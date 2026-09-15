package dev.androidjtools.prep.loops

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.prep.cues.EditorMarkerDraft
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationMutationStager
import dev.androidjtools.prep.cues.PreparationSnapMode
import dev.androidjtools.prep.cues.PreparationStageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopEditorTest {
    private val durationMs = 120_000L

    private fun aggregate(id: String, startMs: Long, endMs: Long) = IntervalEditorStateContext(
        intervalId = id,
        baseRevision = "revision-$id",
        startMs = startMs,
        endMs = endMs,
        markers = listOf(EditorMarkerDraft("marker-$id", startMs + 100L, "kept", "cue", "#00FF00")),
        completeMarkerSet = true,
    )

    @Test
    fun createMoveAndResizeUseBeatAwareSnapping() {
        val grid = BeatGrid("track", anchorMs = 0L, bpm = 120.0, revision = 1L)
        var editor = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = emptyList(),
            beatGrid = grid,
            snapMode = PreparationSnapMode.BEAT,
        )

        editor = editor.create("local", 10_230L, 14_770L).editor
        var loop = editor.current.single()
        assertEquals(10_000L, loop.startMs)
        assertEquals(15_000L, loop.endMs)

        editor = editor.move("local", 20_260L).editor
        loop = editor.current.single()
        assertEquals(20_500L, loop.startMs)
        assertEquals(25_500L, loop.endMs)

        editor = editor.resizeStart("local", 21_240L).editor
        editor = editor.resizeEnd("local", 27_260L).editor
        loop = editor.current.single()
        assertEquals(21_000L, loop.startMs)
        assertEquals(27_500L, loop.endMs)
    }

    @Test
    fun absentGridKeepsUnsnappedLoopPrecision() {
        val editor = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = emptyList(),
            beatGrid = null,
            snapMode = PreparationSnapMode.BEAT,
        ).create("local", 12_345L, 15_678L).editor

        assertEquals(12_345L, editor.current.single().startMs)
        assertEquals(15_678L, editor.current.single().endMs)
    }

    @Test
    fun overlappingCreateAndMoveAreRejectedDeterministically() {
        val initial = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = listOf(
                LoopDraft("a", 10_000L, 20_000L),
                LoopDraft("b", 30_000L, 40_000L),
            ),
        )

        val create = initial.create("c", 19_000L, 31_000L)
        assertEquals(LoopValidationCode.OVERLAP, create.error)
        assertEquals(initial, create.editor)

        val move = initial.move("b", 15_000L)
        assertEquals(LoopValidationCode.OVERLAP, move.error)
        assertEquals(initial, move.editor)
    }

    @Test
    fun tooShortOrReversedLoopCreationIsRejectedInsteadOfInventingBounds() {
        val editor = LoopEditorState.create("track", durationMs, emptyList())

        assertEquals(LoopValidationCode.TOO_SHORT, editor.create("short", 10_000L, 10_020L).error)
        assertEquals(LoopValidationCode.TOO_SHORT, editor.create("reversed", 12_000L, 11_000L).error)
    }

    @Test
    fun activateIsExclusiveAndDeactivateIsExplicit() {
        var editor = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = listOf(LoopDraft("a", 10_000L, 20_000L), LoopDraft("b", 30_000L, 40_000L)),
        )
        editor = editor.activate("a").editor
        assertTrue(editor.current.first { it.localId == "a" }.active)
        assertFalse(editor.current.first { it.localId == "b" }.active)

        editor = editor.activate("b").editor
        assertFalse(editor.current.first { it.localId == "a" }.active)
        assertTrue(editor.current.first { it.localId == "b" }.active)

        editor = editor.deactivate("b").editor
        assertFalse(editor.current.any { it.active })
    }

    @Test
    fun renameRecolorRoleDeleteUndoAndCancelAreRecoverable() {
        val initial = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = listOf(LoopDraft("a", 10_000L, 20_000L, label = "A")),
        )
        var editor = initial.rename("a", "Mix").editor
        editor = editor.recolor("a", "#aa33cc").editor
        editor = editor.setRole("a", PreparationLoopRole.MIX_OUT).editor
        assertEquals("Mix", editor.current.single().label)
        assertEquals("#AA33CC", editor.current.single().colorHex)
        assertEquals(PreparationLoopRole.MIX_OUT, editor.current.single().role)

        val pending = editor.requestDelete("a").editor
        val deleted = pending.confirmDelete().editor
        assertTrue(deleted.current.isEmpty())
        assertEquals(editor.current, deleted.undo().current)
        assertEquals(initial.current, editor.cancelStaging().current)
    }

    @Test
    fun snapshotRestoresStagedLoopAndHistory() {
        val initial = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = listOf(LoopDraft("a", 10_000L, 20_000L)),
        )
        val edited = initial.move("a", 12_345L).editor.requestDelete("a").editor

        val restored = LoopEditorState.restore("track", durationMs, null, edited.snapshot())

        assertEquals(edited.current, restored.current)
        assertEquals(edited.undoStack, restored.undoStack)
        assertEquals("a", restored.pendingDeleteId)
    }

    @Test
    fun existingCanonicalLoopStagesAggregateBoundaryReplaceAndPreservesMarkers() {
        val context = aggregate("loop-a", 10_000L, 20_000L)
        val editor = LoopEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            loops = listOf(LoopDraft("loop-a", 10_000L, 20_000L, aggregate = context)),
        ).resizeEnd("loop-a", 22_000L).editor

        var captured: IntervalEditorStateReplaceIntent? = null
        val result = editor.stageLoop(
            localId = "loop-a",
            mutationId = "mutation-loop-0001",
            createdAtEpochMillis = 44L,
            stager = PreparationMutationStager { intent ->
                captured = intent
                PreparationStageResult.Queued(intent.mutationId)
            },
        )

        assertEquals(PreparationStageResult.Queued("mutation-loop-0001"), result)
        val intent = requireNotNull(captured)
        assertEquals("interval.editor_state.replace", intent.operation)
        assertEquals("revision-loop-a", intent.baseRevision)
        assertEquals(10_000L, intent.payload.startMs)
        assertEquals(22_000L, intent.payload.endMs)
        assertEquals("marker-loop-a", intent.payload.markers.single().id)
    }

    @Test
    fun locallyCreatedLoopDoesNotInventUnsupportedIntervalCreateMutation() {
        val editor = LoopEditorState.create("track", durationMs, emptyList())
            .create("local-new", 10_000L, 20_000L).editor

        val result = editor.stageLoop("local-new", "mutation-loop-0002", 44L, PreparationMutationStager {
            error("stager must not be called for unsupported interval creation")
        })

        assertEquals(PreparationStageResult.RequiresCanonicalCreate("local-new"), result)
    }

    @Test
    fun metadataOnlyLoopEditsAreNotMisrepresentedAsCanonicalWrites() {
        val context = aggregate("loop-a", 10_000L, 20_000L)
        val editor = LoopEditorState.create(
            "track",
            durationMs,
            listOf(LoopDraft("loop-a", 10_000L, 20_000L, aggregate = context)),
        ).rename("loop-a", "Local role label").editor

        val result = editor.stageLoop("loop-a", "mutation-loop-0003", 44L, PreparationMutationStager {
            error("stager must not be called for metadata-only local staging")
        })

        assertEquals("no_supported_canonical_change", (result as PreparationStageResult.Rejected).code)
    }

    @Test
    fun canonicalLoopDeleteIsExplicitlyGatedByMissingProtocolOperation() {
        val context = aggregate("loop-a", 10_000L, 20_000L)
        val editor = LoopEditorState.create(
            "track",
            durationMs,
            listOf(LoopDraft("loop-a", 10_000L, 20_000L, aggregate = context)),
        ).requestDelete("loop-a").editor.confirmDelete().editor

        assertEquals(PreparationStageResult.RequiresCanonicalDelete("loop-a"), editor.stageDeletedLoop("loop-a"))
    }
}
