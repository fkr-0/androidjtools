package dev.androidjtools.prep.cues

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class CueEditorSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cueSemanticsExposeRoleNumberLabelPrecisionAndStagedState() {
        val cue = HotCueDraft(
            localId = "cue-1",
            positionMs = 12_345L,
            label = "Main drop",
            number = 4,
            role = HotCueRole.DROP,
        )

        compose.setContent {
            Box(Modifier.hotCuePreparationSemantics(cue, staged = true)) {
                Text("Cue target")
            }
        }

        compose.onNodeWithContentDescription("Hot cue 4, drop, Main drop, at 0:12.345")
            .assertExists()
            .assertIsDisplayed()
    }

    @Test
    fun pendingDeleteHasDistinctAccessibleState() {
        val cue = HotCueDraft("cue-1", positionMs = 1_000L)

        compose.setContent {
            Box(Modifier.hotCuePreparationSemantics(cue, staged = true, pendingDelete = true)) {
                Text("Delete cue")
            }
        }

        compose.onNodeWithContentDescription("Hot cue, cue, at 0:01.000").assertExists()
    }
}
