package com.geno1024.ai.worm.facebook

import com.geno1024.ai.worm.core.Jsonp
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.MediaFiles
import com.geno1024.ai.worm.core.MediaKind
import com.geno1024.ai.worm.core.optArrayOf
import com.geno1024.ai.worm.core.optObjectOf
import com.geno1024.ai.worm.core.optStringOf
import com.geno1024.ai.worm.core.objects
import com.geno1024.ai.worm.core.responseProblem
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

const val FACEBOOK_REFERER: String = "https://www.facebook.com/"
const val GRAPH_BASE: String = "https://graph.facebook.com/v19.0"

enum class FacebookMode(val label: String) {
    AUTO("自动"), GRAPH("Graph API"), COOKIE("网页 Cookie"),
}

/** Accepts a numeric id, a page name, or a facebook.com/<name> / profile.php?id= URL. */
fun normalizeFacebookSubject(input: String): String {
    val raw = input.trim().trim('/')
    val id = Regex("""profile\.php\?[^#\s]*?id=(\d+)""").find(raw)?.groupValues?.get(1)
        ?: Regex("""facebook\.com/(?:m/)?([A-Za-z0-9.]+)""").find(raw)?.groupValues?.get(1)
        ?: raw.substringBefore('/').substringBefore('?')
    return id.takeIf { Regex("""[A-Za-z0-9.]{1,100}""").matches(it) }.orEmpty()
}

fun buildGraphUrl(subject: String, edge: String, token: String, after: String?, limit: Int): String {
    val fields = when (edge) {
        "videos" -> "id,created_time,title,source,picture"
        else -> "id,created_time,name,images{source,width,height}"
    }
    val encoded = URLEncoder.encode(after.orEmpty(), "UTF-8")
    return "$GRAPH_BASE/$subject/$edge?fields=$fields&limit=$limit" +
        (if (after.isNullOrBlank()) "" else "&after=$encoded") +
        "&access_token=${URLEncoder.encode(token, "UTF-8")}"
}

fun buildFacebookPhotoPageUrl(subject: String, cursor: String? = null): String =
    "https://m.facebook.com/$subject/photos" + (if (cursor.isNullOrBlank()) "" else "?after=$cursor")

data class FacebookEntry(
    val id: String,
    val createdAt: Long?,
    val title: String?,
    val media: List<MediaAsset>,
)

data class FacebookPage(
    val entries: List<FacebookEntry>,
    val nextAfter: String? = null,
    val nextUrl: String? = null,
    val error: String? = null,
)

/** Facebook returns `2026-07-21T13:03:20+0000`, which Instant.parse needs colon-separated. */
fun parseFacebookTime(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    text.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000L else it }
    val normalized = Regex("""([+-]\d{2})(\d{2})$""").replace(text, "$1:$2")
    return runCatching { Instant.parse(normalized).epochSecond }.getOrNull()
        ?: runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH).parse(text)?.time?.div(1000)
        }.getOrNull()
}

/** Pure parse of a Graph API `photos` or `videos` edge. */
fun parseGraphPage(body: String, edge: String): FacebookPage {
    val root = Jsonp.objectOrNull(body) ?: return FacebookPage(emptyList(), error = responseProblem(body))
    val errorObject = root.optObjectOf("error")
    if (errorObject != null || (root.opt("data") == null && root.optStringOf("message") != null)) {
        val code = errorObject?.optInt("code", 0) ?: root.optInt("code", 0)
        val message = errorObject?.optStringOf("message", "msg") ?: root.optStringOf("message") ?: "未知错误"
        val problem = if (code == 190 || code == 102 || code == 401 ||
            message.contains("token", ignoreCase = true)
        ) "access_token 无效或过期" else "Graph API 错误"
        return FacebookPage(emptyList(), error = "$problem（code=$code）：$message")
    }

    val entries = root.optArrayOf("data")?.objects().orEmpty().mapNotNull { item ->
        graphEntry(item, edge)
    }
    val paging = root.optObjectOf("paging")
    val after = paging?.optObjectOf("cursors")?.optStringOf("after")?.takeIf { it.isNotBlank() }
    val hasNext = paging?.optStringOf("next") != null || after != null
    return FacebookPage(entries, nextAfter = after?.takeIf { hasNext })
}

private fun graphEntry(item: JSONObject, edge: String): FacebookEntry? {
    val id = item.optStringOf("id") ?: return null
    val createdAt = parseFacebookTime(item.optStringOf("created_time"))
    val media = mutableListOf<MediaAsset>()
    if (edge == "videos") {
        item.optStringOf("source")?.takeIf { it.startsWith("http") }?.let { url ->
            media += MediaAsset(
                url, MediaKind.VIDEO, id = id, createdAt = createdAt, referer = FACEBOOK_REFERER,
                extension = MediaFiles.extensionOf(url, MediaKind.VIDEO),
            )
        }
        val picture = item.optStringOf("picture")
            ?: item.optObjectOf("picture")?.optStringOf("url")
        picture?.takeIf { it.startsWith("http") }?.let { url ->
            media += MediaAsset(
                url, MediaKind.IMAGE, id = id, createdAt = createdAt, referer = FACEBOOK_REFERER,
                extension = MediaFiles.extensionOf(url, MediaKind.IMAGE), isCover = true,
            )
        }
    } else {
        val image = item.optArrayOf("images")?.objects().orEmpty()
            .maxByOrNull { it.optInt("width", 0) * it.optInt("height", 0) }
            ?.optStringOf("source")
        image?.takeIf { it.startsWith("http") }?.let { url ->
            media += MediaAsset(
                url, MediaKind.IMAGE, id = id, createdAt = createdAt, referer = FACEBOOK_REFERER,
                extension = MediaFiles.extensionOf(url, MediaKind.IMAGE),
            )
        }
    }
    return FacebookEntry(id, createdAt, item.optStringOf("name", "title"), media)
}

/** The cookie path is best-effort: Facebook changes its markup often. Never a silent empty result. */
fun parseFacebookPhotoPage(body: String, pageUrl: String): FacebookPage {
    val doc = Jsoup.parse(body)
    val base = runCatching { URI(pageUrl) }.getOrNull()
    val images = LinkedHashMap<String, MediaAsset>()
    val videos = LinkedHashMap<String, MediaAsset>()
    val createdAt = Regex("""(?:data-utime|creation_time|publish_time)["':= ]+(\d{9,13})""").find(body)
        ?.groupValues?.get(1)?.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000 else it }

    fun addImage(raw: String?) {
        val url = unescape(raw.orEmpty())
        if (!url.startsWith("http") || !isCdnUrl(url)) return
        if (!Regex("""\.(jpg|jpeg|png|webp)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) return
        images.putIfAbsent(url.substringAfterLast('/').substringBefore('?'), MediaAsset(
            url, MediaKind.IMAGE, createdAt = createdAt, referer = FACEBOOK_REFERER,
            extension = MediaFiles.extensionOf(url, MediaKind.IMAGE),
        ))
    }

    fun addVideo(raw: String?) {
        val url = unescape(raw.orEmpty())
        if (!url.startsWith("http") || !isCdnUrl(url)) return
        videos.putIfAbsent(url, MediaAsset(
            url, MediaKind.VIDEO, createdAt = createdAt, referer = FACEBOOK_REFERER,
            extension = MediaFiles.extensionOf(url, MediaKind.VIDEO),
        ))
    }

    doc.select("img[src], img[data-src], video[src]").forEach { element ->
        val raw = element.absUrl("src").ifBlank { element.attr("data-src") }
        if (element.tagName() == "video") addVideo(raw) else addImage(raw)
    }
    doc.select("script").forEach { script ->
        val json = unescape(script.data())
        Regex("""\"(?:image_uri|imageURI|display_url|browser_native_hd_url)\"\s*:\s*\"([^\"]+)\"""")
            .findAll(json).forEach { match -> addImage(match.groupValues[1]) }
        Regex("""\"(?:playable_url|playable_url_quality_hd)\"\s*:\s*\"([^\"]+)\"""")
            .findAll(json).forEach { match -> addVideo(match.groupValues[1]) }
    }

    val nextHref = doc.select("a[href]").firstOrNull { element ->
        element.attr("rel").contains("next") ||
            element.text().let { it.contains("next", true) || it.contains("下一页") }
    }?.attr("href")
    val nextUrl = nextHref?.let { href -> base?.resolve(href)?.toString() ?: href }
        ?.takeIf { it != pageUrl }

    val media = images.values + videos.values
    val entry = FacebookEntry(
        id = "page-${pageUrl.hashCode()}", createdAt = createdAt, title = null, media = media,
    )
    return FacebookPage(listOf(entry), nextUrl = nextUrl)
}

fun isFacebookLoginPage(body: String): Boolean = body.contains("id=\"login_form\"") ||
    body.contains("name=\"pass\"") || body.contains("checkpoint")

private fun unescape(value: String): String = value
    .replace("\\/", "/").replace("\\u0025", "%").replace("&amp;", "&")

private fun isCdnUrl(url: String): Boolean =
    url.contains("fbcdn.net") || url.contains("facebook.com")
