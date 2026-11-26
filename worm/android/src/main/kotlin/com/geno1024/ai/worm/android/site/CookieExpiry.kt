package com.geno1024.ai.worm.android.site

/**
 * Pure builder for WebView cookie-expiry directives. Kept separate from the
 * Android `CookieManager` so domain/host handling can be unit-tested offline.
 */
internal object CookieExpiry {
    private const val EXPIRED = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"

    /**
     * Cookies set with an explicit `Domain=.example.com` need a matching expiry on
     * the base domain; host-only cookies need one on each URL they were read from.
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
        for (domain in domains) {
            for (name in names) {
                result += "https://$domain/" to "$name=; $EXPIRED; Domain=.$domain"
            }
        }
        return result
    }
}
