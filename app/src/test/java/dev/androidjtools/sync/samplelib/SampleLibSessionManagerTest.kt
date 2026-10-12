package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibSessionManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, SAMPLE_LIB_CLIENT_ID, "install-1")
    private lateinit var store: SampleLibProfileStore
    private lateinit var manager: SampleLibSessionManager
    private val profile = SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1")

    @Before
    fun setUp() {
        store = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher())
        manager = SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" })
    }

    @After
    fun close() = db.close()

    private fun seedStoredSession() = store.save(profile, MobileCredential.issued("token-secret"))

    private fun seedCache(vararg ids: String) =
        cache.applyPage(ids.map { assetChange(it) }, emptyList(), CacheMeta("c0", "gen-1", "server-1"))

    @Test
    fun pairingSavesTheProfileSyncsTheLibraryAndGoesOnline() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        manager.pair("  https://samplelab.example/  ", " 246810 ")

        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
        assertNull(manager.error.value)
        assertEquals("https://samplelab.example", manager.profile.value?.baseUrl)
        assertEquals(setOf("asset", "interval", "marker"), manager.capabilities.value?.pull)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        val loaded = store.load() as StoredSessionResult.Loaded
        assertEquals("token-secret", loaded.session.credential.bearerValue())
        assertEquals("server-1", loaded.session.profile.serverId)
    }

    @Test
    fun aRejectedPairingCodeKeepsTheStateAndNeverEchoesTheCode() = runBlocking {
        transport.pairFailure = SampleLibFailure.PairingRejected()

        manager.pair("https://samplelab.example", "000999")

        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertEquals("The server rejected the pairing code.", manager.error.value)
        assertFalse(manager.error.value.orEmpty().contains("000999"))
        assertEquals(StoredSessionResult.Absent, store.load())
    }

    @Test
    fun anInsecureRemoteAddressIsRefusedBeforeAnyNetworkCall() = runBlocking {
        val real = SampleLibSessionManager(store, cache)

        real.pair("http://192.0.2.10:8765", "246810")

        assertTrue(real.error.value.orEmpty().contains("https://"))
        assertEquals(SyncState.UNCONFIGURED, real.state.value)
        assertEquals(StoredSessionResult.Absent, store.load())
    }

    @Test
    fun invalidInputIsReportedWithoutTouchingTheNetwork() = runBlocking {
        manager.pair("   ", "246810")
        assertEquals("Enter the server address.", manager.error.value)
        manager.pair("https://samplelab.example", "  ")
        assertEquals("Enter the pairing code.", manager.error.value)
        assertEquals(0, transport.pairCalls)
    }

    @Test
    fun pairingADifferentServerDropsTheOldServersCache() = runBlocking {
        cache.applyPage(listOf(assetChange("old")), emptyList(), CacheMeta("c0", "gen-0", "old-server"))
        transport.enqueuePage(page("c1", changes = listOf(assetChange("new"))))

        manager.pair("https://samplelab.example", "246810")

        assertEquals(listOf("new"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun restoreWithNothingStoredIsUnconfigured() = runBlocking {
        manager.refresh()
        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertEquals(0, transport.helloCalls)
    }

    @Test
    fun refreshReconnectsWithThePinnedIdentityAndSyncs() = runBlocking {
        seedStoredSession()
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        manager.refresh()

        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
        assertEquals(profile, manager.profile.value)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aRejectedCredentialRequiresPairingAgainButKeepsTheCachedLibrary() = runBlocking {
        seedStoredSession()
        seedCache("a1")
        transport.helloFailure = SampleLibFailure.AuthRequired()

        manager.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, manager.state.value)
        assertFalse(manager.error.value.orEmpty().contains("token-secret"))
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anUnreachableServerIsOfflineAndTheCachedLibraryStaysReadable() = runBlocking {
        seedStoredSession()
        seedCache("a1")
        transport.helloFailure = SampleLibFailure.Offline()

        manager.refresh()

        assertEquals(SyncState.OFFLINE, manager.state.value)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anIncompatibleServerIsReportedAsSuch() = runBlocking {
        seedStoredSession()
        transport.helloFailure = SampleLibFailure.Incompatible(listOf("1"), listOf("2"))

        manager.refresh()

        assertEquals(SyncState.SERVER_INCOMPATIBLE, manager.state.value)
    }

    @Test
    fun aChangedServerIdentityIsRefusedAndTellsThePersonToForgetIt() = runBlocking {
        seedStoredSession()
        seedCache("a1")
        transport.serverId = "someone-else"

        manager.refresh()

        assertEquals(SyncState.DEGRADED, manager.state.value)
        assertTrue(manager.error.value.orEmpty().contains("Forget"))
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anUnreadableStoredCredentialRequiresPairingAndMakesNoNetworkCall() = runBlocking {
        seedStoredSession()
        val brokenStore = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher(failDecrypt = true))
        val broken = SampleLibSessionManager(brokenStore, cache, { _, _ -> client }, { "install-1" })

        broken.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, broken.state.value)
        assertEquals(profile, broken.profile.value)
        assertEquals(0, transport.helloCalls)
    }

    @Test
    fun aLocalCacheFailureDegradesInsteadOfCrashing() = runBlocking {
        val failing = FailingSqlDatabase(JdbcSqlDatabase())
        val brokenCache = SqlLibraryCache(failing)
        val broken = SampleLibSessionManager(store, brokenCache, { _, _ -> client }, { "install-1" })
        seedStoredSession()
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        failing.failOnNthExecute(1)

        broken.refresh()

        assertEquals(SyncState.DEGRADED, broken.state.value)
        assertEquals("Local storage error.", broken.error.value)
    }

    @Test
    fun forgetRemovesTheProfileCredentialAndCache() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.pair("https://samplelab.example", "246810")

        manager.forget()

        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertNull(manager.profile.value)
        assertNull(manager.capabilities.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun forgetDuringAnInFlightSyncLeavesNothingBehind() = runBlocking {
        seedStoredSession()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        transport.onPull = {
            started.complete(Unit)
            gate.await()
        }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        val refresh = async(Dispatchers.Default) { manager.refresh() }
        started.await()
        val forget = async(Dispatchers.Default) { manager.forget() }
        delay(50)
        assertEquals(SyncState.PULLING, manager.state.value)
        gate.complete(Unit)
        refresh.await()
        forget.await()

        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
        assertNull(manager.profile.value)
    }
}
