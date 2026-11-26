package com.geno1024.ai.worm.weibo

import com.geno1024.ai.worm.core.Jsonp
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.optArrayOf
import com.geno1024.ai.worm.core.optLongOf
import com.geno1024.ai.worm.core.optObjectOf
import com.geno1024.ai.worm.core.optStringOf
import com.geno1024.ai.worm.core.objects
import com.geno1024.ai.worm.core.responseProblem
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

const val WEIBO_REFERER: String = "https://weibo.com/"

/** Accepts a numeric uid or any `weibo.com/u/<uid>` / `weibo.cn/<uid>` profile URL. */
fun normalizeWeiboSubject(input: String): String {
    val trimmed = input.trim()
    val profile = Regex("""weibo\.(?:com|cn)/(?:u/)?(\d+)""").find(trimmed)
    return profile?.groupValues?.get(1) ?: Regex("""\d+""").find(trimmed)?.value.orEmpty()
}

fun buildWeiboListUrl(uid: String, page: Int): String =
    "https://m.weibo.cn/api/container/getIndex?type=uid&value=$uid&containerid=107603$uid&page=$page"

/** The logged-in web timeline often exposes older posts than the mobile container. */
fun buildWeiboTimelineUrl(uid: String, page: Int): String =
    "https://weibo.com/ajax/statuses/mymblog?uid=$uid&page=$page&feature=0"

fun weiboWebReferer(uid: String): String = "https://weibo.com/u/$uid"

val WEIBO_WEB_HEADERS: Map<String, String> = mapOf(
    "x-requested-with" to "XMLHttpRequest",
    "accept" to "application/json, text/plain, */*",
)

data class WeiboPost(
    val id: String,
    val createdAt: Long?,
    val html: String,
    val media: List<MediaAsset>,
)

data class WeiboListPage(
    val posts: List<WeiboPost>,
    val pageCount: Int? = null,
    val error: String? = null,
)

/** Pure parse of the m.weibo.cn container response. */
fun parseWeiboListPage(
    body: String,
    uid: String,
    referer: String = WEIBO_REFERER,
    nowSec: Long = System.currentTimeMillis() / 1000,
): WeiboListPage {
    val root = Jsonp.objectOrNull(body) ?: return WeiboListPage(emptyList(), null, responseProblem(body))
    val data = root.optObjectOf("data")
    if (data == null) {
        val problem = root.optStringOf("msg", "message") ?: "接口未返回 data"
        return WeiboListPage(emptyList(), null, if (root.optInt("ok", 0) == 0) "微博接口错误：$problem" else problem)
    }

    val posts = mutableListOf<WeiboPost>()
    fun add(mblog: JSONObject) {
        postOf(mblog, referer, nowSec)?.let(posts::add)
    }

    val cards = data.optArrayOf("cards") ?: return WeiboListPage(emptyList())
    for (card in cards.objects()) {
        card.optObjectOf("mblog")?.let(::add)
        card.optArrayOf("card_group")?.objects().orEmpty().forEach { group ->
            group.optObjectOf("mblog")?.let(::add)
        }
    }
    val total = data.optObjectOf("cardlistInfo")?.optLongOf("total")?.takeIf { it > 0 }
    return WeiboListPage(posts, total?.let { ((it + 19) / 20).toInt() })
}

/** Pure parse of the logged-in `weibo.com/ajax/statuses/mymblog` response. */
fun parseWeiboTimelinePage(
    body: String,
    referer: String,
    nowSec: Long = System.currentTimeMillis() / 1000,
): WeiboListPage {
    val root = Jsonp.objectOrNull(body) ?: return WeiboListPage(emptyList(), null, responseProblem(body))
    if (root.optInt("ok", 1) == 0) {
        val problem = root.optStringOf("msg", "message") ?: "未知错误"
        return WeiboListPage(emptyList(), null, "微博网页接口错误：$problem")
    }
    val data = root.optObjectOf("data")
        ?: return WeiboListPage(emptyList(), null, "微博网页接口未返回 data：${responseProblem(body)}")
    val list = data.optArrayOf("list") ?: return WeiboListPage(emptyList())
    val posts = list.objects().mapNotNull { postOf(it, referer, nowSec) }
    val total = data.optLongOf("total", "total_number")?.takeIf { it > 0 }
    return WeiboListPage(posts, total?.let { ((it + 19) / 20).toInt() })
}

private fun postOf(mblog: JSONObject, referer: String, nowSec: Long): WeiboPost? {
    val id = mblog.optStringOf("id", "mid", "idstr") ?: return null
    val createdAt = parseWeiboTime(mblog.optStringOf("created_at", "createdAt"), nowSec)
    return WeiboPost(
        id = id,
        createdAt = createdAt,
        html = mblog.optStringOf("text", "text_raw").orEmpty(),
        media = WeiboMedia.extract(mblog, createdAt, referer),
    )
}

/** Weibo mixes absolute dates, relative labels and epoch numbers in `created_at`. */
fun parseWeiboTime(value: String?, nowSec: Long): Long? {
    val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    text.toLongOrNull()?.let { number ->
        return if (number > 100_000_000_000L) number / 1000L else number
    }
    if (text == "刚刚") return nowSec
    Regex("""(\d+)\s*分钟前""").find(text)?.let { return nowSec - it.groupValues[1].toLong() * 60 }
    Regex("""(\d+)\s*小时前""").find(text)?.let { return nowSec - it.groupValues[1].toLong() * 3600 }
    if (text.startsWith("昨天")) {
        val hourMinute = Regex("""(\d{1,2}):(\d{2})""").find(text) ?: return null
        val zone = ZoneId.systemDefault()
        val time = LocalDateTime.now(zone).minusDays(1)
            .withHour(hourMinute.groupValues[1].toInt())
            .withMinute(hourMinute.groupValues[2].toInt())
        return time.atZone(zone).toEpochSecond()
    }
    return runCatching {
        SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).parse(text)?.time?.div(1000)
    }.getOrNull()
}
