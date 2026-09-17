package dev.androidjtools.ui.waveform

import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformModelTest {
    private val duration = 240_000L

    @Test
    fun zoomKeepsFocalTimeStable() {
        val initial = WaveformViewport(zoom = 2.0, startMs = 30_000L)
        val before = initial.timeAtFraction(duration, 0.25f)
        val zoomed = initial.zoomBy(duration, scale = 2.0, focalFraction = 0.25f)
        val after = zoomed.timeAtFraction(duration, 0.25f)

        assertTrue(kotlin.math.abs(before - after) <= 1L)
        assertEquals(4.0, zoomed.zoom, 0.0001)
    }

    @Test
    fun viewportFractionRoundTripsTime() {
        val viewport = WaveformViewport(zoom = 4.0, startMs = 60_000L)
        val fraction = viewport.fractionForTime(duration, 90_000L)

        assertEquals(0.5f, fraction, 0.0001f)
        assertEquals(90_000L, viewport.timeAtFraction(duration, fraction))
    }

    @Test
    fun panAndScrubClampToTrackBounds() {
        val left = WaveformViewport(zoom = 4.0, startMs = 0L).panByFraction(duration, -3f)
        val right = WaveformViewport(zoom = 4.0, startMs = 180_000L).panByFraction(duration, 3f)

        assertEquals(0L, left.startMs)
        assertEquals(180_000L, right.startMs)
        assertEquals(240_000L, right.timeAtFraction(duration, 1f))
    }

    @Test
    fun beatGridOnlyReturnsVisibleBeats() {
        val viewport = WaveformViewport(zoom = 4.0, startMs = 60_000L)
        val beats = beatPositions(BeatGrid("track", anchorMs = 0L, bpm = 120.0, revision = 1L), viewport, duration)

        assertEquals(121, beats.size)
        assertEquals(60_000L, beats.first())
        assertEquals(120_000L, beats.last())
    }

    @Test
    fun quantizeSnapsToConfiguredBeatFraction() {
        val grid = BeatGrid("track", anchorMs = 125L, bpm = 120.0, revision = 1L)

        assertEquals(625L, snapTime(810L, grid, QuantizeMode.BEAT, duration))
        assertEquals(875L, snapTime(810L, grid, QuantizeMode.HALF_BEAT, duration))
        assertEquals(810L, snapTime(810L, grid, QuantizeMode.OFF, duration))
    }

    @Test
    fun beatJumpUsesGridTempoAndClampsAtBounds() {
        val grid = BeatGrid("track", anchorMs = 0L, bpm = 120.0, revision = 1L)

        assertEquals(3_000L, jumpByBeats(1_000L, grid, 4, duration))
        assertEquals(0L, jumpByBeats(1_000L, grid, -4, duration))
        assertEquals(duration, jumpByBeats(duration - 250L, grid, 4, duration))
    }

    @Test
    fun overlaysKeepCanonicalAndCandidateSourcesDistinct() {
        val overlays = buildWaveformOverlays(
            cues = listOf(Cue("cue-1", "track", 10_000L, label = "Mix in")),
            loops = listOf(Loop("loop-1", "track", 20_000L, 28_000L, label = "Drums")),
            suggestions = listOf(
                AnalysisSuggestion(
                    id = "candidate-cue",
                    trackId = "track",
                    kind = SuggestionKind.CUE,
                    payload = SuggestionPayload.Point(30_000L, "drop", "Detected drop"),
                    source = IntelligenceSource("fixture"),
                ),
                AnalysisSuggestion(
                    id = "candidate-range",
                    trackId = "track",
                    kind = SuggestionKind.REGION,
                    payload = SuggestionPayload.Range(40_000L, 48_000L, "phrase", "Phrase"),
                    source = IntelligenceSource("fixture"),
                ),
            ),
        )

        assertEquals(WaveformOverlaySource.CANONICAL, overlays.first { it.id == "cue-1" }.source)
        assertEquals(WaveformOverlaySource.CANONICAL, overlays.first { it.id == "loop-1" }.source)
        assertEquals(WaveformOverlaySource.CANDIDATE, overlays.first { it.id == "candidate-cue" }.source)
        assertEquals(WaveformOverlaySource.CANDIDATE, overlays.first { it.id == "candidate-range" }.source)
    }

    @Test
    fun stagedRangeEditingQuantizesAndPreservesMinimumLength() {
        val overlay = WaveformOverlay(
            id = "loop-1",
            startMs = 20_000L,
            endMs = 28_000L,
            label = "Drums",
            kind = WaveformOverlayKind.LOOP,
            source = WaveformOverlaySource.CANONICAL,
        )
        val grid = BeatGrid("track", anchorMs = 0L, bpm = 120.0, revision = 1L)
        val initial = requireNotNull(StagedRangeEdit.from(overlay))
        val movedStart = updateRangeHandle(initial, RangeHandle.START, 20_520L, grid, QuantizeMode.BEAT, duration)
        val movedEnd = updateRangeHandle(movedStart, RangeHandle.END, 27_030L, grid, QuantizeMode.BEAT, duration)
        val collapsed = updateRangeHandle(movedEnd, RangeHandle.START, 27_000L, grid, QuantizeMode.OFF, duration)

        assertEquals(20_500L, movedStart.startMs)
        assertEquals(27_000L, movedEnd.endMs)
        assertEquals(movedEnd.endMs - 40L, collapsed.startMs)
        assertTrue(movedEnd.dirty)
    }

    @Test
    fun editHistorySupportsUndoAndDiscardStyleReset() {
        val initial = StagedRangeEdit("loop", "Loop", 20_000L, 28_000L)
        val first = initial.copy(startMs = 20_500L)
        val second = first.copy(endMs = 27_500L)
        val history = EditHistory(initial).update(first).update(second)

        assertTrue(history.canUndo)
        assertEquals(first, history.undo().current)
        assertEquals(initial, history.current.reset())
        assertFalse(initial.dirty)
    }

    @Test
    fun gridPrecisionControlsClampAndNudgeDeterministically() {
        val initial = StagedGridEdit.from(BeatGrid("track", anchorMs = 125L, bpm = 92.5, revision = 7L))
        val highBpm = updateGridBpm(initial, 400.0)
        val lowAnchor = updateGridAnchor(highBpm, -10L, duration)
        val nudged = nudgeGridAnchorByBeats(updateGridBpm(lowAnchor, 120.0), 1, duration)

        assertEquals(300.0, highBpm.bpm, 0.0001)
        assertEquals(0L, lowAnchor.anchorMs)
        assertEquals(500L, nudged.anchorMs)
        assertTrue(nudged.dirty)
    }

    @Test
    fun gridHalfAndDoubleBpmAreReversibleAndFailClosedAtBounds() {
        val initial = StagedGridEdit.from(BeatGrid("track", anchorMs = 125L, bpm = 92.5, revision = 7L))
        val halved = scaleGridBpm(initial, 0.5)
        val restored = scaleGridBpm(halved, 2.0)

        assertEquals(46.25, halved.bpm, 0.000001)
        assertEquals(initial.anchorMs, halved.anchorMs)
        assertEquals(initial.revision, halved.revision)
        assertEquals(initial.bpm, restored.bpm, 0.000001)
        assertEquals(initial.anchorMs, restored.anchorMs)
        assertEquals(initial.revision, restored.revision)

        val tooLow = initial.copy(bpm = 30.0)
        val tooHigh = initial.copy(bpm = 180.0)
        assertEquals(null, transformedGridBpm(tooLow, 0.5))
        assertEquals(tooLow, scaleGridBpm(tooLow, 0.5))
        assertEquals(null, transformedGridBpm(tooHigh, 2.0))
        assertEquals(tooHigh, scaleGridBpm(tooHigh, 2.0))
        assertEquals(null, transformedGridBpm(initial, Double.NaN))
        assertEquals(null, transformedGridBpm(initial, 0.0))
    }

    @Test
    fun editableGridBpmFormattingUsesDotDecimalUnderGermanLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("92.50", formatGridBpm(92.5))
            assertEquals("128.00", formatGridBpm(128.0))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun applyingStagedRangeOnlyChangesMatchingCanonicalRange() {
        val canonical = WaveformOverlay("loop", 20_000L, 28_000L, "Loop", WaveformOverlayKind.LOOP, WaveformOverlaySource.CANONICAL)
        val candidate = WaveformOverlay("candidate", 20_000L, 28_000L, "Candidate", WaveformOverlayKind.RANGE, WaveformOverlaySource.CANDIDATE)
        val edit = requireNotNull(StagedRangeEdit.from(canonical)).copy(startMs = 21_000L)
        val applied = applyStagedRange(listOf(canonical, candidate), edit)

        assertEquals(21_000L, applied.first { it.id == "loop" }.startMs)
        assertEquals(20_000L, applied.first { it.id == "candidate" }.startMs)
    }

    @Test
    fun selectionPrefersClosestOverlayAndSupportsRanges() {
        val overlays = listOf(
            WaveformOverlay("point", 10_000L, label = "point", kind = WaveformOverlayKind.CUE, source = WaveformOverlaySource.CANONICAL),
            WaveformOverlay("range", 20_000L, 30_000L, "range", WaveformOverlayKind.LOOP, WaveformOverlaySource.CANONICAL),
        )

        assertEquals("point", selectOverlayAt(overlays, 10_060L, 100L)?.id)
        assertEquals("range", selectOverlayAt(overlays, 25_000L, 0L)?.id)
        assertEquals(null, selectOverlayAt(overlays, 15_000L, 100L))
    }

    @Test
    fun advisoryDensityCapNeverHidesSafetyWarnings() {
        val advisories = listOf(
            WaveformAdvisory("suggestion-1", "BPM candidate 92.00"),
            WaveformAdvisory("suggestion-2", "Key candidate Am"),
            WaveformAdvisory("suggestion-3", "BPM candidate 92.25"),
            WaveformAdvisory("suggestion-4", "Key candidate C"),
            WaveformAdvisory("warning-1", "Clipping risk", warning = true),
            WaveformAdvisory("warning-2", "Spectral outlier", warning = true),
        )

        val visible = visibleWaveformAdvisories(advisories, maxSuggestions = 3)

        assertEquals(
            listOf("suggestion-1", "suggestion-2", "suggestion-3", "warning-1", "warning-2"),
            visible.map { it.id },
        )
        assertEquals(3, visible.count { !it.warning })
        assertEquals(2, visible.count { it.warning })
    }

    @Test
    fun deterministicWaveformVariesByTrackAndSample() {
        val a = waveformAmplitude("track-a", 42)
        val again = waveformAmplitude("track-a", 42)
        val otherSample = waveformAmplitude("track-a", 43)
        val otherTrack = waveformAmplitude("track-b", 42)

        assertEquals(a, again, 0f)
        assertNotEquals(a, otherSample)
        assertNotEquals(a, otherTrack)
        assertTrue(a in 0.16f..0.94f)
    }
}
