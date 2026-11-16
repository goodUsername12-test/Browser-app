package com.fable.browser.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI

object McpSessionManager {
    data class State(val endpoint: String = "", val connected: Boolean = false, val connecting: Boolean = false, val automaticAuth: Boolean = true, val customHeaders: Boolean = false, val expiresAtMs: Long? = null, val status: String = "Disconnected", val activity: List<String> = emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var expiryJob: Job? = null

    fun connect(endpoint: String, durationMinutes: Int, automaticAuth: Boolean, customHeaders: Boolean) {
        val url = endpoint.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return update("Enter an HTTP(S) MCP URL", false)
        runCatching { URI(url) }.onFailure { return update("Invalid MCP URL", false) }
        expiryJob?.cancel()
        val expires = System.currentTimeMillis() + durationMinutes.coerceIn(1, 1440) * 60_000L
        _state.value = State(url, false, true, automaticAuth, customHeaders, expires, "Connecting", listOf("Starting MCP session for $url"))
        scope.launch {
            runCatching {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                val code = conn.responseCode; conn.disconnect(); code
            }.onSuccess { code ->
                val ok = code in 200..499
                _state.value = _state.value.copy(connected = ok, connecting = false, status = if (ok) "Connected, MCP endpoint reachable" else "Endpoint returned HTTP $code", activity = (_state.value.activity + "Endpoint probe returned HTTP $code").takeLast(80))
                if (ok) startExpiry(expires)
            }.onFailure { error -> _state.value = _state.value.copy(connecting = false, status = "Connection failed: ${error.message ?: "network error"}", activity = (_state.value.activity + "Connection failed").takeLast(80)) }
        }
    }

    fun disconnect(reason: String = "Disconnected by user") {
        expiryJob?.cancel(); expiryJob = null
        _state.value = _state.value.copy(connected = false, connecting = false, expiresAtMs = null, status = reason, activity = (_state.value.activity + reason).takeLast(80))
    }

    private fun startExpiry(expires: Long) {
        expiryJob?.cancel(); expiryJob = scope.launch {
            while (System.currentTimeMillis() < expires && _state.value.connected) delay(1_000)
            if (_state.value.connected) disconnect("Session expired")
        }
    }

    private fun update(status: String, connecting: Boolean) { _state.value = _state.value.copy(status = status, connecting = connecting, activity = (_state.value.activity + status).takeLast(80)) }
}
