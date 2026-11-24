package com.geno1024.ai.worm.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.worm.android.site.Site
import com.geno1024.ai.worm.android.site.SiteCookies
import com.geno1024.ai.worm.android.site.Source
import com.geno1024.ai.worm.android.storage.createMediaSink
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.Crawler
import com.geno1024.ai.worm.core.HttpPortal
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.MediaSink
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
    val source: Source = Source.QZONE_SHUOSHUO,
    val subject: String = "",
    val qqCookies: Map<String, String> = emptyMap(),
    val zhihuCookies: Map<String, String> = emptyMap(),
    val storageLabel: String = "应用私有目录",
    val running: Boolean = false,
    val progress: CrawlProgress? = null,
    val warnings: List<String> = emptyList(),
    val result: String? = null,
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
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var sink: MediaSink = createMediaSink(app, prefs.getString(KEY_STORAGE, null))

    init {
        refreshCookies()
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
                error = null,
            )
        }
    }

    fun start() {
        if (_state.value.running) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(running = true, progress = null, warnings = emptyList(), result = null, error = null)
            }
            try {
                val current = _state.value
                val session = sessionFor(current)
                val http: HttpPortal = JdkHttpPortal(
                    userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36 worm-android",
                    delayMs = 800L,
                )
                val crawler = crawlerFor(current.source, http, File(app.cacheDir, "downloads"))
                val displayName = crawler.displayName
                val liveWarnings = Collections.synchronizedList(mutableListOf<String>())
                val listener = object : CrawlListener {
                    override fun onProgress(progress: CrawlProgress) {
                        _state.update { it.copy(progress = progress) }
                    }

                    override fun onWarning(message: String) {
                        liveWarnings.add(message)
                    }
                }
                val report = crawler.collect(
                    CrawlRequest(current.subject.trim()),
                    session,
                    sink,
                    listener,
                )
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

    private fun crawlerFor(source: Source, http: HttpPortal, cacheDir: File): Crawler =
        when (source) {
            Source.QZONE_SHUOSHUO -> ShuoshuoCrawler(http, cacheDir)
            Source.QZONE_ALBUM -> AlbumCrawler(http, cacheDir)
            Source.ZHIHU -> ZhihuCrawler(http, cacheDir)
        }

    private companion object {
        const val KEY_STORAGE = "storageTreeUri"
    }
}
