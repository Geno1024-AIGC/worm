package com.geno1024.ai.worm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoreTest {
    @Test
    fun `cookies parse and dedupe by name`() {
        val cookies = Cookies.fromHeader("uin=o123; p_skey=abc; skey=old; p_skey=new")
        assertEquals("new", cookies["p_skey"])
        assertEquals("uin=o123; p_skey=new; skey=old", Cookies.toHeader(cookies))
        assertTrue(Cookies.hasLogin(cookies, "p_skey", "skey"))
        assertFalse(Cookies.hasLogin(mapOf("uin" to "1"), "p_skey", "skey"))
    }

    @Test
    fun `netscape cookies accept HttpOnly lines`() {
        val text = """
            # Netscape HTTP Cookie File
            #HttpOnly_.qq.com	TRUE	/	TRUE	0	p_skey	secret
            .qq.com	TRUE	/	FALSE	0	uin	o123
        """.trimIndent()
        val cookies = Cookies.fromNetscape(text)
        assertEquals("secret", cookies["p_skey"])
        assertEquals("o123", cookies["uin"])
    }

    @Test
    fun `jsonp strips callback wrapper`() {
        assertEquals("{\"code\":0}", Jsonp.strip("_preloadCallback({\"code\":0});"))
        assertEquals("[ ]", Jsonp.strip("[ ]"))
    }

    @Test
    fun `jsonp objectOrNull never leaks a raw parser exception`() {
        assertNull(Jsonp.objectOrNull("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"))
        assertNull(Jsonp.objectOrNull(""))
        assertEquals(0, Jsonp.objectOrNull("{\"code\":0}")?.optInt("code"))
    }

    @Test
    fun `responseProblem names svg html and empty bodies`() {
        val svgProblem = responseProblem("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="40">""")
        assertTrue(svgProblem.contains("SVG"))
        assertTrue(svgProblem.contains("width=\"100\" height=\"40\""))
        assertTrue(responseProblem("""<?xml version="1.0"?><svg/>""").contains("SVG"))
        assertTrue(responseProblem("""<!doctype html><html>login</html>""").contains("HTML"))
        assertTrue(responseProblem("  ").contains("响应为空"))
        assertTrue(responseProblem("not-json").contains("响应不是合法 JSON"))
        assertTrue(responseProblem("""{"paging":{"is_end":false,"totals":8""").contains("响应被截断"))
    }

    @Test
    fun `media names are sanitised and extensions whitelisted`() {
        val asset = MediaAsset(
            url = "https://example.com/a/photo.png?size=1",
            kind = MediaKind.IMAGE,
            suggestedName = "../../evil name",
        )
        assertEquals("evil_name.png", MediaFiles.nameFor(asset))
        assertEquals("旅行_2024", MediaFiles.sanitize("旅行/2024"))
        val video = MediaAsset("https://example.com/v.mp4", MediaKind.VIDEO)
        assertEquals("mp4", MediaFiles.extensionOf(video.url, MediaKind.VIDEO))
        val cover = MediaAsset("https://example.com/c.jpg", MediaKind.IMAGE, isCover = true)
        assertEquals("jpg", MediaFiles.extensionOf(cover.url, MediaKind.IMAGE))

        // The kind wins over a misleading URL or a stale asset.extension.
        val imageAsVideo = MediaAsset(
            "https://example.com/photo.mp4", MediaKind.IMAGE, id = "p",
            extension = "mp4",
        )
        val videoAsImage = MediaAsset(
            "https://example.com/clip.jpg", MediaKind.VIDEO, id = "p",
            extension = "jpg",
        )
        assertEquals("p.jpg", MediaFiles.nameFor(imageAsVideo))
        assertEquals("p.mp4", MediaFiles.nameFor(videoAsImage))

        assertEquals(MediaKind.IMAGE, MediaFiles.kindOf("旅行.JPG"))
        assertEquals(MediaKind.VIDEO, MediaFiles.kindOf("clip.webm"))
        assertNull(MediaFiles.kindOf("clip.part"))
        assertEquals("image/jpeg", MediaFiles.mimeOf("a.jpg"))
        assertEquals("video/mp4", MediaFiles.mimeOf("a.mp4"))
    }

    @Test
    fun `html media extracts images and videos without duplicates`() {
        val html = """
            <img src="//cdn.example.com/a.jpg" data-original="https://cdn.example.com/big.jpg">
            <div><img src="/c.png"></div>
            <video src="https://cdn.example.com/v.mp4" poster="https://cdn.example.com/poster.jpg"></video>
        """.trimIndent()
        val assets = HtmlMedia.extract(html, "https://www.example.com/post/1")
        val urls = assets.map { it.url }
        assertEquals(listOf(
            "https://cdn.example.com/big.jpg",
            "https://cdn.example.com/a.jpg",
            "https://www.example.com/c.png",
            "https://cdn.example.com/v.mp4",
            "https://cdn.example.com/poster.jpg",
        ), urls)
        assertTrue(assets.first { it.kind == MediaKind.IMAGE && it.url.endsWith("poster.jpg") }.isCover)
    }

    @Test
    fun `crawl journal round trips per-target watermarks`() {
        val zhihu = CrawlStamp(
            "zhihu.user", "alice", 1_700_000_000L, 1_699_999_900L,
            listOf("answer/a1", "image/b2"),
        )
        val qzone = CrawlStamp("qzone", "12345", 1_600_000_000L)
        val body = serializeCrawlJournal(
            mapOf(
                crawlKey(zhihu.crawlerId, zhihu.subject) to zhihu,
                crawlKey(qzone.crawlerId, qzone.subject) to qzone,
            ),
        )
        val parsed = parseCrawlJournal(body)
        assertEquals(zhihu, parsed[crawlKey("zhihu.user", "alice")])
        assertEquals(qzone, parsed[crawlKey("qzone", "12345")])
        assertEquals(emptyMap<String, CrawlStamp>(), parseCrawlJournal("{broken"))
        assertEquals(zhihu.newestItemAt!! - INCREMENTAL_OVERLAP_SEC, sinceOf(zhihu))
        assertNull(sinceOf(null))
    }

    @Test
    fun `watermark only stops pages whose every item is old`() {
        val since = 1000L
        assertTrue(reachedWatermark(listOf(900L, 800L), since))
        assertFalse(reachedWatermark(listOf(900L, null), since))
        assertFalse(reachedWatermark(emptyList(), since))
        assertTrue(isBeforeWatermark(999L, since))
        assertFalse(isBeforeWatermark(1000L, since))
        assertFalse(isBeforeWatermark(null, since))
    }

    @Test
    fun `text error pages are rejected before they are saved as media`(@TempDir temp: File) {
        val svg = File(temp, "placeholder.jpg")
        svg.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="40"></svg>""")
        assertTrue(MediaFiles.textResponseProblem(svg)!!.contains("SVG"))

        val json = File(temp, "error.jpg")
        json.writeText("""{"error":{"code":40353}}""")
        assertTrue(MediaFiles.textResponseProblem(json)!!.contains("JSON"))

        val jpeg = File(temp, "real.jpg")
        jpeg.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
        assertNull(MediaFiles.textResponseProblem(jpeg))
    }

    @Test
    fun `inline data svg placeholders are skipped without crashing`() {
        val html = """
            <img src="data:image/svg+xml;utf8,<svg%20xmlns=http://www.w3.org/2000/svg%20width='4624'%20height='3468'></svg>">
            <img src="https://pic1.zhimg.com/real.jpg">
            <video poster="data:image/svg+xml;utf8,<svg width='10' height='10'></svg>"></video>
            <img src="http://bad host/not a url.jpg">
        """.trimIndent()
        val assets = HtmlMedia.extract(html, "https://www.zhihu.com/p/1")
        assertEquals(listOf("https://pic1.zhimg.com/real.jpg"), assets.map { it.url })
    }

    @Test
    fun `batch download has a fixed total and survives one failed asset`(@TempDir temp: File) {
        val assets = (1..3).map { MediaAsset("https://example.com/$it.jpg", MediaKind.IMAGE, id = "m$it") }
        val http = object : HttpPortal {
            override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>) =
                HttpResponse(200, "", url)

            override fun download(
                url: String,
                session: CrawlSession,
                referer: String?,
                target: File,
                onBytes: (Long, Long?) -> Unit,
            ): File {
                if ("2.jpg" in url) throw java.io.IOException("boom")
                target.writeBytes("x".toByteArray())
                return target
            }
        }
        val downloader = MediaDownloader(http, File(temp, "cache"), delayMs = 0)
        val steps = mutableListOf<Pair<Int, Int>>()
        val failures = mutableListOf<String>()
        val saved = downloader.downloadAll(
            assets,
            CrawlSession(emptyMap()),
            DirectoryMediaSink(File(temp, "media")),
            onProgress = { current, total, _ -> steps += current to total },
            onFailure = { asset, _ -> failures += asset.url },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), steps)
        assertEquals(2, saved.size)
        assertEquals(listOf("https://example.com/2.jpg"), failures)
    }

    @Test
    fun `media time manifest survives reload and forgets stale times`(@TempDir temp: File) {
        val manifest = MediaTimeManifest(File(temp, "state/media-times.json"))
        manifest.recordSeconds("qzone/123/a.jpg", 1_700_000_000L)
        manifest.recordSeconds("qzone/123/b.jpg", null)
        val reloaded = MediaTimeManifest(File(temp, "state/media-times.json"))
        assertEquals(1_700_000_000L, reloaded.secondsAt("/qzone/123/a.jpg"))
        assertNull(reloaded.secondsAt("qzone/123/b.jpg"))
        reloaded.recordSeconds("qzone/123/a.jpg", null)
        assertNull(reloaded.secondsAt("qzone/123/a.jpg"))
    }

    @Test
    fun `fallback url is used when the primary download fails`(@TempDir temp: File) {
        val primary = "https://example.com/thumb_r.jpg"
        val fallback = "https://example.com/thumb_720w.jpg"
        val asset = MediaAsset(primary, MediaKind.IMAGE, fallbackUrls = listOf(fallback))
        val tried = mutableListOf<String>()
        val http = object : HttpPortal {
            override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>) =
                HttpResponse(200, "", url)

            override fun download(
                url: String,
                session: CrawlSession,
                referer: String?,
                target: File,
                onBytes: (Long, Long?) -> Unit,
            ): File {
                tried += url
                if (url == primary) throw java.io.IOException("boom")
                target.writeBytes("x".toByteArray())
                return target
            }
        }
        val saved = MediaDownloader(http, File(temp, "cache"), delayMs = 0)
            .download(asset, CrawlSession(emptyMap()), DirectoryMediaSink(File(temp, "media")))
        assertTrue(saved != null)
        assertEquals(listOf(primary, fallback), tried)
    }

    @Test
    fun `resume queue roundtrips assets and drops settled ones`(@TempDir temp: File) {
        val queue = ResumeQueue(File(temp, "state/resume-queue.json"))
        val assets = listOf(
            MediaAsset(
                "https://example.com/1.jpg", MediaKind.IMAGE, id = "a",
                relativeDir = "qzone/123/image", referer = "https://user.qzone.qq.com/",
            ),
            MediaAsset(
                "https://example.com/2.mp4", MediaKind.VIDEO, id = "b",
                createdAt = 42L, relativeDir = "qzone/123/video",
            ),
        )
        queue.save(
            ResumeQueue.Batch(
                "qzone", "123",
                ResumeQueue.SinkRef(ResumeQueue.SinkRef.DIRECTORY, "media"),
                total = 5, assets = assets,
            ),
        )
        val loaded = queue.load()!!
        assertEquals("qzone", loaded.crawlerId)
        assertEquals("123", loaded.subject)
        assertEquals(5, loaded.total)
        assertEquals(assets, loaded.assets)

        queue.remove(assets.first())
        assertEquals(listOf(assets.last()), queue.load()!!.assets)
        queue.clear()
        assertNull(queue.load())
    }

    @Test
    fun `corrupt resume queue is dropped instead of replayed`(@TempDir temp: File) {
        val file = File(temp, "resume-queue.json").apply { writeText("{broken") }
        assertNull(ResumeQueue(file).load())
        assertFalse(file.exists())
    }

    @Test
    fun `resume runner continues progress and drains the queue`(@TempDir temp: File) {
        val pending = listOf(
            MediaAsset("https://example.com/4.jpg", MediaKind.IMAGE, id = "m4", relativeDir = "qzone/123/image"),
            MediaAsset("https://example.com/5.jpg", MediaKind.IMAGE, id = "m5", relativeDir = "qzone/123/image"),
        )
        val queue = ResumeQueue(File(temp, "resume-queue.json"))
        queue.save(
            ResumeQueue.Batch(
                "qzone", "123",
                ResumeQueue.SinkRef(ResumeQueue.SinkRef.DIRECTORY, "media"),
                total = 5, assets = pending,
            ),
        )
        val http = object : HttpPortal {
            override fun get(url: String, session: CrawlSession, referer: String?, extraHeaders: Map<String, String>) =
                HttpResponse(200, "", url)

            override fun download(
                url: String,
                session: CrawlSession,
                referer: String?,
                target: File,
                onBytes: (Long, Long?) -> Unit,
            ): File {
                if ("5.jpg" in url) throw java.io.IOException("boom")
                target.writeBytes("x".toByteArray())
                return target
            }
        }
        val steps = mutableListOf<Triple<String, Int, Int>>()
        val warnings = mutableListOf<String>()
        val listener = object : CrawlListener {
            override fun onProgress(progress: CrawlProgress) {
                steps += Triple(progress.stage, progress.current, progress.total ?: -1)
            }
            override fun onWarning(message: String) {
                warnings += message
            }
        }
        val saved = ResumeRunner(
            queue, http, CrawlSession(emptyMap()),
            DirectoryMediaSink(File(temp, "media")), File(temp, "cache"), listener, delayMs = 0,
        ).drain()

        assertEquals(1, saved.size)
        assertEquals(listOf(Triple("续传", 4, 5), Triple("续传", 5, 5)), steps)
        assertEquals(1, warnings.size)
        assertNull(queue.load())
    }

    @Test
    fun `page cap and reported page count`() {
        assertEquals(10, pageCapOf("10"))
        assertEquals(10, pageCapOf(null))
        assertEquals(10, pageCapOf("abc"))
        assertEquals(ALL_PAGES, pageCapOf("0"))
        assertEquals(ALL_PAGES, pageCapOf("-5"))
        assertEquals(ALL_PAGES, pageCapOf("99999"))
        assertEquals(3, pageCountOf(41, 20))
        assertNull(pageCountOf(null, 20))
        assertNull(pageCountOf(0, 20))
    }

    @Test
    fun `json output escapes strings`() {
        assertEquals("""{"a":"x\"y","b":[1,true]}""", JsonOutput.value(mapOf("a" to "x\"y", "b" to listOf(1, true))))
    }

    @Test
    fun `directory sink stores under safe relative directory`(@TempDir temp: File) {
        val sink = DirectoryMediaSink(temp)
        val asset = MediaAsset(
            url = "https://example.com/a.jpg",
            kind = MediaKind.IMAGE,
            id = "abc",
            relativeDir = "albums/My Album",
        )
        assertFalse(sink.exists(asset))
        val source = File(temp, "source.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val saved = sink.accept(asset, source)
        assertEquals("abc.jpg", saved.name)
        assertTrue(sink.exists(asset))
        assertEquals(3L, saved.size)
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(temp, "albums/My_Album/abc.jpg").readBytes().toList())
    }

    @Test
    fun `directory sink stamps publish time onto the saved file`(@TempDir temp: File) {
        val sink = DirectoryMediaSink(temp)
        val createdAt = 1700000000L
        val asset = MediaAsset(
            url = "https://example.com/clip.mp4",
            kind = MediaKind.VIDEO,
            id = "clip",
            createdAt = createdAt,
        )
        val source = File(temp, "source.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals("clip.mp4", sink.accept(asset, source).name)
        assertEquals(createdAt * 1000L, File(temp, "videos/clip.mp4").lastModified())

        val undated = asset.copy(id = "undated", createdAt = null)
        source.writeBytes(byteArrayOf(1, 2, 3))
        val saved = sink.accept(undated, source)
        assertTrue(File(temp, "videos/undated.mp4").lastModified() > 0)
        assertEquals("undated.mp4", saved.name)
    }
}
