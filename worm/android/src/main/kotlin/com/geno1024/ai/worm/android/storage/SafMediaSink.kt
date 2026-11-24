package com.geno1024.ai.worm.android.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.SavedMedia
import java.io.File

/**
 * Writes into a user-chosen SAF tree. Bytes always land under a `.part` name and
 * are only renamed to the final name once complete; a crash leaves a resumable
 * partial file rather than a corrupt media file.
 */
class SafMediaSink(
    private val context: Context,
    treeUri: Uri,
) : MediaSink {
    private val resolver = context.contentResolver
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("invalid storage tree: $treeUri")

    init {
        require(root.canWrite()) { "chosen storage directory is not writable" }
    }

    private fun directory(asset: MediaAsset): DocumentFile? {
        var dir = root
        for (segment in asset.relativeDir.split('/').map(MediaFiles::sanitize)) {
            if (segment.isBlank()) continue
            dir = dir.findFile(segment)?.takeIf { it.isDirectory() }
                ?: dir.createDirectory(segment)
                ?: return null
        }
        return dir
    }

    override fun exists(asset: MediaAsset): Boolean {
        val name = MediaFiles.nameFor(asset)
        return directory(asset)?.findFile(name)?.let { it.isFile && it.length() > 0 } ?: false
    }

    override fun accept(asset: MediaAsset, source: File): SavedMedia {
        val name = MediaFiles.nameFor(asset)
        val dir = directory(asset) ?: error("cannot create media directory ${asset.relativeDir}")
        val previous = dir.findFile(name)?.takeIf { it.isFile }
        val existed = previous != null && previous.length() > 0
        val partName = "$name.part"
        dir.findFile(partName)?.delete()
        val part = dir.createFile(mimeFor(name), partName)
            ?: error("cannot create $partName in the chosen directory")
        try {
            resolver.openOutputStream(part.uri, "w")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("cannot open output stream for $partName")

            previous?.delete()
            if (!part.renameTo(name)) {
                // Some providers refuse rename; copy into the final name instead.
                val final = dir.createFile(mimeFor(name), name)
                    ?: error("cannot create $name in the chosen directory")
                resolver.openOutputStream(final.uri, "w")?.use { output ->
                    resolver.openInputStream(part.uri)?.use { it.copyTo(output) }
                        ?: error("cannot read back $partName")
                } ?: error("cannot open output stream for $name")
                part.delete()
                applyCreatedTime(final.uri, asset.createdAt)
                return SavedMedia(asset, name, final.length(), existed)
            }
            applyCreatedTime(part.uri, asset.createdAt)
            return SavedMedia(asset, name, part.length(), existed)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun applyCreatedTime(uri: Uri, createdAt: Long?) {
        if (createdAt == null || createdAt <= 0) return
        try {
            val values = ContentValues().apply {
                put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, createdAt * 1000L)
            }
            resolver.update(uri, values, null, null)
        } catch (_: Exception) {
            // Not all providers allow updating this column; the file is still saved.
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}

/** A SAF tree when the user picked one, otherwise app-private storage. */
fun createMediaSink(context: Context, treeUri: String?): MediaSink {
    if (!treeUri.isNullOrBlank()) {
        try {
            return SafMediaSink(context, Uri.parse(treeUri))
        } catch (_: Exception) {
            // Permission may have been revoked; fall back instead of failing the run.
        }
    }
    return DirectoryMediaSink(File(context.applicationContext.filesDir, "worm-media"))
}
