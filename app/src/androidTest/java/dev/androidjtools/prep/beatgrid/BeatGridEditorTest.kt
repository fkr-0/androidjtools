package dev.androidjtools.prep.beatgrid

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.androidjtools.core.model.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BeatGridEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun halfDoubleCandidateDownbeatAndCommitHandlersAreAddressable() {
        val canonical = BeatGrid("track", anchorMs = 125L, bpm = 92.5, revision = 7L)
        var state by mutableStateOf(
            BeatGridPreparationState(
                canonical = canonical,
                candidate = BeatGridCandidate(
                    id = "candidate",
                    trackId = "track",
                    bpm = 128.0,
                    anchorMs = 250L,
                    source = "fixture-intelligence",
                    confidence = 0.9,
                ),
            )
        )
        var commit: BeatGridCommitIntent? = null

        compose.setContent {
            MaterialTheme {
                BeatGridEditor(
                    state = state,
                    durationMs = 240_000L,
                    playheadMs = 2_000L,
                    onStateChange = { state = it },
                    onCommitIntent = { commit = it },
                )
            }
        }

        compose.onNodeWithTag("beatgrid-half-bpm").assertIsEnabled().performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "46.25 BPM")
        compose.onNodeWithTag("beatgrid-double-bpm").assertIsEnabled().performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "92.50 BPM")

        compose.onNodeWithTag("beatgrid-set-downbeat-at-playhead").performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "2000 ms")
        compose.onNodeWithTag("beatgrid-phase-forward").performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "2010 ms")

        compose.onNodeWithTag("beatgrid-accept-candidate").performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "128.00 BPM")
        assertTaggedTextContains("beatgrid-staged-summary", "250 ms")
        compose.onNodeWithTag("beatgrid-pending-edit").assertIsDisplayed()
        compose.onNodeWithText("Pending local beatgrid edit · canonical state is unchanged until a receipt is accepted.").assertIsDisplayed()

        compose.onNodeWithTag("beatgrid-commit-intent").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertNotNull(commit)
            assertEquals(7L, commit?.baseRevision)
            assertEquals(128.0, commit?.bpm ?: 0.0, 0.0)
            assertEquals(250L, commit?.anchorMs)
            assertEquals(canonical, state.canonical)
        }
    }

    @Test
    fun undoAndCancelRestoreStagedGridWithoutChangingCanonicalGrid() {
        val canonical = BeatGrid("track", anchorMs = 0L, bpm = 120.0, revision = 9L)
        var state by mutableStateOf(BeatGridPreparationState(canonical))

        compose.setContent {
            MaterialTheme {
                BeatGridEditor(
                    state = state,
                    durationMs = 10_000L,
                    onStateChange = { state = it },
                    onCommitIntent = {},
                )
            }
        }

        compose.onNodeWithTag("beatgrid-phase-forward").performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "10 ms")
        compose.onNodeWithTag("beatgrid-undo").performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "0 ms")

        compose.onNodeWithTag("beatgrid-double-bpm").performClick()
        compose.onNodeWithTag("beatgrid-cancel").performClick()
        assertTaggedTextContains("beatgrid-staged-summary", "120.00 BPM")
        compose.runOnIdle { assertEquals(canonical, state.canonical) }
    }

    private fun assertTaggedTextContains(tag: String, expected: String) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
        assertTrue("Expected '$expected' in '$actual' for $tag", expected in actual)
    }
}
