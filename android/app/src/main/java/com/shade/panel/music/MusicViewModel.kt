package com.shade.panel.music

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: Uri? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val hasTrack get() = mediaId != null
}

/** Order of the song list (and so of the play order built from it). */
enum class SongSort(val label: String) {
    TITLE("A–Z"),
    ALBUM("ÁLBUM"),
    RECENT("RECIENTES");

    fun next() = entries[(ordinal + 1) % entries.size]

    fun sorted(all: List<Song>): List<Song> = when (this) {
        TITLE -> all.sortedBy { it.titleKey }
        ALBUM -> all.sortedWith(compareBy<Song> { it.albumKey }.thenBy { it.trackNo }.thenBy { it.titleKey })
        RECENT -> all.sortedWith(compareByDescending<Song> { it.dateAdded }.thenBy { it.titleKey })
    }
}

/** One upcoming item of the player's list; [queued] = added by hand ("a la cola"). */
data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artUri: Uri?,
    val queued: Boolean,
)

// The music player's side of playback: talks to PlaybackService through a
// MediaController, so playback lives in the service and survives the screen.
//
// Shuffle is done here, not with ExoPlayer's shuffle mode: the list itself is
// reordered, so "add to queue" can always mean "right after the current song
// and whatever was queued before it", shuffled or not.
class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val library = MusicLibrary.get(app)
    val loading: StateFlow<Boolean> = library.loading

    private val prefs = app.getSharedPreferences("shade_music", Context.MODE_PRIVATE)

    private val _sort = MutableStateFlow(
        runCatching { SongSort.valueOf(prefs.getString(KEY_SORT, null) ?: "") }.getOrDefault(SongSort.TITLE),
    )
    val sort: StateFlow<SongSort> = _sort.asStateFlow()

    // The library in the chosen order; the list shown and the play order both come from here.
    val songs: StateFlow<List<Song>> = combine(library.songs, _sort) { all, sort -> sort.sorted(all) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var shuffle = prefs.getBoolean(KEY_SHUFFLE, false)

    private val _now = MutableStateFlow(NowPlaying(shuffle = shuffle))
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    val upNext: StateFlow<List<QueueEntry>> = _upNext.asStateFlow()

    private val _lyrics = MutableStateFlow(Lyrics.NONE)
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()
    private val lyricsCache = LruCache<String, Lyrics>(24)
    private var lyricsFor: String? = null

    private val _statsPeriod = MutableStateFlow(StatsPeriod.SEMANA)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()
    private val _stats = MutableStateFlow(StatsSummary.EMPTY)
    val stats: StateFlow<StatsSummary> = _stats.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var saver: Job? = null
    private var restored = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) publishQueue(player)
            if (restored) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) saveQueue(player)
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) savePosition(player)
                if (player.isPlaying && saver?.isActive != true) {
                    saver = viewModelScope.launch {
                        while (isActive && controller?.isPlaying == true) {
                            delay(SAVE_EVERY_MS)
                            controller?.let(::savePosition)
                        }
                    }
                }
            }
        }
    }

    init {
        // Once the library is in, bring back the last song (paused) if nothing is loaded yet.
        viewModelScope.launch {
            library.songs.collect { if (it.isNotEmpty()) tryRestore() }
        }
        // New rows from Nation (or this phone's own, pushed) → re-read the stats.
        viewModelScope.launch {
            ListenSync.state.map { it.revision }.distinctUntilChanged().collect { refreshStats() }
        }
    }

    /** Call once the audio permission is granted. */
    fun start() {
        if (songs.value.isEmpty()) viewModelScope.launch { library.refreshIfIdle() }
        if (controllerFuture != null) return
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(app, token).buildAsync().also { future ->
            future.addListener({
                val c = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = c
                // An older build used ExoPlayer's shuffle; ours lives in the list order.
                c.shuffleModeEnabled = false
                c.addListener(listener)
                publish(c)
                publishQueue(c)
                tryRestore()
            }, MoreExecutors.directExecutor())
        }
    }

    fun setSort(sort: SongSort) {
        _sort.value = sort
        prefs.edit { putString(KEY_SORT, sort.name) }
    }

    // ---- last-song memory

    // Puts the saved queue back into the player, paused at the saved position.
    // Runs once, when both the library and the controller are ready; a service that
    // already holds a session (queue not empty) is left untouched.
    private fun tryRestore() {
        if (restored) return
        val c = controller ?: return
        val all = library.songs.value
        if (all.isEmpty()) return
        restored = true
        if (c.mediaItemCount > 0) return
        val lastId = prefs.getLong(KEY_LAST_ID, -1L)
        if (lastId < 0) return
        val byId = all.associateBy { it.id }
        if (byId[lastId] == null) return
        val items = ArrayList<MediaItem>()
        var start = -1
        val saved = prefs.getString(KEY_QUEUE, null)
        val savedIndex = prefs.getInt(KEY_QUEUE_INDEX, -1)
        if (!saved.isNullOrEmpty()) {
            saved.split(',').forEachIndexed { i, token ->
                val queued = token.endsWith("q")
                val song = byId[token.removeSuffix("q").toLongOrNull() ?: return@forEachIndexed] ?: return@forEachIndexed
                if (i == savedIndex && song.id == lastId) start = items.size
                items += song.toMediaItem(queued = queued)
            }
        }
        if (start < 0) {
            // No usable saved queue: fall back to the library around the song.
            items.clear()
            val ordered = songs.value.ifEmpty { all }
            start = ordered.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
            ordered.mapTo(items) { it.toMediaItem() }
        }
        c.setMediaItems(items, start, prefs.getLong(KEY_LAST_POS, 0L).coerceAtLeast(0))
        c.prepare()
        c.pause()
    }

    private fun saveQueue(p: Player) {
        if (p.currentMediaItem == null) return
        val sb = StringBuilder()
        for (i in 0 until p.mediaItemCount) {
            if (i > 0) sb.append(',')
            val item = p.getMediaItemAt(i)
            sb.append(item.mediaId)
            if (item.isQueued) sb.append('q')
        }
        prefs.edit {
            putString(KEY_QUEUE, sb.toString())
            putInt(KEY_QUEUE_INDEX, p.currentMediaItemIndex)
        }
    }

    private fun savePosition(p: Player) {
        val id = p.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        prefs.edit {
            putLong(KEY_LAST_ID, id)
            putLong(KEY_LAST_POS, p.currentPosition.coerceAtLeast(0))
            putInt(KEY_QUEUE_INDEX, p.currentMediaItemIndex)
        }
    }

    /** Call when the app goes to the background. */
    fun saveState() {
        val c = controller ?: return
        if (!restored) return
        saveQueue(c)
        savePosition(c)
    }

    fun setStatsPeriod(period: StatsPeriod) {
        _statsPeriod.value = period
        refreshStats()
    }

    /** Re-reads the journal; the open listen is saved every 30 s, so that's as fresh as it gets. */
    fun refreshStats() {
        val period = _statsPeriod.value
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { ListenStats.query(ListenDb.get(getApplication()), period) }
            if (_statsPeriod.value == period) _stats.value = summary
        }
    }

    fun rescan() {
        viewModelScope.launch { library.refresh() }
    }

    /** Plays [song] with the library around it; what was queued by hand stays next. */
    fun play(song: Song) {
        val c = controller ?: return
        val queued = queuedAfterCurrent(c)
        val all = songs.value
        val items = ArrayList<MediaItem>(all.size + queued.size)
        val start: Int
        if (shuffle) {
            items += song.toMediaItem()
            items += queued
            items += all.filter { it.id != song.id }.shuffled().map { it.toMediaItem() }
            start = 0
        } else {
            val at = all.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            all.subList(0, at).mapTo(items) { it.toMediaItem() }
            items += song.toMediaItem()
            items += queued
            all.subList((at + 1).coerceAtMost(all.size), all.size).mapTo(items) { it.toMediaItem() }
            start = at
        }
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    fun shuffleAll() {
        val all = songs.value
        if (all.isEmpty()) return
        setShuffle(true, rebuild = false)
        play(all.random())
    }

    /** Swipe-left on a song: plays after the current one and anything queued before. */
    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0 || c.currentMediaItem == null) {
            play(song)
            return
        }
        val at = c.currentMediaItemIndex + 1 + queuedAfterCurrent(c).size
        c.addMediaItem(at.coerceAtMost(c.mediaItemCount), song.toMediaItem(queued = true))
    }

    fun removeAt(index: Int) {
        controller?.takeIf { index in 0 until it.mediaItemCount && index != it.currentMediaItemIndex }?.removeMediaItem(index)
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0)
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    // Skipping means "play that one", even from pause.
    fun next() = skip { it.seekToNextMediaItem() }

    // Like most players: past the first seconds "previous" restarts the song.
    fun previous() = skip { it.seekToPrevious() }

    // The swipe always means the song before, never "restart this one".
    fun previousTrack() = skip { it.seekToPreviousMediaItem() }

    private inline fun skip(move: (MediaController) -> Unit) {
        val c = controller ?: return
        move(c)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        if (c.duration > 0) c.seekTo((c.duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun toggleShuffle() = setShuffle(!shuffle, rebuild = true)

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // Reorders everything but the current song (which keeps playing untouched)
    // and the hand-queued songs right after it.
    private fun setShuffle(on: Boolean, rebuild: Boolean) {
        shuffle = on
        prefs.edit { putBoolean(KEY_SHUFFLE, on) }
        _now.update { it.copy(shuffle = on) }
        val c = controller ?: return
        if (!rebuild || c.currentMediaItem == null) return
        val currentId = c.currentMediaItem?.mediaId?.toLongOrNull()
        val queued = queuedAfterCurrent(c)
        val all = songs.value
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
        if (on) {
            c.addMediaItems(1, queued + all.filter { it.id != currentId }.shuffled().map { it.toMediaItem() })
        } else {
            val at = all.indexOfFirst { it.id == currentId }
            if (at < 0) {
                c.addMediaItems(1, queued + all.map { it.toMediaItem() })
            } else {
                c.addMediaItems(1, queued + all.subList(at + 1, all.size).map { it.toMediaItem() })
                c.addMediaItems(0, all.subList(0, at).map { it.toMediaItem() })
            }
        }
    }

    private fun queuedAfterCurrent(p: Player): List<MediaItem> {
        if (p.currentMediaItem == null) return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in p.currentMediaItemIndex + 1 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            if (!item.isQueued) break
            out += item
        }
        return out
    }

    private fun publish(p: Player) {
        val item = p.currentMediaItem
        val m = item?.mediaMetadata
        _now.update {
            NowPlaying(
                mediaId = item?.mediaId,
                title = m?.title?.toString() ?: "",
                artist = m?.artist?.toString() ?: "",
                album = m?.albumTitle?.toString() ?: "",
                artUri = m?.artworkUri,
                playing = p.isPlaying,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { it > 0 } ?: (m?.durationMs ?: 0),
                shuffle = shuffle,
                repeatMode = p.repeatMode,
            )
        }
        loadLyrics(item?.mediaId)
        // The position only needs polling while it moves; 250 ms keeps lyric lines on time.
        if (p.isPlaying && ticker?.isActive != true) {
            ticker = viewModelScope.launch {
                while (isActive && controller?.isPlaying == true) {
                    controller?.let { c -> _now.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) } }
                    delay(250)
                }
            }
        }
    }

    private fun publishQueue(p: Player) {
        if (p.currentMediaItem == null) {
            _upNext.value = emptyList()
            return
        }
        val from = p.currentMediaItemIndex + 1
        val to = minOf(p.mediaItemCount, from + MAX_UP_NEXT)
        _upNext.value = (from until to).map { i ->
            val item = p.getMediaItemAt(i)
            val m = item.mediaMetadata
            QueueEntry(i, item.mediaId, m.title?.toString() ?: "", m.artist?.toString() ?: "", m.artworkUri, item.isQueued)
        }
    }

    private fun loadLyrics(mediaId: String?) {
        if (mediaId == lyricsFor) return
        lyricsFor = mediaId
        if (mediaId == null) {
            _lyrics.value = Lyrics.NONE
            return
        }
        lyricsCache.get(mediaId)?.let {
            _lyrics.value = it
            return
        }
        _lyrics.value = Lyrics.NONE
        viewModelScope.launch {
            val app = getApplication<Application>()
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(PlaybackService.contentUri(mediaId))?.use { TagReader.readLyrics(it) }
                }.getOrNull().let(Lyrics::parse)
            }
            lyricsCache.put(mediaId, found)
            if (lyricsFor == mediaId) _lyrics.value = found
        }
    }

    override fun onCleared() {
        saveState()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    private companion object {
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_SORT = "sort"
        const val KEY_LAST_ID = "last_id"
        const val KEY_LAST_POS = "last_pos"
        const val KEY_QUEUE = "last_queue"
        const val KEY_QUEUE_INDEX = "last_queue_index"
        const val SAVE_EVERY_MS = 5_000L
        const val MAX_UP_NEXT = 300
    }
}
