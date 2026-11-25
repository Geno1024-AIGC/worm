package com.geno1024.ai.worm.android.browser

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.MediaTimeManifest
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

/** Newest first; files without a usable mtime go last, then keep a stable name order. */
fun sortMediaByTime(media: List<LocalMedia>): List<LocalMedia> =
    media.sortedWith(
        compareByDescending<LocalMedia> { it.modifiedAt ?: Long.MIN_VALUE }
            .thenBy { it.name },
    )

/** Folders with newer media first; folders without any timed media go last. */
fun sortDirectoriesByTime(directories: List<DirectoryEntry>): List<DirectoryEntry> =
    directories.sortedWith(
        compareByDescending<DirectoryEntry> { it.newestAt ?: Long.MIN_VALUE }
            .thenBy { it.name },
    )

data class DirectoryEntry(
    val name: String,
    val newestAt: Long? = null,
)

data class DirectoryListing(
    val path: String,
    val directories: List<DirectoryEntry> = emptyList(),
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
    private val mediaTimes: MediaTimeManifest = MediaTimeManifest(File(context.filesDir, "worm/media-times.json")),
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
            directories = sortDirectoriesByTime(
                files.filter { it.isDirectory() }.mapNotNull { dir ->
                    dir.name?.let { DirectoryEntry(it, newestAtSaf(dir, childFolder(folder, it))) }
                },
            ),
            media = files.filter { it.isFile() }.mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                val kind = MediaFiles.kindOf(name) ?: return@mapNotNull null
                val path = childFolder(folder, name)
                LocalMedia(
                    path = path,
                    name = name,
                    kind = kind,
                    uri = document.uri,
                    modifiedAt = mediaTimes.get(path) ?: document.lastModified().takeIf { it > 0 },
                )
            }.let(::sortMediaByTime),
        )
    }

    private fun listPrivate(folder: String): DirectoryListing {
        val root = File(context.filesDir, "worm-media")
        val current = folder.split('/').filter { it.isNotBlank() }
            .fold(root) { dir, segment -> File(dir, segment) }
        val files = current.listFiles() ?: return DirectoryListing(folder, error = "目录不存在：$folder")
        return DirectoryListing(
            path = folder,
            directories = sortDirectoriesByTime(
                files.filter { it.isDirectory() }.map { dir ->
                    DirectoryEntry(dir.name, newestAtPrivate(root, dir, childFolder(folder, dir.name)))
                },
            ),
            media = files.filter { it.isFile() }.mapNotNull { file ->
                val kind = MediaFiles.kindOf(file.name) ?: return@mapNotNull null
                val path = childFolder(folder, file.name)
                LocalMedia(
                    path = path,
                    name = file.name,
                    kind = kind,
                    file = file,
                    modifiedAt = mediaTimes.get(path) ?: file.lastModified().takeIf { it > 0 },
                )
            }.let(::sortMediaByTime),
        )
    }

    /** Newest mtime anywhere under a SAF folder; unreadable providers simply have no time. */
    private fun newestAtSaf(dir: DocumentFile, prefix: String): Long? = runCatching {
        val children = dir.listFiles()
        val direct = children.filter { it.isFile() }.mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            mediaTimes.get(childFolder(prefix, name)) ?: document.lastModified().takeIf { it > 0 }
        }
        val nested = children.filter { it.isDirectory() }.mapNotNull { child ->
            child.name?.let { name -> newestAtSaf(child, childFolder(prefix, name)) }
        }
        (direct + nested).maxOrNull()
    }.getOrNull()

    private fun newestAtPrivate(root: File, dir: File, prefix: String): Long? = runCatching {
        dir.walkTopDown().filter { it.isFile() }
            .mapNotNull { file ->
                mediaTimes.get(childFolder(prefix, file.toRelativeString(root)))
                    ?: file.lastModified().takeIf { it > 0 }
            }
            .maxOrNull()
    }.getOrNull()
}
