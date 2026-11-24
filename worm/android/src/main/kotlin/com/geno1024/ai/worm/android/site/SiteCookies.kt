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

    fun read(site: Site): Map<String, String> {
        val manager = CookieManager.getInstance()
        val jar = LinkedHashMap<String, String>()
        val urls = when (site) {
            Site.QQ -> QQ_URLS
            Site.ZHIHU -> ZHIHU_URLS
        }
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
    }

    fun flush() {
        CookieManager.getInstance().flush()
    }
}
