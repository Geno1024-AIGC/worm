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
)

data class PhotoListPage(
    val photos: JSONArray,
    val totalInAlbum: Int?,
    val code: Int,
    val subcode: Int,
    val message: String,
)

private fun now(): Long = System.currentTimeMillis() % 1_000_000

fun buildAlbumListUrl(targetUin: String, gtk: Long): String {
    val uin = normalizeUin(targetUin)
    return "https://user.qzone.qq.com/proxy/domain/photo.qzone.qq.com/fcgi-bin/fcg_list_album_v3" +
        "?g_tk=$gtk&t=${now()}&hostUin=$uin&uin=$uin&appid=4" +
        "&inCharset=utf-8&outCharset=utf-8&source=qzone&plat=qzone&format=jsonp" +
        "&notice=0&filter=1&handset=4&pageNumModeSort=40&pageNumModeClass=15" +
        "&needUserInfo=1&idcNum=4&callbackFun=shine0&callback=shine0_Callback"
}

fun buildPhotoListUrl(targetUin: String, gtk: Long, albumId: String, pageStart: Int, pageNum: Int): String {
    val uin = normalizeUin(targetUin)
    return "https://h5.qzone.qq.com/proxy/domain/photo.qzone.qq.com/fcgi-bin/cgi_list_photo" +
        "?g_tk=$gtk&t=${now()}&mode=0&idcNum=4&hostUin=$uin&topicId=$albumId" +
        "&noTopic=0&uin=$uin&pageStart=$pageStart&pageNum=$pageNum" +
        "&skipCmtCount=0&singleurl=1&batchId=&notice=0&appid=4" +
        "&inCharset=utf-8&outCharset=utf-8&source=qzone&plat=qzone" +
        "&outstyle=json&format=jsonp&json_esc=1&question=&answer=" +
        "&callbackFun=shine0&callback=shine0_Callback"
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
    return AlbumListResult(albums.values.toList(), code, subcode, message)
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
    val total = when {
        !data.isNull("totalInAlbum") && data.has("totalInAlbum") -> data.optInt("totalInAlbum")
        !data.isNull("total") && data.has("total") -> data.optInt("total")
        else -> null
    }
    return PhotoListPage(photos, total, code, subcode, message)
}
