package dev.androidjtools.sync.journal

import dev.androidjtools.sync.samplelib.ANALYSIS_ACCEPT_OPERATION
import dev.androidjtools.sync.samplelib.ANALYSIS_SUGGESTION_ENTITY
import dev.androidjtools.sync.samplelib.AnalysisSuggestionRecord
import dev.androidjtools.sync.samplelib.SuggestionData
import dev.androidjtools.sync.samplelib.SuggestionWireKind
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns an explicit "accept" of a tempo, beat-grid or key suggestion into a durable `analysis_suggestion.accept`
 * journal entry, the second operation family next to the editor-state replacement. Writing the journal is blocking
 * disk I/O: callers keep it off the main thread. Cue candidates never come through here; they become cues through
 * the editor-state path.
 */
class AnalysisSuggestionAcceptanceCoordinator(private val journal: DurableMutationJournal) {
    /**
     * [bpm] is a tempo's chosen alternative. The suggestion's own bpm needs no choice and is not sent, so the server
     * applies the value it holds itself.
     */
    fun queueAccept(
        record: AnalysisSuggestionRecord,
        targetIntervalId: String,
        bpm: Double?,
        createdAtEpochMillis: Long,
    ): JournalMutation {
        require(record.kind != SuggestionWireKind.CUE_CANDIDATES) { "cue candidates are applied as cues, not accepted" }
        require(targetIntervalId.isNotBlank()) { "an accept needs the interval it applies to" }
        val choice = chosenAlternative(record, bpm)
        val mutationId = mutationIdFor(record, targetIntervalId, choice)
        // A repeated tap is the same intent; a new timestamp must not turn it into a different body.
        journal.mutation(mutationId)?.let { return it }
        return journal.enqueue(
            mutationId = mutationId,
            entityType = ANALYSIS_SUGGESTION_ENTITY,
            entityId = record.id,
            baseRevision = record.revision,
            operation = ANALYSIS_ACCEPT_OPERATION,
            payloadJson = payloadOf(targetIntervalId, choice).toString(),
            provenanceJson = provenanceOf(record).toString(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    /** The newest acceptance of a suggestion that has not been closed, whatever revision it was made against. */
    fun latestFor(suggestionId: String): JournalMutation? = journal.all()
        .filter { it.operation == ANALYSIS_ACCEPT_OPERATION && it.entityId == suggestionId && it.state != JournalMutationState.RESOLVED }
        .maxByOrNull { it.sequence }

    private fun chosenAlternative(record: AnalysisSuggestionRecord, bpm: Double?): Double? {
        if (bpm == null) return null
        val tempo = record.data as? SuggestionData.Tempo
        require(tempo != null) { "only a tempo suggestion has a bpm to choose" }
        if (close(bpm, tempo.bpm)) return null
        require(tempo.alternatives.any { close(bpm, it) }) { "bpm must be one of the suggestion's alternatives" }
        return bpm
    }

    private fun close(a: Double, b: Double) = abs(a - b) < 1e-9

    private fun payloadOf(targetIntervalId: String, bpm: Double?): JsonObject = buildJsonObject {
        put("target_interval_id", targetIntervalId)
        bpm?.let { put("bpm", JsonPrimitive(it)) }
    }

    private fun provenanceOf(record: AnalysisSuggestionRecord): JsonObject = buildJsonObject {
        put("source", "analysis_suggestion")
        put("surface", "analysis")
        put("analysis_run_id", record.analysisRunId)
        put("kind", record.kind.wire)
        put("tool", record.tool)
        record.toolVersion?.let { put("tool_version", it) }
    }

    companion object {
        /** Deterministic: the same acceptance of the same suggestion revision is the same mutation, so a retry cannot double up. */
        fun mutationIdFor(record: AnalysisSuggestionRecord, targetIntervalId: String, chosenBpm: Double?): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${record.id}|${record.revision}|$targetIntervalId|${chosenBpm ?: "-"}".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return "analysis-accept-${digest.take(24)}"
        }
    }
}
