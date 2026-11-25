package com.geno1024.ai.worm.weibo

import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.optArrayOf
import com.geno1024.ai.worm.core.optObjectOf
import com.geno1024.ai.worm.core.optStringOf
import com.geno1024.ai.worm.core.objects
import org.json.JSONObject

/** Pure conversion of one Weibo post into media assets. */
object WeiboMedia {
    fun extract(mblog: JSONObject, createdAt: Long?, referer: String): List<MediaAsset> {
        val result = LinkedHashMap<String, MediaAsset>()
        val id = mblog.optStringOf("id", "mid", "idstr")

        mblog.optArrayOf("pics")?.objects().orEmpty().forEach { pic ->
            // `large` is the full-size variant; `url` is often just orj360.
            val url = pic.optObjectOf("large")?.optStringOf("url")
                ?: pic.optStringOf("url", "raw")
                ?: return@forEach
            if (!url.startsWith("http")) return@forEach
            val asset = MediaAsset(
                url = url,
                kind = MediaKind.IMAGE,
                id = id,
                createdAt = createdAt,
                referer = referer,
                extension = MediaFiles.extensionOf(url, MediaKind.IMAGE),
            )
            result.putIfAbsent(imageKey(url), asset)
        }

        val page = mblog.optObjectOf("page_info")
        if (page != null) {
            val info = page.optObjectOf("media_info")
            val isVideo = page.optStringOf("type")?.contains("video", ignoreCase = true) == true ||
                info != null || page.optArrayOf("playback_list") != null
            if (isVideo) {
                val cover = page.optObjectOf("page_pic")?.optStringOf("url")
                if (!cover.isNullOrBlank() && cover.startsWith("http")) {
                    result.putIfAbsent(imageKey(cover), MediaAsset(
                        url = cover,
                        kind = MediaKind.IMAGE,
                        id = id,
                        createdAt = createdAt,
                        referer = referer,
                        extension = MediaFiles.extensionOf(cover, MediaKind.IMAGE),
                        isCover = true,
                    ))
                }
                videoUrlOf(page)?.let { url ->
                    result.putIfAbsent("video:$url", MediaAsset(
                        url = url,
                        kind = MediaKind.VIDEO,
                        id = id,
                        createdAt = createdAt,
                        referer = referer,
                        extension = MediaFiles.extensionOf(url, MediaKind.VIDEO),
                    ))
                }
            }
        }
        return result.values.toList()
    }

    private fun videoUrlOf(page: JSONObject): String? {
        val info = page.optObjectOf("media_info")
        val direct = listOf(
            "stream_url_hd", "mp4_hd_url", "stream_url", "mp4_sd_url", "mp4_240k", "play_url",
        ).mapNotNull { info?.optStringOf(it) }.firstOrNull { it.startsWith("http") }
        if (direct != null) return direct

        val urls = page.optArrayOf("playback_list")?.objects().orEmpty().mapNotNull { entry ->
            entry.optObjectOf("play_info")?.optStringOf("url")?.takeIf { it.startsWith("http") }
        }
        // Streams are often HLS; keep an mp4 if one exists, otherwise use the stream.
        return urls.firstOrNull { !it.contains(".m3u8") } ?: urls.firstOrNull()
    }

    /** Sina CDN variants share a filename (`orj360/<id>.jpg` vs `large/<id>.jpg`). */
    private fun imageKey(url: String): String = url.substringAfterLast('/').substringBefore('?')
}
