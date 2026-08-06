package com.shade.panel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.LyricsLine
import com.shade.panel.data.ShadeSocket
import com.shade.panel.data.ShadeTransport
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
    val artHash: String? = null,
    val artUrl: String? = null,
    val artBytes: ByteArray? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Double? = null,
    val currentLyricsLine: String? = null,
    val spectrumBands: List<Float> = List(BAND_COUNT) { 0f },
)

const val BAND_COUNT = 32

class PanelViewModel(
    private val transport: ShadeTransport = ShadeSocket(),
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
        transport.connect()

        viewModelScope.launch {
            transport.connectionState.collect { status ->
                _uiState.update { it.copy(connection = status) }
            }
        }
        viewModelScope.launch {
            transport.trackUpdates.collect { track ->
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
                        artHash = track.artHash,
                        artUrl = track.artHash?.let { hash -> "$ART_BASE_URL/$hash" },
                        // Cleared until the Bluetooth transport's matching push
                        // arrives (see artUpdates below) — WebSocket doesn't push
                        // art at all, it just uses artUrl above.
                        artBytes = null,
                        currentLyricsLine = if (isNewTrack) null else it.currentLyricsLine,
                    )
                }
            }
        }
        viewModelScope.launch {
            transport.artUpdates.collect { art ->
                // Guards against a stale push arriving after a fast double
                // track-change — only apply it if it still matches the track
                // currently on screen.
                _uiState.update { if (it.artHash == art.hash) it.copy(artBytes = art.bytes) else it }
            }
        }
        viewModelScope.launch {
            transport.stateUpdates.collect { state ->
                basePositionMs = state.positionMs
                baseTimestampMs = state.timestampMs
                _uiState.update { it.copy(playing = state.playing, positionMs = state.positionMs, volume = state.volume ?: it.volume) }
            }
        }
        viewModelScope.launch {
            transport.lyricsUpdates.collect { lyrics ->
                lyricsLines = lyrics.lines.orEmpty()
            }
        }
        viewModelScope.launch {
            transport.spectrumUpdates.collect { spectrum ->
                val incoming = spectrum.bands
                _uiState.update { current ->
                    // Light smoothing between frames so the bars glide instead
                    // of jumping — the agent already sends ~20-30 frames/sec,
                    // this just takes the edge off.
                    val smoothed = current.spectrumBands.mapIndexed { i, previous ->
                        val target = incoming.getOrElse(i) { 0f }
                        previous * 0.4f + target * 0.6f
                    }
                    current.copy(spectrumBands = smoothed)
                }
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

    fun playPause() = transport.sendCommand("playPause")
    fun next() = transport.sendCommand("next")
    fun prev() = transport.sendCommand("prev")
    fun volumeUp() = transport.sendCommand("volumeUp")
    fun volumeDown() = transport.sendCommand("volumeDown")

    fun seek(positionMs: Long) {
        basePositionMs = positionMs
        baseTimestampMs = System.currentTimeMillis()
        _uiState.update { it.copy(positionMs = positionMs, currentLyricsLine = lineAt(positionMs)) }
        transport.sendCommand("seek", value = positionMs.toDouble())
    }

    override fun onCleared() {
        transport.disconnect()
    }

    private companion object {
        const val ART_BASE_URL = "http://127.0.0.1:8080/art"
        const val INTERPOLATION_TICK_MS = 250L
    }
}
