package com.geno1024.ai.worm.android.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * Hands a cached APK to the system installer through a FileProvider.
 *
 * The two endings the UI must distinguish are "an installer was opened" and "this
 * device has nothing that can open an APK"; the caller reports both from its
 * ActivityResult callback.
 */
object UpdateInstaller {
    private const val MIME = "application/vnd.android.package-archive"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** `null` means no activity on the device can install the file. */
    fun installIntent(context: Context, apk: File): Intent? {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME).addFlags(flags)
        if (view.resolveActivity(context.packageManager) != null) return view

        // OEM firmwares sometimes only register the legacy action.
        @Suppress("DEPRECATION")
        val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, MIME).addFlags(flags)
        return legacy.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
