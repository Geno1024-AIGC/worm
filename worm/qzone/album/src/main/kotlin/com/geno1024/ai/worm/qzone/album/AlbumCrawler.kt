package com.geno1024.ai.worm.qzone.album

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
import com.geno1024.ai.worm.core.objects
import com.geno1024.ai.worm.core.qzone.buildAlbumListUrl
import com.geno1024.ai.worm.core.qzone.buildPhotoListUrl
import com.geno1024.ai.worm.core.qzone.extractAlbumPhotoMedia
import com.geno1024.ai.worm.core.qzone.isAuthError
import com.geno1024.ai.worm.core.qzone.normalizeUin
import com.geno1024.ai.worm.core.qzone.parseAlbumListResponse
import com.geno1024.ai.worm.core.qzone.parsePhotoListResponse
import com.geno1024.ai.worm.core.qzone.requireQzoneAuth
import java.io.File

private const val REFERER = "https://user.qzone.qq.com/"

/** Collects media from all accessible Qzone albums. */
class AlbumCrawler(
    private val http: HttpPortal = JdkHttpPortal(),
    cacheDir: File = File(System.getProperty("java.io.tmpdir"), "worm-downloads"),
    private val pageSize: Int = 100,
    private val maxPagesPerAlbum: Int = 100,
    private val albumFilter: String? = null,
    downloadDelayMs: Long = 300L,
) : Crawler {
    private val downloader = MediaDownloader(http, cacheDir, downloadDelayMs)

    override val id: String = "qzone.album"
    override val displayName: String = "QQ空间相册"

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

        val albumPage = parseAlbumListResponse(
            http.get(buildAlbumListUrl(targetUin, auth.gtk), auth.session, REFERER).body,
        )
        if (isAuthError(albumPage.code, albumPage.subcode)) {
            warnings += "登录态失效（code=${albumPage.code}），请重新登录"
            return CrawlReport(id, targetUin, items, emptyList(), warnings)
        }
        if (albumPage.code != 0) {
            warnings += "相册列表返回错误：code=${albumPage.code}, message=${albumPage.message}"
            return CrawlReport(id, targetUin, items, emptyList(), warnings)
        }
        val albums = albumPage.albums.filter { album ->
            album.allowAccess && (albumFilter.isNullOrBlank() ||
                album.name.contains(albumFilter, ignoreCase = true) || album.id == albumFilter)
        }
        if (albums.isEmpty()) warnings += "没有可访问的相册"

        // Phase 1: list every accessible album page, collecting assets only.
        for ((albumIndex, album) in albums.withIndex()) {
            var start = 0
            var page = 0
            while (page < maxPagesPerAlbum) {
                listener.onProgress(CrawlProgress(
                    "相册", albumIndex + 1, albums.size,
                    "${album.name} · 第${page + 1}页，已发现 ${items.size} 项",
                ))
                val photoPage = parsePhotoListResponse(
                    http.get(
                        buildPhotoListUrl(targetUin, auth.gtk, album.id, start, pageSize),
                        auth.session,
                        REFERER,
                    ).body,
                )
                if (isAuthError(photoPage.code, photoPage.subcode)) {
                    warnings += "${album.name}: 登录态失效（code=${photoPage.code}）"
                    break
                }
                if (photoPage.code != 0) {
                    warnings += "${album.name}: 照片列表错误 code=${photoPage.code}, message=${photoPage.message}"
                    break
                }
                val photos = photoPage.photos.objects()
                if (photos.isEmpty()) break

                for (photo in photos) {
                    for (asset in extractAlbumPhotoMedia(photo, album.name)) {
                        if (seen.add("${asset.kind.name}:${asset.url}")) items += CollectedItem.Media(asset)
                    }
                }

                val total = photoPage.totalInAlbum
                if (total != null && start + photos.size >= total) break
                if (photos.size < pageSize) break
                start += photos.size
                page++
            }
        }

        // Phase 2: download the discovered list with a determinate total.
        val saved = downloader.downloadAll(
            assets = items.filterIsInstance<CollectedItem.Media>().map { it.asset },
            session = auth.session,
            sink = sink,
            onProgress = { current, total, asset ->
                listener.onProgress(CrawlProgress("媒体", current, total, asset.url))
            },
            onFailure = { asset, error -> warnings += "下载失败 ${asset.url}: ${error.message}" },
        )
        return CrawlReport(id, targetUin, items, saved, warnings)
    }
}
