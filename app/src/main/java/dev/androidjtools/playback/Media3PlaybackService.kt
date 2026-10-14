package dev.androidjtools.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.androidjtools.sync.samplelib.SampleLibRuntime

/**
 * Media3 substrate for notification, lock-screen, headset and Bluetooth controls.
 *
 * Manifest registration and foreground-service permissions are deliberately left to the shell
 * integration lane because those shared files are outside this packet's claimed write scope.
 */
class Media3PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Server tracks stream through the same authenticating client as downloads: the bearer is added by an
        // interceptor, only for the paired server's media route, and redirects are never followed. file: and
        // content: URIs keep their default handling. No credential ever travels on a MediaItem.
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(SampleLibRuntime.get(this).mediaHttpClient))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .build()
            .also {
                it.setAudioAttributes(audioAttributes, true)
                it.setHandleAudioBecomingNoisy(true)
            }
        session = MediaSession.Builder(this, requireNotNull(player)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
