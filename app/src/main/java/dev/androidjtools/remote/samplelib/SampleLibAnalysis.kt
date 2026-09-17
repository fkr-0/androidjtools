package dev.androidjtools.remote.samplelib

import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Portable Android boundary for one advisory Sample Lib analysis candidate. */
data class SampleLibAnalysisCandidate(
    val candidateId: String,
    val analysisRunId: String,
    val assetId: String,
    val intervalId: String? = null,
    val kind: AnalysisCandidateKind,
    val value: AnalysisCandidateValue,
    val confidence: Double? = null,
    val source: AnalysisCandidateSource,
    val generatedAt: Instant,
    val inputIdentity: AnalysisInputIdentity,
    val freshness: AnalysisCandidateFreshness,
    val decision: AnalysisCandidateDecision,
) {
    /**
     * The existing presentation model still has a legacy numeric inputRevision field. Do not
     * truncate or hash an opaque authority revision into it. The exact identity remains on this
     * envelope and is consumed by AnalysisSuggestionAcceptanceCoordinator.
     */
    fun toPresentationSuggestion(): AnalysisSuggestion = AnalysisSuggestion(
        id = candidateId,
        trackId = assetId,
        kind = kind.presentationKind,
        payload = value.toPresentationPayload(),
        source = IntelligenceSource(
            service = source.service,
            model = source.model,
            version = source.version,
            pipelineVersion = source.pipelineVersion,
        ),
        confidence = confidence,
        generatedAt = generatedAt,
        inputRevision = null,
        decision = decision.presentationDecision,
        // Unknown freshness is intentionally fail-closed in the presentation as well as acceptance.
        stale = freshness != AnalysisCandidateFreshness.FRESH,
    )
}

data class AnalysisInputIdentity(
    val entityType: String,
    val entityId: String,
    val revision: OpaqueRevision,
    val contentSha256: String? = null,
)

data class AnalysisCandidateSource(
    val service: String,
    val model: String? = null,
    val version: String? = null,
    val pipelineVersion: String? = null,
)

enum class AnalysisCandidateFreshness { FRESH, STALE, UNKNOWN }
enum class AnalysisCandidateDecision { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED }

enum class AnalysisCandidateKind(val wireName: String, val presentationKind: SuggestionKind) {
    BPM("bpm", SuggestionKind.BPM),
    KEY("key", SuggestionKind.KEY),
    CUE("cue", SuggestionKind.CUE),
    LOOP("loop", SuggestionKind.LOOP),
    REGION("region", SuggestionKind.REGION),
    RELATED_TRACK("related_track", SuggestionKind.RELATED_TRACK),
}

sealed interface AnalysisCandidateValue {
    data class Bpm(val value: Double) : AnalysisCandidateValue
    data class Key(val value: String) : AnalysisCandidateValue
    data class Point(val positionMs: Long, val role: String, val label: String? = null) : AnalysisCandidateValue
    data class Range(val startMs: Long, val endMs: Long, val role: String, val label: String? = null) : AnalysisCandidateValue
    data class RelatedTrack(val trackId: String, val dimensions: List<String>) : AnalysisCandidateValue

    fun toPresentationPayload(): SuggestionPayload = when (this) {
        is Bpm -> SuggestionPayload.Bpm(value)
        is Key -> SuggestionPayload.Key(value)
        is Point -> SuggestionPayload.Point(positionMs, role, label)
        is Range -> SuggestionPayload.Range(startMs, endMs, role, label)
        is RelatedTrack -> SuggestionPayload.Related(trackId, dimensions)
    }

    fun toWireElement(): JsonElement = when (this) {
        is Bpm -> JsonPrimitive(value)
        is Key -> JsonPrimitive(value)
        is Point -> JsonObject(
            buildMap {
                put("position_ms", JsonPrimitive(positionMs))
                put("role", JsonPrimitive(role))
                label?.let { put("label", JsonPrimitive(it)) }
            }
        )
        is Range -> JsonObject(
            buildMap {
                put("start_ms", JsonPrimitive(startMs))
                put("end_ms", JsonPrimitive(endMs))
                put("role", JsonPrimitive(role))
                label?.let { put("label", JsonPrimitive(it)) }
            }
        )
        is RelatedTrack -> JsonObject(
            mapOf(
                "track_id" to JsonPrimitive(trackId),
                "dimensions" to JsonArray(dimensions.map(::JsonPrimitive)),
            )
        )
    }
}

data class AnalysisCandidatePage(
    val protocolVersion: String = "1",
    val nextCursor: String,
    val hasMore: Boolean,
    val authorityGeneration: String,
    val candidates: List<SampleLibAnalysisCandidate>,
)

suspend fun SampleLibClient.pullAnalysisCandidates(
    session: SampleLibSession,
    cursor: String? = null,
    limit: Int = session.limits.pullPageMax,
): AnalysisCandidatePage {
    if ("suggestions.read" !in session.capabilities.analysis) {
        throw SampleLibFailure.CapabilityUnavailable("analysis.suggestions.read")
    }
    if ("analysis_suggestion" !in session.capabilities.pull) {
        throw SampleLibFailure.CapabilityUnavailable("pull.analysis_suggestion")
    }
    val page = pull(session, cursor = cursor, scopes = setOf("analysis_suggestion"), limit = limit)
    return AnalysisCandidatePage(
        nextCursor = page.nextCursor,
        hasMore = page.hasMore,
        authorityGeneration = page.authorityGeneration,
        candidates = page.changes.mapNotNull(::decodeAnalysisCandidate),
    )
}

internal fun decodeAnalysisCandidate(change: EntityChange): SampleLibAnalysisCandidate? {
    if (change.entityType != "analysis_suggestion") return null
    if (change.schema != "androidjtools.analysis-candidate/v1") return null
    val obj = change.value
    val candidateId = obj.requiredString("candidate_id")
    require(candidateId == change.entityId) { "analysis candidate identity does not match entity_id" }
    val kind = when (obj.requiredString("kind")) {
        "bpm" -> AnalysisCandidateKind.BPM
        "key" -> AnalysisCandidateKind.KEY
        "cue" -> AnalysisCandidateKind.CUE
        "loop" -> AnalysisCandidateKind.LOOP
        "region" -> AnalysisCandidateKind.REGION
        "related_track" -> AnalysisCandidateKind.RELATED_TRACK
        else -> return null // Unknown or contract-external candidate families degrade independently.
    }
    val value = when (kind) {
        AnalysisCandidateKind.BPM -> AnalysisCandidateValue.Bpm(
            obj["value"]?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it in 20.0..400.0 }
                ?: throw SampleLibFailure.InvalidResponse("Invalid BPM analysis candidate value")
        )
        AnalysisCandidateKind.KEY -> AnalysisCandidateValue.Key(
            obj.requiredString("value").takeIf { it.length <= 64 }
                ?: throw SampleLibFailure.InvalidResponse("Invalid key analysis candidate value")
        )
        AnalysisCandidateKind.CUE -> {
            val valueObj = obj["value"] as? JsonObject
                ?: throw SampleLibFailure.InvalidResponse("Cue candidate value must be an object")
            AnalysisCandidateValue.Point(
                positionMs = valueObj.requiredNonNegativeLong("position_ms"),
                role = valueObj.requiredString("role"),
                label = valueObj.optionalString("label"),
            )
        }
        AnalysisCandidateKind.LOOP, AnalysisCandidateKind.REGION -> {
            val valueObj = obj["value"] as? JsonObject
                ?: throw SampleLibFailure.InvalidResponse("Range candidate value must be an object")
            val startMs = valueObj.requiredNonNegativeLong("start_ms")
            val endMs = valueObj.requiredNonNegativeLong("end_ms")
            if (endMs <= startMs) {
                throw SampleLibFailure.InvalidResponse("Range candidate end_ms must be greater than start_ms")
            }
            AnalysisCandidateValue.Range(
                startMs = startMs,
                endMs = endMs,
                role = valueObj.requiredString("role"),
                label = valueObj.optionalString("label"),
            )
        }
        AnalysisCandidateKind.RELATED_TRACK -> {
            val valueObj = obj["value"] as? JsonObject
                ?: throw SampleLibFailure.InvalidResponse("Related-track candidate value must be an object")
            AnalysisCandidateValue.RelatedTrack(
                trackId = valueObj.requiredString("track_id"),
                dimensions = (valueObj["dimensions"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                    .orEmpty(),
            )
        }
    }
    val sourceObj = obj["source"] as? JsonObject
        ?: throw SampleLibFailure.InvalidResponse("Analysis candidate source must be an object")
    val inputObj = obj["input_identity"] as? JsonObject
        ?: throw SampleLibFailure.InvalidResponse("Analysis candidate input_identity must be an object")
    val confidence = obj["confidence"]?.let { element ->
        if (element.toString() == "null") null else element.jsonPrimitive.doubleOrNull
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: throw SampleLibFailure.InvalidResponse("Analysis candidate confidence must be in [0, 1]")
    }
    val freshness = when (obj.requiredString("freshness")) {
        "fresh" -> AnalysisCandidateFreshness.FRESH
        "stale" -> AnalysisCandidateFreshness.STALE
        "unknown" -> AnalysisCandidateFreshness.UNKNOWN
        else -> throw SampleLibFailure.InvalidResponse("Unknown analysis candidate freshness")
    }
    val decision = when (obj.requiredString("decision")) {
        "proposed" -> AnalysisCandidateDecision.PROPOSED
        "accepted" -> AnalysisCandidateDecision.ACCEPTED
        "rejected" -> AnalysisCandidateDecision.REJECTED
        "superseded" -> AnalysisCandidateDecision.SUPERSEDED
        else -> throw SampleLibFailure.InvalidResponse("Unknown analysis candidate decision")
    }
    val entityType = inputObj.requiredString("entity_type")
    val entityId = inputObj.requiredString("entity_id")
    if (entityType !in setOf("asset", "interval")) {
        throw SampleLibFailure.InvalidResponse("Analysis input identity must target asset or interval")
    }
    val assetId = obj.requiredString("asset_id")
    val intervalId = obj.optionalString("interval_id")
    if (entityType == "asset" && entityId != assetId) {
        throw SampleLibFailure.InvalidResponse("Analysis input asset identity does not match candidate asset_id")
    }
    if (entityType == "interval" && entityId != intervalId) {
        throw SampleLibFailure.InvalidResponse("Analysis input interval identity does not match candidate interval_id")
    }
    return SampleLibAnalysisCandidate(
        candidateId = candidateId,
        analysisRunId = obj.requiredString("analysis_run_id"),
        assetId = assetId,
        intervalId = intervalId,
        kind = kind,
        value = value,
        confidence = confidence,
        source = AnalysisCandidateSource(
            service = sourceObj.requiredString("service"),
            model = sourceObj.optionalString("model"),
            version = sourceObj.optionalString("version"),
            pipelineVersion = sourceObj.optionalString("pipeline_version"),
        ),
        generatedAt = try {
            Instant.parse(obj.requiredString("generated_at"))
        } catch (_: Exception) {
            throw SampleLibFailure.InvalidResponse("Invalid analysis candidate generated_at")
        },
        inputIdentity = AnalysisInputIdentity(
            entityType = entityType,
            entityId = entityId,
            revision = OpaqueRevision(inputObj.requiredString("revision")),
            contentSha256 = inputObj.optionalString("content_sha256"),
        ),
        freshness = freshness,
        decision = decision,
    )
}

private val AnalysisCandidateDecision.presentationDecision: SuggestionDecision
    get() = when (this) {
        AnalysisCandidateDecision.PROPOSED -> SuggestionDecision.PROPOSED
        AnalysisCandidateDecision.ACCEPTED -> SuggestionDecision.ACCEPTED
        AnalysisCandidateDecision.REJECTED -> SuggestionDecision.REJECTED
        AnalysisCandidateDecision.SUPERSEDED -> SuggestionDecision.SUPERSEDED
    }

private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?.takeIf(String::isNotBlank)
    ?: throw SampleLibFailure.InvalidResponse("Missing or blank analysis candidate field: $name")

private fun JsonObject.optionalString(name: String): String? = this[name]
    ?.takeUnless { it.toString() == "null" }
    ?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)

private fun JsonObject.requiredNonNegativeLong(name: String): Long = this[name]
    ?.jsonPrimitive
    ?.longOrNull
    ?.takeIf { it >= 0L }
    ?: throw SampleLibFailure.InvalidResponse("Invalid non-negative analysis candidate field: $name")
