package com.geno1024.ai.worm.android.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Checks a downloaded file with the system's own APK parser before we hand it to the
 * installer. A mirror that returns a padded page, a stale build or a re-signed APK then
 * fails here and [Updater.download] can try the next source instead of showing the
 * installer's generic "problem parsing the package" dialog.
 */
object ApkVerifier {
    /** `null` means the file matches the release on offer and can be installed. */
    fun problem(context: Context, apk: File, release: Updater.Release): String? {
        if (!apk.isFile || apk.length() <= 0) return "下载的文件为空"

        @Suppress("DEPRECATION") // GET_SIGNATURES works down to minSdk 26.
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            ?: return "下载的文件无法被系统解析为 APK（可能已损坏或不是 APK）"

        val stamp = info.versionName?.let(Updater::parseVersion)
            ?: return "APK 版本号无法识别：${info.versionName ?: "未知"}"
        val expected = release.version
        if (expected != null && stamp != expected) {
            return "APK 与更新信息不符：${stamp}（应为 ${expected}）"
        }

        @Suppress("DEPRECATION")
        val apkSignatures = info.signatures?.map { it.toCharsString() }
        if (!apkSignatures.isNullOrEmpty()) {
            @Suppress("DEPRECATION")
            val installed = runCatching {
                context.packageManager.getPackageInfo(info.packageName, PackageManager.GET_SIGNATURES)
            }.getOrNull()
            @Suppress("DEPRECATION")
            val installedSignatures = installed?.signatures?.map { it.toCharsString() }
            if (!installedSignatures.isNullOrEmpty() && installedSignatures != apkSignatures) {
                return "APK 签名与已安装版本不一致，请先卸载旧版本"
            }
        }
        return null
    }
}
