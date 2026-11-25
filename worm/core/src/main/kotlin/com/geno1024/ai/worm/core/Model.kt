package com.geno1024.ai.worm.core

/** What kind of bytes a [MediaAsset] points at. */
enum class MediaKind(val folder: String) {
    IMAGE("images"),
    VIDEO("videos"),
}

/**
 * A media item discovered by a crawler. Crawlers only *describe* media;
 * downloading and placing it is the job of [MediaSink] / [MediaDownloader].
 *
 * [isCover] marks a first-frame / poster image that came from a video post.
 * It is saved as an image, never mislabelled as a playable video.
 */
data class MediaAsset(
    val url: String,
    val kind: MediaKind,
    val id: String? = null,
    val title: String? = null,
    val createdAt: Long? = null,
    val referer: String? = null,
    val extension: String? = null,
    val isCover: Boolean = false,
    val relativeDir: String = kind.folder,
    val suggestedName: String? = null,
    /** Tried in order only when the primary URL fails; the saved file keeps the primary identity. */
    val fallbackUrls: List<String> = emptyList(),
)

/** One collected record. Text is present now for API stability; media is collected first. */
sealed interface CollectedItem {
    val sourceUrl: String
    val title: String?
    val createdAt: Long?

    /** Stable identity used by incremental crawls to avoid replaying the overlap window. */
    fun identity(): String = sourceUrl

    data class Media(val asset: MediaAsset) : CollectedItem {
        override val sourceUrl: String get() = asset.url
        override val title: String? get() = asset.title
        override val createdAt: Long? get() = asset.createdAt
    }

    data class Text(
        override val sourceUrl: String,
        override val title: String?,
        val body: String,
        override val createdAt: Long? = null,
    ) : CollectedItem
}

/** A login state handed to crawlers; cookies are keyed by name only (domain is irrelevant to QQ/Zhihu APIs). */
data class CrawlSession(
    val cookies: Map<String, String>,
    val loginId: String? = null,
) {
    val cookieHeader: String get() = Cookies.toHeader(cookies)
}

/** Input to a crawl. [subject] is a QQ number, a Zhihu user token, etc., depending on the crawler. */
data class CrawlRequest(
    val subject: String,
    val includeText: Boolean = false,
    /** Epoch seconds; content older than this was already covered by a prior run. */
    val since: Long? = null,
    /** Identities seen by the prior run; skips duplicates inside the overlap window. */
    val seenIds: Set<String> = emptySet(),
)

data class SavedMedia(
    val asset: MediaAsset,
    val name: String,
    val size: Long,
    val existed: Boolean = false,
)

data class CrawlReport(
    val crawlerId: String,
    val subject: String,
    val items: List<CollectedItem>,
    val saved: List<SavedMedia>,
    val warnings: List<String> = emptyList(),
    /** Newest content timestamp seen, so the next run can show how fresh the target is. */
    val newestItemAt: Long? = null,
)

/**
 * One step of a crawl. Crawlers work in two phases: discover the full list
 * ([stage] describes the list, [total] may be unknown), then download the discovered
 * assets ([stage] = "媒体", [total] is the known asset count).
 */
data class CrawlProgress(
    val stage: String,
    val current: Int,
    val total: Int? = null,
    val detail: String = "",
)

interface CrawlListener {
    fun onProgress(progress: CrawlProgress) {}
    fun onWarning(message: String) {}

    /** The complete discovered list, before any bytes are downloaded. Checkpoint here. */
    fun onDiscovered(assets: List<MediaAsset>) {}

    /** One asset is settled (saved or failed); safe to drop from a durable queue. */
    fun onAssetDone(asset: MediaAsset) {}

    companion object {
        val NOOP: CrawlListener = object : CrawlListener {}
    }
}

/** Implemented by every crawler module; the Android app only knows this interface. */
interface Crawler {
    val id: String
    val displayName: String

    suspend fun collect(
        request: CrawlRequest,
        session: CrawlSession,
        sink: MediaSink,
        listener: CrawlListener = CrawlListener.NOOP,
    ): CrawlReport
}

class CrawlException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
