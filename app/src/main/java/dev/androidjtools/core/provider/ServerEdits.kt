package dev.androidjtools.core.provider

import dev.androidjtools.prep.cues.IntervalEditorStateContext
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationStageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a cue or loop edit stands on its way to the server. A draft is the screen's own state, before any of these. */
enum class ServerEditStatus { PENDING, SENDING, SAVED, CONFLICT, REJECTED }

data class ServerEdit(
    val mutationId: String,
    val intervalId: String,
    val trackId: String?,
    val summary: String,
    val status: ServerEditStatus,
    val detail: String,
    val createdAtEpochMillis: Long,
)

sealed interface EditAvailability {
    data object Available : EditAvailability
    data class Unavailable(val reason: String) : EditAvailability
}

/** Saving cue and loop edits to the paired Sample Lib server. Every call that touches disk is a suspend function. */
interface ServerEditProvider {
    val availability: StateFlow<EditAvailability>

    /** Newest first. An edit is `SAVED` only once the server has acknowledged it; until then it is never shown as done. */
    val edits: StateFlow<List<ServerEdit>>

    /** The aggregate an edit of this interval starts from, at the revision the server last sent. */
    fun aggregate(intervalId: String): IntervalEditorStateContext?

    /** Every editable interval of a track, ordered by start. */
    fun aggregates(trackId: String): List<IntervalEditorStateContext>

    suspend fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult

    /** Conflict action 1: drop the local intent and show what the server has. False when the edit is not closable. */
    suspend fun useServerState(mutationId: String): Boolean

    /** Conflict action 2: send the same change again as a new mutation on the server's current revision. */
    suspend fun reapplyDraft(mutationId: String): PreparationStageResult
}

/** Used when no server is paired: nothing can be saved. */
object NoServerEdits : ServerEditProvider {
    override val availability: StateFlow<EditAvailability> =
        MutableStateFlow<EditAvailability>(EditAvailability.Unavailable("Connect to a Sample Lib server to save edits to it.")).asStateFlow()
    override val edits: StateFlow<List<ServerEdit>> = MutableStateFlow(emptyList<ServerEdit>()).asStateFlow()
    override fun aggregate(intervalId: String): IntervalEditorStateContext? = null
    override fun aggregates(trackId: String): List<IntervalEditorStateContext> = emptyList()
    override suspend fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult =
        PreparationStageResult.Rejected("unavailable", (availability.value as EditAvailability.Unavailable).reason)
    override suspend fun useServerState(mutationId: String): Boolean = false
    override suspend fun reapplyDraft(mutationId: String): PreparationStageResult =
        PreparationStageResult.Rejected("unavailable", (availability.value as EditAvailability.Unavailable).reason)
}
