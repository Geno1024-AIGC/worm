package com.geno1024.ai.worm.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "avi", "mkv", "flv", "webm")

object MediaFiles {
    fun extensionOf(url: String, kind: MediaKind): String {
        val raw = runCatching { URI.create(url).path.substringAfterLast('.', "") }.getOrDefault("")
        val candidate = raw.lowercase().substringBefore('?').substringBefore('#')
        val allowed = if (kind == MediaKind.VIDEO) VIDEO_EXTENSIONS else IMAGE_EXTENSIONS
        return if (candidate in allowed) candidate else if (kind == MediaKind.VIDEO) "mp4" else "jpg"
    }

    /** Keeps only safe path components; a hostile URL can never escape the sink root. */
    fun sanitize(value: String): String =
        value.trim().replace('\\', '_').replace('/', '_').replace("..", "_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_').take(80).ifEmpty { "media" }

    fun nameFor(asset: MediaAsset): String {
        val base = asset.suggestedName?.let(::sanitize)
            ?: asset.id?.let(::sanitize)
            ?: "media-${asset.url.hashCode().toUInt().toString(16)}"
        return "$base.${asset.extension ?: extensionOf(asset.url, asset.kind)}"
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
}
