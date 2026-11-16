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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

object McpSessionManager {
    data class Tool(val name: String, val description: String, val inputSchema: String)
    data class State(val endpoint: String = "", val connected: Boolean = false, val connecting: Boolean = false, val automaticAuth: Boolean = true, val customHeaders: Boolean = false, val expiresAtMs: Long? = null, val status: String = "Disconnected", val tools: List<Tool> = emptyList(), val activity: List<String> = emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var expiryJob: Job? = null
    private var requestId = 0
    private var protocolVersion = "2025-06-18"

    fun connect(endpoint: String, durationMinutes: Int, automaticAuth: Boolean, customHeaders: Boolean) {
        val url = endpoint.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return update("Enter an HTTP(S) MCP URL", false)
        runCatching { URI(url) }.onFailure { return update("Invalid MCP URL", false) }
        expiryJob?.cancel()
        val expires = System.currentTimeMillis() + durationMinutes.coerceIn(1, 1440) * 60_000L
        _state.value = State(url, false, true, automaticAuth, customHeaders, expires, "Connecting", emptyList(), listOf("Starting MCP session for $url"))
        scope.launch {
            try {
                val init = rpc(url, "initialize", JSONObject().apply {
                    put("protocolVersion", protocolVersion)
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().put("name", "Fable Browser").put("version", "1.49.0"))
                })
                protocolVersion = init.optString("protocolVersion", protocolVersion)
                rpc(url, "notifications/initialized", null, notification = true)
                val list = rpc(url, "tools/list", JSONObject())
                val parsed = parseTools(list.optJSONArray("tools"))
                _state.value = _state.value.copy(connected = true, connecting = false, status = "Connected, ${parsed.size} MCP tools available", tools = parsed, activity = (_state.value.activity + "Initialized MCP protocol $protocolVersion" + " | Discovered ${parsed.size} tools").takeLast(80))
                startExpiry(expires)
            } catch (e: AuthRequired) {
                _state.value = _state.value.copy(connecting = false, status = if (automaticAuth) "Authentication required, open the MCP provider authorization flow" else "Authentication required", activity = (_state.value.activity + "MCP endpoint requested authentication").takeLast(80))
            } catch (e: Throwable) {
                _state.value = _state.value.copy(connecting = false, status = "MCP connection failed: ${e.message ?: "unknown error"}", activity = (_state.value.activity + "MCP connection failed").takeLast(80))
            }
        }
    }

    fun callTool(name: String, arguments: JSONObject = JSONObject()) {
        val url = _state.value.endpoint
        if (!_state.value.connected || url.isBlank()) return
        scope.launch {
            _state.value = _state.value.copy(activity = (_state.value.activity + "Calling tool: $name").takeLast(80))
            runCatching { rpc(url, "tools/call", JSONObject().put("name", name).put("arguments", arguments)) }
                .onSuccess { result -> _state.value = _state.value.copy(activity = (_state.value.activity + "Tool result: $name ${result.toString().take(500)}").takeLast(80)) }
                .onFailure { e -> _state.value = _state.value.copy(activity = (_state.value.activity + "Tool error: $name ${e.message ?: "unknown"}").takeLast(80)) }
        }
    }

    fun disconnect(reason: String = "Disconnected by user") {
        expiryJob?.cancel(); expiryJob = null
        _state.value = _state.value.copy(connected = false, connecting = false, expiresAtMs = null, tools = emptyList(), status = reason, activity = (_state.value.activity + reason).takeLast(80))
    }

    private fun startExpiry(expires: Long) {
        expiryJob?.cancel(); expiryJob = scope.launch {
            while (System.currentTimeMillis() < expires && _state.value.connected) delay(1_000)
            if (_state.value.connected) disconnect("Session expired")
        }
    }

    private fun parseTools(arr: JSONArray?): List<Tool> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Tool(it.optString("name"), it.optString("description"), it.optJSONObject("inputSchema")?.toString().orEmpty()) } }
    }

    private fun rpc(url: String, method: String, params: JSONObject?, notification: Boolean = false): JSONObject {
        val id = if (notification) null else ++requestId
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (id != null) body.put("id", id)
        if (params != null) body.put("params", params)
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code == 401 || code == 403) { conn.disconnect(); throw AuthRequired() }
        if (code !in 200..299) { val msg = conn.errorStream?.bufferedReader()?.readText().orEmpty(); conn.disconnect(); throw IllegalStateException("HTTP $code ${msg.take(180)}") }
        val text = conn.inputStream.bufferedReader().readText(); conn.disconnect()
        if (notification) return JSONObject()
        val jsonText = text.lineSequence().firstOrNull { it.trimStart().startsWith("data:") }?.substringAfter("data:")?.trim() ?: text.trim()
        val response = JSONObject(jsonText)
        if (response.has("error")) throw IllegalStateException(response.optJSONObject("error")?.toString() ?: "MCP error")
        return response.optJSONObject("result") ?: response
    }

    private fun update(status: String, connecting: Boolean) { _state.value = _state.value.copy(status = status, connecting = connecting, activity = (_state.value.activity + status).takeLast(80)) }
    private class AuthRequired : Exception()
}
