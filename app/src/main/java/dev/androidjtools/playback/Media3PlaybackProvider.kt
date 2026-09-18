package dev.androidjtools.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.androidjtools.core.provider.PlaybackProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlaybackEngineState(
    val mediaId: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
)

internal interface PlaybackEngine {
    fun setStateListener(listener: (PlaybackEngineState) -> Unit)
    fun load(mediaId: String, uri: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

class Media3PlaybackProvider internal constructor(
    private val mediaUriResolver: (String) -> String?,
    private val engine: PlaybackEngine,
) : PlaybackProvider {
    private val mutableTrack = MutableStateFlow<String?>(null)
    private val mutablePlaying = MutableStateFlow(false)
    private val mutablePosition = MutableStateFlow(0L)

    override val currentTrackId: StateFlow<String?> = mutableTrack.asStateFlow()
    override val isPlaying: StateFlow<Boolean> = mutablePlaying.asStateFlow()
    override val positionMs: StateFlow<Long> = mutablePosition.asStateFlow()

    init {
        engine.setStateListener(::acceptEngineState)
    }

    override fun play(trackId: String) {
        val uri = mediaUriResolver(trackId) ?: run {
            mutablePlaying.value = false
            return
        }
        mutableTrack.value = trackId
        mutablePosition.value = 0L
        engine.load(trackId, uri)
        engine.play()
        mutablePlaying.value = true
    }

    override fun toggle() {
        if (mutableTrack.value == null) return
        if (mutablePlaying.value) {
            engine.pause()
            mutablePlaying.value = false
        } else {
            engine.play()
            mutablePlaying.value = true
        }
    }

    override fun seek(positionMs: Long) {
        if (mutableTrack.value == null) return
        val target = positionMs.coerceAtLeast(0L)
        engine.seekTo(target)
        mutablePosition.value = target
    }

    override fun release() {
        engine.release()
    }

    private fun acceptEngineState(state: PlaybackEngineState) {
        state.mediaId?.let { mutableTrack.value = it }
        mutablePlaying.value = state.isPlaying
        mutablePosition.value = state.positionMs.coerceAtLeast(0L)
    }

    companion object {
        fun create(
            context: Context,
            mediaUriResolver: (String) -> String?,
        ): Media3PlaybackProvider = Media3PlaybackProvider(
            mediaUriResolver = mediaUriResolver,
            engine = ExoPlayerPlaybackEngine(context.applicationContext),
        )
    }
}

private class ExoPlayerPlaybackEngine(context: Context) : PlaybackEngine {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: (PlaybackEngineState) -> Unit = {}
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    private val player = ExoPlayer.Builder(context)
        .build()
        .also {
            it.setAudioAttributes(audioAttributes, true)
            it.setHandleAudioBecomingNoisy(true)
        }

    private val ticker = object : Runnable {
        override fun run() {
            emit()
            if (player.isPlaying) handler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handler.removeCallbacks(ticker)
                    emit()
                    if (isPlaying) handler.postDelayed(ticker, POSITION_UPDATE_MS)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    emit()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    emit()
                }
            },
        )
    }

    override fun setStateListener(listener: (PlaybackEngineState) -> Unit) {
        this.listener = listener
        emit()
    }

    override fun load(mediaId: String, uri: String) {
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(Uri.parse(uri))
                .build(),
        )
        player.prepare()
        emit()
    }

    override fun play() {
        player.play()
        emit()
    }

    override fun pause() {
        player.pause()
        emit()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        emit()
    }

    override fun release() {
        handler.removeCallbacks(ticker)
        player.release()
    }

    private fun emit() {
        listener(
            PlaybackEngineState(
                mediaId = player.currentMediaItem?.mediaId,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
            ),
        )
    }

    private companion object {
        const val POSITION_UPDATE_MS = 250L
    }
}
