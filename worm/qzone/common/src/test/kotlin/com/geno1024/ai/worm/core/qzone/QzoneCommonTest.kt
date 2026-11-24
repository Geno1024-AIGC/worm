package com.geno1024.ai.worm.core.qzone

import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.MediaKind
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QzoneCommonTest {
    @Test
    fun `gtk uses p_skey with known vectors`() {
        assertEquals(5381L, computeGtk(""))
        assertEquals(2090756197L, computeGtk("test"))
        assertEquals(1900538817L, computeGtk("abc123"))
        val auth = requireQzoneAuth(CrawlSession(mapOf("skey" to "test", "p_skey" to "abc123", "uin" to "o00123")))
        assertEquals(1900538817L, auth.gtk)
        assertEquals("123", auth.loginUin)
        assertThrows<Exception> { requireQzoneAuth(CrawlSession(mapOf("uin" to "o123"))) }
    }

    @Test
    fun `message list parses jsonp and auth errors`() {
        val body = """
            _preloadCallback({"code":0,"subcode":0,"message":"","total":42,"hasMore":true,
              "msglist":[{"tid":"t1","content":"hello","created_time":1700000000}]});
        """.trimIndent()
        val page = parseMsgListResponse(body)
        assertEquals(0, page.code)
        assertEquals(42, page.total)
        assertTrue(page.hasMore)
        assertEquals("t1", page.messages.getJSONObject(0).optString("tid"))
        assertTrue(isAuthError(-3000, 0))
        assertFalse(isAuthError(0, 0))
        assertEquals("123", normalizeUin("o00123"))
    }

    @Test
    fun `message media handles images video repost and cover only`() {
        val json = """
            {"tid":"t1","created_time":1700000000,
             "pic":[{"url":"https://p.qzone.com/a.jpg","burl":"https://p.qzone.com/big.jpg"},
                    {"is_video":1,"url":"https://p.qzone.com/poster.jpg"},
                    {"is_video":1,"url":"https://p.qzone.com/play.mp4"}],
             "video":[{"raw_url":"https://v.qzone.com/v.mp4","url":"https://v.qzone.com/cover.jpg"}],
             "rt_con":{"pic":[{"url":"https://p.qzone.com/repost.png"}]}}
        """.trimIndent()
        val assets = extractMessageMedia(org.json.JSONObject(json))
        val byUrl = assets.associateBy { it.url }
        assertEquals(MediaKind.IMAGE, byUrl["https://p.qzone.com/big.jpg"]?.kind)
        assertTrue(byUrl["https://p.qzone.com/poster.jpg"]!!.isCover)
        assertEquals(MediaKind.VIDEO, byUrl["https://p.qzone.com/play.mp4"]?.kind)
        assertEquals(MediaKind.VIDEO, byUrl["https://v.qzone.com/v.mp4"]?.kind)
        assertEquals(MediaKind.IMAGE, byUrl["https://p.qzone.com/repost.png"]?.kind)
        assertEquals(1700000000L, byUrl["https://p.qzone.com/big.jpg"]?.createdAt)
        assertEquals(5, assets.size)
    }

    @Test
    fun `credentials roundtrip`(@TempDir temp: File) {
        val file = File(temp, "nested/credentials.json")
        val session = CrawlSession(mapOf("p_skey" to "secret", "uin" to "o123"))
        saveQzoneCredentials(file, session, "123")
        assertEquals("secret", loadQzoneCredentials(file)!!.cookies["p_skey"])
        assertEquals("123", loadQzoneCredentials(file)!!.loginUin)
    }

    @Test
    fun `album list parser handles all three shapes`() {
        val body = """
            shine0_Callback({"code":0,"subcode":0,"data":{
              "albumListModeSort":[{"id":"1","name":"Trip","total":2}],
              "albumListModeClass":[{"albumList":[{"id":"2","name":"Family","total":1}]}],
              "albumList":[{"id":"3","name":"Hidden","allowAccess":0,"total":1}]
            }});
        """.trimIndent()
        val albums = parseAlbumListResponse(body).albums
        assertEquals(listOf("Trip", "Family"), albums.map { it.name })
        val fallback = parseAlbumListResponse("""{"code":0,"data":{"albumList":[{"id":"3","name":"Hidden","allowAccess":0}]}}""")
        assertEquals(listOf("Hidden"), fallback.albums.map { it.name })
        assertFalse(fallback.albums.single().allowAccess)
        assertTrue(parseAlbumListResponse("""{"code":-3000}""").albums.isEmpty())
        assertTrue(buildAlbumListUrl("123", 42).contains("hostUin=123"))
        assertTrue(buildPhotoListUrl("123", 42, "V_abc", 0, 100).contains("topicId=V_abc"))
    }

    @Test
    fun `photo list parser reads outstyle json`() {
        val body = """
            {"code":0,"data":{"totalInAlbum":2,"photoList":[
              {"lloc":"p1","raw":"https://p.qzone.com/1.jpg"},
              {"lloc":"p2","raw":"https://v.qzone.com/2.mp4"}
            ]}};
        """.trimIndent()
        val page = parsePhotoListResponse(body)
        assertEquals(2, page.totalInAlbum)
        assertEquals(2, page.photos.length())
        assertEquals(0, parsePhotoListResponse("""{"code":-3000}""").photos.length())
    }

    @Test
    fun `album photo never stores a cover as mp4`() {
        val covered = org.json.JSONObject("""{"raw":"https://p.qzone.com/cover.jpg","is_video":1}""")
        val assets = extractAlbumPhotoMedia(covered, "My Album")
        assertEquals(1, assets.size)
        assertEquals(MediaKind.IMAGE, assets[0].kind)
        assertTrue(assets[0].isCover)
        assertEquals("albums/My_Album", assets[0].relativeDir)

        val playable = org.json.JSONObject("""{"raw":"https://v.qzone.com/a.mp4","url":"https://p.qzone.com/c.jpg"}""")
        val video = extractAlbumPhotoMedia(playable, "Trip")
        assertEquals(MediaKind.VIDEO, video.single().kind)
        assertEquals("albums/Trip", video.single().relativeDir)
    }
}
