package com.shade.panel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shade.panel.data.ConnectionState
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

    init {
        socket.connect()

        viewModelScope.launch {
            socket.connectionState.collect { status ->
                _uiState.update { it.copy(connection = status) }
            }
        }
        viewModelScope.launch {
            socket.trackUpdates.collect { track ->
                _uiState.update {
                    it.copy(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        artUrl = track.artHash?.let { hash -> "$ART_BASE_URL/$hash" },
                    )
                }
            }
        }
        viewModelScope.launch {
            socket.stateUpdates.collect { state ->
                basePositionMs = state.positionMs
                baseTimestampMs = state.timestampMs
                _uiState.update { it.copy(playing = state.playing, positionMs = state.positionMs) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(INTERPOLATION_TICK_MS)
                val current = _uiState.value
                if (current.playing && baseTimestampMs > 0) {
                    val elapsed = System.currentTimeMillis() - baseTimestampMs
                    val interpolated = (basePositionMs + elapsed).coerceIn(0, current.durationMs.coerceAtLeast(0))
                    _uiState.update { it.copy(positionMs = interpolated) }
                }
            }
        }
    }

    fun playPause() = socket.sendCommand("playPause")
    fun next() = socket.sendCommand("next")
    fun prev() = socket.sendCommand("prev")

    override fun onCleared() {
        socket.disconnect()
    }

    private companion object {
        const val ART_BASE_URL = "http://127.0.0.1:8080/art"
        const val INTERPOLATION_TICK_MS = 250L
    }
}
