package com.geno1024.ai.worm.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One successful run for one crawler + subject pair. Times are epoch seconds. */
data class CrawlStamp(
    val crawlerId: String,
    val subject: String,
    val lastSuccessAt: Long,
    val newestItemAt: Long? = null,
    val seenIds: List<String> = emptyList(),
)

/** NUL cannot occur in a crawler id or a user token, so keys never collide. */
fun crawlKey(crawlerId: String, subject: String): String = "$crawlerId\u0000$subject"

/** The watermark handed to a crawler; null means "full crawl". */
fun sinceOf(stamp: CrawlStamp?): Long? {
    val anchor = listOfNotNull(
        stamp?.lastSuccessAt?.takeIf { it > 0 },
        stamp?.newestItemAt?.takeIf { it > 0 },
    ).minOrNull() ?: return null
    return (anchor - INCREMENTAL_OVERLAP_SEC).coerceAtLeast(0)
}

/** Pure parse, so a corrupt journal can be tested without touching the filesystem. */
fun parseCrawlJournal(body: String): Map<String, CrawlStamp> {
    if (body.isBlank()) return emptyMap()
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyMap()
    val result = linkedMapOf<String, CrawlStamp>()
    for (key in root.keys()) {
        val value = runCatching { root.getJSONObject(key) }.getOrNull() ?: continue
        val at = value.optLong("lastSuccessAt", -1)
        if (at <= 0) continue
        result[key] = CrawlStamp(
            crawlerId = value.optString("crawlerId"),
            subject = value.optString("subject"),
            lastSuccessAt = at,
            newestItemAt = value.optLong("newestItemAt", -1).takeIf { it > 0 },
            seenIds = value.optJSONArray("seenIds")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, null)?.takeIf { id -> id.isNotBlank() } }
            }.orEmpty(),
        )
    }
    return result
}

fun serializeCrawlJournal(stamps: Map<String, CrawlStamp>): String {
    val root = JSONObject()
    for ((key, stamp) in stamps) {
        val value = JSONObject()
            .put("crawlerId", stamp.crawlerId)
            .put("subject", stamp.subject)
            .put("lastSuccessAt", stamp.lastSuccessAt)
        stamp.newestItemAt?.let { value.put("newestItemAt", it) }
        if (stamp.seenIds.isNotEmpty()) {
            value.put("seenIds", JSONArray().apply { stamp.seenIds.forEach { put(it) } })
        }
        root.put(key, value)
    }
    return root.toString()
}

/**
 * Durable per-target timestamps. Writing is best-effort: a full journal or an
 * unwritable filesDir must never turn a successful crawl into a failure.
 */
class CrawlJournal(private val file: File) {
    fun all(): Map<String, CrawlStamp> = runCatching {
        if (file.isFile) parseCrawlJournal(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }

    fun lastSuccess(crawlerId: String, subject: String): CrawlStamp? =
        all()[crawlKey(crawlerId, subject)]

    fun record(stamp: CrawlStamp) = write { it[crawlKey(stamp.crawlerId, stamp.subject)] = stamp }

    fun forget(crawlerId: String, subject: String) = write { it.remove(crawlKey(crawlerId, subject)) }

    private fun write(change: (MutableMap<String, CrawlStamp>) -> Unit) {
        runCatching {
            val current = all().toMutableMap()
            change(current)
            file.parentFile?.mkdirs()
            file.writeText(serializeCrawlJournal(current))
        }
    }
}
