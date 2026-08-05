package com.shade.panel.data

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

// RFCOMM is a raw byte stream with no message boundaries (unlike WebSocket), so
// every message is wrapped in a 5-byte header: [1-byte type][4-byte BE length].
// Must match src/Shade.Agent/Streaming/FrameCodec.cs exactly. Callers run these
// from Dispatchers.IO — the reads/writes here are plain blocking java.io calls.
data class Frame(val type: Int, val payload: ByteArray)

object FrameCodec {
    const val TYPE_JSON = 0x01
    const val TYPE_ART = 0x02

    // Guards against a corrupted length field causing a runaway allocation.
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    fun writeFrame(output: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteArray(5)
        header[0] = type.toByte()
        header[1] = (payload.size ushr 24).toByte()
        header[2] = (payload.size ushr 16).toByte()
        header[3] = (payload.size ushr 8).toByte()
        header[4] = payload.size.toByte()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    // Returns null when the stream ended cleanly right at a frame boundary
    // (i.e. the connection closed).
    fun readFrame(input: InputStream): Frame? {
        val header = readExactly(input, 5) ?: return null
        val type = header[0].toInt() and 0xFF
        val length = ((header[1].toInt() and 0xFF) shl 24) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or
            (header[4].toInt() and 0xFF)
        check(length in 0..MAX_FRAME_BYTES) { "Frame Bluetooth de $length bytes excede el máximo permitido" }

        val payload = if (length == 0) {
            ByteArray(0)
        } else {
            readExactly(input, length) ?: throw EOFException("Conexión Bluetooth cerrada a mitad de un frame.")
        }
        return Frame(type, payload)
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buf, read, size - read)
            if (n == -1) {
                if (read == 0) return null
                throw EOFException("Conexión Bluetooth cerrada a mitad de un frame.")
            }
            read += n
        }
        return buf
    }
}
