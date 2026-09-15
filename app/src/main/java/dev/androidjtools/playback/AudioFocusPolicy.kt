package dev.androidjtools.playback

enum class AudioFocusEvent {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
}

data class AudioFocusState(
    val pausedByFocusLoss: Boolean = false,
    val ducked: Boolean = false,
)

sealed interface AudioFocusAction {
    data object Pause : AudioFocusAction
    data object Resume : AudioFocusAction
    data class SetVolumeMultiplier(val multiplier: Float) : AudioFocusAction
}

data class AudioFocusTransition(
    val state: AudioFocusState,
    val actions: List<AudioFocusAction>,
)

object AudioFocusPolicy {
    fun reduce(
        state: AudioFocusState,
        event: AudioFocusEvent,
        wasPlaying: Boolean,
    ): AudioFocusTransition = when (event) {
        AudioFocusEvent.LOSS -> AudioFocusTransition(
            AudioFocusState(),
            buildList {
                if (state.ducked) add(AudioFocusAction.SetVolumeMultiplier(1f))
                if (wasPlaying) add(AudioFocusAction.Pause)
            },
        )
        AudioFocusEvent.LOSS_TRANSIENT -> AudioFocusTransition(
            AudioFocusState(pausedByFocusLoss = wasPlaying),
            buildList {
                if (state.ducked) add(AudioFocusAction.SetVolumeMultiplier(1f))
                if (wasPlaying) add(AudioFocusAction.Pause)
            },
        )
        AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK -> AudioFocusTransition(
            state.copy(ducked = true),
            listOf(AudioFocusAction.SetVolumeMultiplier(0.2f)),
        )
        AudioFocusEvent.GAIN -> AudioFocusTransition(
            AudioFocusState(),
            buildList {
                if (state.ducked) add(AudioFocusAction.SetVolumeMultiplier(1f))
                if (state.pausedByFocusLoss) add(AudioFocusAction.Resume)
            },
        )
    }
}
