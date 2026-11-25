package com.geno1024.ai.worm.core.qzone

import com.geno1024.ai.worm.core.CrawlException
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.Jsonp
import com.geno1024.ai.worm.core.responseProblem
import com.geno1024.ai.worm.core.optArrayOf
import org.json.JSONArray
import org.json.JSONObject

/** Qzone-specific login state derived from the cookies the app collected. */
data class QzoneAuth(
    val session: CrawlSession,
    val gtk: Long,
    val loginUin: String?,
)

/** Classic QQ `g_tk` hash; `p_skey` wins over `skey`. */
fun computeGtk(skey: String): Long {
    var hash = 5381L
    for (ch in skey) hash += (hash shl 5) + ch.code
    return hash and 0x7fffffff
}

fun normalizeUin(value: String): String =
    value.trim().removePrefix("o").trimStart('0').ifEmpty { "0" }
        .takeIf { it.all(Char::isDigit) } ?: ""

fun requireQzoneAuth(session: CrawlSession): QzoneAuth {
    val skey = session.cookies["p_skey"]?.takeIf { it.isNotBlank() }
        ?: session.cookies["skey"]?.takeIf { it.isNotBlank() }
        ?: throw CrawlException("Qzone login is missing p_skey/skey")
    val loginUin = listOfNotNull(session.cookies["uin"], session.cookies["p_uin"])
        .firstOrNull { it.isNotBlank() }
        ?.let(::normalizeUin)
        ?.takeIf { it.isNotBlank() && it != "0" }
    return QzoneAuth(session, computeGtk(skey), loginUin)
}

data class QzonePage(
    val code: Int,
    val subcode: Int,
    val message: String,
    val messages: JSONArray,
    val total: Int?,
    val hasMore: Boolean,
)

fun isAuthError(code: Int, subcode: Int): Boolean = code == -3000 || code == -4002 || subcode == -3000

/** Pure parse: JSONP string in, typed page out. */
fun parseMsgListResponse(body: String): QzonePage {
    val object_ = Jsonp.objectOrNull(body) ?: return QzonePage(
        code = -1, subcode = 0, message = responseProblem(body),
        messages = JSONArray(), total = null, hasMore = false,
    )
    val messages = object_.optArrayOf("msglist", "msgList") ?: JSONArray()
    val explicitMore = object_.opt("hasMore") ?: object_.opt("more")
    return QzonePage(
        code = object_.optInt("code"),
        subcode = object_.optInt("subcode"),
        message = object_.optString("message"),
        messages = messages,
        total = object_.opt("total")?.toString()?.toIntOrNull(),
        hasMore = when (explicitMore) {
            is Boolean -> explicitMore
            is Number -> explicitMore.toInt() != 0
            is String -> explicitMore.equals("true", ignoreCase = true) || explicitMore == "1"
            else -> messages.length() > 0
        },
    )
}

fun buildMsgListUrl(targetUin: String, gtk: Long, pos: Int, num: Int): String =
    "https://user.qzone.qq.com/proxy/domain/taotao.qq.com/cgi-bin/emotion_cgi_msglist_v6" +
        "?uin=$targetUin&ftype=0&sort=0&pos=$pos&num=$num&replynum=100&g_tk=$gtk" +
        "&callback=_preloadCallback&code_version=1&format=jsonp&need_private_comment=1"

fun JSONObject.asMap(): Map<String, Any?> {
    fun value(any: Any?): Any? = when (any) {
        is JSONObject -> any.keySet().associateWith { value(any.opt(it)) }
        is JSONArray -> (0 until any.length()).map { value(any.opt(it)) }
        else -> any
    }
    return keySet().associateWith { value(opt(it)) }
}
