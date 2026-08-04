package com.shade.panel.data

import kotlinx.serialization.Serializable

// Kotlin mirror of the agent's protocol (see the JSON shapes documented at the project root).

@Serializable
data class TrackMessage(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artHash: String? = null,
)

@Serializable
data class StateMessage(
    val playing: Boolean,
    val positionMs: Long,
    val timestampMs: Long,
    val volume: Double? = null,
)

@Serializable
data class CommandMessage(
    val type: String = "cmd",
    val action: String,
    val value: Double? = null,
)

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
