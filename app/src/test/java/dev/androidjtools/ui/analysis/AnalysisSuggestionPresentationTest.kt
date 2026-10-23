package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.AnalysisRequestAvailability
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.core.provider.SuggestionAcceptance
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How server suggestions read on the Suggest screen: tool, optional confidence, choices, and where an accept stands. */
class AnalysisSuggestionPresentationTest {
    private val source = IntelligenceSource("mir.essentia_lowlevel", null, "2.1", null)
    private val capability = AnalysisCapability("analysis.tempo", "Tempo analysis", true, SuggestionKind.entries.toSet(), anySource = true)

    private fun suggestion(
        id: String,
        kind: SuggestionKind,
        payload: SuggestionPayload,
        confidence: Double? = null,
        decision: SuggestionDecision = SuggestionDecision.PROPOSED,
    ) = AnalysisSuggestion(id, "a1", kind, payload, source, confidence, Instant.EPOCH, null, decision, false, "run-1")

    private fun present(
        suggestions: List<AnalysisSuggestion>,
        acceptances: Map<String, SuggestionAcceptance> = emptyMap(),
        request: AnalysisRequestUi = AnalysisRequestUi.None,
    ) = presentAnalysisAssistant(
        "a1", "Alpha", suggestions, listOf(capability), SyncState.ONLINE_IDLE, acceptances = acceptances, request = request,
    )

    private val tempo = suggestion("tempo", SuggestionKind.BPM, SuggestionPayload.Bpm(120.0, listOf(60.0, 240.0)), 0.82)
    private val key = suggestion("key", SuggestionKind.KEY, SuggestionPayload.Key("A minor"))
    private val grid = suggestion("grid", SuggestionKind.BEAT_GRID, SuggestionPayload.Grid(120.0, 250, 4))
    private val cues = suggestion("cues", SuggestionKind.CUE, SuggestionPayload.CuePoints(listOf(8_000L, 16_000L, 32_000L)))

    private fun candidate(state: AnalysisAssistantUiState, id: String) =
        state.candidateGroups.flatMap { it.candidates }.single { it.id == id }

    // ---- values ----

    @Test
    fun tempoShowsItsValueTheToolAndTheAliasesAsAChoice() {
        val c = candidate(present(listOf(tempo)), "tempo")
        assertEquals("120.00 BPM", c.value)
        assertEquals("mir.essentia_lowlevel · v2.1", c.provenance.label)
        assertEquals(listOf(60.0, 120.0, 240.0), c.tempoChoices)
        assertEquals(120.0, c.primaryBpm!!, 0.0)
        assertTrue(c.detail!!.contains("60.00"))
    }

    @Test
    fun aTempoWithoutAliasesHasNoChoiceToMake() {
        val c = candidate(present(listOf(suggestion("t", SuggestionKind.BPM, SuggestionPayload.Bpm(120.0)))), "t")
        assertTrue(c.tempoChoices.isEmpty())
    }

    @Test
    fun beatGridShowsBpmAndFirstBeatAndKeyShowsItsLabel() {
        val state = present(listOf(grid, key))
        assertEquals("120.00 BPM grid", candidate(state, "grid").value)
        assertTrue(candidate(state, "grid").detail!!.contains("First beat at 0:00.250"))
        assertTrue(candidate(state, "grid").detail!!.contains("4 beats"))
        assertEquals("A minor", candidate(state, "key").value)
    }

    @Test
    fun cueCandidatesShowACountAndTheFirstPositions() {
        val c = candidate(present(listOf(cues)), "cues")
        assertEquals("3 cue points", c.value)
        assertEquals("0:08 · 0:16 · 0:32", c.detail)
        assertEquals(listOf(8_000L, 16_000L, 32_000L), c.cuePointsMs)
        assertEquals("run-1", c.analysisRunId)
        assertEquals("Beat grid", present(listOf(grid)).candidateGroups.single().label)
    }

    @Test
    fun theGroupsComeInReadingOrder() {
        val order = present(listOf(cues, key, grid, tempo)).candidateGroups.map { it.kind }
        assertEquals(listOf(SuggestionKind.BPM, SuggestionKind.BEAT_GRID, SuggestionKind.KEY, SuggestionKind.CUE), order)
    }

    // ---- confidence is informational ----

    @Test
    fun confidenceIsShownOnlyWhenThereIsOne() {
        val state = present(listOf(tempo, key))
        assertEquals("confidence 82%", candidate(state, "tempo").confidenceLabel)
        assertNull(candidate(state, "key").confidenceLabel)
    }

    @Test
    fun aMissingConfidenceNeverReordersOrHidesAndNeverBlocksAccepting() {
        val withConfidence = suggestion("b", SuggestionKind.KEY, SuggestionPayload.Key("B"), confidence = 0.99)
        val without = suggestion("a", SuggestionKind.KEY, SuggestionPayload.Key("A"), confidence = null)
        val group = present(listOf(withConfidence, without)).candidateGroups.single()

        assertEquals(listOf("a", "b"), group.candidates.map { it.id })
        assertNotNull(group.candidates.first().acceptanceIntent(offline = false))
    }

    // ---- accepting ----

    @Test
    fun aTempoChoiceTravelsInTheIntentOnlyIfItIsOneOfTheOptions() {
        val c = candidate(present(listOf(tempo)), "tempo")
        assertEquals(60.0, c.acceptanceIntent(false, 60.0)!!.chosenBpm!!, 0.0)
        assertNull(c.acceptanceIntent(false, 99.0)!!.chosenBpm)
        assertNull(c.acceptanceIntent(false)!!.chosenBpm)
    }

    private fun accept(status: ServerEditStatus, detail: String = "x") = mapOf("tempo" to SuggestionAcceptance("tempo", "m1", status, detail))

    @Test
    fun anAcceptanceUsesTheWordsOfSavedEdits() {
        val labels = mapOf(
            ServerEditStatus.PENDING to "Waiting to send",
            ServerEditStatus.SENDING to "Sending",
            ServerEditStatus.SAVED to "Saved on server",
            ServerEditStatus.CONFLICT to "Needs your decision",
            ServerEditStatus.REJECTED to "Not accepted",
        )
        for ((status, label) in labels) {
            assertEquals(label, candidate(present(listOf(tempo), accept(status)), "tempo").acceptance!!.label)
        }
    }

    @Test
    fun queuedAndSavedAreDistinctReviewStates() {
        assertEquals(CandidateReviewState.QUEUED, candidate(present(listOf(tempo), accept(ServerEditStatus.PENDING)), "tempo").reviewState)
        assertEquals(CandidateReviewState.QUEUED, candidate(present(listOf(tempo), accept(ServerEditStatus.SENDING)), "tempo").reviewState)
        assertEquals(CandidateReviewState.ACCEPTED, candidate(present(listOf(tempo), accept(ServerEditStatus.SAVED)), "tempo").reviewState)
    }

    @Test
    fun aConflictOrRefusalOffersOnlyDismissAndNeverAnotherAccept() {
        for (status in listOf(ServerEditStatus.CONFLICT, ServerEditStatus.REJECTED)) {
            val c = candidate(present(listOf(tempo), accept(status)), "tempo")
            assertTrue(c.acceptance!!.onlyDismiss)
            assertNull("no accept while a decision is outstanding", c.acceptanceIntent(false))
        }
        assertFalse(candidate(present(listOf(tempo), accept(ServerEditStatus.PENDING)), "tempo").acceptance!!.onlyDismiss)
    }

    @Test
    fun theLibraryAlreadyHoldingTheValueReadsAsAccepted() {
        val done = suggestion("done", SuggestionKind.BPM, SuggestionPayload.Bpm(120.0), decision = SuggestionDecision.ACCEPTED)
        val c = candidate(present(listOf(done)), "done")
        assertEquals(CandidateReviewState.ACCEPTED, c.reviewState)
        assertNull(c.acceptanceIntent(false))
    }

    // ---- the explicit request ----

    @Test
    fun theRequestButtonExistsOnlyWhenOffered() {
        val offered = AnalysisRequestUi.of(AnalysisRequestAvailability.Offered)
        assertTrue(offered.offered)
        assertNull(offered.note)

        val not = AnalysisRequestUi.of(AnalysisRequestAvailability.Unavailable("This server has no analysis available."))
        assertFalse(not.offered)
        assertEquals("This server has no analysis available.", not.note)

        assertEquals(not, present(emptyList(), request = not).request)
    }

    @Test
    fun theJobsOfAnySourceCapabilityAreReadyOnceAnythingOfItsKindExists() {
        val state = present(listOf(tempo))
        assertEquals(AnalysisJobState.READY, state.jobs.single().state)
        assertEquals(AnalysisJobState.NOT_REQUESTED, present(emptyList()).jobs.single().state)
    }
}
