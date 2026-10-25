package com.shade.panel.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

// The Música screen's side of the player: talks to PlaybackService through a
// MediaController, so playback lives in the service and survives the screen.
class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val library = MusicLibrary.get(app)
    val songs: StateFlow<List<Song>> = library.songs
    val loading: StateFlow<Boolean> = library.loading

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Search narrows what's on screen only; playing a result still queues the whole library.
    val shown: StateFlow<List<Song>> = combine(songs, _query) { all, q ->
        val words = fold(q).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) all else all.filter { s -> words.all { s.searchKey.contains(it) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    fun setQuery(q: String) {
        _query.value = q
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
                c.addListener(listener)
                publish(c)
            }, MoreExecutors.directExecutor())
        }
    }

    fun rescan() {
        viewModelScope.launch { library.refresh() }
    }

    fun play(song: Song) {
        val c = controller ?: return
        val all = songs.value
        val index = all.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(all.map { it.toMediaItem() }, index, 0)
        c.prepare()
        c.play()
    }

    fun shuffleAll() {
        val c = controller ?: return
        val all = songs.value
        if (all.isEmpty()) return
        c.shuffleModeEnabled = true
        c.setMediaItems(all.map { it.toMediaItem() }, all.indices.random(), 0)
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() { controller?.seekToNextMediaItem() }

    // Like most players: past the first seconds "previous" restarts the song.
    fun previous() { controller?.seekToPrevious() }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        if (c.duration > 0) c.seekTo((c.duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
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
                shuffle = p.shuffleModeEnabled,
                repeatMode = p.repeatMode,
            )
        }
        // The position only needs polling while it moves.
        if (p.isPlaying && ticker?.isActive != true) {
            ticker = viewModelScope.launch {
                while (isActive && controller?.isPlaying == true) {
                    controller?.let { c -> _now.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) } }
                    delay(500)
                }
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
