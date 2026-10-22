package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.ReceiptOutcome
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalConflict
import dev.androidjtools.sync.journal.JournalReceipt
import dev.androidjtools.sync.journal.JournalReceiptOutcome
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibJournalProviderTest {
    private class MemoryStorage : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            durable = snapshot
        }
    }

    private fun DurableMutationJournal.queue(id: String) = enqueue(
        mutationId = id,
        entityType = "interval",
        entityId = "iv-1",
        baseRevision = "rev-1",
        operation = "interval.editor_state.replace",
        payloadJson = """{"start_ms":0,"end_ms":5000,"markers":[]}""",
        provenanceJson = """{"surface":"cue_editor"}""",
        createdAtEpochMillis = 42_000L,
    )

    @Test
    fun onlyEditsNotYetAnsweredByTheServerArePending() = runTest(UnconfinedTestDispatcher()) {
        val journal = DurableMutationJournal(MemoryStorage())
        val provider = SampleLibJournalProvider(lazyOf(journal), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertTrue(provider.pending.value.isEmpty())
        assertEquals(0, provider.pendingCount.value)

        journal.queue("mutation-p01")
        journal.queue("mutation-p02")
        journal.markApplying("mutation-p02")
        assertEquals(listOf("mutation-p01", "mutation-p02"), provider.pending.value.map { it.mutationId })
        assertEquals(2, provider.pendingCount.value)
        val first = provider.pending.value.first()
        assertEquals("interval", first.entityType)
        assertEquals("iv-1", first.entityId)
        assertEquals("interval.editor_state.replace", first.operation)
        assertEquals(Instant.ofEpochMilli(42_000L), first.createdAt)

        journal.recordReceipt(JournalReceipt("mutation-p01", JournalReceiptOutcome.APPLIED, "rev-2"))
        journal.recordReceipt(JournalReceipt("mutation-p02", JournalReceiptOutcome.REJECTED, detail = "no"))
        assertTrue(provider.pending.value.isEmpty())
        assertEquals(0, provider.pendingCount.value)
    }

    @Test
    fun receiptsAreReportedWithTheirOutcomeAndReason() = runTest(UnconfinedTestDispatcher()) {
        val journal = DurableMutationJournal(MemoryStorage())
        val provider = SampleLibJournalProvider(lazyOf(journal), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        journal.queue("mutation-r01")
        journal.queue("mutation-r02")
        journal.queue("mutation-r03")
        journal.recordReceipt(JournalReceipt("mutation-r01", JournalReceiptOutcome.APPLIED, "rev-2"))
        journal.recordReceipt(JournalReceipt("mutation-r02", JournalReceiptOutcome.REJECTED, detail = "invalid_payload: x"))
        journal.recordReceipt(
            JournalReceipt("mutation-r03", JournalReceiptOutcome.CONFLICT, conflict = JournalConflict("stale", "editor_aggregate", "rev-9")),
        )

        val byId = provider.recentReceipts.value.associateBy { it.mutationId }
        assertEquals(ReceiptOutcome.APPLIED, byId.getValue("mutation-r01").outcome)
        assertEquals(ReceiptOutcome.REJECTED, byId.getValue("mutation-r02").outcome)
        assertEquals("invalid_payload: x", byId.getValue("mutation-r02").detail)
        assertEquals(ReceiptOutcome.CONFLICT, byId.getValue("mutation-r03").outcome)
        assertEquals(3, byId.size)
    }
}
