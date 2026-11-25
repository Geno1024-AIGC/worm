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
