package com.geno1024.ai.worm.core

import java.io.File

/** Small dependency-free JSON writer for CLI artifacts and future exporters. */
object JsonOutput {
    fun value(input: Any?): String = when (input) {
        null -> "null"
        is String -> quote(input)
        is Number, is Boolean -> input.toString()
        is Map<*, *> -> input.entries.joinToString(",", "{", "}") { (key, item) ->
            "${quote(key.toString())}:${value(item)}"
        }
        is Iterable<*> -> input.joinToString(",", "[", "]") { value(it) }
        is Array<*> -> input.joinToString(",", "[", "]") { value(it) }
        else -> quote(input.toString())
    }

    fun pretty(input: Any?): String = prettyValue(input, "")

    private fun prettyValue(input: Any?, indent: String): String = when (input) {
        is Map<*, *> -> if (input.isEmpty()) "{}" else input.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, item) ->
            "$indent  ${quote(key.toString())}: ${prettyValue(item, "$indent  ")}"
        }
        is Iterable<*> -> {
            val items = input.toList()
            if (items.isEmpty()) "[]" else items.joinToString(",\n", "[\n", "\n$indent]") {
                "$indent  ${prettyValue(it, "$indent  ")}"
            }
        }
        is Array<*> -> prettyValue(input.asList(), indent)
        else -> value(input)
    }

    fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }

    fun write(file: File, content: Any?, pretty: Boolean = true) {
        file.parentFile?.mkdirs()
        file.writeText((if (pretty) pretty(content) else value(content)) + "\n", Charsets.UTF_8)
    }
}
