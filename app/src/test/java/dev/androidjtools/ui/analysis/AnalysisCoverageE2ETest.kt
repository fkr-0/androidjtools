package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-executable coverage for the advisory intelligence matrix used by the UI. */
class AnalysisCoverageE2ETest {
    private val source = IntelligenceSource("sample-intelligence", "fixture-matrix", "1", "e2e")

    @Test
    fun allPreparationCandidateKindsCoexistAndRelatedTrackExplainsDimensions() {
        val suggestions = listOf(
            suggestion("bpm", SuggestionKind.BPM, SuggestionPayload.Bpm(92.5)),
            suggestion("key", SuggestionKind.KEY, SuggestionPayload.Key("8A")),
            suggestion("cue", SuggestionKind.CUE, SuggestionPayload.Point(16_000, "mix_in", "Mix in")),
            suggestion("loop", SuggestionKind.LOOP, SuggestionPayload.Range(32_000, 40_000, "loop", "Drums")),
            suggestion("region", SuggestionKind.REGION, SuggestionPayload.Range(48_000, 64_000, "break", "Break")),
            suggestion("stem", SuggestionKind.STEM, SuggestionPayload.Stem("drums", "samplelib://derived/track/drums")),
            suggestion(
                "related",
                SuggestionKind.RELATED_TRACK,
                SuggestionPayload.Related("related-track", listOf("bpm", "key", "energy")),
            ),
        )
        val state = presentAnalysisAssistant(
            trackId = "track",
            trackTitle = "Fixture Track",
            suggestions = suggestions,
            capabilities = listOf(
                AnalysisCapability(
                    id = "sample-intelligence",
                    displayName = "Sample Intelligence",
                    available = true,
                    kinds = SuggestionKind.entries.toSet(),
                )
            ),
            syncState = SyncState.ONLINE_IDLE,
        )

        val required = setOf(
            SuggestionKind.BPM,
            SuggestionKind.KEY,
            SuggestionKind.CUE,
            SuggestionKind.LOOP,
            SuggestionKind.REGION,
            SuggestionKind.STEM,
        )
        assertTrue(state.candidateGroups.map { it.kind }.containsAll(required))

        val related = state.candidateGroups
            .single { it.kind == SuggestionKind.RELATED_TRACK }
            .candidates.single()
        assertEquals("related-track", related.value)
        assertEquals("bpm · key · energy", related.detail)
        assertNotNull(related.acceptanceIntent(offline = false))

        required.forEach { kind ->
            val candidate = state.candidateGroups.single { it.kind == kind }.candidates.single()
            assertNotNull("$kind must remain explicitly reviewable", candidate.acceptanceIntent(false))
        }
        assertEquals(7, suggestions.size)
        assertTrue(suggestions.all { it.decision.name == "PROPOSED" })
    }

    private fun suggestion(
        id: String,
        kind: SuggestionKind,
        payload: SuggestionPayload,
    ) = AnalysisSuggestion(
        id = id,
        trackId = "track",
        kind = kind,
        payload = payload,
        source = source,
        confidence = 0.9,
        generatedAt = Instant.EPOCH,
        inputRevision = 42,
    )
}
