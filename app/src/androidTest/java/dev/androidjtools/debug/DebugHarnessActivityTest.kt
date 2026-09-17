package dev.androidjtools.debug

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DebugHarnessActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugHarnessActivity>()

    @Test
    fun deterministicControlsExposeOfflineAndFailureStatesWithJournalEvidence() {
        compose.onNodeWithText("DOGFOOD / DEBUG ONLY").assertExists()
        compose.onNodeWithText("Mutation journal (2 pending)").assertExists()
        compose.onNodeWithText("Receipts (2)").assertExists()
        compose.onNodeWithText("Online").assertExists().performClick()

        compose.onNodeWithText("Effective sync: OFFLINE · backend: DOWN").assertExists()
        compose.onNodeWithText("Mutation journal (2 pending)").assertExists()
        compose.onNodeWithText("Receipts (2)").assertExists()

        compose.onNodeWithText("None").assertExists().performClick()
        compose.onNodeWithText("Provider error").assertExists()
        compose.onNodeWithText("Library unavailable").assertExists()

        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Online").assertExists()
        compose.onNodeWithText("None").assertExists()
        compose.onNodeWithText("Effective sync: PENDING_LOCAL · backend: HEALTHY").assertExists()
    }
}
