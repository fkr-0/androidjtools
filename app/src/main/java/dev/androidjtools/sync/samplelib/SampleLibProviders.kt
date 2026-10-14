package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.core.provider.PreparationProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.core.provider.SyncProvider
import dev.androidjtools.remote.samplelib.ServerCapabilities
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val SERVER_AUDIO_UNAVAILABLE_NOTICE =
    "This track is on your Sample Lib server. Server audio is not available yet, so it cannot be played here."

/** Recomputes the library projection off the main thread after every cache commit. */
class SampleLibProjectionState(
    cache: LibraryCache,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    capabilities: StateFlow<ServerCapabilities?> = MutableStateFlow(null),
    /** asset_file id -> lowercase sha256 of a verified local copy. A cold flow so the offline store loads off the main thread. */
    downloaded: Flow<Map<String, String>> = flowOf(emptyMap()),
    /** True while the server rejects the stored credential: streaming is withheld, verified local copies still play. */
    pairingRequired: Flow<Boolean> = flowOf(false),
) {
    val projection: StateFlow<LibraryProjection> = combine(cache.changes, capabilities, downloaded, pairingRequired) { _, caps, files, repair -> Triple(caps, files, repair) }
        .map { (caps, files, repair) ->
            try {
                val snap = cache.snapshot("asset", "interval", ASSET_FILE_ENTITY)
                val assetFiles = snap[ASSET_FILE_ENTITY].orEmpty()
                // Until the first hello of this process the capabilities are unknown (null): cached file rows
                // are then the evidence, so a cold start does not lock a synced library out of playback.
                val advertised = !repair && (serverAudioSupport(caps).advertised || (caps == null && assetFiles.isNotEmpty()))
                LibraryProjector.project(
                    snap["asset"].orEmpty(), snap["interval"].orEmpty(), assetFiles, advertised, files,
                    audioBlockedReason = PAIR_AGAIN_NOTICE.takeIf { repair },
                )
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

/** Server tracks first, then device tracks. No de-duplication yet; content-hash matching is a follow-up. */
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

/** The reason a server track cannot be played, or null (device media and unknown ids are the player's business). */
fun playbackRefusal(projection: LibraryProjection, trackId: String): String? {
    val track = projection.tracks.firstOrNull { it.id == trackId } ?: return null
    return if (track.playable) null else track.unplayableReason ?: SERVER_AUDIO_UNAVAILABLE_NOTICE
}

/** Plays through [delegate]; refuses tracks [refusalFor] gives a reason for and says why instead of failing silently. */
class ServerAwarePlayback(
    private val delegate: PlaybackProvider,
    private val refusalFor: (String) -> String?,
) : PlaybackProvider by delegate {
    private val notice = MutableStateFlow<String?>(null)
    override val playbackNotice: StateFlow<String?> = notice.asStateFlow()

    override fun play(trackId: String) {
        val refusal = refusalFor(trackId)
        if (refusal != null) {
            notice.value = refusal
            return
        }
        notice.value = null
        delegate.play(trackId)
    }
}
