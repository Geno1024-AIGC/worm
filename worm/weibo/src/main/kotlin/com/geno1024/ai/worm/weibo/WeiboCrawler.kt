package com.geno1024.ai.worm.weibo

import com.geno1024.ai.worm.core.ALL_PAGES
import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlReport
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.Crawler
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.HttpResponse
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.MediaDownloader
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.isBeforeWatermark
import com.geno1024.ai.worm.core.reachedWatermark
import org.jsoup.Jsoup
import java.io.File

/** Collects images and videos from one Weibo user's posts. */
class WeiboCrawler(
    private val http: HttpPortal = JdkHttpPortal(),
    cacheDir: File = File(System.getProperty("java.io.tmpdir"), "worm-downloads"),
    private val maxPages: Int = ALL_PAGES,
    downloadDelayMs: Long = 300L,
) : Crawler {
    private val downloader = MediaDownloader(http, cacheDir, downloadDelayMs)

    override val id: String = "weibo.user"
    override val displayName: String = "微博用户"

    override suspend fun collect(
        request: CrawlRequest,
        session: CrawlSession,
        sink: MediaSink,
        listener: CrawlListener,
    ): CrawlReport {
        val uid = normalizeWeiboSubject(request.subject)
        require(uid.isNotBlank()) { "微博 uid 或主页 URL 不能为空" }

        val items = mutableListOf<CollectedItem>()
        val warnings = mutableListOf<String>()
        val seen = HashSet<String>()
        val since = request.since
        val nowSec = System.currentTimeMillis() / 1000L
        var newestAt = 0L
        if (session.cookies.isEmpty()) warnings += "未提供微博 Cookie：部分内容可能不可见或触发风控"

        // Phase 1: list all posts without downloading a single byte.
        var page = 1
        while (page <= maxPages) {
            listener.onProgress(CrawlProgress(
                "微博列表", page, null,
                "page=$page，已发现 ${items.size} 项${if (since != null) "，增量" else ""}",
            ))
            val url = buildWeiboListUrl(uid, page)
            var response = http.get(url, session, WEIBO_REFERER)
            if (!usable(response, warnings)) break
            var parsed = parseWeiboListPage(response.body, uid, WEIBO_REFERER, nowSec)
            // A chunked body may stop mid-value without an IOException or a wrong
            // Content-Length. Re-fetch once before blaming the response format.
            if (parsed.error?.contains("响应被截断") == true) {
                response = http.get(url, session, WEIBO_REFERER)
                if (!usable(response, warnings)) break
                parsed = parseWeiboListPage(response.body, uid, WEIBO_REFERER, nowSec)
            }
            if (parsed.error != null) {
                warnings += "微博列表: ${parsed.error}"
                break
            }
            if (parsed.posts.isEmpty()) break

            val pageTimes = mutableListOf<Long?>()
            for (post in parsed.posts) {
                pageTimes += post.createdAt
                post.createdAt?.let { if (it > newestAt) newestAt = it }
                if (isBeforeWatermark(post.createdAt, since)) continue

                val candidates = mutableListOf<CollectedItem>()
                for (asset in post.media) {
                    val folder = if (asset.kind == MediaKind.IMAGE) "image" else "video"
                    candidates += CollectedItem.Media(asset.copy(relativeDir = "weibo/$uid/$folder"))
                }
                if (request.includeText && post.html.isNotBlank()) {
                    candidates += CollectedItem.Text(
                        sourceUrl = "https://weibo.com/$uid/${post.id}",
                        title = null,
                        body = Jsoup.parse(post.html).text(),
                        createdAt = post.createdAt,
                    )
                }
                for (item in candidates) {
                    if (item.identity() in request.seenIds) continue
                    if (item is CollectedItem.Media) {
                        if (!seen.add("${item.asset.kind.name}:${item.asset.url}")) continue
                        listener.onAssetFound(item.asset)
                    }
                    items += item
                }
            }

            if (reachedWatermark(pageTimes, since)) {
                val detail = "命中上次抓取时间，提前结束（接口估算共 ${parsed.pageCount ?: "?"} 页）"
                listener.onProgress(CrawlProgress("微博列表", page, null, detail))
                warnings += "增量抓取在第 $page 页$detail"
                break
            }
            page++
        }

        val assets = items.filterIsInstance<CollectedItem.Media>().map { it.asset }
        listener.onDiscovered(assets)
        val saved = downloader.downloadAll(
            assets = assets,
            session = session,
            sink = sink,
            onProgress = { current, total, asset ->
                listener.onProgress(CrawlProgress("媒体", current, total, asset.url))
            },
            onFailure = { asset, error -> warnings += "下载失败 ${asset.url}: ${error.message}" },
            onDone = { asset -> listener.onAssetDone(asset) },
        )
        return CrawlReport(
            id, uid, items, saved, warnings,
            newestItemAt = newestAt.takeIf { it > 0 },
        )
    }

    private fun usable(response: HttpResponse, warnings: MutableList<String>): Boolean = when {
        response.status == 401 || response.status == 403 -> {
            warnings += "微博需要登录或触发风控（HTTP ${response.status}）"
            false
        }
        !response.isSuccess -> {
            warnings += "微博列表返回 HTTP ${response.status}"
            false
        }
        else -> true
    }
}
