package dev.androidjtools.ui.waveform

import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToLong

private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 32.0
private const val MIN_RANGE_LENGTH_MS = 40L
private const val MIN_BPM = 20.0
private const val MAX_BPM = 300.0

data class WaveformViewport(
    val zoom: Double = MIN_ZOOM,
    val startMs: Long = 0L,
) {
    fun normalized(durationMs: Long): WaveformViewport {
        val duration = durationMs.coerceAtLeast(1L)
        val safeZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val visible = (duration / safeZoom).roundToLong().coerceAtLeast(1L)
        val maxStart = (duration - visible).coerceAtLeast(0L)
        return copy(zoom = safeZoom, startMs = startMs.coerceIn(0L, maxStart))
    }

    fun visibleDurationMs(durationMs: Long): Long {
        val duration = durationMs.coerceAtLeast(1L)
        val safe = normalized(duration)
        return (duration / safe.zoom).roundToLong().coerceIn(1L, duration)
    }

    fun endMs(durationMs: Long): Long {
        val safe = normalized(durationMs)
        return (safe.startMs + safe.visibleDurationMs(durationMs)).coerceAtMost(durationMs.coerceAtLeast(1L))
    }

    fun timeAtFraction(durationMs: Long, fraction: Float): Long {
        val safe = normalized(durationMs)
        val visible = safe.visibleDurationMs(durationMs)
        return (safe.startMs + visible * fraction.coerceIn(0f, 1f)).roundToLong()
            .coerceIn(0L, durationMs.coerceAtLeast(1L))
    }

    fun fractionForTime(durationMs: Long, timeMs: Long): Float {
        val safe = normalized(durationMs)
        val visible = safe.visibleDurationMs(durationMs).coerceAtLeast(1L)
        return ((timeMs - safe.startMs).toDouble() / visible.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun zoomBy(durationMs: Long, scale: Double, focalFraction: Float): WaveformViewport {
        val safe = normalized(durationMs)
        val focal = focalFraction.coerceIn(0f, 1f)
        val focalTime = safe.timeAtFraction(durationMs, focal)
        val nextZoom = (safe.zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val nextVisible = (durationMs.coerceAtLeast(1L) / nextZoom).roundToLong().coerceAtLeast(1L)
        val nextStart = (focalTime - nextVisible * focal).roundToLong()
        return WaveformViewport(nextZoom, nextStart).normalized(durationMs)
    }

    fun panByFraction(durationMs: Long, visibleFraction: Float): WaveformViewport {
        val safe = normalized(durationMs)
        val delta = (safe.visibleDurationMs(durationMs) * visibleFraction).roundToLong()
        return safe.copy(startMs = safe.startMs + delta).normalized(durationMs)
    }
}

enum class WaveformOverlaySource { CANONICAL, CANDIDATE }
enum class WaveformOverlayKind { CUE, LOOP, POINT, RANGE, TRANSCRIPT }

data class WaveformOverlay(
    val id: String,
    val startMs: Long,
    val endMs: Long = startMs,
    val label: String,
    val kind: WaveformOverlayKind,
    val source: WaveformOverlaySource,
) {
    val isRange: Boolean get() = endMs > startMs
}

data class WaveformAdvisory(
    val id: String,
    val label: String,
    val warning: Boolean = false,
)

enum class QuantizeMode(val label: String, val beatFraction: Double?) {
    OFF("Off", null),
    BEAT("1 beat", 1.0),
    HALF_BEAT("1/2 beat", 0.5),
    QUARTER_BEAT("1/4 beat", 0.25),
    ;

    fun next(): QuantizeMode = entries[(ordinal + 1) % entries.size]
}

enum class RangeHandle { START, END }

data class EditHistory<T>(
    val current: T,
    val undoStack: List<T> = emptyList(),
) {
    fun update(next: T): EditHistory<T> =
        if (next == current) this else copy(current = next, undoStack = undoStack + current)

    fun undo(): EditHistory<T> =
        if (undoStack.isEmpty()) this else copy(current = undoStack.last(), undoStack = undoStack.dropLast(1))

    val canUndo: Boolean get() = undoStack.isNotEmpty()
}

data class StagedRangeEdit(
    val id: String,
    val label: String,
    val originalStartMs: Long,
    val originalEndMs: Long,
    val startMs: Long = originalStartMs,
    val endMs: Long = originalEndMs,
) {
    val dirty: Boolean get() = startMs != originalStartMs || endMs != originalEndMs

    fun reset(): StagedRangeEdit = copy(startMs = originalStartMs, endMs = originalEndMs)

    companion object {
        fun from(overlay: WaveformOverlay): StagedRangeEdit? = overlay
            .takeIf { it.isRange && it.source == WaveformOverlaySource.CANONICAL }
            ?.let { StagedRangeEdit(it.id, it.label, it.startMs, it.endMs) }
    }
}

data class StagedGridEdit(
    val trackId: String,
    val revision: Long,
    val originalAnchorMs: Long,
    val originalBpm: Double,
    val anchorMs: Long = originalAnchorMs,
    val bpm: Double = originalBpm,
) {
    val dirty: Boolean get() = anchorMs != originalAnchorMs || bpm != originalBpm

    fun reset(): StagedGridEdit = copy(anchorMs = originalAnchorMs, bpm = originalBpm)

    fun toBeatGrid(): BeatGrid = BeatGrid(trackId, anchorMs, bpm, revision)

    companion object {
        fun from(grid: BeatGrid): StagedGridEdit = StagedGridEdit(
            trackId = grid.trackId,
            revision = grid.revision,
            originalAnchorMs = grid.anchorMs,
            originalBpm = grid.bpm,
        )
    }
}

fun buildWaveformOverlays(
    cues: List<Cue>,
    loops: List<Loop>,
    suggestions: List<AnalysisSuggestion>,
): List<WaveformOverlay> = buildList {
    cues.forEach { cue ->
        add(
            WaveformOverlay(
                id = cue.id,
                startMs = cue.positionMs,
                label = cue.label ?: cue.role,
                kind = WaveformOverlayKind.CUE,
                source = WaveformOverlaySource.CANONICAL,
            )
        )
    }
    loops.forEach { loop ->
        add(
            WaveformOverlay(
                id = loop.id,
                startMs = loop.startMs,
                endMs = loop.endMs,
                label = loop.label ?: loop.role,
                kind = WaveformOverlayKind.LOOP,
                source = WaveformOverlaySource.CANONICAL,
            )
        )
    }
    for (suggestion in suggestions) {
        if (suggestion.decision != SuggestionDecision.PROPOSED || suggestion.stale) continue
        val overlay = when (val payload = suggestion.payload) {
            is SuggestionPayload.Point -> WaveformOverlay(
                id = suggestion.id,
                startMs = payload.positionMs,
                label = payload.label ?: payload.role,
                kind = WaveformOverlayKind.POINT,
                source = WaveformOverlaySource.CANDIDATE,
            )
            is SuggestionPayload.Range -> WaveformOverlay(
                id = suggestion.id,
                startMs = payload.startMs,
                endMs = payload.endMs,
                label = payload.label ?: payload.role,
                kind = WaveformOverlayKind.RANGE,
                source = WaveformOverlaySource.CANDIDATE,
            )
            is SuggestionPayload.Transcript -> WaveformOverlay(
                id = suggestion.id,
                startMs = payload.positionMs,
                label = payload.text,
                kind = WaveformOverlayKind.TRANSCRIPT,
                source = WaveformOverlaySource.CANDIDATE,
            )
            else -> null
        }
        overlay?.let(::add)
    }
}.sortedWith(compareBy<WaveformOverlay> { it.startMs }.thenBy { it.id })

fun applyStagedRange(
    overlays: List<WaveformOverlay>,
    edit: StagedRangeEdit?,
): List<WaveformOverlay> = if (edit == null) overlays else overlays.map { overlay ->
    if (overlay.id == edit.id && overlay.source == WaveformOverlaySource.CANONICAL && overlay.isRange) {
        overlay.copy(startMs = edit.startMs, endMs = edit.endMs)
    } else {
        overlay
    }
}

fun analysisAdvisories(suggestions: List<AnalysisSuggestion>): List<WaveformAdvisory> =
    suggestions.asSequence()
        .filter { it.decision == SuggestionDecision.PROPOSED && !it.stale }
        .mapNotNull { suggestion ->
            when (val payload = suggestion.payload) {
                is SuggestionPayload.Bpm -> WaveformAdvisory(suggestion.id, "BPM candidate %.2f".format(payload.value))
                is SuggestionPayload.Key -> WaveformAdvisory(suggestion.id, "Key candidate ${payload.value}")
                is SuggestionPayload.Warning -> WaveformAdvisory(suggestion.id, payload.message, warning = true)
                else -> when (suggestion.kind) {
                    SuggestionKind.LOUDNESS_WARNING,
                    SuggestionKind.SPECTRAL_OUTLIER_WARNING -> WaveformAdvisory(suggestion.id, suggestion.kind.name, warning = true)
                    else -> null
                }
            }
        }
        .toList()

fun visibleWaveformAdvisories(
    advisories: List<WaveformAdvisory>,
    maxSuggestions: Int = 3,
): List<WaveformAdvisory> = buildList {
    val suggestionLimit = maxSuggestions.coerceAtLeast(0)
    var suggestionCount = 0
    advisories.forEach { advisory ->
        if (advisory.warning || suggestionCount < suggestionLimit) {
            add(advisory)
            if (!advisory.warning) suggestionCount++
        }
    }
}

fun beatIntervalMs(grid: BeatGrid?): Double? = grid
    ?.takeIf { it.bpm > 0.0 }
    ?.let { 60_000.0 / it.bpm }

fun snapTime(
    timeMs: Long,
    grid: BeatGrid?,
    mode: QuantizeMode,
    durationMs: Long,
): Long {
    val fraction = mode.beatFraction ?: return timeMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val interval = beatIntervalMs(grid) ?: return timeMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val step = interval * fraction
    if (!step.isFinite() || step <= 0.0 || grid == null) return timeMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val stepIndex = round((timeMs - grid.anchorMs) / step)
    return (grid.anchorMs + stepIndex * step).roundToLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
}

fun jumpByBeats(
    positionMs: Long,
    grid: BeatGrid?,
    beats: Int,
    durationMs: Long,
): Long {
    val interval = beatIntervalMs(grid) ?: return positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    return (positionMs + beats * interval).roundToLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
}

fun updateRangeHandle(
    edit: StagedRangeEdit,
    handle: RangeHandle,
    requestedMs: Long,
    grid: BeatGrid?,
    quantizeMode: QuantizeMode,
    durationMs: Long,
): StagedRangeEdit {
    val duration = durationMs.coerceAtLeast(MIN_RANGE_LENGTH_MS)
    val snapped = snapTime(requestedMs, grid, quantizeMode, duration)
    return when (handle) {
        RangeHandle.START -> edit.copy(startMs = snapped.coerceIn(0L, (edit.endMs - MIN_RANGE_LENGTH_MS).coerceAtLeast(0L)))
        RangeHandle.END -> edit.copy(endMs = snapped.coerceIn((edit.startMs + MIN_RANGE_LENGTH_MS).coerceAtMost(duration), duration))
    }
}

fun nudgeRangeHandle(
    edit: StagedRangeEdit,
    handle: RangeHandle,
    deltaMs: Long,
    grid: BeatGrid?,
    quantizeMode: QuantizeMode,
    durationMs: Long,
): StagedRangeEdit {
    val current = if (handle == RangeHandle.START) edit.startMs else edit.endMs
    return updateRangeHandle(edit, handle, current + deltaMs, grid, quantizeMode, durationMs)
}

fun updateGridBpm(edit: StagedGridEdit, requestedBpm: Double): StagedGridEdit =
    edit.copy(bpm = requestedBpm.coerceIn(MIN_BPM, MAX_BPM))

fun transformedGridBpm(edit: StagedGridEdit, factor: Double): Double? {
    if (!factor.isFinite() || factor <= 0.0) return null
    val transformed = edit.bpm * factor
    return transformed.takeIf { it.isFinite() && it in MIN_BPM..MAX_BPM }
}

fun scaleGridBpm(edit: StagedGridEdit, factor: Double): StagedGridEdit =
    transformedGridBpm(edit, factor)?.let { edit.copy(bpm = it) } ?: edit

fun formatGridBpm(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

fun updateGridAnchor(edit: StagedGridEdit, requestedAnchorMs: Long, durationMs: Long): StagedGridEdit =
    edit.copy(anchorMs = requestedAnchorMs.coerceIn(0L, durationMs.coerceAtLeast(0L)))

fun nudgeGridAnchorByBeats(edit: StagedGridEdit, beats: Int, durationMs: Long): StagedGridEdit {
    val interval = 60_000.0 / edit.bpm.coerceIn(MIN_BPM, MAX_BPM)
    return updateGridAnchor(edit, (edit.anchorMs + beats * interval).roundToLong(), durationMs)
}

fun beatPositions(
    grid: BeatGrid?,
    viewport: WaveformViewport,
    durationMs: Long,
    maxBeats: Int = 256,
): List<Long> {
    if (grid == null || grid.bpm <= 0.0 || maxBeats <= 0) return emptyList()
    val safe = viewport.normalized(durationMs)
    val start = safe.startMs.toDouble()
    val end = safe.endMs(durationMs).toDouble()
    val interval = 60_000.0 / grid.bpm
    val firstIndex = ceil((start - grid.anchorMs) / interval).toLong()
    val result = ArrayList<Long>(minOf(maxBeats, 64))
    var index = firstIndex
    while (result.size < maxBeats) {
        val position = grid.anchorMs + index * interval
        if (position > end + 0.5) break
        if (position >= start - 0.5 && position >= 0.0 && position <= durationMs) {
            result += position.roundToLong()
        }
        index++
    }
    return result
}

fun selectOverlayAt(
    overlays: List<WaveformOverlay>,
    timeMs: Long,
    toleranceMs: Long,
): WaveformOverlay? = overlays
    .asSequence()
    .mapNotNull { overlay ->
        val distance = when {
            timeMs < overlay.startMs -> overlay.startMs - timeMs
            timeMs > overlay.endMs -> timeMs - overlay.endMs
            else -> 0L
        }
        if (distance <= toleranceMs.coerceAtLeast(0L)) overlay to distance else null
    }
    .minWithOrNull(compareBy<Pair<WaveformOverlay, Long>> { it.second }.thenBy { it.first.id })
    ?.first

fun waveformAmplitude(trackId: String, sampleIndex: Int): Float {
    var seed = 17
    trackId.forEach { seed = seed * 31 + it.code }
    val mixed = seed xor (sampleIndex * 0x45d9f3b)
    val normalized = ((mixed.toLong() and 0x7fff_ffffL) % 1000L) / 999f
    return 0.16f + normalized * 0.78f
}

fun formatWaveformTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
