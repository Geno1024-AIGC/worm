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
    fun `captcha svg becomes a readable warning instead of a raw json error`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200"><text>captcha</text></svg>"""
        val msg = parseMsgListResponse(svg)
        assertEquals(-1, msg.code)
        assertEquals(0, msg.messages.length())
        assertTrue(msg.message.contains("SVG"))
        assertFalse(msg.message.contains("illegal character"))

        val albums = parseAlbumListResponse(svg)
        assertEquals(-1, albums.code)
        assertTrue(albums.albums.isEmpty())
        assertTrue(albums.message.contains("SVG"))

        val photos = parsePhotoListResponse(svg)
        assertEquals(-1, photos.code)
        assertEquals(0, photos.photos.length())
        assertTrue(photos.message.contains("SVG"))

        val html = parseMsgListResponse("""<!doctype html><html>login</html>""")
        assertEquals(-1, html.code)
        assertTrue(html.message.contains("HTML"))
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
    fun `qzone playable url3 wins over cover jpg`() {
        // Real h5 payload: video[] uses url3 for the MP4 and url1/pic_url for covers;
        // a video pic carries the same url3 inside video_info.
        val json = """
            {"tid":"t3","created_time":1700000123,
             "pic":[{"is_video":1,"url1":"https://p.qzone.com/cover.jpg",
                     "video_info":{"url3":"https://photovideo.photo.qq.com/clip.mp4"}}],
             "video":[{"video_id":"v1","pic_url":"https://p.qzone.com/cover2.jpg",
                       "url1":"https://p.qzone.com/thumb.jpg",
                       "url3":"https://photovideo.photo.qq.com/clip2.mp4"}]}
        """.trimIndent()
        val assets = extractMessageMedia(org.json.JSONObject(json))
        assertEquals(2, assets.size)
        assertTrue(assets.all { it.kind == MediaKind.VIDEO })
        assertTrue(assets.all { it.extension == "mp4" })
        assertTrue(assets.all { it.createdAt == 1700000123L })
        assertTrue(assets.any { it.url == "https://photovideo.photo.qq.com/clip.mp4" })
        assertTrue(assets.any { it.url == "https://photovideo.photo.qq.com/clip2.mp4" })
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
    }

    @Test
    fun `album urls separate owner from logged in viewer and page the list`() {
        val albumUrl = buildAlbumListUrl("o00123", "o99999", 42, 100, 50)
        assertTrue(albumUrl.contains("hostUin=123"))
        assertTrue(albumUrl.contains("uin=99999"))
        assertTrue(albumUrl.contains("mode=3"))
        assertTrue(albumUrl.contains("sortOrder=1"))
        assertTrue(albumUrl.contains("pageStart=100"))
        assertTrue(albumUrl.contains("pageNum=50"))
        val photoUrl = buildPhotoListUrl("123", "99999", 42, "V_abc", 20, 100)
        assertTrue(photoUrl.contains("hostUin=123"))
        assertTrue(photoUrl.contains("uin=99999"))
        assertTrue(photoUrl.contains("topicId=V_abc"))
        assertTrue(photoUrl.contains("pageStart=20"))
        assertTrue(photoUrl.contains("pageNum=100"))
        assertTrue(photoUrl.startsWith("https://h5.qzone.qq.com/"))
        assertTrue(isAlbumPermissionError(-4009, 0))
        assertTrue(isAlbumPermissionError(0, -10805))
        assertFalse(isAlbumPermissionError(-3000, 0))
    }

    @Test
    fun `album and photo parsers expose paging hints`() {
        val albumBody = """
            shine0_Callback({"code":0,"data":{"albumsInUser":"120","totalInPage":2,
              "nextPageStartModeSort":2,"albumListModeSort":[
              {"id":"1","name":"Trip"},{"id":"2","name":"Family"}]}});
        """.trimIndent()
        val albumPage = parseAlbumListResponse(albumBody)
        assertEquals(120, albumPage.albumsInUser)
        assertEquals(2, albumPage.totalInPage)
        assertEquals(2, albumPage.nextPageStart)
        assertTrue(albumPage.hasMore)

        val photoBody = """
            {"code":0,"data":{"totalInAlbum":300,"totalInPage":100,"nextPageStart":100,
              "hasMore":true,"photoList":[{"lloc":"p1","raw":"https://p.qzone.com/1.jpg"}]}};
        """.trimIndent()
        val photoPage = parsePhotoListResponse(photoBody)
        assertEquals(300, photoPage.totalInAlbum)
        assertEquals(100, photoPage.totalInPage)
        assertEquals(100, photoPage.nextPageStart)
        assertTrue(photoPage.hasMore)
        assertEquals(0, parsePhotoListResponse("""{"code":-4009}""").photos.length())
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
        val assets = extractAlbumPhotoMedia(covered, "qzone/123/My_Album")
        assertEquals(1, assets.size)
        assertEquals(MediaKind.IMAGE, assets[0].kind)
        assertTrue(assets[0].isCover)
        assertEquals("qzone/123/My_Album", assets[0].relativeDir)
        assertEquals("jpg", assets[0].extension)

        val playable = org.json.JSONObject("""{"raw":"https://v.qzone.com/a.mp4","url":"https://p.qzone.com/c.jpg"}""")
        val video = extractAlbumPhotoMedia(playable, "qzone/123/Trip")
        assertEquals(MediaKind.VIDEO, video.single().kind)
        assertEquals("mp4", video.single().extension)
        assertEquals("qzone/123/Trip", video.single().relativeDir)
    }
}
