package dev.androidjtools.ui.analysis

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.ui.waveform.ServerEditPlanner
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Provider adapter for the preparation-assistant UI, for the track being played (else the first one).
 *
 * Suggestions are advisory and come from the paired server. Accepting queues a journal mutation and the library shows
 * the result after the next sync; dismissing is local. Analysis starts only from the "Analyse this track" button,
 * which is shown only when the server offered it; nothing on this screen runs analysis by itself.
 */
@Composable
fun AnalysisScreen(providers: AppProviders) {
    val tracks by providers.library.tracks.collectAsState()
    val currentId by providers.playback.currentTrackId.collectAsState()
    val track = currentId?.let { id -> tracks.firstOrNull { it.id == id } } ?: tracks.firstOrNull()
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
    val acceptances by providers.analysis.acceptances.collectAsState()
    val requestAvailability by providers.analysis.requestAvailability.collectAsState()
    val acceptAvailability by providers.analysis.acceptAvailability.collectAsState()
    val cues by providers.preparation.cues(track.id).collectAsState()
    val loops by providers.preparation.loops(track.id).collectAsState()
    val scope = rememberCoroutineScope()
    var notice by remember(track.id) { mutableStateOf<String?>(null) }
    var requesting by remember(track.id) { mutableStateOf(false) }

    val state = presentAnalysisAssistant(
        trackId = track.id,
        trackTitle = track.title,
        suggestions = suggestions,
        capabilities = capabilities,
        syncState = syncState,
        acceptances = acceptances,
        request = AnalysisRequestUi.of(requestAvailability),
    ).copy(preview = previewAvailability(track))
    AnalysisAssistantContent(
        state = state,
        acceptanceEnabled = acceptAvailability == EditAvailability.Available,
        acceptDisabledReason = (acceptAvailability as? EditAvailability.Unavailable)?.reason,
        notice = notice,
        requestInFlight = requesting,
        onAction = { action ->
            when (action) {
                is AnalysisUiAction.Preview -> {
                    if (track.playable) {
                        suggestions.firstOrNull { it.id == action.suggestionId }?.let(::previewPositionMs)?.let { position ->
                            providers.playback.play(track.id)
                            providers.playback.seek(position)
                        }
                    }
                }
                else -> scope.launch {
                    val asking = action is AnalysisUiAction.RequestAnalysis
                    if (asking) requesting = true
                    try {
                        val said = performAnalysisAction(action, providers.analysis, providers.edits, suggestions) {
                            ServerEditPlanner(
                                track = track,
                                cues = cues,
                                loops = loops,
                                aggregates = providers.edits.aggregates(track.id),
                                nowMs = System::currentTimeMillis,
                                newMutationId = { "android-" + UUID.randomUUID() },
                            )
                        }
                        // Silent gestures (dismiss, refresh) leave the last sentence alone.
                        if (said != null) notice = said
                    } finally {
                        if (asking) requesting = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize().padding(16.dp),
    )
}
