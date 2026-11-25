package com.geno1024.ai.worm.android.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.geno1024.ai.worm.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Decodes downscaled previews off the main thread; a broken file simply has no preview. */
object ThumbnailLoader {
    suspend fun load(context: Context, media: LocalMedia, maxDimension: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                when (media.kind) {
                    MediaKind.IMAGE -> loadImage(context, media, maxDimension)
                    MediaKind.VIDEO -> loadVideo(context, media, maxDimension)
                }
            }.getOrNull()
        }

    private fun loadImage(context: Context, media: LocalMedia, maxDimension: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream(context, media)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val sample = maxOf(1, (longest + maxDimension - 1) / maxDimension)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return stream(context, media)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?.asImageBitmap()
    }

    private fun loadVideo(context: Context, media: LocalMedia, maxDimension: Int): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = media.uri
            if (uri != null) retriever.setDataSource(context, uri)
            else retriever.setDataSource(media.file?.absolutePath ?: return null)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = if (maxOf(frame.width, frame.height) <= maxDimension) frame else {
                val ratio = maxDimension.toFloat() / maxOf(frame.width, frame.height)
                frame.scale(
                    maxOf(1, (frame.width * ratio).toInt()),
                    maxOf(1, (frame.height * ratio).toInt()),
                )
            }
            return scaled.asImageBitmap()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun stream(context: Context, media: LocalMedia): InputStream? =
        media.uri?.let { context.contentResolver.openInputStream(it) } ?: media.file?.inputStream()
}
