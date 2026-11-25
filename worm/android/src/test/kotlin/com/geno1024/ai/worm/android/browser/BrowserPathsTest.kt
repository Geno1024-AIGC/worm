package com.geno1024.ai.worm.android.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BrowserPathsTest {
    @Test
    fun `parent walks up one breadcrumb at a time`() {
        assertNull(parentFolder(""))
        assertEquals("", parentFolder("qzone"))
        assertEquals("qzone", parentFolder("qzone/12345"))
        assertEquals("qzone/12345", parentFolder("qzone/12345/旅行"))
        assertEquals("qzone/12345/旅行", childFolder("qzone/12345", "旅行"))
        assertEquals("qzone", childFolder("", "qzone"))
    }
}
