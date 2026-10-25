package com.shade.panel.music

import androidx.media3.common.Player
import java.util.UUID

/**
 * Turns the phone player into listens — the same rules as Nation's ListenTracker:
 * time only accrues while audio advances (ExoPlayer's isPlaying is false while
 * buffering), seeks fall back to wall time, under 5 s is a skip, starting the
 * same song over is a new listen, and an open listen is saved every 30 s so a
 * killed process loses at most that much. Call observe() about once a second
 * and on every track change, on the player's thread.
 */
class ListenRecorder(private val db: ListenDb) {
    private class Open(
        val id: String,
        val key: String,
        val meta: SongMeta,
        val startedAt: Long,
    ) {
        var lastPlaying = startedAt
        var lastSave = startedAt
        var playedMs = 0L
        var lastPosition = 0L
    }

    data class SongMeta(
        val title: String,
        val artist: String,
        val album: String,
        val genre: String?,
        val isrc: String?,
        val path: String?,
        val durationMs: Long,
    )

    private var open: Open? = null
    private var lastTick = 0L

    fun observe(player: Player) {
        val now = System.currentTimeMillis()
        var step = now - lastTick
        lastTick = now
        // Ticks are ~1 s apart; a longer gap is the process being frozen, not music.
        if (step < 0 || step > MAX_STEP_MS) step = 0

        val item = player.currentMediaItem
        val key = item?.mediaId
        val position = player.currentPosition
        val playing = player.isPlaying
        val duration = player.duration.takeIf { it > 0 } ?: 0L

        open?.let { o ->
            val restarted = key == o.key && position < 5_000 && o.lastPosition > maxOf(30_000, duration / 2)
            if (key != o.key || restarted || (!playing && now - o.lastPlaying > STALE_MS)) close()
        }

        if (open == null && item != null && key != null && playing) {
            val m = item.mediaMetadata
            val extras = m.extras
            open = Open(
                id = UUID.randomUUID().toString().replace("-", ""),
                key = key,
                meta = SongMeta(
                    title = m.title?.toString() ?: "",
                    artist = m.artist?.toString() ?: "Desconocido",
                    album = m.albumTitle?.toString() ?: "",
                    genre = m.genre?.toString(),
                    isrc = extras?.getString(EXTRA_ISRC),
                    path = extras?.getString(EXTRA_PATH),
                    durationMs = m.durationMs ?: duration,
                ),
                startedAt = now,
            ).also { it.lastPosition = position }
            step = 0
        }

        val o = open ?: return
        val previous = o.lastPosition
        o.lastPosition = position
        if (playing) {
            var ms = step
            val moved = position - previous
            // Count what the position advanced; jumps either way (seeks) get wall time.
            if (moved in 0 until ms + 1_000) ms = moved
            o.playedMs += ms
            o.lastPlaying = now
        }
        if (now - o.lastSave > SAVE_EVERY_MS && o.playedMs >= MIN_PLAYED_MS) {
            o.lastSave = now
            save(o)
        }
    }

    /** Ends the open listen (stop, service shutting down). */
    fun close() {
        open?.let { if (it.playedMs >= MIN_PLAYED_MS) save(it) }
        open = null
    }

    private fun save(o: Open) {
        val m = o.meta
        runCatching {
            db.upsert(
                Listen(
                    id = o.id,
                    listener = db.listenerId,
                    device = db.deviceId,
                    source = "local",
                    startedAt = o.startedAt,
                    endedAt = o.lastPlaying,
                    playedMs = o.playedMs,
                    durationMs = m.durationMs,
                    title = m.title,
                    artist = m.artist,
                    album = m.album,
                    genre = m.genre,
                    isrc = m.isrc,
                    path = m.path,
                    trackKey = trackKeyOf(m.isrc, m.artist, m.title),
                ),
            )
        }
    }

    companion object {
        const val EXTRA_ISRC = "isrc"
        const val EXTRA_PATH = "path"
        private const val MIN_PLAYED_MS = 5_000L
        private const val MAX_STEP_MS = 2_500L
        private const val SAVE_EVERY_MS = 30_000L
        private const val STALE_MS = 30 * 60_000L
    }
}
