package com.geno1024.ai.worm.zhihu

/**
 * Zhihu content HTML links the same picture twice: a small `_r.jpg` in `src` and
 * a much larger `/50/<id>_720w.jpg` in `data-original`/`data-actualsrc`. Saving
 * both doubles every image, so variants are grouped by the CDN image id and
 * only the largest link actually present in the page is kept.
 *
 * Nothing is synthesised: a made-up "original" URL can 404, so we only choose
 * between URLs the API really gave us.
 */
object ZhihuMedia {
    private val SIZE_SUFFIX = Regex(
        "_(?:r|s|t|m|l|xl|\\d+w)$",
        RegexOption.IGNORE_CASE,
    )

    /** Host and the `/50/` thumbnail folder are irrelevant; the image id is the identity. */
    fun canonical(url: String): String {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return SIZE_SUFFIX.replace(name.substringBeforeLast('.'), "").lowercase()
    }

    /**
     * When a page only exposes `_r`, try larger variants on the SAME host/path. These
     * are candidates, not saved files: a 404 simply falls back to the original URL.
     */
    fun upgradeCandidates(url: String): List<String> {
        val clean = url.substringBefore('?').substringBefore('#')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return emptyList()
        val name = clean.substringAfterLast('/')
        val stem = name.substringBeforeLast('.')
        if (!SIZE_SUFFIX.containsMatchIn(stem)) return emptyList()
        val base = SIZE_SUFFIX.replace(stem, "")
        val extension = name.substringAfterLast('.', "jpg").ifBlank { "jpg" }
        val prefix = clean.substringBeforeLast('/') + "/"
        val query = url.substringAfter('?', "").takeIf { url.contains('?') }?.let { "?$it" }.orEmpty()
        return listOf("1440w", "1080w", "720w").map { "$prefix${base}_$it.$extension$query" }
    }

    /** Higher means larger. A URL without a size suffix is the original. */
    fun rank(url: String): Int {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val suffix = SIZE_SUFFIX.find(name.substringBeforeLast('.'))?.value?.removePrefix("_")?.lowercase()
            ?: return 1000
        return when (suffix) {
            "r", "s", "t" -> 10
            "m" -> 20
            "l", "xl" -> 30
            "720w" -> 40
            "1080w" -> 50
            "1440w" -> 60
            else -> suffix.removeSuffix("w").toIntOrNull()?.coerceIn(1, 999) ?: 1
        }
    }
}
