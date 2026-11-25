package com.geno1024.ai.worm.qzone.shuoshuo

import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.CrawlException
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlReport
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.Crawler
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.HtmlMedia
import com.geno1024.ai.worm.core.isBeforeWatermark
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaDownloader
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.optLongOf
import com.geno1024.ai.worm.core.optStringOf
import com.geno1024.ai.worm.core.objects
import com.geno1024.ai.worm.core.pageCountOf
import com.geno1024.ai.worm.core.reachedWatermark
import com.geno1024.ai.worm.core.qzone.buildMsgListUrl
import com.geno1024.ai.worm.core.qzone.extractMessageMedia
import com.geno1024.ai.worm.core.qzone.isAuthError
import com.geno1024.ai.worm.core.qzone.normalizeUin
import com.geno1024.ai.worm.core.qzone.parseMsgListResponse
import com.geno1024.ai.worm.core.qzone.requireQzoneAuth
import org.jsoup.Jsoup
import java.io.File

private const val PAGE_SIZE = 20
private const val REFERER = "https://user.qzone.qq.com/"

/** Collects media from a QQ zone's shuoshuo timeline. */
class ShuoshuoCrawler(
    private val http: HttpPortal = JdkHttpPortal(),
    cacheDir: File = File(System.getProperty("java.io.tmpdir"), "worm-downloads"),
    private val maxPages: Int = 100,
    downloadDelayMs: Long = 300L,
) : Crawler {
    private val downloader = MediaDownloader(http, cacheDir, downloadDelayMs)

    override val id: String = "qzone"
    override val displayName: String = "QQ空间说说"

    override suspend fun collect(
        request: CrawlRequest,
        session: CrawlSession,
        sink: MediaSink,
        listener: CrawlListener,
    ): CrawlReport {
        val auth = requireQzoneAuth(session)
        val target = request.subject.ifBlank { auth.loginUin }
            ?: throw CrawlException("target QQ number is required when the login UIN is unknown")
        val targetUin = normalizeUin(target)
        require(targetUin.isNotBlank() && targetUin != "0") { "invalid QQ number: $target" }

        val items = mutableListOf<CollectedItem>()
        val warnings = mutableListOf<String>()
        val seen = HashSet<String>()
        val since = request.since
        var newestAt = 0L
        var pos = 0
        var page = 0

        // Phase 1: discover every accessible page, without touching the network for bytes.
        while (page < maxPages) {
            val body = http.get(buildMsgListUrl(targetUin, auth.gtk, pos, PAGE_SIZE), auth.session, REFERER).body
            val parsed = parseMsgListResponse(body)
            val pages = pageCountOf(parsed.total, PAGE_SIZE)
            listener.onProgress(CrawlProgress(
                "说说列表", page + 1, pages, "pos=$pos${pages?.let { "，共 $it 页" } ?: ""}",
            ))
            if (isAuthError(parsed.code, parsed.subcode)) {
                warnings += "登录态失效（code=${parsed.code}, subcode=${parsed.subcode}），请重新登录"
                break
            }
            if (parsed.code != 0) {
                warnings += "说说接口返回错误：code=${parsed.code}, message=${parsed.message}"
                break
            }

            val messages = parsed.messages.objects()
            if (messages.isEmpty()) break

            val pageTimes = mutableListOf<Long?>()
            for (message in messages) {
                val content = message.optStringOf("content", "contenttext")
                val time = message.optLongOf("created_time", "time")
                pageTimes += time
                time?.let { if (it > newestAt) newestAt = it }
                if (isBeforeWatermark(time, since)) continue
                val assets = linkedMapOf<String, MediaAsset>()
                extractMessageMedia(message).forEach { assets.putIfAbsent("${it.kind.name}:${it.url}", it) }
                if (!content.isNullOrBlank()) {
                    HtmlMedia.extract(content, "https://user.qzone.qq.com/$targetUin", REFERER)
                        .forEach { assets.putIfAbsent("${it.kind.name}:${it.url}", it) }
                }

                val candidates = mutableListOf<CollectedItem>()
                for (asset in assets.values) {
                    // Images and videos share this folder; their extensions tell them apart.
                    candidates += CollectedItem.Media(asset.copy(relativeDir = "qzone/$targetUin"))
                }
                if (request.includeText && !content.isNullOrBlank()) {
                    candidates += CollectedItem.Text(
                        sourceUrl = "https://user.qzone.qq.com/$targetUin/" +
                            (message.optStringOf("tid", "id") ?: "post"),
                        title = message.optStringOf("title", "tid"),
                        body = Jsoup.parse(content).text(),
                        createdAt = time,
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
                warnings += "增量抓取在第 ${page + 1} 页命中上次抓取时间，提前结束（接口显示共 ${pages ?: "?"} 页）"
                break
            }
            if (!parsed.hasMore) break
            if (messages.size < PAGE_SIZE) break
            val total = parsed.total
            if (total != null && pos + messages.size >= total) break
            pos += messages.size
            page++
        }

        // Phase 2: the asset list is fixed, so the progress bar has a real total.
        val assets = items.filterIsInstance<CollectedItem.Media>().map { it.asset }
        listener.onDiscovered(assets)
        val saved = downloader.downloadAll(
            assets = assets,
            session = auth.session,
            sink = sink,
            onProgress = { current, total, asset ->
                listener.onProgress(CrawlProgress("媒体", current, total, asset.url))
            },
            onFailure = { asset, error -> warnings += "下载失败 ${asset.url}: ${error.message}" },
            onDone = { asset -> listener.onAssetDone(asset) },
        )
        return CrawlReport(
            id, targetUin, items, saved, warnings,
            newestItemAt = newestAt.takeIf { it > 0 },
        )
    }
}
