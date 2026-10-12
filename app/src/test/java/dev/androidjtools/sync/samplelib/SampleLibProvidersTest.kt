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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun preparationExposesCuesAndLoopsPerTrackAndNothingForUnknownTracks() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val preparation = SampleLibPreparationProvider(state, backgroundScope)

        cache.applyPage(listOf(asset("a1", "Alpha"), interval("a1")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()

        assertEquals(listOf("m-a1"), preparation.cues("a1").value.map { it.id })
        assertEquals(listOf("i-a1"), preparation.loops("a1").value.map { it.id })
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
}
