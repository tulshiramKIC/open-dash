package com.example.opendash.data

import android.content.Context
import com.example.opendash.BuildConfig
import com.example.opendash.dash.map.LocationTracker
import com.example.opendash.util.DebugLog
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Group Ride: live location sharing between riders, with nothing self-hosted.
 *
 * Positions are ephemeral Supabase Realtime BROADCAST messages on a channel named by a
 * short ride code — they never touch a database, so there is nothing to store or clean
 * up and the free tier's message quota is the only limit (a 3-rider 3-hour ride uses
 * ~25k of the 2M/month allowance). Riders joining the same code see each other as
 * markers on the Route and Dash maps.
 *
 * Bring-your-own Supabase project: SUPABASE_URL + SUPABASE_ANON_KEY in local.properties.
 * [isConfigured] is false when they're absent and the UI hides the feature.
 */
object GroupRide {
    private const val TAG = "GroupRide"
    private const val PREFS = "group_ride"
    private const val KEY_NAME = "rider_name"
    private const val KEY_DEVICE_ID = "device_id"

    /** No ambiguous chars (0/O, 1/I/L) — the code is read aloud in helmets. */
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 6

    private const val PUBLISH_INTERVAL_MS = 4_000L
    private const val EVENT_POS = "pos"
    private const val EVENT_BYE = "bye"

    /** Peer older than this renders greyed-out ("last seen…"). */
    const val STALE_MS = 20_000L
    /** Peer older than this is dropped from the list entirely. */
    private const val EXPIRE_MS = 5 * 60_000L

    data class Peer(
        val id: String,
        val name: String,
        val lat: Double,
        val lng: Double,
        val bearing: Float,
        val speedKmh: Int,
        val updatedAtMs: Long,
    ) {
        val isStale: Boolean get() = System.currentTimeMillis() - updatedAtMs > STALE_MS
    }

    data class State(
        val active: Boolean = false,
        val connecting: Boolean = false,
        val code: String? = null,
        val riderName: String = "",
        /** Other riders only (self excluded), most recently updated first. */
        val peers: List<Peer> = emptyList(),
        val error: String? = null,
    )

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var deviceId: String = ""
    private var channel: RealtimeChannel? = null
    private var rideJob: Job? = null
    private var tracker: LocationTracker? = null
    private val peersById = LinkedHashMap<String, Peer>()

    private val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) { install(Realtime) }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        _state.value = _state.value.copy(riderName = prefs.getString(KEY_NAME, "") ?: "")
    }

    fun setRiderName(name: String) {
        val trimmed = name.trim().take(20)
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_NAME, trimmed)?.apply()
        _state.value = _state.value.copy(riderName = trimmed)
    }

    fun createRide(): String {
        val code = (1..CODE_LENGTH).map { CODE_ALPHABET.random() }.joinToString("")
        joinRide(code)
        return code
    }

    fun joinRide(rawCode: String) {
        val code = rawCode.trim().uppercase()
        if (code.length != CODE_LENGTH || code.any { it !in CODE_ALPHABET }) {
            _state.value = _state.value.copy(error = "Invalid ride code")
            return
        }
        if (!isConfigured) {
            _state.value = _state.value.copy(error = "Supabase keys not configured")
            return
        }
        leaveRide()   // at most one ride at a time
        _state.value = _state.value.copy(connecting = true, code = code, error = null)

        rideJob = scope.launch {
            try {
                val ch = client.channel("ride-$code")
                channel = ch

                // Collector must be registered before subscribe() or early messages drop.
                launch {
                    ch.broadcastFlow<JsonObject>(event = EVENT_POS).collect { onPos(it) }
                }
                launch {
                    ch.broadcastFlow<JsonObject>(event = EVENT_BYE).collect { onBye(it) }
                }

                ch.subscribe(blockUntilSubscribed = true)
                _state.value = _state.value.copy(active = true, connecting = false)
                DebugLog.i(TAG) { "joined ride $code as $deviceId" }

                val t = LocationTracker(requireNotNull(appContext)).also { tracker = it }
                t.start()
                while (isActive) {
                    publishPosition(ch, t)
                    prunePeers()
                    delay(PUBLISH_INTERVAL_MS)
                }
            } catch (e: Exception) {
                DebugLog.w(TAG) { "ride failed: ${e.message}" }
                _state.value = _state.value.copy(
                    active = false, connecting = false,
                    error = e.message ?: "Connection failed",
                )
            }
        }
    }

    fun leaveRide() {
        val ch = channel
        rideJob?.cancel(); rideJob = null
        tracker?.stop(); tracker = null
        channel = null
        peersById.clear()
        if (ch != null) {
            scope.launch {
                runCatching {
                    ch.broadcast(EVENT_BYE, buildJsonObject { put("id", deviceId) })
                    client.realtime.removeChannel(ch)
                }
            }
        }
        _state.value = _state.value.copy(active = false, connecting = false, code = null, peers = emptyList())
    }

    private suspend fun publishPosition(ch: RealtimeChannel, t: LocationTracker) {
        val loc = t.location.value ?: return
        val name = _state.value.riderName.ifBlank { "Rider" }
        runCatching {
            ch.broadcast(
                EVENT_POS,
                buildJsonObject {
                    put("id", deviceId)
                    put("n", name)
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("brg", if (loc.hasBearing()) loc.bearing else 0f)
                    put("spd", (loc.speed * 3.6f).toInt())
                    put("t", System.currentTimeMillis())
                },
            )
        }.onFailure { DebugLog.w(TAG) { "broadcast failed: ${it.message}" } }
    }

    private fun onPos(msg: JsonObject) {
        runCatching {
            val id = msg["id"]?.jsonPrimitive?.content ?: return
            if (id == deviceId) return
            val peer = Peer(
                id = id,
                name = msg["n"]?.jsonPrimitive?.content ?: "Rider",
                lat = msg["lat"]?.jsonPrimitive?.double ?: return,
                lng = msg["lng"]?.jsonPrimitive?.double ?: return,
                bearing = msg["brg"]?.jsonPrimitive?.float ?: 0f,
                speedKmh = msg["spd"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                // Local receive time, not sender time — phones' clocks disagree.
                updatedAtMs = System.currentTimeMillis(),
            )
            synchronized(peersById) { peersById[id] = peer }
            pushPeers()
        }.onFailure { DebugLog.w(TAG) { "bad pos payload: ${it.message}" } }
    }

    private fun onBye(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.content ?: return
        synchronized(peersById) { peersById.remove(id) }
        pushPeers()
    }

    private fun prunePeers() {
        val cutoff = System.currentTimeMillis() - EXPIRE_MS
        synchronized(peersById) {
            peersById.entries.removeAll { it.value.updatedAtMs < cutoff }
        }
        pushPeers()
    }

    private fun pushPeers() {
        val list = synchronized(peersById) { peersById.values.sortedByDescending { it.updatedAtMs } }
        _state.value = _state.value.copy(peers = list)
    }
}
