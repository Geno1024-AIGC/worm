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
import com.geno1024.ai.worm.core.isBeforeWatermark
import com.geno1024.ai.worm.core.MediaDownloader
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.objects
import com.geno1024.ai.worm.core.optLongOf
import com.geno1024.ai.worm.core.pageCountOf
import com.geno1024.ai.worm.core.reachedWatermark
import com.geno1024.ai.worm.core.qzone.QzoneAlbum
import com.geno1024.ai.worm.core.qzone.buildAlbumListUrl
import com.geno1024.ai.worm.core.qzone.buildPhotoListUrl
import com.geno1024.ai.worm.core.qzone.extractAlbumPhotoMedia
import com.geno1024.ai.worm.core.qzone.isAlbumPermissionError
import com.geno1024.ai.worm.core.qzone.isAuthError
import com.geno1024.ai.worm.core.qzone.normalizeUin
import com.geno1024.ai.worm.core.qzone.parseAlbumListResponse
import com.geno1024.ai.worm.core.qzone.parsePhotoListResponse
import com.geno1024.ai.worm.core.qzone.requireQzoneAuth
import java.io.File

private const val REFERER = "https://user.qzone.qq.com/"
private val PHOTO_HEADERS = mapOf("Origin" to "https://user.qzone.qq.com")

/** Collects media from all accessible Qzone albums. */
class AlbumCrawler(
    private val http: HttpPortal = JdkHttpPortal(),
    cacheDir: File = File(System.getProperty("java.io.tmpdir"), "worm-downloads"),
    private val pageSize: Int = 100,
    private val maxAlbumListPages: Int = 20,
    private val maxPagesPerAlbum: Int = 100,
    private val albumFilter: String? = null,
    downloadDelayMs: Long = 300L,
) : Crawler {
    private val downloader = MediaDownloader(http, cacheDir, downloadDelayMs)

    override val id: String = "qzone/album"
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
        // The photo CGIs use hostUin for the album owner and uin for the viewer.
        // Guessing the viewer from the target is what makes every non-self album fail.
        val viewerUin = auth.loginUin?.let(::normalizeUin)?.takeIf { it.isNotBlank() && it != "0" }
            ?: throw CrawlException("Qzone albums require the login UIN from cookie 'uin'/'p_uin'")

        val items = mutableListOf<CollectedItem>()
        val warnings = mutableListOf<String>()
        val seen = HashSet<String>()
        val since = request.since
        var newestAt = 0L

        // Phase 1a: page through the album list. mode=3 is required to get every
        // album, not just the recent ones, and pageStart/pageNum control the window.
        val allAlbums = LinkedHashMap<String, QzoneAlbum>()
        var albumStart = 0
        var albumPage = 0
        while (albumPage < maxAlbumListPages) {
            val result = parseAlbumListResponse(http.get(
                buildAlbumListUrl(targetUin, viewerUin, auth.gtk, albumStart, pageSize),
                auth.session, REFERER, PHOTO_HEADERS,
            ).body)
            val listPages = pageCountOf(result.albumsInUser, pageSize)
            listener.onProgress(CrawlProgress(
                "相册列表", albumPage + 1, listPages,
                "已发现 ${allAlbums.size} 本相册${listPages?.let { "，共 $it 页" } ?: ""}",
            ))
            if (isAuthError(result.code, result.subcode)) {
                warnings += "登录态失效（code=${result.code}），请重新登录"
                break
            }
            if (isAlbumPermissionError(result.code, result.subcode)) {
                warnings += "没有权限访问该空间的相册（code=${result.code}）"
                break
            }
            if (result.code != 0) {
                warnings += "相册列表返回错误：code=${result.code}, message=${result.message}"
                break
            }
            result.albums.forEach { allAlbums.putIfAbsent(it.id, it) }
            if (result.albums.isEmpty()) break
            val next = result.nextPageStart ?: (albumStart + result.totalInPage)
            if (next <= albumStart) break
            albumStart = next
            val total = result.albumsInUser
            if (total != null && albumStart >= total) break
            if (!result.hasMore || result.albums.size < pageSize) break
            albumPage++
        }

        val albums = allAlbums.values.filter { album ->
            album.allowAccess && (albumFilter.isNullOrBlank() ||
                album.name.contains(albumFilter, ignoreCase = true) || album.id == albumFilter)
        }
        if (albums.isEmpty()) warnings += "没有可访问的相册"

        // Phase 1b: list every photo in each accessible album, collecting assets only.
        for ((albumIndex, album) in albums.withIndex()) {
            var start = 0
            var page = 0
            while (page < maxPagesPerAlbum) {
                val photoPage = parsePhotoListResponse(http.get(
                    buildPhotoListUrl(targetUin, viewerUin, auth.gtk, album.id, start, pageSize),
                    auth.session, REFERER, PHOTO_HEADERS,
                ).body)
                val albumPages = pageCountOf(photoPage.totalInAlbum, pageSize)
                listener.onProgress(CrawlProgress(
                    "相册", albumIndex + 1, albums.size,
                    "${album.name} · 第${page + 1}页${albumPages?.let { "/$it" } ?: ""}，已发现 ${items.size} 项",
                ))
                if (isAuthError(photoPage.code, photoPage.subcode)) {
                    warnings += "${album.name}: 登录态失效（code=${photoPage.code}）"
                    break
                }
                if (isAlbumPermissionError(photoPage.code, photoPage.subcode)) {
                    warnings += "${album.name}: 没有权限访问（code=${photoPage.code}）"
                    break
                }
                if (photoPage.code != 0) {
                    warnings += "${album.name}: 照片列表错误 code=${photoPage.code}, message=${photoPage.message}"
                    break
                }
                val photos = photoPage.photos.objects()
                if (photos.isEmpty()) break

                // One folder per album, under the target QQ. Images and videos stay
                // together because their extensions already tell them apart.
                val relativeDir = "qzone/$targetUin/${MediaFiles.sanitize(album.name)}"
                val pageTimes = mutableListOf<Long?>()
                for (photo in photos) {
                    val time = photo.optLongOf("uploadtime", "uploadTime", "rawshoottime", "shotTime")
                    pageTimes += time
                    time?.let { if (it > newestAt) newestAt = it }
                    if (isBeforeWatermark(time, since)) continue
                    for (asset in extractAlbumPhotoMedia(photo, relativeDir)) {
                        if (seen.add("${asset.kind.name}:${asset.url}")) items += CollectedItem.Media(asset)
                    }
                }

                if (reachedWatermark(pageTimes, since)) break
                val next = photoPage.nextPageStart ?: (start + photoPage.totalInPage)
                if (next <= start) break
                start = next
                val total = photoPage.totalInAlbum
                if (total != null && start >= total) break
                if (!photoPage.hasMore || photos.size < pageSize) break
                page++
            }
        }

        // Phase 2: download the discovered list with a determinate total.
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
