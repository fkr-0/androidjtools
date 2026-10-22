package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.core.provider.ServerEdit
import dev.androidjtools.core.provider.ServerEditProvider
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.IdempotencyKeyReuseException
import dev.androidjtools.sync.journal.JournalMutation
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalMutationStager
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val EDITOR_OPERATION = "interval.editor_state.replace"

/**
 * Cue and loop edits for a paired Sample Lib server, backed by the durable journal. Staging and the two conflict
 * actions write to disk, so they switch to [ioDispatcher]; [requestFlush] asks the session to deliver the queue.
 */
class SampleLibServerEditProvider(
    private val lazyJournal: Lazy<DurableMutationJournal>,
    private val projection: StateFlow<LibraryProjection>,
    capabilities: StateFlow<ServerCapabilities?>,
    syncState: StateFlow<SyncState>,
    private val requestFlush: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ServerEditProvider {
    private val journal: DurableMutationJournal by lazyJournal
    private val stager by lazy { JournalMutationStager(journal) }

    override val availability: StateFlow<EditAvailability> =
        combine(syncState, capabilities, ::availabilityOf)
            .stateIn(scope, SharingStarted.Eagerly, availabilityOf(syncState.value, capabilities.value))

    // The journal file is read the first time the list is collected, on the I/O dispatcher, never at construction.
    override val edits: StateFlow<List<ServerEdit>> =
        flow { emitAll(combine(journal.changes, projection) { _, library -> editsOf(library) }) }
            .flowOn(ioDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun aggregate(intervalId: String): IntervalEditorStateContext? = projection.value.aggregate(intervalId)

    override fun aggregates(trackId: String): List<IntervalEditorStateContext> = projection.value.aggregatesOfTrack(trackId)

    override suspend fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult {
        (availability.value as? EditAvailability.Unavailable)?.let {
            return PreparationStageResult.Rejected("unavailable", it.reason)
        }
        val result = withContext(ioDispatcher) { stager.stage(intent) }
        if (result is PreparationStageResult.Queued) requestFlush()
        return result
    }

    override suspend fun useServerState(mutationId: String): Boolean {
        val closed = withContext(ioDispatcher) {
            try {
                journal.resolve(mutationId)
                true
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IOException) {
                false
            }
        }
        // The cached library may predate the conflict: pull so the screen shows what the server has.
        if (closed) requestFlush()
        return closed
    }

    override suspend fun reapplyDraft(mutationId: String): PreparationStageResult {
        val result = withContext(ioDispatcher) {
            try {
                reapply(mutationId)
            } catch (_: IdempotencyKeyReuseException) {
                PreparationStageResult.Rejected("idempotency_key_reused", "This change was already queued differently.")
            } catch (_: IOException) {
                PreparationStageResult.Rejected("journal_unavailable", "The edit could not be saved on this device.")
            }
        }
        if (result is PreparationStageResult.Queued) requestFlush()
        return result
    }

    /** The new id is derived from the old one, so repeating the action after a crash finds the earlier copy. */
    private fun reapply(mutationId: String): PreparationStageResult {
        val old = journal.mutation(mutationId)
            ?: return PreparationStageResult.Rejected("unknown_edit", "This edit is no longer known.")
        val newId = "$mutationId:again"
        if (journal.mutation(newId) != null) {
            if (old.state == JournalMutationState.CONFLICT) journal.resolve(mutationId)
            return PreparationStageResult.Queued(newId)
        }
        if (old.state != JournalMutationState.CONFLICT) {
            return PreparationStageResult.Rejected("not_a_conflict", "Only a conflicted edit can be sent again.")
        }
        val serverRevision = old.receipt?.conflict?.authoritativeRevision
            ?: return PreparationStageResult.Rejected("no_server_revision", "The server did not say which version to build on.")
        journal.enqueue(
            mutationId = newId,
            entityType = old.entityType,
            entityId = old.entityId,
            baseRevision = serverRevision,
            operation = old.operation,
            payloadJson = old.canonicalPayload,
            provenanceJson = provenanceWithOrigin(old, mutationId),
            createdAtEpochMillis = clock(),
        )
        journal.resolve(mutationId)
        return PreparationStageResult.Queued(newId)
    }

    private fun provenanceWithOrigin(old: JournalMutation, originId: String): String {
        val existing = Json.parseToJsonElement(old.canonicalProvenance) as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(existing + ("reapplied_from" to JsonPrimitive(originId))).toString()
    }

    private fun editsOf(library: LibraryProjection): List<ServerEdit> = journal.all()
        .filter { it.operation == EDITOR_OPERATION && it.state != JournalMutationState.RESOLVED }
        .sortedByDescending { it.sequence }
        .map { mutation ->
            val status = statusOf(mutation.state)
            ServerEdit(
                mutationId = mutation.mutationId,
                intervalId = mutation.entityId,
                trackId = library.trackOfInterval(mutation.entityId),
                summary = summaryOf(mutation),
                status = status,
                detail = detailOf(status, mutation),
                createdAtEpochMillis = mutation.createdAtEpochMillis,
            )
        }

    private fun statusOf(state: JournalMutationState): ServerEditStatus = when (state) {
        JournalMutationState.PENDING -> ServerEditStatus.PENDING
        JournalMutationState.APPLYING -> ServerEditStatus.SENDING
        JournalMutationState.RECEIPT_RECORDED -> ServerEditStatus.SAVED
        JournalMutationState.REJECTED -> ServerEditStatus.REJECTED
        JournalMutationState.CONFLICT -> ServerEditStatus.CONFLICT
        JournalMutationState.RESOLVED -> error("resolved edits are not listed")
    }

    private fun summaryOf(mutation: JournalMutation): String {
        val surface = (Json.parseToJsonElement(mutation.canonicalProvenance) as? JsonObject)
            ?.get("surface")?.let { (it as? JsonPrimitive)?.content }
        return when (surface) {
            "cue_editor" -> "Cue edit"
            "loop_editor" -> "Loop edit"
            else -> "Edit"
        }
    }

    private fun detailOf(status: ServerEditStatus, mutation: JournalMutation): String = when (status) {
        ServerEditStatus.PENDING -> "Waiting to be sent to the server."
        ServerEditStatus.SENDING -> "Sending to the server…"
        ServerEditStatus.SAVED -> "Saved on the server."
        ServerEditStatus.CONFLICT -> "The server has a newer version of this part of the track."
        ServerEditStatus.REJECTED -> mutation.receipt?.detail ?: "The server could not accept this edit."
    }

    private fun availabilityOf(state: SyncState, capabilities: ServerCapabilities?): EditAvailability = when {
        state == SyncState.UNCONFIGURED -> EditAvailability.Unavailable("Connect to a Sample Lib server to save edits to it.")
        state == SyncState.AUTH_REQUIRED -> EditAvailability.Unavailable("Pair again to save edits to the server.")
        state == SyncState.SERVER_INCOMPATIBLE -> EditAvailability.Unavailable("This server does not support saving edits from this app version.")
        capabilities != null && EDITOR_OPERATION !in capabilities.mutations ->
            EditAvailability.Unavailable("This server does not support saving cue and loop edits.")
        else -> EditAvailability.Available
    }
}
