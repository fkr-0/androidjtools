package dev.androidjtools.ui.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.ProviderUiState
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class WaveformGestureMode { PAN_ZOOM, SCRUB }

@Composable
fun WaveformScreen(providers: AppProviders) {
    val currentId by providers.playback.currentTrackId.collectAsState()
    val playing by providers.playback.isPlaying.collectAsState()
    val positionMs by providers.playback.positionMs.collectAsState()
    val tracks by providers.library.tracks.collectAsState()
    val providerState by providers.runtime.uiState.collectAsState()
    val pendingMutations by providers.journal.pending.collectAsState()
    val trackId = currentId ?: tracks.firstOrNull()?.id
    val track = trackId?.let(providers.library::track)

    val cues by collectPreparation(trackId, emptyList()) { providers.preparation.cues(it) }
    val loops by collectPreparation(trackId, emptyList()) { providers.preparation.loops(it) }
    val beatGrid by collectPreparation<BeatGrid?>(trackId, null) { providers.preparation.beatGrid(it) }
    val suggestions by collectPreparation(trackId, emptyList()) { providers.analysis.suggestions(it) }

    if (track == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Preparation", style = MaterialTheme.typography.headlineMedium)
            Text("No track is available for waveform preparation.")
        }
        return
    }

    val haptics = LocalHapticFeedback.current
    val canonicalOverlays = remember(cues, loops, suggestions) { buildWaveformOverlays(cues, loops, suggestions) }
    val advisories = remember(suggestions) { analysisAdvisories(suggestions) }
    var viewport by remember(track.id, track.durationMs) { mutableStateOf(WaveformViewport()) }
    var selectedOverlayId by remember(track.id) { mutableStateOf<String?>(null) }
    var quantizeMode by remember(track.id) { mutableStateOf(QuantizeMode.BEAT) }
    var gestureMode by remember(track.id) { mutableStateOf(WaveformGestureMode.PAN_ZOOM) }
    var rangeHistory by remember(track.id) { mutableStateOf<EditHistory<StagedRangeEdit>?>(null) }
    var gridHistory by remember(track.id, beatGrid) {
        mutableStateOf(beatGrid?.let { EditHistory(StagedGridEdit.from(it)) })
    }

    val effectiveGrid = gridHistory?.current?.toBeatGrid() ?: beatGrid
    val overlays = remember(canonicalOverlays, rangeHistory?.current) {
        applyStagedRange(canonicalOverlays, rangeHistory?.current)
    }
    val selected = overlays.firstOrNull { it.id == selectedOverlayId }

    val selectOverlay: (String?) -> Unit = { id ->
        selectedOverlayId = id
        val canonical = canonicalOverlays.firstOrNull { it.id == id }
        rangeHistory = canonical?.let(StagedRangeEdit::from)?.let { EditHistory(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Preparation", style = MaterialTheme.typography.headlineMedium)
        Text("${track.title} · ${track.artist}", style = MaterialTheme.typography.titleMedium)
        Text(
            "${formatWaveformTime(positionMs)} / ${formatWaveformTime(track.durationMs)} · ${viewport.zoom.roundToInt()}× zoom",
            modifier = Modifier.testTag("waveform-time-zoom"),
            style = MaterialTheme.typography.bodySmall,
        )

        PreparationRuntimeStatus(providerState, pendingMutations.size, rangeHistory?.current?.dirty == true, gridHistory?.current?.dirty == true)
        OverviewWaveform(track, positionMs, viewport, overlays)
        DetailedWaveform(
            track = track,
            positionMs = positionMs,
            viewport = viewport,
            beatGrid = effectiveGrid,
            overlays = overlays,
            selectedOverlayId = selectedOverlayId,
            stagedRange = rangeHistory?.current,
            gestureMode = gestureMode,
            onViewportChange = { viewport = it },
            onSeek = providers.playback::seek,
            onSelectOverlay = selectOverlay,
            onRangeHandleDelta = { handle, deltaMs ->
                val history = rangeHistory ?: return@DetailedWaveform
                rangeHistory = history.update(
                    nudgeRangeHandle(history.current, handle, deltaMs, effectiveGrid, quantizeMode, track.durationMs)
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { viewport = viewport.zoomBy(track.durationMs, 0.5, 0.5f) }) { Text("Zoom out") }
            OutlinedButton(onClick = { viewport = viewport.zoomBy(track.durationMs, 2.0, 0.5f) }) { Text("Zoom in") }
            OutlinedButton(onClick = { viewport = WaveformViewport() }) { Text("Overview") }
            OutlinedButton(onClick = { gestureMode = if (gestureMode == WaveformGestureMode.PAN_ZOOM) WaveformGestureMode.SCRUB else WaveformGestureMode.PAN_ZOOM }) {
                Text(if (gestureMode == WaveformGestureMode.PAN_ZOOM) "Gesture: pan/zoom" else "Gesture: scrub")
            }
        }

        BeatJumpControls(
            enabled = effectiveGrid != null,
            onJump = { beats ->
                providers.playback.seek(jumpByBeats(positionMs, effectiveGrid, beats, track.durationMs))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        )

        Text(
            "Canonical cue/loop = solid · analysis candidate = dashed",
            style = MaterialTheme.typography.labelMedium,
        )
        OverlaySelector(overlays, selectedOverlayId, selectOverlay)

        selected?.let {
            Text(
                "Focused ${it.source.name.lowercase()}: ${it.label} @ ${formatWaveformTime(it.startMs)}",
                modifier = Modifier.testTag("waveform-focused-overlay"),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        rangeHistory?.let { history ->
            RangeEditPanel(
                history = history,
                quantizeMode = quantizeMode,
                grid = effectiveGrid,
                durationMs = track.durationMs,
                onHistoryChange = { rangeHistory = it },
                onQuantizeChange = { quantizeMode = it },
                onHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }

        gridHistory?.let { history ->
            GridEditPanel(
                history = history,
                durationMs = track.durationMs,
                onHistoryChange = { gridHistory = it },
                onHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }

        visibleWaveformAdvisories(advisories).forEach { advisory ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("waveform-advisory-${advisory.id}"),
                shape = MaterialTheme.shapes.small,
                tonalElevation = if (advisory.warning) 3.dp else 1.dp,
            ) {
                Text(
                    if (advisory.warning) "Safety · ${advisory.label}" else "Suggestion · ${advisory.label}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { providers.playback.play(track.id) }) { Text("Play") }
            Button(onClick = providers.playback::toggle) { Text(if (playing) "Pause" else "Resume") }
        }
    }
}

@Composable
private fun <T> collectPreparation(
    trackId: String?,
    initial: T,
    flowForTrack: (String) -> kotlinx.coroutines.flow.StateFlow<T>,
) = produceState(initialValue = initial, key1 = trackId) {
    if (trackId == null) value = initial else flowForTrack(trackId).collect { value = it }
}

@Composable
private fun PreparationRuntimeStatus(
    providerState: ProviderUiState,
    pendingMutationCount: Int,
    rangeDirty: Boolean,
    gridDirty: Boolean,
) {
    val stateText = when (providerState) {
        ProviderUiState.Ready -> "online"
        ProviderUiState.Loading -> "loading"
        ProviderUiState.Empty -> "empty"
        is ProviderUiState.Error -> "error: ${providerState.message}"
        ProviderUiState.Offline -> "offline"
        is ProviderUiState.Conflict -> "conflict: ${providerState.detail}"
    }
    val localPending = listOf(rangeDirty, gridDirty).count { it }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("waveform-runtime-status"),
        tonalElevation = if (providerState == ProviderUiState.Ready && pendingMutationCount == 0 && localPending == 0) 0.dp else 2.dp,
    ) {
        Text(
            "Preparation status · $stateText · journal $pendingMutationCount · local drafts $localPending",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BeatJumpControls(enabled: Boolean, onJump: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Beat jump", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        listOf(-4, -1, 1, 4).forEach { beats ->
            OutlinedButton(enabled = enabled, onClick = { onJump(beats) }) {
                Text(if (beats > 0) "+$beats" else beats.toString())
            }
        }
    }
}

@Composable
private fun OverlaySelector(
    overlays: List<WaveformOverlay>,
    selectedOverlayId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("waveform-overlay-selector"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        overlays.take(8).forEach { overlay ->
            TextButton(
                onClick = { onSelect(overlay.id) },
                modifier = Modifier.testTag("waveform-overlay-${overlay.id}"),
            ) {
                val prefix = when {
                    overlay.source == WaveformOverlaySource.CANDIDATE -> "◇"
                    overlay.isRange -> "↔"
                    else -> "◆"
                }
                Text("$prefix ${overlay.label}${if (selectedOverlayId == overlay.id) " •" else ""}")
            }
        }
    }
}

@Composable
private fun RangeEditPanel(
    history: EditHistory<StagedRangeEdit>,
    quantizeMode: QuantizeMode,
    grid: BeatGrid?,
    durationMs: Long,
    onHistoryChange: (EditHistory<StagedRangeEdit>) -> Unit,
    onQuantizeChange: (QuantizeMode) -> Unit,
    onHaptic: () -> Unit,
) {
    val edit = history.current
    var startText by remember(edit.id) { mutableStateOf(edit.startMs.toString()) }
    var endText by remember(edit.id) { mutableStateOf(edit.endMs.toString()) }
    LaunchedEffect(edit.startMs) { startText = edit.startMs.toString() }
    LaunchedEffect(edit.endMs) { endText = edit.endMs.toString() }
    val beatMs = beatIntervalMs(grid)?.roundToLong()?.coerceAtLeast(1L) ?: 1_000L

    Surface(modifier = Modifier.fillMaxWidth().testTag("waveform-range-editor"), tonalElevation = 2.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Loop/range draft · ${edit.label}", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = startText,
                onValueChange = { value ->
                    if (value.all(Char::isDigit)) {
                        startText = value
                        value.toLongOrNull()?.let { requested ->
                            onHistoryChange(history.update(updateRangeHandle(edit, RangeHandle.START, requested, grid, quantizeMode, durationMs)))
                        }
                    }
                },
                label = { Text("Start ms") },
                modifier = Modifier.fillMaxWidth().testTag("range-start-input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { value ->
                    if (value.all(Char::isDigit)) {
                        endText = value
                        value.toLongOrNull()?.let { requested ->
                            onHistoryChange(history.update(updateRangeHandle(edit, RangeHandle.END, requested, grid, quantizeMode, durationMs)))
                        }
                    }
                },
                label = { Text("End ms") },
                modifier = Modifier.fillMaxWidth().testTag("range-end-input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onQuantizeChange(quantizeMode.next()) }, modifier = Modifier.testTag("range-quantize-mode")) {
                    Text("Quantize: ${quantizeMode.label}")
                }
                OutlinedButton(onClick = {
                    onHistoryChange(history.update(updateRangeHandle(edit, RangeHandle.START, edit.startMs, grid, quantizeMode, durationMs)))
                    onHaptic()
                }) { Text("Snap start") }
                OutlinedButton(onClick = {
                    onHistoryChange(history.update(updateRangeHandle(edit, RangeHandle.END, edit.endMs, grid, quantizeMode, durationMs)))
                    onHaptic()
                }) { Text("Snap end") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onHistoryChange(history.update(nudgeRangeHandle(edit, RangeHandle.START, -beatMs, grid, quantizeMode, durationMs))) }) { Text("Start -1 beat") }
                OutlinedButton(onClick = { onHistoryChange(history.update(nudgeRangeHandle(edit, RangeHandle.START, beatMs, grid, quantizeMode, durationMs))) }) { Text("Start +1 beat") }
                OutlinedButton(onClick = { onHistoryChange(history.update(nudgeRangeHandle(edit, RangeHandle.END, -beatMs, grid, quantizeMode, durationMs))) }) { Text("End -1 beat") }
                OutlinedButton(onClick = { onHistoryChange(history.update(nudgeRangeHandle(edit, RangeHandle.END, beatMs, grid, quantizeMode, durationMs))) }) { Text("End +1 beat") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = history.canUndo, onClick = { onHistoryChange(history.undo()) }) { Text("Undo range") }
                OutlinedButton(enabled = edit.dirty, onClick = { onHistoryChange(history.update(edit.reset())) }) { Text("Discard range") }
            }
            Text(
                if (edit.dirty) "Pending local range edit · persistence awaits preparation mutation authority." else "Canonical range unchanged.",
                modifier = Modifier.testTag("range-edit-status"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GridEditPanel(
    history: EditHistory<StagedGridEdit>,
    durationMs: Long,
    onHistoryChange: (EditHistory<StagedGridEdit>) -> Unit,
    onHaptic: () -> Unit,
) {
    val edit = history.current
    var bpmText by remember(edit.trackId) { mutableStateOf("%.2f".format(edit.bpm)) }
    var anchorText by remember(edit.trackId) { mutableStateOf(edit.anchorMs.toString()) }
    LaunchedEffect(edit.bpm) { bpmText = "%.2f".format(edit.bpm) }
    LaunchedEffect(edit.anchorMs) { anchorText = edit.anchorMs.toString() }

    Surface(modifier = Modifier.fillMaxWidth().testTag("waveform-grid-editor"), tonalElevation = 1.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Beat-grid draft", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = bpmText,
                onValueChange = { value ->
                    if (value.all { it.isDigit() || it == '.' }) {
                        bpmText = value
                        value.toDoubleOrNull()?.let { bpm -> onHistoryChange(history.update(updateGridBpm(edit, bpm))) }
                    }
                },
                label = { Text("BPM") },
                modifier = Modifier.fillMaxWidth().testTag("grid-bpm-input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
            OutlinedTextField(
                value = anchorText,
                onValueChange = { value ->
                    if (value.all(Char::isDigit)) {
                        anchorText = value
                        value.toLongOrNull()?.let { anchor -> onHistoryChange(history.update(updateGridAnchor(edit, anchor, durationMs))) }
                    }
                },
                label = { Text("Grid anchor ms") },
                modifier = Modifier.fillMaxWidth().testTag("grid-anchor-input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onHistoryChange(history.update(updateGridBpm(edit, edit.bpm - 0.01))) }) { Text("BPM -0.01") }
                OutlinedButton(onClick = { onHistoryChange(history.update(updateGridBpm(edit, edit.bpm + 0.01))) }) { Text("BPM +0.01") }
                OutlinedButton(onClick = {
                    onHistoryChange(history.update(nudgeGridAnchorByBeats(edit, -1, durationMs)))
                    onHaptic()
                }) { Text("Anchor -1 beat") }
                OutlinedButton(onClick = {
                    onHistoryChange(history.update(nudgeGridAnchorByBeats(edit, 1, durationMs)))
                    onHaptic()
                }) { Text("Anchor +1 beat") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = history.canUndo, onClick = { onHistoryChange(history.undo()) }) { Text("Undo grid") }
                OutlinedButton(enabled = edit.dirty, onClick = { onHistoryChange(history.update(edit.reset())) }) { Text("Discard grid") }
            }
            Text(
                if (edit.dirty) "Pending local grid edit · rendered immediately, not yet persisted." else "Canonical beat grid unchanged.",
                modifier = Modifier.testTag("grid-edit-status"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OverviewWaveform(
    track: Track,
    positionMs: Long,
    viewport: WaveformViewport,
    overlays: List<WaveformOverlay>,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val waveform = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    val playhead = MaterialTheme.colorScheme.error
    val canonical = MaterialTheme.colorScheme.primary
    val candidate = MaterialTheme.colorScheme.tertiary
    val viewportColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        Modifier
            .testTag("waveform-overview")
            .fillMaxWidth()
            .height(72.dp)
            .background(surface)
    ) {
        val mid = size.height / 2f
        val bars = 120
        repeat(bars) { index ->
            val x = (index + 0.5f) * size.width / bars
            val amp = waveformAmplitude(track.id, index) * size.height * 0.78f
            drawLine(waveform, Offset(x, mid - amp / 2f), Offset(x, mid + amp / 2f), strokeWidth = 1.5f)
        }
        overlays.forEach { overlay ->
            val x = overlay.startMs.toFloat() / track.durationMs.coerceAtLeast(1L) * size.width
            drawLine(
                if (overlay.source == WaveformOverlaySource.CANONICAL) canonical else candidate,
                Offset(x, 0f),
                Offset(x, size.height),
                strokeWidth = if (overlay.source == WaveformOverlaySource.CANONICAL) 2f else 1.5f,
            )
        }
        val playheadX = positionMs.coerceIn(0L, track.durationMs).toFloat() / track.durationMs.coerceAtLeast(1L) * size.width
        drawLine(playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2.5f)

        val safe = viewport.normalized(track.durationMs)
        val startX = safe.startMs.toFloat() / track.durationMs.coerceAtLeast(1L) * size.width
        val endX = safe.endMs(track.durationMs).toFloat() / track.durationMs.coerceAtLeast(1L) * size.width
        drawRect(
            viewportColor,
            topLeft = Offset(startX, 1f),
            size = Size((endX - startX).coerceAtLeast(2f), size.height - 2f),
            style = Stroke(width = 2f),
        )
    }
}

@Composable
private fun DetailedWaveform(
    track: Track,
    positionMs: Long,
    viewport: WaveformViewport,
    beatGrid: BeatGrid?,
    overlays: List<WaveformOverlay>,
    selectedOverlayId: String?,
    stagedRange: StagedRangeEdit?,
    gestureMode: WaveformGestureMode,
    onViewportChange: (WaveformViewport) -> Unit,
    onSeek: (Long) -> Unit,
    onSelectOverlay: (String?) -> Unit,
    onRangeHandleDelta: (RangeHandle, Long) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val waveform = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    val beat = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
    val playhead = MaterialTheme.colorScheme.error
    val canonical = MaterialTheme.colorScheme.primary
    val canonicalRange = MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f)
    val candidate = MaterialTheme.colorScheme.tertiary
    val candidateRange = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    val selected = MaterialTheme.colorScheme.onSurface
    val dashed = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f)
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .testTag("waveform-detail")
            .fillMaxWidth()
            .height(220.dp)
            .background(surface)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(track.id, track.durationMs, viewport, gestureMode) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    if (gestureMode == WaveformGestureMode.PAN_ZOOM) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            var next = viewport
                            if (zoom != 1f) next = next.zoomBy(track.durationMs, zoom.toDouble(), centroid.x / width)
                            if (pan.x != 0f) next = next.panByFraction(track.durationMs, -pan.x / width)
                            onViewportChange(next)
                        }
                    } else {
                        detectDragGestures(
                            onDragStart = { offset -> onSeek(viewport.timeAtFraction(track.durationMs, offset.x / width)) },
                            onDrag = { change, _ -> onSeek(viewport.timeAtFraction(track.durationMs, change.position.x / width)) },
                        )
                    }
                }
                .pointerInput(track.id, track.durationMs, viewport, overlays) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val time = viewport.timeAtFraction(track.durationMs, offset.x / width)
                        val tolerance = (viewport.visibleDurationMs(track.durationMs) * 0.018).roundToInt().toLong().coerceAtLeast(80L)
                        onSelectOverlay(selectOverlayAt(overlays, time, tolerance)?.id)
                        onSeek(time)
                    }
                }
        ) {
            val safe = viewport.normalized(track.durationMs)
            val visibleDuration = safe.visibleDurationMs(track.durationMs).coerceAtLeast(1L)
            fun xFor(timeMs: Long): Float = ((timeMs - safe.startMs).toFloat() / visibleDuration * size.width)
            fun visible(startMs: Long, endMs: Long = startMs): Boolean = endMs >= safe.startMs && startMs <= safe.endMs(track.durationMs)

            val mid = size.height / 2f
            val bars = 180
            repeat(bars) { index ->
                val fraction = (index + 0.5f) / bars
                val sampleTime = safe.timeAtFraction(track.durationMs, fraction)
                val globalSample = (sampleTime.toDouble() / track.durationMs.coerceAtLeast(1L) * 4096.0).roundToInt()
                val amp = waveformAmplitude(track.id, globalSample) * size.height * 0.82f
                val x = fraction * size.width
                drawLine(waveform, Offset(x, mid - amp / 2f), Offset(x, mid + amp / 2f), strokeWidth = 1.6f)
            }

            beatPositions(beatGrid, safe, track.durationMs).forEach { beatMs ->
                val x = xFor(beatMs)
                drawLine(beat, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            overlays.filter { it.isRange && visible(it.startMs, it.endMs) }.forEach { overlay ->
                val left = xFor(overlay.startMs).coerceIn(0f, size.width)
                val right = xFor(overlay.endMs).coerceIn(0f, size.width)
                val fill = if (overlay.source == WaveformOverlaySource.CANONICAL) canonicalRange else candidateRange
                drawRect(fill, Offset(left, 0f), Size((right - left).coerceAtLeast(2f), size.height))
                drawLine(
                    if (overlay.source == WaveformOverlaySource.CANONICAL) canonical else candidate,
                    Offset(left, 0f), Offset(left, size.height),
                    strokeWidth = if (overlay.id == selectedOverlayId) 4f else 2f,
                    pathEffect = if (overlay.source == WaveformOverlaySource.CANDIDATE) dashed else null,
                )
                drawLine(
                    if (overlay.source == WaveformOverlaySource.CANONICAL) canonical else candidate,
                    Offset(right, 0f), Offset(right, size.height),
                    strokeWidth = if (overlay.id == selectedOverlayId) 4f else 2f,
                    pathEffect = if (overlay.source == WaveformOverlaySource.CANDIDATE) dashed else null,
                )
            }

            overlays.filter { !it.isRange && visible(it.startMs) }.forEach { overlay ->
                val x = xFor(overlay.startMs)
                val color = if (overlay.source == WaveformOverlaySource.CANONICAL) canonical else candidate
                drawLine(
                    color,
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = if (overlay.id == selectedOverlayId) 4f else 2f,
                    pathEffect = if (overlay.source == WaveformOverlaySource.CANDIDATE) dashed else null,
                )
                drawCircle(if (overlay.id == selectedOverlayId) selected else color, radius = if (overlay.id == selectedOverlayId) 7f else 5f, center = Offset(x, 10f))
            }

            if (visible(positionMs)) {
                val x = xFor(positionMs)
                drawLine(playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }

        stagedRange?.let { edit ->
            val safe = viewport.normalized(track.durationMs)
            val visibleStart = safe.startMs
            val visibleEnd = safe.endMs(track.durationMs)
            val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val msPerPx = safe.visibleDurationMs(track.durationMs).toDouble() / widthPx.toDouble()
            if (edit.startMs in visibleStart..visibleEnd) {
                RangeHandleThumb(
                    tag = "range-handle-start",
                    fraction = safe.fractionForTime(track.durationMs, edit.startMs),
                    maxWidthDp = maxWidth,
                    colorRole = canonical,
                    onDeltaPx = { delta -> onRangeHandleDelta(RangeHandle.START, (delta * msPerPx).roundToLong()) },
                )
            }
            if (edit.endMs in visibleStart..visibleEnd) {
                RangeHandleThumb(
                    tag = "range-handle-end",
                    fraction = safe.fractionForTime(track.durationMs, edit.endMs),
                    maxWidthDp = maxWidth,
                    colorRole = canonical,
                    onDeltaPx = { delta -> onRangeHandleDelta(RangeHandle.END, (delta * msPerPx).roundToLong()) },
                )
            }
        }
    }
}

@Composable
private fun RangeHandleThumb(
    tag: String,
    fraction: Float,
    maxWidthDp: androidx.compose.ui.unit.Dp,
    colorRole: androidx.compose.ui.graphics.Color,
    onDeltaPx: (Float) -> Unit,
) {
    val handleSize = 22.dp
    val maxOffset = (maxWidthDp - handleSize).coerceAtLeast(0.dp)
    val offset = (maxWidthDp * fraction - handleSize / 2).coerceIn(0.dp, maxOffset)
    Surface(
        modifier = Modifier
            .testTag(tag)
            .offset(x = offset, y = 4.dp)
            .size(handleSize)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDeltaPx(delta) },
            ),
        shape = CircleShape,
        color = colorRole,
        shadowElevation = 3.dp,
        content = {},
    )
}
