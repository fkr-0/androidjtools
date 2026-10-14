package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppRuntimeProvider
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
    fun aRefusedTrackIsNotPlayedAndSaysWhy() {
        val device = FakePlayback()
        val playback = ServerAwarePlayback(device) { if (it.startsWith("srv-")) "No audio on the server." else null }

        playback.play("srv-1")
        assertTrue(device.played.isEmpty())
        assertEquals("No audio on the server.", playback.playbackNotice.value)

        playback.play("dev-1")
        assertEquals(listOf("dev-1"), device.played)
        assertNull(playback.playbackNotice.value)
    }

    @Test
    fun aPlayableServerTrackReachesTheDelegate() {
        val device = FakePlayback()
        val playback = ServerAwarePlayback(device) { null }
        playback.play("srv-ok")
        assertEquals(listOf("srv-ok"), device.played)
    }

    @Test
    fun playbackRefusalFollowsTheProjection() {
        val playable = track("srv-ok")
        val blocked = track("srv-no").copy(playable = false, unplayableReason = "No audio on the server.")
        val bare = track("srv-bare").copy(playable = false, unplayableReason = null)
        val projection = LibraryProjection(listOf(playable, blocked, bare), emptyMap(), emptyMap())

        assertNull(playbackRefusal(projection, "srv-ok"))
        assertEquals("No audio on the server.", playbackRefusal(projection, "srv-no"))
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, playbackRefusal(projection, "srv-bare"))
        // Not a server track (device media, or an id from before a Forget): the player decides.
        assertNull(playbackRefusal(projection, "device-media:7"))
        assertNull(playbackRefusal(LibraryProjection.EMPTY, "srv-ok"))
    }

    @Test
    fun playbackWithoutAGateNeverHasANotice() {
        assertNull(FakePlayback().playbackNotice.value)
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

    @Test
    fun aRejectedCredentialWithdrawsStreamingAndSaysToPairAgainButKeepsDownloadedCopiesPlayable() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val pairingRequired = MutableStateFlow(false)
        val downloaded = MutableStateFlow<Map<String, String>>(emptyMap())
        val state = SampleLibProjectionState(
            cache, backgroundScope, UnconfinedTestDispatcher(testScheduler),
            downloaded = downloaded, pairingRequired = pairingRequired,
        )
        cache.applyPage(listOf(asset("a1", "Alpha"), assetFileChange("f1", "a1")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertTrue(state.tracks.value.single().playable)

        pairingRequired.value = true
        advanceUntilIdle()
        val blocked = state.tracks.value.single()
        assertTrue(!blocked.playable)
        assertEquals(PAIR_AGAIN_NOTICE, blocked.unplayableReason)
        assertTrue(!state.projection.value.audioAdvertised)

        downloaded.value = mapOf("f1" to "ab".repeat(32))
        advanceUntilIdle()
        assertTrue("a verified local copy needs no credential", state.tracks.value.single().playable)

        downloaded.value = emptyMap()
        pairingRequired.value = false
        advanceUntilIdle()
        assertTrue(state.tracks.value.single().playable)
    }
}
