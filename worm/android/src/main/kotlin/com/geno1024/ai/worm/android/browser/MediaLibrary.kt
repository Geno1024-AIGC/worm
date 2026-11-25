package com.geno1024.ai.worm.android.browser

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A saved file as shown by the browser. [path] is the stable id and breadcrumb key. */
data class LocalMedia(
    val path: String,
    val name: String,
    val kind: MediaKind,
    val file: File? = null,
    val uri: Uri? = null,
    /** File mtime in milliseconds, set to the post's publish time when the crawler knew it. */
    val modifiedAt: Long? = null,
) {
    val id: String get() = path
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val TIME_DETAIL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun formatMediaTime(epochMillis: Long?): String = formatMediaTime(epochMillis, TIME_FORMAT)

fun formatMediaTimeDetail(epochMillis: Long?): String = formatMediaTime(epochMillis, TIME_DETAIL_FORMAT)

private fun formatMediaTime(epochMillis: Long?, formatter: DateTimeFormatter): String =
    epochMillis?.takeIf { it > 0 }?.let {
        formatter.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
    } ?: "时间未知"

data class DirectoryListing(
    val path: String,
    val directories: List<String> = emptyList(),
    val media: List<LocalMedia> = emptyList(),
    val error: String? = null,
)

/**
 * Reads back exactly what the crawlers wrote: either the user's SAF tree or the app's
 * private `filesDir/worm-media`. No thumbnails or network here, only file metadata.
 */
class MediaLibrary(
    private val context: Context,
    private val treeUri: String?,
) {
    suspend fun list(folder: String): DirectoryListing = withContext(Dispatchers.IO) {
        val safeFolder = folder.split('/').filter { it.isNotBlank() }.joinToString("/")
        val tree = treeUri?.takeIf { it.isNotBlank() }?.let { runCatching { it.toUri() }.getOrNull() }
            ?.let { DocumentFile.fromTreeUri(context, it) }
        if (tree != null && tree.canRead()) listSaf(tree, safeFolder) else listPrivate(safeFolder)
    }

    private fun listSaf(root: DocumentFile, folder: String): DirectoryListing {
        var current = root
        for (segment in folder.split('/').filter { it.isNotBlank() }) {
            current = current.listFiles().firstOrNull { it.isDirectory() && it.name == segment }
                ?: return DirectoryListing(folder, error = "目录已不存在：$folder")
        }
        val files = current.listFiles()
        return DirectoryListing(
            path = folder,
            directories = files.filter { it.isDirectory() }.mapNotNull { it.name }.sorted(),
            media = files.filter { it.isFile() }.mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                val kind = MediaFiles.kindOf(name) ?: return@mapNotNull null
                LocalMedia(
                    path = childFolder(folder, name),
                    name = name,
                    kind = kind,
                    uri = document.uri,
                    modifiedAt = document.lastModified().takeIf { it > 0 },
                )
            }.sortedBy { it.name },
        )
    }

    private fun listPrivate(folder: String): DirectoryListing {
        val root = File(context.filesDir, "worm-media")
        val current = folder.split('/').filter { it.isNotBlank() }
            .fold(root) { dir, segment -> File(dir, segment) }
        val files = current.listFiles() ?: return DirectoryListing(folder, error = "目录不存在：$folder")
        return DirectoryListing(
            path = folder,
            directories = files.filter { it.isDirectory() }.map { it.name }.sorted(),
            media = files.filter { it.isFile() }.mapNotNull { file ->
                val kind = MediaFiles.kindOf(file.name) ?: return@mapNotNull null
                LocalMedia(
                    path = childFolder(folder, file.name),
                    name = file.name,
                    kind = kind,
                    file = file,
                    modifiedAt = file.lastModified().takeIf { it > 0 },
                )
            }.sortedBy { it.name },
        )
    }
}
