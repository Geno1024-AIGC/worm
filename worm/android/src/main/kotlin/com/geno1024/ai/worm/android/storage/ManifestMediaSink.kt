package com.geno1024.ai.worm.android.storage

import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.MediaSink
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaTimeManifest
import com.geno1024.ai.worm.core.SavedMedia
import java.io.File

/** Records publish times alongside the bytes so the browser can show them even if mtime is lost. */
class ManifestMediaSink(
    private val delegate: MediaSink,
    private val manifest: MediaTimeManifest,
) : MediaSink {
    override fun exists(asset: MediaAsset): Boolean = delegate.exists(asset)

    override fun accept(asset: MediaAsset, source: File): SavedMedia {
        val saved = delegate.accept(asset, source)
        val folder = asset.relativeDir.trim().trim('/')
        val path = if (folder.isBlank()) saved.name else "$folder/${saved.name}"
        manifest.recordSeconds(path, asset.createdAt)
        return saved
    }
}
