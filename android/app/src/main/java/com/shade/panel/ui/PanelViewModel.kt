package com.shade.panel.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.LyricsLine
import com.shade.panel.music.ListenSync
import com.shade.panel.data.ProcessEntry
import com.shade.panel.data.ResourceMessage
import com.shade.panel.data.ShadeBluetoothTransport
import com.shade.panel.data.ShadePreferences
import com.shade.panel.data.ShadeSocket
import com.shade.panel.data.ShadeTransport
import com.shade.panel.data.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PanelUiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val transport: Transport = Transport.WEBSOCKET,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val appName: String? = null,
    val artHash: String? = null,
    val artUrl: String? = null,
    val artBytes: ByteArray? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Double? = null,
    val muted: Boolean = false,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
    val lyricsLines: List<LyricsLine> = emptyList(),
    val currentLyricsIndex: Int = -1,
    val resource: ResourceMessage? = null,
    // Non-null metric means the drilldown overlay should be showing; entries
    // stay null while the reply is in flight (drives a loading state).
    // Live process board: open while non-null, sorted by "cpu" or "ram".
    val processBoardSort: String? = null,
    // null until the first reply; per app (the agent sums each app's processes).
    val processApps: List<ProcessEntry>? = null,
    // Each app's CPU over the last minute, oldest first, for its row's trace.
    val processCpuHistory: Map<String, List<Float>> = emptyMap(),
) {
    val art: Any? get() = artBytes ?: artUrl
    val hasTrack: Boolean get() = title.isNotBlank()
}

// Rolling ~60s of resource samples. Lives in the ViewModel (not the stats
// screen) so the graphs keep filling while the user is on another screen
// and are already populated when they swipe back.
data class ResourceHistory(
    val cpu: List<Float> = emptyList(),
    val ram: List<Float> = emptyList(),
    val netDown: List<Float> = emptyList(),
    val netUp: List<Float> = emptyList(),
    val diskRead: List<Float> = emptyList(),
    val diskWrite: List<Float> = emptyList(),
)

const val BAND_COUNT = 32
const val HISTORY_SIZE = 60

// Builds the transport from the user's saved preference each time it's
// called, so restartTransport() picks up a changed setting.
fun panelViewModelFactory(context: Context) = viewModelFactory {
    initializer {
        val appContext = context.applicationContext
        val preferences = ShadePreferences(appContext)
        PanelViewModel(
            transportFactory = {
                if (preferences.transport == Transport.BLUETOOTH) {
                    ShadeBluetoothTransport(appContext) { preferences.pairedDeviceAddress }
                } else {
                    ShadeSocket()
                }
            },
            transportKind = { preferences.transport },
            // The stats sync with Nation rides on whichever transport is active.
            onTransport = { scope, transport -> ListenSync.bind(appContext, scope, transport) },
        )
    }
}

class PanelViewModel(
    private val transportFactory: () -> ShadeTransport = { ShadeSocket() },
    private val transportKind: () -> Transport = { Transport.WEBSOCKET },
    private val onTransport: (CoroutineScope, ShadeTransport) -> Unit = { _, _ -> },
) : ViewModel() {

    private val _uiState = MutableStateFlow(PanelUiState())
    val uiState: StateFlow<PanelUiState> = _uiState.asStateFlow()

    // Spectrum frames arrive 20-50 times a second. Keeping them out of
    // PanelUiState means only the visualizer redraws per frame instead of
    // every composable that reads the UI state recomposing at that rate.
    private val _spectrum = MutableStateFlow(FloatArray(BAND_COUNT))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    private val _resourceHistory = MutableStateFlow(ResourceHistory())
    val resourceHistory: StateFlow<ResourceHistory> = _resourceHistory.asStateFlow()

    // Base point for local interpolation: SMTC only reports position in jumps
    // (restriction #1 in the design), so between "state" messages we advance
    // the displayed position ourselves from this anchor instead of polling.
    private var basePositionMs = 0L
    private var baseTimestampMs = 0L

    private var lyricsLines: List<LyricsLine> = emptyList()
    private var lastTrackKey: String? = null

    private var transport: ShadeTransport = transportFactory()
    private var transportJob: Job? = null

    // While the user drags the volume slider, incoming state echoes would
    // otherwise yank the thumb back to slightly stale values mid-drag.
    private var volumeDragUntilMs = 0L

    init {
        startTransport()

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
                val index = indexAt(positionMs)
                if (positionMs != current.positionMs || index != current.currentLyricsIndex) {
                    _uiState.update { it.copy(positionMs = positionMs, currentLyricsIndex = index) }
                }
            }
        }
    }

    // Swaps to a freshly built transport (after the user changes it on the
    // connection screen) without throwing away the ViewModel — every screen
    // keeps observing the same flows.
    fun restartTransport() {
        transportJob?.cancel()
        transport.disconnect()
        transport = transportFactory()
        lastTrackKey = null
        lyricsLines = emptyList()
        _uiState.value = PanelUiState()
        _spectrum.value = FloatArray(BAND_COUNT)
        startTransport()
    }

    private fun startTransport() {
        val active = transport
        _uiState.update { it.copy(transport = transportKind()) }
        active.connect()

        transportJob = viewModelScope.launch {
            onTransport(this, active)
            launch {
                active.connectionState.collect { status ->
                    _uiState.update { it.copy(connection = status) }
                    if (status != ConnectionState.CONNECTED) _spectrum.value = FloatArray(BAND_COUNT)
                }
            }
            launch {
                active.trackUpdates.collect { track ->
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
                            appName = track.appName,
                            durationMs = track.durationMs,
                            artHash = track.artHash,
                            artUrl = track.artHash?.let { hash -> "$ART_BASE_URL/$hash" },
                            // Cleared until the Bluetooth transport's matching push
                            // arrives (see artUpdates below) — unless it's the same
                            // art as before (duplicate SMTC event), which would
                            // otherwise blink the cover off and on.
                            artBytes = if (track.artHash == it.artHash) it.artBytes else null,
                            lyricsLines = if (isNewTrack) emptyList() else it.lyricsLines,
                            currentLyricsIndex = if (isNewTrack) -1 else it.currentLyricsIndex,
                        )
                    }
                }
            }
            launch {
                active.artUpdates.collect { art ->
                    // Guards against a stale push arriving after a fast double
                    // track-change — only apply it if it still matches the track
                    // currently on screen.
                    _uiState.update { if (it.artHash == art.hash) it.copy(artBytes = art.bytes) else it }
                }
            }
            launch {
                active.stateUpdates.collect { state ->
                    // SMTC's TimelinePropertiesChanged fires roughly every second
                    // even without a real change, and the position it reports can
                    // lag the true elapsed time by up to ~1s depending on when the
                    // source app last ticked its own timeline. Re-anchoring the
                    // local interpolation on every one of these made the displayed
                    // second visibly hop backward and forward (21, 22, 21, 22...).
                    // Only re-anchor on an actual discontinuity — play/pause
                    // toggling or a drift bigger than that jitter margin (seek,
                    // track change) — otherwise let interpolation keep counting
                    // up uninterrupted.
                    val wasPlaying = _uiState.value.playing
                    val predictedMs = if (wasPlaying && baseTimestampMs > 0) {
                        basePositionMs + (System.currentTimeMillis() - baseTimestampMs)
                    } else {
                        basePositionMs
                    }
                    val driftMs = state.positionMs - predictedMs
                    if (!state.playing || state.playing != wasPlaying || kotlin.math.abs(driftMs) > POSITION_DRIFT_TOLERANCE_MS) {
                        basePositionMs = state.positionMs
                        baseTimestampMs = state.timestampMs
                    }
                    val dragging = System.currentTimeMillis() < volumeDragUntilMs
                    _uiState.update {
                        it.copy(
                            playing = state.playing,
                            positionMs = if (!state.playing) state.positionMs else it.positionMs,
                            volume = if (dragging) it.volume else state.volume ?: it.volume,
                            muted = state.muted ?: it.muted,
                            shuffle = state.shuffle,
                            repeat = state.repeat,
                        )
                    }
                }
            }
            launch {
                active.lyricsUpdates.collect { lyrics ->
                    lyricsLines = lyrics.lines.orEmpty()
                    val index = indexAt(_uiState.value.positionMs)
                    _uiState.update { it.copy(lyricsLines = lyricsLines, currentLyricsIndex = index) }
                }
            }
            launch {
                active.spectrumUpdates.collect { spectrum ->
                    val incoming = spectrum.bands
                    _spectrum.value = FloatArray(BAND_COUNT) { i -> incoming.getOrElse(i) { 0f } }
                }
            }
            launch {
                active.resourceUpdates.collect { resource ->
                    _uiState.update { it.copy(resource = resource) }
                    recordHistory(resource)
                }
            }
            launch {
                active.processUpdates.collect { msg ->
                    if (msg.metric != "apps") return@collect
                    _uiState.update {
                        // A late reply after the board closed is dropped.
                        if (it.processBoardSort == null) it
                        else it.copy(processApps = msg.processes, processCpuHistory = appendCpu(it.processCpuHistory, msg.processes))
                    }
                }
            }
        }
    }

    private fun appendCpu(history: Map<String, List<Float>>, apps: List<ProcessEntry>): Map<String, List<Float>> =
        apps.associate { app -> app.name to ((history[app.name] ?: emptyList()) + app.cpuPercent.toFloat()).takeLast(BOARD_HISTORY) }

    private fun recordHistory(r: ResourceMessage) {
        val ramFraction = if (r.ramTotalBytes > 0) {
            (r.ramUsedBytes.toFloat() / r.ramTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
        _resourceHistory.update { h ->
            ResourceHistory(
                cpu = h.cpu.pushed((r.cpuUsagePercent.toFloat() / 100f).coerceIn(0f, 1f)),
                ram = h.ram.pushed(ramFraction),
                netDown = h.netDown.pushed(r.netDownBytesPerSec.toFloat()),
                netUp = h.netUp.pushed(r.netUpBytesPerSec.toFloat()),
                diskRead = h.diskRead.pushed(r.diskReadBytesPerSec.toFloat()),
                diskWrite = h.diskWrite.pushed(r.diskWriteBytesPerSec.toFloat()),
            )
        }
    }

    private fun List<Float>.pushed(value: Float): List<Float> =
        if (size >= HISTORY_SIZE) drop(size - HISTORY_SIZE + 1) + value else this + value

    // Set from the user's setting (see MainActivity); applied on the next tick.
    var lyricsOffsetMs: Int = 0

    private fun indexAt(positionMs: Long): Int =
        lyricsLines.indexOfLast { it.timeMs <= positionMs + lyricsOffsetMs }

    fun playPause() {
        // Optimistic flip so the button reacts instantly; the agent's state
        // echo (usually <100ms later) confirms or corrects it. Re-anchor the
        // interpolation at the current position, or resuming would jump
        // ahead by however long playback had been paused.
        basePositionMs = _uiState.value.positionMs
        baseTimestampMs = System.currentTimeMillis()
        _uiState.update { it.copy(playing = !it.playing) }
        transport.sendCommand("playPause")
    }

    fun next() = transport.sendCommand("next")
    fun prev() = transport.sendCommand("prev")
    fun volumeUp() = transport.sendCommand("volumeUp")
    fun volumeDown() = transport.sendCommand("volumeDown")
    fun toggleMute() {
        _uiState.update { it.copy(muted = !it.muted) }
        transport.sendCommand("toggleMute")
    }

    fun toggleShuffle() {
        _uiState.update { it.copy(shuffle = it.shuffle?.not()) }
        transport.sendCommand("toggleShuffle")
    }

    fun cycleRepeat() {
        _uiState.update {
            it.copy(
                repeat = when (it.repeat) {
                    "none" -> "list"
                    "list" -> "track"
                    "track" -> "none"
                    else -> it.repeat
                },
            )
        }
        transport.sendCommand("cycleRepeat")
    }

    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        volumeDragUntilMs = System.currentTimeMillis() + VOLUME_ECHO_GRACE_MS
        _uiState.update { it.copy(volume = clamped.toDouble(), muted = false) }
        transport.sendCommand("setVolume", value = clamped.toDouble())
    }

    fun seek(positionMs: Long) {
        basePositionMs = positionMs
        baseTimestampMs = System.currentTimeMillis()
        _uiState.update { it.copy(positionMs = positionMs, currentLyricsIndex = indexAt(positionMs)) }
        transport.sendCommand("seek", value = positionMs.toDouble())
    }

    private var boardJob: Job? = null

    /** Opens the live process board; it polls the agent every 2 s until closed. */
    fun openProcessBoard(sort: String) {
        _uiState.update { it.copy(processBoardSort = sort, processApps = null, processCpuHistory = emptyMap()) }
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            while (true) {
                transport.sendCommand("topApps")
                delay(BOARD_POLL_MS)
            }
        }
    }

    fun sortProcessBoard(sort: String) {
        _uiState.update { if (it.processBoardSort == null) it else it.copy(processBoardSort = sort) }
    }

    fun closeProcessBoard() {
        boardJob?.cancel()
        boardJob = null
        _uiState.update { it.copy(processBoardSort = null, processApps = null, processCpuHistory = emptyMap()) }
    }

    override fun onCleared() {
        transportJob?.cancel()
        transport.disconnect()
    }

    private companion object {
        const val BOARD_POLL_MS = 2_000L
        const val BOARD_HISTORY = 30

        const val ART_BASE_URL = "http://127.0.0.1:8080/art"
        const val INTERPOLATION_TICK_MS = 250L
        const val POSITION_DRIFT_TOLERANCE_MS = 900L
        const val VOLUME_ECHO_GRACE_MS = 600L
    }
}
