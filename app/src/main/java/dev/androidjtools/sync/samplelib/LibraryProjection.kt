package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.Track
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class LibraryProjection(
    val tracks: List<Track>,
    val cues: Map<String, List<Cue>>,
    val loops: Map<String, List<Loop>>,
) {
    private val trackIds: Set<String> by lazy { tracks.mapTo(HashSet()) { it.id } }

    fun hasTrack(id: String): Boolean = id in trackIds

    companion object {
        val EMPTY = LibraryProjection(emptyList(), emptyMap(), emptyMap())
    }
}

object LibraryProjector {
    fun project(assets: List<CachedEntity>, intervals: List<CachedEntity>): LibraryProjection {
        val parsedIntervals = intervals.mapNotNull(::parseInterval)
        val byAsset = parsedIntervals.groupBy { it.assetId }

        val tracks = assets.mapNotNull { entity ->
            val value = parseObject(entity.valueJson) ?: return@mapNotNull null
            val duration = value.long("duration_ms") ?: 0L
            val fullSpan = byAsset[entity.entityId].orEmpty()
                .firstOrNull { duration > 0 && it.startMs == 0L && it.endMs >= duration }
            Track(
                id = entity.entityId,
                title = value.string("title") ?: value.string("original_filename") ?: entity.entityId,
                artist = value.string("artist") ?: "Unknown artist",
                album = value.string("collection"),
                durationMs = duration,
                bpm = fullSpan?.bpm,
                key = fullSpan?.key,
                rating = value.long("rating")?.toInt(),
            )
        }
        return LibraryProjection(
            tracks = tracks,
            cues = parsedIntervals.flatMap { it.cues }.groupBy { it.trackId },
            loops = parsedIntervals.flatMap { it.loops }.groupBy { it.trackId },
        )
    }

    private class ParsedInterval(
        val assetId: String,
        val startMs: Long,
        val endMs: Long,
        val bpm: Double?,
        val key: String?,
        val cues: List<Cue>,
        val loops: List<Loop>,
    )

    private class ParsedMarker(val id: String, val positionMs: Long, val type: String, val label: String?, val color: String?)

    private fun parseInterval(entity: CachedEntity): ParsedInterval? {
        val root = parseObject(entity.valueJson) ?: return null
        val interval = root["interval"] as? JsonObject ?: return null
        val assetId = interval.string("asset_id") ?: return null
        val start = interval.long("start_ms") ?: return null
        val end = interval.long("end_ms") ?: return null

        val markers = ((root["markers"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { element ->
            val marker = element as? JsonObject ?: return@mapNotNull null
            ParsedMarker(
                id = marker.string("id") ?: return@mapNotNull null,
                positionMs = marker.long("position_ms") ?: return@mapNotNull null,
                type = marker.string("marker_type") ?: "custom",
                label = marker.string("label"),
                color = marker.string("color"),
            )
        }

        val cues = markers.mapNotNull { marker ->
            val role = when (marker.type) {
                "slice" -> "slice"
                "custom" -> "cue"
                else -> return@mapNotNull null
            }
            Cue(id = marker.id, trackId = assetId, positionMs = marker.positionMs, role = role, label = marker.label, colorArgb = parseColor(marker.color))
        }

        val loops = mutableListOf<Loop>()
        if (interval.long("loopable") == 1L) {
            loops += Loop(id = entity.entityId, trackId = assetId, startMs = start, endMs = end, role = "loop", label = interval.string("label"))
        }
        var openStart: ParsedMarker? = null
        for (marker in markers.sortedBy { it.positionMs }) {
            when (marker.type) {
                "loop_start" -> openStart = marker
                "loop_end" -> {
                    val opened = openStart
                    if (opened != null && marker.positionMs > opened.positionMs) {
                        loops += Loop(
                            id = "${opened.id}~${marker.id}",
                            trackId = assetId,
                            startMs = opened.positionMs,
                            endMs = marker.positionMs,
                            role = "loop",
                            label = opened.label,
                        )
                        openStart = null
                    }
                }
            }
        }

        return ParsedInterval(assetId, start, end, interval.double("bpm"), interval.string("key_label"), cues, loops)
    }

    private val colour = Regex("^#([0-9a-fA-F]{6})$")

    private fun parseColor(value: String?): Long? =
        value?.let { colour.matchEntire(it) }?.groupValues?.get(1)?.toLong(16)?.let { 0xFF000000L or it }

    private fun parseObject(json: String): JsonObject? =
        try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}
