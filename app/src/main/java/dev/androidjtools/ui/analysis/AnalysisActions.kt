package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.provider.AnalysisActionResult
import dev.androidjtools.core.provider.AnalysisProvider
import dev.androidjtools.core.provider.ServerEditProvider
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.ui.waveform.EditBuild
import dev.androidjtools.ui.waveform.ServerEditPlanner

/** Where a suggestion can be auditioned on the timeline, if anywhere. */
fun previewPositionMs(suggestion: AnalysisSuggestion): Long? = when (val payload = suggestion.payload) {
    is SuggestionPayload.Point -> payload.positionMs
    is SuggestionPayload.Range -> payload.startMs
    is SuggestionPayload.Transcript -> payload.positionMs
    is SuggestionPayload.CuePoints -> payload.pointsMs.firstOrNull()
    is SuggestionPayload.Grid -> payload.firstBeatMs
    else -> null
}

private fun AnalysisActionResult.words() = when (this) {
    is AnalysisActionResult.Done -> message
    is AnalysisActionResult.Refused -> reason
}

/**
 * Carries out one deliberate gesture on the Suggest screen and says, in a sentence, what happened (or null when
 * nothing needs saying). The only gesture that can start analysis is [AnalysisUiAction.RequestAnalysis]; every other
 * gesture is local, queues a journal entry, or asks for an ordinary refresh.
 *
 * [planner] is built lazily because it reads the track's cues and sections; [preview] is handled by the screen itself.
 */
suspend fun performAnalysisAction(
    action: AnalysisUiAction,
    analysis: AnalysisProvider,
    edits: ServerEditProvider,
    suggestions: List<AnalysisSuggestion>,
    planner: () -> ServerEditPlanner?,
): String? = when (action) {
    is AnalysisUiAction.RequestAnalysis -> analysis.requestAnalysis(action.trackId).words()
    is AnalysisUiAction.QueueAcceptance -> analysis.acceptSuggestion(action.intent.suggestionId, action.intent.chosenBpm).words()
    is AnalysisUiAction.Reject -> {
        analysis.dismissSuggestion(action.suggestionId)
        null
    }
    is AnalysisUiAction.Refresh -> {
        analysis.refresh(action.trackId)
        null
    }
    is AnalysisUiAction.AddAsCues -> addAsCues(action.suggestionId, edits, suggestions, planner)
    is AnalysisUiAction.Preview -> null
}

private suspend fun addAsCues(
    suggestionId: String,
    edits: ServerEditProvider,
    suggestions: List<AnalysisSuggestion>,
    planner: () -> ServerEditPlanner?,
): String {
    val suggestion = suggestions.firstOrNull { it.id == suggestionId }
    val points = (suggestion?.payload as? SuggestionPayload.CuePoints)?.pointsMs
        ?: return "These cue points are no longer there."
    val build = (planner() ?: return "This track is not on the server, so cues cannot be saved to it.")
        .addCues(points, suggestion.analysisRunId)
    return when (build) {
        is EditBuild.Refused -> build.reason
        is EditBuild.Ready -> when (val result = edits.stage(build.intent)) {
            is PreparationStageResult.Queued -> "Queued. The cues are sent to the server as soon as it can be reached."
            is PreparationStageResult.Rejected -> result.detail.ifBlank { "The cues could not be saved." }
            else -> "The cues could not be saved."
        }
    }
}
