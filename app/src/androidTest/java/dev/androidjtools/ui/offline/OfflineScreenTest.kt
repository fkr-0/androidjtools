package dev.androidjtools.ui.offline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import org.junit.Rule
import org.junit.Test

class OfflineScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nominalFixture_exposesCachePinProgressAndPlaybackControls() {
        val providers = FixtureAppProviders.create()
        compose.setContent { AndroidDjToolsTheme { OfflineScreen(providers) } }

        compose.onNodeWithTag("offline-screen").assertIsDisplayed()
        compose.onNodeWithTag("offline-cache-summary").assertIsDisplayed()
        compose.onNodeWithText("142.0 MB of 1.0 GB used").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play cached Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel download for Concrete Flash").performClick()
        compose.onNodeWithContentDescription("Retry download for Concrete Flash").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pin playlist High Energy").performClick()
        compose.onNodeWithContentDescription("Unpin playlist High Energy").assertIsDisplayed()
    }

    @Test
    fun offlineFixture_keepsOfflineManagementVisible() {
        val providers = FixtureAppProviders.create(FixtureScenario.OFFLINE)
        compose.setContent { AndroidDjToolsTheme { OfflineScreen(providers) } }

        compose.onNodeWithTag("offline-state-banner").assertIsDisplayed()
        compose.onNodeWithText("Offline mode: cached media remains available. New downloads wait for connectivity.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play cached Night Bus").assertIsDisplayed()
    }
}
