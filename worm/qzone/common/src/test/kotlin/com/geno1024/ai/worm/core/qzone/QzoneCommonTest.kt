package com.geno1024.ai.worm.core.qzone

import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.MediaKind
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
