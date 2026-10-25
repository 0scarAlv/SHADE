package com.shade.panel.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

// One stretch of listening to one song on this phone. Same shape and rules as
// Nation's journal (Stats/ListenLog.cs) so both sides merge by id without
// double counting: append-only, times in Unix ms UTC.
data class Listen(
    val id: String,
    val listener: String,
    val device: String,
    val source: String,
    val startedAt: Long,
    val endedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val isrc: String?,
    val path: String?,
    val trackKey: String,
    val music: Boolean = true,
    // Only meaningful for rows read back from the journal (sync cursors).
    val updatedAt: Long = 0,
)

// Plain SQLiteOpenHelper rather than Room: no annotation processor (KSP would
// have to track the Kotlin compiler version exactly) for a handful of queries.
class ListenDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "listens.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Matches Nation's schema v2 column for column, plus the tag cache the
        // library uses so ISRCs are read from each file only once.
        db.execSQL(
            """
            CREATE TABLE listen (
                id          TEXT PRIMARY KEY,
                device      TEXT NOT NULL,
                source      TEXT NOT NULL,
                started_at  INTEGER NOT NULL,
                ended_at    INTEGER NOT NULL,
                played_ms   INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                title       TEXT NOT NULL,
                artist      TEXT NOT NULL,
                album       TEXT NOT NULL,
                genre       TEXT,
                isrc        TEXT,
                path        TEXT,
                track_key   TEXT NOT NULL,
                music       INTEGER NOT NULL DEFAULT 1,
                updated_at  INTEGER NOT NULL,
                listener    TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX listen_started ON listen(started_at)")
        db.execSQL("CREATE INDEX listen_updated ON listen(updated_at)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE song_tags (
                path     TEXT PRIMARY KEY,
                size     INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                isrc     TEXT,
                genre    TEXT
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // Who is listening on this phone. Until the phone is paired with Nation the
    // listener is a local placeholder; pairing replaces it on every row.
    @Volatile private var listener: String? = null
    val listenerId: String get() = listener ?: metaOrCreate("listener").also { listener = it }
    val deviceId: String by lazy { metaOrCreate("device") }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String?) {
        if (value == null) writableDatabase.delete("meta", "key = ?", arrayOf(key))
        else writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Pairing: this phone's listens become [owner]'s, the person Nation's journal belongs to. */
    fun adoptListener(owner: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE listen SET listener = ?, updated_at = ? WHERE listener = ?", arrayOf<Any>(owner, System.currentTimeMillis(), listenerId))
            setMeta("listener", owner)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        listener = owner
    }

    /** Empties the journal and every sync cursor (meta keys starting with [cursorPrefixes]); who the phone belongs to stays. */
    fun clearListens(cursorPrefixes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("listen", null, null)
            cursorPrefixes.forEach { db.delete("meta", "key LIKE ?", arrayOf("$it%")) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Rows changed after [since] (local clock) that didn't come from [excludeDevice], oldest first. */
    fun changedSince(since: Long, excludeDevice: String, limit: Int): List<Listen> =
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM listen WHERE device != ? AND updated_at > ? ORDER BY updated_at LIMIT ?",
            arrayOf(excludeDevice, since.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toListen()) } }

    /**
     * Rows from Nation (the PC's listens, and this phone's own coming back).
     * Idempotent like Nation's merge: a known row only ever grows.
     */
    fun mergeRemote(rows: List<Listen>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (l in rows) {
                db.execSQL(
                    """
                    INSERT INTO listen (id, listener, device, source, started_at, ended_at, played_ms, duration_ms,
                                        title, artist, album, genre, isrc, path, track_key, music, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        ended_at = MAX(ended_at, excluded.ended_at), played_ms = MAX(played_ms, excluded.played_ms)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        l.id, l.listener, l.device, l.source, l.startedAt, l.endedAt, l.playedMs, l.durationMs,
                        l.title, l.artist, l.album, l.genre, l.isrc, l.path, l.trackKey, if (l.music) 1 else 0, now,
                    ),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.Cursor.toListen() = Listen(
        id = getString(0), listener = getString(1), device = getString(2), source = getString(3),
        startedAt = getLong(4), endedAt = getLong(5), playedMs = getLong(6), durationMs = getLong(7),
        title = getString(8), artist = getString(9), album = getString(10),
        genre = getStringOrNull(11), isrc = getStringOrNull(12), path = getStringOrNull(13),
        trackKey = getString(14), music = getInt(15) != 0, updatedAt = getLong(16),
    )

    private fun metaOrCreate(key: String): String {
        val db = writableDatabase
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        return id
    }

    fun upsert(l: Listen) {
        val values = ContentValues().apply {
            put("id", l.id)
            put("listener", l.listener)
            put("device", l.device)
            put("source", l.source)
            put("started_at", l.startedAt)
            put("ended_at", l.endedAt)
            put("played_ms", l.playedMs)
            put("duration_ms", l.durationMs)
            put("title", l.title)
            put("artist", l.artist)
            put("album", l.album)
            put("genre", l.genre)
            put("isrc", l.isrc)
            put("path", l.path)
            put("track_key", l.trackKey)
            put("music", 1)
            put("updated_at", System.currentTimeMillis())
        }
        // An open listen is saved again as it grows; only these three change.
        val db = writableDatabase
        if (db.insertWithOnConflict("listen", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            db.update(
                "listen",
                ContentValues().apply {
                    put("ended_at", l.endedAt)
                    put("played_ms", l.playedMs)
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(l.id),
            )
        }
    }

    data class CachedTags(val size: Long, val modified: Long, val isrc: String?, val genre: String?)

    fun loadTagCache(readerVersion: Int): Map<String, CachedTags> {
        val db = writableDatabase
        val stored = db.rawQuery("SELECT value FROM meta WHERE key = 'tag_reader'", null).use { c ->
            if (c.moveToFirst()) c.getString(0).toIntOrNull() else null
        }
        if (stored != readerVersion) {
            db.delete("song_tags", null, null)
            db.insertWithOnConflict(
                "meta", null,
                ContentValues().apply { put("key", "tag_reader"); put("value", readerVersion.toString()) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        val map = HashMap<String, CachedTags>()
        readableDatabase.rawQuery("SELECT path, size, modified, isrc, genre FROM song_tags", null).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = CachedTags(c.getLong(1), c.getLong(2), c.getStringOrNull(3), c.getStringOrNull(4))
            }
        }
        return map
    }

    fun saveTags(rows: List<Pair<String, CachedTags>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((path, t) in rows) {
                db.insertWithOnConflict(
                    "song_tags",
                    null,
                    ContentValues().apply {
                        put("path", path)
                        put("size", t.size)
                        put("modified", t.modified)
                        put("isrc", t.isrc)
                        put("genre", t.genre)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        private const val VERSION = 1
        private const val COLUMNS = "id, listener, device, source, started_at, ended_at, played_ms, duration_ms, " +
            "title, artist, album, genre, isrc, path, track_key, music, updated_at"

        @Volatile private var instance: ListenDb? = null

        fun get(context: Context): ListenDb =
            instance ?: synchronized(this) { instance ?: ListenDb(context).also { instance = it } }
    }
}
