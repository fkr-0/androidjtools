package dev.androidjtools.prep.cues

import dev.androidjtools.core.model.BeatGrid
import kotlin.math.round
import kotlin.math.roundToLong

/** Quantization used by preparation editors without taking ownership of beat-grid editing. */
enum class PreparationSnapMode(val beatFraction: Double?) {
    OFF(null),
    BEAT(1.0),
    HALF_BEAT(0.5),
    QUARTER_BEAT(0.25),
}

data class EditorMarkerDraft(
    val id: String? = null,
    val positionMs: Long,
    val label: String? = null,
    val markerType: String,
    val color: String? = null,
    val sourceKind: String = "android_manual",
)

/**
 * Complete read snapshot of the canonical Sample Lib interval editor aggregate.
 *
 * interval.editor_state.replace is an aggregate replacement. Callers must therefore prove that
 * [markers] is complete before an editor is allowed to construct a replacement intent. This keeps
 * cue editing from accidentally deleting marker kinds that the cue UI does not understand.
 */
data class IntervalEditorStateContext(
    val intervalId: String,
    val baseRevision: String,
    val startMs: Long,
    val endMs: Long,
    val markers: List<EditorMarkerDraft>,
    val completeMarkerSet: Boolean,
) {
    init {
        require(intervalId.isNotBlank()) { "intervalId must be non-empty" }
        require(baseRevision.isNotBlank()) { "baseRevision must be an opaque non-empty value" }
        require(startMs >= 0L) { "interval start must be non-negative" }
        require(endMs > startMs) { "interval end must be after start" }
    }
}

data class IntervalEditorStatePayload(
    val startMs: Long,
    val endMs: Long,
    val markers: List<EditorMarkerDraft>,
    val comment: String? = null,
)

/** Backend-neutral intent matching the negotiated v1 operation-family mutation envelope. */
data class IntervalEditorStateReplaceIntent(
    val mutationId: String,
    val entityId: String,
    val baseRevision: String,
    val payload: IntervalEditorStatePayload,
    val createdAtEpochMillis: Long,
    val provenance: Map<String, String>,
) {
    val entityType: String = "interval"
    val operation: String = "interval.editor_state.replace"

    init {
        require(mutationId.length >= 8) { "mutationId must be at least 8 characters" }
        require(entityId.isNotBlank()) { "entityId must be non-empty" }
        require(baseRevision.isNotBlank()) { "baseRevision must be opaque and non-empty" }
        require(payload.endMs > payload.startMs) { "payload interval must be non-empty" }
    }
}

sealed interface PreparationStageResult {
    data class Queued(val mutationId: String) : PreparationStageResult
    data class Rejected(val code: String, val detail: String) : PreparationStageResult
    data class RequiresCanonicalCreate(val localId: String) : PreparationStageResult
    data class RequiresCanonicalDelete(val canonicalId: String) : PreparationStageResult
}

/**
 * Deliberately does not depend on the sync journal or Sample Lib client. EPIC-11/12 adapters can
 * implement this seam and are responsible for durable queueing and authoritative receipts.
 */
fun interface PreparationMutationStager {
    fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult
}

fun snapPreparationTime(
    requestedMs: Long,
    durationMs: Long,
    beatGrid: BeatGrid?,
    mode: PreparationSnapMode,
): Long {
    val duration = durationMs.coerceAtLeast(0L)
    val fraction = mode.beatFraction ?: return requestedMs.coerceIn(0L, duration)
    val grid = beatGrid?.takeIf { it.bpm.isFinite() && it.bpm > 0.0 }
        ?: return requestedMs.coerceIn(0L, duration)
    val step = 60_000.0 / grid.bpm * fraction
    if (!step.isFinite() || step <= 0.0) return requestedMs.coerceIn(0L, duration)
    val index = round((requestedMs - grid.anchorMs) / step)
    return (grid.anchorMs + index * step).roundToLong().coerceIn(0L, duration)
}

fun normalizePreparationColor(value: String?): String? {
    if (value == null) return null
    val trimmed = value.trim()
    require(COLOR_PATTERN.matches(trimmed)) { "color must be #RRGGBB or #AARRGGBB" }
    return trimmed.uppercase()
}

internal fun stableMarkers(markers: List<EditorMarkerDraft>): List<EditorMarkerDraft> =
    markers.sortedWith(
        compareBy<EditorMarkerDraft> { it.positionMs }
            .thenBy { it.id ?: "" }
            .thenBy { it.markerType }
            .thenBy { it.label ?: "" },
    )

private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
