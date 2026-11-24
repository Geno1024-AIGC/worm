package com.geno1024.ai.worm.core

import org.json.JSONArray
import org.json.JSONObject

/** JSONP wrappers used by both Qzone and Zhihu endpoints. */
object Jsonp {
    fun strip(body: String): String {
        val text = body.trim().removePrefix("\uFEFF")
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        return if (open >= 0 && close > open) text.substring(open + 1, close).trim() else text
    }

    fun objectOf(body: String): JSONObject = JSONObject(strip(body))

    fun arrayOf(body: String): JSONArray = JSONArray(strip(body))
}

/** Tolerant accessors: these APIs rename fields across versions and embed numbers as strings. */
fun JSONObject.optStringOf(vararg names: String): String? {
    for (name in names) {
        val value = opt(name) ?: continue
        val text = value.toString().trim()
        if (text.isNotBlank() && text != "null" && text != "0") return text
    }
    return null
}

fun JSONObject.optLongOf(vararg names: String): Long? {
    for (name in names) {
        val value = opt(name) ?: continue
        val number = value.toString().trim().toLongOrNull()
        if (number != null && number > 0) return number
    }
    return null
}

fun JSONObject.optObjectOf(vararg names: String): JSONObject? {
    for (name in names) {
        val value = opt(name)
        if (value is JSONObject) return value
        if (value is String) runCatching { return JSONObject(value) }
    }
    return null
}

fun JSONObject.optArrayOf(vararg names: String): JSONArray? {
    for (name in names) {
        val value = opt(name)
        if (value is JSONArray) return value
        if (value is String) runCatching { return JSONArray(value) }
    }
    return null
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }
