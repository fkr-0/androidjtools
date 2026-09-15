package dev.androidjtools.ui.waveform

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import dev.androidjtools.fixture.FixtureAppProviders
import org.junit.Rule
import org.junit.Test

class WaveformScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersOverviewDetailAndCandidateLegendAtMdpi() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme { WaveformScreen(FixtureAppProviders.create()) }
            }
        }

        compose.onNodeWithTag("waveform-overview").assertExists()
        compose.onNodeWithTag("waveform-detail").assertExists()
        compose.onNodeWithText("Canonical cue/loop = solid · analysis candidate = dashed").assertExists()
        compose.onNodeWithText("◇ Detected drop").assertExists()
    }

    @Test
    fun rendersWaveformSurfacesAtHighDensity() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) {
                MaterialTheme { WaveformScreen(FixtureAppProviders.create()) }
            }
        }

        compose.onNodeWithTag("waveform-overview").assertExists()
        compose.onNodeWithTag("waveform-detail").assertExists()
        compose.onNodeWithTag("waveform-runtime-status").assertExists()
    }

    @Test
    fun exposesPrecisionGridBeatJumpAndGestureControls() {
        compose.setContent { MaterialTheme { WaveformScreen(FixtureAppProviders.create()) } }

        compose.onNodeWithText("Beat jump").assertExists()
        compose.onNodeWithText("Gesture: pan/zoom").assertExists()
        compose.onNodeWithTag("waveform-grid-editor").assertExists()
        compose.onNodeWithTag("grid-bpm-input").assertExists()
        compose.onNodeWithTag("grid-anchor-input").assertExists()
    }

    @Test
    fun selectingCanonicalLoopExposesRangeHandlesAndQuantizeEditing() {
        compose.setContent { MaterialTheme { WaveformScreen(FixtureAppProviders.create()) } }

        compose.onNodeWithText("↔ Drums").performClick()
        compose.onNodeWithTag("waveform-range-editor").assertExists()
        compose.onNodeWithTag("range-handle-start").assertExists()
        compose.onNodeWithTag("range-handle-end").assertExists()
        compose.onNodeWithText("Quantize: 1 beat").performClick()
        compose.onNodeWithText("Quantize: 1/2 beat").assertExists()
        compose.onNodeWithTag("range-start-input").performTextReplacement("64500")
        compose.onNodeWithText("Pending local range edit · persistence awaits preparation mutation authority.").assertExists()
        compose.onNodeWithText("Undo range").performClick()
    }

    @Test
    fun zoomPanScrubAndMarkerSelectionAreAddressable() {
        compose.setContent { MaterialTheme { WaveformScreen(FixtureAppProviders.create()) } }

        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithTag("waveform-detail").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Gesture: pan/zoom").performClick()
        compose.onNodeWithText("Gesture: scrub").assertExists()
        compose.onNodeWithTag("waveform-detail").performTouchInput { swipeLeft() }
        compose.onNodeWithText("◆ Clean intro").performClick()
        compose.onNodeWithTag("waveform-focused-overlay").assertExists()
    }
}
