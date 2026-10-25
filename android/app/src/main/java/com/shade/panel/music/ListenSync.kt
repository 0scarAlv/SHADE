package com.shade.panel.music

import android.content.Context
import android.os.Build
import com.shade.panel.data.ConnectionState
import com.shade.panel.data.ShadeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Nation's owner, as the agent reports it. */
data class NationOwner(val id: String, val name: String)

data class SyncState(
    val linked: Boolean = false,
    val ownerName: String? = null,
    /** Shown as "¿vincular?" until the user answers. */
    val askToLink: NationOwner? = null,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0,
    val message: String? = null,
    /** Bumped whenever rows arrive, so stats re-read the journal. */
    val revision: Int = 0,
)

/**
 * Keeps the phone's journal and Nation's in step, through the Shade agent
 * (see the agent's ListenSyncRelay). Nation is the source of truth: the phone
 * pushes its own listens and pulls everything of the same person, after the
 * user agreed once to link this phone to that PC's Nation owner.
 */
object ListenSync {
    private const val PUSH_LIMIT = 2_000
    private const val PERIOD_MS = 3 * 60_000L

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private lateinit var db: ListenDb
    private var transport: ShadeTransport? = null
    private var jobs: List<Job> = emptyList()
    private var pendingPushUntil = 0L
    private var pushWasFull = false
    private var timeout: Job? = null
    private var scope: CoroutineScope? = null

    /** Follows the active transport: syncs on connect, every few minutes, and on demand. */
    fun bind(context: Context, scope: CoroutineScope, transport: ShadeTransport) {
        db = ListenDb.get(context)
        jobs.forEach { it.cancel() }
        this.transport = transport
        this.scope = scope
        val name = db.meta(KEY_PAIRED_NAME)
        _state.update { it.copy(linked = db.meta(KEY_PAIRED) != null, ownerName = name, syncing = false) }
        jobs = listOf(
            scope.launch {
                transport.connectionState.collect { status ->
                    if (status == ConnectionState.CONNECTED) syncNow()
                    else _state.update { it.copy(syncing = false) }
                }
            },
            scope.launch { transport.syncResults.collect { handle(it) } },
            scope.launch {
                while (isActive) {
                    delay(PERIOD_MS)
                    syncNow()
                }
            },
        )
    }

    fun syncNow() {
        val t = transport ?: return
        val s = scope ?: return
        if (_state.value.syncing || t.connectionState.value != ConnectionState.CONNECTED) {
            if (t.connectionState.value != ConnectionState.CONNECTED) _state.update { it.copy(message = "Sin conexión con el PC") }
            return
        }
        _state.update { it.copy(syncing = true) }
        s.launch {
            val request = withContext(Dispatchers.IO) { buildRequest() }
            if (!t.sendJson(request.toString())) {
                _state.update { it.copy(syncing = false, message = "Sin conexión con el PC") }
                return@launch
            }
            // An agent too old to know "sync" never answers.
            timeout?.cancel()
            timeout = s.launch {
                delay(30_000)
                _state.update { it.copy(syncing = false, message = "El agente de Shade no respondió (¿versión vieja?)") }
            }
        }
    }

    /** "Sí, este teléfono es mío": its listens become that person's, then sync. */
    fun acceptLink() {
        val owner = _state.value.askToLink ?: return
        val s = scope ?: return
        s.launch {
            withContext(Dispatchers.IO) {
                db.adoptListener(owner.id)
                db.setMeta(KEY_PAIRED, owner.id)
                db.setMeta(KEY_PAIRED_NAME, owner.name)
                db.setMeta(KEY_DECLINED, null)
            }
            _state.update { it.copy(linked = true, ownerName = owner.name, askToLink = null) }
            syncNow()
        }
    }

    /** Starts the phone's stats from zero (Nation's journal is cleared on the PC side). */
    suspend fun clearPhoneHistory() {
        withContext(Dispatchers.IO) { db.clearListens(listOf(KEY_PULL_SINCE, KEY_PULL_SINCE_ID, KEY_PUSH_UNTIL)) }
        _state.update { it.copy(revision = it.revision + 1) }
    }

    fun declineLink() {
        val owner = _state.value.askToLink ?: return
        scope?.launch(Dispatchers.IO) { db.setMeta(KEY_DECLINED, owner.id) }
        _state.update { it.copy(askToLink = null, message = "No vinculado con ${owner.name}") }
    }

    private fun buildRequest(): JsonObject {
        val paired = db.meta(KEY_PAIRED)
        val own = if (paired != null) db.ownChangedSince(db.meta(KEY_PUSH_UNTIL)?.toLongOrNull() ?: 0, PUSH_LIMIT) else emptyList()
        pendingPushUntil = own.maxOfOrNull { it.updatedAt } ?: 0
        pushWasFull = own.size == PUSH_LIMIT
        return buildJsonObject {
            put("type", "sync")
            put("listener", paired?.let(::JsonPrimitive) ?: JsonNull)
            put("since", db.meta(KEY_PULL_SINCE)?.toLongOrNull() ?: 0)
            put("sinceId", db.meta(KEY_PULL_SINCE_ID) ?: "")
            put(
                "device",
                buildJsonObject {
                    put("id", db.deviceId)
                    put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("kind", "phone")
                },
            )
            put("listens", buildJsonArray { own.forEach { add(it.toJson()) } })
        }
    }

    private suspend fun handle(result: JsonObject) {
        timeout?.cancel()
        val error = result["error"]?.jsonPrimitive?.contentOrNull
        val nation = result["nation"]?.jsonPrimitive?.booleanOrNull == true
        val ownerJson = result["owner"] as? JsonObject
        val owner = ownerJson?.let {
            val id = it["id"]?.jsonPrimitive?.contentOrNull ?: return@let null
            NationOwner(id, it["name"]?.jsonPrimitive?.contentOrNull ?: "")
        }
        if (!nation || owner == null) {
            _state.update { it.copy(syncing = false, message = error ?: "Nation no respondió") }
            return
        }
        val paired = withContext(Dispatchers.IO) { db.meta(KEY_PAIRED) }
        if (result["paired"]?.jsonPrimitive?.booleanOrNull != true) {
            val declined = withContext(Dispatchers.IO) { db.meta(KEY_DECLINED) }
            _state.update {
                when {
                    // Linked to someone else's Nation: never mix two people's history.
                    paired != null -> it.copy(syncing = false, message = "Este PC es de ${owner.name}; el teléfono está vinculado a otra persona")
                    declined == owner.id -> it.copy(syncing = false, message = "No vinculado con ${owner.name}")
                    else -> it.copy(syncing = false, askToLink = owner, message = null)
                }
            }
            return
        }
        if (error != null) {
            _state.update { it.copy(syncing = false, message = error) }
            return
        }

        val rows = (result["listens"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toListen() }
        val more = result["more"]?.jsonPrimitive?.booleanOrNull == true
        withContext(Dispatchers.IO) {
            db.mergeRemote(rows)
            rows.lastOrNull()?.let {
                db.setMeta(KEY_PULL_SINCE, it.updatedAt.toString())
                db.setMeta(KEY_PULL_SINCE_ID, it.id)
            }
            if (pendingPushUntil > 0) db.setMeta(KEY_PUSH_UNTIL, pendingPushUntil.toString())
        }
        _state.update {
            it.copy(
                syncing = false,
                linked = true,
                ownerName = owner.name,
                lastSyncAt = System.currentTimeMillis(),
                message = null,
                revision = if (rows.isNotEmpty() || pendingPushUntil > 0) it.revision + 1 else it.revision,
            )
        }
        // Big histories come in pages; keep going until both sides are caught up.
        // (The listen playing right now keeps changing; it rides along with the next periodic sync.)
        if (more || pushWasFull) syncNow()
    }

    private fun Listen.toJson() = buildJsonObject {
        put("id", id); put("listener", listener); put("device", device); put("source", source)
        put("startedAt", startedAt); put("endedAt", endedAt); put("playedMs", playedMs); put("durationMs", durationMs)
        put("title", title); put("artist", artist); put("album", album)
        put("genre", genre); put("isrc", isrc); put("path", path)
        put("trackKey", trackKey); put("music", music); put("updatedAt", updatedAt)
    }

    private fun JsonObject.toListen(): Listen? = runCatching {
        fun str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        fun long(k: String) = this[k]?.jsonPrimitive?.longOrNull ?: 0L
        Listen(
            id = str("id")!!, listener = str("listener")!!, device = str("device")!!, source = str("source") ?: "",
            startedAt = long("startedAt"), endedAt = long("endedAt"), playedMs = long("playedMs"), durationMs = long("durationMs"),
            title = str("title") ?: "", artist = str("artist") ?: "", album = str("album") ?: "",
            genre = str("genre"), isrc = str("isrc"), path = str("path"), trackKey = str("trackKey") ?: "",
            music = this["music"]?.jsonPrimitive?.booleanOrNull ?: true, updatedAt = long("updatedAt"),
        )
    }.getOrNull()

    private const val KEY_PAIRED = "nation_listener"
    private const val KEY_PAIRED_NAME = "nation_name"
    private const val KEY_DECLINED = "nation_declined"
    private const val KEY_PULL_SINCE = "pull_since"
    private const val KEY_PULL_SINCE_ID = "pull_since_id"
    private const val KEY_PUSH_UNTIL = "push_until"
}
