package com.geno1024.ai.worm.core.qzone

import com.geno1024.ai.worm.core.Jsonp
import com.geno1024.ai.worm.core.objects
import org.json.JSONArray
import org.json.JSONObject

data class QzoneAlbum(
    val id: String,
    val name: String,
    val total: Int,
    val allowAccess: Boolean,
)

data class AlbumListResult(
    val albums: List<QzoneAlbum>,
    val code: Int,
    val subcode: Int,
    val message: String,
    val albumsInUser: Int? = null,
    val nextPageStart: Int? = null,
    val totalInPage: Int = 0,
    val hasMore: Boolean = false,
)

data class PhotoListPage(
    val photos: JSONArray,
    val totalInAlbum: Int?,
    val code: Int,
    val subcode: Int,
    val message: String,
    val nextPageStart: Int? = null,
    val totalInPage: Int = 0,
    val hasMore: Boolean = false,
)

/** -4009 is "no access to this space", -10805 "no permission for this album". */
fun isAlbumPermissionError(code: Int, subcode: Int): Boolean =
    code == -4009 || code == -10805 || subcode == -4009 || subcode == -10805

private fun now(): Long = System.currentTimeMillis() % 1_000_000

/**
 * The photo CGIs need both UINs: [hostUin] is the album owner (the crawl target),
 * [viewerUin] is the logged-in QQ whose cookies are actually sent. Setting both to
 * the owner makes every non-self album request fail with a non-zero code.
 */
fun buildAlbumListUrl(
    hostUin: String,
    viewerUin: String,
    gtk: Long,
    pageStart: Int = 0,
    pageNum: Int = 100,
): String {
    val host = normalizeUin(hostUin)
    val viewer = normalizeUin(viewerUin)
    return "https://user.qzone.qq.com/proxy/domain/photo.qzone.qq.com/fcgi-bin/fcg_list_album_v3" +
        "?g_tk=$gtk&t=${now()}&hostUin=$host&uin=$viewer&appid=4" +
        "&inCharset=utf-8&outCharset=utf-8&source=qzone&plat=qzone&format=jsonp" +
        "&notice=0&filter=1&handset=4&pageNumModeSort=$pageNum&pageNumModeClass=15" +
        "&needUserInfo=1&idcNum=4&mode=3&sortOrder=1&pageStart=$pageStart&pageNum=$pageNum" +
        "&callbackFun=shine0&callback=shine0_Callback"
}

fun buildPhotoListUrl(
    hostUin: String,
    viewerUin: String,
    gtk: Long,
    albumId: String,
    pageStart: Int,
    pageNum: Int,
): String {
    val host = normalizeUin(hostUin)
    val viewer = normalizeUin(viewerUin)
    return "https://h5.qzone.qq.com/proxy/domain/photo.qzone.qq.com/fcgi-bin/cgi_list_photo" +
        "?g_tk=$gtk&t=${now()}&mode=0&idcNum=4&hostUin=$host&topicId=$albumId" +
        "&noTopic=0&uin=$viewer&pageStart=$pageStart&pageNum=$pageNum" +
        "&skipCmtCount=0&singleurl=1&batchId=&notice=0&appid=4" +
        "&inCharset=utf-8&outCharset=utf-8&source=qzone&plat=qzone" +
        "&outstyle=json&format=jsonp&json_esc=1&question=&answer=" +
        "&callbackFun=shine0&callback=shine0_Callback"
}

/** JSON numbers sometimes arrive as strings; only non-negative values are paging hints. */
private fun JSONObject.optCount(vararg keys: String): Int? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = when (val raw = opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        if (value != null && value >= 0) return value
    }
    return null
}

private fun JSONObject.optMore(): Boolean? {
    for (key in arrayOf("hasMore", "has_more")) {
        if (!has(key) || isNull(key)) continue
        return when (val raw = opt(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> null
        }
    }
    return null
}

/** Pure parser. The endpoint has three different album array shapes depending on `mode`. */
fun parseAlbumListResponse(body: String): AlbumListResult {
    val root = Jsonp.objectOf(body)
    val code = root.optInt("code", -1)
    val subcode = root.optInt("subcode")
    val message = root.optString("message")
    if (code != 0) return AlbumListResult(emptyList(), code, subcode, message)
    val data = root.optJSONObject("data") ?: return AlbumListResult(emptyList(), code, subcode, message)

    val albums = linkedMapOf<String, QzoneAlbum>()
    fun add(object_: JSONObject) {
        val id = object_.optString("id").ifBlank { object_.optString("albumid") }.trim()
        if (id.isEmpty() || id in albums) return
        val name = object_.optString("name").ifBlank { object_.optString("title") }
            .ifBlank { id }.trim()
        val total = object_.optInt("total", object_.optInt("total_photo", 0))
        val allowAccess = object_.optInt("allowAccess", 1) != 0 && object_.optInt("allow_access", 1) != 0
        albums[id] = QzoneAlbum(id, name, total, allowAccess)
    }

    data.optJSONArray("albumListModeSort")?.objects()?.forEach(::add)
    data.optJSONArray("albumListModeClass")?.objects()?.forEach { group ->
        group.optJSONArray("albumList")?.objects()?.forEach(::add)
    }
    if (albums.isEmpty()) data.optJSONArray("albumList")?.objects()?.forEach(::add)

    val list = albums.values.toList()
    val next = data.optCount("nextPageStartModeSort", "nextPageStartModeClass", "nextPageStart")
    val inUser = data.optCount("albumsInUser", "total")
    val inPage = data.optCount("totalInPage") ?: list.size
    val more = data.optMore() ?: (next != null || inUser == null)
    return AlbumListResult(list, code, subcode, message, inUser, next, inPage, more)
}

/** Pure parser; a non-zero code yields an empty page plus the error fields. */
fun parsePhotoListResponse(body: String): PhotoListPage {
    val root = Jsonp.objectOf(body)
    val code = root.optInt("code", -1)
    val subcode = root.optInt("subcode")
    val message = root.optString("message")
    if (code != 0) return PhotoListPage(JSONArray(), null, code, subcode, message)
    val data = root.optJSONObject("data") ?: return PhotoListPage(JSONArray(), null, code, subcode, message)
    val photos = data.optJSONArray("photoList") ?: JSONArray()
    val total = data.optCount("totalInAlbum", "total")
    val next = data.optCount("nextPageStart")
    val inPage = data.optCount("totalInPage") ?: photos.length()
    val more = data.optMore() ?: (next != null || total == null)
    return PhotoListPage(photos, total, code, subcode, message, next, inPage, more)
}
