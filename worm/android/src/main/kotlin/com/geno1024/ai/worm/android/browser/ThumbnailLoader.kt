package com.geno1024.ai.worm.android.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.geno1024.ai.worm.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream

/** A decode attempt with the actual reason when there is no bitmap to show. */
data class DecodedImage(val bitmap: ImageBitmap?, val error: String? = null)

/** Decodes downscaled previews off the main thread; a broken file reports why it failed. */
object ThumbnailLoader {
    suspend fun load(context: Context, media: LocalMedia, maxDimension: Int): ImageBitmap? =
        loadDetailed(context, media, maxDimension).bitmap

    suspend fun loadDetailed(context: Context, media: LocalMedia, maxDimension: Int): DecodedImage =
        withContext(Dispatchers.IO) {
            runCatching {
                when (media.kind) {
                    MediaKind.IMAGE -> decodeImage(context, media, maxDimension)
                    MediaKind.VIDEO -> decodeVideo(context, media, maxDimension)
                }
            }.getOrElse { DecodedImage(null, "${media.name}：${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""}") }
        }

    private fun decodeImage(context: Context, media: LocalMedia, maxDimension: Int): DecodedImage {
        val textProblem = textResponse(context, media)
        if (textProblem != null) return DecodedImage(null, "${media.name}：$textProblem")
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, media, maxDimension)
        } else {
            decodeWithBitmapFactory(context, media, maxDimension)
        }
        return bitmap?.let { DecodedImage(it) }
            ?: DecodedImage(null, "${media.name}：解码器返回空结果，文件可能已损坏")
    }

    /** ImageDecoder copes with non-seekable SAF streams and with very large images. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, media: LocalMedia, maxDimension: Int): ImageBitmap? {
        val source = media.uri?.let { android.graphics.ImageDecoder.createSource(context.contentResolver, it) }
            ?: media.file?.let { android.graphics.ImageDecoder.createSource(it) }
            ?: return null
        val decoded = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Compose cannot upload hardware bitmaps; a software allocation is required.
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(maxOf(info.size.width, info.size.height), maxDimension))
        }
        return fit(decoded, maxDimension).asImageBitmap()
    }

    /** API 26/27 fallback. Never reuse a stream for both passes, and always buffer it. */
    private fun decodeWithBitmapFactory(context: Context, media: LocalMedia, maxDimension: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream(context, media)?.use { BitmapFactory.decodeStream(buffer(it), null, bounds) } ?: return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(longest, maxDimension) }
        val decoded = stream(context, media)?.use { BitmapFactory.decodeStream(buffer(it), null, options) }
            ?: return null
        return fit(decoded, maxDimension).asImageBitmap()
    }

    private fun decodeVideo(context: Context, media: LocalMedia, maxDimension: Int): DecodedImage {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = media.uri
            if (uri != null) retriever.setDataSource(context, uri)
            else retriever.setDataSource(media.file?.absolutePath ?: return DecodedImage(null, "${media.name}：没有可读取的视频文件"))
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return DecodedImage(null, "${media.name}：无法读取视频首帧")
            return DecodedImage(fit(frame, maxDimension).asImageBitmap())
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** BitmapFactory only accepts powers of two; ImageDecoder rounds down to one too. */
    private fun sampleSize(longest: Int, maxDimension: Int): Int {
        var sample = 1
        while (longest / sample / 2 >= maxDimension) sample *= 2
        return sample
    }

    private fun fit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension || longest <= 0) return bitmap
        val ratio = maxDimension.toFloat() / longest
        return bitmap.scale(
            maxOf(1, (bitmap.width * ratio).toInt()),
            maxOf(1, (bitmap.height * ratio).toInt()),
        )
    }

    private fun buffer(stream: InputStream): InputStream =
        if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 64 * 1024)

    /**
     * Old crawls could save an SVG placeholder or an error page under a `.jpg` name.
     * Naming the problem beats a silent "cannot decode" with no way to recover.
     */
    private fun textResponse(context: Context, media: LocalMedia): String? {
        val head = ByteArray(32)
        val read = stream(context, media)?.use { input ->
            var total = 0
            while (total < head.size) {
                val count = input.read(head, total, head.size - total)
                if (count < 0) break
                total += count
            }
            total
        } ?: return null
        return when (head.copyOf(read).toString(Charsets.ISO_8859_1).trimStart().firstOrNull()) {
            '<' -> "文件内容是 SVG/HTML 文本，不是图片；可能是旧版抓取保存的占位图或错误页，请删除后重新抓取"
            '{' -> "文件内容是 JSON 文本，不是图片；请重新抓取"
            else -> null
        }
    }

    private fun stream(context: Context, media: LocalMedia): InputStream? =
        media.uri?.let { context.contentResolver.openInputStream(it) } ?: media.file?.inputStream()
}
