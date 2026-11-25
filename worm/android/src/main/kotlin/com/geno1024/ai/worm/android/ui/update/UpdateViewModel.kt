package com.geno1024.ai.worm.android.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.worm.android.update.ApkVerifier
import com.geno1024.ai.worm.android.update.UpdatePrefs
import com.geno1024.ai.worm.android.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UpdateUiState(
    val installedVersion: String = "",
    val source: Updater.Source = Updater.PRESETS.first(),
    val customPrefix: String = "",
    val available: Updater.Release? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val downloaded: File? = null,
    val error: String? = null,
    val message: String? = null,
)

/**
 * Checks for a newer canary and fetches it.
 *
 * The installed version comes from the package manager, never from a saved copy, so it
 * cannot drift from what is running. Metadata comes from GitHub's API only; mirrors are
 * chosen here solely for the APK transfer.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val prefs = UpdatePrefs(app)
    private val installedVersionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private val state = MutableStateFlow(
        UpdateUiState(
            installedVersion = installedVersionName.ifBlank { "未知" },
            source = Updater.sourceFrom(prefs.sourceId, prefs.customPrefix),
            customPrefix = prefs.customPrefix,
        ),
    )
    val uiState: StateFlow<UpdateUiState> = state.asStateFlow()

    // Both flags are flipped on the caller's (main) thread before the coroutine starts,
    // so a second tap cannot start a second flight even before the flow is collected.
    private var checkInFlight = false
    private var downloadInFlight = false

    init {
        check()
    }

    /** A revisit re-checks only when the last answer could have gone stale. */
    fun recheckIfStale(nowMillis: Long = System.currentTimeMillis()) {
        if (nowMillis - prefs.lastCheckedAt >= REVISIT_AFTER_MILLIS) check()
    }

    fun check() {
        if (checkInFlight) return
        checkInFlight = true
        prefs.lastCheckedAt = System.currentTimeMillis()
        state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { Updater.fetchReleases() } }
            outcome
                .onSuccess { releases ->
                    when {
                        releases.isEmpty() -> state.update {
                            it.copy(available = null, message = "已是最新版本。")
                        }
                        releases.none { release -> release.version != null } -> state.update {
                            // A feed of canaries we cannot stamp is not a feed of updates;
                            // saying "found something" here would be a guess (AGENTS.md).
                            it.copy(available = null, error = "版本号无法识别")
                        }
                        else -> {
                            val current = parseInstalledVersion()
                            if (current == null) {
                                state.update {
                                    it.copy(available = null, error = "版本号无法识别（已安装版本）")
                                }
                            } else {
                                val found = Updater.newer(current, releases)
                                val dismissed = prefs.dismissedVersion
                                state.update {
                                    it.copy(
                                        available = found,
                                        // A dismissed build stays available for manual install,
                                        // it just stops being announced as news.
                                        message = when {
                                            found == null -> "已是最新版本。"
                                            found.version?.toString() == dismissed -> "${found.name} 可以安装。"
                                            else -> "发现新版本 ${found.name}。"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                .onFailure { failure ->
                    state.update { it.copy(error = "检查失败：${failure.message ?: "未知原因"}") }
                }
            state.update { it.copy(checking = false) }
            checkInFlight = false
        }
    }

    fun setSource(source: Updater.Source) {
        prefs.sourceId = source.id
        state.update { it.copy(source = source) }
    }

    fun setCustomPrefix(value: String) {
        prefs.customPrefix = value
        state.update {
            val source = if (value.isBlank() && it.source.id == "custom") {
                Updater.PRESETS.first()
            } else {
                it.source
            }
            it.copy(customPrefix = value, source = source)
        }
    }

    /** Keeps the offer installable, only stops announcing it. */
    fun dismiss() {
        val found = state.value.available ?: return
        found.version?.let { prefs.dismissedVersion = it.toString() }
        state.update { it.copy(message = "已忽略该版本的更新提示，仍可在下方安装。") }
    }

    fun download() {
        if (downloadInFlight) return
        val release = state.value.available ?: return
        downloadInFlight = true
        val target = File(app.cacheDir, "update/${release.apkName}")
        state.update {
            it.copy(
                downloading = true,
                error = null,
                message = null,
                downloaded = null,
                downloadedBytes = 0L,
                totalBytes = release.apkSize,
            )
        }
        viewModelScope.launch {
            val sources = Updater.orderedSources(state.value.source, state.value.customPrefix)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    Updater.download(
                        sources = sources,
                        release = release,
                        target = target,
                        expectedSize = release.apkSize,
                        onProgress = { done, total ->
                            val size = if (total > 0) total else release.apkSize
                            state.update { it.copy(downloadedBytes = done, totalBytes = size) }
                        },
                        verify = { apk ->
                            ApkVerifier.problem(app, apk, release)?.let { reason ->
                                throw IllegalStateException(reason)
                            }
                        },
                    )
                }
            }
            outcome
                .onSuccess {
                    reapOldApks(keep = target)
                    state.update { it.copy(downloaded = target, message = "已下载 ${target.name}。") }
                }
                .onFailure { failure ->
                    target.delete()
                    state.update { it.copy(error = "下载失败：${failure.message ?: "未知原因"}") }
                }
            state.update { it.copy(downloading = false) }
            downloadInFlight = false
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    /** Reports both endings of an install attempt back to the screen. */
    fun noteInstallOutcome(outcome: String) = state.update { it.copy(message = outcome) }

    private fun parseInstalledVersion(): Updater.Version? = Updater.parseVersion(installedVersionName)

    /** Cached APKs are per-build; keep only the one on offer so the cache cannot grow. */
    private fun reapOldApks(keep: File) {
        runCatching {
            File(app.cacheDir, "update").listFiles()?.forEach { file ->
                if (file != keep && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                    file.delete()
                }
            }
        }
    }

    private companion object {
        const val REVISIT_AFTER_MILLIS = 30 * 60 * 1000L
    }
}
