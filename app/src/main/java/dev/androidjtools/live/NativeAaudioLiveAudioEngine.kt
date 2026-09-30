package dev.androidjtools.live

import android.content.Context
import android.os.Build

/**
 * LiveAudioEngine whose decoded and tempo-adjusted PCM is rendered by AAudio.
 *
 * Media3 remains the decoder and timeline authority. Its platform AudioTrack receives silence
 * solely to preserve Media3's clock while the final processor feeds audible PCM to AAudio.
 */
class NativeAaudioLiveAudioEngine private constructor(
    private val delegate: Media3LiveAudioEngine,
) : LiveAudioEngine by delegate {
    fun stats(): NativeAaudioStats? = delegate.nativeStats()

    companion object {
        fun createOrNull(
            context: Context,
            mediaUriResolver: (String) -> String?,
            onPosition: (DeckId, Long) -> Unit = { _, _ -> },
        ): NativeAaudioLiveAudioEngine? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            val mixer = NativeAaudioMixer.createOrNull() ?: return null
            return runCatching {
                NativeAaudioLiveAudioEngine(
                    Media3LiveAudioEngine(
                        context = context,
                        mediaUriResolver = mediaUriResolver,
                        onPosition = onPosition,
                        nativeMixer = mixer,
                    ),
                )
            }.getOrElse {
                mixer.close()
                null
            }
        }
    }
}
