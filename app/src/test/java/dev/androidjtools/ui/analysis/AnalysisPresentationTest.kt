package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisPresentationTest {
    private val source = IntelligenceSource("sample-intelligence", "tempo-ensemble", "3", "2026.09")

    @Test
    fun `competing tempo candidates coexist and expose compare context`() {
        val state = present(
            listOf(
                suggestion("bpm-a", SuggestionKind.BPM, SuggestionPayload.Bpm(92.48), confidence = 0.96),
                suggestion("bpm-b", SuggestionKind.BPM, SuggestionPayload.Bpm(184.96), confidence = 0.72),
                suggestion("key-a", SuggestionKind.KEY, SuggestionPayload.Key("8A"), confidence = 0.88),
            )
        )
        val tempo = state.candidateGroups.single { it.kind == SuggestionKind.BPM }
        assertTrue(tempo.competing)
        assertEquals(listOf("bpm-a", "bpm-b"), tempo.candidates.map { it.id })
        assertEquals("sample-intelligence · tempo-ensemble · v3 · pipeline 2026.09", tempo.candidates.first().provenance.label)
    }

    @Test
    fun `stale cached candidate remains reviewable offline but cannot create acceptance intent`() {
        val state = present(
            suggestions = listOf(suggestion("bpm-stale", SuggestionKind.BPM, SuggestionPayload.Bpm(91.9), stale = true, inputRevision = 41)),
            sync = SyncState.OFFLINE,
        )
        val candidate = state.candidateGroups.single().candidates.single()
        assertTrue(state.offline)
        assertTrue(candidate.stale)
        assertEquals(41L, candidate.inputRevision)
        assertEquals("sample-intelligence", candidate.provenance.service)
        assertEquals(null, candidate.acceptanceIntent(state.offline))
    }

    @Test
    fun `queued and receipt confirmed acceptance are distinct states`() {
        val proposed = suggestion("queued", SuggestionKind.KEY, SuggestionPayload.Key("8A"))
        val accepted = suggestion("accepted", SuggestionKind.KEY, SuggestionPayload.Key("8B"), decision = SuggestionDecision.ACCEPTED)
        val state = present(listOf(proposed, accepted), queued = setOf("queued"))
        val candidates = state.candidateGroups.single().candidates.associateBy { it.id }
        assertEquals(CandidateReviewState.QUEUED, candidates.getValue("queued").reviewState)
        assertEquals(CandidateReviewState.ACCEPTED, candidates.getValue("accepted").reviewState)
        assertEquals(null, candidates.getValue("accepted").acceptanceIntent(false))
    }

    @Test
    fun `capability loss is partial when another service remains usable`() {
        val state = presentAnalysisAssistant(
            trackId = "track",
            trackTitle = "Track",
            suggestions = listOf(suggestion("bpm", SuggestionKind.BPM, SuggestionPayload.Bpm(92.0))),
            capabilities = listOf(
                AnalysisCapability("sample-intelligence", "Sample Intelligence", true, setOf(SuggestionKind.BPM)),
                AnalysisCapability("demucs", "Demucs", false, setOf(SuggestionKind.STEM), "Worker unavailable"),
            ),
            syncState = SyncState.DEGRADED,
        )
        assertTrue(state.partialFailure)
        assertEquals(CapabilityAvailability.UNAVAILABLE, state.capabilities.single { it.id == "demucs" }.availability)
        assertEquals(AnalysisJobState.UNAVAILABLE, state.jobs.single { it.id == "demucs" }.state)
        assertTrue(state.hasSuggestions)
    }

    @Test
    fun `safety findings stay separate from musical candidate ranking`() {
        val state = present(
            listOf(
                suggestion("bpm", SuggestionKind.BPM, SuggestionPayload.Bpm(92.0)),
                suggestion("warning", SuggestionKind.SPECTRAL_OUTLIER_WARNING, SuggestionPayload.Warning("spectral-outlier", "Harsh peak near 58 seconds", "blocking")),
            )
        )
        assertEquals(1, state.safetyFindings.size)
        assertEquals("Spectral safety", state.safetyFindings.single().title)
        assertFalse(state.candidateGroups.any { it.kind == SuggestionKind.SPECTRAL_OUTLIER_WARNING })
    }

    @Test
    fun `timeline integrations include cue range and transcript positions`() {
        val state = present(
            listOf(
                suggestion("cue", SuggestionKind.CUE, SuggestionPayload.Point(30_000, "drop", "Drop")),
                suggestion("region", SuggestionKind.REGION, SuggestionPayload.Range(10_000, 20_000, "intro", "Intro")),
                suggestion("phrase", SuggestionKind.TRANSCRIPT_MARKER, SuggestionPayload.Transcript(15_000, "bring it back", "en")),
            )
        )
        assertEquals(listOf("region", "phrase", "cue"), state.timelineItems.map { it.id })
        assertEquals(20_000L, state.timelineItems.first().endMs)
    }

    @Test
    fun `deterministic fixtures cover empty failure and competition states`() {
        assertFalse(AnalysisAssistantFixtures.empty().hasSuggestions)
        assertNotNull(AnalysisAssistantFixtures.failure().failureMessage)
        val competition = AnalysisAssistantFixtures.competition()
        assertTrue(competition.offline)
        assertTrue(competition.partialFailure)
        assertTrue(competition.candidateGroups.first { it.kind == SuggestionKind.BPM }.competing)
        assertTrue(
            competition.candidateGroups.map { it.kind }.containsAll(
                setOf(
                    SuggestionKind.BPM,
                    SuggestionKind.KEY,
                    SuggestionKind.CUE,
                    SuggestionKind.LOOP,
                    SuggestionKind.REGION,
                    SuggestionKind.STEM,
                    SuggestionKind.RELATED_TRACK,
                )
            )
        )
        assertEquals("bpm · key · energy", competition.candidateGroups.single { it.kind == SuggestionKind.RELATED_TRACK }.candidates.single().detail)
        assertEquals("blocking", competition.safetyFindings.first().severity)
    }

    private fun present(
        suggestions: List<AnalysisSuggestion>,
        sync: SyncState = SyncState.ONLINE_IDLE,
        queued: Set<String> = emptySet(),
    ) = presentAnalysisAssistant(
        trackId = "track",
        trackTitle = "Track",
        suggestions = suggestions,
        capabilities = listOf(AnalysisCapability("sample-intelligence", "Sample Intelligence", true, SuggestionKind.entries.toSet())),
        syncState = sync,
        queuedSuggestionIds = queued,
    )

    private fun suggestion(
        id: String,
        kind: SuggestionKind,
        payload: SuggestionPayload,
        confidence: Double? = 0.8,
        stale: Boolean = false,
        inputRevision: Long? = 42,
        decision: SuggestionDecision = SuggestionDecision.PROPOSED,
    ) = AnalysisSuggestion(
        id = id,
        trackId = "track",
        kind = kind,
        payload = payload,
        source = source,
        confidence = confidence,
        generatedAt = Instant.EPOCH,
        inputRevision = inputRevision,
        decision = decision,
        stale = stale,
    )
}

