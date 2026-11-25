package com.geno1024.ai.worm.core

import org.jsoup.Jsoup

/**
 * Extracts media from an HTML fragment (answers, articles, rich posts). Parsing is
 * pure so it can be tested with offline fixtures; callers supply the page URL as
 * [baseUrl] to resolve relative links.
 */
object HtmlMedia {
    // Zhihu (and other rich-text sites) inline lazy-load placeholders as
    // `data:image/svg+xml;utf8,<svg ...>` — not downloadable media, and URI.create()
    // rejects the markup inside them with "Illegal character in opaque part".
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun extract(html: String, baseUrl: String = "", referer: String? = baseUrl): List<MediaAsset> {
        val document = Jsoup.parseBodyFragment(html, baseUrl)
        val found = linkedMapOf<String, MediaAsset>()

        fun add(rawUrl: String?, kind: MediaKind, isCover: Boolean = false) {
            val raw = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (SCHEME.containsMatchIn(raw) && !HTTP.containsMatchIn(raw)) return
            val absolute = (document.selectFirst("base[href]")?.absUrl("href")
                ?.let { base -> resolve(raw, base) } ?: resolve(raw, baseUrl))
                .takeIf { HTTP.containsMatchIn(it) && runCatching { java.net.URI.create(it) }.isSuccess }
                ?: return
            found.putIfAbsent(absolute, MediaAsset(absolute, kind, referer = referer, isCover = isCover))
        }

        for (image in document.select("img[src], img[data-original], img[data-actualsrc]")) {
            add(image.attr("data-original"), MediaKind.IMAGE)
            add(image.attr("data-actualsrc"), MediaKind.IMAGE)
            add(image.attr("src"), MediaKind.IMAGE)
        }
        for (node in document.select("video[src], video[data-url], video[poster], source[src]")) {
            add(node.attr("src"), MediaKind.VIDEO)
            add(node.attr("data-url"), MediaKind.VIDEO)
            add(node.attr("poster"), MediaKind.IMAGE, isCover = true)
        }
        return found.values.toList()
    }

    /** Malformed links are skipped by [add]; they must never abort the whole crawl. */
    private fun resolve(url: String, baseUrl: String): String = runCatching {
        when {
            HTTP.containsMatchIn(url) -> url
            url.startsWith("//") -> "https:$url"
            baseUrl.isEmpty() -> url
            else -> java.net.URI(baseUrl).resolve(url).toString()
        }
    }.getOrDefault(url)
}
