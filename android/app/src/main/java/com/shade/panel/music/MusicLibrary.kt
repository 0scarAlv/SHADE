package com.shade.panel.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer

data class Song(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val genre: String?,
    val isrc: String?,
) {
    val artUri: Uri get() = ContentUris.withAppendedId(ALBUM_ART, albumId)

    // Folded once for search and for the cross-device song key.
    val searchKey: String by lazy { fold("$title $artist $album") }

    companion object {
        private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

/**
 * Which song this is across devices: the ISRC when the file has one, else
 * folded artist + title. Must match Nation's ListenTracker.TrackKeyOf.
 */
fun trackKeyOf(isrc: String?, artist: String, title: String): String =
    if (!isrc.isNullOrEmpty()) "isrc:$isrc" else "tag:" + fold(artist.trim()) + "|" + fold(title.trim())

/** Lowercase without accents ("Rocío" → "rocio"), like Nation's TrackSearch.Fold. */
fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

private val DIACRITICS = Regex("\\p{Mn}+")

// The phone's music, from MediaStore (which already knows every file and its
// genre), plus the ISRC read from each file's tags once and cached by size+mtime.
class MusicLibrary private constructor(private val context: Context) {
    private val db = ListenDb.get(context)
    private val mutex = Mutex()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Scans unless a scan is already running (callers stack up while the first one reads tags). */
    suspend fun refreshIfIdle() {
        if (!mutex.isLocked) refresh()
    }

    suspend fun refresh() = mutex.withLock {
        _loading.value = true
        try {
            withContext(Dispatchers.IO) { _songs.value = scan() }
        } finally {
            _loading.value = false
        }
    }

    fun byId(id: Long): Song? = _songs.value.firstOrNull { it.id == id }

    private fun scan(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()

        data class Row(val song: Song, val size: Long, val modified: Long)
        val rows = ArrayList<Row>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { c ->
            val genreCol = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val artist = c.getString(3)?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Desconocido"
                rows += Row(
                    Song(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        path = c.getString(1) ?: "",
                        title = c.getString(2) ?: "",
                        artist = artist,
                        album = c.getString(4) ?: "",
                        albumId = c.getLong(5),
                        durationMs = c.getLong(6),
                        genre = if (genreCol >= 0) c.getString(genreCol) else null,
                        isrc = null,
                    ),
                    size = c.getLong(7),
                    modified = c.getLong(8),
                )
            }
        }

        // Tags for new or changed files only; the first run reads every file once.
        val cache = db.loadTagCache(TagReader.VERSION)
        val fresh = ArrayList<Pair<String, ListenDb.CachedTags>>()
        val started = System.currentTimeMillis()
        val songs = rows.map { row ->
            val s = row.song
            val hit = cache[s.path]?.takeIf { it.size == row.size && it.modified == row.modified }
            val tags = hit ?: run {
                val read = runCatching { context.contentResolver.openInputStream(s.uri)?.use { TagReader.read(it) } }.getOrNull()
                ListenDb.CachedTags(row.size, row.modified, read?.isrc, read?.genre).also { fresh += s.path to it }
            }
            s.copy(isrc = tags.isrc, genre = tags.genre ?: s.genre)
        }
        if (fresh.isNotEmpty()) db.saveTags(fresh)
        Log.i(TAG, "library: ${songs.size} songs, ${fresh.size} tag reads in ${System.currentTimeMillis() - started} ms, ${songs.count { it.isrc != null }} with ISRC")
        return songs
    }

    companion object {
        private const val TAG = "ShadeMusic"

        @Volatile private var instance: MusicLibrary? = null

        fun get(context: Context): MusicLibrary =
            instance ?: synchronized(this) { instance ?: MusicLibrary(context.applicationContext).also { instance = it } }
    }
}
