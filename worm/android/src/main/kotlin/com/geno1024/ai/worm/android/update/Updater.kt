package com.geno1024.ai.worm.android.update

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Channel A (AGENTS.md / RULES/VERSION.md): metadata comes only from GitHub's API.
 * Mirrors transport the APK and nothing else — a mirror that could edit the release
 * feed could also lie about which build is current.
 */
object Updater {
    const val OWNER = "Geno1024-AIGC"
    const val REPO = "worm"

    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
    private const val FALLBACK_ASSET = "worm-canary.apk"
    private const val USER_AGENT = "worm-android"
    private const val BUFFER_BYTES = 64 * 1024

    data class Source(val id: String, val label: String, val prefix: String)

    val PRESETS = listOf(
        Source("github", "GitHub", "https://github.com"),
        Source("ghfast", "ghfast.top", "https://ghfast.top/https://github.com"),
        Source("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/https://github.com"),
        Source("ghproxy", "ghproxy.net", "https://ghproxy.net/https://github.com"),
    )

    fun sources(customPrefix: String? = null): List<Source> {
        val custom = customPrefix?.trim()?.takeIf { prefix ->
            prefix.startsWith("https://") || prefix.startsWith("http://")
        }?.let { Source("custom", "自定义镜像", it.trimEnd('/')) }
        return PRESETS + listOfNotNull(custom)
    }

    fun sourceFrom(id: String?, customPrefix: String?): Source =
        sources(customPrefix).firstOrNull { it.id == id } ?: PRESETS.first()

    /** Selected source first, then every other preset; duplicates are removed by URL later. */
    fun orderedSources(selected: Source, customPrefix: String?): List<Source> {
        val all = sources(customPrefix)
        return listOfNotNull(all.firstOrNull { it.id == selected.id }) +
            all.filter { it.id != selected.id }
    }

    /**
     * A five-part build stamp. Only [env] votes in ordering: `pack` changes on every
     * rebuild of one commit, and base must not make a newer capability look older.
     */
    data class Version(
        val base: String,
        val env: Int,
        val pack: Int,
        val sha: String,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int = env.compareTo(other.env)
        override fun toString(): String = "$base.$env.$pack.$sha"
    }

    /** Fixed shape, arbitrary base text: never hard-code `0.1.` or `0.2.`. */
    fun parseVersion(text: String): Version? {
        val match = Regex("""(\d+\.\d+)\.(\d+)\.(\d+)\.([0-9a-fA-F]{8})""").find(text)
            ?: return null
        return Version(
            base = match.groupValues[1],
            env = match.groupValues[2].toIntOrNull() ?: return null,
            pack = match.groupValues[3].toIntOrNull() ?: return null,
            sha = match.groupValues[4].lowercase(),
        )
    }

    data class Release(
        val tag: String,
        val name: String,
        val version: Version?,
        val apkName: String,
        val apkSize: Long,
    )

    /** Asset names are appended to a URL, so they get a strict whitelist. */
    fun safeAssetName(name: String?): String? {
        if (name.isNullOrBlank() || name.length > 128) return null
        if (!name.matches(Regex("[A-Za-z0-9._-]+"))) return null
        return name.takeIf { it.endsWith(".apk", ignoreCase = true) }
    }

    /** Pure parser: a GitHub Releases JSON body in, installable canary releases out. */
    fun parseReleases(body: String): List<Release> {
        val root = JSONArray(body)
        val result = mutableListOf<Release>()
        for (i in 0 until root.length()) {
            val release = root.optJSONObject(i) ?: continue
            if (!release.optBoolean("prerelease", false)) continue

            var apkName: String? = null
            var apkSize = 0L
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            if (apkName == null) continue

            val tag = release.optString("tag_name")
            if (tag.isBlank()) continue
            val name = release.optString("name").takeIf { it.isNotBlank() } ?: tag
            result += Release(
                tag = tag,
                name = name,
                version = parseVersion(name) ?: parseVersion(tag) ?: parseVersion(apkName),
                apkName = safeAssetName(apkName) ?: FALLBACK_ASSET,
                apkSize = apkSize,
            )
        }
        return result
    }

    fun fetchReleases(): List<Release> = parseReleases(get(API_URL))

    /** `null` when there truly is no newer build; equal stamps are the same build. */
    fun newer(current: Version?, releases: List<Release>): Release? =
        releases.mapNotNull { release -> release.version?.let { it to release } }
            .filter { (version, _) -> current == null || version > current }
            .maxByOrNull { (version, _) -> version }
            ?.second

    fun downloadUrl(source: Source, release: Release): String =
        "${source.prefix}/$OWNER/$REPO/releases/download/${release.tag}/${release.apkName}"

    /** Tries the chosen source, then the presets; a failed mirror never leaves a partial APK. */
    fun download(
        sources: List<Source>,
        release: Release,
        target: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val urls = sources.map { downloadUrl(it, release) }.distinct()
        var lastFailure: Exception? = null
        for (url in urls) {
            try {
                downloadOne(url, target, expectedSize, onProgress)
                return target
            } catch (e: Exception) {
                lastFailure = e
                File(target.parentFile, "${target.name}.part").delete()
                if (target.exists()) target.delete()
            }
        }
        throw IllegalStateException("所有下载源均失败：${lastFailure?.message ?: "未知原因"}")
    }

    private fun downloadOne(url: String, target: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.android.package-archive")
        }
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            val status = connection.responseCode
            check(status in 200..299) { "HTTP $status from ${URL(url).host}" }
            val headerLength = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total = expectedSize.takeIf { it > 0 } ?: headerLength
            target.parentFile?.mkdirs()
            var bytes = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        onProgress(bytes, total)
                    }
                }
            }
            check(expectedSize <= 0 || bytes == expectedSize) {
                "download size mismatch: $bytes != $expectedSize"
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            check(status in 200..299) { "HTTP $status from ${URL(url).host}" }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
