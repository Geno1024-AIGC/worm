package com.geno1024.ai.worm.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The durable tail of discovered assets that were not confirmed saved.
 *
 * A snapshot is written before the first byte of a batch, and each settled asset is
 * removed afterwards. A process that dies mid-batch therefore leaves exactly the
 * unfinished tail on disk. Writes go through a sibling `.tmp` and are renamed, so a
 * killed process can never leave a half-written JSON that would be replayed forever.
 *
 * The sink is stored as an opaque [SinkRef]: a directory path on the JVM, a SAF tree
 * URI on Android. Cookies are deliberately not stored — the caller rebuilds the
 * session from its own (already durable) login state when resuming.
 */
class ResumeQueue(private val file: File) {

    data class SinkRef(val kind: String, val root: String) {
        companion object {
            const val DIRECTORY = "directory"
            const val SAF = "saf"
        }
    }

    data class Batch(
        val crawlerId: String,
        val subject: String,
        val sink: SinkRef,
        val total: Int,
        val assets: List<MediaAsset>,
    )

    /** Returns the last good snapshot; a corrupt one is dropped instead of replayed forever. */
    fun load(): Batch? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(text)
            val sink = root.getJSONObject("sink")
            val assets = root.getJSONArray("assets")
            Batch(
                crawlerId = root.optString("crawlerId"),
                subject = root.optString("subject"),
                sink = SinkRef(sink.getString("kind"), sink.getString("root")),
                total = root.optInt("total", assets.length()),
                assets = (0 until assets.length()).map { assets.getJSONObject(it).toAsset() },
            )
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun save(batch: Batch) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("crawlerId", batch.crawlerId)
                put("subject", batch.subject)
                put("sink", JSONObject().apply {
                    put("kind", batch.sink.kind)
                    put("root", batch.sink.root)
                })
                put("total", batch.total)
                put("assets", JSONArray().apply { batch.assets.forEach { put(it.toJson()) } })
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    /** Drops one settled asset and checkpoints the remainder. */
    fun remove(asset: MediaAsset) {
        val batch = load() ?: return
        val remaining = batch.assets.filterNot { it.url == asset.url && it.kind == asset.kind }
        if (remaining.size == batch.assets.size) return
        if (remaining.isEmpty()) clear() else save(batch.copy(assets = remaining))
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private companion object {
        fun MediaAsset.toJson() = JSONObject().apply {
            put("url", url)
            put("kind", kind.name)
            id?.let { put("id", it) }
            title?.let { put("title", it) }
            createdAt?.let { put("createdAt", it) }
            referer?.let { put("referer", it) }
            extension?.let { put("extension", it) }
            put("isCover", isCover)
            put("relativeDir", relativeDir)
            suggestedName?.let { put("suggestedName", it) }
        }

        fun JSONObject.toAsset() = MediaAsset(
            url = getString("url"),
            kind = MediaKind.valueOf(getString("kind")),
            id = optStringOrNull("id"),
            title = optStringOrNull("title"),
            createdAt = optLongOrNull("createdAt"),
            referer = optStringOrNull("referer"),
            extension = optStringOrNull("extension"),
            isCover = optBoolean("isCover", false),
            relativeDir = optString("relativeDir").takeIf { it.isNotBlank() }
                ?: MediaKind.valueOf(getString("kind")).folder,
            suggestedName = optStringOrNull("suggestedName"),
        )

        fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        fun JSONObject.optLongOrNull(key: String): Long? =
            if (!has(key) || isNull(key)) null else getLong(key)
    }
}
