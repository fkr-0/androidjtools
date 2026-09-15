package dev.androidjtools.prep.loops

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Loop
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import dev.androidjtools.prep.cues.IntervalEditorStatePayload
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationMutationStager
import dev.androidjtools.prep.cues.PreparationSnapMode
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.prep.cues.normalizePreparationColor
import dev.androidjtools.prep.cues.snapPreparationTime
import dev.androidjtools.prep.cues.stableMarkers

private const val MIN_LOOP_LENGTH_MS = 40L

enum class PreparationLoopRole { LOOP, MIX_IN, MIX_OUT, FADE_IN, FADE_OUT, BREAK, DROP }

data class LoopDraft(
    val localId: String,
    val startMs: Long,
    val endMs: Long,
    val label: String? = null,
    val colorHex: String? = null,
    val role: PreparationLoopRole = PreparationLoopRole.LOOP,
    val active: Boolean = false,
    val aggregate: IntervalEditorStateContext? = null,
) {
    init {
        require(localId.isNotBlank())
        require(startMs >= 0L)
        require(endMs - startMs >= MIN_LOOP_LENGTH_MS) { "loop must be at least $MIN_LOOP_LENGTH_MS ms" }
        normalizePreparationColor(colorHex)
        aggregate?.let { require(it.intervalId == localId) { "canonical loop ID must match interval ID" } }
    }

    companion object {
        fun fromDomain(loop: Loop, aggregate: IntervalEditorStateContext? = null): LoopDraft = LoopDraft(
            localId = loop.id,
            startMs = loop.startMs,
            endMs = loop.endMs,
            label = loop.label,
            role = when (loop.role) {
                "mix_in" -> PreparationLoopRole.MIX_IN
                "mix_out" -> PreparationLoopRole.MIX_OUT
                "fade_in" -> PreparationLoopRole.FADE_IN
                "fade_out" -> PreparationLoopRole.FADE_OUT
                "break" -> PreparationLoopRole.BREAK
                "drop" -> PreparationLoopRole.DROP
                else -> PreparationLoopRole.LOOP
            },
            aggregate = aggregate,
        )
    }

}

enum class LoopValidationCode {
    UNKNOWN_LOOP,
    DUPLICATE_ID,
    OUT_OF_BOUNDS,
    TOO_SHORT,
    OVERLAP,
}

data class LoopEditResult(
    val editor: LoopEditorState,
    val error: LoopValidationCode? = null,
) {
    val accepted: Boolean get() = error == null
}

data class LoopEditorSnapshot(
    val current: List<LoopDraft>,
    val baseline: List<LoopDraft>,
    val undoStack: List<List<LoopDraft>>,
    val pendingDeleteId: String?,
    val snapMode: PreparationSnapMode,
)

data class LoopEditorState private constructor(
    val trackId: String,
    val durationMs: Long,
    val beatGrid: BeatGrid?,
    val snapMode: PreparationSnapMode,
    val baseline: List<LoopDraft>,
    val current: List<LoopDraft>,
    val undoStack: List<List<LoopDraft>>,
    val pendingDeleteId: String? = null,
) {
    val dirty: Boolean get() = current != baseline
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun create(
        localId: String,
        requestedStartMs: Long,
        requestedEndMs: Long,
        label: String? = null,
        colorHex: String? = null,
        role: PreparationLoopRole = PreparationLoopRole.LOOP,
    ): LoopEditResult {
        if (localId.isBlank() || current.any { it.localId == localId }) return rejected(LoopValidationCode.DUPLICATE_ID)
        val start = snapPreparationTime(requestedStartMs, durationMs, beatGrid, snapMode)
        val end = snapPreparationTime(requestedEndMs, durationMs, beatGrid, snapMode)
        if (end - start < MIN_LOOP_LENGTH_MS) return rejected(LoopValidationCode.TOO_SHORT)
        val candidate = LoopDraft(
            localId = localId,
            startMs = start,
            endMs = end,
            label = label?.trim()?.ifEmpty { null },
            colorHex = normalizePreparationColor(colorHex),
            role = role,
        )
        validation(candidate, replacingId = null)?.let { return rejected(it) }
        return applied((current + candidate).sortedLoopOrder())
    }

    fun move(localId: String, requestedStartMs: Long): LoopEditResult {
        val loop = current.firstOrNull { it.localId == localId } ?: return rejected(LoopValidationCode.UNKNOWN_LOOP)
        val length = loop.endMs - loop.startMs
        var start = snapPreparationTime(requestedStartMs, durationMs, beatGrid, snapMode)
        if (start + length > durationMs) start = (durationMs - length).coerceAtLeast(0L)
        val candidate = loop.copy(startMs = start, endMs = start + length)
        validation(candidate, localId)?.let { return rejected(it) }
        return replace(candidate)
    }

    fun resizeStart(localId: String, requestedStartMs: Long): LoopEditResult {
        val loop = current.firstOrNull { it.localId == localId } ?: return rejected(LoopValidationCode.UNKNOWN_LOOP)
        val start = snapPreparationTime(requestedStartMs, durationMs, beatGrid, snapMode)
        if (loop.endMs - start < MIN_LOOP_LENGTH_MS) return rejected(LoopValidationCode.TOO_SHORT)
        val candidate = loop.copy(startMs = start)
        validation(candidate, localId)?.let { return rejected(it) }
        return replace(candidate)
    }

    fun resizeEnd(localId: String, requestedEndMs: Long): LoopEditResult {
        val loop = current.firstOrNull { it.localId == localId } ?: return rejected(LoopValidationCode.UNKNOWN_LOOP)
        val end = snapPreparationTime(requestedEndMs, durationMs, beatGrid, snapMode)
        if (end - loop.startMs < MIN_LOOP_LENGTH_MS) return rejected(LoopValidationCode.TOO_SHORT)
        val candidate = loop.copy(endMs = end)
        validation(candidate, localId)?.let { return rejected(it) }
        return replace(candidate)
    }

    fun activate(localId: String): LoopEditResult {
        if (current.none { it.localId == localId }) return rejected(LoopValidationCode.UNKNOWN_LOOP)
        return applied(current.map { it.copy(active = it.localId == localId) })
    }

    fun deactivate(localId: String): LoopEditResult = update(localId) { it.copy(active = false) }

    fun rename(localId: String, label: String?): LoopEditResult = update(localId) {
        it.copy(label = label?.trim()?.ifEmpty { null })
    }

    fun recolor(localId: String, colorHex: String?): LoopEditResult = update(localId) {
        it.copy(colorHex = normalizePreparationColor(colorHex))
    }

    fun setRole(localId: String, role: PreparationLoopRole): LoopEditResult = update(localId) { it.copy(role = role) }

    fun requestDelete(localId: String): LoopEditResult = if (current.none { it.localId == localId }) {
        rejected(LoopValidationCode.UNKNOWN_LOOP)
    } else {
        LoopEditResult(copy(pendingDeleteId = localId))
    }

    fun cancelDelete(): LoopEditorState = copy(pendingDeleteId = null)

    fun confirmDelete(): LoopEditResult {
        val target = pendingDeleteId ?: return rejected(LoopValidationCode.UNKNOWN_LOOP)
        if (current.none { it.localId == target }) return rejected(LoopValidationCode.UNKNOWN_LOOP)
        return applied(current.filterNot { it.localId == target })
    }

    fun undo(): LoopEditorState = if (undoStack.isEmpty()) {
        this
    } else {
        copy(current = undoStack.last(), undoStack = undoStack.dropLast(1), pendingDeleteId = null)
    }

    fun cancelStaging(): LoopEditorState = copy(current = baseline, undoStack = emptyList(), pendingDeleteId = null)

    fun withSnapMode(mode: PreparationSnapMode): LoopEditorState = copy(snapMode = mode)

    fun snapshot(): LoopEditorSnapshot = LoopEditorSnapshot(current, baseline, undoStack, pendingDeleteId, snapMode)

    fun stageLoop(
        localId: String,
        mutationId: String,
        createdAtEpochMillis: Long,
        stager: PreparationMutationStager,
        comment: String = "Android DJ loop preparation edit",
    ): PreparationStageResult {
        val loop = current.firstOrNull { it.localId == localId }
            ?: return PreparationStageResult.Rejected("unknown_loop", "loop $localId does not exist")
        val baselineLoop = baseline.firstOrNull { it.localId == localId }
        if (baselineLoop == null || loop.aggregate == null) {
            // v1 intentionally has no offline-safe interval.create operation.
            return PreparationStageResult.RequiresCanonicalCreate(localId)
        }
        val aggregate = loop.aggregate
        if (!aggregate.completeMarkerSet) {
            return PreparationStageResult.Rejected(
                "incomplete_editor_aggregate",
                "interval.editor_state.replace requires the complete canonical marker set",
            )
        }
        val boundariesChanged = loop.startMs != baselineLoop.startMs || loop.endMs != baselineLoop.endMs
        if (!boundariesChanged) {
            return PreparationStageResult.Rejected(
                "no_supported_canonical_change",
                "v1 editor-state replacement persists loop bounds; label/color/role/activation remain local staging",
            )
        }
        return stager.stage(
            IntervalEditorStateReplaceIntent(
                mutationId = mutationId,
                entityId = aggregate.intervalId,
                baseRevision = aggregate.baseRevision,
                payload = IntervalEditorStatePayload(
                    startMs = loop.startMs,
                    endMs = loop.endMs,
                    markers = stableMarkers(aggregate.markers),
                    comment = comment,
                ),
                createdAtEpochMillis = createdAtEpochMillis,
                provenance = mapOf("source" to "android_manual", "surface" to "loop_editor"),
            ),
        )
    }

    fun stageDeletedLoop(localId: String): PreparationStageResult {
        val baselineLoop = baseline.firstOrNull { it.localId == localId }
            ?: return PreparationStageResult.Rejected("unknown_loop", "loop $localId is not a canonical baseline loop")
        if (current.any { it.localId == localId }) {
            return PreparationStageResult.Rejected("loop_not_deleted", "loop $localId is still present in staged state")
        }
        return if (baselineLoop.aggregate == null) {
            PreparationStageResult.Rejected(
                "missing_canonical_interval_context",
                "canonical loop deletion requires its authoritative interval identity",
            )
        } else {
            // v1 deliberately exposes aggregate replacement, not interval deletion.
            PreparationStageResult.RequiresCanonicalDelete(baselineLoop.aggregate.intervalId)
        }
    }

    private fun update(localId: String, transform: (LoopDraft) -> LoopDraft): LoopEditResult {
        val loop = current.firstOrNull { it.localId == localId } ?: return rejected(LoopValidationCode.UNKNOWN_LOOP)
        return replace(transform(loop))
    }

    private fun replace(candidate: LoopDraft): LoopEditResult {
        val next = current.map { if (it.localId == candidate.localId) candidate else it }.sortedLoopOrder()
        return applied(next)
    }

    private fun validation(candidate: LoopDraft, replacingId: String?): LoopValidationCode? {
        if (candidate.startMs < 0L || candidate.endMs > durationMs) return LoopValidationCode.OUT_OF_BOUNDS
        if (candidate.endMs - candidate.startMs < MIN_LOOP_LENGTH_MS) return LoopValidationCode.TOO_SHORT
        val overlaps = current.any { other ->
            other.localId != replacingId && candidate.startMs < other.endMs && other.startMs < candidate.endMs
        }
        return if (overlaps) LoopValidationCode.OVERLAP else null
    }

    private fun applied(next: List<LoopDraft>): LoopEditResult = if (next == current) {
        LoopEditResult(copy(pendingDeleteId = null))
    } else {
        LoopEditResult(copy(current = next, undoStack = undoStack + listOf(current), pendingDeleteId = null))
    }

    private fun rejected(code: LoopValidationCode): LoopEditResult = LoopEditResult(this, code)

    companion object {
        fun create(
            trackId: String,
            durationMs: Long,
            loops: List<LoopDraft>,
            beatGrid: BeatGrid? = null,
            snapMode: PreparationSnapMode = PreparationSnapMode.OFF,
        ): LoopEditorState {
            require(trackId.isNotBlank())
            require(durationMs > 0L)
            require(loops.map { it.localId }.distinct().size == loops.size) { "loop IDs must be unique" }
            require(loops.all { it.endMs <= durationMs }) { "loop outside track bounds" }
            require(loops.count { it.active } <= 1) { "at most one loop may be active" }
            val ordered = loops.sortedLoopOrder()
            return LoopEditorState(trackId, durationMs, beatGrid, snapMode, ordered, ordered, emptyList())
        }

        fun fromDomain(
            trackId: String,
            durationMs: Long,
            loops: List<Loop>,
            aggregates: Map<String, IntervalEditorStateContext> = emptyMap(),
            beatGrid: BeatGrid? = null,
            snapMode: PreparationSnapMode = PreparationSnapMode.OFF,
        ): LoopEditorState = create(
            trackId,
            durationMs,
            loops.map { LoopDraft.fromDomain(it, aggregates[it.id]) },
            beatGrid,
            snapMode,
        )

        fun restore(
            trackId: String,
            durationMs: Long,
            beatGrid: BeatGrid?,
            snapshot: LoopEditorSnapshot,
        ): LoopEditorState = LoopEditorState(
            trackId = trackId,
            durationMs = durationMs,
            beatGrid = beatGrid,
            snapMode = snapshot.snapMode,
            baseline = snapshot.baseline,
            current = snapshot.current,
            undoStack = snapshot.undoStack,
            pendingDeleteId = snapshot.pendingDeleteId,
        )
    }
}

private fun List<LoopDraft>.sortedLoopOrder(): List<LoopDraft> =
    sortedWith(compareBy<LoopDraft> { it.startMs }.thenBy { it.endMs }.thenBy { it.localId })
