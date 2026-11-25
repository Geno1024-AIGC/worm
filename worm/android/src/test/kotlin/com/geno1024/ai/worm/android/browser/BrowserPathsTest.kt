package com.geno1024.ai.worm.android.browser

import com.geno1024.ai.worm.core.MediaKind
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

    @Test
    fun `media sort newest first and keep unknown times last`() {
        val sorted = sortMediaByTime(
            listOf(
                LocalMedia("a", "a.jpg", MediaKind.IMAGE, modifiedAt = 1000L),
                LocalMedia("c", "c.jpg", MediaKind.IMAGE, modifiedAt = null),
                LocalMedia("b", "b.jpg", MediaKind.IMAGE, modifiedAt = 3000L),
                LocalMedia("d", "d.jpg", MediaKind.IMAGE, modifiedAt = 2000L),
                LocalMedia("e", "e.jpg", MediaKind.IMAGE, modifiedAt = null),
            ),
        )
        assertEquals(listOf("b.jpg", "d.jpg", "a.jpg", "c.jpg", "e.jpg"), sorted.map { it.name })
    }
}
