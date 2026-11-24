package com.geno1024.ai.worm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArgParserTest {
    @Test
    fun `parses switches values and positionals`() {
        val args = ArgParser(
            arrayOf("--all", "--out", "result.json", "--num=20", "--delay", "800", "123456", "extra"),
            flagNames = setOf("all"),
        )
        assertTrue(args.flag("all"))
        assertFalse(args.flag("missing"))
        assertEquals("result.json", args.string("out"))
        assertEquals(20, args.int("num", 0))
        assertEquals(800L, args.long("delay", 0))
        assertEquals(listOf("123456", "extra"), args.positionals)
    }
}
