package com.geno1024.ai.worm.qzone.shuoshuo

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
    fun `auth error becomes a warning instead of a silent empty result`(@TempDir temp: File) = runBlocking {
        val http = FakeHttp("""_preloadCallback({"code":-3000,"subcode":0,"message":"login expired"});""")
        val crawler = ShuoshuoCrawler(http, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))
        assertTrue(report.items.isEmpty())
        assertTrue(report.warnings.single().contains("登录态失效"))
    }
}
