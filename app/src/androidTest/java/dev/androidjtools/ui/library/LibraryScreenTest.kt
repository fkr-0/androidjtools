package dev.androidjtools.ui.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nominalLibrary_searchAndCompoundOfflineFilter_areCoherent() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithText("3 tracks").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search library").performTextInput("concrete")
        compose.onNodeWithText("Concrete Flash").assertIsDisplayed()
        compose.onAllNodesWithText("Night Bus").assertCountEquals(0)

        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("Any availability").performClick()
        compose.onNodeWithText("2 of 3 tracks").assertIsDisplayed()
        compose.onNodeWithText("Night Bus").assertIsDisplayed()
        compose.onNodeWithText("Dub Colony").assertIsDisplayed()
        compose.onAllNodesWithText("Concrete Flash").assertCountEquals(0)
    }

    @Test
    fun selectionAndPlayback_exposeClearAccessibleFeedback() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithContentDescription("Select Night Bus").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Select visible").assertIsDisplayed()
        compose.onNodeWithContentDescription("Deselect Night Bus").assertIsDisplayed()

        compose.onNodeWithContentDescription("Play Night Bus").performClick()
        compose.onNodeWithContentDescription("Playing").assertIsDisplayed()

        compose.onNodeWithText("Clear").performClick()
        compose.onAllNodesWithText("1 selected").assertCountEquals(0)
    }

    @Test
    fun trackDetail_restoresAndReturnsToSameLibraryState() {
        val restoration = StateRestorationTester(compose)
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        restoration.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithContentDescription("Search library").performTextInput("night")
        compose.onNodeWithContentDescription("Open details for Night Bus").performClick()
        compose.onNodeWithText("Track details").assertIsDisplayed()
        compose.onNodeWithText("Night Bus").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Track details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithText("1 of 3 tracks").assertIsDisplayed()
        compose.onNodeWithText("Night Bus").assertIsDisplayed()
        compose.onAllNodesWithText("Concrete Flash").assertCountEquals(0)
    }

    @Test
    fun savedView_restoresAcrossSavedInstanceState_andCanBeReapplied() {
        val restoration = StateRestorationTester(compose)
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        restoration.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithContentDescription("Search library").performTextInput("night")
        compose.onNodeWithText("Save view").performClick()
        compose.onNodeWithText("Saved 1").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Saved 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("3 tracks").assertIsDisplayed()
        compose.onNodeWithText("Saved 1").performClick()
        compose.onNodeWithText("View 1").performClick()
        compose.onNodeWithText("1 of 3 tracks").assertIsDisplayed()
        compose.onNodeWithText("Night Bus").assertIsDisplayed()
    }

    @Test
    fun fixtureEmptyState_isDeterministic() {
        val providers = FixtureAppProviders.create(FixtureScenario.EMPTY)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }
        compose.onNodeWithText("Library is empty").assertIsDisplayed()
    }

    @Test
    fun fixtureOfflineState_keepsOfflineReadyLibraryUsable() {
        val providers = FixtureAppProviders.create(FixtureScenario.OFFLINE)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }
        compose.onNodeWithText("Offline-ready tracks remain available.").assertIsDisplayed()
        compose.onNodeWithText("Night Bus").assertIsDisplayed()
    }

    @Test
    fun fixtureLoadingState_drivesTheLibrarySurface() {
        val providers = FixtureAppProviders.create(FixtureScenario.LOADING)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }
        compose.onNodeWithText("Loading library…").assertIsDisplayed()
    }

    @Test
    fun fixtureErrorState_drivesTheLibrarySurface() {
        val providers = FixtureAppProviders.create(FixtureScenario.ERROR)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }
        compose.onNodeWithText("Library unavailable").assertIsDisplayed()
        compose.onNodeWithText("Fixture provider failure").assertIsDisplayed()
    }

    @Test
    fun fixtureConflictState_drivesTheLibrarySurface() {
        val providers = FixtureAppProviders.create(FixtureScenario.CONFLICT)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }
        compose.onNodeWithText("Library conflict").assertIsDisplayed()
        compose.onNodeWithText("Fixture conflict requires resolution").assertIsDisplayed()
    }

    @Test
    fun searchSelectionSortAndFilters_restoreAcrossSavedInstanceState() {
        val restoration = StateRestorationTester(compose)
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        restoration.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithContentDescription("Search library").performTextInput("night")
        compose.onNodeWithContentDescription("Select Night Bus").performClick()
        compose.onNodeWithContentDescription("Sort direction Ascending. Double tap to reverse.").performClick()
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("4+ stars").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("1 of 3 tracks").assertIsDisplayed()
        compose.onNodeWithText("Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Deselect Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort direction Descending. Double tap to reverse.").assertIsDisplayed()
        compose.onNodeWithText("Filters 1").assertIsDisplayed()
    }

    @Test
    fun keyControlsHaveAccessibilitySemantics() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Artwork placeholder for Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open details for Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Night Bus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort by Title. Double tap to change field.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort direction Ascending. Double tap to reverse.").assertIsDisplayed()
    }
}
