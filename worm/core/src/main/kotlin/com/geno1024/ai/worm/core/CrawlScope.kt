package com.geno1024.ai.worm.core

/** Upper bound used by the "all pages" choice; keeps a runaway API from looping forever. */
const val ALL_PAGES = 1000

/**
 * Page cap from a UI/CLI value. Blank or invalid falls back to [default]; a non-positive
 * value means "as many pages as [ALL_PAGES] allows", and huge values are clamped.
 */
fun pageCapOf(text: String?, default: Int = 10): Int {
    val value = text?.trim()?.toIntOrNull() ?: return default
    if (value <= 0) return ALL_PAGES
    return value.coerceIn(1, ALL_PAGES)
}

/** Total pages implied by an API-reported item count, when the API reports one at all. */
fun pageCountOf(total: Int?, pageSize: Int): Int? =
    total?.takeIf { it > 0 }?.let { (it + pageSize - 1) / pageSize }

/**
 * Overlap applied to an incremental watermark. A device clock a few hours fast
 * must not make freshly published posts look "already seen"; six hours costs at
 * most one extra page on the sites we crawl.
 */
const val INCREMENTAL_OVERLAP_SEC: Long = 6 * 60 * 60L

/** Recent identities kept per target; enough to cover the overlap without growing forever. */
const val SEEN_ID_LIMIT = 300

/** One item is older than the incremental watermark and needs no download. */
fun isBeforeWatermark(createdAt: Long?, since: Long?): Boolean =
    createdAt != null && since != null && createdAt < since

/**
 * A whole page is older than the watermark. Sites we crawl sort newest first, so
 * later pages cannot contain anything new; stop paginating instead of replaying history.
 * Pages with unknown timestamps are never treated as old.
 */
fun reachedWatermark(times: List<Long?>, since: Long?): Boolean =
    since != null && times.isNotEmpty() && times.all { it != null && it < since }
