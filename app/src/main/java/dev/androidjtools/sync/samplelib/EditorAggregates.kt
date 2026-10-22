package dev.androidjtools.sync.samplelib

import dev.androidjtools.prep.cues.EditorMarkerDraft
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Builds the editable aggregate of a synced interval from its cached entity. */
object EditorAggregates {
    /**
     * The aggregate an `interval.editor_state.replace` must start from, or null when the entity cannot be edited
     * safely. [IntervalEditorStateContext.completeMarkerSet] is true only when the server sent a marker list and
     * every marker in it could be read: a replacement built from a partial list would delete the unread markers.
     */
    fun contextOf(entity: CachedEntity): IntervalEditorStateContext? {
        if (entity.revision.isBlank()) return null
        val root = parseJsonObject(entity.valueJson) ?: return null
        val interval = root["interval"] as? JsonObject ?: return null
        val start = interval.long("start_ms") ?: return null
        val end = interval.long("end_ms") ?: return null
        if (start < 0L || end <= start) return null

        val rawMarkers = root["markers"] as? JsonArray
        val markers = rawMarkers.orEmpty().mapNotNull { element ->
            val marker = element as? JsonObject ?: return@mapNotNull null
            EditorMarkerDraft(
                id = marker.string("id") ?: return@mapNotNull null,
                positionMs = marker.long("position_ms") ?: return@mapNotNull null,
                label = marker.string("label"),
                markerType = marker.string("marker_type") ?: return@mapNotNull null,
                color = marker.string("color"),
            )
        }
        return IntervalEditorStateContext(
            intervalId = entity.entityId,
            baseRevision = entity.revision,
            startMs = start,
            endMs = end,
            markers = markers,
            completeMarkerSet = rawMarkers != null && markers.size == rawMarkers.size,
        )
    }
}
