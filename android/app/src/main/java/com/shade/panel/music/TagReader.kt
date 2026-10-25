package com.shade.panel.music

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

// Reads ISRC and GENRE from a file's Vorbis comments — FLAC's VORBIS_COMMENT
// block or an Ogg stream's OpusTags/Vorbis comment packet. MediaStore has the
// genre but not the ISRC, and the ISRC is what lets the phone and Nation agree
// on which song is which. Other formats (m4a) return nothing.
object TagReader {
    /** Bumped whenever parsing changes, so the library re-reads tags cached by an older reader. */
    const val VERSION = 2

    data class Tags(val isrc: String?, val genre: String?)

    // An OpusTags packet carries the cover too (base64); anything past this is not worth reading.
    private const val MAX_COMMENT_BYTES = 12 * 1024 * 1024

    fun read(stream: InputStream): Tags? {
        val input = DataInputStream(stream.buffered(64 * 1024))
        val magic = ByteArray(4)
        input.readFully(magic)
        return when {
            magic.contentEquals("fLaC".toByteArray()) -> readFlac(input)
            magic.contentEquals("OggS".toByteArray()) -> readOgg(input)
            else -> null
        }
    }

    private fun readFlac(input: DataInputStream): Tags? {
        while (true) {
            val header = input.readInt()
            val last = header ushr 31 == 1
            val type = (header ushr 24) and 0x7F
            val length = header and 0xFFFFFF
            if (type == 4) {
                val block = ByteArray(length)
                input.readFully(block)
                return parseComments(block, 0)
            }
            // Pictures and seek tables are skipped without being read into memory.
            skipFully(input, length.toLong())
            if (last) return null
        }
    }

    // Ogg pages carry packets in 255-byte lacing segments; packet 0 is the codec
    // header, packet 1 the comments (OpusTags, or Vorbis' 0x03 "vorbis").
    private fun readOgg(input: DataInputStream): Tags? {
        var packetIndex = 0
        val packet = ByteArrayOutputStream()
        var firstPage = true
        while (true) {
            if (!firstPage) {
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals("OggS".toByteArray())) return null
            }
            firstPage = false
            skipFully(input, 22) // version, type, granule, serial, sequence, crc
            val segments = input.readUnsignedByte()
            val lacing = IntArray(segments) { input.readUnsignedByte() }
            for (size in lacing) {
                if (packetIndex == 1) {
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    packet.write(bytes)
                    if (packet.size() > MAX_COMMENT_BYTES) return null
                } else {
                    skipFully(input, size.toLong())
                }
                // A segment shorter than 255 ends the packet.
                if (size < 255) {
                    if (packetIndex == 1) {
                        val data = packet.toByteArray()
                        val offset = when {
                            data.size >= 8 && String(data, 0, 8, Charsets.US_ASCII) == "OpusTags" -> 8
                            data.size >= 7 && data[0].toInt() == 3 && String(data, 1, 6, Charsets.US_ASCII) == "vorbis" -> 7
                            else -> return null
                        }
                        return parseComments(data, offset)
                    }
                    packetIndex++
                }
            }
        }
    }

    // vendor length + vendor, count, then count × (length + "KEY=value"), all little-endian.
    private fun parseComments(data: ByteArray, start: Int): Tags {
        var pos = start
        fun int32(): Int {
            if (pos + 4 > data.size) throw EOFException()
            val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        var isrc: String? = null
        var genre: String? = null
        runCatching {
            // Not `pos += int32()`: that reads pos before int32() advances it.
            val vendor = int32()
            pos += vendor
            val count = int32()
            repeat(count) {
                val len = int32()
                if (len < 0 || pos + len > data.size) return@runCatching
                // Only short fields matter; don't decode the base64 cover as a string.
                if (len < 512) {
                    val field = String(data, pos, len, Charsets.UTF_8)
                    val eq = field.indexOf('=')
                    if (eq > 0) {
                        val key = field.substring(0, eq).uppercase()
                        val value = field.substring(eq + 1).trim()
                        if (key == "ISRC" && isrc == null && value.isNotEmpty()) isrc = value.uppercase()
                        if (key == "GENRE" && genre == null && value.isNotEmpty()) genre = value
                    }
                }
                pos += len
            }
        }
        return Tags(isrc, genre)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() == -1) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }
}
