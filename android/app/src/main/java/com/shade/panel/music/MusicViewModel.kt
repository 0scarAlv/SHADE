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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val songs: StateFlow<List<Song>> = library.songs
    val loading: StateFlow<Boolean> = library.loading

    private val prefs = app.getSharedPreferences("shade_music", Context.MODE_PRIVATE)
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) publishQueue(player)
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
            }, MoreExecutors.directExecutor())
        }
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
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    private companion object {
        const val KEY_SHUFFLE = "shuffle"
        const val MAX_UP_NEXT = 300
    }
}
