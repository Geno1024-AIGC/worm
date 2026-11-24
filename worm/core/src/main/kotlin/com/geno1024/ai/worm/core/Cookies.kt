package com.geno1024.ai.worm.core

/** Cookie helpers shared by CLI login imports and Android WebView capture. */
object Cookies {
    /** Dedupe by name, last value wins — the shape both target sites expect in a Cookie header. */
    fun toHeader(cookies: Map<String, String>): String = cookies.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .associate { it.key to it.value }
        .entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun fromHeader(header: String): Map<String, String> = buildMap {
        for (part in header.split(';')) {
            val (name, value) = part.trim().let { it.substringBefore('=') to it.substringAfter('=', "") }
            if (name.isNotBlank()) put(name, value.trim())
        }
    }

    /** Netscape `cookies.txt`, including the `#HttpOnly_` prefix used by browsers. */
    fun fromNetscape(text: String): Map<String, String> = buildMap {
        for (line in text.lineSequence()) {
            val fields = line.removePrefix("#HttpOnly_").split('\t')
            if (fields.size >= 7 && !fields[0].startsWith("#")) {
                val name = fields[5].trim()
                val value = fields[6].trim()
                if (name.isNotBlank()) put(name, value)
            }
        }
    }

    fun hasLogin(cookies: Map<String, String>, vararg required: String): Boolean =
        required.any { !cookies[it].isNullOrBlank() }
}
