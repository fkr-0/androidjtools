package dev.androidjtools.live

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Two-deck Android compatibility engine.
 *
 * The live controller is deliberately independent of Media3 so a native AAudio/OpenSL ES backend can
 * implement [LiveAudioEngine] without changing UI/control semantics. This implementation provides
 * actual dual-source playback, gain, tempo-rate changes, A/B loop enforcement and per-deck PCM DSP
 * for device media. The controller remains backend-independent so a direct native AAudio/OpenSL ES
 * implementation can replace this compatibility backend without changing UI/control semantics.
 */
class Media3LiveAudioEngine(
    context: Context,
    private val mediaUriResolver: (String) -> String?,
    private val onPosition: (DeckId, Long) -> Unit = { _, _ -> },
    private val nativeMixer: NativeAaudioMixer? = null,
) : LiveAudioEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    private val dspProcessors = if (nativeMixer == null) {
        DeckId.entries.associateWith { Media3LiveDspAudioProcessor() }
    } else {
        emptyMap()
    }
    private val nativeProcessors = nativeMixer?.let { mixer ->
        DeckId.entries.associateWith { deck -> NativeAaudioOutputProcessor(deck, mixer) }
    }.orEmpty()
    private val players = DeckId.entries.associateWith { deck ->
        val renderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                if (nativeMixer != null) {
                    builder.setAudioProcessorChain(
                        NativeAaudioProcessorChain(nativeProcessors.getValue(deck)),
                    )
                } else {
                    builder.setAudioProcessors(arrayOf(dspProcessors.getValue(deck)))
                }
                return builder.build()
            }
        }
        ExoPlayer.Builder(context.applicationContext, renderersFactory).build().apply {
            setAudioAttributes(audioAttributes, false)
        }
    }
    private val loops = mutableMapOf<DeckId, LiveLoop?>()
    private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            DeckId.entries.forEach { deck ->
                val player = players.getValue(deck)
                val loop = loops[deck]
                var position = player.currentPosition.coerceAtLeast(0L)
                if (loop != null && player.isPlaying && position >= loop.endMs) {
                    player.seekTo(loop.startMs)
                    position = loop.startMs
                }
                onPosition(deck, position)
            }
            handler.postDelayed(this, POSITION_POLL_MS)
        }
    }

    init {
        handler.post(ticker)
    }

    override fun load(deck: DeckId, trackId: String) {
        val uri = mediaUriResolver(trackId) ?: return
        val player = players.getValue(deck)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        player.seekTo(0L)
        loops[deck] = null
    }

    override fun setPlaying(deck: DeckId, playing: Boolean) {
        val player = players.getValue(deck)
        if (playing) {
            player.play()
            nativeMixer?.setPlaying(deck, true)
        } else {
            nativeMixer?.setPlaying(deck, false)
            player.pause()
        }
    }

    override fun seek(deck: DeckId, positionMs: Long) {
        players.getValue(deck).seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackRate(deck: DeckId, rate: Float) {
        players.getValue(deck).playbackParameters = PlaybackParameters(
            rate.coerceIn(0.50f, 2.00f),
            1f,
        )
    }

    override fun setGain(deck: DeckId, gain: Float) {
        val normalized = gain.coerceIn(0f, 1f)
        players.getValue(deck).volume = normalized
        nativeMixer?.setGain(deck, normalized)
    }

    override fun setLoop(deck: DeckId, loop: LiveLoop?) {
        loops[deck] = loop
        if (loop != null) {
            val player = players.getValue(deck)
            if (player.currentPosition !in loop.startMs until loop.endMs) {
                player.seekTo(loop.startMs)
            }
        }
    }

    override fun setEffect(deck: DeckId, type: LiveEffectType, state: LiveEffectState) {
        dspProcessors[deck]?.setEffect(type, state)
        nativeProcessors[deck]?.setEffect(type, state)
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(ticker)
        players.values.forEach(ExoPlayer::release)
        nativeMixer?.close()
    }

    fun nativeStats(): NativeAaudioStats? = nativeMixer?.stats()

    private companion object {
        const val POSITION_POLL_MS = 16L
    }
}
