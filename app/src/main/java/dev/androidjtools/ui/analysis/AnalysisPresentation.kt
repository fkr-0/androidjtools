package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState

enum class CapabilityAvailability { AVAILABLE, DEGRADED, UNAVAILABLE, UNKNOWN }

enum class AnalysisJobState { NOT_REQUESTED, QUEUED, RUNNING, READY, PARTIAL, FAILED, UNAVAILABLE, CACHED }

enum class CandidateReviewState { PROPOSED, QUEUED, ACCEPTED, REJECTED, SUPERSEDED }

data class CapabilityUi(
    val id: String,
    val label: String,
    val availability: CapabilityAvailability,
    val detail: String? = null,
)

data class AnalysisJobUi(
    val id: String,
    val label: String,
    val state: AnalysisJobState,
    val progress: Float? = null,
    val detail: String? = null,
)

data class SuggestionProvenanceUi(
    val service: String,
    val model: String? = null,
    val modelVersion: String? = null,
    val pipelineVersion: String? = null,
) {
    val label: String = buildList {
        add(service)
        model?.let(::add)
        modelVersion?.let { add("v$it") }
        pipelineVersion?.let { add("pipeline $it") }
    }.joinToString(" · ")
}

data class CandidateUi(
    val id: String,
    val trackId: String,
    val kind: SuggestionKind,
    val title: String,
    val value: String,
    val detail: String? = null,
    val confidence: Double? = null,
    val provenance: SuggestionProvenanceUi,
    val inputRevision: Long? = null,
    val generatedAt: String,
    val stale: Boolean = false,
    val reviewState: CandidateReviewState = CandidateReviewState.PROPOSED,
    val timelineStartMs: Long? = null,
    val timelineEndMs: Long? = null,
)

data class SafetyFindingUi(
    val id: String,
    val title: String,
    val message: String,
    val severity: String,
    val provenance: SuggestionProvenanceUi,
    val stale: Boolean,
)

data class CandidateGroupUi(
    val kind: SuggestionKind,
    val label: String,
    val candidates: List<CandidateUi>,
) {
    val competing: Boolean get() = candidates.count { it.reviewState == CandidateReviewState.PROPOSED } > 1
}

data class TimelineAnalysisItemUi(
    val id: String,
    val startMs: Long,
    val endMs: Long? = null,
    val label: String,
    val kind: SuggestionKind,
    val stale: Boolean,
)

data class AnalysisAssistantUiState(
    val trackId: String,
    val trackTitle: String,
    val offline: Boolean,
    val capabilities: List<CapabilityUi>,
    val jobs: List<AnalysisJobUi>,
    val candidateGroups: List<CandidateGroupUi>,
    val safetyFindings: List<SafetyFindingUi>,
    val timelineItems: List<TimelineAnalysisItemUi>,
    val partialFailure: Boolean,
    val failureMessage: String? = null,
) {
    val hasSuggestions: Boolean get() = candidateGroups.any { it.candidates.isNotEmpty() }
}

data class AnalysisMutationIntent(
    val suggestionId: String,
    val trackId: String,
    val kind: SuggestionKind,
    val inputRevision: Long?,
    val provenance: SuggestionProvenanceUi,
    val queuedOffline: Boolean,
    val requiresFreshnessConfirmation: Boolean,
)

sealed interface AnalysisUiAction {
    data class QueueAcceptance(val intent: AnalysisMutationIntent) : AnalysisUiAction
    data class Reject(val suggestionId: String) : AnalysisUiAction
    data class Preview(val suggestionId: String) : AnalysisUiAction
    data class Refresh(val trackId: String) : AnalysisUiAction
}

fun CandidateUi.acceptanceIntent(offline: Boolean): AnalysisMutationIntent? {
    if (reviewState != CandidateReviewState.PROPOSED || stale) return null
    return AnalysisMutationIntent(
        suggestionId = id,
        trackId = trackId,
        kind = kind,
        inputRevision = inputRevision,
        provenance = provenance,
        queuedOffline = offline,
        requiresFreshnessConfirmation = stale,
    )
}

fun presentAnalysisAssistant(
    trackId: String,
    trackTitle: String,
    suggestions: List<AnalysisSuggestion>,
    capabilities: List<AnalysisCapability>,
    syncState: SyncState,
    queuedSuggestionIds: Set<String> = emptySet(),
    failureMessage: String? = null,
): AnalysisAssistantUiState {
    val offline = syncState == SyncState.OFFLINE
    val safetyKinds = setOf(SuggestionKind.LOUDNESS_WARNING, SuggestionKind.SPECTRAL_OUTLIER_WARNING)
    val safety = suggestions
        .filter { it.kind in safetyKinds }
        .mapNotNull(::toSafetyFinding)
        .sortedByDescending { safetySeverityRank(it.severity) }

    val candidates = suggestions
        .filterNot { it.kind in safetyKinds }
        .map { it.toCandidateUi(queuedSuggestionIds) }

    val groups = candidates
        .groupBy { it.kind }
        .map { (kind, values) ->
            CandidateGroupUi(
                kind = kind,
                label = kind.displayLabel(),
                candidates = values.sortedWith(
                    compareByDescending<CandidateUi> { it.reviewState == CandidateReviewState.PROPOSED }
                        .thenByDescending { it.confidence ?: -1.0 }
                        .thenBy { it.id }
                ),
            )
        }
        .sortedBy { candidateKindOrder(it.kind) }

    val capabilityUi = capabilities.map { capability ->
        CapabilityUi(
            id = capability.id,
            label = capability.displayName,
            availability = if (capability.available) CapabilityAvailability.AVAILABLE else CapabilityAvailability.UNAVAILABLE,
            detail = capability.detail,
        )
    }

    val jobs = capabilities.map { capability ->
        val matching = suggestions.filter { suggestion ->
            suggestion.kind in capability.kinds &&
                (suggestion.source.service == capability.id || capability.id == "sample-intelligence")
        }
        val state = when {
            !capability.available -> AnalysisJobState.UNAVAILABLE
            offline && matching.isNotEmpty() -> AnalysisJobState.CACHED
            syncState == SyncState.DEGRADED && matching.isEmpty() -> AnalysisJobState.PARTIAL
            matching.isNotEmpty() -> AnalysisJobState.READY
            else -> AnalysisJobState.NOT_REQUESTED
        }
        AnalysisJobUi(
            id = capability.id,
            label = capability.displayName,
            state = state,
            detail = when (state) {
                AnalysisJobState.CACHED -> "Cached results remain reviewable offline"
                AnalysisJobState.UNAVAILABLE -> capability.detail ?: "Capability unavailable"
                AnalysisJobState.PARTIAL -> "Backend is degraded; other analysis remains usable"
                else -> capability.detail
            },
        )
    }

    val timeline = candidates.mapNotNull { candidate ->
        candidate.timelineStartMs?.let { start ->
            TimelineAnalysisItemUi(
                id = candidate.id,
                startMs = start,
                endMs = candidate.timelineEndMs,
                label = candidate.value,
                kind = candidate.kind,
                stale = candidate.stale,
            )
        }
    }.sortedWith(compareBy<TimelineAnalysisItemUi> { it.startMs }.thenBy { it.id })

    val availableCount = capabilityUi.count { it.availability == CapabilityAvailability.AVAILABLE }
    val unavailableCount = capabilityUi.count { it.availability == CapabilityAvailability.UNAVAILABLE }
    return AnalysisAssistantUiState(
        trackId = trackId,
        trackTitle = trackTitle,
        offline = offline,
        capabilities = capabilityUi,
        jobs = jobs,
        candidateGroups = groups,
        safetyFindings = safety,
        timelineItems = timeline,
        partialFailure = (availableCount > 0 && unavailableCount > 0) || syncState == SyncState.DEGRADED,
        failureMessage = failureMessage,
    )
}

private fun AnalysisSuggestion.toCandidateUi(queuedSuggestionIds: Set<String>): CandidateUi {
    val rendered = payload.render()
    return CandidateUi(
        id = id,
        trackId = trackId,
        kind = kind,
        title = kind.displayLabel(),
        value = rendered.value,
        detail = rendered.detail,
        confidence = confidence,
        provenance = source.toUi(),
        inputRevision = inputRevision,
        generatedAt = generatedAt.toString(),
        stale = stale,
        reviewState = when {
            id in queuedSuggestionIds && decision == SuggestionDecision.PROPOSED -> CandidateReviewState.QUEUED
            decision == SuggestionDecision.ACCEPTED -> CandidateReviewState.ACCEPTED
            decision == SuggestionDecision.REJECTED -> CandidateReviewState.REJECTED
            decision == SuggestionDecision.SUPERSEDED -> CandidateReviewState.SUPERSEDED
            else -> CandidateReviewState.PROPOSED
        },
        timelineStartMs = rendered.startMs,
        timelineEndMs = rendered.endMs,
    )
}

private fun toSafetyFinding(suggestion: AnalysisSuggestion): SafetyFindingUi? {
    val payload = suggestion.payload as? SuggestionPayload.Warning
    val title = when (suggestion.kind) {
        SuggestionKind.LOUDNESS_WARNING -> "Loudness safety"
        SuggestionKind.SPECTRAL_OUTLIER_WARNING -> "Spectral safety"
        else -> return null
    }
    return SafetyFindingUi(
        id = suggestion.id,
        title = title,
        message = payload?.message ?: "Audio safety analysis requires attention",
        severity = payload?.severity ?: "warning",
        provenance = suggestion.source.toUi(),
        stale = suggestion.stale,
    )
}

private data class RenderedPayload(
    val value: String,
    val detail: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
)

private fun SuggestionPayload.render(): RenderedPayload = when (this) {
    is SuggestionPayload.Bpm -> RenderedPayload("%.2f BPM".format(value))
    is SuggestionPayload.Key -> RenderedPayload(value)
    is SuggestionPayload.Point -> RenderedPayload(label ?: role, "${role} · ${formatTime(positionMs)}", positionMs)
    is SuggestionPayload.Range -> RenderedPayload(label ?: role, "${role} · ${formatTime(startMs)}–${formatTime(endMs)}", startMs, endMs)
    is SuggestionPayload.Stem -> RenderedPayload("${stem.replaceFirstChar { it.uppercase() }} stem", "Registered derived media")
    is SuggestionPayload.Transcript -> RenderedPayload("“$text”", listOfNotNull(language, formatTime(positionMs)).joinToString(" · "), positionMs)
    is SuggestionPayload.Related -> RenderedPayload(trackId, dimensions.joinToString(" · "))
    is SuggestionPayload.Warning -> RenderedPayload(message, "$code · $severity")
    is SuggestionPayload.Text -> RenderedPayload(title, detail)
}

private fun IntelligenceSource.toUi() = SuggestionProvenanceUi(service, model, version, pipelineVersion)

private fun SuggestionKind.displayLabel(): String = when (this) {
    SuggestionKind.BPM -> "Tempo"
    SuggestionKind.KEY -> "Key"
    SuggestionKind.CUE -> "Cue points"
    SuggestionKind.LOOP -> "Loops"
    SuggestionKind.REGION -> "Regions"
    SuggestionKind.STEM -> "Stems"
    SuggestionKind.TRANSCRIPT_MARKER -> "Transcript & phrases"
    SuggestionKind.RELATED_TRACK -> "Related tracks"
    SuggestionKind.MIX_PREP -> "Mix preparation"
    SuggestionKind.LOUDNESS_WARNING -> "Loudness safety"
    SuggestionKind.SPECTRAL_OUTLIER_WARNING -> "Spectral safety"
}

private fun candidateKindOrder(kind: SuggestionKind): Int = when (kind) {
    SuggestionKind.BPM -> 0
    SuggestionKind.KEY -> 1
    SuggestionKind.CUE, SuggestionKind.LOOP, SuggestionKind.REGION -> 2
    SuggestionKind.STEM -> 3
    SuggestionKind.TRANSCRIPT_MARKER -> 4
    SuggestionKind.RELATED_TRACK -> 5
    SuggestionKind.MIX_PREP -> 6
    SuggestionKind.LOUDNESS_WARNING, SuggestionKind.SPECTRAL_OUTLIER_WARNING -> 7
}

private fun safetySeverityRank(severity: String): Int = when (severity.lowercase()) {
    "blocking" -> 3
    "warning" -> 2
    else -> 1
}

private fun formatTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

