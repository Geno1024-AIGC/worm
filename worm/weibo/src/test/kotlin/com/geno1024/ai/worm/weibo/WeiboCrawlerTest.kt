package com.geno1024.ai.worm.weibo

import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.HttpResponse
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WeiboCrawlerTest {
    private val firstPage = """
        {"ok":1,"data":{"cardlistInfo":{"total":20},"cards":[
          {"card_type":9,"mblog":{"id":"w1","created_at":"1700000000","text":"<p>photo</p>",
            "pics":[{"url":"https://ww2.sinaimg.cn/orj360/aaa.jpg",
                     "large":{"url":"https://ww2.sinaimg.cn/large/aaa.jpg"}}]}},
          {"card_type":9,"mblog":{"id":"w2","created_at":"1700000000","text":"video",
            "page_info":{"type":"video","media_info":{"stream_url_hd":"https://video.weibo.com/w2.mp4"}}}}
        ]}}
    """.trimIndent()
    private val emptyPage = """{"ok":1,"data":{"cards":[]}}"""

    private class FakeHttp(private val bodies: List<String>) : HttpPortal {
        val urls = mutableListOf<String>()
        var calls = 0
        override fun get(
            url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>,
        ): HttpResponse {
            urls += url
            return HttpResponse(200, bodies.getOrNull(calls++) ?: bodies.last(), url)
        }
        override fun download(
            url: String, session: CrawlSession, referer: String?, target: File,
            onBytes: (Long, Long?) -> Unit,
        ): File {
            target.writeBytes("payload".toByteArray())
            return target
        }
    }

    private fun crawler(http: HttpPortal, temp: File) =
        WeiboCrawler(http, File(temp, "cache"), downloadDelayMs = 0)

    @Test
    fun `images and videos are saved under the uid`(@TempDir temp: File) = runBlocking {
        val http = FakeHttp(listOf(firstPage, emptyPage))
        val report = crawler(http, temp).collect(
            CrawlRequest("https://weibo.com/u/12345", includeText = true),
            CrawlSession(mapOf("SUB" to "token")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertEquals("weibo.user", report.crawlerId)
        assertEquals("12345", report.subject)
        assertTrue(report.warnings.isEmpty())
        assertEquals(4, report.items.size) // 1 image + 1 video + 2 text
        assertEquals(2, report.saved.size)
        assertTrue(http.urls[0].contains("containerid=10760312345"))
        assertEquals(2, http.urls.size)
        assertTrue(report.saved.any { it.name.contains("w2") || it.asset.kind == MediaKind.VIDEO })
        assertTrue(File(temp, "media/weibo/12345/image").isDirectory)
        assertTrue(File(temp, "media/weibo/12345/video").isDirectory)
        assertEquals(1_700_000_000L, report.newestItemAt)
        assertTrue(report.items.any { it is CollectedItem.Text && it.body == "photo" })
    }

    @Test
    fun `incremental crawl skips posts before the watermark`(@TempDir temp: File) = runBlocking {
        val report = crawler(FakeHttp(listOf(firstPage)), temp).collect(
            CrawlRequest("12345", since = 1_700_000_001L),
            CrawlSession(mapOf("SUB" to "token")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertTrue(report.items.isEmpty())
        assertEquals(1_700_000_000L, report.newestItemAt)
    }

    @Test
    fun `seen ids are not replayed in the overlap`(@TempDir temp: File) = runBlocking {
        val report = crawler(FakeHttp(listOf(firstPage)), temp).collect(
            CrawlRequest("12345", since = 1_699_000_000L, seenIds = setOf("https://ww2.sinaimg.cn/large/aaa.jpg")),
            CrawlSession(mapOf("SUB" to "token")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertTrue(report.items.none { it is CollectedItem.Media && it.asset.url.endsWith("aaa.jpg") })
        assertTrue(report.items.any { it is CollectedItem.Media && it.asset.kind == MediaKind.VIDEO })
    }

    @Test
    fun `truncated list response is refetched once`(@TempDir temp: File) = runBlocking {
        // Ends in `}` but never closes the outer objects — the exact shape Weibo sent.
        val truncated = """{"ok":1,"data":{"cardlistInfo":{"contain}"""
        val http = FakeHttp(listOf(truncated, firstPage, emptyPage))
        val report = crawler(http, temp).collect(
            CrawlRequest("12345"), CrawlSession(mapOf("SUB" to "token")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertEquals(2, report.items.size) // one image + one video, no text in this request
        assertEquals(2, report.saved.size)
        assertTrue(report.warnings.isEmpty())
        assertEquals(3, http.urls.size)
    }

    @Test
    fun `persistent truncation reports a readable warning`(@TempDir temp: File) = runBlocking {
        val truncated = """{"ok":1,"data":{"cardlistInfo":{"contain}"""
        val report = crawler(FakeHttp(listOf(truncated)), temp).collect(
            CrawlRequest("12345"), CrawlSession(mapOf("SUB" to "token")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertTrue(report.items.isEmpty())
        assertTrue(report.warnings.any { it.contains("响应被截断") })
    }

    @Test
    fun `auth failures become warnings instead of empty success`(@TempDir temp: File) = runBlocking {
        val http = object : HttpPortal by FakeHttp(listOf("")) {
            override fun get(
                url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>,
            ) = HttpResponse(403, "", url)
        }
        val report = crawler(http, temp).collect(
            CrawlRequest("12345"), CrawlSession(mapOf("SUB" to "stale")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertTrue(report.items.isEmpty())
        assertTrue(report.warnings.any { it.contains("需要登录") || it.contains("风控") })
    }
}
