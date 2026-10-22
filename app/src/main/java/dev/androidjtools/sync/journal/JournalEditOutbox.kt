package dev.androidjtools.sync.journal

import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibSession
import dev.androidjtools.sync.samplelib.EditOutbox

/**
 * Replays the durable journal through the exact-idempotent replayer: the same immutable mutation id and body
 * are retried until the server answers with an authoritative receipt.
 */
class JournalEditOutbox(private val journal: DurableMutationJournal) : EditOutbox {
    override fun hasPending(): Boolean = journal.replayBatch(1).isNotEmpty()

    override suspend fun deliver(client: SampleLibClient, session: SampleLibSession) {
        try {
            SampleLibMutationJournalReplayer(journal, client).replay(session)
        } catch (_: SampleLibFailure.CapabilityUnavailable) {
            // The server no longer advertises an operation the queue needs. Keep every intent for a later
            // session, and do not leave one looking as if it were being sent.
            journal.all().filter { it.state == JournalMutationState.APPLYING }.forEach { journal.markPending(it.mutationId) }
        }
    }
}
