package com.geno1024.ai.worm.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "avi", "mkv", "flv", "webm")

object MediaFiles {
    private fun allowed(kind: MediaKind): Set<String> =
        if (kind == MediaKind.VIDEO) VIDEO_EXTENSIONS else IMAGE_EXTENSIONS

    /** Classify a saved filename; unknown or partial files are not media. */
    fun kindOf(name: String): MediaKind? = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXTENSIONS -> MediaKind.IMAGE
        in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        else -> null
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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

    /** An image never gets a video extension (or vice versa), whatever the URL claims. */
    fun extensionOf(url: String, kind: MediaKind): String {
        val raw = runCatching { URI.create(url).path.substringAfterLast('.', "") }.getOrDefault("")
        val candidate = raw.lowercase().substringBefore('?').substringBefore('#')
        return if (candidate in allowed(kind)) candidate else if (kind == MediaKind.VIDEO) "mp4" else "jpg"
    }

    /** Keeps only safe path components; a hostile URL can never escape the sink root. */
    fun sanitize(value: String): String =
        value.trim().replace('\\', '_').replace('/', '_').replace("..", "_")
            // Unicode letters are safe in a filename and Chinese album names must survive.
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .trim('_').take(80).ifEmpty { "media" }

    fun nameFor(asset: MediaAsset): String {
        val base = asset.suggestedName?.let(::sanitize)
            ?: asset.id?.let(::sanitize)
            ?: "media-${asset.url.hashCode().toUInt().toString(16)}"
        // A stale/mislabelled asset extension must not turn a cover JPG into a video.
        val extension = asset.extension?.lowercase()?.takeIf { it in allowed(asset.kind) }
            ?: extensionOf(asset.url, asset.kind)
        return "$base.$extension"
    }

    fun copyFinished(part: File, target: File) {
        target.parentFile?.mkdirs()
        if (!part.renameTo(target)) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Where downloaded bytes end up. The JVM CLI uses [DirectoryMediaSink]; Android
 * implements the same interface over Storage Access Framework so both share the
 * crawlers unchanged.
 */
interface MediaSink {
    fun exists(asset: MediaAsset): Boolean
    fun accept(asset: MediaAsset, source: File): SavedMedia
}

class DirectoryMediaSink(private val root: File) : MediaSink {
    private fun target(asset: MediaAsset): File =
        File(root, "${sanitizeDir(asset.relativeDir)}/${MediaFiles.nameFor(asset)}")

    private fun sanitizeDir(dir: String): String = dir.split('/')
        .map { MediaFiles.sanitize(it) }
        .joinToString("/")
        .ifEmpty { "media" }

    override fun exists(asset: MediaAsset): Boolean = target(asset).let { it.isFile && it.length() > 0 }

    override fun accept(asset: MediaAsset, source: File): SavedMedia {
        val target = target(asset)
        val existed = target.isFile && target.length() > 0
        MediaFiles.copyFinished(source, target)
        return SavedMedia(asset, target.name, target.length(), existed)
    }
}

/** Fetches assets into a cache directory, then hands the finished file to a [MediaSink]. */
class MediaDownloader(
    private val http: HttpPortal,
    private val cacheDir: File,
    private val delayMs: Long = 300L,
) {
    fun download(
        asset: MediaAsset,
        session: CrawlSession,
        sink: MediaSink,
        onBytes: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ): SavedMedia? {
        if (sink.exists(asset)) return null
        cacheDir.mkdirs()
        val temp = File(cacheDir, "download-${asset.url.hashCode().toUInt().toString(16)}.part")
        try {
            http.download(asset.url, session, asset.referer, temp, onBytes)
            if (delayMs > 0) Thread.sleep(delayMs)
            return sink.accept(asset, temp)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Downloads an already discovered list. The total is known up front, so [onProgress]
     * can drive a determinate bar; one failed asset never aborts the rest of the batch.
     */
    fun downloadAll(
        assets: List<MediaAsset>,
        session: CrawlSession,
        sink: MediaSink,
        onProgress: (current: Int, total: Int, asset: MediaAsset) -> Unit,
        onFailure: (asset: MediaAsset, error: Exception) -> Unit,
        onDone: (asset: MediaAsset) -> Unit = {},
    ): List<SavedMedia> {
        val saved = mutableListOf<SavedMedia>()
        assets.forEachIndexed { index, asset ->
            try {
                download(asset, session, sink)?.let(saved::add)
            } catch (e: Exception) {
                onFailure(asset, e)
            }
            onProgress(index + 1, assets.size, asset)
            onDone(asset)
        }
        return saved
    }
}
