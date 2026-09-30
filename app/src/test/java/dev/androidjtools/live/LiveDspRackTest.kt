package dev.androidjtools.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDspRackTest {
    @Test
    fun gateSuppressesSamplesBelowThresholdInPlace() {
        val rack = LiveDspRack(sampleRate = 48_000, channelCount = 2)
        rack.setEffect(
            LiveEffectType.GATE,
            LiveEffectState(enabled = true, mix = 1f, parameter = 0.5f),
        )
        val samples = floatArrayOf(0.02f, -0.02f, 0.8f, -0.8f)

        rack.processInterleaved(samples)

        assertEquals(0f, samples[0], 0f)
        assertEquals(0f, samples[1], 0f)
        assertEquals(0.8f, samples[2], 0.0001f)
        assertEquals(-0.8f, samples[3], 0.0001f)
    }

    @Test
    fun lowPassFilterChangesAbruptSignalWithoutGrowingBuffer() {
        val rack = LiveDspRack(sampleRate = 48_000, channelCount = 1)
        rack.setEffect(
            LiveEffectType.FILTER,
            LiveEffectState(enabled = true, mix = 1f, parameter = 0f),
        )
        val samples = floatArrayOf(1f, -1f, 1f, -1f)
        val identity = System.identityHashCode(samples)

        rack.processInterleaved(samples)

        assertEquals(identity, System.identityHashCode(samples))
        assertNotEquals(1f, samples[0])
        assertTrue(samples.all { it in -1f..1f })
    }

    @Test
    fun effectParametersAreClampedAtControlBoundary() {
        val rack = LiveDspRack(sampleRate = 48_000, channelCount = 2)
        rack.setEffect(
            LiveEffectType.ECHO,
            LiveEffectState(enabled = true, mix = 9f, parameter = -3f),
        )

        val effect = rack.effect(LiveEffectType.ECHO)
        assertEquals(1f, effect.mix, 0f)
        assertEquals(0f, effect.parameter, 0f)
    }
}
