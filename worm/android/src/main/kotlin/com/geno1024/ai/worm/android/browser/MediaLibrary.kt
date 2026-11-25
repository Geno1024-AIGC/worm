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

/** A saved file as shown by the browser. [path] is the stable id and breadcrumb key. */
data class LocalMedia(
    val path: String,
    val name: String,
    val kind: MediaKind,
    val file: File? = null,
    val uri: Uri? = null,
) {
    val id: String get() = path
}

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
                LocalMedia(childFolder(folder, name), name, kind, uri = document.uri)
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
                LocalMedia(childFolder(folder, file.name), file.name, kind, file = file)
            }.sortedBy { it.name },
        )
    }
}
