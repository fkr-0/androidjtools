package dev.androidjtools.core.provider

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.MutationReceipt
import dev.androidjtools.core.model.PendingMutation
import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import kotlinx.coroutines.flow.StateFlow

sealed interface ProviderUiState {
    data object Loading : ProviderUiState
    data object Ready : ProviderUiState
    data object Empty : ProviderUiState
    data class Error(val message: String) : ProviderUiState
    data object Offline : ProviderUiState
    data class Conflict(val detail: String) : ProviderUiState
}

interface AppRuntimeProvider {
    val uiState: StateFlow<ProviderUiState>
}

interface ProviderSelectionStore {
    fun selectedProviderId(): String?
    fun selectProvider(id: String)
}

interface LibraryProvider {
    val tracks: StateFlow<List<Track>>
    fun track(id: String): Track?
}

interface PreparationProvider {
    fun cues(trackId: String): StateFlow<List<Cue>>
    fun loops(trackId: String): StateFlow<List<Loop>>
    fun beatGrid(trackId: String): StateFlow<BeatGrid?>
}

interface PlaybackProvider {
    val currentTrackId: StateFlow<String?>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    fun play(trackId: String)
    fun toggle()
    fun seek(positionMs: Long)
}

interface PlaylistProvider {
    val playlists: StateFlow<List<Playlist>>
    fun playlist(id: String): Playlist?
}

interface DownloadProvider {
    fun state(trackId: String): StateFlow<TrackDownloadState>
}

interface MutationJournalProvider {
    val pending: StateFlow<List<PendingMutation>>
    val recentReceipts: StateFlow<List<MutationReceipt>>
}

interface SyncProvider {
    val state: StateFlow<SyncState>
    val pendingMutationCount: StateFlow<Int>
}

interface AnalysisProvider {
    val capabilities: StateFlow<List<AnalysisCapability>>
    fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>>
    fun accept(suggestionId: String)
    fun reject(suggestionId: String)
    fun refresh(trackId: String)
}

data class AppProviders(
    val runtime: AppRuntimeProvider,
    val library: LibraryProvider,
    val playlists: PlaylistProvider,
    val preparation: PreparationProvider,
    val playback: PlaybackProvider,
    val downloads: DownloadProvider,
    val journal: MutationJournalProvider,
    val sync: SyncProvider,
    val analysis: AnalysisProvider,
)
