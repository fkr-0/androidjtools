package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.MutationReceipt
import dev.androidjtools.core.model.PendingMutation
import dev.androidjtools.core.model.ReceiptOutcome
import dev.androidjtools.core.provider.MutationJournalProvider
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalMutation
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalReceiptOutcome
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val RECENT_RECEIPTS_LIMIT = 20

/** What the journal still owes the server, and what the server last answered. Read lazily, off the main thread. */
class SampleLibJournalProvider(
    private val lazyJournal: Lazy<DurableMutationJournal>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : MutationJournalProvider {
    private val journal: DurableMutationJournal by lazyJournal

    // Opaque revisions are not numbers, so the domain's numeric baseRevision stays unset.
    override val pending: StateFlow<List<PendingMutation>> = observe(scope, ioDispatcher) {
        journal.all()
            .filter { it.state == JournalMutationState.PENDING || it.state == JournalMutationState.APPLYING }
            .sortedBy { it.sequence }
            .map {
                PendingMutation(
                    mutationId = it.mutationId,
                    entityType = it.entityType,
                    entityId = it.entityId,
                    operation = it.operation,
                    createdAt = Instant.ofEpochMilli(it.createdAtEpochMillis),
                )
            }
    }

    override val recentReceipts: StateFlow<List<MutationReceipt>> = observe(scope, ioDispatcher) {
        journal.all()
            .filter { it.receipt != null }
            .sortedByDescending(JournalMutation::sequence)
            .take(RECENT_RECEIPTS_LIMIT)
            .mapNotNull { mutation ->
                val receipt = mutation.receipt ?: return@mapNotNull null
                MutationReceipt(
                    mutationId = mutation.mutationId,
                    outcome = when (receipt.outcome) {
                        JournalReceiptOutcome.APPLIED -> ReceiptOutcome.APPLIED
                        JournalReceiptOutcome.NO_OP -> ReceiptOutcome.NO_OP
                        JournalReceiptOutcome.REJECTED -> ReceiptOutcome.REJECTED
                        JournalReceiptOutcome.CONFLICT -> ReceiptOutcome.CONFLICT
                    },
                    detail = receipt.detail,
                )
            }
    }

    val pendingCount: StateFlow<Int> = pending.map { it.size }.stateIn(scope, SharingStarted.Eagerly, 0)

    private fun <T> observe(scope: CoroutineScope, io: CoroutineDispatcher, read: () -> List<T>): StateFlow<List<T>> =
        flow { emitAll(journal.changes.map { read() }) }
            .flowOn(io)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
