package com.geno1024.ai.worm.zhihu

import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlException
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlReport
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.Crawler
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.HttpResponse
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.isBeforeWatermark
import com.geno1024.ai.worm.core.MediaDownloader
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.reachedWatermark
import java.io.File

private const val REFERER = "https://www.zhihu.com/"
private val API_HEADERS = mapOf("x-requested-with" to "fetch")

/** Collects media from a Zhihu user's answers, articles and/or activities. */
class ZhihuCrawler(
    private val http: HttpPortal = JdkHttpPortal(),
    cacheDir: File = File(System.getProperty("java.io.tmpdir"), "worm-downloads"),
    private val kinds: Set<ZhihuKind> = ZhihuKind.entries.toSet(),
    private val pageSize: Int = 20,
    private val maxPagesPerKind: Int = 50,
    private val upgradeImages: Boolean = false,
    downloadDelayMs: Long = 300L,
) : Crawler {
    private val downloader = MediaDownloader(http, cacheDir, downloadDelayMs)

    override val id: String = "zhihu.user"
    override val displayName: String = "知乎用户"

    override suspend fun collect(
        request: CrawlRequest,
        session: CrawlSession,
        sink: MediaSink,
        listener: CrawlListener,
    ): CrawlReport {
        val token = normalizeZhihuSubject(request.subject)
        require(token.isNotBlank()) { "Zhihu user token or profile URL is required" }

        val items = mutableListOf<CollectedItem>()
        val warnings = mutableListOf<String>()
        val seen = HashSet<String>()
        val since = request.since
        var newestAt = 0L
        if (session.cookies.isEmpty()) warnings += "未提供知乎 Cookie：接口可能返回 401/403（需要登录）"

        // Phase 1: paginate through each content kind, recording media without downloading.
        for (kind in kinds) {
            var offset = 0
            var page = 0
            while (page < maxPagesPerKind) {
                listener.onProgress(CrawlProgress(kind.name, page + 1, null, "offset=$offset，已发现 ${items.size} 项"))
                val url = buildZhihuListUrl(token, kind, offset, pageSize)
                var response = http.get(url, session, REFERER, API_HEADERS)
                if (!usable(response, kind, warnings)) break
                var parsed = parseZhihuListPage(response.body, kind, REFERER, offset, upgradeImages)
                // A chunked/truncated body can slip past the HTTP Content-Length check.
                // Re-fetch once before turning a transient cut-off into a crawl failure.
                if (parsed.error?.contains("响应被截断") == true) {
                    response = http.get(url, session, REFERER, API_HEADERS)
                    if (!usable(response, kind, warnings)) break
                    parsed = parseZhihuListPage(response.body, kind, REFERER, offset, upgradeImages)
                }
                if (parsed.error != null) {
                    warnings += "知乎 ${kind.name}: ${parsed.error}"
                    break
                }
                if (parsed.contents.isEmpty()) break

                val pageTimes = mutableListOf<Long?>()
                for (content in parsed.contents) {
                    pageTimes += content.createdAt
                    content.createdAt?.let { if (it > newestAt) newestAt = it }
                    if (isBeforeWatermark(content.createdAt, since)) continue
                    val candidates = mutableListOf<CollectedItem>()
                    for (asset in content.media) {
                        val folder = if (asset.kind == MediaKind.IMAGE) "image" else "video"
                        candidates += CollectedItem.Media(asset.copy(relativeDir = "zhihu/$token/$folder"))
                    }
                    if (request.includeText && content.html.isNotBlank()) {
                        candidates += CollectedItem.Text(
                            sourceUrl = content.url,
                            title = content.title,
                            body = content.html,
                            createdAt = content.createdAt,
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

                if (reachedWatermark(pageTimes, since)) break
                val next = parsed.nextOffset
                if (next == null || next <= offset) break
                offset = next
                page++
            }
        }

        // Phase 2: the list is complete, so the download progress has a real total.
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
            id, token, items, saved, warnings,
            newestItemAt = newestAt.takeIf { it > 0 },
        )
    }

    private fun usable(response: HttpResponse, kind: ZhihuKind, warnings: MutableList<String>): Boolean =
        when {
            response.status == 401 || response.status == 403 -> {
                warnings += "知乎 ${kind.name} 需要登录或触发风控（HTTP ${response.status}）"
                false
            }
            !response.isSuccess -> {
                warnings += "知乎 ${kind.name} 返回 HTTP ${response.status}"
                false
            }
            else -> true
        }
}
