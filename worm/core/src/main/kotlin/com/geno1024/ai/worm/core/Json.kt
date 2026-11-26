package com.geno1024.ai.worm.core

import org.json.JSONArray
import org.json.JSONObject

/** JSONP wrappers used by both Qzone and Zhihu endpoints. */
object Jsonp {
    private val callbackName = Regex("""[A-Za-z_$][A-Za-z0-9_$.]*""")

    fun strip(body: String): String {
        val text = body.trim().removePrefix("\uFEFF")
        val open = text.indexOf('(')
        if (open < 0) return text
        // Plain JSON can contain '(' and ')' inside string values — Weibo texts and
        // URLs do. Only unwrap a real JSONP call: a bare callback name before the
        // first '(' and a ')' that closes the whole body. Otherwise the "wrapper"
        // would cut a valid response into an unparseable fragment.
        val callback = text.substring(0, open).trim()
        if (callback.isNotEmpty() && !callback.matches(callbackName)) return text
        val close = text.lastIndexOf(')')
        if (close <= open) return text
        val tail = text.substring(close + 1).trim()
        if (tail.isNotEmpty() && tail != ";") return text
        return text.substring(open + 1, close).trim()
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
    val truncated = isTruncatedJson(text)
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

/**
 * A body cut off mid-value can still end in `}` or `)` (a nested container closing),
 * so counting containers outside strings is more reliable than checking edge bytes.
 */
private fun isTruncatedJson(text: String): Boolean {
    if ((text.startsWith("{") && !text.endsWith("}")) ||
        (text.startsWith("[") && !text.endsWith("]"))) return true
    var braces = 0
    var brackets = 0
    var parens = 0
    var inString = false
    var escaped = false
    for (ch in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> braces++
            '}' -> braces--
            '[' -> brackets++
            ']' -> brackets--
            '(' -> parens++
            ')' -> parens--
        }
    }
    return inString || braces > 0 || brackets > 0 || parens > 0
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
