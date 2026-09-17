package dev.androidjtools.ui.waveform

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        compose.onNodeWithTag("grid-bpm-input").assertExists().assertTextContains("92.50")
        compose.onNodeWithTag("grid-anchor-input").assertExists()
        compose.onNodeWithTag("grid-half-bpm").assertExists().performClick()
        compose.onNodeWithTag("grid-bpm-input").assertTextContains("46.25")
        compose.onNodeWithTag("grid-double-bpm").assertExists().performClick()
        compose.onNodeWithTag("grid-bpm-input").assertTextContains("92.50")
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

    @Test
    fun pinchPanAndScrub_produceObservableViewportAndSeekTransitions() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent { MaterialTheme { WaveformScreen(providers) } }

        val initialStatus = waveformStatusText()
        compose.onNodeWithTag("waveform-detail").performTouchInput {
            pinch(
                start0 = Offset(center.x - 36f, center.y),
                start1 = Offset(center.x + 36f, center.y),
                end0 = Offset(center.x - 120f, center.y),
                end1 = Offset(center.x + 120f, center.y),
            )
        }
        compose.waitForIdle()
        val zoomedStatus = waveformStatusText()
        assertNotEquals("Pinch must change the rendered zoom/window state", initialStatus, zoomedStatus)

        compose.onNodeWithTag("waveform-detail").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val pannedStatus = waveformStatusText()
        assertNotEquals("Pan must change the rendered visible window", zoomedStatus, pannedStatus)

        compose.onNodeWithText("Gesture: pan/zoom").performClick()
        compose.onNodeWithText("Gesture: scrub").assertExists()
        val beforeScrub = providers.playback.positionMs.value
        compose.onNodeWithTag("waveform-detail").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val afterScrub = providers.playback.positionMs.value
        assertNotEquals("Scrub gesture must seek playback", beforeScrub, afterScrub)
        assertTrue(afterScrub in 0L..244_000L)
        compose.onNodeWithTag("waveform-time-zoom")
            .assertTextContains(formatWaveformTime(afterScrub))
    }

    @Test
    fun canonicalAndCandidateOverlaySelection_renderDistinctFocusedSources() {
        compose.setContent { MaterialTheme { WaveformScreen(FixtureAppProviders.create()) } }

        compose.onNodeWithText("◆ Clean intro").performClick()
        compose.onNodeWithTag("waveform-focused-overlay")
            .assertTextContains("Focused canonical: Clean intro @ 0:16")

        compose.onNodeWithText("◇ Detected drop").performClick()
        compose.onNodeWithTag("waveform-focused-overlay")
            .assertTextContains("Focused candidate: Detected drop @ 0:31")
    }

    private fun waveformStatusText(): String = compose
        .onNodeWithTag("waveform-time-zoom")
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }
}
