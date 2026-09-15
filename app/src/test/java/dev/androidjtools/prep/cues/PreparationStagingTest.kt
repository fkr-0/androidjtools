package dev.androidjtools.prep.cues

import dev.androidjtools.core.model.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparationStagingTest {
    @Test
    fun snapMatchesBeatAnchorAndFraction() {
        val grid = BeatGrid("track", anchorMs = 125L, bpm = 120.0, revision = 7L)

        assertEquals(625L, snapPreparationTime(810L, 10_000L, grid, PreparationSnapMode.BEAT))
        assertEquals(875L, snapPreparationTime(810L, 10_000L, grid, PreparationSnapMode.HALF_BEAT))
        assertEquals(810L, snapPreparationTime(810L, 10_000L, grid, PreparationSnapMode.OFF))
    }

    @Test
    fun invalidOrAbsentGridFallsBackToExactRequestedPrecision() {
        assertEquals(811L, snapPreparationTime(811L, 10_000L, null, PreparationSnapMode.BEAT))
        assertEquals(
            811L,
            snapPreparationTime(811L, 10_000L, BeatGrid("track", 0L, 0.0, 1L), PreparationSnapMode.BEAT),
        )
    }

    @Test
    fun colorsAreNormalizedDeterministically() {
        assertEquals("#AABBCC", normalizePreparationColor(" #aabbcc "))
        assertEquals("#FFAABBCC", normalizePreparationColor("#ffaabbcc"))
    }
}
