package dev.androidjtools.ui.analysis

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.provider.ServerEditStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The S5 controls: request, tempo choice, add as cues, and the single way out of a conflict. Compile-checked in CI. */
class ServerSuggestionContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun candidate(
        id: String,
        kind: SuggestionKind,
        confidence: Double? = null,
        tempoChoices: List<Double> = emptyList(),
        cuePointsMs: List<Long> = emptyList(),
        acceptance: AcceptanceUi? = null,
    ) = CandidateUi(
        id = id, trackId = "a1", kind = kind, title = kind.name, value = "value", confidence = confidence,
        provenance = SuggestionProvenanceUi("mir.essentia_lowlevel", modelVersion = "2.1"),
        generatedAt = "2026-10-01T10:00:00Z", tempoChoices = tempoChoices, primaryBpm = tempoChoices.getOrNull(1),
        cuePointsMs = cuePointsMs, analysisRunId = "run-1", acceptance = acceptance,
    )

    private fun show(candidate: CandidateUi, actions: MutableList<AnalysisUiAction>) = compose.setContent {
        MaterialTheme { CandidateCard(candidate, offline = false, acceptanceEnabled = true, onAction = actions::add) }
    }

    @Test
    fun analyseThisTrackIsAButtonOnlyWhenOffered() {
        val actions = mutableListOf<AnalysisUiAction>()
        compose.setContent {
            MaterialTheme { RequestSection(AnalysisRequestUi(true, null), "a1", null, false, actions::add) }
        }
        compose.onNodeWithTag("analysis-request").assertIsEnabled().performClick()
        compose.onNodeWithTag("analysis-request-unavailable").assertDoesNotExist()
        assertEquals(listOf<AnalysisUiAction>(AnalysisUiAction.RequestAnalysis("a1")), actions)
    }

    @Test
    fun withoutOfferOneLineSaysSoAndThereIsNoButton() {
        compose.setContent {
            MaterialTheme { RequestSection(AnalysisRequestUi(false, "This server has no analysis available."), "a1", null, false, {}) }
        }
        compose.onNodeWithTag("analysis-request").assertDoesNotExist()
        compose.onNodeWithTag("analysis-request-unavailable").assertIsDisplayed()
    }

    @Test
    fun aChosenTempoAliasTravelsWithTheAccept() {
        val actions = mutableListOf<AnalysisUiAction>()
        show(candidate("t", SuggestionKind.BPM, tempoChoices = listOf(60.0, 120.0, 240.0)), actions)

        compose.onNodeWithTag("tempo-choice-t-0").performClick()
        compose.onNodeWithTag("accept-t").performClick()

        assertEquals(60.0, (actions.single() as AnalysisUiAction.QueueAcceptance).intent.chosenBpm!!, 0.0)
    }

    @Test
    fun cueCandidatesOfferAddAsCuesNotAccept() {
        val actions = mutableListOf<AnalysisUiAction>()
        show(candidate("c", SuggestionKind.CUE, cuePointsMs = listOf(1000L, 2000L)), actions)

        compose.onNodeWithTag("accept-c").assertDoesNotExist()
        compose.onNodeWithTag("add-cues-c").performClick()
        assertEquals(listOf<AnalysisUiAction>(AnalysisUiAction.AddAsCues("c")), actions)
    }

    @Test
    fun aConflictOffersOnlyDismiss() {
        val actions = mutableListOf<AnalysisUiAction>()
        val conflict = AcceptanceUi(ServerEditStatus.CONFLICT, "Needs your decision", "The server's suggestion changed.")
        show(candidate("t", SuggestionKind.BPM, tempoChoices = listOf(60.0, 120.0), acceptance = conflict), actions)

        compose.onNodeWithTag("acceptance-t").assertIsDisplayed()
        compose.onNodeWithTag("accept-t").assertDoesNotExist()
        compose.onNodeWithTag("tempo-choice-t-0").assertDoesNotExist()
        compose.onNodeWithTag("reject-t").performClick()
        assertTrue(actions.single() is AnalysisUiAction.Reject)
    }

    @Test
    fun confidenceAppearsOnlyWhenThereIsOne() {
        show(candidate("with", SuggestionKind.KEY, confidence = 0.82), mutableListOf())
        compose.onNodeWithTag("provenance-with").assertIsDisplayed()
        assertNull(candidate("without", SuggestionKind.KEY).confidenceLabel)
        assertEquals("confidence 82%", candidate("with", SuggestionKind.KEY, confidence = 0.82).confidenceLabel)
    }
}
