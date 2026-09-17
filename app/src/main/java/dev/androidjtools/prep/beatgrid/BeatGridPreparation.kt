package dev.androidjtools.prep.beatgrid

import dev.androidjtools.core.model.BeatGrid
import kotlin.math.round
import kotlin.math.roundToLong

const val MIN_EDITABLE_BPM = 20.0
const val MAX_EDITABLE_BPM = 300.0

/**
 * Advisory beat-grid candidate. It is never canonical until an explicit editor
 * action copies it into staged state and a later provider/journal receipt makes
 * that staged intent authoritative.
 */
data class BeatGridCandidate(
    val id: String,
    val trackId: String,
    val bpm: Double,
    val anchorMs: Long? = null,
    val source: String,
    val confidence: Double? = null,
)

data class BeatGridCommitIntent(
    val trackId: String,
    val baseRevision: Long,
    val anchorMs: Long,
    val bpm: Double,
    val operation: String = "beatgrid.replace",
)

/** Typed persistence seam for process recreation / local preparation storage. */
data class BeatGridPreparationSnapshot(
    val trackId: String,
    val canonicalRevision: Long,
    val stagedAnchorMs: Long,
    val stagedBpm: Double,
    val undo: List<BeatGrid>,
    val candidate: BeatGridCandidate?,
)

data class BeatGridPreparationState(
    val canonical: BeatGrid,
    val staged: BeatGrid = canonical,
    val candidate: BeatGridCandidate? = null,
    val undoStack: List<BeatGrid> = emptyList(),
) {
    init {
        require(staged.trackId == canonical.trackId) { "staged beatgrid must belong to the canonical track" }
        require(staged.revision == canonical.revision) { "staged revision must match canonical base revision" }
        require(staged.bpm.isFinite() && staged.bpm in MIN_EDITABLE_BPM..MAX_EDITABLE_BPM) {
            "staged BPM must be finite and editable"
        }
        require(candidate == null || candidate.trackId == canonical.trackId) {
            "candidate beatgrid must belong to the canonical track"
        }
    }

    val dirty: Boolean
        get() = staged.anchorMs != canonical.anchorMs || staged.bpm != canonical.bpm

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canHalfBpm: Boolean
        get() = transformedBpm(0.5) != null

    val canDoubleBpm: Boolean
        get() = transformedBpm(2.0) != null

    fun setBpm(bpm: Double): BeatGridPreparationState {
        if (!bpm.isFinite()) return this
        val next = bpm.coerceIn(MIN_EDITABLE_BPM, MAX_EDITABLE_BPM)
        return update(staged.copy(bpm = next))
    }

    /** Anchor is the first downbeat (beat index zero) for the staged grid. */
    fun setFirstDownbeat(positionMs: Long, durationMs: Long): BeatGridPreparationState =
        update(staged.copy(anchorMs = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))))

    /** Fine phase correction, intentionally independent of beat quantization. */
    fun nudgePhase(deltaMs: Long, durationMs: Long): BeatGridPreparationState =
        setFirstDownbeat(staged.anchorMs + deltaMs, durationMs)

    fun halfBpm(): BeatGridPreparationState = transformBpm(0.5)

    fun doubleBpm(): BeatGridPreparationState = transformBpm(2.0)

    fun acceptCandidate(): BeatGridPreparationState {
        val advisory = candidate ?: return this
        if (!advisory.bpm.isFinite() || advisory.bpm !in MIN_EDITABLE_BPM..MAX_EDITABLE_BPM) return this
        val next = staged.copy(
            bpm = advisory.bpm,
            anchorMs = advisory.anchorMs ?: staged.anchorMs,
        )
        return update(next)
    }

    fun undo(): BeatGridPreparationState {
        if (undoStack.isEmpty()) return this
        return copy(staged = undoStack.last(), undoStack = undoStack.dropLast(1))
    }

    /** Discards all local staging without changing canonical state. */
    fun cancel(): BeatGridPreparationState = copy(staged = canonical, undoStack = emptyList())

    fun commitIntent(): BeatGridCommitIntent = BeatGridCommitIntent(
        trackId = canonical.trackId,
        baseRevision = canonical.revision,
        anchorMs = staged.anchorMs,
        bpm = staged.bpm,
    )

    fun snapshot(): BeatGridPreparationSnapshot = BeatGridPreparationSnapshot(
        trackId = canonical.trackId,
        canonicalRevision = canonical.revision,
        stagedAnchorMs = staged.anchorMs,
        stagedBpm = staged.bpm,
        undo = undoStack,
        candidate = candidate,
    )

    private fun update(next: BeatGrid): BeatGridPreparationState =
        if (next == staged) this else copy(staged = next, undoStack = undoStack + staged)

    private fun transformedBpm(factor: Double): Double? {
        val value = staged.bpm * factor
        return value.takeIf { it.isFinite() && it in MIN_EDITABLE_BPM..MAX_EDITABLE_BPM }
    }

    private fun transformBpm(factor: Double): BeatGridPreparationState {
        val next = transformedBpm(factor) ?: return this
        return update(staged.copy(bpm = next))
    }

    companion object {
        fun restore(
            canonical: BeatGrid,
            snapshot: BeatGridPreparationSnapshot,
        ): BeatGridPreparationState? {
            if (snapshot.trackId != canonical.trackId || snapshot.canonicalRevision != canonical.revision) return null
            if (!snapshot.stagedBpm.isFinite() || snapshot.stagedBpm !in MIN_EDITABLE_BPM..MAX_EDITABLE_BPM) return null
            if (snapshot.undo.any { it.trackId != canonical.trackId || it.revision != canonical.revision }) return null
            return BeatGridPreparationState(
                canonical = canonical,
                staged = canonical.copy(anchorMs = snapshot.stagedAnchorMs, bpm = snapshot.stagedBpm),
                candidate = snapshot.candidate?.takeIf { it.trackId == canonical.trackId },
                undoStack = snapshot.undo,
            )
        }
    }
}

fun beatIntervalMs(grid: BeatGrid): Double = 60_000.0 / grid.bpm

/** Stable nearest-beat index, including beat positions before the anchor. */
fun nearestBeatIndex(grid: BeatGrid, timeMs: Long): Long {
    require(grid.bpm.isFinite() && grid.bpm > 0.0) { "BPM must be positive and finite" }
    return round((timeMs - grid.anchorMs) / beatIntervalMs(grid)).toLong()
}

/** Deterministic beat-index -> timeline position using Kotlin's stable rounding. */
fun beatTimeMs(grid: BeatGrid, beatIndex: Long): Long {
    require(grid.bpm.isFinite() && grid.bpm > 0.0) { "BPM must be positive and finite" }
    return (grid.anchorMs + beatIndex * beatIntervalMs(grid)).roundToLong()
}
