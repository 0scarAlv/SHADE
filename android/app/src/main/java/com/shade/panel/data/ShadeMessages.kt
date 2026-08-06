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
data class LyricsLine(
    val timeMs: Long,
    val text: String,
)

@Serializable
data class LyricsMessage(
    val lines: List<LyricsLine>? = null,
    val plain: String? = null,
)

@Serializable
data class SpectrumMessage(
    val bands: List<Float>,
)

@Serializable
data class ResourceMessage(
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val netDownBytesPerSec: Double,
    val netUpBytesPerSec: Double,
    val hasBattery: Boolean,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val cpuUsagePercent: Double = 0.0,
)

@Serializable
data class ProcessEntry(
    val name: String,
    val pid: Int,
    val ramBytes: Long,
    val cpuPercent: Double,
)

@Serializable
data class ProcessListMessage(
    val metric: String,
    val processes: List<ProcessEntry>,
)

@Serializable
data class CommandMessage(
    val type: String = "cmd",
    val action: String,
    val value: Double? = null,
)

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
