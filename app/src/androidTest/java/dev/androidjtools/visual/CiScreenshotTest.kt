package dev.androidjtools.visual

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.playback.InMemoryQueueStateStore
import dev.androidjtools.playback.PlayerQueueController
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * CI-owned visual evidence for the fixture-first app shell.
 *
 * UiAutomation captures the complete emulator display, so the PNGs contain the
 * actual Compose rendering plus Android system chrome at the CI device's real
 * pixel dimensions. Files stay in the debuggable target app's private files
 * directory until the workflow extracts them with `adb run-as`.
 */
class CiScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureTopLevelScreensOnModernAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshotDirectory = File(instrumentation.targetContext.filesDir, OUTPUT_DIRECTORY).apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create screenshot directory: $absolutePath" }
        }

        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        val controller = PlayerQueueController(providers.playback, InMemoryQueueStateStore())
        controller.playNow("trk-001")

        compose.setContent {
            AndroidDjToolsTheme {
                AndroidDjToolsApp(
                    providers = providers,
                    playerQueueController = controller,
                )
            }
        }

        compose.onNodeWithContentDescription("Search library").assertExists()
        capture(screenshotDirectory, "01-library.png")

        compose.onNodeWithContentDescription("Lists").performClick()
        compose.onNodeWithText("Collections & metadata").assertExists()
        capture(screenshotDirectory, "02-collections.png")

        compose.onNodeWithContentDescription("Prep").performClick()
        compose.onNodeWithText("Preparation").assertExists()
        capture(screenshotDirectory, "03-preparation.png")

        compose.onNodeWithContentDescription("Suggest").performClick()
        compose.onNodeWithText("Preparation assistant").assertExists()
        capture(screenshotDirectory, "04-suggestions.png")

        compose.onNodeWithContentDescription("Sync").performClick()
        compose.onNodeWithText("Sync & backend").assertExists()
        capture(screenshotDirectory, "05-sync.png")

        assertTrue(
            "Expected exactly five CI screenshots",
            screenshotDirectory.listFiles { file -> file.extension == "png" }?.size == 5,
        )
    }

    private fun capture(directory: File, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(directory, name)
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to encode screenshot $name"
            }
        }
        bitmap.recycle()
        assertTrue("Screenshot $name was empty", output.length() > PNG_HEADER_BYTES)
        val header = ByteArray(PNG_SIGNATURE.size)
        FileInputStream(output).use { stream ->
            check(stream.read(header) == header.size) { "Screenshot $name was too small for a PNG header" }
        }
        assertTrue("Screenshot $name did not contain a PNG signature on-device", header.contentEquals(PNG_SIGNATURE))
    }

    private companion object {
        const val OUTPUT_DIRECTORY = "ui-screenshots"
        const val PNG_HEADER_BYTES = 24L
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
