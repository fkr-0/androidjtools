package dev.androidjtools.performance

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.debug.DebugHarnessActivity
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.ui.library.LibraryScreen
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import dev.androidjtools.ui.waveform.WaveformScreen
import java.util.Collections
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * EPIC-17 device-side regression gate.
 *
 * This is deliberately an instrumentation measurement tier rather than a Macrobenchmark claim.
 * It exercises the real Compose surfaces on the attached Android runtime, records wall-clock
 * 10k-library interaction latency and Window FrameMetrics for waveform gestures, and writes a
 * machine-readable evidence file into the debug app sandbox for the host runner to pull.
 */
class PerformanceRegressionTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugHarnessActivity>()

    @Test
    fun largeLibraryAndWaveformStayWithinInstrumentationRegressionBudgets() {
        val library = measureLargeLibrary()
        val waveform = measureWaveformFrames()

        val evidence = JSONObject()
            .put("schema", "androidjtools.epic17-performance/v1")
            .put("tier", "android-instrumentation-regression-gate")
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("api", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT))
            .put("library", library)
            .put("waveform", waveform)

        val destination = compose.activity.filesDir.resolve(EVIDENCE_FILE)
        destination.writeText(evidence.toString(2))
        assertTrue("performance evidence file must exist", destination.isFile)
    }

    private fun measureLargeLibrary(): JSONObject {
        val base = FixtureAppProviders.create()
        val tracks = List(LIBRARY_SIZE) { index ->
            val ordinal = index.toString().padStart(5, '0')
            Track(
                id = "perf-$ordinal",
                title = "Track $ordinal",
                artist = "Artist ${(index % 200).toString().padStart(3, '0')}",
                album = "Performance fixture",
                durationMs = 180_000L + index,
                bpm = 80.0 + (index % 80),
                key = if (index % 2 == 0) "Am" else "Dm",
                rating = (index % 5) + 1,
                energy = (index % 100) / 100.0,
                offlineAvailable = index % 3 == 0,
            )
        }
        val providers = base.copy(library = StaticLibraryProvider(tracks))

        val initialStart = SystemClock.elapsedRealtimeNanos()
        compose.runOnUiThread {
            compose.activity.setContent {
                AndroidDjToolsTheme { LibraryScreen(providers) }
            }
        }
        compose.waitForIdle()
        val initialSettleMs = elapsedMs(initialStart)
        compose.onNodeWithText("10000 tracks").assertExists()

        val search = compose.onNodeWithContentDescription("Search library")
        val queryOrdinals = listOf("09999", "07500", "05000", "02500", "00001")
        val samplesMs = queryOrdinals.map { ordinal ->
            val query = "Track $ordinal"
            val started = SystemClock.elapsedRealtimeNanos()
            search.performTextReplacement(query)
            compose.waitForIdle()
            // Assert the filtered result row, not just the text-field value (which is also query).
            compose.onNodeWithTag("track-row-perf-$ordinal").assertExists()
            elapsedMs(started)
        }
        val p95Ms = percentile95(samplesMs)

        assertTrue(
            "10k library initial settle ${"%.1f".format(initialSettleMs)} ms exceeded $MAX_LIBRARY_INITIAL_SETTLE_MS ms",
            initialSettleMs <= MAX_LIBRARY_INITIAL_SETTLE_MS,
        )
        assertTrue(
            "10k library search p95 ${"%.1f".format(p95Ms)} ms exceeded $MAX_LIBRARY_SEARCH_P95_MS ms",
            p95Ms <= MAX_LIBRARY_SEARCH_P95_MS,
        )

        return JSONObject()
            .put("track_count", LIBRARY_SIZE)
            .put("initial_settle_ms", initialSettleMs)
            .put("search_samples_ms", JSONArray(samplesMs))
            .put("search_p95_ms", p95Ms)
            .put("threshold_initial_settle_ms", MAX_LIBRARY_INITIAL_SETTLE_MS)
            .put("threshold_search_p95_ms", MAX_LIBRARY_SEARCH_P95_MS)
            .put("status", "pass")
    }

    private fun measureWaveformFrames(): JSONObject {
        compose.runOnUiThread {
            compose.activity.setContent {
                AndroidDjToolsTheme { WaveformScreen(FixtureAppProviders.create()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("waveform-detail").assertExists()

        // Warm the surface before collecting timings so class loading/layout startup is not
        // mislabeled as gesture-frame performance.
        compose.onNodeWithTag("waveform-detail").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("waveform-detail").performTouchInput { swipeRight() }
        compose.waitForIdle()

        val durationsNs = Collections.synchronizedList(mutableListOf<Long>())
        val metricsThread = HandlerThread("androidjtools-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) durationsNs += duration
        }
        compose.runOnUiThread {
            compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
        }

        try {
            repeat(WAVEFORM_GESTURE_COUNT) { index ->
                compose.onNodeWithTag("waveform-detail").performTouchInput {
                    if (index % 2 == 0) swipeLeft() else swipeRight()
                }
            }
            compose.waitForIdle()
            // FrameMetrics callbacks are asynchronous to Compose idleness.
            SystemClock.sleep(250)
        } finally {
            compose.runOnUiThread {
                compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
            metricsThread.quitSafely()
        }

        val frameMs = synchronized(durationsNs) { durationsNs.map { it / 1_000_000.0 } }
        assertTrue("expected at least $MIN_FRAME_SAMPLES frame samples, got ${frameMs.size}", frameMs.size >= MIN_FRAME_SAMPLES)
        val p95Ms = percentile95(frameMs)
        val slowRatio = frameMs.count { it > SLOW_FRAME_MS }.toDouble() / frameMs.size

        assertTrue(
            "waveform p95 frame ${"%.1f".format(p95Ms)} ms exceeded $MAX_WAVEFORM_P95_MS ms",
            p95Ms <= MAX_WAVEFORM_P95_MS,
        )
        assertTrue(
            "waveform slow-frame ratio ${"%.3f".format(slowRatio)} exceeded $MAX_SLOW_FRAME_RATIO",
            slowRatio <= MAX_SLOW_FRAME_RATIO,
        )

        return JSONObject()
            .put("gesture_count", WAVEFORM_GESTURE_COUNT)
            .put("frame_samples", frameMs.size)
            .put("frame_p95_ms", p95Ms)
            .put("slow_frame_threshold_ms", SLOW_FRAME_MS)
            .put("slow_frame_ratio", slowRatio)
            .put("threshold_p95_ms", MAX_WAVEFORM_P95_MS)
            .put("threshold_slow_frame_ratio", MAX_SLOW_FRAME_RATIO)
            .put("status", "pass")
    }

    private fun elapsedMs(startNs: Long): Double = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0

    private fun percentile95(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private class StaticLibraryProvider(items: List<Track>) : LibraryProvider {
        override val tracks: StateFlow<List<Track>> = MutableStateFlow(items)
        override fun track(id: String): Track? = tracks.value.firstOrNull { it.id == id }
    }

    companion object {
        const val EVIDENCE_FILE = "epic17-performance.json"
        private const val LIBRARY_SIZE = 10_000
        private const val MAX_LIBRARY_INITIAL_SETTLE_MS = 3_000.0
        private const val MAX_LIBRARY_SEARCH_P95_MS = 1_500.0
        private const val WAVEFORM_GESTURE_COUNT = 12
        private const val MIN_FRAME_SAMPLES = 20
        private const val SLOW_FRAME_MS = 34.0
        private const val MAX_WAVEFORM_P95_MS = 75.0
        private const val MAX_SLOW_FRAME_RATIO = 0.25
    }
}
