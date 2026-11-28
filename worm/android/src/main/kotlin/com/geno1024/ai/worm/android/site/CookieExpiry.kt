package com.geno1024.ai.worm.android.site

import java.net.URI

/**
 * Pure builder for WebView cookie-expiry directives. Kept separate from the
 * Android `CookieManager` so domain/host handling can be unit-tested offline.
 */
internal object CookieExpiry {
    private const val EXPIRED = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"

    /**
     * Host-only cookies need an expiry on each URL they were read from. Domain
     * cookies must be expired on the exact domain they were set under: Qzone
     * writes `skey` / `p_skey` under `.qzone.qq.com` as well as `.qq.com`, and a
     * `Domain=.qq.com` expiry cannot remove a `.qzone.qq.com` cookie. So besides
     * the registrable [domains] we also cover every URL's own host as a domain.
     */
    fun directives(
        names: Collection<String>,
        urls: List<String>,
        domains: List<String>,
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (url in urls) {
            for (name in names) result += url to "$name=; $EXPIRED"
        }
        val domainTargets = (urls.mapNotNull { hostOf(it) } + domains).distinct()
        for (domain in domainTargets) {
            for (name in names) {
                result += "https://$domain/" to "$name=; $EXPIRED; Domain=.$domain"
            }
        }
        return result
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}
