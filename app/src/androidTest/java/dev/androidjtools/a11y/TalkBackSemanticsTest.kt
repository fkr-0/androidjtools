package dev.androidjtools.a11y

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.fixture.FixtureAppProviders
import org.junit.Rule
import org.junit.Test

class TalkBackSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shellAndPrimaryLibraryActionsExposeMeaningfulClickableSemantics() {
        compose.setContent {
            MaterialTheme { AndroidDjToolsApp(FixtureAppProviders.create()) }
        }

        listOf("Library", "Lists", "Prep", "Suggest", "Sync").forEach { destination ->
            compose.onNodeWithContentDescription(destination)
                .assertIsDisplayed()
                .assertHasClickAction()
        }

        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select Night Bus").assertHasClickAction()
        compose.onNodeWithContentDescription("Open details for Night Bus").assertHasClickAction()
        compose.onNodeWithContentDescription("Play Night Bus").assertHasClickAction()

        compose.onNodeWithContentDescription("Prep").performClick()
        compose.onNodeWithText("Zoom in").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Gesture: pan/zoom").assertIsDisplayed().assertHasClickAction()
    }
}
