package dev.androidjtools.offline.download

import kotlinx.coroutines.flow.StateFlow

interface DownloadScheduler {
    fun enqueue(asset: OfflineAsset, replace: Boolean = false)
    fun cancel(assetId: String)
}

class OfflineDownloadCoordinator(
    private val store: OfflineDownloadStore,
    private val scheduler: DownloadScheduler,
    private val resolver: OfflineMediaResolver,
) {
    fun observe(assetId: String): StateFlow<OfflineDownloadRecord?> = store.observe(assetId)

    fun pinTrack(asset: OfflineAsset, trackId: String = asset.assetId) {
        pin(asset, "track:$trackId")
    }

    fun unpinTrack(assetId: String, trackId: String = assetId) {
        removePin(assetId, "track:$trackId")
    }

    /** Playlist membership is supplied by the playlist owner; this lane stores only stable asset IDs. */
    fun pinPlaylist(playlistId: String, assets: Collection<OfflineAsset>) {
        val owner = "playlist:$playlistId"
        val desired = assets.associateBy(OfflineAsset::assetId)
        store.all()
            .filter { owner in it.pinOwners && it.asset.assetId !in desired }
            .forEach { record -> removePin(record.asset.assetId, owner) }
        desired.values.forEach { asset -> pin(asset, owner) }
    }

    fun unpinPlaylist(playlistId: String) {
        val owner = "playlist:$playlistId"
        store.all().filter { owner in it.pinOwners }.forEach { removePin(it.asset.assetId, owner) }
    }

    fun cancel(assetId: String) {
        store.update(assetId) {
            it.copy(
                phase = OfflineDownloadPhase.CANCELLED,
                cancelRequested = true,
                error = OfflineDownloadError(DownloadFailureKind.CANCELLED, "download cancelled", retryable = true),
            )
        }
        scheduler.cancel(assetId)
    }

    fun retry(asset: OfflineAsset) {
        store.ensure(asset)
        store.update(asset.assetId) {
            it.copy(phase = OfflineDownloadPhase.QUEUED, cancelRequested = false, error = null)
        }
        scheduler.enqueue(asset, replace = true)
    }

    fun cachedMedia(assetId: String): OfflineCachedMedia? = resolver.resolve(assetId)

    private fun pin(asset: OfflineAsset, owner: String) {
        store.ensure(asset)
        val record = requireNotNull(store.update(asset.assetId) {
            it.copy(pinOwners = it.pinOwners + owner, cancelRequested = false)
        })
        if (record.phase != OfflineDownloadPhase.AVAILABLE || resolver.resolve(asset.assetId) == null) {
            store.update(asset.assetId) { it.copy(phase = OfflineDownloadPhase.QUEUED, error = null) }
            scheduler.enqueue(asset, replace = false)
        }
    }

    private fun removePin(assetId: String, owner: String) {
        store.update(assetId) { it.copy(pinOwners = it.pinOwners - owner) }
    }
}
