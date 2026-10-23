package dev.androidjtools.ui.waveform

import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.Track
import dev.androidjtools.prep.cues.CueEditResult
import dev.androidjtools.prep.cues.CueEditorState
import dev.androidjtools.prep.cues.CueValidationCode
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationMutationStager
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.prep.loops.LoopEditorState
import dev.androidjtools.prep.loops.LoopValidationCode

sealed interface EditBuild {
    data class Ready(val intent: IntervalEditorStateReplaceIntent) : EditBuild
    data class Refused(val reason: String) : EditBuild
}

/**
 * Turns a cue or loop gesture on the Preparation screen into a replace intent, or says why it cannot be one.
 * Pure: it only builds the intent. Saving it (disk I/O) is the edit provider's job, off the main thread.
 */
class ServerEditPlanner(
    private val track: Track,
    private val cues: List<Cue>,
    private val loops: List<Loop>,
    private val aggregates: List<IntervalEditorStateContext>,
    private val nowMs: () -> Long,
    private val newMutationId: () -> String,
) {
    fun addCue(positionMs: Long): EditBuild {
        val aggregate = aggregates.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
            ?: return EditBuild.Refused("No synced section covers this position.")
        return editCues(aggregate) { it.create(localId = "new-cue-$positionMs", requestedPositionMs = positionMs) }
    }

    /**
     * Turns an analysis run's cue candidates into cues, all in one edit of one synced section, through the same
     * editor-state path as a cue made by hand. The provenance says the cues came from analysis and from which run.
     * Positions that already hold a cue are left alone. Refused (with the reason) when no single synced section
     * covers every candidate: the edit would otherwise have to be split or silently drop some.
     */
    fun addCues(pointsMs: List<Long>, analysisRunId: String?): EditBuild {
        val points = pointsMs.distinct().sorted()
        if (points.isEmpty()) return EditBuild.Refused("There are no cue points to add.")
        val aggregate = aggregates.firstOrNull { section -> points.all { it >= section.startMs && it < section.endMs } }
            ?: return EditBuild.Refused(
                if (points.any { p -> aggregates.any { p >= it.startMs && p < it.endMs } }) {
                    "These cue points are not all in one synced section, so they cannot be added in one edit."
                } else {
                    "No synced section covers these cue points."
                },
            )
        val ofInterval = cues.filter { it.intervalId == aggregate.intervalId }
        val fresh = points.filter { point -> ofInterval.none { it.positionMs == point } }
        if (fresh.isEmpty()) return EditBuild.Refused("These cue points are already cues.")
        var editor = try {
            CueEditorState.fromDomain(track.id, track.durationMs, aggregate, ofInterval)
        } catch (_: IllegalArgumentException) {
            return EditBuild.Refused("These cues cannot be edited.")
        }
        for (point in fresh) {
            val result = editor.create(localId = "analysis-cue-$point", requestedPositionMs = point)
            if (!result.accepted) return EditBuild.Refused(describe(result.error!!))
            editor = result.editor
        }
        val provenance = buildMap {
            put("source", "analysis")
            put("surface", "analysis_cues")
            analysisRunId?.let { put("analysis_run_id", it) }
        }
        val capture = Capture()
        return capture.finish(editor.stage(newMutationId(), nowMs(), capture, "Android DJ analysis cue candidates", provenance))
    }

    fun moveCue(cueId: String, positionMs: Long): EditBuild {
        val aggregate = aggregateOfCue(cueId) ?: return unavailableCue(cueId)
        return editCues(aggregate) { it.move(cueId, positionMs) }
    }

    fun deleteCue(cueId: String): EditBuild {
        val aggregate = aggregateOfCue(cueId) ?: return unavailableCue(cueId)
        return editCues(aggregate) { it.requestDelete(cueId).editor.confirmDelete() }
    }

    fun saveLoop(loopId: String, startMs: Long, endMs: Long): EditBuild {
        val loop = loops.firstOrNull { it.id == loopId } ?: return EditBuild.Refused("That loop is no longer there.")
        if (loop.intervalId == null || loop.id != loop.intervalId) {
            return EditBuild.Refused("This loop is made from two markers, which the app cannot change yet.")
        }
        val aggregate = aggregates.firstOrNull { it.intervalId == loop.id }
            ?: return EditBuild.Refused("The server's version of this loop is not available.")
        if (startMs < 0L || endMs > track.durationMs || endMs <= startMs) {
            return EditBuild.Refused("The loop has to stay inside the track.")
        }
        if (aggregate.markers.any { it.positionMs < startMs || it.positionMs >= endMs }) {
            return EditBuild.Refused("Some markers would fall outside the new bounds. Move or delete them first.")
        }
        // Only this loop goes to the editor: a loop made from two markers always sits inside its interval's loop,
        // and the server has no rule against overlapping intervals, so the editor's overlap check does not apply.
        val editor = try {
            LoopEditorState.fromDomain(track.id, track.durationMs, listOf(loop), mapOf(loop.id to aggregate))
        } catch (_: IllegalArgumentException) {
            return EditBuild.Refused("This loop cannot be edited.")
        }
        // Moving one edge past the other can be refused in one order and fine in the other, so try both.
        val attempts = listOf(true, false).map { startFirst ->
            val first = if (startFirst) editor.resizeStart(loop.id, startMs) else editor.resizeEnd(loop.id, endMs)
            when {
                !first.accepted -> first
                startFirst -> first.editor.resizeEnd(loop.id, endMs)
                else -> first.editor.resizeStart(loop.id, startMs)
            }
        }
        val resized = attempts.firstOrNull { it.accepted }
            ?: return EditBuild.Refused("Those bounds are not allowed: ${describe(attempts.last().error ?: LoopValidationCode.TOO_SHORT)}.")

        val capture = Capture()
        return capture.finish(resized.editor.stageLoop(loop.id, newMutationId(), nowMs(), capture))
    }

    private fun editCues(aggregate: IntervalEditorStateContext, edit: (CueEditorState) -> CueEditResult): EditBuild {
        val ofInterval = cues.filter { it.intervalId == aggregate.intervalId }
        val editor = try {
            CueEditorState.fromDomain(track.id, track.durationMs, aggregate, ofInterval)
        } catch (_: IllegalArgumentException) {
            return EditBuild.Refused("These cues cannot be edited.")
        }
        val result = edit(editor)
        if (!result.accepted) return EditBuild.Refused(describe(result.error!!))
        val capture = Capture()
        return capture.finish(result.editor.stage(newMutationId(), nowMs(), capture))
    }

    private fun aggregateOfCue(cueId: String): IntervalEditorStateContext? {
        val intervalId = cues.firstOrNull { it.id == cueId }?.intervalId ?: return null
        return aggregates.firstOrNull { it.intervalId == intervalId }
    }

    private fun unavailableCue(cueId: String): EditBuild {
        val cue = cues.firstOrNull { it.id == cueId } ?: return EditBuild.Refused("That cue is no longer there.")
        return EditBuild.Refused(
            if (cue.intervalId == null) "This cue is not stored on the server, so it cannot be saved there."
            else "The server's version of this cue is not available.",
        )
    }

    /** Collects the intent a cue/loop editor hands to its stager; nothing is saved here. */
    private class Capture : PreparationMutationStager {
        var intent: IntervalEditorStateReplaceIntent? = null

        override fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult {
            this.intent = intent
            return PreparationStageResult.Queued(intent.mutationId)
        }

        fun finish(result: PreparationStageResult): EditBuild = when (result) {
            is PreparationStageResult.Queued -> EditBuild.Ready(requireNotNull(intent))
            is PreparationStageResult.Rejected -> EditBuild.Refused(describe(result))
            is PreparationStageResult.RequiresCanonicalCreate, is PreparationStageResult.RequiresCanonicalDelete ->
                EditBuild.Refused("The server cannot create or delete this kind of item from the app yet.")
        }
    }

    private companion object {
        fun describe(code: CueValidationCode): String = when (code) {
            CueValidationCode.UNKNOWN_CUE -> "That cue is no longer there."
            CueValidationCode.OUT_OF_BOUNDS -> "That position is outside the track."
            CueValidationCode.DUPLICATE_ID, CueValidationCode.DUPLICATE_NUMBER, CueValidationCode.INVALID_NUMBER ->
                "There is already a cue there."
            CueValidationCode.INCOMPLETE_EDITOR_AGGREGATE -> describeIncomplete()
            CueValidationCode.OUTSIDE_CANONICAL_INTERVAL -> "A cue has to stay inside its synced section."
            CueValidationCode.NO_CHANGES -> "There is no change to save."
        }

        fun describe(code: LoopValidationCode): String = when (code) {
            LoopValidationCode.UNKNOWN_LOOP -> "that loop is no longer there"
            LoopValidationCode.DUPLICATE_ID -> "that loop already exists"
            LoopValidationCode.OUT_OF_BOUNDS -> "it would leave the track"
            LoopValidationCode.TOO_SHORT -> "it would be too short"
            LoopValidationCode.OVERLAP -> "it would overlap another loop"
        }

        fun describe(rejected: PreparationStageResult.Rejected): String = when (rejected.code) {
            "incomplete_editor_aggregate" -> describeIncomplete()
            "outside_canonical_interval" -> "A cue has to stay inside its synced section."
            "no_changes", "no_supported_canonical_change" -> "There is no change to save."
            else -> rejected.detail.ifBlank { "This edit cannot be saved." }
        }

        fun describeIncomplete() =
            "The server's marker list is not complete, so changing it here could delete markers. Sync again first."
    }
}
