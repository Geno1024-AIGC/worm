package com.geno1024.ai.worm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoreTest {
    @Test
    fun `cookies parse and dedupe by name`() {
        val cookies = Cookies.fromHeader("uin=o123; p_skey=abc; skey=old; p_skey=new")
        assertEquals("new", cookies["p_skey"])
        assertEquals("uin=o123; p_skey=new; skey=old", Cookies.toHeader(cookies))
        assertTrue(Cookies.hasLogin(cookies, "p_skey", "skey"))
        assertFalse(Cookies.hasLogin(mapOf("uin" to "1"), "p_skey", "skey"))
    }

    @Test
    fun `netscape cookies accept HttpOnly lines`() {
        val text = """
            # Netscape HTTP Cookie File
            #HttpOnly_.qq.com	TRUE	/	TRUE	0	p_skey	secret
            .qq.com	TRUE	/	FALSE	0	uin	o123
        """.trimIndent()
        val cookies = Cookies.fromNetscape(text)
        assertEquals("secret", cookies["p_skey"])
        assertEquals("o123", cookies["uin"])
    }

    @Test
    fun `jsonp strips callback wrapper`() {
        assertEquals("{\"code\":0}", Jsonp.strip("_preloadCallback({\"code\":0});"))
        assertEquals("[ ]", Jsonp.strip("[ ]"))
    }

    @Test
    fun `media names are sanitised and extensions whitelisted`() {
        val asset = MediaAsset(
            url = "https://example.com/a/photo.png?size=1",
            kind = MediaKind.IMAGE,
            suggestedName = "../../evil name",
        )
        assertEquals("evil_name.png", MediaFiles.nameFor(asset))
        val video = MediaAsset("https://example.com/v.mp4", MediaKind.VIDEO)
        assertEquals("mp4", MediaFiles.extensionOf(video.url, MediaKind.VIDEO))
        val cover = MediaAsset("https://example.com/c.jpg", MediaKind.IMAGE, isCover = true)
        assertEquals("jpg", MediaFiles.extensionOf(cover.url, MediaKind.IMAGE))
    }

    @Test
    fun `html media extracts images and videos without duplicates`() {
        val html = """
            <img src="//cdn.example.com/a.jpg" data-original="https://cdn.example.com/big.jpg">
            <div><img src="/c.png"></div>
            <video src="https://cdn.example.com/v.mp4" poster="https://cdn.example.com/poster.jpg"></video>
        """.trimIndent()
        val assets = HtmlMedia.extract(html, "https://www.example.com/post/1")
        val urls = assets.map { it.url }
        assertEquals(listOf(
            "https://cdn.example.com/big.jpg",
            "https://cdn.example.com/a.jpg",
            "https://www.example.com/c.png",
            "https://cdn.example.com/v.mp4",
            "https://cdn.example.com/poster.jpg",
        ), urls)
        assertTrue(assets.first { it.kind == MediaKind.IMAGE && it.url.endsWith("poster.jpg") }.isCover)
    }

    @Test
    fun `batch download has a fixed total and survives one failed asset`(@TempDir temp: File) {
        val assets = (1..3).map { MediaAsset("https://example.com/$it.jpg", MediaKind.IMAGE, id = "m$it") }
        val http = object : HttpPortal {
            override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>) =
                HttpResponse(200, "", url)

            override fun download(
                url: String,
                session: CrawlSession,
                referer: String?,
                target: File,
                onBytes: (Long, Long?) -> Unit,
            ): File {
                if ("2.jpg" in url) throw java.io.IOException("boom")
                target.writeBytes("x".toByteArray())
                return target
            }
        }
        val downloader = MediaDownloader(http, File(temp, "cache"), delayMs = 0)
        val steps = mutableListOf<Pair<Int, Int>>()
        val failures = mutableListOf<String>()
        val saved = downloader.downloadAll(
            assets,
            CrawlSession(emptyMap()),
            DirectoryMediaSink(File(temp, "media")),
            onProgress = { current, total, _ -> steps += current to total },
            onFailure = { asset, _ -> failures += asset.url },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), steps)
        assertEquals(2, saved.size)
        assertEquals(listOf("https://example.com/2.jpg"), failures)
    }

    @Test
    fun `json output escapes strings`() {
        assertEquals("""{"a":"x\"y","b":[1,true]}""", JsonOutput.value(mapOf("a" to "x\"y", "b" to listOf(1, true))))
    }

    @Test
    fun `directory sink stores under safe relative directory`(@TempDir temp: File) {
        val sink = DirectoryMediaSink(temp)
        val asset = MediaAsset(
            url = "https://example.com/a.jpg",
            kind = MediaKind.IMAGE,
            id = "abc",
            relativeDir = "albums/My Album",
        )
        assertFalse(sink.exists(asset))
        val source = File(temp, "source.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val saved = sink.accept(asset, source)
        assertEquals("abc.jpg", saved.name)
        assertTrue(sink.exists(asset))
        assertEquals(3L, saved.size)
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(temp, "albums/My_Album/abc.jpg").readBytes().toList())
    }
}
