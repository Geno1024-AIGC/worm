package com.geno1024.ai.worm.facebook

import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.HttpResponse
import com.geno1024.ai.worm.core.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FacebookCrawlerTest {
    private val graphPhotos = """
        {"data":[{"id":"p1","created_time":"2026-07-21T13:03:20+0000","name":"trip",
          "images":[{"source":"https://scontent.xx.fbcdn.net/v/large.jpg","width":2048,"height":1536}]}]}
    """.trimIndent()
    private val graphVideos = """
        {"data":[{"id":"v1","created_time":"2026-07-21T13:00:00+0000","title":"clip",
          "source":"https://video.xx.fbcdn.net/v/v1.mp4",
          "picture":"https://scontent.xx.fbcdn.net/v/cover.jpg"}]}
    """.trimIndent()
    private val cookiePage = """
        <html><body>
        <img src="https://scontent.xx.fbcdn.net/v/t1/aaa_n.jpg?stp=1"/>
        <script>{"playable_url":"https:\/\/video.xx.fbcdn.net\/v\/clip.mp4"}</script>
        </body></html>
    """.trimIndent()

    private class Routes(private val routes: Map<String, String>) : HttpPortal {
        val urls = mutableListOf<String>()
        override fun get(
            url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>,
        ): HttpResponse {
            urls += url
            val body = routes.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value.orEmpty()
            return HttpResponse(200, body, url)
        }
        override fun download(
            url: String, session: CrawlSession, referer: String?, target: File,
            onBytes: (Long, Long?) -> Unit,
        ): File {
            target.writeBytes("payload".toByteArray())
            return target
        }
    }

    private fun crawler(http: HttpPortal, temp: File, mode: FacebookMode = FacebookMode.AUTO) =
        FacebookCrawler(http, File(temp, "cache"), mode = mode, downloadDelayMs = 0)

    @Test
    fun `graph token collects photos and videos into separate folders`(@TempDir temp: File) = runBlocking {
        val http = Routes(mapOf(
            "https://graph.facebook.com/v19.0/zuck/photos" to graphPhotos,
            "https://graph.facebook.com/v19.0/zuck/videos" to graphVideos,
        ))
        val report = crawler(http, temp).collect(
            CrawlRequest("https://www.facebook.com/zuck", includeText = true),
            CrawlSession(mapOf("access_token" to "EAACtoken")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertEquals("facebook.user", report.crawlerId)
        assertEquals("zuck", report.subject)
        assertTrue(report.warnings.isEmpty())
        assertEquals(5, report.items.size) // photo image + photo text + video + cover + video text
        assertEquals(3, report.saved.size)
        assertTrue(File(temp, "media/facebook/zuck/image").isDirectory)
        assertTrue(File(temp, "media/facebook/zuck/video").isDirectory)
        assertEquals(1_784_639_000L, report.newestItemAt)
        assertTrue(report.items.any { it is CollectedItem.Text && it.body == "clip" })
    }

    @Test
    fun `expired token falls back to the cookie page`(@TempDir temp: File) = runBlocking {
        val http = Routes(mapOf(
            "https://graph.facebook.com/v19.0/zuck/photos" to
                """{"error":{"code":190,"message":"Invalid OAuth access token"}}""",
            "https://m.facebook.com/zuck/photos" to cookiePage,
        ))
        val report = crawler(http, temp).collect(
            CrawlRequest("zuck"),
            CrawlSession(mapOf("access_token" to "stale")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertEquals(2, report.items.size)
        assertEquals(2, report.saved.size)
        assertTrue(report.warnings.any { it.contains("已回退网页 Cookie") })
        assertTrue(http.urls.any { it.startsWith("https://m.facebook.com/zuck/photos") })
        assertTrue(report.items.any { it is CollectedItem.Media && it.asset.kind == MediaKind.VIDEO })
    }

    @Test
    fun `explicit graph mode does not silently fall back`(@TempDir temp: File) = runBlocking {
        val http = Routes(mapOf(
            "https://graph.facebook.com/v19.0/zuck/photos" to
                """{"error":{"code":190,"message":"Invalid OAuth access token"}}""",
            "https://m.facebook.com/zuck/photos" to cookiePage,
        ))
        val report = crawler(http, temp, FacebookMode.GRAPH).collect(
            CrawlRequest("zuck"),
            CrawlSession(mapOf("access_token" to "stale")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertTrue(report.items.isEmpty())
        assertTrue(report.warnings.any { it.contains("access_token") })
        assertTrue(http.urls.none { it.startsWith("https://m.facebook.com") })
    }

    @Test
    fun `login page is reported instead of an empty crawl`(@TempDir temp: File) = runBlocking {
        val http = Routes(mapOf(
            "https://m.facebook.com/zuck/photos" to """<form id="login_form"></form>""",
        ))
        val report = crawler(http, temp, FacebookMode.COOKIE).collect(
            CrawlRequest("zuck"), CrawlSession(mapOf("cuser" to "123")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertTrue(report.items.isEmpty())
        assertTrue(report.warnings.any { it.contains("Cookie 已失效") })
    }
}
