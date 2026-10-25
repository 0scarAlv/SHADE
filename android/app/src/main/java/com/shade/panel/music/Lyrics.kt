package com.shade.panel.music

import com.shade.panel.data.LyricsLine

/** A song's embedded lyrics: timed lines when the tag is LRC, else plain text. */
data class Lyrics(val lines: List<LyricsLine> = emptyList(), val plain: String? = null) {
    val synced get() = lines.isNotEmpty()
    val isEmpty get() = lines.isEmpty() && plain.isNullOrBlank()

    /** The line showing at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

    companion object {
        val NONE = Lyrics()

        // [mm:ss], [mm:ss.xx] or [mm:ss.xxx]; a line may carry several stamps.
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        fun parse(text: String?): Lyrics {
            if (text.isNullOrBlank()) return NONE
            val lines = ArrayList<LyricsLine>()
            for (raw in text.lineSequence()) {
                val stamps = STAMP.findAll(raw).toList()
                // Only leading stamps count; metadata tags like [ar:…] never match.
                if (stamps.isEmpty() || stamps.first().range.first != 0) continue
                val words = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val (min, sec, frac) = m.destructured
                    val fracMs = when (frac.length) {
                        0 -> 0
                        1 -> frac.toInt() * 100
                        2 -> frac.toInt() * 10
                        else -> frac.toInt()
                    }
                    lines += LyricsLine((min.toLong() * 60 + sec.toLong()) * 1000 + fracMs, words)
                }
            }
            return if (lines.isNotEmpty()) Lyrics(lines.sortedBy { it.timeMs }) else Lyrics(plain = text.trim())
        }
    }
}
