package dev.androidjtools.prep.loops

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class LoopEditorSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loopSemanticsExposeRoleLabelBoundsAndActiveState() {
        val loop = LoopDraft(
            localId = "loop-1",
            startMs = 10_000L,
            endMs = 18_000L,
            label = "Mix region",
            role = PreparationLoopRole.MIX_IN,
            active = true,
        )

        compose.setContent {
            Box(Modifier.loopPreparationSemantics(loop, staged = true)) {
                Text("Loop target")
            }
        }

        compose.onNodeWithContentDescription(
            "Loop, mix in, Mix region, 10000 to 18000 milliseconds",
        ).assertExists().assertIsDisplayed()
    }

    @Test
    fun pendingDeleteKeepsLoopAddressable() {
        val loop = LoopDraft("loop-1", 10_000L, 18_000L)

        compose.setContent {
            Box(Modifier.loopPreparationSemantics(loop, staged = true, pendingDelete = true)) {
                Text("Delete loop")
            }
        }

        compose.onNodeWithContentDescription("Loop, loop, 10000 to 18000 milliseconds").assertExists()
    }
}
