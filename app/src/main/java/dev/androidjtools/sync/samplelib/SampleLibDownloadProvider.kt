package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.offline.download.DownloadFailureKind
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadCoordinator
import dev.androidjtools.offline.download.OfflineDownloadError
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadRecord
import dev.androidjtools.offline.download.OfflineDownloadStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TRACK_OWNER_PREFIX = "track:"

/** asset_file id to the lowercase sha256 of every complete local copy. */
fun downloadedFiles(records: Flow<Map<String, OfflineDownloadRecord>>): Flow<Map<String, String>> =
    records
        .map { all ->
            all.values.filter { it.phase == OfflineDownloadPhase.AVAILABLE }
                .associate { it.asset.assetId to it.asset.normalizedSha256 }
        }
        .distinctUntilChanged()

/**
 * Pins, state, retry/cancel and the cache summary for server tracks, on top of the resumable engine. Device
 * tracks and anything without a server audio file go to [delegate]. Every mutation runs on [dispatcher] (a
 * single background thread in production) because the store fsyncs its state files.
 */
class SampleLibDownloadProvider(
    private val delegate: DownloadProvider,
    private val projection: StateFlow<LibraryProjection>,
    private val rangeSupported: () -> Boolean,
    private val coordinator: OfflineDownloadCoordinator,
    private val store: OfflineDownloadStore,
    private val storage: OfflineCacheStorage,
    private val scope: CoroutineScope,
    private val maxBytes: Long,
    private val freeSpaceBytes: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val reserveBytes: Long = 256L * 1024L * 1024L,
) : DownloadProvider {
    private val states = ConcurrentHashMap<String, StateFlow<TrackDownloadState>>()

    override val cacheSummary: StateFlow<OfflineCacheSummary> = store.allRecords
        .map(::summarize)
        .flowOn(dispatcher)
        .stateIn(scope, SharingStarted.Eagerly, OfflineCacheSummary(maxBytes = maxBytes))

    private fun isServerTrack(trackId: String) = projection.value.hasTrack(trackId)
    private fun downloadOf(trackId: String): AssetFileDescriptor? = projection.value.audio[trackId]?.download

    override fun state(trackId: String): StateFlow<TrackDownloadState> {
        if (!isServerTrack(trackId)) return delegate.state(trackId)
        return states.getOrPut(trackId) {
            val fileId = projection.map { it.audio[trackId]?.download }.distinctUntilChanged()
            combine(fileId, store.allRecords) { descriptor, records -> toState(trackId, descriptor, records) }
                .stateIn(scope, SharingStarted.Eagerly, toState(trackId, downloadOf(trackId), store.allRecords.value))
        }
    }

    override fun pinTrack(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.pinTrack(trackId)
        val descriptor = downloadOf(trackId) ?: return
        val asset = offlineAsset(descriptor)
        scope.launch(dispatcher) {
            if (hasRoomFor(descriptor, asset)) coordinator.pinTrack(asset, trackId)
        }
    }

    /** Refuses visibly (FAILED/QUOTA) when the device cannot hold the file plus the reserve. A complete copy needs no room. */
    private fun hasRoomFor(descriptor: AssetFileDescriptor, asset: OfflineAsset): Boolean {
        val complete = store.get(asset.assetId)?.let {
            it.phase == OfflineDownloadPhase.AVAILABLE && it.asset.normalizedSha256 == asset.normalizedSha256
        } == true
        if (complete || freeSpaceBytes() >= descriptor.sizeBytes + reserveBytes) return true
        store.ensure(asset)
        store.update(asset.assetId) {
            it.copy(
                phase = OfflineDownloadPhase.FAILED,
                error = OfflineDownloadError(DownloadFailureKind.QUOTA, "Not enough free space on this device.", retryable = true),
            )
        }
        return false
    }

    override fun unpinTrack(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.unpinTrack(trackId)
        val descriptor = downloadOf(trackId) ?: return
        scope.launch(dispatcher) {
            coordinator.unpinTrack(descriptor.id, trackId)
            val record = store.get(descriptor.id) ?: return@launch
            when {
                record.pinned -> Unit // another owner still needs it
                record.phase == OfflineDownloadPhase.QUEUED || record.phase == OfflineDownloadPhase.DOWNLOADING ->
                    // Never delete underneath a running worker: stop it and let prune remove the partial later.
                    coordinator.cancel(descriptor.id)
                else -> store.evictIfEligible(descriptor.id) { storage.evict(descriptor.id) }
            }
        }
    }

    // Server playlists are not synced yet (S3); nothing to pin.
    override fun pinPlaylist(playlistId: String) = delegate.pinPlaylist(playlistId)
    override fun unpinPlaylist(playlistId: String) = delegate.unpinPlaylist(playlistId)

    override fun retry(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.retry(trackId)
        val descriptor = downloadOf(trackId) ?: return
        val asset = offlineAsset(descriptor)
        scope.launch(dispatcher) {
            if (hasRoomFor(descriptor, asset)) coordinator.retry(asset)
        }
    }

    override fun cancel(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.cancel(trackId)
        val descriptor = downloadOf(trackId) ?: return
        scope.launch(dispatcher) { coordinator.cancel(descriptor.id) }
    }

    override fun pruneUnpinned() {
        delegate.pruneUnpinned()
        scope.launch(dispatcher) {
            dropPinsOfVanishedTracks()
            store.all().filter { !it.pinned }.forEach { record ->
                store.evictIfEligible(record.asset.assetId) { storage.evict(record.asset.assetId) }
            }
        }
    }

    /**
     * A track the server deleted, or whose file it re-issued under a new id, can no longer be unpinned from any
     * screen. On an explicit prune its pins are dropped so the bytes can be reclaimed. An empty library (a cache
     * reset or a cold start) proves nothing, so then nothing is dropped.
     */
    private fun dropPinsOfVanishedTracks() {
        val library = projection.value
        if (library.tracks.isEmpty()) return
        store.all().forEach { record ->
            val stale = record.pinOwners.filter { owner ->
                owner.startsWith(TRACK_OWNER_PREFIX) &&
                    library.audio[owner.removePrefix(TRACK_OWNER_PREFIX)]?.download?.id != record.asset.assetId
            }
            if (stale.isEmpty()) return@forEach
            store.update(record.asset.assetId) { it.copy(pinOwners = it.pinOwners - stale.toSet()) }
            val after = store.get(record.asset.assetId)
            if (after != null && !after.pinned &&
                (after.phase == OfflineDownloadPhase.QUEUED || after.phase == OfflineDownloadPhase.DOWNLOADING)
            ) {
                coordinator.cancel(record.asset.assetId)
            }
        }
    }

    private fun offlineAsset(d: AssetFileDescriptor) = OfflineAsset(
        assetId = d.id,
        resourceUri = "${d.role}://${d.id}",
        contentSha256 = d.sha256,
        contentLength = d.sizeBytes,
        supportsRange = rangeSupported(),
        advertisedEtag = d.etag,
    )

    private fun toState(trackId: String, descriptor: AssetFileDescriptor?, records: Map<String, OfflineDownloadRecord>): TrackDownloadState {
        val record = descriptor?.let { records[it.id] }
        // A record made for another revision of the file says nothing about the current one.
        if (record == null || record.asset.normalizedSha256 != descriptor.sha256) {
            return TrackDownloadState(trackId, DownloadStatus.NOT_DOWNLOADED)
        }
        val status = when (record.phase) {
            OfflineDownloadPhase.NOT_CACHED -> DownloadStatus.NOT_DOWNLOADED
            OfflineDownloadPhase.QUEUED -> DownloadStatus.QUEUED
            OfflineDownloadPhase.DOWNLOADING -> DownloadStatus.DOWNLOADING
            OfflineDownloadPhase.AVAILABLE -> DownloadStatus.AVAILABLE
            OfflineDownloadPhase.FAILED -> DownloadStatus.FAILED
            OfflineDownloadPhase.CANCELLED -> DownloadStatus.CANCELLED
            OfflineDownloadPhase.CORRUPT -> DownloadStatus.CORRUPT
        }
        return TrackDownloadState(trackId, status, record.progress, record.bytesDownloaded, record.totalBytes, record.error?.message)
    }

    private fun summarize(records: Map<String, OfflineDownloadRecord>): OfflineCacheSummary {
        val all = records.values
        return OfflineCacheSummary(
            usedBytes = storage.usedBytes(),
            maxBytes = maxBytes,
            evictableBytes = all.filter { !it.pinned && it.phase == OfflineDownloadPhase.AVAILABLE }.sumOf { it.bytesDownloaded },
            pinnedTrackIds = all.flatMap { it.pinOwners }
                .filter { it.startsWith(TRACK_OWNER_PREFIX) }
                .mapTo(HashSet()) { it.removePrefix(TRACK_OWNER_PREFIX) },
        )
    }
}
