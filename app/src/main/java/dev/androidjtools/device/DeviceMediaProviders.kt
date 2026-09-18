package dev.androidjtools.device

import android.content.Context
import android.provider.MediaStore
import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.MutationReceipt
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.PendingMutation
import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.AnalysisProvider
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.MutationJournalProvider
import dev.androidjtools.core.provider.PlaylistProvider
import dev.androidjtools.core.provider.PreparationProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.core.provider.SyncProvider
import dev.androidjtools.playback.Media3PlaybackProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DEVICE_TRACK_PREFIX = "device-media:"
private const val DEVICE_MEDIA_URI_PREFIX = "content://media/external/audio/media"

internal fun deviceTrackId(mediaStoreId: Long): String = "$DEVICE_TRACK_PREFIX$mediaStoreId"

internal fun mediaStoreIdFromTrackId(trackId: String): Long? =
    trackId.removePrefix(DEVICE_TRACK_PREFIX)
        .takeIf { trackId.startsWith(DEVICE_TRACK_PREFIX) }
        ?.toLongOrNull()

internal fun mediaUriForDeviceTrack(trackId: String): String? =
    mediaStoreIdFromTrackId(trackId)?.let { "$DEVICE_MEDIA_URI_PREFIX/$it" }

class DeviceMediaProviderBundle private constructor(
    val providers: AppProviders,
    private val library: DeviceMediaLibraryProvider,
) {
    fun refresh(permissionGranted: Boolean) {
        library.refresh(permissionGranted)
    }

    fun close() {
        providers.playback.release()
        library.close()
    }

    companion object {
        fun create(context: Context): DeviceMediaProviderBundle {
            val runtime = DeviceRuntimeProvider()
            val library = DeviceMediaLibraryProvider(context.applicationContext, runtime)
            val playback = Media3PlaybackProvider.create(
                context = context.applicationContext,
                mediaUriResolver = ::mediaUriForDeviceTrack,
            )
            val providers = AppProviders(
                runtime = runtime,
                library = library,
                playlists = EmptyPlaylistProvider(),
                preparation = EmptyPreparationProvider(),
                playback = playback,
                downloads = DeviceLocalDownloadProvider(),
                journal = EmptyMutationJournalProvider(),
                sync = DeviceSyncProvider(),
                analysis = EmptyAnalysisProvider(),
            )
            return DeviceMediaProviderBundle(providers, library)
        }
    }
}

private class DeviceRuntimeProvider : AppRuntimeProvider {
    private val mutableState = MutableStateFlow<ProviderUiState>(ProviderUiState.Loading)
    override val uiState: StateFlow<ProviderUiState> = mutableState.asStateFlow()

    fun update(state: ProviderUiState) {
        mutableState.value = state
    }
}

private class DeviceMediaLibraryProvider(
    private val context: Context,
    private val runtime: DeviceRuntimeProvider,
) : LibraryProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableTracks = MutableStateFlow<List<Track>>(emptyList())
    override val tracks: StateFlow<List<Track>> = mutableTracks.asStateFlow()

    override fun track(id: String): Track? = tracks.value.firstOrNull { it.id == id }

    fun refresh(permissionGranted: Boolean) {
        if (!permissionGranted) {
            mutableTracks.value = emptyList()
            runtime.update(ProviderUiState.Error("Audio-library permission is required to browse and play device music."))
            return
        }
        runtime.update(ProviderUiState.Loading)
        scope.launch {
            runCatching(::queryTracks)
                .onSuccess { discovered ->
                    mutableTracks.value = discovered
                    runtime.update(if (discovered.isEmpty()) ProviderUiState.Empty else ProviderUiState.Ready)
                }
                .onFailure { failure ->
                    mutableTracks.value = emptyList()
                    runtime.update(ProviderUiState.Error(failure.message ?: "Unable to read the device audio library."))
                }
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun queryTracks(): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val result = mutableListOf<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val durationMs = cursor.getLong(durationColumn).coerceAtLeast(0L)
                if (durationMs == 0L) continue
                val title = cursor.getString(titleColumn).orEmpty().ifBlank { "Track $mediaId" }
                val rawArtist = cursor.getString(artistColumn).orEmpty()
                val artist = rawArtist.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING } ?: "Unknown artist"
                val rawAlbum = cursor.getString(albumColumn)
                val album = rawAlbum?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                result += Track(
                    id = deviceTrackId(mediaId),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    offlineAvailable = true,
                )
            }
        }
        return result
    }
}

private class EmptyPlaylistProvider : PlaylistProvider {
    override val playlists: StateFlow<List<Playlist>> = MutableStateFlow(emptyList())
    override fun playlist(id: String): Playlist? = null
}

private class EmptyPreparationProvider : PreparationProvider {
    private val cues = mutableMapOf<String, StateFlow<List<Cue>>>()
    private val loops = mutableMapOf<String, StateFlow<List<Loop>>>()
    private val grids = mutableMapOf<String, StateFlow<BeatGrid?>>()

    override fun cues(trackId: String): StateFlow<List<Cue>> =
        cues.getOrPut(trackId) { MutableStateFlow(emptyList()) }

    override fun loops(trackId: String): StateFlow<List<Loop>> =
        loops.getOrPut(trackId) { MutableStateFlow(emptyList()) }

    override fun beatGrid(trackId: String): StateFlow<BeatGrid?> =
        grids.getOrPut(trackId) { MutableStateFlow(null) }
}

private class DeviceLocalDownloadProvider : DownloadProvider {
    private val states = mutableMapOf<String, StateFlow<TrackDownloadState>>()
    override val cacheSummary: StateFlow<OfflineCacheSummary> = MutableStateFlow(OfflineCacheSummary())

    override fun state(trackId: String): StateFlow<TrackDownloadState> =
        states.getOrPut(trackId) {
            MutableStateFlow(
                TrackDownloadState(
                    trackId = trackId,
                    status = DownloadStatus.AVAILABLE,
                    progress = 1.0,
                ),
            )
        }

    override fun pinTrack(trackId: String) = Unit
    override fun unpinTrack(trackId: String) = Unit
    override fun pinPlaylist(playlistId: String) = Unit
    override fun unpinPlaylist(playlistId: String) = Unit
    override fun retry(trackId: String) = Unit
    override fun cancel(trackId: String) = Unit
    override fun pruneUnpinned() = Unit
}

private class EmptyMutationJournalProvider : MutationJournalProvider {
    override val pending: StateFlow<List<PendingMutation>> = MutableStateFlow(emptyList())
    override val recentReceipts: StateFlow<List<MutationReceipt>> = MutableStateFlow(emptyList())
}

private class DeviceSyncProvider : SyncProvider {
    override val state: StateFlow<SyncState> = MutableStateFlow(SyncState.UNCONFIGURED)
    override val pendingMutationCount: StateFlow<Int> = MutableStateFlow(0)
}

private class EmptyAnalysisProvider : AnalysisProvider {
    override val capabilities: StateFlow<List<AnalysisCapability>> = MutableStateFlow(emptyList())
    private val suggestions = mutableMapOf<String, StateFlow<List<AnalysisSuggestion>>>()
    override fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>> =
        suggestions.getOrPut(trackId) { MutableStateFlow(emptyList()) }

    override fun accept(suggestionId: String) = Unit
    override fun reject(suggestionId: String) = Unit
    override fun refresh(trackId: String) = Unit
}
