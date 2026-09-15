package dev.androidjtools.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Media3 substrate for notification, lock-screen, headset and Bluetooth controls.
 *
 * Manifest registration and foreground-service permissions are deliberately left to the shell
 * integration lane because those shared files are outside this packet's claimed write scope.
 */
class Media3PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .build()
            .also { it.setAudioAttributes(audioAttributes, true) }
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
