package dev.androidjtools.offline.download

/** Canonical media identity stays separate from local cache paths. */
data class OfflineAsset(
    val assetId: String,
    val resourceUri: String,
    val contentSha256: String,
    val contentLength: Long? = null,
    val supportsRange: Boolean = true,
    val advertisedEtag: String? = null,
    val advertisedLastModified: String? = null,
) {
    init {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        require(resourceUri.isNotBlank()) { "resourceUri must not be blank" }
        require(SHA256.matches(contentSha256)) { "contentSha256 must be 64 hexadecimal characters" }
        require(contentLength == null || contentLength >= 0L) { "contentLength must be non-negative" }
    }

    val normalizedSha256: String = contentSha256.lowercase()

    private companion object {
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

enum class OfflineDownloadPhase {
    NOT_CACHED,
    QUEUED,
    DOWNLOADING,
    AVAILABLE,
    FAILED,
    CANCELLED,
    CORRUPT,
}

enum class DownloadFailureKind {
    NETWORK,
    HTTP,
    INTEGRITY,
    QUOTA,
    PROTOCOL,
    CANCELLED,
}

data class OfflineDownloadError(
    val kind: DownloadFailureKind,
    val message: String,
    val retryable: Boolean,
)

data class OfflineDownloadRecord(
    val asset: OfflineAsset,
    val phase: OfflineDownloadPhase = OfflineDownloadPhase.NOT_CACHED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = asset.contentLength,
    val currentEtag: String? = asset.advertisedEtag,
    val currentLastModified: String? = asset.advertisedLastModified,
    val pinOwners: Set<String> = emptySet(),
    val cancelRequested: Boolean = false,
    val lastAccessEpochMs: Long = 0L,
    val error: OfflineDownloadError? = null,
) {
    val pinned: Boolean get() = pinOwners.isNotEmpty()
    val progress: Double
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            (bytesDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        } ?: if (phase == OfflineDownloadPhase.AVAILABLE) 1.0 else 0.0
}

sealed interface DownloadAttemptResult {
    data object Success : DownloadAttemptResult
    data class Retry(val error: OfflineDownloadError) : DownloadAttemptResult
    data class Failed(val error: OfflineDownloadError) : DownloadAttemptResult
    data object Cancelled : DownloadAttemptResult
}

data class OfflineCachedMedia(
    val assetId: String,
    val uri: String,
    val contentSha256: String,
    val sizeBytes: Long,
)
