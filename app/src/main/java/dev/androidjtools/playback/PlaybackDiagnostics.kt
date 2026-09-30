package dev.androidjtools.playback

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackIssueKind {
    UNAVAILABLE_MEDIA,
    AUDIO_FOCUS,
    PLAYBACK_ERROR,
}

data class PlaybackIssue(
    val kind: PlaybackIssueKind,
    val message: String,
)

/**
 * Optional diagnostics extension for PlaybackProvider implementations.
 *
 * The core provider contract intentionally stays backend-neutral; UI code can consume this
 * capability when the concrete playback backend supplies richer Media3 state.
 */
interface PlaybackDiagnostics {
    val issue: StateFlow<PlaybackIssue?>
}
