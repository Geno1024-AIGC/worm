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

    /** `null` when the body is not JSON/JSONP at all — never leaks org.json's raw message. */
    fun objectOrNull(body: String): JSONObject? = runCatching { objectOf(body) }.getOrNull()
}

/**
 * A short, user-facing reason a response is not the JSON/JSONP the endpoint should
 * return. QQ answers captcha/risk pages as SVG or HTML with HTTP 200, which otherwise
 * surfaces as a cryptic "illegal character at 24" from the JSON parser.
 */
fun responseProblem(body: String): String {
    val text = body.trim().removePrefix("\uFEFF")
    val head = text.take(160)
    val snippet = head.take(120).replace('\n', ' ').replace('\r', ' ')
    val lower = head.lowercase()
    val truncated = (text.startsWith("{") && !text.endsWith("}")) ||
        (text.startsWith("[") && !text.endsWith("]"))
    return when {
        head.isEmpty() -> "响应为空（可能触发登录或风控）"
        lower.startsWith("<svg") || lower.startsWith("<?xml") ->
            "收到 SVG 页面（$snippet），通常是验证码或风控拦截，请重新登录或稍后再试"
        lower.startsWith("<!doctype html") || lower.startsWith("<html") ->
            "收到 HTML 页面（$snippet），通常是登录跳转或风控拦截，请重新登录"
        truncated -> "响应被截断（$snippet），可能连接中断或接口提前关闭，请重试"
        else -> "响应不是合法 JSON（${snippet.take(40)}）"
    }
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
