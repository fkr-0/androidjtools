package dev.androidjtools.live

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Routes external Android MIDI device output directly into [LivePerformanceController].
 *
 * Parsing and dispatch are synchronous and tiny; no coroutine hop is introduced between a received
 * MIDI packet and the control-state update.
 */
class AndroidMidiInputRouter(
    context: Context,
    private val controller: LivePerformanceController,
) : Closeable {
    private data class OpenDevice(
        val device: MidiDevice,
        val ports: List<MidiOutputPort>,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val openDevices = mutableMapOf<Int, OpenDevice>()
    private val openingDeviceIds = mutableSetOf<Int>()
    private val mutableDeviceCount = MutableStateFlow(0)
    val deviceCount: StateFlow<Int> = mutableDeviceCount.asStateFlow()
    private var started = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parseMidiPacket(data, offset, count) { type, channel, number, value ->
                controller.handleMidi(type, channel, number, value)
            }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            closeDevice(info.id)
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (started) return
        started = true
        // Keep API 28 compatibility. Newer transport-specific MIDI APIs can replace this branch
        // once the project's min/target API policy moves forward.
        midiManager.registerDeviceCallback(callback, handler)
        midiManager.devices.forEach(::open)
    }

    override fun close() {
        if (started) {
            midiManager.unregisterDeviceCallback(callback)
            started = false
        }
        openDevices.keys.toList().forEach(::closeDevice)
        openingDeviceIds.clear()
        mutableDeviceCount.value = 0
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.id in openDevices || !openingDeviceIds.add(info.id)) return
        midiManager.openDevice(
            info,
            { device ->
                openingDeviceIds.remove(info.id)
                if (device == null || !started) {
                    device?.close()
                    return@openDevice
                }
                val ports = info.ports
                    .asSequence()
                    .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                    .mapNotNull { port -> device.openOutputPort(port.portNumber) }
                    .onEach { port -> port.connect(receiver) }
                    .toList()
                openDevices[info.id] = OpenDevice(device, ports)
                mutableDeviceCount.value = openDevices.size
            },
            handler,
        )
    }

    private fun closeDevice(deviceId: Int) {
        val open = openDevices.remove(deviceId) ?: return
        open.ports.forEach { port ->
            runCatching { port.disconnect(receiver) }
            runCatching { port.close() }
        }
        runCatching { open.device.close() }
        mutableDeviceCount.value = openDevices.size
    }
}

internal fun parseMidiPacket(
    data: ByteArray,
    offset: Int,
    count: Int,
    onMessage: (MidiMessageType, Int, Int, Int) -> Unit,
) {
    val end = (offset + count).coerceAtMost(data.size)
    var index = offset.coerceAtLeast(0)
    while (index < end) {
        val status = data[index].toInt() and 0xff
        if (status < 0x80) {
            index++
            continue
        }
        val command = status and 0xf0
        val channel = status and 0x0f
        val bytes = when (command) {
            0x80, 0x90, 0xB0 -> 3
            0xC0, 0xD0 -> 2
            else -> 1
        }
        if (index + bytes > end) break
        if (bytes == 3) {
            val number = data[index + 1].toInt() and 0x7f
            val rawValue = data[index + 2].toInt() and 0x7f
            when (command) {
                0x80 -> onMessage(MidiMessageType.NOTE, channel, number, 0)
                0x90 -> onMessage(MidiMessageType.NOTE, channel, number, rawValue)
                0xB0 -> onMessage(MidiMessageType.CONTROL_CHANGE, channel, number, rawValue)
            }
        }
        index += bytes
    }
}
