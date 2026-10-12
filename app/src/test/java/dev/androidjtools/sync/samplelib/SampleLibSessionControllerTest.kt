package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibSessionControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeScheduler : SyncScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun schedule() { scheduled++ }
        override fun cancel() { cancelled++ }
    }

    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, SAMPLE_LIB_CLIENT_ID, "install-1")
    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val scheduler = FakeScheduler()

    private fun store() = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher())
    private fun manager(store: SampleLibProfileStore) =
        SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" })

    @Test
    fun aSuccessfulPairingSchedulesPeriodicSyncAndAFailedOneDoesNot() = runTest(UnconfinedTestDispatcher()) {
        val controller = SampleLibSessionController(manager(store()), backgroundScope, scheduler)

        transport.pairFailure = SampleLibFailure.PairingRejected()
        controller.pair("https://samplelab.example", "000000")
        advanceUntilIdle()
        assertEquals(0, scheduler.scheduled)
        assertEquals("The server rejected the pairing code.", controller.error.value)

        transport.pairFailure = null
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        controller.pair("https://samplelab.example", "246810")
        advanceUntilIdle()
        assertEquals(1, scheduler.scheduled)
        assertEquals(SyncState.ONLINE_IDLE, controller.state.value)
        assertEquals("https://samplelab.example", controller.serverUrl.value)
    }

    @Test
    fun forgetCancelsTheScheduleAndClearsEverything() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val controller = SampleLibSessionController(manager(store), backgroundScope, scheduler)
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        controller.pair("https://samplelab.example", "246810")
        advanceUntilIdle()

        controller.forget()
        advanceUntilIdle()

        assertEquals(1, scheduler.cancelled)
        assertEquals(SyncState.UNCONFIGURED, controller.state.value)
        assertNull(controller.serverUrl.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun forgetCancelsAnInFlightRefreshInsteadOfWaitingForIt() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.save(SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1"), MobileCredential.issued("token-secret"))
        val gate = CompletableDeferred<Unit>()
        transport.onPull = { gate.await() }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        val controller = SampleLibSessionController(manager(store), backgroundScope, scheduler)

        controller.refresh()
        advanceUntilIdle()
        assertEquals(SyncState.PULLING, controller.state.value)

        controller.forget()
        advanceUntilIdle()

        assertEquals(SyncState.UNCONFIGURED, controller.state.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
        assertTrue(!gate.isCompleted)
    }

    @Test
    fun aRequestMadeWhileAForgetIsStillWaitingDoesNotCancelThatForget() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.save(SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1"), MobileCredential.issued("token-secret"))
        val gate = CompletableDeferred<Unit>()
        // A refresh that cannot be interrupted keeps the forget queued behind it.
        transport.onPull = { withContext(NonCancellable) { gate.await() } }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        val controller = SampleLibSessionController(manager(store), backgroundScope, scheduler)

        controller.refresh()
        assertEquals(SyncState.PULLING, controller.state.value)
        controller.forget()
        controller.refresh()
        assertEquals(StoredSessionResult.Loaded::class, store.load()::class)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(StoredSessionResult.Absent, store.load())
        assertEquals(SyncState.UNCONFIGURED, controller.state.value)
        assertTrue(cache.entities("asset").isEmpty())
        assertEquals(1, transport.helloCalls)
        assertEquals(1, scheduler.cancelled)
    }
}
