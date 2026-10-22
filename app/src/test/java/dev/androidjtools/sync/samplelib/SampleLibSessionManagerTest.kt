package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.MutationConflict
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.PushResult
import dev.androidjtools.remote.samplelib.ReceiptOutcome
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalEditOutbox
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test
    fun cancellingARefreshMidSyncRestoresTheStateReleasesTheLockAndPropagates() = runBlocking {
        seedStoredSession()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        transport.onPull = {
            started.complete(Unit)
            gate.await()
        }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        val job = launch(Dispatchers.Default) { manager.refresh() }
        started.await()
        assertEquals(SyncState.PULLING, manager.state.value)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(SyncState.UNCONFIGURED, manager.state.value)

        gate.complete(Unit)
        manager.refresh()
        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun cancellingAPairingMidSyncRestoresTheStateAndPropagates() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        transport.onPull = {
            started.complete(Unit)
            gate.await()
        }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        val job = launch(Dispatchers.Default) { manager.pair("https://samplelab.example", "246810") }
        started.await()
        assertEquals(1, transport.pairCalls)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertNull(manager.error.value)

        gate.complete(Unit)
        manager.refresh()
        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
    }

    @Test
    fun aFailingCacheResetDuringForgetIsReportedWithoutThrowing() = runBlocking {
        val failing = FailingSqlDatabase(JdbcSqlDatabase())
        val brokenCache = SqlLibraryCache(failing)
        val subject = SampleLibSessionManager(store, brokenCache, { _, _ -> client }, { "install-1" })
        seedStoredSession()
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.refresh()
        assertEquals(SyncState.ONLINE_IDLE, subject.state.value)
        failing.failOnNthExecute(1)

        subject.forget()

        assertEquals(SyncState.DEGRADED, subject.state.value)
        assertEquals(FORGET_FAILED, subject.error.value)
        assertEquals(profile, subject.profile.value)
        // store.clear() is independent of the failing reset, so it still ran and removed the credential.
        assertEquals(StoredSessionResult.Absent, store.load())
    }

    @Test
    fun anUndeletableCredentialMakesForgetReportFailureAndKeepTheProfile() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.pair("https://samplelab.example", "246810")
        val credential = File(tmp.root, "sl/samplelib-credential.bin")
        assertTrue(credential.delete())
        assertTrue(credential.mkdirs())
        File(credential, "blocker").writeText("x") // non-empty directory: File.delete() returns false

        manager.forget()

        assertEquals(SyncState.DEGRADED, manager.state.value)
        assertEquals(FORGET_FAILED, manager.error.value)
        assertEquals("https://samplelab.example", manager.profile.value?.baseUrl)
        assertTrue(cache.entities("asset").isEmpty())
    }

    private companion object {
        const val FORGET_FAILED = "Could not remove the stored server connection from this device. Try again."
    }

    @Test
    fun theMediaCredentialIsAvailableAfterPairingAndGoneAfterForget() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.pair("https://samplelab.example", "246810")

        val auth = manager.mediaAuth()!!
        assertEquals("samplelab.example", auth.base.host)

        manager.forget()
        assertNull(manager.mediaAuth())
    }

    @Test
    fun aColdManagerLoadsTheMediaCredentialFromTheStoreOnDemand() = runBlocking {
        seedStoredSession()
        assertEquals("samplelab.example", manager.mediaAuth()!!.base.host)
    }

    @Test
    fun aRejectedCredentialWithdrawsTheMediaCredentialUntilTheNextAcceptedSession() = runBlocking {
        seedStoredSession()
        transport.helloFailure = SampleLibFailure.AuthRequired()

        manager.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, manager.state.value)
        assertNull(manager.mediaAuth())

        transport.helloFailure = null
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.refresh()
        assertEquals("samplelab.example", manager.mediaAuth()!!.base.host)
    }

    @Test
    fun withNothingStoredThereIsNoMediaCredential() = runBlocking {
        manager.refresh()
        assertNull(manager.mediaAuth())
    }

    private fun managerWith(hook: suspend () -> Boolean) =
        SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" }, hook)

    @Test
    fun forgetAlsoDiscardsTheDownloadsAndNeedsThemGone() = runBlocking {
        var calls = 0
        val subject = managerWith { calls++; true }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(1, calls)
        assertEquals(SyncState.UNCONFIGURED, subject.state.value)
    }

    @Test
    fun aFailedDownloadCleanupKeepsTheConnectionSoForgetCanBeRetried() = runBlocking {
        val subject = managerWith { false }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(SyncState.DEGRADED, subject.state.value)
        assertTrue(subject.profile.value != null)
    }

    @Test
    fun aFailedDownloadCleanupKeepsTheCachedServerIdentitySoALaterPairingOrForgetRetriesIt() = runBlocking {
        var downloadsRemovable = false
        val subject = managerWith { downloadsRemovable }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(SyncState.DEGRADED, subject.state.value)
        // Without the cached server id a later pairing to another server could not tell the old downloads are foreign.
        assertEquals("server-1", cache.meta().serverId)

        downloadsRemovable = true
        subject.forget()

        assertEquals(SyncState.UNCONFIGURED, subject.state.value)
        assertEquals(null, cache.meta().serverId)
    }

    @Test
    fun aThrowingCleanupIsReportedNotPropagated() = runBlocking {
        val subject = managerWith { error("disk gone") }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(SyncState.DEGRADED, subject.state.value)
    }

    @Test
    fun pairingADifferentServerDiscardsTheOldServersDownloadsFirst() = runBlocking {
        var calls = 0
        cache.applyPage(listOf(assetChange("old")), emptyList(), CacheMeta("c0", "gen-0", "old-server"))
        val subject = managerWith { calls++; true }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        subject.pair("https://samplelab.example", "246810")

        assertEquals(1, calls)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun pairingTheSameServerKeepsItsDownloads() = runBlocking {
        var calls = 0
        cache.applyPage(listOf(assetChange("a1")), emptyList(), CacheMeta("c0", "gen-1", "server-1"))
        val subject = managerWith { calls++; true }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        subject.pair("https://samplelab.example", "246810")

        assertEquals(0, calls)
    }

    @Test
    fun ifTheOldDownloadsCannotBeRemovedThePairingIsAbortedBeforeAnythingIsSaved() = runBlocking {
        cache.applyPage(listOf(assetChange("old")), emptyList(), CacheMeta("c0", "gen-0", "old-server"))
        val subject = managerWith { false }

        subject.pair("https://samplelab.example", "246810")

        assertEquals(StoredSessionResult.Absent, store.load())
        assertEquals(listOf("old"), cache.entities("asset").map { it.entityId })
        assertTrue(subject.error.value.orEmpty().contains("downloads"))
        assertEquals(SyncState.UNCONFIGURED, subject.state.value)
    }

    private class MemoryJournalStorage : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            durable = snapshot
        }
    }

    private lateinit var lastManager: SampleLibSessionManager

    private fun managerWithEdits(journal: DurableMutationJournal): SampleLibSessionManager =
        SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" }, outbox = JournalEditOutbox(journal)).also { lastManager = it }

    private fun queueEdit(journal: DurableMutationJournal, id: String = "mutation-edit-1") = journal.enqueue(
        mutationId = id,
        entityType = "interval",
        entityId = "interval-1",
        baseRevision = "rev-old",
        operation = "interval.editor_state.replace",
        payloadJson = """{"start_ms":0,"end_ms":5000,"markers":[]}""",
        provenanceJson = """{"source":"android_manual"}""",
        createdAtEpochMillis = 1000,
    )

    private fun applied(id: String) = MutationReceipt(id, ReceiptOutcome.APPLIED, 5L, OpaqueRevision("rev-new"))

    @Test
    fun refreshDeliversQueuedEditsBeforeItPullsSoThePulledStateIncludesThem() = runBlocking {
        seedStoredSession()
        val journal = DurableMutationJournal(MemoryJournalStorage())
        queueEdit(journal)
        transport.pushScript = { sent -> PushResult(sent.map { applied(it.mutationId) }, 5L) }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        managerWithEdits(journal).refresh()

        assertEquals(listOf("hello", "push", "pull"), transport.calls)
        assertEquals(JournalMutationState.RECEIPT_RECORDED, journal.mutation("mutation-edit-1")?.state)
        assertEquals(SyncState.ONLINE_IDLE, lastManager.state.value)
    }

    @Test
    fun noQueuedEditsMeansNoPushAtAll() = runBlocking {
        seedStoredSession()
        transport.enqueuePage(page("c1"))

        managerWithEdits(DurableMutationJournal(MemoryJournalStorage())).refresh()

        assertEquals(listOf("hello", "pull"), transport.calls)
    }

    @Test
    fun anUnreachablePushKeepsTheEditPendingAndTheLibraryStillSyncs() = runBlocking {
        seedStoredSession()
        val journal = DurableMutationJournal(MemoryJournalStorage())
        queueEdit(journal)
        transport.pushScript = { throw SampleLibFailure.Offline() }
        transport.receiptScript = { throw SampleLibFailure.ReceiptNotFound(it) }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        managerWithEdits(journal).refresh()

        assertEquals(JournalMutationState.PENDING, journal.mutation("mutation-edit-1")?.state)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        assertEquals(SyncState.ONLINE_IDLE, lastManager.state.value)
    }

    @Test
    fun aServerThatDoesNotAdvertiseEditingLeavesTheEditPendingAndNeverPushes() = runBlocking {
        seedStoredSession()
        val journal = DurableMutationJournal(MemoryJournalStorage())
        queueEdit(journal)
        transport.capabilities = transport.capabilities.copy(mutations = emptySet())
        transport.enqueuePage(page("c1"))

        managerWithEdits(journal).refresh()

        assertFalse("push" in transport.calls)
        assertEquals(JournalMutationState.PENDING, journal.mutation("mutation-edit-1")?.state)
        assertEquals(SyncState.ONLINE_IDLE, lastManager.state.value)
    }

    @Test
    fun aConflictReceiptIsRecordedWithItsEvidenceAndTheSyncContinues() = runBlocking {
        seedStoredSession()
        val journal = DurableMutationJournal(MemoryJournalStorage())
        queueEdit(journal)
        transport.pushScript = { sent ->
            PushResult(
                sent.map {
                    MutationReceipt(
                        it.mutationId, ReceiptOutcome.CONFLICT, 6L,
                        conflict = MutationConflict(
                            it.mutationId, "interval-1", OpaqueRevision("rev-old"), OpaqueRevision("rev-remote"),
                            null, null, "editor_aggregate", "stale_base_revision",
                        ),
                    )
                },
                6L,
            )
        }
        transport.enqueuePage(page("c1"))

        managerWithEdits(journal).refresh()

        val recorded = journal.mutation("mutation-edit-1")!!
        assertEquals(JournalMutationState.CONFLICT, recorded.state)
        assertEquals("rev-remote", recorded.receipt?.conflict?.authoritativeRevision)
        assertEquals(listOf("hello", "push", "pull"), transport.calls)
    }
}
