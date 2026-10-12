package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.core.provider.PreparationProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.core.provider.SyncProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val SERVER_AUDIO_UNAVAILABLE_NOTICE =
    "This track is on your Sample Lib server. Server audio is not available yet, so it cannot be played here."

/** Recomputes the library projection off the main thread after every cache commit. */
class SampleLibProjectionState(
    cache: LibraryCache,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val projection: StateFlow<LibraryProjection> = cache.changes
        .map {
            try {
                val snap = cache.snapshot("asset", "interval")
                LibraryProjector.project(snap["asset"].orEmpty(), snap["interval"].orEmpty())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null // keep previous value
            }
        }
        .filterNotNull()
        .flowOn(dispatcher)
        .stateIn(scope, SharingStarted.Eagerly, LibraryProjection.EMPTY)

    val tracks: StateFlow<List<Track>> = projection
        .map { it.tracks }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}

class SampleLibLibraryProvider(private val state: SampleLibProjectionState) : LibraryProvider {
    override val tracks: StateFlow<List<Track>> = state.tracks
    override fun track(id: String): Track? = state.projection.value.tracks.firstOrNull { it.id == id }
}

class SampleLibPreparationProvider(
    private val state: SampleLibProjectionState,
    private val scope: CoroutineScope,
) : PreparationProvider {
    private val cues = ConcurrentHashMap<String, StateFlow<List<Cue>>>()
    private val loops = ConcurrentHashMap<String, StateFlow<List<Loop>>>()
    private val noBeatGrid = MutableStateFlow<BeatGrid?>(null).asStateFlow()

    override fun cues(trackId: String): StateFlow<List<Cue>> = cues.getOrPut(trackId) {
        state.projection.map { it.cues[trackId].orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, state.projection.value.cues[trackId].orEmpty())
    }

    override fun loops(trackId: String): StateFlow<List<Loop>> = loops.getOrPut(trackId) {
        state.projection.map { it.loops[trackId].orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, state.projection.value.loops[trackId].orEmpty())
    }

    /** The server only sends an opaque beatgrid reference in S1, so no grid is shown. */
    override fun beatGrid(trackId: String): StateFlow<BeatGrid?> = noBeatGrid
}

class SampleLibSyncProvider(manager: SampleLibSessionManager) : SyncProvider {
    override val state: StateFlow<SyncState> = manager.state
    override val pendingMutationCount: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
}

/** Server tracks first, then device tracks. No de-duplication until S2 brings content-hash matching. */
class CompositeLibraryProvider(
    private val server: LibraryProvider,
    private val device: LibraryProvider,
    scope: CoroutineScope,
) : LibraryProvider {
    override val tracks: StateFlow<List<Track>> = combine(server.tracks, device.tracks) { s, d -> s + d }
        .stateIn(scope, SharingStarted.Eagerly, server.tracks.value + device.tracks.value)

    override fun track(id: String): Track? = server.track(id) ?: device.track(id)
}

/** A device-side failure (for example a denied audio permission) must not hide a synced server library. */
class CompositeRuntimeProvider(
    device: AppRuntimeProvider,
    server: LibraryProvider,
    scope: CoroutineScope,
) : AppRuntimeProvider {
    override val uiState: StateFlow<ProviderUiState> = combine(device.uiState, server.tracks, ::merge)
        .stateIn(scope, SharingStarted.Eagerly, merge(device.uiState.value, server.tracks.value))

    private companion object {
        fun merge(device: ProviderUiState, serverTracks: List<Track>): ProviderUiState =
            if (serverTracks.isNotEmpty() &&
                (device is ProviderUiState.Error || device is ProviderUiState.Empty || device is ProviderUiState.Loading)
            ) {
                ProviderUiState.Ready
            } else {
                device
            }
    }
}

/** Plays device tracks through [delegate]; refuses server tracks and says why instead of failing silently. */
class ServerAwarePlayback(
    private val delegate: PlaybackProvider,
    private val isServerTrack: (String) -> Boolean,
) : PlaybackProvider by delegate {
    private val notice = MutableStateFlow<String?>(null)
    override val playbackNotice: StateFlow<String?> = notice.asStateFlow()

    override fun play(trackId: String) {
        if (isServerTrack(trackId)) {
            notice.value = SERVER_AUDIO_UNAVAILABLE_NOTICE
            return
        }
        notice.value = null
        delegate.play(trackId)
    }
}

/** Server tracks report NOT_DOWNLOADED and pin/retry/cancel are no-ops; device tracks delegate. */
class ServerAwareDownloads(
    private val delegate: DownloadProvider,
    private val isServerTrack: (String) -> Boolean,
) : DownloadProvider by delegate {
    override fun state(trackId: String): StateFlow<TrackDownloadState> {
        if (isServerTrack(trackId)) {
            return MutableStateFlow(TrackDownloadState(trackId, DownloadStatus.NOT_DOWNLOADED)).asStateFlow()
        }
        return delegate.state(trackId)
    }

    override fun pinTrack(trackId: String) {
        if (!isServerTrack(trackId)) delegate.pinTrack(trackId)
    }

    override fun unpinTrack(trackId: String) {
        if (!isServerTrack(trackId)) delegate.unpinTrack(trackId)
    }

    override fun retry(trackId: String) {
        if (!isServerTrack(trackId)) delegate.retry(trackId)
    }

    override fun cancel(trackId: String) {
        if (!isServerTrack(trackId)) delegate.cancel(trackId)
    }
}
