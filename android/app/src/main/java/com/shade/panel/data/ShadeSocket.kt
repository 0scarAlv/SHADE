package com.shade.panel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// Owns the connection to the agent: connect/reconnect-with-backoff, parses
// incoming track/state frames, and sends control commands. Reconnection is
// exponential (500ms doubling up to 8s) per the design's restriction #5 —
// the app never shows a blank screen, it shows "disconnected" and keeps trying.
class ShadeSocket(
    private val url: String = "ws://127.0.0.1:8080",
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // encodeDefaults is required: without it, CommandMessage.type (which uses
    // a default value of "cmd") gets silently dropped from the outgoing JSON,
    // and the agent can't tell what kind of message it's looking at.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trackUpdates = MutableSharedFlow<TrackMessage>(extraBufferCapacity = 1)
    val trackUpdates: SharedFlow<TrackMessage> = _trackUpdates.asSharedFlow()

    private val _stateUpdates = MutableSharedFlow<StateMessage>(extraBufferCapacity = 1)
    val stateUpdates: SharedFlow<StateMessage> = _stateUpdates.asSharedFlow()

    private val _lyricsUpdates = MutableSharedFlow<LyricsMessage>(extraBufferCapacity = 1)
    val lyricsUpdates: SharedFlow<LyricsMessage> = _lyricsUpdates.asSharedFlow()

    fun connect() {
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.CONNECTING
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE_CODE, null)
        webSocket = null
    }

    fun sendCommand(action: String, value: Double? = null) {
        webSocket?.send(json.encodeToString(CommandMessage.serializer(), CommandMessage(action = action, value = value)))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val element = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (element["type"]?.jsonPrimitive?.contentOrNull) {
                "track" -> runCatching { json.decodeFromJsonElement(TrackMessage.serializer(), element) }
                    .onSuccess { _trackUpdates.tryEmit(it) }
                "state" -> runCatching { json.decodeFromJsonElement(StateMessage.serializer(), element) }
                    .onSuccess { _stateUpdates.tryEmit(it) }
                "lyrics" -> runCatching { json.decodeFromJsonElement(LyricsMessage.serializer(), element) }
                    .onSuccess { _lyricsUpdates.tryEmit(it) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
    }

    private fun scheduleReconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            connect()
        }
    }

    private companion object {
        const val INITIAL_RECONNECT_DELAY_MS = 500L
        const val MAX_RECONNECT_DELAY_MS = 8_000L
        const val NORMAL_CLOSURE_CODE = 1000
    }
}
