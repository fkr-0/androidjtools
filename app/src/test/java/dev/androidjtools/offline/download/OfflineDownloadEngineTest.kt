package dev.androidjtools.offline.download

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadEngineTest {
    @Test
    fun processDeathResumeUsesPersistedRangeAndValidator() = runTest {
        val root = tempRoot()
        val stateDir = File(root, "state")
        val mediaDir = File(root, "media")
        val content = "abcdefghij".toByteArray()
        val asset = asset("asset-1", content, etag = "\"v1\"")

        val firstStore = OfflineDownloadStore(stateDir)
        val storage = OfflineCacheStorage(mediaDir)
        firstStore.ensure(asset)
        storage.partialFile(asset.assetId).writeBytes(content.copyOfRange(0, 4))
        firstStore.update(asset.assetId) {
            it.copy(
                phase = OfflineDownloadPhase.DOWNLOADING,
                bytesDownloaded = 4,
                currentEtag = "\"v1\"",
            )
        }

        // Reopening the store models process death: no in-memory state is reused.
        val restartedStore = OfflineDownloadStore(stateDir)
        val transport = FakeRangeTransport(
            response(206, content.copyOfRange(4, content.size), start = 4, etag = "\"v1\""),
        )
        val engine = engine(restartedStore, storage, transport, quotaBytes = 1024)

        assertTrue(engine.download(asset) is DownloadAttemptResult.Success)
        assertEquals(1, transport.requests.size)
        assertEquals(4L, transport.requests.single().startByte)
        assertEquals("\"v1\"", transport.requests.single().ifRange)
        assertArrayEquals(content, storage.finalFile(asset.assetId).readBytes())
        assertEquals(OfflineDownloadPhase.AVAILABLE, restartedStore.get(asset.assetId)?.phase)
    }

    @Test
    fun validatorMismatchFallsBackToFullWithoutAppendingStaleBytes() = runTest {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val content = "new-content".toByteArray()
        val asset = asset("asset-validator", content, etag = "\"v1\"")
        store.ensure(asset)
        storage.partialFile(asset.assetId).writeBytes("old".toByteArray())
        store.update(asset.assetId) {
            it.copy(bytesDownloaded = 3, phase = OfflineDownloadPhase.DOWNLOADING, currentEtag = "\"v1\"")
        }
        val transport = FakeRangeTransport(
            response(206, "WRONG".toByteArray(), start = 3, etag = "\"v2\""),
            response(200, content, etag = "\"v2\""),
        )

        assertTrue(engine(store, storage, transport, 1024).download(asset) is DownloadAttemptResult.Success)
        assertEquals(listOf(3L, null), transport.requests.map { it.startByte })
        assertArrayEquals(content, storage.finalFile(asset.assetId).readBytes())
    }

    @Test
    fun descriptorRevisionDeletesStaleCompletedAndPartialMedia() = runTest {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val old = "old".toByteArray()
        val replacement = "replacement".toByteArray()
        val oldAsset = asset("stable-id", old, uri = "https://example.invalid/media/v1")
        store.ensure(oldAsset)
        storage.finalFile(oldAsset.assetId).writeBytes(old)
        storage.partialFile(oldAsset.assetId).writeBytes("stale-part".toByteArray())
        store.update(oldAsset.assetId) {
            it.copy(phase = OfflineDownloadPhase.AVAILABLE, bytesDownloaded = old.size.toLong())
        }
        val newAsset = asset("stable-id", replacement, uri = "https://example.invalid/media/v2")
        val transport = FakeRangeTransport(response(200, replacement))

        assertTrue(engine(store, storage, transport, 1024).download(newAsset) is DownloadAttemptResult.Success)
        assertEquals(listOf(null), transport.requests.map { it.startByte })
        assertArrayEquals(replacement, storage.finalFile(newAsset.assetId).readBytes())
    }

    @Test
    fun hashMismatchNeverPublishesCorruptMedia() = runTest {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val expected = "good".toByteArray()
        val asset = asset("asset-hash", expected)
        val transport = FakeRangeTransport(response(200, "evil".toByteArray()))

        val result = engine(store, storage, transport, 1024).download(asset)

        assertTrue(result is DownloadAttemptResult.Retry)
        assertFalse(storage.finalFile(asset.assetId).exists())
        assertFalse(storage.partialFile(asset.assetId).exists())
        assertEquals(OfflineDownloadPhase.QUEUED, store.get(asset.assetId)?.phase)
        assertEquals(DownloadFailureKind.INTEGRITY, store.get(asset.assetId)?.error?.kind)
    }

    @Test
    fun pinSafeEvictionOnlyRemovesEligibleUnpinnedMedia() {
        var clock = 1L
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state")) { clock++ }
        val storage = OfflineCacheStorage(File(root, "media"))
        val pinnedContent = ByteArray(10) { 1 }
        val unpinnedContent = ByteArray(10) { 2 }
        val pinned = asset("pinned", pinnedContent)
        val unpinned = asset("unpinned", unpinnedContent)
        store.ensure(pinned)
        store.ensure(unpinned)
        storage.finalFile(pinned.assetId).writeBytes(pinnedContent)
        storage.finalFile(unpinned.assetId).writeBytes(unpinnedContent)
        store.update(pinned.assetId) {
            it.copy(phase = OfflineDownloadPhase.AVAILABLE, pinOwners = setOf("track:pinned"), lastAccessEpochMs = 1)
        }
        store.update(unpinned.assetId) {
            it.copy(phase = OfflineDownloadPhase.AVAILABLE, lastAccessEpochMs = 2)
        }

        val result = OfflineCacheQuotaManager(store, storage, maxBytes = 22).ensureCapacity("incoming", 8)

        assertTrue(result.allowed)
        assertTrue(storage.finalFile(pinned.assetId).exists())
        assertFalse(storage.finalFile(unpinned.assetId).exists())
        assertEquals(OfflineDownloadPhase.NOT_CACHED, store.get(unpinned.assetId)?.phase)
    }

    @Test
    fun quotaFailsRatherThanEvictPinnedOrActiveMedia() {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val pinned = asset("pinned-only", ByteArray(10) { 3 })
        val active = asset("active-unpinned", ByteArray(10) { 4 })
        store.ensure(pinned)
        store.ensure(active)
        storage.finalFile(pinned.assetId).writeBytes(ByteArray(10) { 3 })
        storage.partialFile(active.assetId).writeBytes(ByteArray(10) { 4 })
        store.update(pinned.assetId) { it.copy(phase = OfflineDownloadPhase.AVAILABLE, pinOwners = setOf("playlist:x")) }
        store.update(active.assetId) { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = 10) }

        val result = OfflineCacheQuotaManager(store, storage, maxBytes = 20).ensureCapacity("incoming", 1)

        assertFalse(result.allowed)
        assertTrue(storage.finalFile(pinned.assetId).exists())
        assertTrue(storage.partialFile(active.assetId).exists())
    }

    @Test
    fun evictionRechecksLatestPinStateAtomically() {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val content = ByteArray(10) { 5 }
        val candidate = asset("pin-race", content)
        store.ensure(candidate)
        storage.finalFile(candidate.assetId).writeBytes(content)
        store.update(candidate.assetId) {
            it.copy(phase = OfflineDownloadPhase.AVAILABLE, lastAccessEpochMs = 1)
        }

        // A quota scan may have observed the old unpinned record. The store-level eviction
        // primitive must re-read the current record under its lock before deleting bytes.
        store.update(candidate.assetId) { it.copy(pinOwners = setOf("track:pin-race")) }
        val evicted = store.evictIfEligible(candidate.assetId) { storage.evict(candidate.assetId) }

        assertNull(evicted)
        assertTrue(storage.finalFile(candidate.assetId).exists())
        assertTrue(store.get(candidate.assetId)?.pinned == true)
    }

    @Test
    fun concurrentQuotaWritesCannotOversubscribeCapacity() {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val quota = OfflineCacheQuotaManager(store, storage, maxBytes = 10)
        val a = asset("quota-a", ByteArray(8) { 1 })
        val b = asset("quota-b", ByteArray(8) { 2 })
        store.ensure(a)
        store.ensure(b)
        store.update(a.assetId) { it.copy(phase = OfflineDownloadPhase.DOWNLOADING) }
        store.update(b.assetId) { it.copy(phase = OfflineDownloadPhase.DOWNLOADING) }

        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val first = executor.submit<QuotaResult> {
                start.await(5, TimeUnit.SECONDS)
                quota.writeWithinQuota(a.assetId, 8) {
                    storage.partialFile(a.assetId).writeBytes(ByteArray(8) { 1 })
                }
            }
            val second = executor.submit<QuotaResult> {
                start.await(5, TimeUnit.SECONDS)
                quota.writeWithinQuota(b.assetId, 8) {
                    storage.partialFile(b.assetId).writeBytes(ByteArray(8) { 2 })
                }
            }
            start.countDown()

            val results = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.allowed })
            assertTrue(storage.usedBytes() <= 10L)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun corruptionRevalidationRejectsOfflinePlaybackUntilRedownloaded() = runTest {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val good = "offline-media".toByteArray()
        val asset = asset("offline", good)
        store.ensure(asset)
        storage.finalFile(asset.assetId).writeBytes("corrupt".toByteArray())
        store.update(asset.assetId) {
            it.copy(phase = OfflineDownloadPhase.AVAILABLE, bytesDownloaded = 7, pinOwners = setOf("track:offline"))
        }
        val resolver = OfflineMediaResolver(store, storage)

        assertNull(resolver.resolve(asset.assetId))
        assertEquals(OfflineDownloadPhase.CORRUPT, store.get(asset.assetId)?.phase)
        assertFalse(storage.finalFile(asset.assetId).exists())

        val transport = FakeRangeTransport(response(200, good))
        assertTrue(engine(store, storage, transport, 1024).download(asset) is DownloadAttemptResult.Success)
        val cached = resolver.resolve(asset.assetId)
        assertNotNull(cached)
        assertTrue(cached!!.uri.startsWith("file:"))
        assertEquals(asset.normalizedSha256, cached.contentSha256)
    }

    @Test
    fun playlistPinningPreservesIndependentTrackPinAndCancelRetryIsDeterministic() {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val scheduler = FakeScheduler()
        val coordinator = OfflineDownloadCoordinator(store, scheduler, OfflineMediaResolver(store, storage))
        val a = asset("a", "a".toByteArray())
        val b = asset("b", "b".toByteArray())

        coordinator.pinTrack(a)
        coordinator.pinPlaylist("crate", listOf(a, b))
        assertEquals(setOf("track:a", "playlist:crate"), store.get("a")?.pinOwners)
        assertEquals(setOf("playlist:crate"), store.get("b")?.pinOwners)
        assertEquals(OfflineDownloadPhase.QUEUED, coordinator.observe("a").value?.phase)

        coordinator.pinPlaylist("crate", listOf(b))
        assertEquals(setOf("track:a"), store.get("a")?.pinOwners)
        coordinator.cancel("b")
        assertEquals(OfflineDownloadPhase.CANCELLED, store.get("b")?.phase)
        assertTrue(store.get("b")?.cancelRequested == true)
        assertEquals(listOf("b"), scheduler.cancelled)

        coordinator.retry(b)
        assertEquals(OfflineDownloadPhase.QUEUED, store.get("b")?.phase)
        assertFalse(store.get("b")?.cancelRequested == true)
        assertTrue(scheduler.enqueued.last().replace)
    }

    @Test
    fun queuedProgressAndPinsSurviveStoreReconstruction() {
        val root = tempRoot()
        val stateDir = File(root, "state")
        val content = "persist".toByteArray()
        val asset = asset("persisted", content, etag = "\"persist-v1\"")
        OfflineDownloadStore(stateDir).also { first ->
            first.ensure(asset)
            first.update(asset.assetId) {
                it.copy(
                    phase = OfflineDownloadPhase.QUEUED,
                    bytesDownloaded = 3,
                    totalBytes = 7,
                    currentEtag = "\"persist-v1\"",
                    pinOwners = setOf("playlist:persist"),
                    error = OfflineDownloadError(DownloadFailureKind.NETWORK, "offline", retryable = true),
                )
            }
        }

        val restored = OfflineDownloadStore(stateDir).get(asset.assetId)
        assertNotNull(restored)
        assertEquals(OfflineDownloadPhase.QUEUED, restored?.phase)
        assertEquals(3L, restored?.bytesDownloaded)
        assertEquals(setOf("playlist:persist"), restored?.pinOwners)
        assertEquals("\"persist-v1\"", restored?.currentEtag)
        assertEquals(DownloadFailureKind.NETWORK, restored?.error?.kind)
    }

    private fun engine(
        store: OfflineDownloadStore,
        storage: OfflineCacheStorage,
        transport: RangeTransport,
        quotaBytes: Long,
    ): OfflineDownloadEngine = OfflineDownloadEngine(
        store = store,
        storage = storage,
        quota = OfflineCacheQuotaManager(store, storage, quotaBytes),
        transport = transport,
        progressPersistBytes = 1,
    )

    private fun asset(
        id: String,
        content: ByteArray,
        uri: String = "https://example.invalid/media/$id",
        etag: String? = null,
    ): OfflineAsset = OfflineAsset(
        assetId = id,
        resourceUri = uri,
        contentSha256 = sha256Hex(content),
        contentLength = content.size.toLong(),
        supportsRange = true,
        advertisedEtag = etag,
    )

    private fun response(
        status: Int,
        bytes: ByteArray,
        start: Long? = null,
        etag: String? = null,
    ): RangeResponse = RangeResponse(
        statusCode = status,
        body = ByteArrayInputStream(bytes),
        contentLength = bytes.size.toLong(),
        contentRangeStart = start,
        etag = etag,
        lastModified = null,
    )

    private fun tempRoot(): File = Files.createTempDirectory("androidjtools-offline-test").toFile().apply {
        deleteOnExit()
    }

    private class FakeRangeTransport(vararg responses: RangeResponse) : RangeTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<RangeRequest>()

        override suspend fun open(request: RangeRequest): RangeResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeScheduler : DownloadScheduler {
        data class Enqueued(val assetId: String, val replace: Boolean)
        val enqueued = mutableListOf<Enqueued>()
        val cancelled = mutableListOf<String>()

        override fun enqueue(asset: OfflineAsset, replace: Boolean) {
            enqueued += Enqueued(asset.assetId, replace)
        }

        override fun cancel(assetId: String) {
            cancelled += assetId
        }
    }
}
