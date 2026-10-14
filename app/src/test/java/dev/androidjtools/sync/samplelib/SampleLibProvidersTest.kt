package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.OpaqueRevision
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibProvidersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(type: String, id: String, body: String) =
        EntityChange(type, id, OpaqueRevision("r"), "s", kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)

    private fun asset(id: String, title: String) = json("asset", id, """{"title":"$title","duration_ms":10000}""")

    private fun interval(assetId: String) = json(
        "interval",
        "i-$assetId",
        """{"interval":{"id":"i-$assetId","asset_id":"$assetId","start_ms":1000,"end_ms":4000,"loopable":1},"markers":[{"id":"m-$assetId","position_ms":1500,"marker_type":"slice","color":"#112233"}]}""",
    )

    private class FakeLibrary(initial: List<Track>) : LibraryProvider {
        val flow = MutableStateFlow(initial)
        override val tracks: StateFlow<List<Track>> = flow
        override fun track(id: String): Track? = flow.value.firstOrNull { it.id == id }
    }

    private class FakeRuntime(initial: ProviderUiState) : AppRuntimeProvider {
        val flow = MutableStateFlow(initial)
        override val uiState: StateFlow<ProviderUiState> = flow
    }

    private class FakePlayback : PlaybackProvider {
        val played = mutableListOf<String>()
        override val currentTrackId = MutableStateFlow<String?>(null)
        override val isPlaying = MutableStateFlow(false)
        override val positionMs = MutableStateFlow(0L)
        override fun play(trackId: String) {
            played += trackId
        }
        override fun toggle() = Unit
        override fun seek(positionMs: Long) = Unit
    }

    private fun track(id: String) = Track(id = id, title = id, artist = "x", durationMs = 1)

    @Test
    fun theServerLibraryFollowsEveryCacheCommit() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val library = SampleLibLibraryProvider(state)
        assertTrue(library.tracks.value.isEmpty())

        cache.applyPage(listOf(asset("a1", "Alpha")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertEquals(listOf("Alpha"), library.tracks.value.map { it.title })
        assertEquals("Alpha", library.track("a1")?.title)
        assertNull(library.track("missing"))

        cache.reset()
        advanceUntilIdle()
        assertTrue(library.tracks.value.isEmpty())
    }

    @Test
    fun preparationFlowsTrackCacheCommitsIncludingRemovals() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val preparation = SampleLibPreparationProvider(state, backgroundScope)

        // The flows are obtained BEFORE any commit, as a screen that is already collecting would.
        val cues = preparation.cues("a1")
        val loops = preparation.loops("a1")
        assertTrue(cues.value.isEmpty())
        assertTrue(loops.value.isEmpty())

        cache.applyPage(listOf(asset("a1", "Alpha"), interval("a1")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertEquals(listOf("m-a1"), cues.value.map { it.id })
        assertEquals(listOf("i-a1"), loops.value.map { it.id })

        cache.applyPage(emptyList(), listOf(tombstoneOf("interval", "i-a1")), CacheMeta("c2", "g", "s"))
        advanceUntilIdle()
        assertTrue(cues.value.isEmpty())
        assertTrue(loops.value.isEmpty())

        assertTrue(preparation.cues("nope").value.isEmpty())
        assertNull(preparation.beatGrid("a1").value)
    }

    @Test
    fun theCompositeLibraryListsServerTracksAndDeviceTracksTogether() = runTest(UnconfinedTestDispatcher()) {
        val server = FakeLibrary(listOf(track("srv-1")))
        val device = FakeLibrary(listOf(track("dev-1")))
        val composite = CompositeLibraryProvider(server, device, backgroundScope)

        assertEquals(listOf("srv-1", "dev-1"), composite.tracks.value.map { it.id })
        device.flow.value = listOf(track("dev-1"), track("dev-2"))
        assertEquals(listOf("srv-1", "dev-1", "dev-2"), composite.tracks.value.map { it.id })
        assertEquals("srv-1", composite.track("srv-1")?.id)
        assertEquals("dev-2", composite.track("dev-2")?.id)
        assertNull(composite.track("none"))
    }

    @Test
    fun aDevicePermissionErrorDoesNotHideAServerLibrary() = runTest(UnconfinedTestDispatcher()) {
        val server = FakeLibrary(emptyList())
        val device = FakeRuntime(ProviderUiState.Error("Audio-library permission is required"))
        val runtime = CompositeRuntimeProvider(device, server, backgroundScope)

        assertEquals(ProviderUiState.Error("Audio-library permission is required"), runtime.uiState.value)
        server.flow.value = listOf(track("srv-1"))
        assertEquals(ProviderUiState.Ready, runtime.uiState.value)
        device.flow.value = ProviderUiState.Offline
        assertEquals(ProviderUiState.Offline, runtime.uiState.value)
    }

    @Test
    fun serverTracksAreNotPlayedAndSayWhy() {
        val device = FakePlayback()
        val playback = ServerAwarePlayback(device) { it.startsWith("srv-") }

        playback.play("srv-1")
        assertTrue(device.played.isEmpty())
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, playback.playbackNotice.value)

        playback.play("dev-1")
        assertEquals(listOf("dev-1"), device.played)
        assertNull(playback.playbackNotice.value)
    }

    @Test
    fun playbackWithoutAGateNeverHasANotice() {
        assertNull(FakePlayback().playbackNotice.value)
    }

    private class FakeDownloads : DownloadProvider {
        val pinned = mutableSetOf<String>()
        val retried = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override val cacheSummary = MutableStateFlow(OfflineCacheSummary())
        private val states = mutableMapOf<String, MutableStateFlow<TrackDownloadState>>()

        override fun state(trackId: String): StateFlow<TrackDownloadState> =
            states.getOrPut(trackId) { MutableStateFlow(TrackDownloadState(trackId, DownloadStatus.AVAILABLE, progress = 1.0)) }

        override fun pinTrack(trackId: String) { pinned += trackId }
        override fun unpinTrack(trackId: String) { pinned -= trackId }
        override fun pinPlaylist(playlistId: String) = Unit
        override fun unpinPlaylist(playlistId: String) = Unit
        override fun retry(trackId: String) { retried += trackId }
        override fun cancel(trackId: String) { cancelled += trackId }
        override fun pruneUnpinned() = Unit
    }

    @Test
    fun serverTracksReportNotDownloadedAndNeverReachDelegateForPinRetryCancel() {
        val delegate = FakeDownloads()
        val downloads = ServerAwareDownloads(delegate) { it.startsWith("srv-") }

        val state = downloads.state("srv-1")
        assertEquals(DownloadStatus.NOT_DOWNLOADED, state.value.status)

        downloads.pinTrack("srv-1")
        assertTrue(delegate.pinned.isEmpty())

        downloads.unpinTrack("srv-1")
        assertTrue(delegate.pinned.isEmpty())

        downloads.retry("srv-1")
        assertTrue(delegate.retried.isEmpty())

        downloads.cancel("srv-1")
        assertTrue(delegate.cancelled.isEmpty())
    }

    /** Delegates to a real cache but can inject failures into snapshot(). */
    private class FailingSnapshotCache(private val delegate: LibraryCache) : LibraryCache by delegate {
        var shouldFailSnapshot = false

        override fun snapshot(vararg entityTypes: String): Map<String, List<CachedEntity>> {
            if (shouldFailSnapshot) {
                shouldFailSnapshot = false
                throw IllegalStateException("injected snapshot failure")
            }
            return delegate.snapshot(*entityTypes)
        }
    }

    @Test
    fun projectionFlowSurvivesATransientCacheError() = runTest(UnconfinedTestDispatcher()) {
        val realDb = JdbcSqlDatabase()
        val realCache = SqlLibraryCache(realDb)
        val failingCache = FailingSnapshotCache(realCache)
        val state = SampleLibProjectionState(failingCache, backgroundScope, UnconfinedTestDispatcher(testScheduler))

        // Seed one commit
        realCache.applyPage(listOf(asset("a1", "Alpha")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertEquals(listOf("Alpha"), state.tracks.value.map { it.title })

        // Inject a failure so the next recompute throws
        failingCache.shouldFailSnapshot = true
        realCache.applyPage(listOf(asset("a2", "Beta")), emptyList(), CacheMeta("c2", "g", "s"))
        advanceUntilIdle()

        // Previous value is kept, no crash, scope still alive
        assertEquals(listOf("Alpha"), state.tracks.value.map { it.title })

        // Commit again — projection updates now that the failure is gone
        realCache.applyPage(listOf(asset("a3", "Gamma")), emptyList(), CacheMeta("c3", "g", "s"))
        advanceUntilIdle()
        assertTrue(state.tracks.value.map { it.title }.contains("Gamma"))
    }

    @Test
    fun cancellingTheWrapperScopeStopsWrapperFlowUpdates() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val projectionState = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler))

        val serverLib = SampleLibLibraryProvider(projectionState)
        val deviceLib = FakeLibrary(listOf(track("dev-1")))

        // Create a child scope that simulates lifecycleScope
        val childJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val wrapperScope = CoroutineScope(childJob + UnconfinedTestDispatcher(testScheduler))

        val composite = CompositeLibraryProvider(serverLib, deviceLib, wrapperScope)

        cache.applyPage(listOf(asset("srv-1", "Server")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertEquals(listOf("srv-1", "dev-1"), composite.tracks.value.map { it.id })

        // Cancel the wrapper scope (simulating activity destroyed)
        wrapperScope.cancel()

        // Further changes should not update the wrapper
        cache.applyPage(listOf(asset("srv-2", "Server2")), emptyList(), CacheMeta("c2", "g", "s"))
        advanceUntilIdle()

        // The composite still holds the old value since collectors are gone
        assertEquals(listOf("srv-1", "dev-1"), composite.tracks.value.map { it.id })

        // But the projection state still updates (process-scoped)
        assertEquals(listOf("srv-1", "srv-2"), projectionState.tracks.value.map { it.id })
    }

    @Test
    fun deviceTracksDelegate() {
        val delegate = FakeDownloads()
        val downloads = ServerAwareDownloads(delegate) { it.startsWith("srv-") }

        val state = downloads.state("dev-1")
        assertEquals(DownloadStatus.AVAILABLE, state.value.status)

        downloads.pinTrack("dev-1")
        assertTrue(delegate.pinned.contains("dev-1"))
    }

    @Test
    fun theSyncProviderMirrorsTheManagerState() = runTest(UnconfinedTestDispatcher()) {
        val db = JdbcSqlDatabase()
        val manager = SampleLibSessionManager(
            SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher()),
            SqlLibraryCache(db),
            { _, _ -> error("not used") },
        )
        val provider = SampleLibSyncProvider(manager)
        assertEquals(SyncState.UNCONFIGURED, provider.state.value)
        assertEquals(0, provider.pendingMutationCount.value)
        db.close()
    }

    private fun assetFileChange(id: String, assetId: String) = json(
        "asset_file", id,
        """{"id":"$id","asset_id":"$assetId","file_role":"original","resource_uri":"original://$id","sha256":"${"ab".repeat(32)}","size_bytes":9,"mime_type":"audio/wav","etag":"\"${"ab".repeat(32)}\""}""",
    )

    private fun caps(vararg pull: String) = dev.androidjtools.remote.samplelib.ServerCapabilities(
        pull.toSet(), emptySet(), "canonical_v1", emptySet(), setOf("resource_uri", "content_hash", "range"),
    )

    @Test
    fun tracksBecomePlayableWhenTheServerAdvertisesAudioAndFollowTheDownloadedFiles() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val capabilities = MutableStateFlow<dev.androidjtools.remote.samplelib.ServerCapabilities?>(null)
        val downloaded = MutableStateFlow<Map<String, String>>(emptyMap())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler), capabilities, downloaded)
        cache.applyPage(listOf(asset("a1", "Alpha"), assetFileChange("f1", "a1")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()

        // Capabilities not known yet (cold start): cached file rows are the evidence, so tracks are not locked out.
        assertTrue(state.tracks.value.single().playable)

        capabilities.value = caps("asset", "interval")
        advanceUntilIdle()
        assertTrue("server without asset_file", !state.tracks.value.single().playable)

        capabilities.value = caps("asset", "asset_file")
        advanceUntilIdle()
        assertTrue(state.tracks.value.single().playable)
        assertTrue(!state.tracks.value.single().offlineAvailable)

        downloaded.value = mapOf("f1" to "ab".repeat(32))
        advanceUntilIdle()
        assertTrue(state.tracks.value.single().offlineAvailable)
        assertEquals("f1", state.projection.value.audio.getValue("a1").download.id)
    }
}
