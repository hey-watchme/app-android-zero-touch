package com.subbrain.zerotouch.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.subbrain.zerotouch.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single row change emitted from Supabase Realtime postgres_changes.
 * record holds the new row (INSERT/UPDATE) or null for DELETE.
 * oldRecord holds the previous row when REPLICA IDENTITY FULL is set.
 */
data class RealtimeChange(
    val table: String,
    val type: String,
    val record: Map<String, Any?>?,
    val oldRecord: Map<String, Any?>?
)

/**
 * Lightweight Supabase Realtime client that speaks the Phoenix Channels
 * protocol over OkHttp WebSocket. One connection subscribes to row changes
 * on zerotouch_sessions and zerotouch_conversation_topics filtered by
 * device_id, then streams them to onChange.
 *
 * Reconnects with exponential backoff. Token refresh is not yet handled;
 * when the JWT expires the server closes the channel and reconnect will
 * use the same (stale) token until updateAccessToken is called.
 */
class SupabaseRealtimeClient(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val tag = "RealtimeClient"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val ref = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var topic: String? = null
    @Volatile private var deviceId: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var onChange: ((RealtimeChange) -> Unit)? = null
    @Volatile private var onStatus: ((String) -> Unit)? = null
    @Volatile private var stopped = true
    private var attempt = 0

    fun connect(
        deviceId: String,
        accessToken: String,
        onChange: (RealtimeChange) -> Unit,
        onStatus: ((String) -> Unit)? = null
    ) {
        if (deviceId.isBlank() || accessToken.isBlank()) {
            Log.w(tag, "connect skipped: missing deviceId or accessToken")
            return
        }
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            Log.w(tag, "connect skipped: SUPABASE_URL / SUPABASE_ANON_KEY not configured")
            return
        }
        disconnect()
        stopped = false
        this.deviceId = deviceId
        this.accessToken = accessToken
        this.onChange = onChange
        this.onStatus = onStatus
        this.topic = "realtime:zerotouch_pipeline_$deviceId"
        attempt = 0
        openSocket()
    }

    fun disconnect() {
        stopped = true
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "client disconnect")
        } catch (_: Exception) {
        }
        webSocket = null
    }

    fun updateAccessToken(newToken: String) {
        accessToken = newToken
        val ws = webSocket ?: return
        val payload = mapOf(
            "topic" to topic,
            "event" to "access_token",
            "payload" to mapOf("access_token" to newToken),
            "ref" to ref.incrementAndGet().toString()
        )
        ws.send(gson.toJson(payload))
    }

    private fun openSocket() {
        if (stopped) return
        val wsUrl = supabaseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
        val request = Request.Builder().url(wsUrl).build()
        Log.d(tag, "opening ws")
        webSocket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(tag, "ws opened")
            attempt = 0
            joinChannel(webSocket)
            startHeartbeat()
            onStatus?.invoke("connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(tag, "ws closing code=$code reason=$reason")
            try {
                webSocket.close(1000, null)
            } catch (_: Exception) {
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(tag, "ws closed code=$code reason=$reason stopped=$stopped")
            heartbeatJob?.cancel()
            heartbeatJob = null
            this@SupabaseRealtimeClient.webSocket = null
            onStatus?.invoke("closed")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(tag, "ws failure: ${t.message}")
            heartbeatJob?.cancel()
            heartbeatJob = null
            this@SupabaseRealtimeClient.webSocket = null
            onStatus?.invoke("failure")
            scheduleReconnect()
        }
    }

    private fun joinChannel(ws: WebSocket) {
        val deviceId = this.deviceId ?: return
        val topic = this.topic ?: return
        val token = this.accessToken ?: return
        val joinRef = ref.incrementAndGet().toString()
        val payload = mapOf(
            "topic" to topic,
            "event" to "phx_join",
            "payload" to mapOf(
                "config" to mapOf(
                    "broadcast" to mapOf("ack" to false, "self" to false),
                    "presence" to mapOf("key" to ""),
                    "postgres_changes" to listOf(
                        mapOf(
                            "event" to "*",
                            "schema" to "public",
                            "table" to "zerotouch_sessions",
                            "filter" to "device_id=eq.$deviceId"
                        ),
                        mapOf(
                            "event" to "*",
                            "schema" to "public",
                            "table" to "zerotouch_conversation_topics",
                            "filter" to "device_id=eq.$deviceId"
                        )
                    )
                ),
                "access_token" to token
            ),
            "ref" to joinRef,
            "join_ref" to joinRef
        )
        ws.send(gson.toJson(payload))
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!stopped) {
                delay(30_000)
                val live = webSocket ?: break
                val payload = mapOf(
                    "topic" to "phoenix",
                    "event" to "heartbeat",
                    "payload" to emptyMap<String, Any>(),
                    "ref" to ref.incrementAndGet().toString()
                )
                try {
                    live.send(gson.toJson(payload))
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attempt++
            val capped = (attempt - 1).coerceAtMost(5)
            val backoff = (1_000L shl capped).coerceAtMost(30_000L)
            Log.d(tag, "reconnect in ${backoff}ms (attempt=$attempt)")
            delay(backoff)
            if (!stopped) openSocket()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleMessage(text: String) {
        val obj = try {
            gson.fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            Log.w(tag, "non-json frame: $text")
            return
        }
        val event = obj.get("event")?.asString ?: return
        when (event) {
            "postgres_changes" -> {
                val payload = obj.getAsJsonObject("payload") ?: return
                val data = payload.getAsJsonObject("data") ?: return
                val table = data.get("table")?.asString ?: return
                val type = data.get("type")?.asString ?: return
                val record = data.getAsJsonObject("record")
                    ?.let { gson.fromJson(it, MutableMap::class.java) as Map<String, Any?> }
                val oldRecord = data.getAsJsonObject("old_record")
                    ?.let { gson.fromJson(it, MutableMap::class.java) as Map<String, Any?> }
                onChange?.invoke(RealtimeChange(table, type, record, oldRecord))
            }
            "phx_reply" -> {
                val payload = obj.getAsJsonObject("payload")
                val status = payload?.get("status")?.asString
                if (status == "error") {
                    Log.w(tag, "phx_reply error: ${payload}")
                }
            }
            "phx_error" -> {
                Log.w(tag, "phx_error: $text")
            }
            "system" -> {
                Log.d(tag, "system: ${obj.getAsJsonObject("payload")}")
            }
            "phx_close" -> {
                Log.d(tag, "phx_close: $text")
            }
        }
    }
}
