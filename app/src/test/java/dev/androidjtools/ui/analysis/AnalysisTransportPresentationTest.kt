package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.remote.samplelib.AnalysisCandidateDecision
import dev.androidjtools.remote.samplelib.AnalysisCandidateFreshness
import dev.androidjtools.remote.samplelib.AnalysisCandidateKind
import dev.androidjtools.remote.samplelib.AnalysisCandidateSource
import dev.androidjtools.remote.samplelib.AnalysisCandidateValue
import dev.androidjtools.remote.samplelib.AnalysisInputIdentity
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.SampleLibAnalysisCandidate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTransportPresentationTest {
    @Test
    fun `backend estimate renders as proposal and does not replace canonical metadata`() {
        val canonicalBpm = 88.0
        val candidate = SampleLibAnalysisCandidate(
            candidateId = "candidate-bpm-1",
            analysisRunId = "run-1",
            assetId = "asset-1",
            kind = AnalysisCandidateKind.BPM,
            value = AnalysisCandidateValue.Bpm(92.48),
            confidence = 0.94,
            source = AnalysisCandidateSource("sample-lib", "essentia-rhythm", "1.2.0", "analysis-v1"),
            generatedAt = Instant.parse("2026-09-15T12:00:00Z"),
            inputIdentity = AnalysisInputIdentity("asset", "asset-1", OpaqueRevision("rev:asset-1:7")),
            freshness = AnalysisCandidateFreshness.FRESH,
            decision = AnalysisCandidateDecision.PROPOSED,
        )
        val state = presentAnalysisAssistant(
            trackId = "asset-1",
            trackTitle = "Fixture One",
            suggestions = listOf(candidate.toPresentationSuggestion()),
            capabilities = listOf(AnalysisCapability("sample-lib", "Sample Lib analysis", true, setOf(SuggestionKind.BPM))),
            syncState = SyncState.ONLINE_IDLE,
        )

        val proposal = state.candidateGroups.single().candidates.single()
        assertEquals("92.48 BPM", proposal.value)
        assertEquals(CandidateReviewState.PROPOSED, proposal.reviewState)
        assertEquals("sample-lib · essentia-rhythm · v1.2.0 · pipeline analysis-v1", proposal.provenance.label)
        assertEquals(88.0, canonicalBpm, 0.0) // Transport projection has no canonical write side effect.
        assertFalse(proposal.stale)
        assertTrue(state.hasSuggestions)
    }
}
