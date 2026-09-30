package dev.androidjtools.live

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

/**
 * Two-deck Android compatibility engine.
 *
 * The live controller is deliberately independent of Media3 so a native AAudio/OpenSL ES backend can
 * implement [LiveAudioEngine] without changing UI/control semantics. This implementation provides
 * actual dual-source playback, gain, tempo-rate changes and A/B loop enforcement for device media.
 * FX state is retained by the controller but is not inserted into Media3's PCM path here; production
 * low-latency DSP belongs in the native backend.
 */
class Media3LiveAudioEngine(
    context: Context,
    private val mediaUriResolver: (String) -> String?,
    private val onPosition: (DeckId, Long) -> Unit = { _, _ -> },
) : LiveAudioEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    private val players = DeckId.entries.associateWith {
        ExoPlayer.Builder(context.applicationContext).build().apply {
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
        if (playing) player.play() else player.pause()
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
        players.getValue(deck).volume = gain.coerceIn(0f, 1f)
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
        // The compatibility engine intentionally does not pretend Media3 exposes the native live-DSP
        // chain. Controller/UI state remains authoritative and the native backend consumes this call.
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(ticker)
        players.values.forEach(ExoPlayer::release)
    }

    private companion object {
        const val POSITION_POLL_MS = 16L
    }
}
