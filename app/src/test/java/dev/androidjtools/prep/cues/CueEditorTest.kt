package dev.androidjtools.prep.cues

import dev.androidjtools.core.model.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueEditorTest {
    private val durationMs = 120_000L
    private val aggregate = IntervalEditorStateContext(
        intervalId = "interval-main",
        baseRevision = "opaque-revision-1",
        startMs = 10_000L,
        endMs = 90_000L,
        markers = listOf(
            EditorMarkerDraft("cue-a", 20_000L, "Intro", "cue", "#FF0000"),
            EditorMarkerDraft("foreign", 30_000L, "Transcript", "transcript", null, "analysis"),
        ),
        completeMarkerSet = true,
    )

    @Test
    fun createMoveRenameRecolorRenumberAndRoleAreDeterministic() {
        val grid = BeatGrid("track", anchorMs = 0L, bpm = 120.0, revision = 1L)
        var editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L, number = 1)),
            beatGrid = grid,
            snapMode = PreparationSnapMode.BEAT,
        )

        editor = editor.create(
            localId = "local-b",
            requestedPositionMs = 21_260L,
            label = "Drop",
            colorHex = "#00aaee",
            number = 2,
            role = HotCueRole.DROP,
        ).editor
        assertEquals(21_500L, editor.current.first { it.localId == "local-b" }.positionMs)

        editor = editor.move("local-b", 22_760L).editor
        editor = editor.rename("local-b", "Main drop").editor
        editor = editor.recolor("local-b", "#aabbcc").editor
        editor = editor.renumber("local-b", 5).editor
        editor = editor.setRole("local-b", HotCueRole.MIX_IN).editor

        val edited = editor.current.first { it.localId == "local-b" }
        assertEquals(23_000L, edited.positionMs)
        assertEquals("Main drop", edited.label)
        assertEquals("#AABBCC", edited.colorHex)
        assertEquals(5, edited.number)
        assertEquals(HotCueRole.MIX_IN, edited.role)
        assertTrue(editor.dirty)
    }

    @Test
    fun noGridPreservesUnsnappedMillisecondPrecision() {
        val editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = emptyList(),
            beatGrid = null,
            snapMode = PreparationSnapMode.BEAT,
        ).create("precise", 12_345L).editor

        assertEquals(12_345L, editor.current.single().positionMs)
    }

    @Test
    fun duplicateHotCueNumbersAreRejectedWithoutChangingState() {
        val editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L, number = 1)),
        )

        val result = editor.create("cue-b", 25_000L, number = 1)

        assertEquals(CueValidationCode.DUPLICATE_NUMBER, result.error)
        assertEquals(editor, result.editor)
    }

    @Test
    fun deleteRequiresConfirmationAndUndoRestoresPreviousSnapshot() {
        val initial = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L)),
        )
        val pending = initial.requestDelete("cue-a").editor
        assertEquals("cue-a", pending.pendingDeleteId)
        assertEquals(1, pending.current.size)

        val deleted = pending.confirmDelete().editor
        assertTrue(deleted.current.isEmpty())
        assertTrue(deleted.canUndo)

        val undone = deleted.undo()
        assertEquals(initial.current, undone.current)
        assertNull(undone.pendingDeleteId)
    }

    @Test
    fun cancelStagingReturnsExactlyToBaseline() {
        val initial = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L, label = "A")),
        )
        val edited = initial.rename("cue-a", "Changed").editor.create("local", 40_000L).editor

        val cancelled = edited.cancelStaging()

        assertEquals(initial.current, cancelled.current)
        assertFalse(cancelled.dirty)
        assertFalse(cancelled.canUndo)
    }

    @Test
    fun snapshotRestoresStagedStateAndHistory() {
        val initial = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L)),
        )
        val edited = initial.rename("cue-a", "Edited").editor.requestDelete("cue-a").editor

        val restored = CueEditorState.restore(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            beatGrid = null,
            snapshot = edited.snapshot(),
        )

        assertEquals(edited.current, restored.current)
        assertEquals(edited.undoStack, restored.undoStack)
        assertEquals("cue-a", restored.pendingDeleteId)
    }

    @Test
    fun stageProducesOpaqueRevisionAggregateReplaceAndPreservesForeignMarkers() {
        val editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L, label = "Intro")),
        ).rename("cue-a", "Edited intro").editor

        var captured: IntervalEditorStateReplaceIntent? = null
        val result = editor.stage(
            mutationId = "mutation-cue-0001",
            createdAtEpochMillis = 1234L,
            stager = PreparationMutationStager { intent ->
                captured = intent
                PreparationStageResult.Queued(intent.mutationId)
            },
        )

        assertEquals(PreparationStageResult.Queued("mutation-cue-0001"), result)
        val intent = requireNotNull(captured)
        assertEquals("interval.editor_state.replace", intent.operation)
        assertEquals("interval", intent.entityType)
        assertEquals("interval-main", intent.entityId)
        assertEquals("opaque-revision-1", intent.baseRevision)
        assertEquals("Edited intro", intent.payload.markers.first { it.id == "cue-a" }.label)
        assertEquals("Transcript", intent.payload.markers.first { it.id == "foreign" }.label)
    }

    @Test
    fun stageFailsClosedWhenCanonicalMarkerSetIsIncomplete() {
        val incomplete = aggregate.copy(completeMarkerSet = false)
        val editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = incomplete,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L)),
        ).rename("cue-a", "Changed").editor

        val result = editor.stage("mutation-cue-0002", 1234L, PreparationMutationStager {
            error("stager must not be called")
        })

        assertTrue(result is PreparationStageResult.Rejected)
        assertEquals("incomplete_editor_aggregate", (result as PreparationStageResult.Rejected).code)
    }

    @Test
    fun numberOnlyEditStaysLocalRatherThanClaimingCanonicalPersistence() {
        val editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L, label = "Intro", number = 1)),
        ).renumber("cue-a", 8).editor

        val result = editor.stage("mutation-cue-0004", 1234L, PreparationMutationStager {
            error("stager must not be called for a local-only number change")
        })

        assertEquals("no_supported_canonical_change", (result as PreparationStageResult.Rejected).code)
        assertEquals(8, editor.current.single().number)
    }

    @Test
    fun canonicalHalfOpenIntervalIsValidatedBeforeStaging() {
        val editor = CueEditorState.create(
            trackId = "track",
            durationMs = durationMs,
            aggregate = aggregate,
            cues = listOf(HotCueDraft("cue-a", "cue-a", 20_000L)),
        ).move("cue-a", 90_000L).editor

        val result = editor.stage("mutation-cue-0003", 1234L, PreparationMutationStager {
            error("stager must not be called")
        })

        assertEquals("outside_canonical_interval", (result as PreparationStageResult.Rejected).code)
    }
}
