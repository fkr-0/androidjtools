package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AnalysisRequestAvailability
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.core.provider.SuggestionAcceptance
import dev.androidjtools.ui.waveform.serverEditStatusLabel

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

/** Where an accepted suggestion stands, in the words used for saved cue and loop edits. */
data class AcceptanceUi(val status: ServerEditStatus, val label: String, val detail: String) {
    /** A conflict or a refusal offers exactly one thing: dismiss. There is no reapply for a suggestion. */
    val onlyDismiss: Boolean get() = status == ServerEditStatus.CONFLICT || status == ServerEditStatus.REJECTED
    val inFlight: Boolean get() = status == ServerEditStatus.PENDING || status == ServerEditStatus.SENDING
}

/** The explicit "Analyse this track" action: a button when the server offers it, otherwise one line saying why not. */
data class AnalysisRequestUi(val offered: Boolean, val note: String?) {
    companion object {
        val None = AnalysisRequestUi(offered = false, note = null)

        fun of(availability: AnalysisRequestAvailability) = when (availability) {
            AnalysisRequestAvailability.Offered -> AnalysisRequestUi(offered = true, note = null)
            is AnalysisRequestAvailability.Unavailable -> AnalysisRequestUi(offered = false, note = availability.reason)
        }
    }
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
    /** A tempo's half/double-time aliases and its own value, ascending, when there is a real choice to make. */
    val tempoChoices: List<Double> = emptyList(),
    /** The suggestion's own tempo, which needs no choice to accept. */
    val primaryBpm: Double? = null,
    /** Candidate cue positions; "Add as cues" is offered for these. */
    val cuePointsMs: List<Long> = emptyList(),
    val analysisRunId: String? = null,
    val acceptance: AcceptanceUi? = null,
) {
    /** Shown only when the producing run recorded one. Never required, never used to order or hide anything. */
    val confidenceLabel: String? get() = confidence?.let { "confidence ${"%.0f".format(it * 100)}%" }
}

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

/** Whether a suggestion's timeline position can be auditioned on this track right now. */
data class PreviewAvailability(val enabled: Boolean, val reason: String? = null) {
    companion object {
        val Available = PreviewAvailability(enabled = true)
    }
}

fun previewAvailability(track: Track): PreviewAvailability =
    if (track.playable) {
        PreviewAvailability.Available
    } else {
        PreviewAvailability(
            enabled = false,
            reason = track.unplayableReason?.takeIf { it.isNotBlank() } ?: "This track cannot be played yet.",
        )
    }

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
    val preview: PreviewAvailability = PreviewAvailability.Available,
    val request: AnalysisRequestUi = AnalysisRequestUi.None,
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
    /** A tempo alternative the person picked; null means the suggestion's own tempo. */
    val chosenBpm: Double? = null,
)

sealed interface AnalysisUiAction {
    data class QueueAcceptance(val intent: AnalysisMutationIntent) : AnalysisUiAction
    data class Reject(val suggestionId: String) : AnalysisUiAction
    data class Preview(val suggestionId: String) : AnalysisUiAction
    data class Refresh(val trackId: String) : AnalysisUiAction

    /** The only way analysis starts. Offered (and handled) only when the server advertised it. */
    data class RequestAnalysis(val trackId: String) : AnalysisUiAction

    /** Turn cue candidates into cues with the same editor-state path as hand-made cues, all in one edit. */
    data class AddAsCues(val suggestionId: String) : AnalysisUiAction
}

fun CandidateUi.acceptanceIntent(offline: Boolean, chosenBpm: Double? = null): AnalysisMutationIntent? {
    if (reviewState != CandidateReviewState.PROPOSED || stale) return null
    if (acceptance?.onlyDismiss == true) return null
    return AnalysisMutationIntent(
        suggestionId = id,
        trackId = trackId,
        kind = kind,
        inputRevision = inputRevision,
        provenance = provenance,
        queuedOffline = offline,
        requiresFreshnessConfirmation = stale,
        chosenBpm = chosenBpm?.takeIf { choice -> tempoChoices.any { kotlin.math.abs(it - choice) < 1e-9 } },
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
    acceptances: Map<String, SuggestionAcceptance> = emptyMap(),
    request: AnalysisRequestUi = AnalysisRequestUi.None,
): AnalysisAssistantUiState {
    val offline = syncState == SyncState.OFFLINE
    val safetyKinds = setOf(SuggestionKind.LOUDNESS_WARNING, SuggestionKind.SPECTRAL_OUTLIER_WARNING)
    val safety = suggestions
        .filter { it.kind in safetyKinds }
        .mapNotNull(::toSafetyFinding)
        .sortedByDescending { safetySeverityRank(it.severity) }

    val candidates = suggestions
        .filterNot { it.kind in safetyKinds }
        .map { it.toCandidateUi(queuedSuggestionIds, acceptances[it.id]) }

    val groups = candidates
        .groupBy { it.kind }
        .map { (kind, values) ->
            CandidateGroupUi(
                kind = kind,
                label = kind.displayLabel(),
                // Proposed first, then a stable order. Confidence is optional, so it plays no part here.
                candidates = values.sortedWith(
                    compareByDescending<CandidateUi> { it.reviewState == CandidateReviewState.PROPOSED }.thenBy { it.id },
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
                (capability.anySource || suggestion.source.service == capability.id || capability.id == "sample-intelligence")
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
        request = request,
    )
}

private fun AnalysisSuggestion.toCandidateUi(queuedSuggestionIds: Set<String>, acceptance: SuggestionAcceptance?): CandidateUi {
    val rendered = payload.render()
    val acceptanceUi = acceptance?.let { AcceptanceUi(it.status, serverEditStatusLabel(it.status), it.detail) }
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
            acceptanceUi?.inFlight == true && decision == SuggestionDecision.PROPOSED -> CandidateReviewState.QUEUED
            acceptanceUi?.status == ServerEditStatus.SAVED -> CandidateReviewState.ACCEPTED
            id in queuedSuggestionIds && decision == SuggestionDecision.PROPOSED -> CandidateReviewState.QUEUED
            decision == SuggestionDecision.ACCEPTED -> CandidateReviewState.ACCEPTED
            decision == SuggestionDecision.REJECTED -> CandidateReviewState.REJECTED
            decision == SuggestionDecision.SUPERSEDED -> CandidateReviewState.SUPERSEDED
            else -> CandidateReviewState.PROPOSED
        },
        timelineStartMs = rendered.startMs,
        timelineEndMs = rendered.endMs,
        tempoChoices = (payload as? SuggestionPayload.Bpm)?.takeIf { it.alternatives.isNotEmpty() }
            ?.let { (it.alternatives + it.value).distinct().sorted() }.orEmpty(),
        primaryBpm = (payload as? SuggestionPayload.Bpm)?.value,
        cuePointsMs = (payload as? SuggestionPayload.CuePoints)?.pointsMs.orEmpty(),
        analysisRunId = analysisRunId,
        acceptance = acceptanceUi,
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
    is SuggestionPayload.Bpm -> RenderedPayload(
        "%.2f BPM".format(value),
        alternatives.takeIf { it.isNotEmpty() }?.let { "Also possible: " + it.joinToString(" · ") { alt -> "%.2f".format(alt) } + " BPM" },
    )
    is SuggestionPayload.Grid -> RenderedPayload(
        "%.2f BPM grid".format(bpm),
        "First beat at ${formatTime(firstBeatMs)}.${"%03d".format(firstBeatMs % 1000)} · $beatCount beats",
    )
    is SuggestionPayload.CuePoints -> RenderedPayload(
        "${pointsMs.size} cue ${if (pointsMs.size == 1) "point" else "points"}",
        pointsMs.take(CUE_PREVIEW_COUNT).joinToString(" · ") { formatTime(it) } + if (pointsMs.size > CUE_PREVIEW_COUNT) " · …" else "",
    )
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
    SuggestionKind.BEAT_GRID -> "Beat grid"
    SuggestionKind.LOUDNESS_WARNING -> "Loudness safety"
    SuggestionKind.SPECTRAL_OUTLIER_WARNING -> "Spectral safety"
}

private const val CUE_PREVIEW_COUNT = 8

private fun candidateKindOrder(kind: SuggestionKind): Int = when (kind) {
    SuggestionKind.BPM -> 0
    SuggestionKind.BEAT_GRID -> 1
    SuggestionKind.KEY -> 2
    SuggestionKind.CUE, SuggestionKind.LOOP, SuggestionKind.REGION -> 3
    SuggestionKind.STEM -> 4
    SuggestionKind.TRANSCRIPT_MARKER -> 5
    SuggestionKind.RELATED_TRACK -> 6
    SuggestionKind.MIX_PREP -> 7
    SuggestionKind.LOUDNESS_WARNING, SuggestionKind.SPECTRAL_OUTLIER_WARNING -> 8
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

