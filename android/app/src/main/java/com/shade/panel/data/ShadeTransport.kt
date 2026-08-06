package com.shade.panel.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// Common surface for both ways of reaching the agent (WebSocket-over-adb-reverse
// and Bluetooth RFCOMM), so PanelViewModel doesn't care which one is active.
interface ShadeTransport {
    val connectionState: StateFlow<ConnectionState>
    val trackUpdates: SharedFlow<TrackMessage>
    val stateUpdates: SharedFlow<StateMessage>
    val lyricsUpdates: SharedFlow<LyricsMessage>
    val spectrumUpdates: SharedFlow<SpectrumMessage>

    // Only ever emitted by the Bluetooth transport — WebSocket clients fetch
    // art over HTTP instead, so ShadeSocket's flow simply never fires.
    val artUpdates: SharedFlow<ArtPayload>

    fun connect()
    fun disconnect()
    fun sendCommand(action: String, value: Double? = null)
}

data class ArtPayload(
    val hash: String,
    val contentType: String,
    val bytes: ByteArray,
)
