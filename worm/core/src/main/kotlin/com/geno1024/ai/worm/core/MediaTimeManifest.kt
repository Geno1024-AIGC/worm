package com.geno1024.ai.worm.core

import org.json.JSONObject
import java.io.File

/**
 * Publish times for saved files whose filesystem mtime cannot be set (notably some
 * SAF providers). Keys are paths relative to the storage root, the same ids the
 * browser uses. Best-effort: a corrupt or unwritable manifest never fails a crawl.
 */
class MediaTimeManifest(private val file: File) {
    fun get(path: String): Long? = read()[key(path)]

    /** Pass [createdAt] = null to forget a stale entry (e.g. a forced re-download). */
    fun record(path: String, createdAt: Long?) {
        val current = read()
        if (createdAt != null && createdAt > 0) current[key(path)] = createdAt
        else current.remove(key(path))
        write(current)
    }

    private fun read(): MutableMap<String, Long> {
        if (!file.isFile) return LinkedHashMap()
        val result = LinkedHashMap<String, Long>()
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            for (name in root.keys()) {
                val value = root.optLong(name, -1)
                if (value > 0) result[name] = value
            }
        }
        return result
    }

    private fun write(times: Map<String, Long>) = runCatching {
        file.parentFile?.mkdirs()
        val root = JSONObject()
        times.forEach { (path, at) -> root.put(path, at) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun key(path: String): String = path.trim().trim('/').replace('\\', '/')
}
