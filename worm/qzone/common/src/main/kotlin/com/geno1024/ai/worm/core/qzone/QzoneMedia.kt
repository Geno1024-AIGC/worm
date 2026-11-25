package com.geno1024.ai.worm.core.qzone

import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.optArrayOf
import com.geno1024.ai.worm.core.optLongOf
import com.geno1024.ai.worm.core.optObjectOf
import com.geno1024.ai.worm.core.optStringOf
import com.geno1024.ai.worm.core.objects
import org.json.JSONObject

private const val REFERER = "https://user.qzone.qq.com/"
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "avi", "mkv", "flv", "webm")
private val IMAGE_KEYS = listOf("burl", "raw", "url3", "url2", "url1", "smallurl", "curl", "url", "photo_url")
private val VIDEO_KEYS = listOf(
    "videourl", "raw_url", "download_url", "video_url", "origin_video_url", "raw_video_url",
    "play_url", "url", "raw", "videourl",
)

/** Reads image/video URLs from one Qzone message (including reposts). Pure and fixture-testable. */
fun extractMessageMedia(message: JSONObject): List<MediaAsset> {
    val result = linkedMapOf<String, MediaAsset>()
    val tid = message.optStringOf("tid", "id")
    val messageTime = message.optLongOf("created_time", "time")
    var counter = 0

    fun add(url: String, kind: MediaKind, cover: Boolean, id: String?, time: Long?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        // One post can carry several images: <tid>-<n> keeps the old CLI's naming
        // convention and prevents later assets from overwriting earlier ones.
        val suggested = id?.let { "$it-${++counter}" }
        val asset = MediaAsset(
            url = url,
            kind = kind,
            id = id,
            createdAt = time,
            referer = REFERER,
            extension = MediaFiles.extensionOf(url, kind),
            isCover = cover,
            suggestedName = suggested,
        )
        result.putIfAbsent("${kind.name}:$url", asset)
    }

    fun addImage(object_: JSONObject, id: String?, time: Long?, cover: Boolean) {
        pickUrl(object_, IMAGE_KEYS)?.let { add(it, MediaKind.IMAGE, cover, id, time) }
    }

    fun addVideo(object_: JSONObject, id: String?, time: Long?, requireExtension: Boolean) {
        val url = pickVideoUrl(object_, requireExtension) ?: return
        add(url, MediaKind.VIDEO, false, id, time)
    }

    message.optArrayOf("pic")?.objects()?.forEach { pic ->
        val time = pic.optLongOf("uploadtime", "uploadTime", "shotTime") ?: messageTime
        val videoLike = pic.optBoolean("is_video") || pic.optInt("is_video") == 1
        if (videoLike) {
            val video = pickVideoUrl(pic, requireExtension = true)
            if (video != null) add(video, MediaKind.VIDEO, false, tid, time)
            // No playable URL: the API only gave us a first frame. Keep it as an
            // image instead of faking an .mp4 download.
            else addImage(pic, tid, time, cover = true)
        } else {
            addImage(pic, tid, time, cover = false)
        }
    }

    for (key in listOf("video", "videos", "cell_video", "small_video")) {
        message.optArrayOf(key)?.objects()?.forEach { video ->
            val time = video.optLongOf("created_time", "uploadtime") ?: messageTime
            val url = pickVideoUrl(video, requireExtension = false)
            if (url != null) add(url, MediaKind.VIDEO, false, tid, time)
            else addImage(video, tid, time, cover = true)
        }
    }

    when (val forwarded = message.opt("rt_con")) {
        is JSONObject -> extractMessageMedia(forwarded).forEach { result.putIfAbsent("${it.kind.name}:${it.url}", it) }
        is org.json.JSONArray -> (0 until forwarded.length()).mapNotNull { forwarded.opt(it) as? JSONObject }
            .flatMap(::extractMessageMedia)
            .forEach { result.putIfAbsent("${it.kind.name}:${it.url}", it) }
    }
    return result.values.toList()
}

/** Album photos often expose only a cover JPG in `url`; never store it as an mp4. */
fun extractAlbumPhotoMedia(photo: JSONObject, relativeDir: String): List<MediaAsset> {
    val id = photo.optStringOf("lloc", "sloc", "id")
    val time = photo.optLongOf("uploadtime", "uploadTime", "rawshoottime", "shotTime")
    val videoUrl = pickVideoUrl(photo, requireExtension = true)
    if (videoUrl != null) {
        return listOf(MediaAsset(
            url = videoUrl,
            kind = MediaKind.VIDEO,
            id = id,
            createdAt = time,
            referer = REFERER,
            extension = MediaFiles.extensionOf(videoUrl, MediaKind.VIDEO),
            relativeDir = relativeDir,
        ))
    }
    val imageUrl = pickUrl(photo, IMAGE_KEYS) ?: return emptyList()
    return listOf(MediaAsset(
        url = imageUrl,
        kind = MediaKind.IMAGE,
        id = id,
        createdAt = time,
        referer = REFERER,
        extension = MediaFiles.extensionOf(imageUrl, MediaKind.IMAGE),
        isCover = isVideoLike(photo),
        relativeDir = relativeDir,
    ))
}

private fun isVideoLike(object_: JSONObject): Boolean =
    object_.optBoolean("is_video") || object_.optInt("is_video") == 1 ||
        object_.optStringOf("type", "media_type")?.equals("video", ignoreCase = true) == true

private fun pickUrl(object_: JSONObject, keys: List<String>): String? {
    for (key in keys) {
        val raw = object_.opt(key)?.toString()?.trim() ?: continue
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    }
    return null
}

private fun pickVideoUrl(object_: JSONObject, requireExtension: Boolean): String? {
    for (key in VIDEO_KEYS) {
        val raw = object_.opt(key)?.toString()?.trim() ?: continue
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) continue
        if (!requireExtension || looksLikeVideo(raw)) return raw
    }
    for (key in listOf("video_info", "videodata")) {
        when (val nested = object_.opt(key)) {
            is JSONObject -> pickVideoUrl(nested, requireExtension)?.let { return it }
            is org.json.JSONArray -> (0 until nested.length()).mapNotNull { nested.opt(it) as? JSONObject }
                .firstNotNullOfOrNull { pickVideoUrl(it, requireExtension) }?.let { return it }
        }
    }
    return null
}

private fun looksLikeVideo(url: String): Boolean {
    val extension = runCatching { java.net.URI.create(url).path.substringAfterLast('.', "") }
        .getOrDefault("").lowercase()
    return extension in VIDEO_EXTENSIONS
}
