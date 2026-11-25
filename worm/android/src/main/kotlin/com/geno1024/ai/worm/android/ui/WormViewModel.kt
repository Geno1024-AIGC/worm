package com.geno1024.ai.worm.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.worm.android.site.Site
import com.geno1024.ai.worm.android.site.SiteCookies
import com.geno1024.ai.worm.android.site.Source
import com.geno1024.ai.worm.android.storage.SafMediaSink
import com.geno1024.ai.worm.android.storage.createMediaSink
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.Crawler
import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.ResumeQueue
import com.geno1024.ai.worm.core.ResumeRunner
import com.geno1024.ai.worm.core.pageCapOf
import com.geno1024.ai.worm.core.qzone.requireQzoneAuth
import com.geno1024.ai.worm.qzone.album.AlbumCrawler
import com.geno1024.ai.worm.qzone.shuoshuo.ShuoshuoCrawler
import com.geno1024.ai.worm.zhihu.ZhihuCrawler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

data class UiState(
    val source: Source = Source.QZONE,
    val subject: String = "",
    val pageLimit: String = "10",
    val albumFilter: String = "",
    val qqCookies: Map<String, String> = emptyMap(),
    val zhihuCookies: Map<String, String> = emptyMap(),
    val storageLabel: String = "应用私有目录",
    val storageUri: String? = null,
    val running: Boolean = false,
    val progress: CrawlProgress? = null,
    val warnings: List<String> = emptyList(),
    val result: String? = null,
    val resume: String? = null,
    val error: String? = null,
) {
    val cookies: Map<String, String>
        get() = when (source.site) {
            Site.QQ -> qqCookies
            Site.ZHIHU -> zhihuCookies
        }

    val loginReady: Boolean get() = SiteCookies.hasLogin(source.site, cookies)
}

class WormViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("worm", Context.MODE_PRIVATE)
    private val storedTreeUri = prefs.getString(KEY_STORAGE, null)
    private val _state = MutableStateFlow(UiState(storageUri = storedTreeUri))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var sink: MediaSink = createMediaSink(app, storedTreeUri)
    private val queue = ResumeQueue(File(app.filesDir, "worm/resume-queue.json"))

    init {
        refreshCookies()
        resumePending()
    }

    /**
     * Replays an unfinished batch before accepting a new crawl. A missing login or an
     * unreadable SAF tree keeps the queue intact, so the next launch can try again;
     * nothing is discarded just because the app started before the user logged in.
     */
    private fun resumePending() {
        val pending = queue.load() ?: return
        if (pending.assets.isEmpty()) {
            queue.clear()
            return
        }
        _state.update { it.copy(running = true, progress = null, resume = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val warnings = Collections.synchronizedList(mutableListOf<String>())
            try {
                val source = sourceFor(pending.crawlerId)
                    ?: throw IllegalStateException("未知续传任务：${pending.crawlerId}")
                val session = sessionFor(_state.value.copy(source = source))
                val listener = object : CrawlListener {
                    override fun onProgress(progress: CrawlProgress) {
                        _state.update { it.copy(progress = progress) }
                    }
                    override fun onWarning(message: String) {
                        warnings.add(message)
                    }
                }
                val saved = ResumeRunner(
                    queue = queue,
                    http = httpPortal(),
                    session = session,
                    sink = sinkFor(pending.sink),
                    cacheDir = File(app.cacheDir, "downloads"),
                    listener = listener,
                    delayMs = 800L,
                ).drain()
                _state.update {
                    it.copy(
                        running = false,
                        progress = null,
                        resume = "续传完成：新下载 ${saved.count { item -> !item.existed }} 项",
                        warnings = warnings.toList(),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        running = false,
                        progress = null,
                        resume = "待续传 ${pending.assets.size} 项：${e.message ?: e.javaClass.simpleName}",
                        warnings = warnings.toList(),
                    )
                }
            }
        }
    }

    fun refreshCookies() {
        _state.update {
            it.copy(
                qqCookies = SiteCookies.read(Site.QQ),
                zhihuCookies = SiteCookies.read(Site.ZHIHU),
            )
        }
    }

    fun selectSource(source: Source) {
        _state.update { it.copy(source = source, result = null, error = null) }
    }

    fun updateSubject(value: String) {
        _state.update { it.copy(subject = value) }
    }

    fun updatePageLimit(value: String) {
        _state.update { it.copy(pageLimit = value) }
    }

    fun updateAlbumFilter(value: String) {
        _state.update { it.copy(albumFilter = value) }
    }

    fun useStorage(context: Context, uri: Uri?) {
        if (uri != null) {
            val tree = DocumentFile.fromTreeUri(context, uri)
            if (tree == null || !tree.canWrite()) {
                _state.update { it.copy(error = "所选目录不可写，请换一个目录") }
                return
            }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        }
        val value = uri?.toString()
        prefs.edit().putString(KEY_STORAGE, value).apply()
        sink = createMediaSink(context.applicationContext, value)
        _state.update {
            it.copy(
                storageLabel = uri?.let { DocumentFile.fromTreeUri(context, it)?.name }?.takeIf { name ->
                    name.isNotBlank()
                } ?: "应用私有目录",
                storageUri = value,
                error = null,
            )
        }
    }

    fun start() {
        if (_state.value.running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(running = true, progress = null, warnings = emptyList(), result = null, resume = null, error = null)
            }
            try {
                val current = _state.value
                val session = sessionFor(current)
                val http = httpPortal()
                val crawler = crawlerFor(current, http, File(app.cacheDir, "downloads"))
                val displayName = crawler.displayName
                val liveWarnings = Collections.synchronizedList(mutableListOf<String>())
                val listener = appListener(crawler.id, current.subject.trim(), liveWarnings)
                val report = crawler.collect(
                    CrawlRequest(current.subject.trim()),
                    session,
                    sink,
                    listener,
                )
                queue.clear()
                val fresh = report.saved.count { !it.existed }
                val existing = report.saved.count { it.existed }
                val warnings = (liveWarnings + report.warnings).distinct()
                _state.update {
                    it.copy(
                        running = false,
                        result = "$displayName：发现 ${report.items.size} 项，新下载 $fresh 项，" +
                            "已存在 $existing 项，跳过 ${report.items.size - report.saved.size} 项",
                        warnings = warnings,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(running = false, error = e.message?.takeIf { text -> text.isNotBlank() }
                        ?: "抓取失败：${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Checkpoints the discovered batch and drops each settled asset from the queue. */
    private fun appListener(
        crawlerId: String,
        subject: String,
        warnings: MutableList<String>,
    ): CrawlListener = object : CrawlListener {
        override fun onProgress(progress: CrawlProgress) {
            _state.update { it.copy(progress = progress) }
        }

        override fun onWarning(message: String) {
            warnings.add(message)
        }

        override fun onDiscovered(assets: List<MediaAsset>) {
            if (assets.isNotEmpty()) {
                queue.save(ResumeQueue.Batch(crawlerId, subject, sinkRef(), assets.size, assets))
            }
        }

        override fun onAssetDone(asset: MediaAsset) {
            queue.remove(asset)
        }
    }

    private fun sinkRef(): ResumeQueue.SinkRef {
        val tree = prefs.getString(KEY_STORAGE, null)
        return if (!tree.isNullOrBlank()) {
            ResumeQueue.SinkRef(ResumeQueue.SinkRef.SAF, tree)
        } else {
            ResumeQueue.SinkRef(
                ResumeQueue.SinkRef.DIRECTORY,
                File(app.filesDir, "worm-media").absolutePath,
            )
        }
    }

    /** Rebuilds the exact sink the interrupted run used; a revoked SAF grant is not silently redirected. */
    private fun sinkFor(ref: ResumeQueue.SinkRef): MediaSink = when (ref.kind) {
        ResumeQueue.SinkRef.DIRECTORY -> DirectoryMediaSink(File(ref.root))
        ResumeQueue.SinkRef.SAF -> {
            val uri = runCatching { ref.root.toUri() }.getOrNull()
                ?: throw IllegalStateException("续传目录无效，请重新选择保存目录")
            val tree = DocumentFile.fromTreeUri(app, uri)
            if (tree == null || !tree.canWrite()) {
                throw IllegalStateException("续传目录权限已失效，请重新选择保存目录")
            }
            SafMediaSink(app, uri)
        }
        else -> throw IllegalStateException("未知的续传目标：${ref.kind}")
    }

    private fun sourceFor(crawlerId: String): Source? = when (crawlerId) {
        "qzone", "qzone.shuoshuo" -> Source.QZONE // old queues keep working
        "qzone/album", "qzone.album" -> Source.QZONE_ALBUM
        "zhihu.user" -> Source.ZHIHU
        else -> null
    }

    private fun httpPortal(): HttpPortal = JdkHttpPortal(
        userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36 worm-android",
        delayMs = 800L,
    )

    private fun sessionFor(state: UiState): CrawlSession {
        val cookies = state.cookies
        if (cookies.isEmpty()) {
            throw IllegalStateException("尚未获取登录 Cookie：请先登录 ${state.source.site.label}")
        }
        when (state.source.site) {
            Site.QQ -> runCatching { requireQzoneAuth(CrawlSession(cookies)) }
                .onFailure { throw IllegalStateException("QQ 登录态缺少 p_skey/skey，请重新登录空间") }
            Site.ZHIHU -> if (cookies["z_c0"].isNullOrBlank()) {
                throw IllegalStateException("知乎登录态缺少 z_c0，请重新登录知乎")
            }
        }
        return CrawlSession(cookies, cookies["uin"] ?: cookies["p_uin"])
    }

    private fun crawlerFor(state: UiState, http: HttpPortal, cacheDir: File): Crawler {
        val cap = pageCapOf(state.pageLimit)
        return when (state.source) {
            Source.QZONE -> ShuoshuoCrawler(http, cacheDir, maxPages = cap)
            Source.QZONE_ALBUM -> AlbumCrawler(
                http, cacheDir,
                maxPagesPerAlbum = cap,
                albumFilter = state.albumFilter.trim().ifBlank { null },
            )
            Source.ZHIHU -> ZhihuCrawler(http, cacheDir, maxPagesPerKind = cap)
        }
    }

    private companion object {
        const val KEY_STORAGE = "storageTreeUri"
    }
}
