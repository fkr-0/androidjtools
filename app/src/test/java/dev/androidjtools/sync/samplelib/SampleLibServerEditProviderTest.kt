package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.prep.cues.EditorMarkerDraft
import dev.androidjtools.prep.cues.IntervalEditorStatePayload
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalConflict
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalReceipt
import dev.androidjtools.sync.journal.JournalReceiptOutcome
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibServerEditProviderTest {
    private class MemoryStorage : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            durable = snapshot
        }
    }

    private val editorOp = "interval.editor_state.replace"
    private fun caps(mutations: Set<String> = setOf(editorOp)) =
        ServerCapabilities(setOf("interval"), mutations, "canonical_v1", emptySet(), emptySet())

    private class Fixture(
        scope: TestScope,
        capabilities: ServerCapabilities?,
        state: SyncState,
        projection: LibraryProjection,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
    ) {
        val storage = MemoryStorage()
        var journalLoadedOn: String? = null
        val journal: DurableMutationJournal by lazy {
            journalLoadedOn = Thread.currentThread().name
            DurableMutationJournal(storage)
        }
        val capabilities = MutableStateFlow(capabilities)
        val state = MutableStateFlow(state)
        val projection = MutableStateFlow(projection)
        var flushes = 0
        var now = 5_000L
        val provider = SampleLibServerEditProvider(
            lazy { journal }, this.projection, this.capabilities, this.state, { flushes++ },
            io, scope.backgroundScope, { now },
        )
    }

    private val library = LibraryProjector.project(
        listOf(CachedEntity("asset", "a1", "r", "s", """{"title":"Alpha","duration_ms":10000}""")),
        listOf(
            CachedEntity(
                "interval", "iv-1", "rev-1", "s",
                """{"interval":{"id":"iv-1","asset_id":"a1","start_ms":0,"end_ms":10000},"markers":[{"id":"m1","position_ms":1500,"label":"Intro","marker_type":"custom"}]}""",
            ),
        ),
    )

    private fun TestScope.fixture(
        capabilities: ServerCapabilities? = caps(),
        state: SyncState = SyncState.ONLINE_IDLE,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) = Fixture(this, capabilities, state, library, io)

    private fun intent(id: String = "mutation-0001", surface: String = "cue_editor") = IntervalEditorStateReplaceIntent(
        mutationId = id,
        entityId = "iv-1",
        baseRevision = "rev-1",
        payload = IntervalEditorStatePayload(0L, 10_000L, listOf(EditorMarkerDraft("m1", 1500L, "Edited", "cue")), "Android DJ edit"),
        createdAtEpochMillis = 1_000L,
        provenance = mapOf("source" to "android_manual", "surface" to surface),
    )

    private fun conflictReceipt(id: String, authoritative: String? = "rev-remote") = JournalReceipt(
        id, JournalReceiptOutcome.CONFLICT,
        conflict = JournalConflict("stale_base_revision", "editor_aggregate", authoritative, "{}", "{}"),
    )

    // ---- construction ----

    @Test
    fun theJournalFileIsReadOnTheIoDispatcherNeverOnTheCallersThread() = runTest(UnconfinedTestDispatcher()) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "edits-io-test") }
        try {
            val f = fixture(io = executor.asCoroutineDispatcher())
            f.provider.availability.value
            f.provider.aggregate("iv-1")
            f.provider.stage(intent())

            assertTrue("loaded on ${f.journalLoadedOn}", f.journalLoadedOn.orEmpty().startsWith("edits-io-test"))
        } finally {
            executor.shutdown()
        }
    }

    // ---- availability ----

    @Test
    fun editingIsUnavailableUntilAServerIsPairedAndSaysWhy() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = null, state = SyncState.UNCONFIGURED)
        val unavailable = f.provider.availability.value as EditAvailability.Unavailable
        assertTrue(unavailable.reason.contains("Connect"))
    }

    @Test
    fun aRejectedCredentialAsksToPairAgain() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(state = SyncState.AUTH_REQUIRED)
        assertTrue((f.provider.availability.value as EditAvailability.Unavailable).reason.contains("Pair again"))
    }

    @Test
    fun editsCanBeQueuedOfflineBeforeTheServerHasBeenAskedThisSession() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = null, state = SyncState.OFFLINE)
        assertEquals(EditAvailability.Available, f.provider.availability.value)
    }

    @Test
    fun aServerThatDoesNotAdvertiseEditingSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = caps(mutations = emptySet()))
        assertTrue((f.provider.availability.value as EditAvailability.Unavailable).reason.contains("does not support"))
        f.capabilities.value = caps()
        assertEquals(EditAvailability.Available, f.provider.availability.value)
    }

    // ---- staging ----

    @Test
    fun stagingQueuesThePendingEditAndAsksForADelivery() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val result = f.provider.stage(intent())

        assertEquals(PreparationStageResult.Queued("mutation-0001"), result)
        assertEquals(1, f.flushes)
        val edit = f.provider.edits.value.single()
        assertEquals(ServerEditStatus.PENDING, edit.status)
        assertEquals("iv-1", edit.intervalId)
        assertEquals("a1", edit.trackId)
        assertEquals("Cue edit", edit.summary)
        assertEquals(1_000L, edit.createdAtEpochMillis)
    }

    @Test
    fun aLoopEditIsNamedAsOne() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent(surface = "loop_editor"))
        assertEquals("Loop edit", f.provider.edits.value.single().summary)
    }

    @Test
    fun stagingIsRefusedWithTheReasonWhenEditingIsUnavailableAndNothingIsQueued() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(state = SyncState.AUTH_REQUIRED)
        val result = f.provider.stage(intent())

        assertEquals("unavailable", (result as PreparationStageResult.Rejected).code)
        assertTrue(f.journal.all().isEmpty())
        assertEquals(0, f.flushes)
    }

    @Test
    fun theAggregateComesFromTheLastSyncedIntervalRevision() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val aggregate = f.provider.aggregate("iv-1")!!
        assertEquals("rev-1", aggregate.baseRevision)
        assertNull(f.provider.aggregate("unknown"))
    }

    @Test
    fun theEditableIntervalsOfATrackComeFromTheLibrary() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        assertEquals(listOf("iv-1"), f.provider.aggregates("a1").map { it.intervalId })
        assertTrue(f.provider.aggregates("unknown").isEmpty())
    }

    // ---- status ----

    @Test
    fun anEditIsNeverShownAsSavedBeforeTheServerAcknowledgedIt() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())
        assertEquals(ServerEditStatus.PENDING, f.provider.edits.value.single().status)

        f.journal.markApplying("mutation-0001")
        assertEquals(ServerEditStatus.SENDING, f.provider.edits.value.single().status)

        f.journal.recordReceipt(JournalReceipt("mutation-0001", JournalReceiptOutcome.APPLIED, "rev-2"))
        assertEquals(ServerEditStatus.SAVED, f.provider.edits.value.single().status)
    }

    @Test
    fun aRejectedEditKeepsTheServersReason() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())
        f.journal.recordReceipt(JournalReceipt("mutation-0001", JournalReceiptOutcome.REJECTED, detail = "invalid_payload: bad marker"))

        val edit = f.provider.edits.value.single()
        assertEquals(ServerEditStatus.REJECTED, edit.status)
        assertTrue(edit.detail.contains("bad marker"))
    }

    @Test
    fun aConflictIsShownAsOneAndNewestEditsComeFirst() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent("mutation-0001"))
        f.now += 10
        f.provider.stage(intent("mutation-0002").copy(createdAtEpochMillis = 2_000L))
        f.journal.recordReceipt(conflictReceipt("mutation-0001"))

        val edits = f.provider.edits.value
        assertEquals(listOf("mutation-0002", "mutation-0001"), edits.map { it.mutationId })
        assertEquals(ServerEditStatus.CONFLICT, edits.last().status)
    }

    // ---- conflict actions ----

    @Test
    fun useServerStateClosesTheConflictHidesTheEditAndRefreshes() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())
        f.journal.recordReceipt(conflictReceipt("mutation-0001"))
        val before = f.flushes

        assertTrue(f.provider.useServerState("mutation-0001"))

        assertEquals(JournalMutationState.RESOLVED, f.journal.mutation("mutation-0001")?.state)
        assertTrue(f.provider.edits.value.isEmpty())
        assertEquals(before + 1, f.flushes)
    }

    @Test
    fun useServerStateRefusesAnEditThatIsStillWaitingToBeSent() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())

        assertFalse(f.provider.useServerState("mutation-0001"))
        assertEquals(JournalMutationState.PENDING, f.journal.mutation("mutation-0001")?.state)
        assertFalse(f.provider.useServerState("unknown"))
    }

    @Test
    fun reapplyingTheDraftSendsTheSameChangeOnTheServersCurrentRevisionUnderANewId() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())
        f.journal.recordReceipt(conflictReceipt("mutation-0001", authoritative = "rev-remote"))
        val original = f.journal.mutation("mutation-0001")!!
        val before = f.flushes

        val result = f.provider.reapplyDraft("mutation-0001")

        val newId = (result as PreparationStageResult.Queued).mutationId
        assertTrue(newId != "mutation-0001")
        val again = f.journal.mutation(newId)!!
        assertEquals("rev-remote", again.baseRevision)
        assertEquals(original.canonicalPayload, again.canonicalPayload)
        assertEquals(original.entityId, again.entityId)
        assertEquals(JournalMutationState.PENDING, again.state)
        assertEquals(JournalMutationState.RESOLVED, f.journal.mutation("mutation-0001")?.state)
        assertEquals(listOf(newId), f.provider.edits.value.map { it.mutationId })
        assertEquals(before + 1, f.flushes)
    }

    @Test
    fun reapplyingTwiceDoesNotQueueTheChangeTwice() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())
        f.journal.recordReceipt(conflictReceipt("mutation-0001"))

        val first = f.provider.reapplyDraft("mutation-0001")
        val second = f.provider.reapplyDraft("mutation-0001")

        assertEquals(first, second)
        assertEquals(2, f.journal.all().size)
    }

    @Test
    fun reapplyingNeedsTheRevisionTheConflictNamedAndAConflict() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.stage(intent())
        val notConflicted = f.provider.reapplyDraft("mutation-0001")
        assertEquals("not_a_conflict", (notConflicted as PreparationStageResult.Rejected).code)

        f.journal.recordReceipt(conflictReceipt("mutation-0001", authoritative = null))
        val noRevision = f.provider.reapplyDraft("mutation-0001")
        assertEquals("no_server_revision", (noRevision as PreparationStageResult.Rejected).code)
        assertNotNull(f.journal.mutation("mutation-0001"))
        assertEquals(1, f.journal.all().size)
    }
}
