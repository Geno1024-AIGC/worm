package com.geno1024.ai.worm.android.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Pure-parser tests: no network, no Android runtime. */
class UpdaterTest {

    @Test
    fun `parses five-part stamps without hard-coding the base`() {
        val parsed = Updater.parseVersion("Worm 2.7.31.4.DEADBEEF canary")
        assertNotNull(parsed)
        assertEquals("2.7", parsed!!.base)
        assertEquals(31, parsed.env)
        assertEquals(4, parsed.pack)
        assertEquals("deadbeef", parsed.sha)
        assertEquals("2.7.31.4.deadbeef", parsed.toString())
    }

    @Test
    fun `rejects stamps that do not have the fixed shape`() {
        assertNull(Updater.parseVersion("Worm canary"))
        assertNull(Updater.parseVersion("0.1.12.3.abc"))
        assertNull(Updater.parseVersion("0.1.12.3.1234567g"))
    }

    @Test
    fun `reads only published prereleases that carry an apk`() {
        val body = """
            [
              {
                "tag_name": "v1",
                "name": "Stable 0.1.9.0.aaaaaaaa",
                "prerelease": false,
                "assets": [{"name": "worm-0.1.9.0.aaaaaaaa.apk", "size": 100}]
              },
              {
                "tag_name": "canary",
                "name": "Worm 0.1.13.2.568ac2f5 canary",
                "prerelease": true,
                "assets": [
                  {"name": "notes.txt", "size": 4},
                  {"name": "worm-0.1.13.2.568ac2f5.apk", "size": 4096}
                ]
              },
              {
                "tag_name": "canary-old",
                "name": "no apk here",
                "prerelease": true,
                "assets": [{"name": "notes.txt", "size": 4}]
              }
            ]
        """.trimIndent()

        val releases = Updater.parseReleases(body)
        assertEquals(1, releases.size)
        assertEquals("0.1.13.2.568ac2f5", releases[0].version.toString())
        assertEquals("worm-0.1.13.2.568ac2f5.apk", releases[0].apkName)
        assertEquals(4096L, releases[0].apkSize)
    }

    @Test
    fun `version falls back from name to tag to asset`() {
        val body = """
            [
              {
                "tag_name": "build-7.8.9.1.abcdef01",
                "name": "",
                "prerelease": true,
                "assets": [{"name": "worm.apk", "size": 10}]
              },
              {
                "tag_name": "canary",
                "name": "nightly",
                "prerelease": true,
                "assets": [{"name": "worm-3.4.5.6.1234abcd.apk", "size": 10}]
              }
            ]
        """.trimIndent()

        val releases = Updater.parseReleases(body)
        assertEquals(2, releases.size)
        assertEquals("7.8.9.1.abcdef01", releases[0].version.toString())
        assertEquals("3.4.5.6.1234abcd", releases[1].version.toString())
    }

    @Test
    fun `unsafe asset names fall back to a fixed file name`() {
        assertNull(Updater.safeAssetName("../secret.apk"))
        assertNull(Updater.safeAssetName("notes.txt"))
        assertNull(Updater.safeAssetName("a".repeat(129) + ".apk"))
        assertEquals("worm-canary.apk", Updater.safeAssetName("bad name.apk") ?: "worm-canary.apk")

        val body = """
            [{"tag_name":"canary","name":"Worm 0.1.4.0.aaaaaaaa canary","prerelease":true,
              "assets":[{"name":"../evil.apk","size":10}]}]
        """.trimIndent()
        assertEquals("worm-canary.apk", Updater.parseReleases(body).single().apkName)
    }

    @Test
    fun `ordering votes on env only`() {
        val current = checkNotNull(Updater.parseVersion("0.1.12.9.aaaaaaaa"))
        val older = release(env = 11, base = "9.9")
        val newer = release(env = 13, base = "0.1", pack = 0)
        assertEquals("0.1.13.0.abcdef01", Updater.newer(current, listOf(older, newer))!!.version.toString())

        val sameEnvMorePack = release(env = 12, base = "9.9", pack = 50)
        assertNull(Updater.newer(current, listOf(sameEnvMorePack)))
        assertNull(Updater.newer(current, listOf(release(env = 12))))
    }

    @Test
    fun `selected source is tried first and custom is available`() {
        val selected = Updater.PRESETS[2]
        val ordered = Updater.orderedSources(selected, "https://proxy.example/https://github.com")
        assertEquals("gh-proxy", ordered.first().id)
        assertEquals(Updater.PRESETS.size + 1, ordered.size)
        assertEquals("custom", ordered.last().id)
        assertEquals("https://github.com/Geno1024-AIGC/worm/releases/download/canary/app.apk",
            Updater.downloadUrl(Updater.PRESETS.first(), release(env = 1)))
    }

    @Test
    fun `custom prefix must be http or https`() {
        assertEquals(4, Updater.sources("ftp://bad").size)
        assertEquals(5, Updater.sources(" https://ok.example/ ").size)
        assertEquals("https://ok.example", Updater.sources("https://ok.example/").last().prefix)
        assertFalse(Updater.sources(null).any { it.id == "custom" })
        assertTrue(Updater.sources("https://ok.example").any { it.id == "custom" })
    }

    @Test
    fun `a padded html page is not accepted as an apk`(@TempDir temp: File) {
        val apk = File(temp, "update.apk")
        apk.writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0))
        assertTrue(Updater.looksLikeApk(apk))

        val page = File(temp, "page.apk")
        page.writeText("<html>" + " ".repeat(1024) + "</html>")
        assertFalse(Updater.looksLikeApk(page))

        val empty = File(temp, "empty.apk")
        empty.writeBytes(byteArrayOf())
        assertFalse(Updater.looksLikeApk(empty))
        assertFalse(Updater.looksLikeApk(File(temp, "missing.apk")))
    }

    private fun release(env: Int, base: String = "0.1", pack: Int = 1, sha: String = "abcdef01") =
        Updater.Release(
            tag = "canary",
            name = "$base.$env.$pack.$sha",
            version = Updater.parseVersion("$base.$env.$pack.$sha"),
            apkName = "app.apk",
            apkSize = 10L,
        )
}
