package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class LibraryProjection(
    val tracks: List<Track>,
    val cues: Map<String, List<Cue>>,
    val loops: Map<String, List<Loop>>,
    /** Audio files by track (asset) id, whether or not the server currently advertises streaming. */
    val audio: Map<String, ServerAudio> = emptyMap(),
    /** True when the server advertises audio, so [audio] may be streamed. A downloaded copy never needs it. */
    val audioAdvertised: Boolean = false,
) {
    private val trackIds: Set<String> by lazy { tracks.mapTo(HashSet()) { it.id } }

    fun hasTrack(id: String): Boolean = id in trackIds

    companion object {
        val EMPTY = LibraryProjection(emptyList(), emptyMap(), emptyMap())
    }
}

object LibraryProjector {
    fun project(
        assets: List<CachedEntity>,
        intervals: List<CachedEntity>,
        assetFiles: List<CachedEntity> = emptyList(),
        audioAdvertised: Boolean = false,
        downloaded: Map<String, String> = emptyMap(),
        /** Shown instead of the generic notice when streaming is withheld for a known reason (for example a rejected credential). */
        audioBlockedReason: String? = null,
    ): LibraryProjection {
        val parsedIntervals = intervals.mapNotNull(::parseInterval)
        val byAsset = parsedIntervals.groupBy { it.assetId }
        val audio = AssetFiles.audioByAsset(assetFiles)

        val tracks = assets.mapNotNull { entity ->
            val value = parseJsonObject(entity.valueJson) ?: return@mapNotNull null
            val duration = value.long("duration_ms") ?: 0L
            val fullSpan = byAsset[entity.entityId].orEmpty()
                .firstOrNull { duration > 0 && it.startMs == 0L && it.endMs >= duration }
            val files = audio[entity.entityId]
            val offline = files != null && downloaded[files.download.id] == files.download.sha256
            val playable = files != null && (audioAdvertised || offline)
            Track(
                id = entity.entityId,
                title = value.string("title") ?: value.string("original_filename") ?: entity.entityId,
                artist = value.string("artist") ?: "Unknown artist",
                album = value.string("collection"),
                durationMs = duration,
                bpm = fullSpan?.bpm,
                key = fullSpan?.key,
                rating = value.long("rating")?.toInt(),
                offlineAvailable = offline,
                playable = playable,
                unplayableReason = when {
                    playable -> null
                    audioAdvertised && files == null -> NO_SERVER_AUDIO_NOTICE
                    else -> audioBlockedReason ?: SERVER_AUDIO_UNAVAILABLE_NOTICE
                },
                deckBlockedReason = if (playable && !offline) DECK_NEEDS_DOWNLOAD_NOTICE else null,
            )
        }
        val trackIds = tracks.mapTo(HashSet()) { it.id }
        return LibraryProjection(
            tracks = tracks,
            cues = parsedIntervals.flatMap { it.cues }.groupBy { it.trackId },
            loops = parsedIntervals.flatMap { it.loops }.groupBy { it.trackId },
            audio = audio.filterKeys { it in trackIds },
            audioAdvertised = audioAdvertised,
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
        val root = parseJsonObject(entity.valueJson) ?: return null
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
}
