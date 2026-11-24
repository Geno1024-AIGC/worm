package com.geno1024.ai.worm.core

import org.jsoup.Jsoup

/**
 * Extracts media from an HTML fragment (answers, articles, rich posts). Parsing is
 * pure so it can be tested with offline fixtures; callers supply the page URL as
 * [baseUrl] to resolve relative links.
 */
object HtmlMedia {
    fun extract(html: String, baseUrl: String = "", referer: String? = baseUrl): List<MediaAsset> {
        val document = Jsoup.parseBodyFragment(html, baseUrl)
        val found = linkedMapOf<String, MediaAsset>()

        fun add(rawUrl: String?, kind: MediaKind, isCover: Boolean = false) {
            val absolute = rawUrl?.trim()?.takeIf { it.isNotBlank() }
                ?.let { document.selectFirst("base[href]")?.absUrl("href")?.let { base -> resolve(it, base) } ?: resolve(it, baseUrl) }
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
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

    private fun resolve(url: String, baseUrl: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        baseUrl.isEmpty() -> url
        else -> java.net.URI(baseUrl).resolve(url).toString()
    }
}
