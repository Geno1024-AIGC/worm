package com.geno1024.ai.worm.weibo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeiboApiTest {
    private val listBody = """
        {"ok":1,"data":{"cardlistInfo":{"total":21},"cards":[
          {"card_type":9,"mblog":{"id":"w1","created_at":"Tue Jul 21 21:03:20 +0800 2026",
            "text":"<p>photo</p>",
            "pics":[{"url":"https://ww2.sinaimg.cn/orj360/aaa.jpg",
                     "large":{"url":"https://ww2.sinaimg.cn/large/aaa.jpg"}}]}},
          {"card_type":9,"mblog":{"id":"w2","created_at":"3小时前","text":"video",
            "page_info":{"type":"video","page_pic":{"url":"https://ww2.sinaimg.cn/large/cover.jpg"},
              "media_info":{"stream_url_hd":"https://video.weibo.com/w2.mp4"}}}},
          {"card_type":11,"card_group":[{"mblog":{"id":"w3","created_at":"1700000000","text":"repost"}}]}
        ]}}
    """.trimIndent()

    @Test
    fun `subject accepts bare uid and profile urls`() {
        assertEquals("12345", normalizeWeiboSubject("12345"))
        assertEquals("12345", normalizeWeiboSubject("https://weibo.com/u/12345?from=page"))
        assertEquals("12345", normalizeWeiboSubject("https://weibo.cn/12345"))
        assertEquals("", normalizeWeiboSubject("https://weibo.com/geno"))
    }

    @Test
    fun `time parser handles absolute relative and numeric values`() {
        val now = 1_700_010_000L
        assertEquals(1_784_639_000L, parseWeiboTime("Tue Jul 21 21:03:20 +0800 2026", now))
        assertEquals(now, parseWeiboTime("刚刚", now))
        assertEquals(now - 180, parseWeiboTime("3分钟前", now))
        assertEquals(now - 7200, parseWeiboTime("2小时前", now))
        assertEquals(1_700_000_000L, parseWeiboTime("1700000000", now))
        assertEquals(1_700_000_000L, parseWeiboTime("1700000000000", now))
        assertNull(parseWeiboTime(null, now))
    }

    @Test
    fun `list parser keeps large images video streams and reposts`() {
        val page = parseWeiboListPage(listBody, "12345", WEIBO_REFERER, 1_700_010_000L)
        assertNull(page.error)
        assertEquals(3, page.posts.size)
        assertEquals(2, page.pageCount)

        val first = page.posts[0]
        assertEquals(1_784_639_000L, first.createdAt)
        assertEquals(listOf("https://ww2.sinaimg.cn/large/aaa.jpg"), first.media.map { it.url })

        val second = page.posts[1]
        assertEquals(2, second.media.size)
        assertTrue(second.media.any { it.url == "https://video.weibo.com/w2.mp4" && it.id == "w2" })
        assertTrue(second.media.any { it.url.endsWith("cover.jpg") && it.isCover })
        assertEquals("w3", page.posts[2].id)
    }

    @Test
    fun `web timeline url and headers target the logged-in profile`() {
        assertEquals(
            "https://weibo.com/ajax/statuses/mymblog?uid=12345&page=3&feature=0",
            buildWeiboTimelineUrl("12345", 3),
        )
        assertEquals("https://weibo.com/u/12345", weiboWebReferer("12345"))
        assertEquals("XMLHttpRequest", WEIBO_WEB_HEADERS["x-requested-with"])
        assertTrue(WEIBO_WEB_HEADERS.getValue("accept").contains("application/json"))
    }

    @Test
    fun `web timeline parser keeps older posts and reports errors`() {
        val body = """
            {"ok":1,"data":{"total_number":45,"list":[
              {"id":"w1","created_at":"1700000000","text":"<p>photo</p>",
                "pics":[{"large":{"url":"https://ww2.sinaimg.cn/large/aaa.jpg"}}]},
              {"id":"old","created_at":"1600000000","text":"old",
                "page_info":{"type":"video","media_info":{"stream_url_hd":"https://video.weibo.com/old.mp4"}}}
            ]}}
        """.trimIndent()
        val referer = weiboWebReferer("12345")
        val page = parseWeiboTimelinePage(body, referer, 1_700_010_000L)
        assertNull(page.error)
        assertEquals(3, page.pageCount)
        assertEquals(listOf("w1", "old"), page.posts.map { it.id })
        assertEquals(1_600_000_000L, page.posts[1].createdAt)
        assertEquals("https://video.weibo.com/old.mp4", page.posts[1].media.single().url)
        assertEquals(referer, page.posts[0].media.single().referer)

        val failure = parseWeiboTimelinePage("""{"ok":0,"msg":"not logged in"}""", referer)
        assertTrue(failure.error!!.contains("not logged in"))
        val cut = parseWeiboTimelinePage("""{"ok":1,"data":{"list":[{"id":"w1","created_at":"1600000000""", referer)
        assertTrue(cut.error!!.contains("响应被截断"))
    }

    @Test
    fun `parentheses in post text do not break either endpoint`() {
        // A post body with '(' and ')' inside strings used to be cut as JSONP.
        val mobile = """{"ok":1,"data":{"cards":[{"mblog":{"id":"w1","created_at":"1700000000",
            "text":"<p>see (here) http://t.cn/a(b)</p>"}}]}}""".trimIndent()
        val page = parseWeiboListPage(mobile, "12345", WEIBO_REFERER, 1_700_000_000L)
        assertNull(page.error)
        assertEquals("w1", page.posts.single().id)

        val web = """{"data":{"since_id":"5311266971192171kp2","list":[{"id":"w1",
            "created_at":"1700000000","text":"see (here)"}]}}""".trimIndent()
        val timeline = parseWeiboTimelinePage(web, weiboWebReferer("12345"), 1_700_000_000L)
        assertNull(timeline.error)
        assertEquals("w1", timeline.posts.single().id)
    }

    @Test
    fun `api errors and captcha pages are readable`() {
        val error = parseWeiboListPage("""{"ok":0,"msg":"user not found"}""", "123").error
            ?: error("expected an API error")
        assertTrue(error.contains("user not found"))
        val svg = parseWeiboListPage("""<svg xmlns="http://www.w3.org/2000/svg"></svg>""", "123").error
            ?: error("expected a captcha warning")
        assertTrue(svg.contains("SVG"))
    }
}
