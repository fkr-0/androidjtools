package dev.androidjtools.core.provider

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.MutationReceipt
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.PendingMutation
import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun release() = Unit

    /** A short reason the last play request did nothing, or null. Providers that never refuse inherit null. */
    val playbackNotice: StateFlow<String?> get() = NoPlaybackNotice
}

private val NoPlaybackNotice: StateFlow<String?> = MutableStateFlow<String?>(null)

interface PlaylistProvider {
    val playlists: StateFlow<List<Playlist>>
    fun playlist(id: String): Playlist?
}

interface DownloadProvider {
    val cacheSummary: StateFlow<OfflineCacheSummary>
    fun state(trackId: String): StateFlow<TrackDownloadState>
    fun pinTrack(trackId: String)
    fun unpinTrack(trackId: String)
    fun pinPlaylist(playlistId: String)
    fun unpinPlaylist(playlistId: String)
    fun retry(trackId: String)
    fun cancel(trackId: String)
    fun pruneUnpinned()
}

interface MutationJournalProvider {
    val pending: StateFlow<List<PendingMutation>>
    val recentReceipts: StateFlow<List<MutationReceipt>>
}

interface SyncProvider {
    val state: StateFlow<SyncState>
    val pendingMutationCount: StateFlow<Int>
}

/** Whether "Analyse this track" may be offered. It is offered only when the paired server said it accepts requests. */
sealed interface AnalysisRequestAvailability {
    data object Offered : AnalysisRequestAvailability
    data class Unavailable(val reason: String) : AnalysisRequestAvailability
}

/** Where an accepted suggestion stands on its way to the server. Reuses the vocabulary of saved cue and loop edits. */
data class SuggestionAcceptance(
    val suggestionId: String,
    val mutationId: String,
    val status: ServerEditStatus,
    val detail: String,
)

sealed interface AnalysisActionResult {
    data class Done(val message: String) : AnalysisActionResult
    data class Refused(val reason: String) : AnalysisActionResult
}

interface AnalysisProvider {
    val capabilities: StateFlow<List<AnalysisCapability>>
    fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>>
    fun accept(suggestionId: String)

    /** Dismisses a suggestion on this device only. */
    fun reject(suggestionId: String)
    fun refresh(trackId: String)

    /** Whether the explicit "Analyse this track" action is offered. Providers without a server inherit "not offered". */
    val requestAvailability: StateFlow<AnalysisRequestAvailability> get() = NoAnalysisRequest

    /** Whether accepting can be queued, with the reason when it cannot. */
    val acceptAvailability: StateFlow<EditAvailability> get() = NoAnalysisAccept

    /** Per suggestion, the state of its latest acceptance. Absent when it was never accepted (or the intent was dismissed). */
    val acceptances: StateFlow<Map<String, SuggestionAcceptance>> get() = NoAcceptances

    /** The one way analysis ever starts: a deliberate action. Never called by a pull, a refresh or a screen opening. */
    suspend fun requestAnalysis(trackId: String): AnalysisActionResult =
        AnalysisActionResult.Refused((requestAvailability.value as? AnalysisRequestAvailability.Unavailable)?.reason ?: "Analysis is not available.")

    /** Queues acceptance of a tempo, beat-grid or key suggestion. [bpm] picks one of a tempo's alternatives. */
    suspend fun acceptSuggestion(suggestionId: String, bpm: Double? = null): AnalysisActionResult =
        AnalysisActionResult.Refused("Accepting suggestions needs a connected Sample Lib server.")

    /** Dismisses a suggestion (and closes a conflicted or refused acceptance of it). Local to this device. */
    suspend fun dismissSuggestion(suggestionId: String) = reject(suggestionId)
}

private val NoAnalysisRequest: StateFlow<AnalysisRequestAvailability> =
    MutableStateFlow<AnalysisRequestAvailability>(AnalysisRequestAvailability.Unavailable("Connect to a Sample Lib server to get analysis."))
private val NoAnalysisAccept: StateFlow<EditAvailability> =
    MutableStateFlow<EditAvailability>(EditAvailability.Unavailable("Connect to a Sample Lib server to accept suggestions."))
private val NoAcceptances: StateFlow<Map<String, SuggestionAcceptance>> = MutableStateFlow(emptyMap())

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
    /** Saving cue and loop edits to a paired server. Unavailable (with a reason) until one is paired. */
    val edits: ServerEditProvider = NoServerEdits,
)
