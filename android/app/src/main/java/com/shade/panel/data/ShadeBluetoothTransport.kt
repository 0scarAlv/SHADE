package com.shade.panel.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Bluetooth Classic (RFCOMM) counterpart to ShadeSocket. Same public surface
// (ShadeTransport) and same exponential-backoff reconnect shape, but a socket
// is single-use here — unlike OkHttp, a closed BluetoothSocket can't be
// reconnected, so every attempt below builds a brand-new one.
//
// Permission gating (BLUETOOTH_CONNECT on API 31+) is the caller's
// responsibility — this class assumes it's only ever asked to connect() once
// that's granted, hence the blanket @SuppressLint("MissingPermission") below.
@SuppressLint("MissingPermission")
class ShadeBluetoothTransport(
    context: Context,
    private val deviceAddressProvider: () -> String?,
) : ShadeTransport {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var socket: BluetoothSocket? = null
    private var connectJob: Job? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private val writeMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trackUpdates = MutableSharedFlow<TrackMessage>(extraBufferCapacity = 1)
    override val trackUpdates: SharedFlow<TrackMessage> = _trackUpdates.asSharedFlow()

    private val _stateUpdates = MutableSharedFlow<StateMessage>(extraBufferCapacity = 1)
    override val stateUpdates: SharedFlow<StateMessage> = _stateUpdates.asSharedFlow()

    private val _lyricsUpdates = MutableSharedFlow<LyricsMessage>(extraBufferCapacity = 1)
    override val lyricsUpdates: SharedFlow<LyricsMessage> = _lyricsUpdates.asSharedFlow()

    private val _spectrumUpdates = MutableSharedFlow<SpectrumMessage>(extraBufferCapacity = 1)
    override val spectrumUpdates: SharedFlow<SpectrumMessage> = _spectrumUpdates.asSharedFlow()

    private val _artUpdates = MutableSharedFlow<ArtPayload>(extraBufferCapacity = 1)
    override val artUpdates: SharedFlow<ArtPayload> = _artUpdates.asSharedFlow()

    override fun connect() {
        connectJob?.cancel()
        connectJob = scope.launch { connectLoop() }
    }

    override fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        // Blocking reads don't observe coroutine cancellation — closing the
        // socket is what actually unblocks a pending readFrame() call.
        runCatching { socket?.close() }
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun sendCommand(action: String, value: Double?) {
        val activeSocket = socket ?: return
        scope.launch {
            try {
                writeMutex.withLock {
                    FrameCodec.writeFrame(
                        activeSocket.outputStream,
                        FrameCodec.TYPE_JSON,
                        json.encodeToString(CommandMessage.serializer(), CommandMessage(action = action, value = value))
                            .toByteArray(Charsets.UTF_8),
                    )
                }
            } catch (e: IOException) {
                // The read loop will notice the closed/broken socket and reconnect.
            }
        }
    }

    private suspend fun connectLoop() {
        while (true) {
            _connectionState.value = ConnectionState.CONNECTING

            val address = deviceAddressProvider()
            val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val device = address?.let { addr -> adapter?.bondedDevices?.firstOrNull { it.address == addr } }

            val newSocket = if (adapter != null && device != null) {
                try {
                    adapter.cancelDiscovery()
                    device.createRfcommSocketToServiceRecord(SHADE_SERVICE_UUID).also { it.connect() }
                } catch (e: IOException) {
                    null
                }
            } else {
                null
            }

            if (newSocket == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                continue
            }

            socket = newSocket
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            _connectionState.value = ConnectionState.CONNECTED

            runReadLoop(newSocket)

            socket = null
            _connectionState.value = ConnectionState.DISCONNECTED
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }
    }

    // Suspends (on the blocking IO dispatcher) until the connection closes or errors.
    private fun runReadLoop(activeSocket: BluetoothSocket) {
        try {
            val input = activeSocket.inputStream
            while (true) {
                val frame = FrameCodec.readFrame(input) ?: break
                when (frame.type) {
                    FrameCodec.TYPE_JSON -> handleJsonFrame(String(frame.payload, Charsets.UTF_8))
                    FrameCodec.TYPE_ART -> handleArtFrame(frame.payload)
                }
            }
        } catch (e: IOException) {
            // Connection dropped; connectLoop will reconnect.
        } finally {
            runCatching { activeSocket.close() }
        }
    }

    private fun handleJsonFrame(text: String) {
        val element = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (element["type"]?.jsonPrimitive?.contentOrNull) {
            "track" -> runCatching { json.decodeFromJsonElement(TrackMessage.serializer(), element) }
                .onSuccess { _trackUpdates.tryEmit(it) }
            "state" -> runCatching { json.decodeFromJsonElement(StateMessage.serializer(), element) }
                .onSuccess { _stateUpdates.tryEmit(it) }
            "lyrics" -> runCatching { json.decodeFromJsonElement(LyricsMessage.serializer(), element) }
                .onSuccess { _lyricsUpdates.tryEmit(it) }
            "spectrum" -> runCatching { json.decodeFromJsonElement(SpectrumMessage.serializer(), element) }
                .onSuccess { _spectrumUpdates.tryEmit(it) }
        }
    }

    // Mirrors the agent's Art frame layout exactly (see FrameCodec.cs):
    // [1-byte hashLen][hash][1-byte contentTypeLen][contentType][art bytes].
    private fun handleArtFrame(payload: ByteArray) {
        if (payload.isEmpty()) return
        var offset = 0

        val hashLen = payload[offset].toInt() and 0xFF
        offset += 1
        if (offset + hashLen > payload.size) return
        val hash = String(payload, offset, hashLen, Charsets.UTF_8)
        offset += hashLen

        if (offset >= payload.size) return
        val contentTypeLen = payload[offset].toInt() and 0xFF
        offset += 1
        if (offset + contentTypeLen > payload.size) return
        val contentType = String(payload, offset, contentTypeLen, Charsets.UTF_8)
        offset += contentTypeLen

        val bytes = payload.copyOfRange(offset, payload.size)
        _artUpdates.tryEmit(ArtPayload(hash, contentType, bytes))
    }

    companion object {
        // Fixed constant — must match src/Shade.Agent/Bluetooth/ShadeRfcommService.cs exactly.
        val SHADE_SERVICE_UUID: UUID = UUID.fromString("ae019494-b8df-4acc-9b10-a6ecf17410b2")
        private const val INITIAL_RECONNECT_DELAY_MS = 500L
        private const val MAX_RECONNECT_DELAY_MS = 8_000L
    }
}
