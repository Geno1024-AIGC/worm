package com.geno1024.ai.worm.android.ui

import com.geno1024.ai.worm.android.site.Source
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SourceIconsTest {
    @Test
    fun `every source has a parseable monochrome glyph`() {
        // Building SourceIcons parses every embedded path; a malformed glyph throws here.
        for (source in Source.entries) {
            assertNotNull(SourceIcons.of(source), "${source.label} has no icon")
        }
    }
}
