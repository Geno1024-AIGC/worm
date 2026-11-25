package com.geno1024.ai.worm.android.update

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * Hands a cached APK to the system installer.
 *
 * The same file opens fine from a file manager but can fail with "problem parsing the
 * package" when it is handed over ourselves, because some OEM installers cannot read a
 * third-party FileProvider Uri. On Android 10+ we therefore publish the APK into
 * MediaStore.Downloads first: the installer can always open that content Uri. FileProvider
 * stays as the fallback for older devices and if MediaStore is unavailable.
 *
 * The two endings the UI must distinguish are "an installer was opened" and "this
 * device has nothing that can open an APK"; the caller reports both from its
 * ActivityResult callback.
 */
object UpdateInstaller {
    private const val MIME = "application/vnd.android.package-archive"
    private val DOWNLOADS_DIR = "${Environment.DIRECTORY_DOWNLOADS}/worm"

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
        // A MediaStore Uri is readable by every installer; a third-party provider's
        // Uri is not always. Prefer it where the collection exists.
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloads(context, apk) ?: fileProviderUri(context, apk)
        } else {
            fileProviderUri(context, apk)
        }

        val view = installerIntent(Intent.ACTION_VIEW, uri, context)
        if (view.resolveActivity(context.packageManager) != null) return view

        // OEM firmwares sometimes only register the legacy action.
        @Suppress("DEPRECATION")
        val legacy = installerIntent(Intent.ACTION_INSTALL_PACKAGE, uri, context)
        return legacy.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private fun installerIntent(action: String, uri: Uri, context: Context): Intent = Intent(action)
        .setDataAndType(uri, MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some installers read the grant from ClipData instead of the intent's data.
        .apply { clipData = ClipData.newUri(context.contentResolver, "worm-update", uri) }

    private fun fileProviderUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)

    /**
     * Copy the cached APK into the shared Downloads collection and return its Uri. The
     * entry is written while IS_PENDING=1, so no installer can see a half-written file;
     * it is made visible only once the copy completes. `null` means MediaStore refused
     * it and the caller should fall back to FileProvider.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(context: Context, apk: File): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            val output = resolver.openOutputStream(uri) ?: error("MediaStore output stream unavailable")
            output.use { apk.inputStream().use { input -> input.copyTo(it) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }.getOrNull()
}
