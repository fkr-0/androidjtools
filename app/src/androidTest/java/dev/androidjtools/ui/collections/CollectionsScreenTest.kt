package dev.androidjtools.ui.collections

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import org.junit.Rule
import org.junit.Test

class CollectionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineStateKeepsLocalPreparationVisible() {
        compose.setContent {
            MaterialTheme { CollectionsScreen(FixtureAppProviders.create(FixtureScenario.OFFLINE)) }
        }
        compose.onNodeWithText("Offline preparation").assertIsDisplayed()
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Metadata").assertIsDisplayed()
    }

    @Test
    fun emptyStateExplainsCollectionAbsence() {
        compose.setContent {
            MaterialTheme { CollectionsScreen(FixtureAppProviders.create(FixtureScenario.EMPTY)) }
        }
        compose.onNodeWithText("No collection data").assertIsDisplayed()
    }

    @Test
    fun conflictStateIsExplicitBeforeEdits() {
        compose.setContent {
            MaterialTheme { CollectionsScreen(FixtureAppProviders.create(FixtureScenario.CONFLICT)) }
        }
        compose.onNodeWithText("Conflict requires review").assertIsDisplayed()
    }
}
