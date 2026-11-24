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
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.MediaDownloader
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.SavedMedia
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
        val saved = mutableListOf<SavedMedia>()
        val warnings = mutableListOf<String>()
        if (session.cookies.isEmpty()) warnings += "未提供知乎 Cookie：接口可能返回 401/403（需要登录）"

        for (kind in kinds) {
            var offset = 0
            var page = 0
            while (page < maxPagesPerKind && items.size < request.maxItems) {
                listener.onProgress(CrawlProgress(kind.name, page + 1, null, "offset=$offset"))
                val response = http.get(
                    buildZhihuListUrl(token, kind, offset, pageSize),
                    session,
                    REFERER,
                    API_HEADERS,
                )
                if (response.status == 401 || response.status == 403) {
                    warnings += "知乎 ${kind.name} 需要登录或触发风控（HTTP ${response.status}）"
                    break
                }
                if (!response.isSuccess) {
                    warnings += "知乎 ${kind.name} 返回 HTTP ${response.status}"
                    break
                }
                val parsed = parseZhihuListPage(response.body, kind, REFERER, offset)
                if (parsed.error != null) {
                    warnings += "知乎 ${kind.name}: ${parsed.error}"
                    break
                }
                if (parsed.contents.isEmpty()) break

                for (content in parsed.contents) {
                    if (items.size >= request.maxItems) break
                    for (asset in content.media) {
                        if (items.size >= request.maxItems) break
                        items += CollectedItem.Media(asset)
                        try {
                            downloader.download(asset, session, sink)?.let(saved::add)
                            listener.onProgress(CrawlProgress("媒体", saved.size, items.size, asset.url))
                        } catch (e: Exception) {
                            warnings += "下载失败 ${asset.url}: ${e.message}"
                        }
                    }
                    if (request.includeText && content.html.isNotBlank()) {
                        items += CollectedItem.Text(
                            sourceUrl = content.url,
                            title = content.title,
                            body = content.html,
                            createdAt = content.createdAt,
                        )
                    }
                }

                val next = parsed.nextOffset
                if (next == null || next <= offset) break
                offset = next
                page++
            }
        }
        return CrawlReport(id, token, items, saved, warnings)
    }
}
