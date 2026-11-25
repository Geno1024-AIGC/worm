package com.geno1024.ai.worm.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

data class HttpResponse(
    val status: Int,
    val body: String,
    val finalUrl: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * The only network primitive crawlers use. Implemented with HttpURLConnection so
 * the very same classes run in a JVM CLI and in Android (java.net.http is not
 * available on older Android API levels).
 */
interface HttpPortal {
    fun get(
        url: String,
        session: CrawlSession,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse

    /** Streams into [target] via a sibling `.part`; returns the final file. */
    fun download(
        url: String,
        session: CrawlSession,
        referer: String?,
        target: File,
        onBytes: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ): File
}

/**
 * Serial, polite portal. One instance is not meant to be shared across threads.
 * 429/5xx are retried a bounded number of times; per-host delay is honoured
 * even on redirects because the delay happens before the request is sent.
 */
class JdkHttpPortal(
    val userAgent: String = DEFAULT_USER_AGENT,
    val delayMs: Long = 800L,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    val maxRetries: Int = 2,
) : HttpPortal {
    private val lastHit = ConcurrentHashMap<String, Long>()

    override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>): HttpResponse {
        val uri = parse(url)
        val body = withConnection(uri, session, referer, extraHeaders) { connection ->
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(status, text, connection.url.toString(), connection.headerFields)
        }
        return body
    }

    override fun download(
        url: String,
        session: CrawlSession,
        referer: String?,
        target: File,
        onBytes: (bytes: Long, total: Long?) -> Unit,
    ): File {
        val uri = parse(url)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        try {
            withConnection(uri, session, referer, emptyMap()) { connection ->
                val status = connection.responseCode
                if (status !in 200..299) throw CrawlException("HTTP $status for $url")
                val total = connection.contentLengthLong.takeIf { it > 0 }
                var bytes = 0L
                connection.inputStream.use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytes += read
                            onBytes(bytes, total)
                        }
                    }
                }
                if (total != null && bytes != total) {
                    throw CrawlException("download size mismatch for $url: $bytes != $total")
                }
            }
            if (!part.renameTo(target)) {
                // Some filesystems refuse rename across mounts; copy is still safe
                // because the final name is only visible after a complete copy.
                part.copyTo(target, overwrite = true)
                if (!part.delete()) part.deleteOnExit()
            }
            return target
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun <T> withConnection(
        uri: URI,
        session: CrawlSession,
        referer: String?,
        extraHeaders: Map<String, String>,
        block: (HttpURLConnection) -> T,
    ): T {
        var attempt = 0
        while (true) {
            waitFor(uri.host)
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/json,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Accept-Encoding", "identity")
                if (session.cookieHeader.isNotBlank()) setRequestProperty("Cookie", session.cookieHeader)
                referer?.let { setRequestProperty("Referer", it) }
                extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    if (attempt >= maxRetries) throw CrawlException("network error for $uri: ${e.message}", e)
                    attempt++
                    continue
                }
                if (status in RETRYABLE && attempt < maxRetries) {
                    attempt++
                    Thread.sleep(400L * attempt)
                    continue
                }
                return block(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun waitFor(host: String) {
        val previous = lastHit[host]
        if (previous != null) {
            val remaining = delayMs - (System.nanoTime() - previous) / 1_000_000
            if (remaining > 0) Thread.sleep(remaining)
        }
        lastHit[host] = System.nanoTime()
    }

    private fun parse(url: String): URI = try {
        URI.create(url).also {
            require(it.scheme == "http" || it.scheme == "https") { "unsupported scheme" }
        }
    } catch (e: Exception) {
        throw CrawlException("invalid URL: $url", e)
    }

    companion object {
        // No bot token here: sites that spot a crawler can answer with a captcha SVG
        // instead of JSON (the app's login WebView used to append one too).
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
        private val RETRYABLE = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
