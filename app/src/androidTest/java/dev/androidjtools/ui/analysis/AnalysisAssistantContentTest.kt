package dev.androidjtools.ui.analysis

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.androidjtools.core.model.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AnalysisAssistantContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun competitionAndSafetyRemainVisuallySeparate() {
        val state = AnalysisAssistantFixtures.competition()
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    SafetySection(state.safetyFindings)
                    CandidateGroupCard(
                        group = state.candidateGroups.first { it.kind == SuggestionKind.BPM },
                        offline = state.offline,
                        acceptanceEnabled = true,
                        onAction = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("safety-section").assertIsDisplayed()
        compose.onNodeWithTag("candidate-group-BPM").assertIsDisplayed()
        compose.onNodeWithText("2 competing candidates · compare below").assertIsDisplayed()
    }

    @Test
    fun staleCandidateFailsClosedToRefreshWhileRejectRemainsExplicit() {
        val state = AnalysisAssistantFixtures.competition()
        val candidate = state.candidateGroups
            .first { it.kind == SuggestionKind.BPM }
            .candidates
            .first { it.stale }
        val actions = mutableListOf<AnalysisUiAction>()
        compose.setContent {
            MaterialTheme {
                CandidateCard(
                    candidate = candidate,
                    offline = true,
                    acceptanceEnabled = true,
                    onAction = actions::add,
                )
            }
        }

        compose.onNodeWithTag("freshness-${candidate.id}").assertIsDisplayed()
        compose.onNodeWithText("Source input changed. Refresh this proposal before accepting; stale input never creates a mutation intent.").assertIsDisplayed()
        compose.onNodeWithTag("accept-${candidate.id}").assertDoesNotExist()
        compose.onNodeWithTag("refresh-stale-${candidate.id}").assertIsEnabled().performClick()
        compose.onNodeWithTag("reject-${candidate.id}").assertIsEnabled().performClick()

        assertTrue(actions.filterIsInstance<AnalysisUiAction.QueueAcceptance>().isEmpty())
        assertEquals(state.trackId, actions.filterIsInstance<AnalysisUiAction.Refresh>().single().trackId)
        assertEquals(candidate.id, actions.filterIsInstance<AnalysisUiAction.Reject>().single().suggestionId)
    }

    @Test
    fun freshOfflineAcceptanceQueuesIntentWithoutClaimingCanonicalSuccess() {
        val state = AnalysisAssistantFixtures.competition()
        val candidate = state.candidateGroups
            .first { it.kind == SuggestionKind.BPM }
            .candidates
            .first { !it.stale }
        val actions = mutableListOf<AnalysisUiAction>()
        compose.setContent {
            MaterialTheme {
                CandidateCard(
                    candidate = candidate,
                    offline = true,
                    acceptanceEnabled = true,
                    onAction = actions::add,
                )
            }
        }

        compose.onNodeWithTag("accept-${candidate.id}").assertIsEnabled().performClick()

        val accept = actions.filterIsInstance<AnalysisUiAction.QueueAcceptance>().single()
        assertEquals(candidate.id, accept.intent.suggestionId)
        assertEquals(SuggestionKind.BPM, accept.intent.kind)
        assertTrue(accept.intent.queuedOffline)
        assertFalse(accept.intent.requiresFreshnessConfirmation)
        assertTrue(actions.filterIsInstance<AnalysisUiAction.Reject>().isEmpty())
    }

    @Test
    fun capabilityLossIsExplicitWithoutHidingHealthyCapabilities() {
        val state = AnalysisAssistantFixtures.competition()
        compose.setContent {
            MaterialTheme {
                CapabilityStrip(state.capabilities, state.partialFailure)
            }
        }

        compose.onNodeWithText("Partial backend availability").assertIsDisplayed()
        compose.onNodeWithTag("capability-sample-intelligence").assertIsDisplayed()
        compose.onNodeWithTag("capability-comfyui-asr").assertExists()
        compose.onNodeWithText("ComfyUI ASR: unavailable").assertExists()
    }

    @Test
    fun emptyAndFailureStatesStayActionableWithoutCandidates() {
        val state = AnalysisAssistantFixtures.failure().copy(capabilities = emptyList(), jobs = emptyList())
        compose.setContent {
            MaterialTheme {
                AnalysisAssistantContent(
                    state = state,
                    acceptanceEnabled = false,
                    onAction = {},
                )
            }
        }
        compose.onNodeWithTag("analysis-failure").assertIsDisplayed()
        compose.onNodeWithTag("analysis-empty").assertIsDisplayed()
        compose.onNodeWithText("Analysis services failed. Canonical library data is still available.").assertIsDisplayed()
    }
}
