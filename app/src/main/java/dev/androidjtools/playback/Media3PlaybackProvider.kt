package dev.androidjtools.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.androidjtools.core.provider.PlaybackProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlaybackEngineState(
    val mediaId: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val issue: PlaybackIssue? = null,
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
) : PlaybackProvider, PlaybackDiagnostics {
    private val mutableTrack = MutableStateFlow<String?>(null)
    private val mutablePlaying = MutableStateFlow(false)
    private val mutablePosition = MutableStateFlow(0L)
    private val mutableIssue = MutableStateFlow<PlaybackIssue?>(null)

    override val currentTrackId: StateFlow<String?> = mutableTrack.asStateFlow()
    override val isPlaying: StateFlow<Boolean> = mutablePlaying.asStateFlow()
    override val positionMs: StateFlow<Long> = mutablePosition.asStateFlow()
    override val issue: StateFlow<PlaybackIssue?> = mutableIssue.asStateFlow()

    init {
        engine.setStateListener(::acceptEngineState)
    }

    override fun play(trackId: String) {
        val uri = mediaUriResolver(trackId) ?: run {
            mutablePlaying.value = false
            mutableIssue.value = PlaybackIssue(
                PlaybackIssueKind.UNAVAILABLE_MEDIA,
                "Media is unavailable for this catalog item.",
            )
            return
        }
        mutableIssue.value = null
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
        mutableIssue.value = state.issue
    }

    companion object {
        fun create(
            context: Context,
            mediaUriResolver: (String) -> String?,
        ): Media3PlaybackProvider = Media3PlaybackProvider(
            mediaUriResolver = mediaUriResolver,
            engine = MediaControllerPlaybackEngine(context.applicationContext),
        )
    }
}

private class MediaControllerPlaybackEngine(context: Context) : PlaybackEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)
    private var listener: (PlaybackEngineState) -> Unit = {}
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, Media3PlaybackService::class.java)),
    ).buildAsync()
    private var player: MediaController? = null
    private var pendingMedia: Pair<String, String>? = null
    private var pendingPositionMs: Long? = null
    private var pendingPlay = false
    private var released = false
    private var connectionIssue: PlaybackIssue? = null

    private val ticker = object : Runnable {
        override fun run() {
            emit()
            if (player?.isPlaying == true) handler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    init {
        controllerFuture.addListener({
            if (released) return@addListener
            runCatching { controllerFuture.get() }
                .onSuccess(::attach)
                .onFailure {
                    connectionIssue = PlaybackIssue(
                        PlaybackIssueKind.PLAYBACK_ERROR,
                        "Playback service is unavailable.",
                    )
                    emit()
                }
        }, executor)
    }

    override fun setStateListener(listener: (PlaybackEngineState) -> Unit) {
        this.listener = listener
        emit()
    }

    override fun load(mediaId: String, uri: String) {
        pendingMedia = mediaId to uri
        pendingPositionMs = 0L
        player?.let { loadNow(it, mediaId, uri) }
        emit()
    }

    override fun play() {
        pendingPlay = true
        player?.play()
        emit()
    }

    override fun pause() {
        pendingPlay = false
        player?.pause()
        emit()
    }

    override fun seekTo(positionMs: Long) {
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        player?.seekTo(positionMs.coerceAtLeast(0L))
        emit()
    }

    override fun release() {
        released = true
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        controllerFuture.cancel(false)
    }

    private fun attach(controller: MediaController) {
        if (released) {
            controller.release()
            return
        }
        player = controller
        connectionIssue = null
        controller.setAudioAttributes(audioAttributes, true)
        controller.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handler.removeCallbacks(ticker)
                    emit()
                    if (isPlaying) handler.postDelayed(ticker, POSITION_UPDATE_MS)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = emit()

                override fun onPlaybackStateChanged(playbackState: Int) = emit()

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = emit()

                override fun onPlayerError(error: PlaybackException) = emit()
            },
        )
        pendingMedia?.let { (id, uri) -> loadNow(controller, id, uri) }
        pendingPositionMs?.takeIf { it > 0L }?.let(controller::seekTo)
        if (pendingPlay) controller.play() else controller.pause()
        emit()
    }

    private fun loadNow(player: MediaController, mediaId: String, uri: String) {
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(Uri.parse(uri))
                .build(),
        )
        player.prepare()
    }

    private fun emit() {
        val current = player
        val issue = when {
            connectionIssue != null -> connectionIssue
            current?.playerError != null -> PlaybackIssue(
                PlaybackIssueKind.PLAYBACK_ERROR,
                scrubUrls(current.playerError?.message),
            )
            current != null &&
                current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
                PlaybackIssue(
                    PlaybackIssueKind.AUDIO_FOCUS,
                    "Playback is paused or ducked by another audio source.",
                )
            else -> null
        }
        listener(
            PlaybackEngineState(
                mediaId = current?.currentMediaItem?.mediaId ?: pendingMedia?.first,
                isPlaying = current?.isPlaying ?: false,
                positionMs = current?.currentPosition?.coerceAtLeast(0L) ?: pendingPositionMs ?: 0L,
                issue = issue,
            ),
        )
    }

    private companion object {
        const val POSITION_UPDATE_MS = 250L
    }
}
