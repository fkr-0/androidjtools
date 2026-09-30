package dev.androidjtools.live

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3LiveDspAudioProcessorTest {
    @Test
    fun pcm16GateIsAppliedToDecodedMedia3Frames() {
        val processor = configuredProcessor(C.ENCODING_PCM_16BIT)
        processor.setEffect(
            LiveEffectType.GATE,
            LiveEffectState(enabled = true, mix = 1f, parameter = 0.5f),
        )
        val input = ByteBuffer.allocateDirect(4 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                putShort((0.02f * Short.MAX_VALUE).toInt().toShort())
                putShort((-0.02f * Short.MAX_VALUE).toInt().toShort())
                putShort((0.8f * Short.MAX_VALUE).toInt().toShort())
                putShort((-0.8f * Short.MAX_VALUE).toInt().toShort())
                flip()
            }

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(0, output.short.toInt())
        assertEquals(0, output.short.toInt())
        assertTrue(output.short > 20_000)
        assertTrue(output.short < -20_000)
    }

    @Test
    fun floatPcmPathPreservesFrameShapeAndAppliesGate() {
        val processor = configuredProcessor(C.ENCODING_PCM_FLOAT)
        processor.setEffect(
            LiveEffectType.GATE,
            LiveEffectState(enabled = true, mix = 1f, parameter = 0.5f),
        )
        val input = ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                putFloat(0.01f)
                putFloat(-0.01f)
                putFloat(0.75f)
                putFloat(-0.75f)
                flip()
            }

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(4 * Float.SIZE_BYTES, output.remaining())
        assertEquals(0f, output.float, 0f)
        assertEquals(0f, output.float, 0f)
        assertEquals(0.75f, output.float, 0.0001f)
        assertEquals(-0.75f, output.float, 0.0001f)
    }

    private fun configuredProcessor(encoding: Int): Media3LiveDspAudioProcessor =
        Media3LiveDspAudioProcessor().also { processor ->
            processor.configure(
                AudioProcessor.AudioFormat(
                    48_000,
                    2,
                    encoding,
                ),
            )
            processor.flush()
        }
}
