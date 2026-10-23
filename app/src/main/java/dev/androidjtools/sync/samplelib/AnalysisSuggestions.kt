package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.ServerCapabilities
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

const val ANALYSIS_SUGGESTION_ENTITY = "analysis_suggestion"
const val ANALYSIS_SUGGESTION_SCHEMA = "sample-lib.analysis-suggestion/v1"
const val ANALYSIS_ACCEPT_OPERATION = "analysis_suggestion.accept"

/** The pull scope that carries [ANALYSIS_SUGGESTION_ENTITY] entities. It is separate from `library`. */
const val ANALYSIS_PULL_SCOPE = "analysis"

/** The kinds this app understands. The server's vocabulary is open: anything else is ignored, never an error. */
enum class SuggestionWireKind(val wire: String) {
    TEMPO("tempo"),
    KEY("key"),
    BEAT_GRID("beat_grid"),
    CUE_CANDIDATES("cue_candidates"),
    ;

    companion object {
        fun fromWire(word: String?): SuggestionWireKind? = entries.firstOrNull { it.wire == word }
    }
}

sealed interface SuggestionData {
    /** [alternatives] are the half/double-time aliases; the server does not choose between them, the person does. */
    data class Tempo(val bpm: Double, val alternatives: List<Double>) : SuggestionData
    data class Key(val label: String, val root: String?, val mode: String?) : SuggestionData
    data class BeatGrid(val bpm: Double, val firstBeatMs: Long, val beatTimesMs: List<Long>) : SuggestionData
    data class CueCandidates(val pointsMs: List<Long>) : SuggestionData
}

/**
 * One persisted analysis result as the server presents it. Advisory only: it never changes the library. [revision]
 * is the pulled revision an accept must name as its base.
 */
data class AnalysisSuggestionRecord(
    val id: String,
    val revision: String,
    val kind: SuggestionWireKind,
    val assetId: String,
    /** The interval the run was scoped to, or null for an asset-level run. */
    val intervalId: String?,
    val analysisRunId: String,
    val tool: String,
    val toolVersion: String?,
    val createdAt: Instant?,
    /** Whatever the producing run recorded, else null. Informational: nothing here ever depends on it being present. */
    val confidence: Double?,
    val data: SuggestionData,
)

/**
 * The scopes one pull asks for. `analysis` is added only when the server advertises at least one analysis kind and
 * the credential is not known to lack `analysis.read`; it is refused-and-remembered by the caller when it turns out
 * not to be granted. A server without analysis is pulled exactly as it always was.
 */
fun pullScopesFor(capabilities: ServerCapabilities, grantedScopes: Set<String>?, analysisDenied: Boolean = false): Set<String> {
    val wanted = capabilities.analysis.isNotEmpty() && !analysisDenied && (grantedScopes == null || "analysis.read" in grantedScopes)
    return if (wanted) setOf("library", ANALYSIS_PULL_SCOPE) else setOf("library")
}

object AnalysisSuggestions {
    private const val MAX_BEATS = 512
    private const val MAX_CUES = 256
    private const val MAX_BPM = 1000.0

    /** Null for anything this app cannot use: another schema, an unknown kind, or values that are not valid. Never throws. */
    fun parse(entity: CachedEntity): AnalysisSuggestionRecord? {
        if (entity.entityType != ANALYSIS_SUGGESTION_ENTITY || entity.schema != ANALYSIS_SUGGESTION_SCHEMA) return null
        if (entity.entityId.isBlank() || entity.revision.isBlank()) return null
        val root = parseJsonObject(entity.valueJson) ?: return null
        val kind = SuggestionWireKind.fromWire(root.string("kind")) ?: return null
        val assetId = root.string("asset_id")?.takeIf { it.isNotBlank() } ?: return null
        val runId = root.string("analysis_run_id")?.takeIf { it.isNotBlank() } ?: return null
        val data = parseData(kind, root["data"] as? JsonObject ?: return null) ?: return null
        return AnalysisSuggestionRecord(
            id = entity.entityId,
            revision = entity.revision,
            kind = kind,
            assetId = assetId,
            intervalId = root.string("interval_id")?.takeIf { it.isNotBlank() },
            analysisRunId = runId,
            tool = root.string("tool")?.takeIf { it.isNotBlank() } ?: "analysis",
            toolVersion = root.string("tool_version")?.takeIf { it.isNotBlank() },
            createdAt = root.string("created_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            confidence = root.double("confidence")?.takeIf { it.isFinite() && it in 0.0..1.0 },
            data = data,
        )
    }

    /**
     * Records per track, newest run first. Confidence takes no part in the order, and nothing is dropped for lacking
     * it. Records for assets the library does not hold are left out.
     */
    fun project(entities: List<CachedEntity>, trackIds: Set<String>): Map<String, List<AnalysisSuggestionRecord>> =
        entities.mapNotNull(::parse)
            .filter { it.assetId in trackIds }
            .groupBy { it.assetId }
            .mapValues { (_, records) ->
                records.sortedWith(
                    compareByDescending<AnalysisSuggestionRecord> { it.createdAt ?: Instant.MIN }.thenBy { it.id },
                )
            }

    private fun parseData(kind: SuggestionWireKind, data: JsonObject): SuggestionData? = when (kind) {
        SuggestionWireKind.TEMPO -> bpmOf(data)?.let { bpm ->
            SuggestionData.Tempo(bpm, numbers(data["alternatives"]).filter(::validBpm))
        }
        SuggestionWireKind.KEY -> data.string("key_label")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SuggestionData.Key(it, data.string("root")?.takeIf(String::isNotBlank), data.string("mode")?.takeIf(String::isNotBlank))
        }
        SuggestionWireKind.BEAT_GRID -> {
            val bpm = bpmOf(data)
            val first = data.long("first_beat_ms")?.takeIf { it >= 0L }
            if (bpm == null || first == null) {
                null
            } else {
                SuggestionData.BeatGrid(bpm, first, longs(data["beat_times_ms"]).take(MAX_BEATS))
            }
        }
        SuggestionWireKind.CUE_CANDIDATES ->
            longs(data["points_ms"]).distinct().sorted().take(MAX_CUES).takeIf { it.isNotEmpty() }?.let(SuggestionData::CueCandidates)
    }

    private fun validBpm(value: Double) = value.isFinite() && value > 0.0 && value <= MAX_BPM

    private fun bpmOf(data: JsonObject): Double? = data.double("bpm")?.takeIf(::validBpm)

    private fun numbers(element: Any?): List<Double> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull }

    private fun longs(element: Any?): List<Long> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull }
            .filter { it >= 0L }
}
