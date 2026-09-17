package dev.androidjtools.e2e

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.playback.InMemoryQueueStateStore
import dev.androidjtools.playback.PlayerQueueController
import org.junit.Rule
import org.junit.Test

/**
 * Cross-screen smoke test for the fixture-first app shell.
 *
 * This deliberately exercises only stable top-level contracts. Feature-specific
 * gesture and editor tests live with their owning screens, while this proves the
 * routes can be traversed as one product without losing the active audition.
 */
class AppShellE2ETest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun topLevelUxRemainsNavigableWhileMiniPlayerPersists() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        val controller = PlayerQueueController(providers.playback, InMemoryQueueStateStore())
        controller.playNow("trk-001")

        compose.setContent {
            MaterialTheme {
                AndroidDjToolsApp(
                    providers = providers,
                    playerQueueController = controller,
                )
            }
        }

        assertActiveAudition()
        compose.onNodeWithContentDescription("Search library").assertExists()

        compose.onNodeWithContentDescription("Lists").performClick()
        compose.onNodeWithText("Collections & metadata").assertExists()
        assertActiveAudition()

        compose.onNodeWithContentDescription("Prep").performClick()
        compose.onNodeWithText("Preparation").assertExists()
        compose.onNodeWithTag("waveform-detail").assertExists()
        assertActiveAudition()

        compose.onNodeWithContentDescription("Suggest").performClick()
        compose.onNodeWithTag("analysis-screen").assertExists()
        compose.onNodeWithText("Preparation assistant").assertExists()
        assertActiveAudition()

        compose.onNodeWithContentDescription("Sync").performClick()
        compose.onNodeWithText("Sync & backend").assertExists()
        assertActiveAudition()

        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithContentDescription("Search library").assertExists()
        assertActiveAudition()
    }

    private fun assertActiveAudition() {
        compose.onNodeWithTag("mini-player").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
    }
}
