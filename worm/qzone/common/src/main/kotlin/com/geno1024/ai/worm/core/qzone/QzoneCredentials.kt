package com.geno1024.ai.worm.core.qzone

import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.JsonOutput
import org.json.JSONObject
import java.io.File

data class SavedQzoneCredentials(
    val loginUin: String?,
    val savedAt: String,
    val cookies: Map<String, String>,
)

/** Shared by CLI and (optionally) a desktop helper; Android uses app-private storage instead. */
fun defaultQzoneCredentialFile(): File {
    val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")
    return File(base, ".config/worm/qzone/credentials.json")
}

fun saveQzoneCredentials(file: File, session: CrawlSession, loginUin: String?) {
    val payload = mapOf(
        "loginUin" to loginUin,
        "savedAt" to java.time.Instant.now().toString(),
        "cookies" to session.cookies,
    )
    file.parentFile?.mkdirs()
    file.writeText(JsonOutput.pretty(payload) + "\n", Charsets.UTF_8)
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}

fun loadQzoneCredentials(file: File): SavedQzoneCredentials? {
    if (!file.isFile) return null
    val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
    val cookies = root.optJSONObject("cookies")?.let { jar ->
        jar.keySet().associateWith { key -> jar.opt(key).toString() }
    }.orEmpty()
    if (cookies.isEmpty()) return null
    return SavedQzoneCredentials(
        loginUin = root.optString("loginUin").takeIf { it.isNotBlank() },
        savedAt = root.optString("savedAt"),
        cookies = cookies,
    )
}
