package com.geno1024.ai.worm.zhihu

import com.geno1024.ai.worm.core.HtmlMedia
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.Jsonp
import com.geno1024.ai.worm.core.optArrayOf
import com.geno1024.ai.worm.core.optLongOf
import com.geno1024.ai.worm.core.optObjectOf
import com.geno1024.ai.worm.core.optStringOf
import com.geno1024.ai.worm.core.objects
import com.geno1024.ai.worm.core.responseProblem
import org.json.JSONObject
import java.net.URI

enum class ZhihuKind(val endpoint: String) {
    ANSWER("answers"),
    ARTICLE("articles"),
    ACTIVITY("activities"),
}

data class ZhihuContent(
    val id: String?,
    val kind: ZhihuKind,
    val title: String?,
    val url: String,
    val html: String,
    val createdAt: Long?,
    val media: List<MediaAsset>,
)

data class ZhihuListPage(
    val contents: List<ZhihuContent>,
    val nextOffset: Int?,
    val error: String? = null,
)

/** Accepts a bare token or any `https://www.zhihu.com/people/<token>/...` URL. */
fun normalizeZhihuSubject(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val path = runCatching { URI.create(trimmed).path }.getOrDefault(trimmed)
    return path.split('/').indexOf("people")
        .takeIf { it >= 0 }
        ?.let { path.split('/').getOrNull(it + 1) }
        ?.takeIf { it.isNotBlank() }
        ?: trimmed.substringAfterLast('/')
}

fun buildZhihuListUrl(token: String, kind: ZhihuKind, offset: Int, limit: Int): String =
    "https://www.zhihu.com/api/v4/members/$token/${kind.endpoint}" +
        "?limit=$limit&offset=$offset&include=data[*].content,content_text,created,updated_time" +
        "&sortby=created&desktop=true"

private val VIDEO_KEYS = listOf("play_url", "playUrl", "video_url", "raw_url", "download_url")
private val GENERIC_VIDEO_KEYS = listOf("url", "src")
private val COVER_KEYS = listOf("cover", "cover_url", "coverUrl", "image_url", "thumbnail", "poster")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "flv")

/** Pure parse of a zhihu-api list response. Errors are surfaced, never silently treated as "no data". */
fun parseZhihuListPage(
    body: String,
    kind: ZhihuKind,
    referer: String,
    offset: Int = 0,
    upgradeImages: Boolean = false,
): ZhihuListPage {
    val root = Jsonp.objectOrNull(body)
        ?: return ZhihuListPage(emptyList(), null, responseProblem(body))
    val errorObject = root.optObjectOf("error")
    val code = errorObject?.optInt("code", 0)?.takeIf { it != 0 } ?: root.optInt("code", 0)
    val message = errorObject?.optStringOf("message", "msg") ?: root.optStringOf("message")
    if (root.opt("data") == null && (errorObject != null || code != 0)) {
        val problem = when {
            code == 40353 || code == 403 || message?.contains("unhuman", ignoreCase = true) == true ->
                "需要登录或触发风控"
            else -> "接口错误"
        }
        return ZhihuListPage(emptyList(), null, "$problem（code=$code）${message?.let { "：$it" } ?: ""}")
    }

    val data = root.optArrayOf("data") ?: return ZhihuListPage(emptyList(), null, null)
    val contents = data.objects().mapNotNull { extractZhihuContent(it, kind, referer, upgradeImages) }

    val paging = root.optObjectOf("paging")
    val next = when {
        root.optBoolean("is_end") -> null
        paging?.optBoolean("is_end") == true -> null
        paging != null -> parseOffset(paging.optStringOf("next")) ?: (offset + data.length()).takeIf { data.length() > 0 }
        else -> null
    }
    return ZhihuListPage(contents, next)
}

private fun parseOffset(next: String?): Int? {
    if (next.isNullOrBlank()) return null
    val query = next.substringAfter('?', "")
    return query.split('&').firstOrNull { it.startsWith("offset=") }
        ?.substringAfter('=')?.toIntOrNull()
}

/** Pure conversion of one API item (or an activity wrapper) into a common content record. */
fun extractZhihuContent(
    item: JSONObject,
    fallbackKind: ZhihuKind,
    referer: String,
    upgradeImages: Boolean = false,
): ZhihuContent? {
    val target = item.optObjectOf("target", "object") ?: item
    val id = target.optStringOf("id", "answer_id", "article_id", "content_id")
        ?: item.optStringOf("id", "answer_id", "article_id")
    val contentHtml = target.optStringOf("content", "content_html", "contentHtml")
        ?: item.optStringOf("content", "content_html")
    if (id.isNullOrBlank() && contentHtml.isNullOrBlank()) return null

    val kind = when {
        target.optStringOf("type")?.contains("video") == true -> ZhihuKind.ACTIVITY
        fallbackKind == ZhihuKind.ACTIVITY && target.optStringOf("type") == "article" -> ZhihuKind.ARTICLE
        else -> fallbackKind
    }
    val sourceUrl = target.optStringOf("url", "link", "share_url")
        ?: item.optStringOf("url", "link")
        ?: "https://www.zhihu.com/p/$id"
    val title = target.optStringOf("title")
        ?: target.optObjectOf("question")?.optStringOf("title")
        ?: item.optStringOf("title")
    val created = target.optLongOf("created", "created_time", "createdAt", "updated_time")
        ?: item.optLongOf("created", "created_time")

    val assets = linkedMapOf<String, MediaAsset>()
    var counter = 0
    fun add(url: String?, mediaKind: MediaKind, cover: Boolean = false) {
        val absolute = url?.trim()?.takeIf { it.isNotBlank() }?.let {
            when {
                it.startsWith("http://") || it.startsWith("https://") -> it
                it.startsWith("//") -> "https:$it"
                else -> null
            }
        } ?: return
        // Group image variants by CDN id and keep the largest link the page gave us.
        // Videos keep their exact URL: two different videos may share a hash prefix.
        val key = if (mediaKind == MediaKind.IMAGE) {
            "IMAGE:${ZhihuMedia.canonical(absolute)}"
        } else {
            "VIDEO:$absolute"
        }
        val previous = assets[key]
        if (previous != null && ZhihuMedia.rank(absolute) <= ZhihuMedia.rank(previous.url)) return
        assets[key] = MediaAsset(
            url = absolute,
            kind = mediaKind,
            id = id,
            suggestedName = id?.let { "$it-${++counter}" },
            title = title,
            // Needed by incremental crawls: media inherits its post's publish time.
            createdAt = created,
            referer = referer,
            extension = MediaFiles.extensionOf(absolute, mediaKind),
            isCover = cover || previous?.isCover == true,
            fallbackUrls = if (upgradeImages && mediaKind == MediaKind.IMAGE &&
                ZhihuMedia.rank(absolute) <= 10
            ) ZhihuMedia.upgradeCandidates(absolute) else emptyList(),
        )
    }

    if (!contentHtml.isNullOrBlank()) {
        HtmlMedia.extract(contentHtml, sourceUrl, referer).forEach {
            add(it.url, it.kind, it.isCover)
        }
    }
    visitVideoNodes(target, ::add)
    visitVideoNodes(item, ::add)

    return ZhihuContent(
        id = id,
        kind = kind,
        title = title,
        url = sourceUrl,
        html = contentHtml.orEmpty(),
        createdAt = created,
        media = assets.values.toList(),
    )
}

private fun addVideoFields(node: JSONObject, add: (String?, MediaKind, Boolean) -> Unit) {
    VIDEO_KEYS.forEach { key -> add(node.optStringOf(key), MediaKind.VIDEO, false) }
    // `url` is ambiguous on Zhihu: it is often the poster. Only trust it when the
    // path actually looks like a video file.
    GENERIC_VIDEO_KEYS.forEach { key ->
        val url = node.optStringOf(key)
        if (url != null && looksLikeVideo(url)) add(url, MediaKind.VIDEO, false)
    }
    COVER_KEYS.forEach { key -> add(node.optStringOf(key), MediaKind.IMAGE, true) }
}

private fun looksLikeVideo(url: String): Boolean {
    val extension = runCatching { URI.create(url).path.substringAfterLast('.', "") }
        .getOrDefault("").lowercase()
    return extension in VIDEO_EXTENSIONS
}

private fun visitVideoNodes(root: JSONObject, add: (String?, MediaKind, Boolean) -> Unit) {
    for (key in listOf("video", "videos", "video_info", "playlist", "media", "original")) {
        when (val node = root.opt(key)) {
            is JSONObject -> {
                addVideoFields(node, add)
                visitVideoNodes(node, add)
            }
            is org.json.JSONArray -> node.objects().forEach { child ->
                addVideoFields(child, add)
                visitVideoNodes(child, add)
            }
        }
    }
}
