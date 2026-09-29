package com.antigravity.client.sync

import android.util.Log
import com.antigravity.client.domain.model.ConnectionStatus
import com.antigravity.client.security.TokenStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketManager(
    private val client: OkHttpClient,
    private val tokenStore: TokenStore,
    private val onEventReceived: suspend (JSONObject) -> Unit
) {
    private val tag = "AgyWebSocket"
    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            connect()
        }
    }

    fun stop() {
        isRunning.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _connectionState.value = ConnectionStatus.DISCONNECTED
    }

    fun reconnect() {
        reconnectAttempt = 0
        reconnectJob?.cancel()
        webSocket?.close(1000, "Forced reconnect")
        webSocket = null
        connect()
    }

    private fun connect() {
        if (!isRunning.get()) return

        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            _connectionState.value = ConnectionStatus.ERROR
            Log.w(tag, "Cannot connect: token is missing")
            return
        }

        _connectionState.value = ConnectionStatus.CONNECTING
        val lastSeq = tokenStore.lastReceivedSeq

        var base = tokenStore.serverUrl.trim()
        val wsScheme = if (base.startsWith("https://", ignoreCase = true)) "wss://" else "ws://"
        val hostPart = base.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/')
        val wsUrl = "$wsScheme$hostPart/v1/ws?token=$token&after=$lastSeq"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WebSocket connected successfully (after=$lastSeq)")
                reconnectAttempt = 0
                _connectionState.value = if (lastSeq > 0) ConnectionStatus.REPLAYING else ConnectionStatus.LIVE

                // Transition to LIVE after a brief replay window
                scope.launch {
                    delay(800)
                    if (_connectionState.value == ConnectionStatus.REPLAYING) {
                        _connectionState.value = ConnectionStatus.LIVE
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")

                    // Handle ping
                    if (type == "ping") {
                        webSocket.send("{\"type\":\"pong\"}")
                        return
                    }

                    scope.launch {
                        onEventReceived(json)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing WS message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closed: $code / $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                Log.e(tag, "WebSocket failure: HTTP $code, error=${t.message}")
                if (code == 401 || code == 403) {
                    _connectionState.value = ConnectionStatus.ERROR
                    Log.e(tag, "Authentication failed. Disabling auto-reconnect.")
                    return
                }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        _connectionState.value = ConnectionStatus.RECONNECTING

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
        val delaySec = when (reconnectAttempt) {
            0 -> 1L
            1 -> 2L
            2 -> 4L
            3 -> 8L
            4 -> 16L
            else -> 30L
        }
        reconnectAttempt++

        Log.i(tag, "Scheduling reconnect in ${delaySec}s (attempt #$reconnectAttempt)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delaySec * 1000L)
            if (isRunning.get()) {
                connect()
            }
        }
    }
}
