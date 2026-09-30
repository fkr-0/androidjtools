package dev.androidjtools.live

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiPacketParserTest {
    @Test
    fun parsesNoteOnNoteOffAndControlChangeWithoutAllocationProtocolState() {
        val messages = mutableListOf<List<Int>>()
        val packet = byteArrayOf(
            0x90.toByte(), 36, 100,
            0x80.toByte(), 36, 64,
            0xB2.toByte(), 1, 127.toByte(),
        )

        parseMidiPacket(packet, 0, packet.size) { type, channel, number, value ->
            messages += listOf(type.ordinal, channel, number, value)
        }

        assertEquals(
            listOf(
                listOf(MidiMessageType.NOTE.ordinal, 0, 36, 100),
                listOf(MidiMessageType.NOTE.ordinal, 0, 36, 0),
                listOf(MidiMessageType.CONTROL_CHANGE.ordinal, 2, 1, 127),
            ),
            messages,
        )
    }

    @Test
    fun ignoresUnsupportedStatusAndIncompleteTail() {
        val messages = mutableListOf<Int>()
        val packet = byteArrayOf(
            0xF8.toByte(),
            0x90.toByte(), 40, 127.toByte(),
            0xB0.toByte(), 1,
        )

        parseMidiPacket(packet, 0, packet.size) { _, _, number, _ -> messages += number }

        assertEquals(listOf(40), messages)
    }
}
