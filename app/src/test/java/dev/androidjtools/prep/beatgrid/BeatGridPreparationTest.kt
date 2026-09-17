package dev.androidjtools.prep.beatgrid

import dev.androidjtools.core.model.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatGridPreparationTest {
    private val canonical = BeatGrid("track", anchorMs = 125L, bpm = 92.5, revision = 7L)

    @Test
    fun beatIndexAndTimeRoundTripAcrossNegativeAndPositiveIndices() {
        for (index in -256L..256L) {
            val time = beatTimeMs(canonical, index)
            assertEquals("index=$index time=$time", index, nearestBeatIndex(canonical, time))
        }
    }

    @Test
    fun halfAndDoubleBpmAreExplicitAndReversible() {
        val initial = BeatGridPreparationState(canonical)
        val halved = initial.halfBpm()
        val restored = halved.doubleBpm()

        assertEquals(46.25, halved.staged.bpm, 0.000001)
        assertEquals(canonical.anchorMs, halved.staged.anchorMs)
        assertTrue(halved.dirty)
        assertEquals(canonical.bpm, restored.staged.bpm, 0.000001)
        assertEquals(canonical.anchorMs, restored.staged.anchorMs)
        assertFalse(restored.dirty)
        assertTrue(restored.canUndo)
    }

    @Test
    fun transformsFailClosedAtEditableTempoBounds() {
        val low = BeatGridPreparationState(canonical.copy(bpm = 30.0))
        val high = BeatGridPreparationState(canonical.copy(bpm = 180.0))

        assertFalse(low.canHalfBpm)
        assertEquals(30.0, low.halfBpm().staged.bpm, 0.0)
        assertFalse(high.canDoubleBpm)
        assertEquals(180.0, high.doubleBpm().staged.bpm, 0.0)
    }

    @Test
    fun downbeatAndPhaseNudgesAreBoundedAndUndoable() {
        val state = BeatGridPreparationState(canonical)
            .setFirstDownbeat(2_000L, durationMs = 10_000L)
            .nudgePhase(-10L, durationMs = 10_000L)

        assertEquals(1_990L, state.staged.anchorMs)
        assertEquals(2_000L, state.undo().staged.anchorMs)
        assertEquals(0L, state.setFirstDownbeat(-999L, 10_000L).staged.anchorMs)
        assertEquals(10_000L, state.setFirstDownbeat(12_000L, 10_000L).staged.anchorMs)
    }

    @Test
    fun acceptingCandidateChangesOnlyStagedPreviewUntilCommitIntent() {
        val candidate = BeatGridCandidate(
            id = "analysis-bpm-1",
            trackId = canonical.trackId,
            bpm = 128.0,
            anchorMs = 250L,
            source = "sample-intelligence",
            confidence = 0.91,
        )
        val state = BeatGridPreparationState(canonical, candidate = candidate).acceptCandidate()

        assertEquals(92.5, state.canonical.bpm, 0.0)
        assertEquals(125L, state.canonical.anchorMs)
        assertEquals(128.0, state.staged.bpm, 0.0)
        assertEquals(250L, state.staged.anchorMs)

        val intent = state.commitIntent()
        assertEquals("track", intent.trackId)
        assertEquals(7L, intent.baseRevision)
        assertEquals(128.0, intent.bpm, 0.0)
        assertEquals(250L, intent.anchorMs)
        assertEquals("beatgrid.replace", intent.operation)
    }

    @Test
    fun cancelAndMultiStepUndoNeverMutateCanonicalState() {
        val edited = BeatGridPreparationState(canonical)
            .setBpm(120.0)
            .setFirstDownbeat(500L, 10_000L)

        assertEquals(120.0, edited.undo().staged.bpm, 0.0)
        assertEquals(canonical.anchorMs, edited.undo().staged.anchorMs)

        val cancelled = edited.cancel()
        assertEquals(canonical, cancelled.canonical)
        assertEquals(canonical, cancelled.staged)
        assertFalse(cancelled.dirty)
        assertFalse(cancelled.canUndo)
    }

    @Test
    fun typedSnapshotRestoresOnlyAgainstMatchingCanonicalRevision() {
        val candidate = BeatGridCandidate("candidate", "track", 128.0, 300L, "fixture")
        val edited = BeatGridPreparationState(canonical, candidate = candidate)
            .setBpm(100.0)
            .nudgePhase(25L, 10_000L)
        val snapshot = edited.snapshot()

        val restored = BeatGridPreparationState.restore(canonical, snapshot)
        assertEquals(edited, restored)
        assertNull(BeatGridPreparationState.restore(canonical.copy(revision = 8L), snapshot))
    }
}
