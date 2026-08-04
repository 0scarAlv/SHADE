package com.shade.panel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.LyricsLine
import com.shade.panel.data.ShadeSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PanelUiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUrl: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Double? = null,
    val currentLyricsLine: String? = null,
)

class PanelViewModel(
    private val socket: ShadeSocket = ShadeSocket(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PanelUiState())
    val uiState: StateFlow<PanelUiState> = _uiState.asStateFlow()

    // Base point for local interpolation: SMTC only reports position in jumps
    // (restriction #1 in the design), so between "state" messages we advance
    // the displayed position ourselves from this anchor instead of polling.
    private var basePositionMs = 0L
    private var baseTimestampMs = 0L

    private var lyricsLines: List<LyricsLine> = emptyList()
    private var lastTrackKey: String? = null

    init {
        socket.connect()

        viewModelScope.launch {
            socket.connectionState.collect { status ->
                _uiState.update { it.copy(connection = status) }
            }
        }
        viewModelScope.launch {
            socket.trackUpdates.collect { track ->
                // SMTC can re-fire "track changed" several times for the same
                // song (partial metadata updates). Only wipe the lyrics we
                // already have when it's actually a different song — otherwise
                // a slow LRCLIB lookup keeps getting cancelled out by the next
                // duplicate event before it ever reaches the screen.
                val key = "${track.title}|${track.artist}"
                val isNewTrack = key != lastTrackKey
                if (isNewTrack) {
                    lastTrackKey = key
                    lyricsLines = emptyList()
                }
                _uiState.update {
                    it.copy(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        artUrl = track.artHash?.let { hash -> "$ART_BASE_URL/$hash" },
                        currentLyricsLine = if (isNewTrack) null else it.currentLyricsLine,
                    )
                }
            }
        }
        viewModelScope.launch {
            socket.stateUpdates.collect { state ->
                basePositionMs = state.positionMs
                baseTimestampMs = state.timestampMs
                _uiState.update { it.copy(playing = state.playing, positionMs = state.positionMs, volume = state.volume ?: it.volume) }
            }
        }
        viewModelScope.launch {
            socket.lyricsUpdates.collect { lyrics ->
                lyricsLines = lyrics.lines.orEmpty()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(INTERPOLATION_TICK_MS)
                val current = _uiState.value
                // Position interpolation only advances while actually playing,
                // but the lyrics line still needs to be re-evaluated every tick
                // regardless — otherwise a lyrics fetch that resolves while
                // paused (or right as playback starts) never shows up.
                val positionMs = if (current.playing && baseTimestampMs > 0) {
                    val elapsed = System.currentTimeMillis() - baseTimestampMs
                    (basePositionMs + elapsed).coerceIn(0, current.durationMs.coerceAtLeast(0))
                } else {
                    current.positionMs
                }
                _uiState.update { it.copy(positionMs = positionMs, currentLyricsLine = lineAt(positionMs)) }
            }
        }
    }

    private fun lineAt(positionMs: Long): String? =
        lyricsLines.lastOrNull { it.timeMs <= positionMs }?.text

    fun playPause() = socket.sendCommand("playPause")
    fun next() = socket.sendCommand("next")
    fun prev() = socket.sendCommand("prev")
    fun volumeUp() = socket.sendCommand("volumeUp")
    fun volumeDown() = socket.sendCommand("volumeDown")

    fun seek(positionMs: Long) {
        basePositionMs = positionMs
        baseTimestampMs = System.currentTimeMillis()
        _uiState.update { it.copy(positionMs = positionMs, currentLyricsLine = lineAt(positionMs)) }
        socket.sendCommand("seek", value = positionMs.toDouble())
    }

    override fun onCleared() {
        socket.disconnect()
    }

    private companion object {
        const val ART_BASE_URL = "http://127.0.0.1:8080/art"
        const val INTERPOLATION_TICK_MS = 250L
    }
}
