package dev.androidjtools.offline.download

import dev.androidjtools.playback.Media3PlaybackProvider
import dev.androidjtools.playback.PlaybackEngine
import dev.androidjtools.playback.PlaybackEngineState
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaybackIntegrationTest {
    @Test
    fun verifiedCachedMediaFlowsThroughAcceptedPlaybackResolverWithoutNetworkFallback() {
        val root = Files.createTempDirectory("androidjtools-offline-playback").toFile().apply { deleteOnExit() }
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val bytes = "cached-audio".toByteArray()
        val asset = OfflineAsset(
            assetId = "stable-asset-42",
            resourceUri = "https://example.invalid/media/stable-asset-42",
            contentSha256 = sha256Hex(bytes),
            contentLength = bytes.size.toLong(),
            supportsRange = true,
        )
        store.ensure(asset)
        storage.finalFile(asset.assetId).writeBytes(bytes)
        store.update(asset.assetId) {
            it.copy(
                phase = OfflineDownloadPhase.AVAILABLE,
                bytesDownloaded = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                pinOwners = setOf("track:${asset.assetId}"),
            )
        }

        val coordinator = OfflineDownloadCoordinator(
            store = store,
            scheduler = RejectingScheduler(),
            resolver = OfflineMediaResolver(store, storage),
        )
        val engine = RecordingPlaybackEngine()
        val playback = Media3PlaybackProvider(
            mediaUriResolver = { trackId -> coordinator.cachedMedia(trackId)?.uri },
            engine = engine,
        )

        playback.play(asset.assetId)

        assertEquals(asset.assetId, engine.loadedMediaId)
        assertEquals(storage.finalFile(asset.assetId).toURI().toString(), engine.loadedUri)
        assertEquals(asset.assetId, playback.currentTrackId.value)
        assertTrue(playback.isPlaying.value)
    }

    private class RejectingScheduler : DownloadScheduler {
        override fun cancelAll() = error("unexpected cancelAll")
        override fun enqueue(asset: OfflineAsset, replace: Boolean) {
            error("offline playback must not enqueue network work")
        }

        override fun cancel(assetId: String) = Unit
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        private var listener: (PlaybackEngineState) -> Unit = {}
        var loadedMediaId: String? = null
        var loadedUri: String? = null
        private var playing = false

        override fun setStateListener(listener: (PlaybackEngineState) -> Unit) {
            this.listener = listener
            emit()
        }

        override fun load(mediaId: String, uri: String) {
            loadedMediaId = mediaId
            loadedUri = uri
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

        override fun seekTo(positionMs: Long) = Unit

        override fun release() = Unit

        private fun emit() {
            listener(
                PlaybackEngineState(
                    mediaId = loadedMediaId,
                    isPlaying = playing,
                    positionMs = 0L,
                ),
            )
        }
    }
}
