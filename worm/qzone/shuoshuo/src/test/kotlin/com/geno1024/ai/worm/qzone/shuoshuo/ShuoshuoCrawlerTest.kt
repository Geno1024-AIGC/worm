package com.geno1024.ai.worm.qzone.shuoshuo

import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.HttpResponse
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.qzone.computeGtk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ShuoshuoCrawlerTest {
    private class FakeHttp(private val body: String) : HttpPortal {
        var lastUrl: String = ""
        var calls: Int = 0

        override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>): HttpResponse {
            lastUrl = url
            calls++
            return HttpResponse(200, body, url)
        }

        override fun download(
            url: String,
            session: CrawlSession,
            referer: String?,
            target: File,
            onBytes: (Long, Long?) -> Unit,
        ): File {
            target.writeBytes("payload".toByteArray())
            onBytes(7, 7)
            return target
        }
    }

    private class PagedHttp(private val first: String, private val second: String) : HttpPortal {
        var calls = 0

        override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>): HttpResponse {
            calls++
            return HttpResponse(200, if (calls == 1) first else second, url)
        }

        override fun download(
            url: String,
            session: CrawlSession,
            referer: String?,
            target: File,
            onBytes: (Long, Long?) -> Unit,
        ): File {
            target.writeBytes("payload".toByteArray())
            return target
        }
    }

    private val session = CrawlSession(mapOf("p_skey" to "abc123", "uin" to "o99999"))

    @Test
    fun `crawls one page and saves media`(@TempDir temp: File) = runBlocking {
        val body = """
            _preloadCallback({"code":0,"subcode":0,"total":2,"hasMore":false,"msglist":[
              {"tid":"t1","created_time":1700000000,"pic":[{"url":"https://p.qzone.com/a.jpg"}]},
              {"tid":"t2","video":[{"raw_url":"https://v.qzone.com/b.mp4"}]}
            ]});
        """.trimIndent()
        val http = FakeHttp(body)
        val crawler = ShuoshuoCrawler(http, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))

        assertEquals("12345", report.subject)
        assertEquals(2, report.items.size)
        assertEquals(2, report.saved.size)
        assertEquals(1, http.calls)
        assertTrue(http.lastUrl.contains("g_tk=${computeGtk("abc123")}"))
        assertTrue(http.lastUrl.contains("uin=12345"))
        assertEquals(MediaKind.VIDEO, (report.items[1] as com.geno1024.ai.worm.core.CollectedItem.Media).asset.kind)
        assertEquals("t1-1.jpg", report.saved[0].name)
        assertEquals("t2-1.mp4", report.saved[1].name)
        assertTrue(File(temp, "media/images/t1-1.jpg").isFile)
        assertTrue(File(temp, "media/videos/t2-1.mp4").isFile)
    }

    @Test
    fun `discovers every page before downloading with a determinate total`(@TempDir temp: File) = runBlocking {
        val firstMessages = (1..20).joinToString(",") { i ->
            """{"tid":"t$i","created_time":$i,"pic":[{"url":"https://p.qzone.com/$i.jpg"}]}"""
        }
        val first = """_preloadCallback({"code":0,"subcode":0,"total":100,"hasMore":true,"msglist":[$firstMessages]});"""
        val second = """_preloadCallback({"code":0,"subcode":0,"total":100,"hasMore":false,"msglist":[{"tid":"t21","video":[{"raw_url":"https://v.qzone.com/21.mp4"}]}]});"""
        val http = PagedHttp(first, second)
        val events = mutableListOf<CrawlProgress>()
        val listener = object : CrawlListener {
            override fun onProgress(progress: CrawlProgress) {
                events += progress
            }
        }
        val crawler = ShuoshuoCrawler(http, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(
            CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")), listener,
        )

        assertEquals(2, http.calls)
        assertEquals(21, report.items.size)
        assertEquals(21, report.saved.size)
        val listSteps = events.filter { it.stage == "说说列表" }
        val mediaSteps = events.filter { it.stage == "媒体" }
        assertEquals(listOf(1, 2), listSteps.map { it.current })
        assertEquals((1..21).toList(), mediaSteps.map { it.current })
        assertTrue(mediaSteps.all { it.total == 21 })
        assertTrue(events.indexOf(listSteps.last()) < events.indexOf(mediaSteps.first()))
    }

    @Test
    fun `auth error becomes a warning instead of a silent empty result`(@TempDir temp: File) = runBlocking {
        val http = FakeHttp("""_preloadCallback({"code":-3000,"subcode":0,"message":"login expired"});""")
        val crawler = ShuoshuoCrawler(http, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))
        assertTrue(report.items.isEmpty())
        assertTrue(report.warnings.single().contains("登录态失效"))
    }
}
