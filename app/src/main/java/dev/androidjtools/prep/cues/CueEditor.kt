package dev.androidjtools.prep.cues

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue

enum class HotCueRole(val markerType: String) {
    CUE("cue"),
    MIX_IN("mix_in"),
    MIX_OUT("mix_out"),
    FADE_IN("fade_in"),
    FADE_OUT("fade_out"),
    DROP("drop"),
}

data class HotCueDraft(
    val localId: String,
    val canonicalMarkerId: String? = null,
    val positionMs: Long,
    val label: String? = null,
    val colorHex: String? = null,
    val number: Int? = null,
    val role: HotCueRole = HotCueRole.CUE,
) {
    init {
        require(localId.isNotBlank()) { "localId must be non-empty" }
        require(positionMs >= 0L) { "position must be non-negative" }
        require(number == null || number in HOT_CUE_NUMBER_RANGE) { "hot cue number must be 1..16" }
        normalizePreparationColor(colorHex)
    }

    fun toEditorMarker(): EditorMarkerDraft = EditorMarkerDraft(
        id = canonicalMarkerId,
        positionMs = positionMs,
        label = label,
        markerType = role.markerType,
        color = normalizePreparationColor(colorHex),
    )

    companion object {
        fun fromDomain(cue: Cue): HotCueDraft = HotCueDraft(
            localId = cue.id,
            canonicalMarkerId = cue.id,
            positionMs = cue.positionMs,
            label = cue.label,
            colorHex = cue.colorArgb?.let { "#%08X".format(it and 0xffff_ffffL) },
            number = cue.hotCueNumber,
            role = HotCueRole.entries.firstOrNull { it.markerType == cue.role } ?: HotCueRole.CUE,
        )
    }
}

enum class CueValidationCode {
    UNKNOWN_CUE,
    DUPLICATE_ID,
    DUPLICATE_NUMBER,
    INVALID_NUMBER,
    OUT_OF_BOUNDS,
    INCOMPLETE_EDITOR_AGGREGATE,
    OUTSIDE_CANONICAL_INTERVAL,
    NO_CHANGES,
}

data class CueEditResult(
    val editor: CueEditorState,
    val error: CueValidationCode? = null,
) {
    val accepted: Boolean get() = error == null
}

data class CueEditorSnapshot(
    val current: List<HotCueDraft>,
    val baseline: List<HotCueDraft>,
    val undoStack: List<List<HotCueDraft>>,
    val pendingDeleteId: String?,
    val snapMode: PreparationSnapMode,
)

data class CueEditorState private constructor(
    val trackId: String,
    val durationMs: Long,
    val aggregate: IntervalEditorStateContext,
    val beatGrid: BeatGrid?,
    val snapMode: PreparationSnapMode,
    val baseline: List<HotCueDraft>,
    val current: List<HotCueDraft>,
    val undoStack: List<List<HotCueDraft>>,
    val pendingDeleteId: String? = null,
) {
    val dirty: Boolean get() = current != baseline
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun create(
        localId: String,
        requestedPositionMs: Long,
        label: String? = null,
        colorHex: String? = null,
        number: Int? = null,
        role: HotCueRole = HotCueRole.CUE,
    ): CueEditResult {
        if (localId.isBlank() || current.any { it.localId == localId }) return rejected(CueValidationCode.DUPLICATE_ID)
        if (number != null && number !in HOT_CUE_NUMBER_RANGE) return rejected(CueValidationCode.INVALID_NUMBER)
        if (number != null && current.any { it.number == number }) return rejected(CueValidationCode.DUPLICATE_NUMBER)
        val position = snapPreparationTime(requestedPositionMs, durationMs, beatGrid, snapMode)
        if (position !in 0L..durationMs) return rejected(CueValidationCode.OUT_OF_BOUNDS)
        val cue = HotCueDraft(
            localId = localId,
            positionMs = position,
            label = label?.trim()?.ifEmpty { null },
            colorHex = normalizePreparationColor(colorHex),
            number = number,
            role = role,
        )
        return applied((current + cue).sortedCueOrder())
    }

    fun move(localId: String, requestedPositionMs: Long): CueEditResult {
        val index = current.indexOfFirst { it.localId == localId }
        if (index < 0) return rejected(CueValidationCode.UNKNOWN_CUE)
        val position = snapPreparationTime(requestedPositionMs, durationMs, beatGrid, snapMode)
        val next = current.toMutableList().also { it[index] = it[index].copy(positionMs = position) }
        return applied(next.sortedCueOrder())
    }

    fun rename(localId: String, label: String?): CueEditResult = update(localId) {
        it.copy(label = label?.trim()?.ifEmpty { null })
    }

    fun recolor(localId: String, colorHex: String?): CueEditResult = update(localId) {
        it.copy(colorHex = normalizePreparationColor(colorHex))
    }

    fun renumber(localId: String, number: Int?): CueEditResult {
        if (number != null && number !in HOT_CUE_NUMBER_RANGE) return rejected(CueValidationCode.INVALID_NUMBER)
        if (number != null && current.any { it.localId != localId && it.number == number }) {
            return rejected(CueValidationCode.DUPLICATE_NUMBER)
        }
        return update(localId) { it.copy(number = number) }
    }

    fun setRole(localId: String, role: HotCueRole): CueEditResult = update(localId) { it.copy(role = role) }

    fun requestDelete(localId: String): CueEditResult = if (current.none { it.localId == localId }) {
        rejected(CueValidationCode.UNKNOWN_CUE)
    } else {
        CueEditResult(copy(pendingDeleteId = localId))
    }

    fun cancelDelete(): CueEditorState = copy(pendingDeleteId = null)

    fun confirmDelete(): CueEditResult {
        val target = pendingDeleteId ?: return rejected(CueValidationCode.UNKNOWN_CUE)
        if (current.none { it.localId == target }) return rejected(CueValidationCode.UNKNOWN_CUE)
        return applied(current.filterNot { it.localId == target })
    }

    fun undo(): CueEditorState = if (undoStack.isEmpty()) {
        this
    } else {
        copy(current = undoStack.last(), undoStack = undoStack.dropLast(1), pendingDeleteId = null)
    }

    fun cancelStaging(): CueEditorState = copy(current = baseline, undoStack = emptyList(), pendingDeleteId = null)

    fun withSnapMode(mode: PreparationSnapMode): CueEditorState = copy(snapMode = mode)

    fun snapshot(): CueEditorSnapshot = CueEditorSnapshot(current, baseline, undoStack, pendingDeleteId, snapMode)

    fun stage(
        mutationId: String,
        createdAtEpochMillis: Long,
        stager: PreparationMutationStager,
        comment: String = "Android DJ cue preparation edit",
    ): PreparationStageResult {
        if (!dirty) return PreparationStageResult.Rejected("no_changes", "cue editor has no staged changes")
        if (!aggregate.completeMarkerSet) {
            return PreparationStageResult.Rejected(
                "incomplete_editor_aggregate",
                "interval.editor_state.replace requires the complete canonical marker set",
            )
        }
        val baselineCanonicalMarkers = stableMarkers(baseline.map { it.toEditorMarker() })
        val currentCanonicalMarkers = stableMarkers(current.map { it.toEditorMarker() })
        if (baselineCanonicalMarkers == currentCanonicalMarkers) {
            return PreparationStageResult.Rejected(
                "no_supported_canonical_change",
                "hot-cue numbering is local staging because the v1 marker contract has no number field",
            )
        }
        if (current.any { it.positionMs < aggregate.startMs || it.positionMs >= aggregate.endMs }) {
            return PreparationStageResult.Rejected(
                "outside_canonical_interval",
                "all cue markers must remain inside the canonical half-open interval",
            )
        }
        val managedCanonicalIds = baseline.mapNotNull { it.canonicalMarkerId }.toSet()
        val preserved = aggregate.markers.filterNot { it.id != null && it.id in managedCanonicalIds }
        val payload = IntervalEditorStatePayload(
            startMs = aggregate.startMs,
            endMs = aggregate.endMs,
            markers = stableMarkers(preserved + currentCanonicalMarkers),
            comment = comment,
        )
        return stager.stage(
            IntervalEditorStateReplaceIntent(
                mutationId = mutationId,
                entityId = aggregate.intervalId,
                baseRevision = aggregate.baseRevision,
                payload = payload,
                createdAtEpochMillis = createdAtEpochMillis,
                provenance = mapOf("source" to "android_manual", "surface" to "cue_editor"),
            ),
        )
    }

    private fun update(localId: String, transform: (HotCueDraft) -> HotCueDraft): CueEditResult {
        val index = current.indexOfFirst { it.localId == localId }
        if (index < 0) return rejected(CueValidationCode.UNKNOWN_CUE)
        val next = current.toMutableList().also { it[index] = transform(it[index]) }
        return applied(next.sortedCueOrder())
    }

    private fun applied(next: List<HotCueDraft>): CueEditResult = if (next == current) {
        CueEditResult(copy(pendingDeleteId = null))
    } else {
        CueEditResult(copy(current = next, undoStack = undoStack + listOf(current), pendingDeleteId = null))
    }

    private fun rejected(code: CueValidationCode): CueEditResult = CueEditResult(this, code)

    companion object {
        fun create(
            trackId: String,
            durationMs: Long,
            aggregate: IntervalEditorStateContext,
            cues: List<HotCueDraft>,
            beatGrid: BeatGrid? = null,
            snapMode: PreparationSnapMode = PreparationSnapMode.OFF,
        ): CueEditorState {
            require(trackId.isNotBlank())
            require(durationMs > 0L)
            require(cues.map { it.localId }.distinct().size == cues.size) { "cue local IDs must be unique" }
            val numbered = cues.mapNotNull { it.number }
            require(numbered.distinct().size == numbered.size) { "hot cue numbers must be unique" }
            require(cues.all { it.positionMs in 0L..durationMs }) { "cue position outside track bounds" }
            val ordered = cues.sortedCueOrder()
            return CueEditorState(
                trackId = trackId,
                durationMs = durationMs,
                aggregate = aggregate,
                beatGrid = beatGrid,
                snapMode = snapMode,
                baseline = ordered,
                current = ordered,
                undoStack = emptyList(),
            )
        }

        fun fromDomain(
            trackId: String,
            durationMs: Long,
            aggregate: IntervalEditorStateContext,
            cues: List<Cue>,
            beatGrid: BeatGrid? = null,
            snapMode: PreparationSnapMode = PreparationSnapMode.OFF,
        ): CueEditorState = create(trackId, durationMs, aggregate, cues.map(HotCueDraft::fromDomain), beatGrid, snapMode)

        fun restore(
            trackId: String,
            durationMs: Long,
            aggregate: IntervalEditorStateContext,
            beatGrid: BeatGrid?,
            snapshot: CueEditorSnapshot,
        ): CueEditorState = CueEditorState(
            trackId = trackId,
            durationMs = durationMs,
            aggregate = aggregate,
            beatGrid = beatGrid,
            snapMode = snapshot.snapMode,
            baseline = snapshot.baseline,
            current = snapshot.current,
            undoStack = snapshot.undoStack,
            pendingDeleteId = snapshot.pendingDeleteId,
        )
    }
}

private fun List<HotCueDraft>.sortedCueOrder(): List<HotCueDraft> =
    sortedWith(compareBy<HotCueDraft> { it.positionMs }.thenBy { it.number ?: Int.MAX_VALUE }.thenBy { it.localId })

private val HOT_CUE_NUMBER_RANGE = 1..16
