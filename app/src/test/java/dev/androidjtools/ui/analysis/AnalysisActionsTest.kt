package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AnalysisActionResult
import dev.androidjtools.core.provider.AnalysisProvider
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.core.provider.ServerEdit
import dev.androidjtools.core.provider.ServerEditProvider
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.ui.waveform.ServerEditPlanner
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisActionsTest {
    private class Analysis : AnalysisProvider {
        val calls = mutableListOf<String>()
        override val capabilities: StateFlow<List<AnalysisCapability>> = MutableStateFlow(emptyList())
        override fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>> = MutableStateFlow(emptyList())
        override fun accept(suggestionId: String) { calls += "accept:$suggestionId" }
        override fun reject(suggestionId: String) { calls += "reject:$suggestionId" }
        override fun refresh(trackId: String) { calls += "refresh:$trackId" }
        override suspend fun requestAnalysis(trackId: String): AnalysisActionResult {
            calls += "request:$trackId"
            return AnalysisActionResult.Done("Queued on the server.")
        }
        override suspend fun acceptSuggestion(suggestionId: String, bpm: Double?): AnalysisActionResult {
            calls += "acceptSuggestion:$suggestionId:$bpm"
            return AnalysisActionResult.Refused("nope")
        }
        override suspend fun dismissSuggestion(suggestionId: String) {
            calls += "dismiss:$suggestionId"
        }
    }

    private class Edits(private val result: PreparationStageResult = PreparationStageResult.Queued("m")) : ServerEditProvider {
        val staged = mutableListOf<IntervalEditorStateReplaceIntent>()
        override val availability: StateFlow<EditAvailability> = MutableStateFlow(EditAvailability.Available)
        override val edits: StateFlow<List<ServerEdit>> = MutableStateFlow(emptyList())
        override fun aggregate(intervalId: String): IntervalEditorStateContext? = null
        override fun aggregates(trackId: String): List<IntervalEditorStateContext> = emptyList()
        override suspend fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult {
            staged += intent
            return result
        }
        override suspend fun useServerState(mutationId: String) = false
        override suspend fun reapplyDraft(mutationId: String): PreparationStageResult = error("unused")
    }

    private val track = Track("a1", "Alpha", "Ann", durationMs = 10_000L)
    private val aggregate = IntervalEditorStateContext("iv-1", "r1", 0L, 10_000L, emptyList(), completeMarkerSet = true)
    private fun planner(aggregates: List<IntervalEditorStateContext> = listOf(aggregate)) =
        ServerEditPlanner(track, emptyList(), emptyList(), aggregates, { 1L }, { "android-mutation-1" })

    private val cues = AnalysisSuggestion(
        "run-1:cue_candidates:a1", "a1", SuggestionKind.CUE, SuggestionPayload.CuePoints(listOf(2_000L, 4_000L)),
        IntelligenceSource("tool"), analysisRunId = "run-1", generatedAt = Instant.EPOCH,
    )

    private fun intent(bpm: Double? = null) = AnalysisMutationIntent("s1", "a1", SuggestionKind.BPM, null, SuggestionProvenanceUi("t"), false, false, bpm)

    @Test
    fun onlyTheExplicitRequestGestureStartsAnalysis() = runTest {
        val analysis = Analysis()
        val others = listOf(
            AnalysisUiAction.QueueAcceptance(intent(60.0)),
            AnalysisUiAction.Reject("s1"),
            AnalysisUiAction.Refresh("a1"),
            AnalysisUiAction.Preview("s1"),
            AnalysisUiAction.AddAsCues(cues.id),
        )
        others.forEach { performAnalysisAction(it, analysis, Edits(), listOf(cues)) { planner() } }
        assertTrue("no request among ${analysis.calls}", analysis.calls.none { it.startsWith("request:") })

        assertEquals("Queued on the server.", performAnalysisAction(AnalysisUiAction.RequestAnalysis("a1"), analysis, Edits(), emptyList()) { null })
        assertEquals(1, analysis.calls.count { it == "request:a1" })
    }

    @Test
    fun acceptanceCarriesTheChosenTempoAndReportsTheAnswer() = runTest {
        val analysis = Analysis()
        val notice = performAnalysisAction(AnalysisUiAction.QueueAcceptance(intent(60.0)), analysis, Edits(), emptyList()) { null }
        assertEquals("nope", notice)
        assertEquals(listOf("acceptSuggestion:s1:60.0"), analysis.calls)
    }

    @Test
    fun dismissAndRefreshAreSilentAndGoToTheProvider() = runTest {
        val analysis = Analysis()
        assertNull(performAnalysisAction(AnalysisUiAction.Reject("s1"), analysis, Edits(), emptyList()) { null })
        assertNull(performAnalysisAction(AnalysisUiAction.Refresh("a1"), analysis, Edits(), emptyList()) { null })
        assertEquals(listOf("dismiss:s1", "refresh:a1"), analysis.calls)
    }

    @Test
    fun addAsCuesStagesOneEditWithAnalysisProvenance() = runTest {
        val edits = Edits()
        val notice = performAnalysisAction(AnalysisUiAction.AddAsCues(cues.id), Analysis(), edits, listOf(cues)) { planner() }

        assertTrue(notice!!.startsWith("Queued"))
        val intent = edits.staged.single()
        assertEquals(listOf(2_000L, 4_000L), intent.payload.markers.map { it.positionMs })
        assertEquals("analysis", intent.provenance["source"])
        assertEquals("run-1", intent.provenance["analysis_run_id"])
    }

    @Test
    fun addAsCuesRefusesCleanlyWithoutASyncedSectionAndStagesNothing() = runTest {
        val edits = Edits()
        val notice = performAnalysisAction(AnalysisUiAction.AddAsCues(cues.id), Analysis(), edits, listOf(cues)) { planner(emptyList()) }

        assertTrue(notice!!.contains("No synced section"))
        assertTrue(edits.staged.isEmpty())
    }

    @Test
    fun addAsCuesExplainsAStagingRefusalAndAnUnsavableTrack() = runTest {
        val refused = Edits(PreparationStageResult.Rejected("unavailable", "Pair again to save edits to the server."))
        assertEquals(
            "Pair again to save edits to the server.",
            performAnalysisAction(AnalysisUiAction.AddAsCues(cues.id), Analysis(), refused, listOf(cues)) { planner() },
        )
        assertTrue(performAnalysisAction(AnalysisUiAction.AddAsCues(cues.id), Analysis(), Edits(), listOf(cues)) { null }!!.contains("not on the server"))
        assertTrue(performAnalysisAction(AnalysisUiAction.AddAsCues("gone"), Analysis(), Edits(), listOf(cues)) { planner() }!!.contains("no longer"))
    }

    @Test
    fun cueCandidatesAndGridsCanBeAuditionedAtTheirFirstPosition() {
        assertEquals(2_000L, previewPositionMs(cues))
        assertEquals(250L, previewPositionMs(cues.copy(payload = SuggestionPayload.Grid(120.0, 250L, 4))))
        assertNull(previewPositionMs(cues.copy(payload = SuggestionPayload.Key("A minor"))))
        assertNull(previewPositionMs(cues.copy(payload = SuggestionPayload.Bpm(120.0))))
    }
}
