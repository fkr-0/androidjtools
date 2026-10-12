package dev.androidjtools.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.live.CrossfaderCurve
import dev.androidjtools.live.DeckId
import dev.androidjtools.live.LiveDeckState
import dev.androidjtools.live.LiveEffectType
import dev.androidjtools.live.LivePerformanceController
import dev.androidjtools.ui.waveform.WaveformViewport
import dev.androidjtools.ui.waveform.beatPositions
import dev.androidjtools.ui.waveform.formatWaveformTime
import dev.androidjtools.ui.waveform.waveformAmplitude
import java.util.Locale

@Composable
fun LivePerformanceScreen(
    providers: AppProviders,
    controller: LivePerformanceController,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val tracks by providers.library.tracks.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Live performance", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Dual-deck transport, beat sync, loops, hot cues, FX controls and MIDI mapping.",
            style = MaterialTheme.typography.bodySmall,
        )

        TrackLoader(
            tracks = tracks,
            onLoad = { deck, track ->
                controller.loadDeck(deck, track, providers.preparation.beatGrid(track.id).value)
            },
        )

        DeckPanel(
            deck = DeckId.A,
            state = state.deckA,
            track = state.deckA.trackId?.let(providers.library::track),
            isMaster = state.masterDeck == DeckId.A,
            controller = controller,
        )
        DeckPanel(
            deck = DeckId.B,
            state = state.deckB,
            track = state.deckB.trackId?.let(providers.library::track),
            isMaster = state.masterDeck == DeckId.B,
            controller = controller,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Crossfader", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = state.crossfaderPosition,
                    onValueChange = controller::setCrossfader,
                    valueRange = -1f..1f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val next = CrossfaderCurve.entries[
                                (state.crossfaderCurve.ordinal + 1) % CrossfaderCurve.entries.size
                            ]
                            controller.setCrossfaderCurve(next, state.crossfaderPower)
                        },
                    ) {
                        Text("Curve: ${state.crossfaderCurve.name.lowercase().replace('_', ' ')}")
                    }
                    Text(
                        "A ${formatGain(state.deckA.gain)}  ·  B ${formatGain(state.deckB.gain)}",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.crossfaderCurve == CrossfaderCurve.POWER) {
                    Text("Power ${String.format(Locale.ROOT, "%.2f", state.crossfaderPower)}")
                    Slider(
                        value = state.crossfaderPower,
                        onValueChange = { controller.setCrossfaderCurve(CrossfaderCurve.POWER, it) },
                        valueRange = 0.25f..8f,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("MIDI map", style = MaterialTheme.typography.titleMedium)
                state.midiBindings.forEach { binding ->
                    Text(
                        "${binding.type.name.lowercase()} ch${binding.channel + 1} #${binding.number} → ${binding.action}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackLoader(
    tracks: List<Track>,
    onLoad: (DeckId, Track) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Load tracks", style = MaterialTheme.typography.titleMedium)
            if (tracks.isEmpty()) {
                Text("No tracks available from the active library provider.")
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks.take(32), key = { it.id }) { track ->
                        Card(Modifier.width(210.dp)) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(track.title, maxLines = 1)
                                Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                track.unplayableReason?.let { reason ->
                                    Text(
                                        reason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 2,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onLoad(DeckId.A, track) }, enabled = track.playable) { Text("Deck A") }
                                    Button(onClick = { onLoad(DeckId.B, track) }, enabled = track.playable) { Text("Deck B") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckPanel(
    deck: DeckId,
    state: LiveDeckState,
    track: Track?,
    isMaster: Boolean,
    controller: LivePerformanceController,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Deck ${deck.name}${if (isMaster) " · MASTER" else ""}",
                style = MaterialTheme.typography.titleLarge,
            )
            if (track == null || state.trackId == null) {
                Text("Load a track from the strip above.")
                return@Column
            }

            Text("${track.title} — ${track.artist}", maxLines = 1)
            Text(
                "${formatWaveformTime(state.positionMs)} / ${formatWaveformTime(state.durationMs)} · " +
                    "${formatBpm(state.bpm)} BPM · ${String.format(Locale.ROOT, "%.3f", state.playbackRate)}×",
                style = MaterialTheme.typography.bodySmall,
            )

            LiveDeckWaveform(
                track = track,
                deckState = state,
                onSeek = { controller.seek(deck, it) },
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = { controller.togglePlay(deck) }) {
                    Text(if (state.playing) "Pause" else "Play")
                }
                FilterChip(
                    selected = state.syncEnabled,
                    onClick = { controller.setSync(deck, !state.syncEnabled) },
                    label = { Text("Sync") },
                )
                FilterChip(
                    selected = isMaster,
                    onClick = { controller.setMasterDeck(deck) },
                    label = { Text("Master") },
                )
                OutlinedButton(onClick = { controller.adjustDeckBpm(deck, -1.0) }) { Text("BPM −1") }
                OutlinedButton(onClick = { controller.adjustDeckBpm(deck, 1.0) }) { Text("BPM +1") }
            }

            Text("Quantized loop", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(1, 2, 4, 8, 16).forEach { beats ->
                    FilterChip(
                        selected = state.loop?.beats == beats,
                        onClick = {
                            if (state.loop?.beats == beats) controller.clearLoop(deck)
                            else controller.setQuantizedLoop(deck, beats)
                        },
                        label = { Text("${beats}b") },
                    )
                }
                if (state.loop != null) {
                    OutlinedButton(onClick = { controller.clearLoop(deck) }) { Text("Clear") }
                }
            }

            Text("Hot cues", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..8).forEach { slot ->
                    val cue = state.hotCues[slot]
                    FilterChip(
                        selected = cue != null,
                        onClick = { controller.triggerHotCue(deck, slot) },
                        label = { Text(if (cue == null) "+${slot}" else "Cue ${slot}") },
                    )
                }
            }

            Text("Effects rack", style = MaterialTheme.typography.labelLarge)
            LiveEffectType.entries.forEach { type ->
                val effect = state.effects.getValue(type)
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            type.name.lowercase().replaceFirstChar(Char::uppercase),
                            modifier = Modifier.width(72.dp).padding(top = 10.dp),
                        )
                        Switch(
                            checked = effect.enabled,
                            onCheckedChange = {
                                controller.setEffect(
                                    deck = deck,
                                    type = type,
                                    enabled = it,
                                    mix = effect.mix,
                                    parameter = effect.parameter,
                                )
                            },
                        )
                        Text("mix", modifier = Modifier.padding(top = 10.dp))
                    }
                    Slider(
                        value = effect.mix,
                        onValueChange = {
                            controller.setEffect(
                                deck = deck,
                                type = type,
                                enabled = effect.enabled,
                                mix = it,
                                parameter = effect.parameter,
                            )
                        },
                    )
                    Text("parameter", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = effect.parameter,
                        onValueChange = {
                            controller.setEffect(
                                deck = deck,
                                type = type,
                                enabled = effect.enabled,
                                mix = effect.mix,
                                parameter = it,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveDeckWaveform(
    track: Track,
    deckState: LiveDeckState,
    onSeek: (Long) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val waveform = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val beat = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val playhead = MaterialTheme.colorScheme.error
    val loopFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val cue = MaterialTheme.colorScheme.tertiary
    val effectiveGrid = deckState.beatGrid ?: deckState.bpm?.let { bpm ->
        BeatGrid(trackId = track.id, anchorMs = 0L, bpm = bpm, revision = 0L)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(surface)
            .pointerInput(track.id, track.durationMs) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek((fraction * track.durationMs).toLong())
                }
            },
    ) {
        val duration = track.durationMs.coerceAtLeast(1L)
        val mid = size.height / 2f
        val bars = 140
        repeat(bars) { index ->
            val fraction = (index + 0.5f) / bars
            val amplitude = waveformAmplitude(track.id, index) * size.height * 0.78f
            val x = fraction * size.width
            drawLine(
                waveform,
                Offset(x, mid - amplitude / 2f),
                Offset(x, mid + amplitude / 2f),
                strokeWidth = 1.4f,
            )
        }

        beatPositions(effectiveGrid, WaveformViewport(), duration, maxBeats = 1024).forEach { position ->
            val x = position.toFloat() / duration * size.width
            drawLine(beat, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }

        deckState.loop?.let { loop ->
            val left = loop.startMs.toFloat() / duration * size.width
            val right = loop.endMs.toFloat() / duration * size.width
            drawRect(loopFill, Offset(left, 0f), Size((right - left).coerceAtLeast(2f), size.height))
        }

        deckState.hotCues.values.forEach { hotCue ->
            val x = hotCue.positionMs.toFloat() / duration * size.width
            drawLine(cue, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }

        val playheadX = deckState.positionMs.coerceIn(0L, duration).toFloat() / duration * size.width
        drawLine(playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 3f)
    }
}

private fun formatBpm(bpm: Double?): String =
    bpm?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

private fun formatGain(gain: Float): String =
    String.format(Locale.ROOT, "%.0f%%", gain.coerceIn(0f, 1f) * 100f)
