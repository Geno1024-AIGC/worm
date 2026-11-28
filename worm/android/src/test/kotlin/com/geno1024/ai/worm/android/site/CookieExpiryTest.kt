package com.geno1024.ai.worm.android.site

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CookieExpiryTest {
    @Test
    fun `expiry directives cover host-only and domain cookies`() {
        val directives = CookieExpiry.directives(
            names = setOf("SUB", "SUBP"),
            urls = listOf("https://weibo.cn/", "https://weibo.com/"),
            domains = listOf("weibo.cn", "weibo.com"),
        )
        assertTrue(directives.any {
            it.first == "https://weibo.cn/" && it.second.startsWith("SUB=;") &&
                it.second.contains("Max-Age=0") && !it.second.contains("Domain=")
        })
        assertTrue(directives.any {
            it.first == "https://weibo.com/" && it.second.startsWith("SUBP=;") &&
                it.second.contains("Domain=.weibo.com")
        })
        assertTrue(directives.all { it.second.contains("Path=/") })
    }

    @Test
    fun `Qzone subdomain cookies get their own domain expiry`() {
        val directives = CookieExpiry.directives(
            names = setOf("skey", "p_skey"),
            urls = listOf("https://user.qzone.qq.com/", "https://qzone.qq.com/", "https://qq.com/"),
            domains = listOf("qq.com"),
        )
        // skey under .qzone.qq.com must be expired on qzone.qq.com, not just .qq.com.
        assertTrue(directives.any {
            it.first == "https://qzone.qq.com/" && it.second.startsWith("skey=;") &&
                it.second.contains("Domain=.qzone.qq.com")
        })
        assertTrue(directives.any {
            it.first == "https://user.qzone.qq.com/" && it.second.startsWith("p_skey=;") &&
                it.second.contains("Domain=.user.qzone.qq.com")
        })
        assertTrue(directives.any {
            it.first == "https://qq.com/" && it.second.startsWith("skey=;") &&
                it.second.contains("Domain=.qq.com")
        })
    }
}
