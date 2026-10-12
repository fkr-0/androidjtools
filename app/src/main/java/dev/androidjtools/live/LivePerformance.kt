package dev.androidjtools.live

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin

const val LIVE_TOUCH_LATENCY_BUDGET_MS = 10L
private const val MIN_PLAYBACK_RATE = 0.50f
private const val MAX_PLAYBACK_RATE = 2.00f
private const val HOT_CUE_SLOTS = 8

enum class DeckId { A, B }

enum class CrossfaderCurve {
    LINEAR,
    EQUAL_POWER,
    POWER,
}

data class CrossfaderGains(
    val deckA: Float,
    val deckB: Float,
)

fun crossfaderGains(
    position: Float,
    curve: CrossfaderCurve,
    power: Float = 2.0f,
): CrossfaderGains {
    val t = (position.coerceIn(-1f, 1f) + 1f) / 2f
    return when (curve) {
        CrossfaderCurve.LINEAR -> CrossfaderGains(1f - t, t)
        CrossfaderCurve.EQUAL_POWER -> {
            val angle = t * PI.toFloat() / 2f
            CrossfaderGains(cos(angle), sin(angle))
        }
        CrossfaderCurve.POWER -> {
            val exponent = power.coerceIn(0.25f, 8f).toDouble()
            CrossfaderGains(
                Math.pow((1f - t).toDouble(), exponent).toFloat(),
                Math.pow(t.toDouble(), exponent).toFloat(),
            )
        }
    }
}

fun synchronizedPlaybackRate(sourceBpm: Double?, masterBpm: Double?): Float? {
    if (sourceBpm == null || masterBpm == null) return null
    if (!sourceBpm.isFinite() || !masterBpm.isFinite() || sourceBpm <= 0.0 || masterBpm <= 0.0) return null
    return (masterBpm / sourceBpm).toFloat().coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
}

enum class LiveEffectType {
    FILTER,
    ECHO,
    FLANGER,
    GATE,
}

data class LiveEffectState(
    val enabled: Boolean = false,
    val mix: Float = 0.35f,
    /** Generic normalized parameter: cutoff/feedback/rate/threshold depending on [LiveEffectType]. */
    val parameter: Float = 0.5f,
) {
    fun normalized(): LiveEffectState = copy(
        mix = mix.coerceIn(0f, 1f),
        parameter = parameter.coerceIn(0f, 1f),
    )
}

data class LiveLoop(
    val startMs: Long,
    val endMs: Long,
    val beats: Int?,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class LiveHotCue(
    val slot: Int,
    val positionMs: Long,
)

data class LiveDeckState(
    val trackId: String? = null,
    val durationMs: Long = 0L,
    val bpm: Double? = null,
    val beatGrid: BeatGrid? = null,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val syncEnabled: Boolean = false,
    val playbackRate: Float = 1f,
    val gain: Float = 1f,
    val loop: LiveLoop? = null,
    val hotCues: Map<Int, LiveHotCue> = emptyMap(),
    val effects: Map<LiveEffectType, LiveEffectState> = defaultEffects(),
)

private fun defaultEffects(): Map<LiveEffectType, LiveEffectState> =
    LiveEffectType.entries.associateWith { LiveEffectState() }

enum class MidiMessageType { NOTE, CONTROL_CHANGE }

sealed interface LiveMidiAction {
    data object Crossfader : LiveMidiAction
    data class TogglePlay(val deck: DeckId) : LiveMidiAction
    data class ToggleSync(val deck: DeckId) : LiveMidiAction
    data class HotCue(val deck: DeckId, val slot: Int) : LiveMidiAction
    data class ToggleLoop(val deck: DeckId, val beats: Int) : LiveMidiAction
    data class EffectMix(val deck: DeckId, val effect: LiveEffectType) : LiveMidiAction
}

data class MidiBinding(
    val type: MidiMessageType,
    val channel: Int,
    val number: Int,
    val action: LiveMidiAction,
) {
    init {
        require(channel in 0..15)
        require(number in 0..127)
    }
}

data class LivePerformanceState(
    val deckA: LiveDeckState = LiveDeckState(),
    val deckB: LiveDeckState = LiveDeckState(),
    val masterDeck: DeckId = DeckId.A,
    val crossfaderPosition: Float = 0f,
    val crossfaderCurve: CrossfaderCurve = CrossfaderCurve.EQUAL_POWER,
    val crossfaderPower: Float = 2f,
    val midiBindings: List<MidiBinding> = defaultMidiBindings(),
) {
    fun deck(id: DeckId): LiveDeckState = if (id == DeckId.A) deckA else deckB

    fun withDeck(id: DeckId, deck: LiveDeckState): LivePerformanceState =
        if (id == DeckId.A) copy(deckA = deck) else copy(deckB = deck)
}

fun defaultMidiBindings(): List<MidiBinding> = listOf(
    MidiBinding(MidiMessageType.CONTROL_CHANGE, 0, 1, LiveMidiAction.Crossfader),
    MidiBinding(MidiMessageType.NOTE, 0, 36, LiveMidiAction.TogglePlay(DeckId.A)),
    MidiBinding(MidiMessageType.NOTE, 0, 37, LiveMidiAction.TogglePlay(DeckId.B)),
    MidiBinding(MidiMessageType.NOTE, 0, 38, LiveMidiAction.ToggleSync(DeckId.A)),
    MidiBinding(MidiMessageType.NOTE, 0, 39, LiveMidiAction.ToggleSync(DeckId.B)),
    MidiBinding(MidiMessageType.NOTE, 0, 40, LiveMidiAction.HotCue(DeckId.A, 1)),
    MidiBinding(MidiMessageType.NOTE, 0, 41, LiveMidiAction.HotCue(DeckId.B, 1)),
    MidiBinding(MidiMessageType.NOTE, 0, 42, LiveMidiAction.ToggleLoop(DeckId.A, 4)),
    MidiBinding(MidiMessageType.NOTE, 0, 43, LiveMidiAction.ToggleLoop(DeckId.B, 4)),
    MidiBinding(MidiMessageType.CONTROL_CHANGE, 0, 16, LiveMidiAction.EffectMix(DeckId.A, LiveEffectType.FILTER)),
    MidiBinding(MidiMessageType.CONTROL_CHANGE, 0, 17, LiveMidiAction.EffectMix(DeckId.B, LiveEffectType.FILTER)),
)

interface LiveAudioEngine {
    /**
     * All methods are control-thread calls and must return quickly. Implementations must hand work to
     * their real-time engine without blocking the Compose/UI thread.
     */
    fun load(deck: DeckId, trackId: String)
    fun setPlaying(deck: DeckId, playing: Boolean)
    fun seek(deck: DeckId, positionMs: Long)
    fun setPlaybackRate(deck: DeckId, rate: Float)
    fun setGain(deck: DeckId, gain: Float)
    fun setLoop(deck: DeckId, loop: LiveLoop?)
    fun setEffect(deck: DeckId, type: LiveEffectType, state: LiveEffectState)
    fun release() = Unit
}

object NoOpLiveAudioEngine : LiveAudioEngine {
    override fun load(deck: DeckId, trackId: String) = Unit
    override fun setPlaying(deck: DeckId, playing: Boolean) = Unit
    override fun seek(deck: DeckId, positionMs: Long) = Unit
    override fun setPlaybackRate(deck: DeckId, rate: Float) = Unit
    override fun setGain(deck: DeckId, gain: Float) = Unit
    override fun setLoop(deck: DeckId, loop: LiveLoop?) = Unit
    override fun setEffect(deck: DeckId, type: LiveEffectType, state: LiveEffectState) = Unit
}

class LivePerformanceController(
    private val engine: LiveAudioEngine = NoOpLiveAudioEngine,
    initialState: LivePerformanceState = LivePerformanceState(),
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<LivePerformanceState> = mutableState.asStateFlow()

    init {
        applyCrossfader(initialState)
    }

    fun loadDeck(deck: DeckId, track: Track, beatGrid: BeatGrid? = null) {
        if (!track.playable) return
        val nextDeck = LiveDeckState(
            trackId = track.id,
            durationMs = track.durationMs.coerceAtLeast(0L),
            bpm = beatGrid?.bpm ?: track.bpm,
            beatGrid = beatGrid,
            gain = state.value.deck(deck).gain,
        )
        engine.load(deck, track.id)
        mutableState.value = state.value.withDeck(deck, nextDeck)
        recomputeSync()
        applyCrossfader(state.value)
    }

    fun unloadDeck(deck: DeckId) {
        engine.setPlaying(deck, false)
        engine.setLoop(deck, null)
        mutableState.value = state.value.withDeck(deck, LiveDeckState(gain = state.value.deck(deck).gain))
        recomputeSync()
    }

    fun setMasterDeck(deck: DeckId) {
        if (state.value.masterDeck == deck) return
        mutableState.value = state.value.copy(masterDeck = deck)
        recomputeSync()
    }

    fun togglePlay(deck: DeckId) {
        val current = state.value.deck(deck)
        if (current.trackId == null) return
        val playing = !current.playing
        engine.setPlaying(deck, playing)
        mutableState.value = state.value.withDeck(deck, current.copy(playing = playing))
    }

    fun setSync(deck: DeckId, enabled: Boolean) {
        val current = state.value.deck(deck)
        mutableState.value = state.value.withDeck(deck, current.copy(syncEnabled = enabled))
        recomputeSync()
    }

    fun setDeckBpm(deck: DeckId, bpm: Double): Boolean {
        if (!bpm.isFinite() || bpm !in 20.0..300.0) return false
        val current = state.value.deck(deck)
        if (current.trackId == null) return false
        val adjustedGrid = current.beatGrid?.copy(bpm = bpm)
        mutableState.value = state.value.withDeck(
            deck,
            current.copy(bpm = bpm, beatGrid = adjustedGrid),
        )
        recomputeSync()
        return true
    }

    fun adjustDeckBpm(deck: DeckId, delta: Double): Boolean {
        val current = state.value.deck(deck)
        val baseline = current.bpm ?: current.beatGrid?.bpm ?: 120.0
        return setDeckBpm(deck, (baseline + delta).coerceIn(20.0, 300.0))
    }

    fun seek(deck: DeckId, positionMs: Long) {
        val current = state.value.deck(deck)
        if (current.trackId == null) return
        val target = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(0L))
        engine.seek(deck, target)
        mutableState.value = state.value.withDeck(deck, current.copy(positionMs = target))
    }

    /** Engine/ticker callback; kept allocation-light so UI state can follow the audio clock. */
    fun reportPosition(deck: DeckId, positionMs: Long) {
        val current = state.value.deck(deck)
        if (current.trackId == null) return
        mutableState.value = state.value.withDeck(
            deck,
            current.copy(positionMs = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(0L))),
        )
    }

    fun setCrossfader(position: Float) {
        val next = state.value.copy(crossfaderPosition = position.coerceIn(-1f, 1f))
        mutableState.value = next
        applyCrossfader(next)
    }

    fun setCrossfaderCurve(curve: CrossfaderCurve, power: Float = state.value.crossfaderPower) {
        val next = state.value.copy(crossfaderCurve = curve, crossfaderPower = power.coerceIn(0.25f, 8f))
        mutableState.value = next
        applyCrossfader(next)
    }

    fun setQuantizedLoop(deck: DeckId, beats: Int): Boolean {
        if (beats <= 0) return false
        val current = state.value.deck(deck)
        if (current.trackId == null || current.durationMs <= 0L) return false
        val interval = beatIntervalMs(current) ?: return false
        val length = (interval * beats).roundToLong().coerceAtLeast(1L)
        var start = quantizePosition(current.positionMs, current)
        if (start + length > current.durationMs) {
            start = (current.durationMs - length).coerceAtLeast(0L)
            start = quantizePosition(start, current)
        }
        val end = (start + length).coerceAtMost(current.durationMs)
        if (end <= start) return false
        val loop = LiveLoop(start, end, beats)
        engine.setLoop(deck, loop)
        mutableState.value = state.value.withDeck(deck, current.copy(loop = loop))
        return true
    }

    fun clearLoop(deck: DeckId) {
        val current = state.value.deck(deck)
        if (current.loop == null) return
        engine.setLoop(deck, null)
        mutableState.value = state.value.withDeck(deck, current.copy(loop = null))
    }

    fun setHotCue(deck: DeckId, slot: Int): Boolean {
        if (slot !in 1..HOT_CUE_SLOTS) return false
        val current = state.value.deck(deck)
        if (current.trackId == null) return false
        val cue = LiveHotCue(slot, quantizePosition(current.positionMs, current))
        mutableState.value = state.value.withDeck(
            deck,
            current.copy(hotCues = current.hotCues + (slot to cue)),
        )
        return true
    }

    fun clearHotCue(deck: DeckId, slot: Int) {
        val current = state.value.deck(deck)
        mutableState.value = state.value.withDeck(deck, current.copy(hotCues = current.hotCues - slot))
    }

    fun jumpHotCue(deck: DeckId, slot: Int): Boolean {
        val cue = state.value.deck(deck).hotCues[slot] ?: return false
        seek(deck, cue.positionMs)
        return true
    }

    fun triggerHotCue(deck: DeckId, slot: Int): Boolean =
        if (state.value.deck(deck).hotCues.containsKey(slot)) jumpHotCue(deck, slot) else setHotCue(deck, slot)

    fun setEffect(
        deck: DeckId,
        type: LiveEffectType,
        enabled: Boolean = state.value.deck(deck).effects[type]?.enabled ?: false,
        mix: Float = state.value.deck(deck).effects[type]?.mix ?: 0.35f,
        parameter: Float = state.value.deck(deck).effects[type]?.parameter ?: 0.5f,
    ) {
        val current = state.value.deck(deck)
        val effect = LiveEffectState(enabled, mix, parameter).normalized()
        engine.setEffect(deck, type, effect)
        mutableState.value = state.value.withDeck(
            deck,
            current.copy(effects = current.effects + (type to effect)),
        )
    }

    fun bindMidi(binding: MidiBinding) {
        val withoutCollision = state.value.midiBindings.filterNot {
            it.type == binding.type && it.channel == binding.channel && it.number == binding.number
        }
        mutableState.value = state.value.copy(midiBindings = withoutCollision + binding)
    }

    fun unbindMidi(type: MidiMessageType, channel: Int, number: Int) {
        mutableState.value = state.value.copy(
            midiBindings = state.value.midiBindings.filterNot {
                it.type == type && it.channel == channel && it.number == number
            },
        )
    }

    fun handleMidi(type: MidiMessageType, channel: Int, number: Int, value: Int): Boolean {
        if (channel !in 0..15 || number !in 0..127 || value !in 0..127) return false
        val binding = state.value.midiBindings.firstOrNull {
            it.type == type && it.channel == channel && it.number == number
        } ?: return false

        val normalized = value / 127f
        when (val action = binding.action) {
            LiveMidiAction.Crossfader -> setCrossfader(normalized * 2f - 1f)
            is LiveMidiAction.TogglePlay -> if (value > 0) togglePlay(action.deck)
            is LiveMidiAction.ToggleSync -> if (value > 0) {
                val deckState = state.value.deck(action.deck)
                setSync(action.deck, !deckState.syncEnabled)
            }
            is LiveMidiAction.HotCue -> if (value > 0) triggerHotCue(action.deck, action.slot)
            is LiveMidiAction.ToggleLoop -> if (value > 0) {
                if (state.value.deck(action.deck).loop == null) setQuantizedLoop(action.deck, action.beats)
                else clearLoop(action.deck)
            }
            is LiveMidiAction.EffectMix -> {
                val current = state.value.deck(action.deck).effects.getValue(action.effect)
                setEffect(action.deck, action.effect, enabled = value > 0, mix = normalized, parameter = current.parameter)
            }
        }
        return true
    }

    fun release() = engine.release()

    private fun recomputeSync() {
        var next = state.value
        val master = next.deck(next.masterDeck)
        for (deck in DeckId.entries) {
            val current = next.deck(deck)
            val targetRate = if (deck == next.masterDeck || !current.syncEnabled) {
                1f
            } else {
                synchronizedPlaybackRate(current.bpm, master.bpm) ?: 1f
            }
            if (current.playbackRate != targetRate) {
                engine.setPlaybackRate(deck, targetRate)
                next = next.withDeck(deck, current.copy(playbackRate = targetRate))
            }
        }
        mutableState.value = next
    }

    private fun applyCrossfader(snapshot: LivePerformanceState) {
        val gains = crossfaderGains(
            position = snapshot.crossfaderPosition,
            curve = snapshot.crossfaderCurve,
            power = snapshot.crossfaderPower,
        )
        engine.setGain(DeckId.A, gains.deckA)
        engine.setGain(DeckId.B, gains.deckB)
        mutableState.value = snapshot.copy(
            deckA = snapshot.deckA.copy(gain = gains.deckA),
            deckB = snapshot.deckB.copy(gain = gains.deckB),
        )
    }

    private fun beatIntervalMs(deck: LiveDeckState): Double? {
        val bpm = deck.beatGrid?.bpm ?: deck.bpm ?: return null
        if (!bpm.isFinite() || bpm <= 0.0) return null
        return 60_000.0 / bpm
    }

    private fun quantizePosition(positionMs: Long, deck: LiveDeckState): Long {
        val interval = beatIntervalMs(deck) ?: return positionMs.coerceIn(0L, deck.durationMs)
        val anchor = deck.beatGrid?.anchorMs ?: 0L
        val index = round((positionMs - anchor) / interval)
        return (anchor + index * interval).roundToLong().coerceIn(0L, deck.durationMs)
    }
}
