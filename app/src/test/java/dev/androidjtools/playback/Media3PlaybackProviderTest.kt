package dev.androidjtools.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackProviderTest {
    @Test
    fun resolvedTrackLoadsUriAndStartsPlayback() {
        val engine = FakePlaybackEngine()
        val provider = Media3PlaybackProvider(
            mediaUriResolver = { id -> if (id == "device-media:42") "content://media/external/audio/media/42" else null },
            engine = engine,
        )

        provider.play("device-media:42")

        assertEquals("device-media:42", engine.loadedMediaId)
        assertEquals("content://media/external/audio/media/42", engine.loadedUri)
        assertEquals("device-media:42", provider.currentTrackId.value)
        assertTrue(provider.isPlaying.value)
    }

    @Test
    fun unresolvedTrackCannotPretendToBePlaying() {
        val engine = FakePlaybackEngine()
        val provider = Media3PlaybackProvider(mediaUriResolver = { null }, engine = engine)

        provider.play("missing")

        assertNull(engine.loadedMediaId)
        assertNull(provider.currentTrackId.value)
        assertFalse(provider.isPlaying.value)
    }

    @Test
    fun toggleSeekAndReleaseReachPlaybackEngine() {
        val engine = FakePlaybackEngine()
        val provider = Media3PlaybackProvider(mediaUriResolver = { "content://media/item" }, engine = engine)
        provider.play("device-media:7")

        provider.seek(12_345L)
        assertEquals(12_345L, engine.positionMs)
        assertEquals(12_345L, provider.positionMs.value)

        provider.toggle()
        assertFalse(engine.playing)
        assertFalse(provider.isPlaying.value)

        provider.toggle()
        assertTrue(engine.playing)
        assertTrue(provider.isPlaying.value)

        provider.release()
        assertTrue(engine.released)
    }

    private class FakePlaybackEngine : PlaybackEngine {
        private var listener: (PlaybackEngineState) -> Unit = {}
        var loadedMediaId: String? = null
        var loadedUri: String? = null
        var playing = false
        var positionMs = 0L
        var released = false

        override fun setStateListener(listener: (PlaybackEngineState) -> Unit) {
            this.listener = listener
            emit()
        }

        override fun load(mediaId: String, uri: String) {
            loadedMediaId = mediaId
            loadedUri = uri
            positionMs = 0L
            emit()
        }

        override fun play() {
            playing = true
            emit()
        }

        override fun pause() {
            playing = false
            emit()
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs
            emit()
        }

        override fun release() {
            released = true
        }

        private fun emit() {
            listener(PlaybackEngineState(loadedMediaId, playing, positionMs))
        }
    }
}
