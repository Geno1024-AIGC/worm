package com.geno1024.ai.worm.zhihu

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

class ZhihuTest {
    private val body = """
        {"data":[
          {"id":"a1","title":"示例","created":1700000000,
           "url":"https://www.zhihu.com/answer/a1",
           "content":"<p>图 <img src=\"https://pic1.zhimg.com/a.jpg\"></p>",
           "video":{"play_url":"https://v.zhihu.com/a.mp4","cover":"https://pic1.zhimg.com/cover.jpg"}}
        ],"paging":{"is_end":true}}
    """.trimIndent()

    private class FakeHttp(private val status: Int, private val body: String) : HttpPortal {
        var lastUrl: String = ""
        override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>): HttpResponse {
            lastUrl = url
            return HttpResponse(status, body, url)
        }
        override fun download(url: String, session: CrawlSession, referer: String?, target: File, onBytes: (Long, Long?) -> Unit): File {
            target.writeBytes("payload".toByteArray())
            return target
        }
    }

    @Test
    fun `subject accepts url and bare token`() {
        assertEquals("geno", normalizeZhihuSubject("https://www.zhihu.com/people/geno/answers"))
        assertEquals("geno", normalizeZhihuSubject("geno"))
        assertTrue(buildZhihuListUrl("geno", ZhihuKind.ANSWER, 20, 10).contains("offset=20"))
    }

    @Test
    fun `list parser extracts html and video media`() {
        val page = parseZhihuListPage(body, ZhihuKind.ANSWER, "https://www.zhihu.com/")
        assertEquals(1, page.contents.size)
        assertEquals(null, page.nextOffset)
        val content = page.contents.single()
        assertEquals("示例", content.title)
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.IMAGE), content.media.map { it.kind })
        assertTrue(content.media.last().isCover)
    }

    @Test
    fun `crawler saves media with unique names`(@TempDir temp: File) = runBlocking {
        val http = FakeHttp(200, body)
        val crawler = ZhihuCrawler(http, File(temp, "cache"), kinds = setOf(ZhihuKind.ANSWER), downloadDelayMs = 0)
        val report = crawler.collect(
            CrawlRequest("https://www.zhihu.com/people/geno"),
            CrawlSession(mapOf("z_c0" to "token")),
            DirectoryMediaSink(File(temp, "media")),
        )
        assertEquals("geno", report.subject)
        assertEquals(3, report.items.size)
        assertEquals(3, report.saved.size)
        assertTrue(http.lastUrl.contains("/members/geno/answers"))
        assertTrue(File(temp, "media/zhihu/geno/image/a1-1.jpg").isFile)
        assertTrue(File(temp, "media/zhihu/geno/video/a1-2.mp4").isFile)
    }

    @Test
    fun `auth failure is visible`(@TempDir temp: File) = runBlocking {
        val crawler = ZhihuCrawler(FakeHttp(401, "{}"), File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("geno"), CrawlSession(emptyMap()), DirectoryMediaSink(File(temp, "media")))
        assertTrue(report.warnings.any { it.contains("风控") })
    }
}
