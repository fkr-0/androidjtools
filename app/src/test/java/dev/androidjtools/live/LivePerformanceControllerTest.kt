package dev.androidjtools.live

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePerformanceControllerTest {
    @Test
    fun bpmSyncFollowsMasterAndIsBounded() {
        assertEquals(1.25f, synchronizedPlaybackRate(100.0, 125.0)!!, 0.0001f)
        assertEquals(2.0f, synchronizedPlaybackRate(40.0, 200.0)!!, 0.0001f)
        assertEquals(0.5f, synchronizedPlaybackRate(200.0, 40.0)!!, 0.0001f)
        assertEquals(null, synchronizedPlaybackRate(null, 120.0))

        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        controller.loadDeck(DeckId.A, track("a", 120.0), grid("a", 120.0))
        controller.loadDeck(DeckId.B, track("b", 100.0), grid("b", 100.0))
        controller.setSync(DeckId.B, true)

        assertEquals(1.2f, controller.state.value.deckB.playbackRate, 0.0001f)
        assertEquals(1.2f, engine.rates.getValue(DeckId.B), 0.0001f)

        controller.setMasterDeck(DeckId.B)
        controller.setSync(DeckId.A, true)

        assertEquals(100.0 / 120.0, controller.state.value.deckA.playbackRate.toDouble(), 0.0001)
    }

    @Test
    fun manualBpmFallbackUpdatesSyncWhenProviderHasNoAnalysisMetadata() {
        val controller = LivePerformanceController()
        controller.loadDeck(DeckId.A, track("a", 120.0), grid("a", 120.0))
        controller.loadDeck(
            DeckId.B,
            Track("b", "b", "artist", durationMs = 120_000L, bpm = null),
        )
        controller.setSync(DeckId.B, true)

        assertEquals(1f, controller.state.value.deckB.playbackRate, 0f)
        assertTrue(controller.setDeckBpm(DeckId.B, 100.0))
        assertEquals(1.2f, controller.state.value.deckB.playbackRate, 0.0001f)
        assertFalse(controller.setDeckBpm(DeckId.B, 500.0))
    }

    @Test
    fun crossfaderCurvesProduceDeterministicDeckGains() {
        val linear = crossfaderGains(0f, CrossfaderCurve.LINEAR)
        assertEquals(0.5f, linear.deckA, 0.0001f)
        assertEquals(0.5f, linear.deckB, 0.0001f)

        val equalPower = crossfaderGains(0f, CrossfaderCurve.EQUAL_POWER)
        assertEquals(0.7071f, equalPower.deckA, 0.001f)
        assertEquals(0.7071f, equalPower.deckB, 0.001f)

        val hardLeft = crossfaderGains(-1f, CrossfaderCurve.POWER, power = 4f)
        val hardRight = crossfaderGains(1f, CrossfaderCurve.POWER, power = 4f)
        assertEquals(1f, hardLeft.deckA, 0f)
        assertEquals(0f, hardLeft.deckB, 0f)
        assertEquals(0f, hardRight.deckA, 0f)
        assertEquals(1f, hardRight.deckB, 0f)
    }

    @Test
    fun loopIsQuantizedToBeatGridAndCanBeCleared() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        controller.loadDeck(DeckId.A, track("a", 120.0), BeatGrid("a", 125L, 120.0, 1L))
        controller.seek(DeckId.A, 1_010L)

        assertTrue(controller.setQuantizedLoop(DeckId.A, 4))
        val loop = requireNotNull(controller.state.value.deckA.loop)
        assertEquals(1_125L, loop.startMs)
        assertEquals(3_125L, loop.endMs)
        assertEquals(4, loop.beats)
        assertEquals(loop, engine.loops[DeckId.A])

        controller.clearLoop(DeckId.A)
        assertEquals(null, controller.state.value.deckA.loop)
        assertTrue(engine.loops.containsKey(DeckId.A))
        assertEquals(null, engine.loops[DeckId.A])
    }

    @Test
    fun hotCueMarksQuantizedPositionThenJumps() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        controller.loadDeck(DeckId.B, track("b", 120.0), grid("b", 120.0))
        controller.seek(DeckId.B, 1_230L)

        assertTrue(controller.setHotCue(DeckId.B, 1))
        assertEquals(1_000L, controller.state.value.deckB.hotCues.getValue(1).positionMs)

        controller.seek(DeckId.B, 9_000L)
        assertTrue(controller.jumpHotCue(DeckId.B, 1))
        assertEquals(1_000L, controller.state.value.deckB.positionMs)
        assertEquals(1_000L, engine.positions.getValue(DeckId.B))
        assertFalse(controller.setHotCue(DeckId.B, 9))
    }

    @Test
    fun effectsAreNormalizedAndForwardedToAudioEngine() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        controller.loadDeck(DeckId.A, track("a", 120.0), grid("a", 120.0))

        controller.setEffect(
            DeckId.A,
            LiveEffectType.ECHO,
            enabled = true,
            mix = 1.5f,
            parameter = -1f,
        )

        val effect = controller.state.value.deckA.effects.getValue(LiveEffectType.ECHO)
        assertTrue(effect.enabled)
        assertEquals(1f, effect.mix, 0f)
        assertEquals(0f, effect.parameter, 0f)
        assertEquals(effect, engine.effects.getValue(DeckId.A to LiveEffectType.ECHO))
    }

    @Test
    fun midiBindingsDriveCrossfaderTransportHotCueAndFx() {
        val controller = LivePerformanceController()
        controller.loadDeck(DeckId.A, track("a", 120.0), grid("a", 120.0))
        controller.seek(DeckId.A, 750L)

        assertTrue(controller.handleMidi(MidiMessageType.CONTROL_CHANGE, 0, 1, 127))
        assertEquals(1f, controller.state.value.crossfaderPosition, 0.0001f)

        assertTrue(controller.handleMidi(MidiMessageType.NOTE, 0, 36, 127))
        assertTrue(controller.state.value.deckA.playing)

        assertTrue(controller.handleMidi(MidiMessageType.NOTE, 0, 40, 127))
        assertEquals(1_000L, controller.state.value.deckA.hotCues.getValue(1).positionMs)

        assertTrue(controller.handleMidi(MidiMessageType.CONTROL_CHANGE, 0, 16, 64))
        val filter = controller.state.value.deckA.effects.getValue(LiveEffectType.FILTER)
        assertTrue(filter.enabled)
        assertEquals(64f / 127f, filter.mix, 0.0001f)

        assertFalse(controller.handleMidi(MidiMessageType.NOTE, 15, 99, 127))
    }

    @Test
    fun customMidiBindingReplacesPhysicalControlCollision() {
        val controller = LivePerformanceController()
        val binding = MidiBinding(
            MidiMessageType.CONTROL_CHANGE,
            channel = 0,
            number = 1,
            action = LiveMidiAction.EffectMix(DeckId.A, LiveEffectType.GATE),
        )
        controller.bindMidi(binding)

        assertEquals(binding, controller.state.value.midiBindings.single {
            it.type == MidiMessageType.CONTROL_CHANGE && it.channel == 0 && it.number == 1
        })
        assertTrue(controller.handleMidi(MidiMessageType.CONTROL_CHANGE, 0, 1, 100))
        assertTrue(controller.state.value.deckA.effects.getValue(LiveEffectType.GATE).enabled)
    }

    @Test
    fun anUnplayableTrackIsRefusedByLoadDeck() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        val initialState = controller.state.value.deckA

        val unplayable = Track(
            id = "srv-1",
            title = "Server track",
            artist = "artist",
            durationMs = 120_000L,
            bpm = 128.0,
            playable = false,
            unplayableReason = "Server audio is not available yet.",
        )
        controller.loadDeck(DeckId.A, unplayable, grid("srv-1", 128.0))

        // Deck state unchanged
        assertEquals(initialState.trackId, controller.state.value.deckA.trackId)
        // Engine not called
        assertFalse(engine.loadedTracks.contains(DeckId.A))
    }

    @Test
    fun aPlayableTrackStillLoadsNormally() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        val playable = track("a", 120.0)
        controller.loadDeck(DeckId.A, playable, grid("a", 120.0))

        assertEquals("a", controller.state.value.deckA.trackId)
        assertTrue(engine.loadedTracks.containsKey(DeckId.A))
    }

    private fun track(id: String, bpm: Double) = Track(
        id = id,
        title = id,
        artist = "artist",
        durationMs = 120_000L,
        bpm = bpm,
        offlineAvailable = true,
    )

    private fun grid(id: String, bpm: Double) = BeatGrid(id, anchorMs = 0L, bpm = bpm, revision = 1L)

    private class RecordingEngine : LiveAudioEngine {
        val rates = mutableMapOf<DeckId, Float>()
        val gains = mutableMapOf<DeckId, Float>()
        val positions = mutableMapOf<DeckId, Long>()
        val loops = mutableMapOf<DeckId, LiveLoop?>()
        val effects = mutableMapOf<Pair<DeckId, LiveEffectType>, LiveEffectState>()
        val loadedTracks = mutableMapOf<DeckId, String>()

        override fun load(deck: DeckId, trackId: String) { loadedTracks[deck] = trackId }
        override fun setPlaying(deck: DeckId, playing: Boolean) = Unit
        override fun seek(deck: DeckId, positionMs: Long) {
            positions[deck] = positionMs
        }

        override fun setPlaybackRate(deck: DeckId, rate: Float) {
            rates[deck] = rate
        }

        override fun setGain(deck: DeckId, gain: Float) {
            gains[deck] = gain
        }

        override fun setLoop(deck: DeckId, loop: LiveLoop?) {
            loops[deck] = loop
        }

        override fun setEffect(deck: DeckId, type: LiveEffectType, state: LiveEffectState) {
            effects[deck to type] = state
        }
    }

    @Test
    fun aStreamOnlyServerTrackIsRefusedByLoadDeckUntilItIsDownloaded() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        val streamOnly = Track(
            id = "srv-1", title = "S", artist = "A", durationMs = 120_000L, bpm = 128.0,
            playable = true, deckBlockedReason = "Download this track to load it on a deck.",
        )

        controller.loadDeck(DeckId.A, streamOnly, grid("srv-1", 128.0))

        assertTrue(engine.loadedTracks.isEmpty())
        assertEquals(null, controller.state.value.deckA.trackId)

        controller.loadDeck(DeckId.A, streamOnly.copy(deckBlockedReason = null, offlineAvailable = true), grid("srv-1", 128.0))
        assertEquals("srv-1", engine.loadedTracks[DeckId.A])
    }
}
