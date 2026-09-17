package dev.androidjtools.ui.metadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.ui.collections.CollectionsScreen
import org.junit.Rule
import org.junit.Test

class MetadataQuickRatingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oneTapRatingStagesLocalEditAndEnablesSaveIntent() {
        compose.setContent {
            MaterialTheme {
                CollectionsScreen(FixtureAppProviders.create(FixtureScenario.NOMINAL))
            }
        }

        compose.onNodeWithText("Metadata").performClick()
        compose.onNodeWithText("5★").performClick().assertIsSelected()
        compose.onNodeWithText("Save locally").assertIsEnabled()
    }

    @Test
    fun namespacedTagStagesLocallyWhileUnscopedTagFailsValidation() {
        compose.setContent {
            MaterialTheme {
                CollectionsScreen(FixtureAppProviders.create(FixtureScenario.NOMINAL))
            }
        }

        compose.onNodeWithText("Metadata").performClick()
        val tags = compose.onNodeWithText("Tags (namespace:value, comma separated)")
        tags.performScrollTo().performTextReplacement("mood:late-night")
        compose.onNodeWithText("Save locally").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Queued locally for Night Bus:", substring = true).assertExists()

        tags.performTextReplacement("late-night")
        compose.onNodeWithText("Use namespaced tags such as mood:late-night").assertExists()
        compose.onNodeWithText("Save locally").assertIsNotEnabled()
    }
}
