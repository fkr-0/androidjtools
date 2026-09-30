package dev.androidjtools.live

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Stateful in-place stereo/mono DSP rack suitable for a native/PCM callback adapter.
 *
 * [processInterleaved] performs no buffer allocation. Parameter-map replacement happens only on the
 * control thread via [setEffect].
 */
class LiveDspRack(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    init {
        require(sampleRate in 8_000..384_000)
        require(channelCount in 1..8)
    }

    @Volatile
    private var effects: Map<LiveEffectType, LiveEffectState> =
        LiveEffectType.entries.associateWith { LiveEffectState() }

    private val filterState = FloatArray(channelCount)
    private val echoBuffer = FloatArray(sampleRate * channelCount)
    private var echoFrame = 0
    private val flangerFrames = (sampleRate * 0.03).toInt().coerceAtLeast(8)
    private val flangerBuffer = FloatArray(flangerFrames * channelCount)
    private var flangerFrame = 0
    private var flangerPhase = 0.0

    fun setEffect(type: LiveEffectType, state: LiveEffectState) {
        effects = effects + (type to state.normalized())
    }

    fun effect(type: LiveEffectType): LiveEffectState = effects.getValue(type)

    fun processInterleaved(samples: FloatArray, frameCount: Int = samples.size / channelCount) {
        val frames = frameCount.coerceIn(0, samples.size / channelCount)
        if (frames == 0) return
        val snapshot = effects
        repeat(frames) { frame ->
            val frameOffset = frame * channelCount
            repeat(channelCount) { channel ->
                val sampleIndex = frameOffset + channel
                var sample = samples[sampleIndex]

                snapshot[LiveEffectType.FILTER]?.takeIf { it.enabled }?.let { fx ->
                    sample = processFilter(sample, channel, fx)
                }
                snapshot[LiveEffectType.ECHO]?.takeIf { it.enabled }?.let { fx ->
                    sample = processEcho(sample, channel, fx)
                }
                snapshot[LiveEffectType.FLANGER]?.takeIf { it.enabled }?.let { fx ->
                    sample = processFlanger(sample, channel, fx)
                }
                snapshot[LiveEffectType.GATE]?.takeIf { it.enabled }?.let { fx ->
                    sample = processGate(sample, fx)
                }

                samples[sampleIndex] = sample.coerceIn(-1f, 1f)
            }
            echoFrame = (echoFrame + 1) % sampleRate
            flangerFrame = (flangerFrame + 1) % flangerFrames
            val flanger = snapshot[LiveEffectType.FLANGER]
            if (flanger?.enabled == true) {
                val hz = 0.05 + flanger.parameter * 4.95
                flangerPhase += 2.0 * PI * hz / sampleRate
                if (flangerPhase >= 2.0 * PI) flangerPhase -= 2.0 * PI
            }
        }
    }

    private fun processFilter(sample: Float, channel: Int, fx: LiveEffectState): Float {
        val cutoffHz = 80.0 + fx.parameter * fx.parameter * 17_920.0
        val alpha = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()
        val filtered = filterState[channel] + alpha * (sample - filterState[channel])
        filterState[channel] = filtered
        return sample + (filtered - sample) * fx.mix
    }

    private fun processEcho(sample: Float, channel: Int, fx: LiveEffectState): Float {
        val delayFrames = (sampleRate * (0.12 + fx.parameter * 0.63)).toInt().coerceIn(1, sampleRate - 1)
        val readFrame = (echoFrame - delayFrames + sampleRate) % sampleRate
        val readIndex = readFrame * channelCount + channel
        val writeIndex = echoFrame * channelCount + channel
        val delayed = echoBuffer[readIndex]
        val feedback = 0.15f + fx.parameter * 0.70f
        echoBuffer[writeIndex] = (sample + delayed * feedback).coerceIn(-1f, 1f)
        return sample + (delayed - sample) * fx.mix
    }

    private fun processFlanger(sample: Float, channel: Int, fx: LiveEffectState): Float {
        val modulation = ((sin(flangerPhase) + 1.0) * 0.5).toFloat()
        val delayFrames = (
            sampleRate * (0.0005 + modulation * (0.0015 + fx.parameter * 0.004))
        ).toInt().coerceIn(1, flangerFrames - 1)
        val readFrame = (flangerFrame - delayFrames + flangerFrames) % flangerFrames
        val readIndex = readFrame * channelCount + channel
        val writeIndex = flangerFrame * channelCount + channel
        val delayed = flangerBuffer[readIndex]
        flangerBuffer[writeIndex] = sample
        return sample + (delayed - sample) * fx.mix
    }

    private fun processGate(sample: Float, fx: LiveEffectState): Float {
        val threshold = fx.parameter * 0.35f
        val gated = if (abs(sample) >= threshold) sample else 0f
        return sample + (gated - sample) * fx.mix
    }
}
