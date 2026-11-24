package com.geno1024.ai.worm.qzone.album

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

class AlbumCrawlerTest {
    private class FakeHttp(
        private val albumBody: String,
        private val photoBody: String,
    ) : HttpPortal {
        val urls = mutableListOf<String>()

        override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>): HttpResponse {
            urls += url
            return HttpResponse(200, if ("fcg_list_album_v3" in url) albumBody else photoBody, url)
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
    fun `crawls accessible albums and keeps videos distinct from covers`(@TempDir temp: File) = runBlocking {
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
        val http = FakeHttp(albumBody, photoBody)
        val crawler = AlbumCrawler(http, File(temp, "cache"), downloadDelayMs = 0)
        val report = crawler.collect(CrawlRequest("12345"), session, DirectoryMediaSink(File(temp, "media")))

        assertEquals(3, report.items.size)
        assertEquals(3, report.saved.size)
        assertEquals(2, http.urls.size)
        val kinds = report.items.map { (it as CollectedItem.Media).asset.kind }
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.IMAGE), kinds)
        assertTrue(report.items[2].let { it as CollectedItem.Media }.asset.isCover)
        assertTrue(File(temp, "media/qzone/12345/album/p1.jpg").isFile)
        assertTrue(File(temp, "media/qzone/12345/album/p2.mp4").isFile)
    }
}
