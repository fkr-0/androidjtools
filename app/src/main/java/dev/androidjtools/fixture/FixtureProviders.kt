package dev.androidjtools.fixture

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.MutationReceipt
import dev.androidjtools.core.model.PendingMutation
import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.ReceiptOutcome
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.AnalysisProvider
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.MutationJournalProvider
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.core.provider.PlaylistProvider
import dev.androidjtools.core.provider.PreparationProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.core.provider.SyncProvider
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FixtureScenario {
    NOMINAL,
    LOADING,
    EMPTY,
    ERROR,
    OFFLINE,
    CONFLICT,
}

object FixtureAppProviders {
    const val PROVIDER_ID = "fixture"

    fun create(scenario: FixtureScenario = FixtureScenario.NOMINAL): AppProviders {
        val tracks = if (scenario == FixtureScenario.EMPTY) emptyList() else listOf(
            Track("trk-001", "Night Bus", "Sample Lab", "Fixture Cuts", 244_000, 92.5, "8A", 0.72, 4, true),
            Track("trk-002", "Concrete Flash", "Fixture Artist", "Mobile Prep", 198_000, 140.0, "9A", 0.86, 5, false),
            Track("trk-003", "Dub Colony", "Fixture Artist", "Mobile Prep", 327_000, 74.0, "2A", 0.55, 3, true),
        )
        val library = FixtureLibraryProvider(tracks)
        val playlists = FixturePlaylistProvider(
            listOf(
                Playlist("pl-warmup", "Warmup", listOf("trk-001", "trk-003")),
                Playlist("pl-energy", "High Energy", listOf("trk-002"), smart = true, ruleSummary = "energy ≥ 0.75"),
            )
        )
        val preparation = FixturePreparationProvider()
        val playback = FixturePlaybackProvider()
        val downloads = FixtureDownloadProvider()
        val journal = FixtureMutationJournalProvider()
        val sync = FixtureSyncProvider(journal, scenario)
        val analysis = FixtureAnalysisProvider()
        val runtime = FixtureRuntimeProvider(scenario)
        return AppProviders(runtime, library, playlists, preparation, playback, downloads, journal, sync, analysis)
    }
}

private class FixtureRuntimeProvider(scenario: FixtureScenario) : AppRuntimeProvider {
    override val uiState = MutableStateFlow(scenario.toProviderUiState()).asStateFlow()
}

private class FixtureLibraryProvider(items: List<Track>) : LibraryProvider {
    private val mutableTracks = MutableStateFlow(items)
    override val tracks = mutableTracks.asStateFlow()
    override fun track(id: String): Track? = tracks.value.firstOrNull { it.id == id }
}

private class FixturePlaylistProvider(items: List<Playlist>) : PlaylistProvider {
    private val mutablePlaylists = MutableStateFlow(items)
    override val playlists = mutablePlaylists.asStateFlow()
    override fun playlist(id: String): Playlist? = playlists.value.firstOrNull { it.id == id }
}

private class FixturePreparationProvider : PreparationProvider {
    private val cueMap = mutableMapOf<String, MutableStateFlow<List<Cue>>>()
    private val loopMap = mutableMapOf<String, MutableStateFlow<List<Loop>>>()
    private val gridMap = mutableMapOf<String, MutableStateFlow<BeatGrid?>>()

    override fun cues(trackId: String): StateFlow<List<Cue>> = cueMap.getOrPut(trackId) {
        MutableStateFlow(listOf(Cue("cue-$trackId-1", trackId, 16_000, "mix_in", "Clean intro", hotCueNumber = 1)))
    }.asStateFlow()

    override fun loops(trackId: String): StateFlow<List<Loop>> = loopMap.getOrPut(trackId) {
        MutableStateFlow(listOf(Loop("loop-$trackId-1", trackId, 64_000, 80_000, "loop", "Drums")))
    }.asStateFlow()

    override fun beatGrid(trackId: String): StateFlow<BeatGrid?> = gridMap.getOrPut(trackId) {
        MutableStateFlow(BeatGrid(trackId, 125, 92.5, 1))
    }.asStateFlow()
}

private class FixturePlaybackProvider : PlaybackProvider {
    private val mutableTrack = MutableStateFlow<String?>(null)
    private val mutablePlaying = MutableStateFlow(false)
    private val mutablePosition = MutableStateFlow(0L)
    override val currentTrackId = mutableTrack.asStateFlow()
    override val isPlaying = mutablePlaying.asStateFlow()
    override val positionMs = mutablePosition.asStateFlow()
    override fun play(trackId: String) { mutableTrack.value = trackId; mutablePlaying.value = true; mutablePosition.value = 0 }
    override fun toggle() { mutablePlaying.value = !mutablePlaying.value }
    override fun seek(positionMs: Long) { mutablePosition.value = positionMs.coerceAtLeast(0) }
}

private class FixtureDownloadProvider : DownloadProvider {
    private val states = mutableMapOf<String, MutableStateFlow<TrackDownloadState>>()

    override fun state(trackId: String): StateFlow<TrackDownloadState> = states.getOrPut(trackId) {
        MutableStateFlow(
            when (trackId) {
                "trk-001", "trk-003" -> TrackDownloadState(trackId, DownloadStatus.AVAILABLE, progress = 1.0)
                "trk-002" -> TrackDownloadState(trackId, DownloadStatus.DOWNLOADING, progress = 0.42, bytesDownloaded = 42_000_000, totalBytes = 100_000_000)
                else -> TrackDownloadState(trackId, DownloadStatus.NOT_DOWNLOADED)
            }
        )
    }.asStateFlow()
}

private class FixtureMutationJournalProvider : MutationJournalProvider {
    override val pending = MutableStateFlow(
        listOf(
            PendingMutation("mut-fixture-1", "marker", "cue-trk-001-1", "update", baseRevision = 4, provenance = "manual"),
            PendingMutation("mut-fixture-2", "track", "trk-001", "accept_suggestion", baseRevision = 7, provenance = "suggestion:sg-trk-001-bpm"),
        )
    ).asStateFlow()
    override val recentReceipts = MutableStateFlow(
        listOf(MutationReceipt("mut-fixture-0", ReceiptOutcome.APPLIED, authoritativeRevision = 7))
    ).asStateFlow()
}

private class FixtureSyncProvider(
    journal: MutationJournalProvider,
    scenario: FixtureScenario,
) : SyncProvider {
    override val state = MutableStateFlow(scenario.toSyncState()).asStateFlow()
    override val pendingMutationCount = MutableStateFlow(journal.pending.value.size).asStateFlow()
}

private fun FixtureScenario.toProviderUiState(): ProviderUiState = when (this) {
    FixtureScenario.NOMINAL -> ProviderUiState.Ready
    FixtureScenario.LOADING -> ProviderUiState.Loading
    FixtureScenario.EMPTY -> ProviderUiState.Empty
    FixtureScenario.ERROR -> ProviderUiState.Error("Fixture provider failure")
    FixtureScenario.OFFLINE -> ProviderUiState.Offline
    FixtureScenario.CONFLICT -> ProviderUiState.Conflict("Fixture conflict requires resolution")
}

private fun FixtureScenario.toSyncState(): SyncState = when (this) {
    FixtureScenario.NOMINAL -> SyncState.PENDING_LOCAL
    FixtureScenario.LOADING -> SyncState.PULLING
    FixtureScenario.EMPTY -> SyncState.ONLINE_IDLE
    FixtureScenario.ERROR -> SyncState.DEGRADED
    FixtureScenario.OFFLINE -> SyncState.OFFLINE
    FixtureScenario.CONFLICT -> SyncState.CONFLICT
}

private class FixtureAnalysisProvider : AnalysisProvider {
    private val byTrack = mutableMapOf<String, MutableStateFlow<List<AnalysisSuggestion>>>()
    override val capabilities = MutableStateFlow(
        listOf(
            AnalysisCapability("sample-intelligence", "Sample Intelligence", true, SuggestionKind.entries.toSet(), "Canonical analysis aggregator"),
            AnalysisCapability("demucs", "Demucs stems", true, setOf(SuggestionKind.STEM, SuggestionKind.REGION), "Backend stem separation"),
            AnalysisCapability("comfyui-asr", "ComfyUI ASR", true, setOf(SuggestionKind.TRANSCRIPT_MARKER, SuggestionKind.REGION), "Backend ASR workflow"),
            AnalysisCapability("bpm-ensemble", "Tempo ensemble", true, setOf(SuggestionKind.BPM), "Multiple BPM estimators negotiated server-side"),
        )
    ).asStateFlow()

    override fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>> = flowFor(trackId).asStateFlow()

    override fun accept(suggestionId: String) = decide(suggestionId, SuggestionDecision.ACCEPTED)
    override fun reject(suggestionId: String) = decide(suggestionId, SuggestionDecision.REJECTED)
    override fun refresh(trackId: String) { if (flowFor(trackId).value.isEmpty()) flowFor(trackId).value = seed(trackId) }

    private fun flowFor(trackId: String) = byTrack.getOrPut(trackId) { MutableStateFlow(seed(trackId)) }

    private fun decide(id: String, decision: SuggestionDecision) {
        byTrack.values.forEach { flow ->
            if (flow.value.any { it.id == id }) flow.value = flow.value.map { if (it.id == id) it.copy(decision = decision) else it }
        }
    }

    private fun seed(trackId: String) = listOf(
        AnalysisSuggestion("sg-$trackId-bpm", trackId, SuggestionKind.BPM, SuggestionPayload.Bpm(92.48), IntelligenceSource("sample-intelligence", "tempo-ensemble", "1"), 0.96, Instant.EPOCH),
        AnalysisSuggestion("sg-$trackId-cue", trackId, SuggestionKind.CUE, SuggestionPayload.Point(31_820, "drop", "Detected drop"), IntelligenceSource("sample-intelligence", "structure", "2"), 0.88, Instant.EPOCH),
        AnalysisSuggestion("sg-$trackId-asr", trackId, SuggestionKind.TRANSCRIPT_MARKER, SuggestionPayload.Transcript(46_000, "bring it back", "en"), IntelligenceSource("comfyui-asr", "configured-workflow", "1"), 0.81, Instant.EPOCH),
        AnalysisSuggestion("sg-$trackId-stem", trackId, SuggestionKind.STEM, SuggestionPayload.Stem("drums", "samplelib://derived/$trackId/stems/drums"), IntelligenceSource("demucs", "htdemucs", "4"), 0.99, Instant.EPOCH),
        AnalysisSuggestion("sg-$trackId-safe", trackId, SuggestionKind.SPECTRAL_OUTLIER_WARNING, SuggestionPayload.Warning("spectral-outlier", "Possible harsh high-frequency outlier near 00:58", "warning"), IntelligenceSource("sample-intelligence", "audio-safety", "1"), 0.9, Instant.EPOCH),
    )
}
