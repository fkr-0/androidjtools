package dev.androidjtools.a11y

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import org.junit.Rule
import org.junit.Test

class ScaledFontNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twoXFontScaleKeepsCoreNavigationAndLibrarySearchDiscoverable() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2.0f)
            ) {
                MaterialTheme { AndroidDjToolsApp(providers) }
            }
        }

        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
        listOf("Library", "Lists", "Prep", "Suggest", "Sync").forEach { label ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }

        compose.onNodeWithContentDescription("Suggest").performClick()
        compose.onNodeWithContentDescription("Sync").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
    }
}
