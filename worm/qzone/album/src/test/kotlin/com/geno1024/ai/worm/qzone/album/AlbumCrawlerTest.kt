package com.geno1024.ai.worm.qzone.album

import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlException
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AlbumCrawlerTest {
    private class FakeHttp(private val respond: (String) -> String) : HttpPortal {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>): HttpResponse {
            urls += url
            headers += extraHeaders
            return HttpResponse(200, respond(url), url)
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

    private fun param(url: String, key: String): String =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1) ?: ""

    @Test
    fun `crawls accessible albums with owner and viewer separated`(@TempDir temp: File) = runBlocking {
        val albumBody = """
            {"code":0,"data":{"albumListModeSort":[
              {"id":"V_1","name":"Trip","total":2},
              {"id":"V_2","name":"Private","allowAccess":0,"total":9}
            ]}};
        """.trimIndent()
        val photoBody = """
            {"code":0,"data":{"totalInAlbum":2,"photoList":[
              {"lloc":"p1","raw":"https://p.qzone.com/1.jpg"},
              {"lloc":"p2","raw":"https://v.qzone.com/2.mp4"},
              {"lloc":"p3","raw":"https://p.qzone.com/cover.jpg","is_video":1}
            ]}};
        """.trimIndent()
        val http = FakeHttp { url -> if ("fcg_list_album_v3" in url) albumBody else photoBody }
        val crawler = AlbumCrawler(http, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))

        assertEquals(3, report.items.size)
        assertEquals(3, report.saved.size)
        assertEquals(2, http.urls.size)
        assertEquals("12345", param(http.urls[0], "hostUin"))
        assertEquals("99999", param(http.urls[0], "uin"))
        assertEquals("12345", param(http.urls[1], "hostUin"))
        assertEquals("99999", param(http.urls[1], "uin"))
        assertEquals("3", param(http.urls[0], "mode"))
        assertTrue(http.headers.all { it["Origin"] == "https://user.qzone.qq.com" })
        val kinds = report.items.map { (it as CollectedItem.Media).asset.kind }
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.IMAGE), kinds)
        assertTrue(report.items[2].let { it as CollectedItem.Media }.asset.isCover)
        assertTrue(File(temp, "media/qzone/12345/album/p1.jpg").isFile)
        assertTrue(File(temp, "media/qzone/12345/album/p2.mp4").isFile)
    }

    @Test
    fun `pages through album list and each album photos`(@TempDir temp: File) = runBlocking {
        val photo = { id: String, url: String ->
            """{"lloc":"$id","raw":"$url"}"""
        }
        val http = FakeHttp { url ->
            when {
                "fcg_list_album_v3" in url && param(url, "pageStart") == "0" -> """
                    {"code":0,"data":{"albumsInUser":3,"totalInPage":1,"nextPageStart":2,
                      "albumListModeSort":[{"id":"A","name":"A"}]}};
                """.trimIndent()
                "fcg_list_album_v3" in url -> """
                    {"code":0,"data":{"albumsInUser":3,"totalInPage":1,
                      "albumListModeSort":[{"id":"B","name":"B"}]}};
                """.trimIndent()
                param(url, "topicId") == "A" && param(url, "pageStart") == "0" -> """
                    {"code":0,"data":{"totalInAlbum":2,"totalInPage":1,"nextPageStart":1,
                      "photoList":[${photo("a1", "https://p.qzone.com/a1.jpg")}]}};
                """.trimIndent()
                param(url, "topicId") == "A" -> """
                    {"code":0,"data":{"totalInAlbum":2,"totalInPage":1,
                      "photoList":[${photo("a2", "https://p.qzone.com/a2.jpg")}]}};
                """.trimIndent()
                else -> """
                    {"code":0,"data":{"totalInAlbum":1,"totalInPage":1,
                      "photoList":[${photo("b1", "https://p.qzone.com/b1.jpg")}]}};
                """.trimIndent()
            }
        }
        val crawler = AlbumCrawler(http, File(temp, "cache"), pageSize = 1, downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))

        assertEquals(3, report.items.size)
        assertEquals(3, report.saved.size)
        assertEquals(5, http.urls.size)
        assertEquals(listOf("0", "2"), http.urls.filter { "fcg_list_album_v3" in it }.map { param(it, "pageStart") })
        assertEquals(
            listOf("0", "1", "0"),
            http.urls.filter { "cgi_list_photo" in it }.map { param(it, "pageStart") },
        )
        assertTrue(File(temp, "media/qzone/12345/album/a2.jpg").isFile)
        assertTrue(File(temp, "media/qzone/12345/album/b1.jpg").isFile)
    }

    @Test
    fun `permission errors are warnings and missing viewer fails clearly`(@TempDir temp: File) = runBlocking {
        val denied = FakeHttp { """{"code":-4009,"message":"no access"}""" }
        val crawler = AlbumCrawler(denied, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))
        assertEquals(0, report.items.size)
        assertTrue(report.warnings.any { "没有权限" in it && "-4009" in it }, report.warnings.toString())
        assertTrue(report.warnings.none { "登录态失效" in it })

        val photoDenied = FakeHttp { url ->
            if ("fcg_list_album_v3" in url) """{"code":0,"data":{"albumListModeSort":[{"id":"A","name":"A"}]}}"""
            else """{"code":-10805,"message":"album denied"}"""
        }
        val photoReport = AlbumCrawler(photoDenied, File(temp, "cache2"), downloadDelayMs = 0)
            .collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media2")))
        assertTrue(photoReport.warnings.any { "没有权限" in it && "-10805" in it }, photoReport.warnings.toString())

        val noViewer = CrawlSession(mapOf("p_skey" to "abc123"))
        assertThrows<CrawlException> {
            runBlocking {
                crawler.collect(CrawlRequest("12345"), noViewer, DirectoryMediaSink(File(temp, "media3")))
            }
        }
    }
}
