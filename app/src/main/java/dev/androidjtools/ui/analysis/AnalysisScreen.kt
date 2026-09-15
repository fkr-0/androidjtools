package dev.androidjtools.ui.analysis

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.provider.AppProviders

/**
 * Provider adapter for the preparation-assistant UI.
 *
 * The current core provider exposes no durable journal enqueue API for suggestion acceptance.
 * Therefore the default app route is deliberately review-only for acceptance: it never calls
 * AnalysisProvider.accept(), because that would make a proposal look authoritative before a
 * normal mutation receipt. A journal integration can pass [onQueueAcceptance] once EPIC-11
 * exposes the durable sink. Reject/refresh remain advisory provider operations.
 */
@Composable
fun AnalysisScreen(
    providers: AppProviders,
    onQueueAcceptance: ((AnalysisMutationIntent) -> Unit)? = null,
) {
    val tracks by providers.library.tracks.collectAsState()
    val track = tracks.firstOrNull()
    if (track == null) {
        AnalysisAssistantContent(
            state = AnalysisAssistantFixtures.empty().copy(trackTitle = "No track available"),
            acceptanceEnabled = false,
            onAction = {},
            modifier = Modifier.fillMaxSize().padding(16.dp),
        )
        return
    }

    val suggestions by providers.analysis.suggestions(track.id).collectAsState()
    val capabilities by providers.analysis.capabilities.collectAsState()
    val syncState by providers.sync.state.collectAsState()
    val pending by providers.journal.pending.collectAsState()
    val queuedSuggestionIds = pending.mapNotNull { mutation ->
        mutation.provenance
            ?.takeIf { it.startsWith("suggestion:") }
            ?.removePrefix("suggestion:")
            ?.substringBefore(':')
            ?.takeIf(String::isNotBlank)
    }.toSet()

    val state = presentAnalysisAssistant(
        trackId = track.id,
        trackTitle = track.title,
        suggestions = suggestions,
        capabilities = capabilities,
        syncState = syncState,
        queuedSuggestionIds = queuedSuggestionIds,
    )
    AnalysisAssistantContent(
        state = state,
        acceptanceEnabled = onQueueAcceptance != null,
        onAction = { action ->
            when (action) {
                is AnalysisUiAction.QueueAcceptance -> onQueueAcceptance?.invoke(action.intent)
                is AnalysisUiAction.Reject -> providers.analysis.reject(action.suggestionId)
                is AnalysisUiAction.Refresh -> providers.analysis.refresh(action.trackId)
                is AnalysisUiAction.Preview -> {
                    val positionMs = suggestions
                        .firstOrNull { it.id == action.suggestionId }
                        ?.let { suggestion ->
                            when (val payload = suggestion.payload) {
                                is SuggestionPayload.Point -> payload.positionMs
                                is SuggestionPayload.Range -> payload.startMs
                                is SuggestionPayload.Transcript -> payload.positionMs
                                else -> null
                            }
                        }
                    positionMs?.let { position ->
                        providers.playback.play(track.id)
                        providers.playback.seek(position)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize().padding(16.dp),
    )
}

