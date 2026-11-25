package com.geno1024.ai.worm.facebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FacebookApiTest {
    private val photosBody = """
        {"data":[
          {"id":"p1","created_time":"2026-07-21T13:03:20+0000","name":"trip",
           "images":[{"source":"https://scontent.xx.fbcdn.net/v/small.jpg","width":800,"height":600},
                     {"source":"https://scontent.xx.fbcdn.net/v/large.jpg","width":2048,"height":1536}]}
        ],"paging":{"cursors":{"after":"NEXT"},"next":"https://graph.facebook.com/next"}}
    """.trimIndent()

    @Test
    fun `subject accepts ids names and profile urls`() {
        assertEquals("12345", normalizeFacebookSubject("12345"))
        assertEquals("zuck", normalizeFacebookSubject("https://www.facebook.com/zuck/photos"))
        assertEquals("zuck", normalizeFacebookSubject("https://m.facebook.com/zuck"))
        assertEquals("12345", normalizeFacebookSubject("https://www.facebook.com/profile.php?id=12345"))
        assertEquals("", normalizeFacebookSubject("https://example.com/x?y=1"))
    }

    @Test
    fun `graph time accepts facebook offset format`() {
        assertEquals(1_784_639_000L, parseFacebookTime("2026-07-21T13:03:20+0000"))
        assertEquals(1_700_000_000L, parseFacebookTime("1700000000"))
        assertNull(parseFacebookTime(null))
    }

    @Test
    fun `graph url keeps token and cursor encoded`() {
        val url = buildGraphUrl("zuck", "photos", "token|with", "a+b=", 25)
        assertTrue(url.contains("/v19.0/zuck/photos?"))
        assertTrue(url.contains("limit=25"))
        assertTrue(url.contains("after=a%2Bb%3D"))
        assertTrue(url.contains("access_token=token%7Cwith"))
    }

    @Test
    fun `graph photos keep the largest image and paging cursor`() {
        val page = parseGraphPage(photosBody, "photos")
        assertNull(page.error)
        assertEquals("NEXT", page.nextAfter)
        val entry = page.entries.single()
        assertEquals(1_784_639_000L, entry.createdAt)
        assertEquals("trip", entry.title)
        assertEquals(listOf("https://scontent.xx.fbcdn.net/v/large.jpg"), entry.media.map { it.url })
    }

    @Test
    fun `graph videos keep playback and cover`() {
        val body = """
            {"data":[{"id":"v1","created_time":"2026-07-21T13:00:00+0000","title":"clip",
              "source":"https://video.xx.fbcdn.net/v/v1.mp4",
              "picture":"https://scontent.xx.fbcdn.net/v/cover.jpg"}]}
        """.trimIndent()
        val entry = parseGraphPage(body, "videos").entries.single()
        assertEquals(2, entry.media.size)
        assertTrue(entry.media.any { it.url.endsWith("v1.mp4") && it.kind.name == "VIDEO" })
        assertTrue(entry.media.any { it.url.endsWith("cover.jpg") && it.isCover })
    }

    @Test
    fun `expired token is reported instead of an empty list`() {
        val page = parseGraphPage("""{"error":{"code":190,"message":"Invalid OAuth access token"}}""", "photos")
        val error = page.error ?: error("expected a token error")
        assertTrue(error.contains("access_token"))
        assertTrue(error.contains("190"))
    }

    @Test
    fun `cookie page extracts cdn media and next link`() {
        val html = """
            <html><body>
            <img src="https://scontent.xx.fbcdn.net/v/t1/aaa_n.jpg?stp=1"/>
            <script>{"image_uri":"https:\/\/scontent.xx.fbcdn.net\/v\/t1\/bbb_o.jpg",
                     "playable_url":"https:\/\/video.xx.fbcdn.net\/v\/clip.mp4"}</script>
            <a href="/zuck/photos?after=NEXT" rel="next">Next</a>
            </body></html>
        """.trimIndent()
        val page = parseFacebookPhotoPage(html, "https://m.facebook.com/zuck/photos")
        val media = page.entries.single().media
        assertEquals(3, media.size)
        assertTrue(media.any { it.url.contains("aaa_n.jpg") })
        assertTrue(media.any { it.url.endsWith("bbb_o.jpg") })
        assertTrue(media.any { it.url.endsWith("clip.mp4") })
        assertEquals("https://m.facebook.com/zuck/photos?after=NEXT", page.nextUrl)
    }

    @Test
    fun `login page is detected`() {
        assertTrue(isFacebookLoginPage("""<form id="login_form"><input name="pass"></form>"""))
    }
}
