package dev.androidjtools.sync.journal

import dev.androidjtools.prep.cues.EditorMarkerDraft
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationMutationStager
import dev.androidjtools.prep.cues.PreparationStageResult
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns an editor intent into a durable `interval.editor_state.replace` journal entry.
 *
 * The journal write (with fsync) is blocking disk I/O: callers must not invoke this on the main thread.
 */
class JournalMutationStager(private val journal: DurableMutationJournal) : PreparationMutationStager {
    override fun stage(intent: IntervalEditorStateReplaceIntent): PreparationStageResult = try {
        journal.enqueue(
            mutationId = intent.mutationId,
            entityType = intent.entityType,
            entityId = intent.entityId,
            baseRevision = intent.baseRevision,
            operation = intent.operation,
            payloadJson = payloadOf(intent).toString(),
            provenanceJson = JsonObject(intent.provenance.mapValues { JsonPrimitive(it.value) }).toString(),
            createdAtEpochMillis = intent.createdAtEpochMillis,
        )
        PreparationStageResult.Queued(intent.mutationId)
    } catch (reuse: IdempotencyKeyReuseException) {
        PreparationStageResult.Rejected("idempotency_key_reused", reuse.message.orEmpty())
    } catch (invalid: IllegalArgumentException) {
        PreparationStageResult.Rejected("invalid_intent", invalid.message.orEmpty())
    } catch (_: IOException) {
        PreparationStageResult.Rejected("journal_unavailable", "The edit could not be saved on this device.")
    }

    private fun payloadOf(intent: IntervalEditorStateReplaceIntent): JsonObject {
        val payload = intent.payload
        return JsonObject(
            buildMap {
                put("start_ms", JsonPrimitive(payload.startMs))
                put("end_ms", JsonPrimitive(payload.endMs))
                put("markers", JsonArray(payload.markers.map(::markerOf)))
                payload.comment?.let { put("comment", JsonPrimitive(it)) }
            },
        )
    }

    private fun markerOf(marker: EditorMarkerDraft): JsonObject = JsonObject(
        buildMap {
            // A marker without an id is new; the server assigns its identity.
            marker.id?.let { put("id", JsonPrimitive(it)) }
            put("position_ms", JsonPrimitive(marker.positionMs))
            put("label", marker.label?.let(::JsonPrimitive) ?: JsonNull)
            put("marker_type", JsonPrimitive(serverMarkerType(marker.markerType)))
            put("color", wireColor(marker.color)?.let(::JsonPrimitive) ?: JsonNull)
        },
    )

    internal companion object {
        /**
         * The server stores `slice`, `loop_start`, `loop_end` and `custom` markers only. Editor-side roles beyond
         * cue/slice (mix points, drops, analysis kinds) are local in v1 and are persisted as plain `custom` markers.
         */
        fun serverMarkerType(editorType: String): String = when (editorType) {
            "slice", "loop_start", "loop_end" -> editorType
            else -> "custom"
        }

        /** The server and the library projection understand `#RRGGBB` only, so a leading alpha byte is dropped. */
        fun wireColor(color: String?): String? =
            color?.takeIf { it.length == 9 && it.startsWith("#") }?.let { "#" + it.substring(3) } ?: color
    }
}
