package dev.androidjtools.live

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Media3 PCM adapter for [LiveDspRack].
 *
 * Media3 owns the decode and AudioTrack output path. This processor keeps one reusable float
 * scratch buffer per deck and applies the live rack before PCM reaches the platform audio sink.
 * The scratch buffer only grows when Media3 presents a larger block; steady-state processing does
 * not allocate per audio block.
 */
internal class Media3LiveDspAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var effectStates: Map<LiveEffectType, LiveEffectState> =
        LiveEffectType.entries.associateWith { LiveEffectState() }

    @Volatile
    private var rack: LiveDspRack? = null

    private var scratch = FloatArray(0)

    fun setEffect(type: LiveEffectType, state: LiveEffectState) {
        val normalized = state.normalized()
        effectStates = effectStates + (type to normalized)
        rack?.setEffect(type, normalized)
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "Live DSP requires PCM 16-bit or float input",
                inputAudioFormat,
            )
        }

        rack = LiveDspRack(
            sampleRate = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
        ).also { configured ->
            effectStates.forEach(configured::setEffect)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val inputBytes = inputBuffer.remaining()
        val frameCount = inputBytes / bytesPerFrame
        val processedBytes = frameCount * bytesPerFrame
        val sampleCount = frameCount * inputAudioFormat.channelCount
        ensureScratchCapacity(sampleCount)

        val originalOrder = inputBuffer.order()
        inputBuffer.order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                repeat(sampleCount) { index ->
                    scratch[index] = inputBuffer.short / 32768f
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                repeat(sampleCount) { index ->
                    scratch[index] = inputBuffer.float
                }
            }
        }

        rack?.processInterleaved(scratch, frameCount)

        val output = replaceOutputBuffer(inputBytes).order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                repeat(sampleCount) { index ->
                    val pcm = (scratch[index].coerceIn(-1f, 1f) * 32767f)
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.putShort(pcm.toShort())
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                repeat(sampleCount) { index ->
                    output.putFloat(scratch[index].coerceIn(-1f, 1f))
                }
            }
        }

        if (processedBytes < inputBytes) {
            // AudioProcessor input should always be frame aligned. Preserve any unexpected tail
            // byte-for-byte instead of silently dropping it.
            while (inputBuffer.hasRemaining()) {
                output.put(inputBuffer.get())
            }
        }
        inputBuffer.order(originalOrder)
        output.flip()
    }

    override fun onReset() {
        rack = null
        scratch = FloatArray(0)
    }

    private fun ensureScratchCapacity(sampleCount: Int) {
        if (scratch.size < sampleCount) {
            scratch = FloatArray(sampleCount)
        }
    }
}
