package com.geno1024.ai.worm.android.site

import android.webkit.CookieManager

/**
 * WebView is the only login UI we need: the crawlers take a flat name→value map,
 * so several domains can be merged as long as a name never collides.
 */
object SiteCookies {
    private val QQ_URLS = listOf(
        "https://user.qzone.qq.com/",
        "https://m.qzone.qq.com/",
        "https://qzone.qq.com/",
        "https://ptlogin2.qq.com/",
        "https://qq.com/",
    )
    private val ZHIHU_URLS = listOf(
        "https://www.zhihu.com/",
        "https://zhihu.com/",
        "https://api.zhihu.com/",
    )
    private val WEIBO_URLS = listOf(
        "https://weibo.cn/",
        "https://m.weibo.cn/",
        "https://weibo.com/",
        "https://passport.weibo.cn/",
    )
    private val FACEBOOK_URLS = listOf(
        "https://www.facebook.com/",
        "https://m.facebook.com/",
        "https://facebook.com/",
        "https://graph.facebook.com/",
    )

    private fun urls(site: Site): List<String> = when (site) {
        Site.QQ -> QQ_URLS
        Site.ZHIHU -> ZHIHU_URLS
        Site.WEIBO -> WEIBO_URLS
        Site.FACEBOOK -> FACEBOOK_URLS
    }

    private fun domains(site: Site): List<String> = when (site) {
        Site.QQ -> listOf("qq.com")
        Site.ZHIHU -> listOf("zhihu.com")
        // Weibo writes SUB/SUBP under weibo.cn as well as weibo.com.
        Site.WEIBO -> listOf("weibo.cn", "weibo.com")
        Site.FACEBOOK -> listOf("facebook.com")
    }

    fun read(site: Site): Map<String, String> {
        val manager = CookieManager.getInstance()
        val jar = LinkedHashMap<String, String>()
        val urls = urls(site)
        for (url in urls) {
            val raw = manager.getCookie(url) ?: continue
            for (part in raw.split(';')) {
                val entry = part.trim()
                val separator = entry.indexOf('=')
                if (separator <= 0) continue
                val name = entry.substring(0, separator).trim()
                if (name.isNotEmpty()) jar[name] = entry.substring(separator + 1).trim()
            }
        }
        return jar
    }

    fun hasLogin(site: Site, cookies: Map<String, String>): Boolean = when (site) {
        Site.QQ -> cookies["p_skey"]?.isNotBlank() == true || cookies["skey"]?.isNotBlank() == true
        Site.ZHIHU -> cookies["z_c0"]?.isNotBlank() == true
        // Facebook can authenticate either with a Graph token or the web session cookie.
        Site.WEIBO -> cookies["SUB"]?.isNotBlank() == true || cookies["SUBP"]?.isNotBlank() == true
        Site.FACEBOOK -> cookies["access_token"]?.isNotBlank() == true ||
            cookies["xs"]?.isNotBlank() == true
    }

    /** Clears only [site]'s cookies; other sites' login state stays intact. */
    fun clear(site: Site): Int {
        val names = read(site).keys.toSet()
        if (names.isEmpty()) {
            flush()
            return 0
        }
        val manager = CookieManager.getInstance()
        for ((url, directive) in CookieExpiry.directives(names, urls(site), domains(site))) {
            manager.setCookie(url, directive)
        }
        flush()
        return names.size
    }

    fun flush() {
        CookieManager.getInstance().flush()
    }
}
