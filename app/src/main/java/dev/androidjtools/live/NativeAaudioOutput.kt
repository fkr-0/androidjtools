package dev.androidjtools.live

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val NATIVE_AAUDIO_SAMPLE_RATE = 48_000
private const val NATIVE_AAUDIO_CHANNEL_COUNT = 2

data class NativeAaudioStats(
    val framesPerBurst: Long,
    val bufferSizeFrames: Long,
    val xRunCount: Long,
    val underrunFramesA: Long,
    val underrunFramesB: Long,
    val droppedFramesA: Long,
    val droppedFramesB: Long,
)

internal object NativeAaudioBridge {
    @Volatile
    private var loaded: Boolean? = null

    fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        loaded?.let { return it }
        return synchronized(this) {
            loaded ?: runCatching {
                System.loadLibrary("androidjtools_live")
                true
            }.getOrDefault(false).also { loaded = it }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun probe(): Boolean {
        if (!isAvailable()) return false
        val handle = nativeCreateMixer(NATIVE_AAUDIO_SAMPLE_RATE, NATIVE_AAUDIO_CHANNEL_COUNT)
        if (handle == 0L) return false
        nativeClose(handle)
        return true
    }

    external fun nativeCreateMixer(sampleRate: Int, channelCount: Int): Long
    external fun nativeSetDeckPlaying(handle: Long, deck: Int, playing: Boolean): Boolean
    external fun nativeSetDeckGain(handle: Long, deck: Int, gain: Float)
    external fun nativeFlushDeck(handle: Long, deck: Int)
    external fun nativeWriteDeckFloats(handle: Long, deck: Int, buffer: ByteBuffer, sampleCount: Int): Int
    external fun nativeStats(handle: Long): LongArray
    external fun nativeClose(handle: Long)
}

class NativeAaudioMixer private constructor(
    private var handle: Long,
) {
    fun setPlaying(deck: DeckId, playing: Boolean): Boolean {
        val current = handle
        if (current == 0L) return false
        return NativeAaudioBridge.nativeSetDeckPlaying(current, deck.ordinal, playing)
    }

    fun setGain(deck: DeckId, gain: Float) {
        val current = handle
        if (current != 0L) {
            NativeAaudioBridge.nativeSetDeckGain(current, deck.ordinal, gain.coerceIn(0f, 1f))
        }
    }

    fun flush(deck: DeckId) {
        val current = handle
        if (current != 0L) NativeAaudioBridge.nativeFlushDeck(current, deck.ordinal)
    }

    fun write(deck: DeckId, buffer: ByteBuffer, sampleCount: Int): Int {
        val current = handle
        if (current == 0L) return 0
        return NativeAaudioBridge.nativeWriteDeckFloats(
            current,
            deck.ordinal,
            buffer,
            sampleCount,
        )
    }

    fun stats(): NativeAaudioStats? {
        val current = handle
        if (current == 0L) return null
        val raw = NativeAaudioBridge.nativeStats(current)
        if (raw.size < 7) return null
        return NativeAaudioStats(
            framesPerBurst = raw[0],
            bufferSizeFrames = raw[1],
            xRunCount = raw[2],
            underrunFramesA = raw[3],
            underrunFramesB = raw[4],
            droppedFramesA = raw[5],
            droppedFramesB = raw[6],
        )
    }

    fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeAaudioBridge.nativeClose(current)
    }

    companion object {
        fun createOrNull(): NativeAaudioMixer? {
            if (!NativeAaudioBridge.isAvailable()) return null
            val handle = NativeAaudioBridge.nativeCreateMixer(
                NATIVE_AAUDIO_SAMPLE_RATE,
                NATIVE_AAUDIO_CHANNEL_COUNT,
            )
            return handle.takeIf { it != 0L }?.let(::NativeAaudioMixer)
        }
    }
}

@OptIn(UnstableApi::class)
internal class NativeAaudioOutputProcessor(
    private val deck: DeckId,
    private val mixer: NativeAaudioMixer,
) : BaseAudioProcessor() {
    @Volatile
    private var effectStates: Map<LiveEffectType, LiveEffectState> =
        LiveEffectType.entries.associateWith { LiveEffectState() }

    private var rack: LiveDspRack? = null
    private var scratch = FloatArray(0)
    private var nativeScratch: ByteBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

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
                "AAudio live output requires PCM 16-bit or float input",
                inputAudioFormat,
            )
        }
        if (inputAudioFormat.sampleRate != NATIVE_AAUDIO_SAMPLE_RATE) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "AAudio processor expected $NATIVE_AAUDIO_SAMPLE_RATE Hz after Sonic",
                inputAudioFormat,
            )
        }
        if (inputAudioFormat.channelCount !in 1..8) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "AAudio live output requires 1-8 input channels",
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
        val sourceChannels = inputAudioFormat.channelCount
        val sourceSamples = frameCount * sourceChannels
        ensureScratchCapacity(sourceSamples)

        val previousOrder = inputBuffer.order()
        inputBuffer.order(ByteOrder.nativeOrder())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(sourceSamples) { index ->
                scratch[index] = inputBuffer.short / 32768f
            }
            C.ENCODING_PCM_FLOAT -> repeat(sourceSamples) { index ->
                scratch[index] = inputBuffer.float
            }
        }
        inputBuffer.order(previousOrder)

        rack?.processInterleaved(scratch, frameCount)

        val nativeSampleCount = frameCount * NATIVE_AAUDIO_CHANNEL_COUNT
        ensureNativeScratchCapacity(nativeSampleCount)
        nativeScratch.clear()
        repeat(frameCount) { frame ->
            val sourceOffset = frame * sourceChannels
            val left = scratch[sourceOffset]
            val right = if (sourceChannels == 1) left else scratch[sourceOffset + 1]
            nativeScratch.putFloat(left)
            nativeScratch.putFloat(right)
        }
        nativeScratch.flip()
        mixer.write(deck, nativeScratch, nativeSampleCount)

        val output = replaceOutputBuffer(inputBytes)
        repeat(inputBytes) { output.put(0) }
        output.flip()
    }

    override fun onFlush() {
        mixer.flush(deck)
    }

    override fun onReset() {
        mixer.flush(deck)
        rack = null
        scratch = FloatArray(0)
        nativeScratch = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    private fun ensureScratchCapacity(sampleCount: Int) {
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
    }

    private fun ensureNativeScratchCapacity(sampleCount: Int) {
        val bytes = sampleCount * Float.SIZE_BYTES
        if (nativeScratch.capacity() < bytes) {
            nativeScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
    }
}

@OptIn(UnstableApi::class)
internal class NativeAaudioProcessorChain(
    nativeOutput: NativeAaudioOutputProcessor,
) : DefaultAudioSink.AudioProcessorChain {
    private val silenceSkipping = SilenceSkippingAudioProcessor()
    private val sonic = SonicAudioProcessor().apply {
        setOutputSampleRateHz(NATIVE_AAUDIO_SAMPLE_RATE)
    }
    private val processors = arrayOf<AudioProcessor>(silenceSkipping, sonic, nativeOutput)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDurationUs: Long): Long =
        sonic.getMediaDuration(playoutDurationUs)

    override fun getSkippedOutputFrameCount(): Long =
        silenceSkipping.skippedFrames
}
