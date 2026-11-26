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
}
