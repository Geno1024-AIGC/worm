package com.geno1024.ai.worm.facebook

import com.geno1024.ai.worm.core.ALL_PAGES
import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlReport
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.Crawler
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.MediaDownloader
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.isBeforeWatermark
import com.geno1024.ai.worm.core.reachedWatermark
import java.io.File

/**
 * Collects a Facebook user's photos and videos. Graph API with an `access_token` is
 * tried first; on failure the crawler falls back to the visible m.facebook.com page.
 */
class FacebookCrawler(
    private val http: HttpPortal = JdkHttpPortal(),
    cacheDir: File = File(System.getProperty("java.io.tmpdir"), "worm-downloads"),
    private val mode: FacebookMode = FacebookMode.AUTO,
    private val pageSize: Int = 100,
    private val maxPages: Int = ALL_PAGES,
    downloadDelayMs: Long = 300L,
) : Crawler {
    private val downloader = MediaDownloader(http, cacheDir, downloadDelayMs)

    override val id: String = "facebook.user"
    override val displayName: String = "Facebook 用户"

    override suspend fun collect(
        request: CrawlRequest,
        session: CrawlSession,
        sink: MediaSink,
        listener: CrawlListener,
    ): CrawlReport {
        val subject = normalizeFacebookSubject(request.subject)
        require(subject.isNotBlank()) { "Facebook 用户名、数字 ID 或主页 URL 不能为空" }

        val items = mutableListOf<CollectedItem>()
        val warnings = mutableListOf<String>()
        val seen = HashSet<String>()
        val since = request.since
        var newestAt = 0L
        if (session.cookies.isEmpty()) {
            warnings += "未提供 Facebook 登录态：需要 access_token 或网页 Cookie"
        }

        val token = session.cookies["access_token"]?.trim()?.takeIf { it.isNotBlank() }
        val effective = if (mode == FacebookMode.AUTO) {
            if (token != null) FacebookMode.GRAPH else FacebookMode.COOKIE
        } else mode

        if (effective == FacebookMode.GRAPH && token != null) {
            val problems = mutableListOf<String>()
            val ok = graphPages(subject, token, request, session, items, seen, problems, listener, since) {
                newestAt = maxOf(newestAt, it)
            }
            if (ok) {
                warnings += problems
            } else if (mode == FacebookMode.GRAPH) {
                warnings += problems
            } else {
                // Discard partial Graph results: the cookie fallback starts from page one.
                items.clear()
                seen.clear()
                newestAt = 0L
                cookiePages(subject, request, session, items, seen, warnings, listener, since) {
                    newestAt = maxOf(newestAt, it)
                }
                warnings += "Graph 不可用，已回退网页 Cookie：${problems.firstOrNull() ?: "未知原因"}"
            }
        } else {
            if (effective == FacebookMode.GRAPH) {
                warnings += "Graph 模式缺少 access_token：请在登录页手动输入 access_token=<token>"
            }
            cookiePages(subject, request, session, items, seen, warnings, listener, since) {
                newestAt = maxOf(newestAt, it)
            }
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
            id, subject, items, saved, warnings,
            newestItemAt = newestAt.takeIf { it > 0 },
        )
    }

    /** Runs both Graph edges; false means a fatal response (so a fallback can start clean). */
    private fun graphPages(
        subject: String,
        token: String,
        request: CrawlRequest,
        session: CrawlSession,
        items: MutableList<CollectedItem>,
        seen: MutableSet<String>,
        problems: MutableList<String>,
        listener: CrawlListener,
        since: Long?,
        onNewest: (Long) -> Unit,
    ): Boolean {
        for (edge in listOf("photos", "videos")) {
            var after: String? = null
            var page = 0
            while (page < maxPages) {
                listener.onProgress(CrawlProgress(
                    "Graph$edge", page + 1, null,
                    "已发现 ${items.size} 项${if (since != null) "，增量" else ""}",
                ))
                val response = http.get(buildGraphUrl(subject, edge, token, after, pageSize), session, FACEBOOK_REFERER)
                if (response.status == 401 || response.status == 403) {
                    problems += "Graph $edge 需要有效 access_token（HTTP ${response.status}）"
                    return false
                }
                if (!response.isSuccess) {
                    problems += "Graph $edge 返回 HTTP ${response.status}"
                    return false
                }
                val parsed = parseGraphPage(response.body, edge)
                if (parsed.error != null) {
                    problems += "Graph $edge: ${parsed.error}"
                    return false
                }
                if (parsed.entries.isEmpty()) break

                val times = append(parsed, subject, request, items, seen, listener)
                times.forEach { time -> time?.let { onNewest(it) } }
                if (reachedWatermark(times, since)) {
                    listener.onProgress(CrawlProgress(
                        "Graph$edge", page + 1, null,
                        "命中上次抓取时间，提前结束",
                    ))
                    break
                }
                after = parsed.nextAfter ?: break
                page++
            }
        }
        return true
    }

    private fun cookiePages(
        subject: String,
        request: CrawlRequest,
        session: CrawlSession,
        items: MutableList<CollectedItem>,
        seen: MutableSet<String>,
        warnings: MutableList<String>,
        listener: CrawlListener,
        since: Long?,
        onNewest: (Long) -> Unit,
    ) {
        var url: String? = buildFacebookPhotoPageUrl(subject)
        var page = 0
        while (url != null && page < maxPages) {
            listener.onProgress(CrawlProgress(
                "网页照片", page + 1, null,
                "已发现 ${items.size} 项${if (since != null) "，增量" else ""}",
            ))
            val response = http.get(url, session, FACEBOOK_REFERER)
            if (!response.isSuccess) {
                warnings += "Facebook 网页返回 HTTP ${response.status}"
                return
            }
            if (isFacebookLoginPage(response.body)) {
                warnings += "Facebook 网页 Cookie 已失效，请重新登录或改用 access_token"
                return
            }
            val parsed = parseFacebookPhotoPage(response.body, url)
            if (parsed.entries.isEmpty() || parsed.entries.all { it.media.isEmpty() }) {
                if (page == 0) {
                    warnings += "网页模式没有发现媒体：可能未登录、无访问权限或页面结构已变化"
                }
                return
            }
            val times = append(parsed, subject, request, items, seen, listener)
            times.forEach { time -> time?.let { onNewest(it) } }
            if (reachedWatermark(times, since)) {
                listener.onProgress(CrawlProgress("网页照片", page + 1, null, "命中上次抓取时间，提前结束"))
                return
            }
            url = parsed.nextUrl
            page++
        }
    }

    private fun append(
        page: FacebookPage,
        subject: String,
        request: CrawlRequest,
        items: MutableList<CollectedItem>,
        seen: MutableSet<String>,
        listener: CrawlListener,
    ): List<Long?> {
        val times = mutableListOf<Long?>()
        for (entry in page.entries) {
            times += entry.createdAt
            if (isBeforeWatermark(entry.createdAt, request.since)) continue
            val candidates = mutableListOf<CollectedItem>()
            for (asset in entry.media) {
                val folder = if (asset.kind == MediaKind.IMAGE) "image" else "video"
                candidates += CollectedItem.Media(asset.copy(relativeDir = "facebook/$subject/$folder"))
            }
            if (request.includeText && !entry.title.isNullOrBlank()) {
                candidates += CollectedItem.Text(
                    sourceUrl = "https://www.facebook.com/$subject/posts/${entry.id}",
                    title = entry.title,
                    body = entry.title,
                    createdAt = entry.createdAt,
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
        return times
    }
}
