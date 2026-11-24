package com.geno1024.ai.worm.core

import java.io.File

/**
 * Replays the unfinished tail of a crawl after a process restart.
 *
 * The caller supplies a freshly built [CrawlSession] (cookies are never persisted) and a
 * [MediaSink] rebuilt from the batch's opaque [ResumeQueue.SinkRef]. Progress continues
 * from the assets already settled in the previous run, and every settled asset is
 * checkpointed immediately, so another crash only ever replays the remaining tail.
 */
class ResumeRunner(
    private val queue: ResumeQueue,
    private val http: HttpPortal,
    private val session: CrawlSession,
    private val sink: MediaSink,
    private val cacheDir: File,
    private val listener: CrawlListener = CrawlListener.NOOP,
    private val delayMs: Long = 300L,
) {
    fun hasPending(): Boolean = queue.load()?.assets?.isNotEmpty() == true

    fun drain(): List<SavedMedia> {
        val batch = queue.load() ?: return emptyList()
        if (batch.assets.isEmpty()) {
            queue.clear()
            return emptyList()
        }
        val already = (batch.total - batch.assets.size).coerceAtLeast(0)
        val saved = MediaDownloader(http, cacheDir, delayMs).downloadAll(
            assets = batch.assets,
            session = session,
            sink = sink,
            onProgress = { current, _, asset ->
                listener.onProgress(CrawlProgress("续传", already + current, batch.total, asset.url))
            },
            onFailure = { asset, error ->
                listener.onWarning("续传失败 ${asset.url}: ${error.message}")
            },
            onDone = { asset -> queue.remove(asset) },
        )
        queue.clear()
        return saved
    }
}
