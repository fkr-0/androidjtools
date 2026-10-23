package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.offline.download.DownloadFailureKind
import dev.androidjtools.offline.download.DownloadScheduler
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadCoordinator
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.OfflineMediaResolver
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibDownloadProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = "0123456789".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val original = AssetFileDescriptor("f-orig", "a1", "original", sha, 10, "audio/wav", "\"$sha\"")
    private val preview = AssetFileDescriptor("f-prev", "a1", "preview", "cd".repeat(32), 4, "audio/mpeg", "\"x\"")
    private val audio = ServerAudio(stream = preview, download = original)

    private class RecordingScheduler : DownloadScheduler {
        override fun cancelAll() = Unit
        val enqueued = mutableListOf<OfflineAsset>()
        val cancelled = mutableListOf<String>()
        override fun enqueue(asset: OfflineAsset, replace: Boolean) {
            enqueued += asset
        }
        override fun cancel(assetId: String) {
            cancelled += assetId
        }
    }

    private class FakeDevice : DownloadProvider {
        val calls = mutableListOf<String>()
        override val cacheSummary: StateFlow<OfflineCacheSummary> = MutableStateFlow(OfflineCacheSummary())
        override fun state(trackId: String): StateFlow<TrackDownloadState> =
            MutableStateFlow(TrackDownloadState(trackId, DownloadStatus.AVAILABLE, 1.0))
        override fun pinTrack(trackId: String) { calls += "pin:$trackId" }
        override fun unpinTrack(trackId: String) { calls += "unpin:$trackId" }
        override fun pinPlaylist(playlistId: String) = Unit
        override fun unpinPlaylist(playlistId: String) = Unit
        override fun retry(trackId: String) { calls += "retry:$trackId" }
        override fun cancel(trackId: String) { calls += "cancel:$trackId" }
        override fun pruneUnpinned() = Unit
    }

    private inner class Fixture(
        val scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        free: Long = 10L shl 30,
        range: Boolean = true,
        projectionAudio: Map<String, ServerAudio> = mapOf("a1" to audio),
    ) {
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val scheduler = RecordingScheduler()
        val device = FakeDevice()
        val projection = MutableStateFlow(
            LibraryProjection(
                tracks = listOf(dev.androidjtools.core.model.Track("a1", "A", "B", durationMs = 1)),
                cues = emptyMap(), loops = emptyMap(), audio = projectionAudio, audioAdvertised = true,
            ),
        )
        val provider = SampleLibDownloadProvider(
            delegate = device, projection = projection, rangeSupported = { range },
            offline = lazyOf(OfflineParts(OfflineDownloadCoordinator(store, scheduler, OfflineMediaResolver(store, storage)), store, storage)),
            scope = scope + dispatcher, maxBytes = 1L shl 30,
            freeSpaceBytes = { free }, dispatcher = dispatcher, reserveBytes = 1000,
        )
    }

    @Test
    fun pinningQueuesTheOriginalWithItsHashLengthEtagAndRoleReference() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        f.provider.pinTrack("a1")

        val asset = f.scheduler.enqueued.single()
        assertEquals("f-orig", asset.assetId)
        assertEquals("original://f-orig", asset.resourceUri)
        assertEquals(sha, asset.contentSha256)
        assertEquals(10L, asset.contentLength)
        assertEquals("\"$sha\"", asset.advertisedEtag)
        assertTrue(asset.supportsRange)
        assertEquals(setOf("track:a1"), f.store.get("f-orig")!!.pinOwners)
        assertEquals(DownloadStatus.QUEUED, f.provider.state("a1").value.status)
    }

    @Test
    fun aServerWithoutRangeSupportIsReflectedInTheQueuedAsset() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), range = false)
        f.provider.pinTrack("a1")
        assertFalse(f.scheduler.enqueued.single().supportsRange)
    }

    @Test
    fun stateFollowsTheEngineRecordAndReportsProgress() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val state = f.provider.state("a1")
        assertEquals(DownloadStatus.NOT_DOWNLOADED, state.value.status)

        f.provider.pinTrack("a1")
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = 5) }
        assertEquals(DownloadStatus.DOWNLOADING, state.value.status)
        assertEquals(0.5, state.value.progress, 0.0001)
        assertEquals(10L, state.value.totalBytes)

        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE, bytesDownloaded = 10) }
        assertEquals(DownloadStatus.AVAILABLE, state.value.status)

        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.CORRUPT) }
        assertEquals(DownloadStatus.CORRUPT, state.value.status)
    }

    @Test
    fun aRecordOfAnotherRevisionIsNotShownAsDownloaded() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.store.ensure(OfflineAsset("f-orig", "original://f-orig", "ee".repeat(32), 10))
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        assertEquals(DownloadStatus.NOT_DOWNLOADED, f.provider.state("a1").value.status)
    }

    @Test
    fun aFullDiskFailsVisiblyBeforeAnythingIsQueued() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), free = 500)

        f.provider.pinTrack("a1")

        assertTrue(f.scheduler.enqueued.isEmpty())
        val state = f.provider.state("a1").value
        assertEquals(DownloadStatus.FAILED, state.status)
        assertTrue(state.error.orEmpty().contains("space"))
        assertEquals(DownloadFailureKind.QUOTA, f.store.get("f-orig")!!.error!!.kind)
    }

    @Test
    fun retryAndCancelActOnTheDownloadFile() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")

        f.provider.cancel("a1")
        assertEquals(listOf("f-orig"), f.scheduler.cancelled)
        assertEquals(DownloadStatus.CANCELLED, f.provider.state("a1").value.status)

        f.provider.retry("a1")
        assertEquals(2, f.scheduler.enqueued.size)
        assertEquals(DownloadStatus.QUEUED, f.provider.state("a1").value.status)
    }

    @Test
    fun unpinningACompletedDownloadRemovesTheFileAndTheBytes() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE, bytesDownloaded = 10) }

        f.provider.unpinTrack("a1")

        assertFalse(f.storage.finalFile("f-orig").exists())
        assertEquals(OfflineDownloadPhase.NOT_CACHED, f.store.get("f-orig")!!.phase)
        assertEquals(DownloadStatus.NOT_DOWNLOADED, f.provider.state("a1").value.status)
    }

    @Test
    fun unpinningAnActiveDownloadCancelsItInsteadOfDeletingUnderTheWorker() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")

        f.provider.unpinTrack("a1")

        assertEquals(listOf("f-orig"), f.scheduler.cancelled)
        assertEquals(emptySet<String>(), f.store.get("f-orig")!!.pinOwners)
    }

    @Test
    fun pruneRemovesEveryUnpinnedCopyButKeepsPinnedOnes() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        val strayAsset = OfflineAsset("stray", "original://stray", "ab".repeat(32), 3)
        f.store.ensure(strayAsset)
        f.store.update("stray") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        f.storage.finalFile("stray").writeBytes("abc".toByteArray())

        f.provider.pruneUnpinned()

        assertTrue(f.storage.finalFile("f-orig").exists())
        assertFalse(f.storage.finalFile("stray").exists())
    }

    @Test
    fun theSummaryCountsUsedBytesAndPinnedTracks() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }

        val summary = f.provider.cacheSummary.first { it.usedBytes > 0 }

        assertEquals(10L, summary.usedBytes)
        assertEquals(1L shl 30, summary.maxBytes)
        assertEquals(setOf("a1"), summary.pinnedTrackIds)
        assertEquals(0L, summary.evictableBytes)
    }

    @Test
    fun deviceTracksAndTracksWithoutAudioAreLeftAlone() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), projectionAudio = emptyMap())

        f.provider.pinTrack("device-media:5")
        f.provider.pinTrack("a1") // a server track with no audio file: nothing to download
        f.provider.retry("device-media:5")

        assertEquals(listOf("pin:device-media:5", "retry:device-media:5"), f.device.calls)
        assertTrue(f.scheduler.enqueued.isEmpty())
        assertEquals(DownloadStatus.NOT_DOWNLOADED, f.provider.state("a1").value.status)
        assertEquals(DownloadStatus.AVAILABLE, f.provider.state("device-media:5").value.status)
    }

    @Test
    fun downloadedFilesListsOnlyCompleteRecordsWithTheirHash() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.store.ensure(OfflineAsset("done", "original://done", "ab".repeat(32), 1))
        f.store.update("done") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        f.store.ensure(OfflineAsset("busy", "original://busy", "cd".repeat(32), 1))
        f.store.update("busy") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING) }

        assertEquals(mapOf("done" to "ab".repeat(32)), downloadedFiles(f.store.allRecords).first())
    }

    @Test
    fun retryIsRefusedOnAFullDiskLikePinning() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), free = 500)

        f.provider.retry("a1")

        assertTrue(f.scheduler.enqueued.isEmpty())
        assertEquals(DownloadStatus.FAILED, f.provider.state("a1").value.status)
        assertEquals(DownloadFailureKind.QUOTA, f.store.get("f-orig")!!.error!!.kind)
    }

    @Test
    fun pruneDropsPinsForTracksTheServerDeletedOrReissuedUnderAnotherFileId() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        for (id in listOf("f-reissued", "f-deleted")) {
            f.store.ensure(OfflineAsset(id, "original://$id", "ab".repeat(32), 3))
            f.store.update(id) { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
            f.storage.finalFile(id).writeBytes("abc".toByteArray())
        }
        f.store.update("f-reissued") { it.copy(pinOwners = setOf("track:a1")) } // a1's current file is f-orig
        f.store.update("f-deleted") { it.copy(pinOwners = setOf("track:gone")) } // track no longer in the library

        f.provider.pruneUnpinned()

        assertFalse(f.storage.finalFile("f-reissued").exists())
        assertFalse(f.storage.finalFile("f-deleted").exists())
        assertEquals(setOf("track:a1"), f.store.get("f-orig")!!.pinOwners)
    }

    @Test
    fun pruneNeverDropsPinsWhileTheLibraryIsEmpty() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        f.projection.value = LibraryProjection.EMPTY // a cache reset or cold start, not a real deletion

        f.provider.pruneUnpinned()

        assertEquals(setOf("track:a1"), f.store.get("f-orig")!!.pinOwners)
        assertTrue(f.storage.finalFile("f-orig").exists())
    }

    @Test
    fun theOfflineStoreIsFirstTouchedOnTheProviderDispatcherNeverOnTheCaller() {
        val ioThread = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "offline-io-test") }
        val dispatcher = ioThread.asCoroutineDispatcher()
        val touchedOn = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val parts = lazy {
            touchedOn.set(Thread.currentThread().name)
            OfflineParts(OfflineDownloadCoordinator(store, RecordingScheduler(), OfflineMediaResolver(store, storage)), store, storage)
        }
        val job = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(job + dispatcher)
        try {
            val projection = MutableStateFlow(
                LibraryProjection(
                    tracks = listOf(dev.androidjtools.core.model.Track("a1", "A", "B", durationMs = 1)),
                    cues = emptyMap(), loops = emptyMap(), audio = mapOf("a1" to audio), audioAdvertised = true,
                ),
            )
            val provider = SampleLibDownloadProvider(
                delegate = FakeDevice(), projection = projection, rangeSupported = { true }, offline = parts,
                scope = scope, maxBytes = 1L shl 30, freeSpaceBytes = { 10L shl 30 }, dispatcher = dispatcher,
            )
            provider.state("a1")
            provider.pinTrack("a1")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (touchedOn.get() == null && System.nanoTime() < deadline) Thread.sleep(10)

            assertTrue(touchedOn.get(), touchedOn.get().orEmpty().startsWith("offline-io-test")) // coroutine debug adds a suffix
            assertTrue(parts.isInitialized())
        } finally {
            job.cancel()
            // Let the pin that is already running on the IO thread finish its file writes: tearing the temporary folder
            // down underneath it made the write fail on that thread, and the failure surfaced in a later test.
            ioThread.shutdown()
            assertTrue("offline IO thread did not finish", ioThread.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
}
