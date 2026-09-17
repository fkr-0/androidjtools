package dev.androidjtools.visual

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.ui.library.LibraryScreen
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import dev.androidjtools.ui.waveform.WaveformScreen
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device-rendered evidence for Lane A stories that explicitly require visual proof. */
class LaneAVisualParityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureLibraryArtworkAndCompactMetadata_US011() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent {
            AndroidDjToolsTheme { LibraryScreen(providers) }
        }

        compose.onNodeWithTag("library-track-list").performScrollToIndex(2)
        compose.onNodeWithTag("track-artwork-trk-001").assertIsDisplayed()
        compose.onNodeWithTag("track-metadata-trk-001")
            .assertIsDisplayed()
            .assertTextContains("92.5 BPM · 8A · ★★★★ · Offline · Analyzed")
        compose.onNodeWithText("Sample Lab · Fixture Cuts").assertIsDisplayed()

        captureRoot("US-011-library-artwork-compact-metadata.png")
    }

    @Test
    fun captureWaveformOverviewDetailAndOverlayLegend_US040_US043() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        compose.setContent {
            MaterialTheme { WaveformScreen(providers) }
        }

        compose.onNodeWithTag("waveform-overview").assertIsDisplayed()
        compose.onNodeWithTag("waveform-detail").assertIsDisplayed()
        compose.onNodeWithText("Canonical cue/loop = solid · analysis candidate = dashed").assertIsDisplayed()
        compose.onNodeWithText("◆ Clean intro").assertIsDisplayed()
        compose.onNodeWithText("◇ Detected drop").assertIsDisplayed()

        captureRoot("US-040-US-043-waveform-overlays.png")
    }

    private fun captureRoot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Visual evidence must have meaningful width", bitmap.width >= 200)
        assertTrue("Visual evidence must have meaningful height", bitmap.height >= 200)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, OUTPUT_DIRECTORY).apply { mkdirs() }
        val output = File(directory, name)
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to encode visual evidence $name"
            }
        }
        assertTrue("Visual evidence $name was empty", output.length() > PNG_HEADER_BYTES)
    }

    private companion object {
        const val OUTPUT_DIRECTORY = "lane-a-parity-screenshots"
        const val PNG_HEADER_BYTES = 24L
    }
}
