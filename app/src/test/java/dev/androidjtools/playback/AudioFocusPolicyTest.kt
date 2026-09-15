package dev.androidjtools.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    @Test
    fun transientLossPausesAndGainResumesOnlyWhenFocusPausedPlayback() {
        val lost = AudioFocusPolicy.reduce(AudioFocusState(), AudioFocusEvent.LOSS_TRANSIENT, wasPlaying = true)
        assertTrue(lost.state.pausedByFocusLoss)
        assertEquals(listOf(AudioFocusAction.Pause), lost.actions)

        val gained = AudioFocusPolicy.reduce(lost.state, AudioFocusEvent.GAIN, wasPlaying = false)
        assertFalse(gained.state.pausedByFocusLoss)
        assertEquals(listOf(AudioFocusAction.Resume), gained.actions)
    }

    @Test
    fun duckingRestoresVolumeWithoutForcingResume() {
        val ducked = AudioFocusPolicy.reduce(AudioFocusState(), AudioFocusEvent.LOSS_TRANSIENT_CAN_DUCK, wasPlaying = true)
        assertTrue(ducked.state.ducked)
        assertEquals(listOf(AudioFocusAction.SetVolumeMultiplier(0.2f)), ducked.actions)

        val gained = AudioFocusPolicy.reduce(ducked.state, AudioFocusEvent.GAIN, wasPlaying = true)
        assertEquals(listOf(AudioFocusAction.SetVolumeMultiplier(1f)), gained.actions)
    }

    @Test
    fun permanentLossNeverAutoResumes() {
        val lost = AudioFocusPolicy.reduce(
            AudioFocusState(pausedByFocusLoss = true, ducked = true),
            AudioFocusEvent.LOSS,
            wasPlaying = true,
        )
        assertEquals(
            listOf(AudioFocusAction.SetVolumeMultiplier(1f), AudioFocusAction.Pause),
            lost.actions,
        )
        assertFalse(lost.state.pausedByFocusLoss)
        assertFalse(lost.state.ducked)
    }
}
