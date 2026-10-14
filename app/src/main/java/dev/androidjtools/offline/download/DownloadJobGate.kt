package dev.androidjtools.offline.download

internal enum class DownloadJobGate { RUN, STALE, UNAUTHORIZED }

/**
 * Decides whether a queued download job may touch the network. A record exists only while something pinned or
 * retried the download, so a missing record means the cache was cleared (Forget or a server switch) after the job
 * was queued: the engine's own ensure() must not bring it back.
 */
internal fun downloadJobGate(store: OfflineDownloadStore, assetId: String, hasMediaAuth: Boolean): DownloadJobGate = when {
    store.get(assetId) == null -> DownloadJobGate.STALE
    !hasMediaAuth -> DownloadJobGate.UNAUTHORIZED
    else -> DownloadJobGate.RUN
}
