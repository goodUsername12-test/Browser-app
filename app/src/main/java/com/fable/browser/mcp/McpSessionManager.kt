package com.fable.browser.mcp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object McpSessionManager {
    data class Tool(
        val name: String,
        val description: String,
        val inputSchema: String,
        val builtIn: Boolean = false
    )

    data class ClickMarker(
        val x: Float,
        val y: Float,
        val label: String,
        val timestampMs: Long = SystemClock.uptimeMillis()
    )

    data class State(
        val endpoint: String = "local://fable-mcp",
        val connected: Boolean = false,
        val connecting: Boolean = false,
        val paused: Boolean = false,
        val showLiveHud: Boolean = true,
        val automaticAuth: Boolean = true,
        val customHeaders: Boolean = false,
        val customHeaderText: String = "",
        val expiresAtMs: Long? = null,
        val remainingMinutes: Int = 0,
        val sessionId: String = "",
        val status: String = "Disconnected",
        val currentAction: String = "Idle",
        val lastClick: ClickMarker? = null,
        val tools: List<Tool> = emptyList(),
        val activity: List<String> = emptyList()
    )

    interface BrowserController {
        fun currentUrl(): String
        fun currentTitle(): String
        fun navigate(url: String)
        fun reload()
        fun goBack()
        fun goForward()
        fun openNewTab(url: String?, incognito: Boolean)
        fun closeActiveTab()
        fun listTabsSummary(): String
        fun activeBlinkWebView(): WebView?
        fun activeRootView(): View?
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State(tools = builtInTools()))
    val state: StateFlow<State> = _state.asStateFlow()
    private var expiryJob: Job? = null
    private var requestId = 0
    private var protocolVersion = "2025-06-18"
    @Volatile private var mcpSessionIdHeader: String? = null
    @Volatile private var controllerRef: BrowserController? = null

    fun bindBrowserController(controller: BrowserController?) {
        controllerRef = controller
    }

    fun setShowLiveHud(show: Boolean) {
        _state.value = _state.value.copy(showLiveHud = show)
    }

    fun setCustomHeaderText(value: String) {
        _state.value = _state.value.copy(customHeaderText = value)
    }

    fun togglePause() {
        val next = !_state.value.paused
        val msg = if (next) "Paused AI / MCP execution" else "Resumed AI / MCP execution"
        _state.value = _state.value.copy(
            paused = next,
            currentAction = if (next) "Paused" else "Ready",
            status = msg,
            activity = appendLog(msg)
        )
    }

    private fun nowStamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun appendLog(entry: String): List<String> {
        return (_state.value.activity + "[${nowStamp()}] $entry").takeLast(80)
    }

    fun builtInTools(): List<Tool> = listOf(
        Tool(
            name = "browser_navigate",
            description = "Navigate the active Fable tab to a URL or search query. Args: {\"url\":\"https://bloxd.io\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}",
            builtIn = true
        ),
        Tool(
            name = "browser_read_page",
            description = "Read the active tab URL, title, visible text summary, and interactive buttons/inputs.",
            inputSchema = "{\"type\":\"object\",\"properties\":{}}",
            builtIn = true
        ),
        Tool(
            name = "browser_click",
            description = "Click a DOM selector or screen coordinates (x, y) in the active tab with a live visual marker. Args: {\"selector\":\"button\"} or {\"x\":420,\"y\":320}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}}}",
            builtIn = true
        ),
        Tool(
            name = "browser_type",
            description = "Type text into the focused input or matching CSS selector. Args: {\"text\":\"hello\",\"selector\":\"input\",\"submit\":true}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"selector\":{\"type\":\"string\"},\"submit\":{\"type\":\"boolean\"}}}",
            builtIn = true
        ),
        Tool(
            name = "browser_scroll",
            description = "Scroll the active page by (dx, dy) pixels. Args: {\"dx\":0,\"dy\":420}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"dx\":{\"type\":\"number\"},\"dy\":{\"type\":\"number\"}}}",
            builtIn = true
        ),
        Tool(
            name = "browser_tabs",
            description = "Manage browser tabs (list, new, close, back, forward, reload). Args: {\"action\":\"list\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"}}}",
            builtIn = true
        ),
        Tool(
            name = "browser_eval_js",
            description = "Evaluate JavaScript in the active Blink tab and return the result. Args: {\"script\":\"document.title\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"script\":{\"type\":\"string\"}}}",
            builtIn = true
        ),
        Tool(
            name = "browser_game_input",
            description = "Send keyboard/game action (KeyW, KeyA, KeyS, KeyD, Space) to the active game tab. Args: {\"key\":\"KeyW\",\"durationMs\":400}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"durationMs\":{\"type\":\"number\"}}}",
            builtIn = true
        )
    )

    fun connect(
        endpoint: String,
        durationMinutes: Int,
        automaticAuth: Boolean,
        customHeaders: Boolean,
        customHeaderText: String = _state.value.customHeaderText
    ) {
        val raw = endpoint.trim().ifBlank { "local://fable-mcp" }
        val mins = durationMinutes.coerceIn(1, 1440)
        val expires = System.currentTimeMillis() + mins * 60_000L
        expiryJob?.cancel()
        mcpSessionIdHeader = null

        if (raw.startsWith("local://", ignoreCase = true) || raw.equals("local", ignoreCase = true)) {
            val localTools = builtInTools()
            _state.value = State(
                endpoint = "local://fable-mcp",
                connected = true,
                connecting = false,
                paused = false,
                showLiveHud = true,
                automaticAuth = automaticAuth,
                customHeaders = customHeaders,
                customHeaderText = customHeaderText,
                expiresAtMs = expires,
                remainingMinutes = mins,
                sessionId = "local-${System.currentTimeMillis() % 100000}",
                status = "Connected (Local Fable MCP · ${localTools.size} live browser tools ready)",
                currentAction = "Watching browser session",
                tools = localTools,
                activity = appendLog("Started local Fable MCP session ($mins min) with ${localTools.size} browser control tools")
            )
            startExpiry(expires)
            return
        }

        if (!raw.startsWith("https://", ignoreCase = true) && !raw.startsWith("http://", ignoreCase = true)) {
            update("Enter an HTTP(S) MCP URL or local://fable-mcp", false)
            return
        }
        runCatching { URI(raw) }.onFailure {
            update("Invalid MCP URL", false)
            return
        }

        _state.value = _state.value.copy(
            endpoint = raw,
            connected = false,
            connecting = true,
            paused = false,
            showLiveHud = true,
            automaticAuth = automaticAuth,
            customHeaders = customHeaders,
            customHeaderText = customHeaderText,
            expiresAtMs = expires,
            remainingMinutes = mins,
            status = "Connecting to $raw...",
            currentAction = "Handshake initialize",
            activity = appendLog("Starting MCP session for $raw")
        )
        scope.launch {
            try {
                val init = rpc(raw, "initialize", JSONObject().apply {
                    put("protocolVersion", protocolVersion)
                    put("capabilities", JSONObject().put("roots", JSONObject().put("listChanged", true)))
                    put("clientInfo", JSONObject().put("name", "Fable Browser").put("version", "1.50.0"))
                })
                protocolVersion = init.optString("protocolVersion", protocolVersion)
                runCatching { rpc(raw, "notifications/initialized", null, notification = true) }
                val list = runCatching { rpc(raw, "tools/list", JSONObject()) }.getOrDefault(JSONObject())
                val remoteTools = parseTools(list.optJSONArray("tools"))
                val merged = (remoteTools + builtInTools()).distinctBy { it.name }
                _state.value = _state.value.copy(
                    connected = true,
                    connecting = false,
                    sessionId = mcpSessionIdHeader.orEmpty(),
                    status = "Connected · ${remoteTools.size} remote + ${builtInTools().size} browser tools",
                    currentAction = "Connected & Ready",
                    tools = merged,
                    activity = appendLog("Initialized MCP $protocolVersion · ${remoteTools.size} remote tools + ${builtInTools().size} browser tools")
                )
                startExpiry(expires)
            } catch (e: AuthRequired) {
                val localFallback = builtInTools()
                if (automaticAuth) {
                    mainHandler.post { controllerRef?.openNewTab(raw, false) }
                }
                _state.value = _state.value.copy(
                    connecting = false,
                    tools = localFallback,
                    currentAction = "Auth Required",
                    status = if (automaticAuth) {
                        "Authentication required: opened endpoint in browser tab (or add Bearer header)"
                    } else {
                        "Authentication required: enable Custom Headers to pass Authorization Bearer token"
                    },
                    activity = appendLog("MCP endpoint requested authentication (HTTP 401/403)")
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    connecting = false,
                    currentAction = "Connection error",
                    status = "MCP connection failed: ${e.message ?: "unknown error"}",
                    activity = appendLog("MCP connection failed: ${e.message ?: "error"}")
                )
            }
        }
    }

    fun callTool(name: String, arguments: JSONObject = JSONObject()) {
        val st = _state.value
        if (!st.connected) {
            update("Connect MCP session first before running $name", false)
            return
        }
        if (st.paused) {
            update("MCP is paused. Resume before running $name", false)
            return
        }
        val builtIn = builtInTools().any { it.name == name }
        if (builtIn) {
            executeBuiltInBrowserTool(name, arguments)
            return
        }
        val url = st.endpoint
        scope.launch {
            _state.value = _state.value.copy(
                currentAction = "Running $name",
                activity = appendLog("Calling remote MCP tool: $name ${arguments.toString().take(120)}")
            )
            runCatching {
                rpc(url, "tools/call", JSONObject().put("name", name).put("arguments", arguments))
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    currentAction = "Done: $name",
                    activity = appendLog("Tool result ($name): ${result.toString().take(360)}")
                )
                // If the remote tool returned a browser action directive, execute it automatically
                val actionObj = result.optJSONObject("browserAction")
                if (actionObj != null) {
                    val actName = actionObj.optString("tool")
                    val actArgs = actionObj.optJSONObject("arguments") ?: JSONObject()
                    if (actName.isNotBlank()) executeBuiltInBrowserTool(actName, actArgs)
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    currentAction = "Error: $name",
                    activity = appendLog("Tool error ($name): ${e.message ?: "unknown"}")
                )
            }
        }
    }

    private fun executeBuiltInBrowserTool(name: String, args: JSONObject) {
        val ctrl = controllerRef
        _state.value = _state.value.copy(
            currentAction = "$name(${args.toString().take(60)})",
            activity = appendLog("AI Action -> $name ${args.toString().take(140)}")
        )
        if (ctrl == null) {
            _state.value = _state.value.copy(
                activity = appendLog("Browser tab controller not attached yet; open the Browser screen")
            )
            return
        }
        mainHandler.post {
            try {
                when (name) {
                    "browser_navigate" -> {
                        val target = args.optString("url").ifBlank { "https://bloxd.io" }
                        ctrl.navigate(target)
                        logActionResult(name, "Navigated active tab to $target")
                    }
                    "browser_read_page" -> {
                        val wv = ctrl.activeBlinkWebView()
                        if (wv != null) {
                            wv.evaluateJavascript(
                                "(function(){try{const btns=Array.from(document.querySelectorAll('button,a,input,[role=button]')).slice(0,12).map(e=>(e.innerText||e.value||e.placeholder||e.tagName||'').trim()).filter(Boolean);return JSON.stringify({title:document.title,url:location.href,text:(document.body&&document.body.innerText||'').slice(0,260),interactive:btns});}catch(e){return String(e);}})()"
                            ) { res ->
                                logActionResult(name, "Page state: ${res?.take(340) ?: ctrl.currentUrl()}")
                            }
                        } else {
                            logActionResult(name, "Tab title='${ctrl.currentTitle()}' url='${ctrl.currentUrl()}'")
                        }
                    }
                    "browser_click" -> {
                        val selector = args.optString("selector")
                        val wv = ctrl.activeBlinkWebView()
                        val root = wv ?: ctrl.activeRootView()
                        if (selector.isNotBlank() && wv != null) {
                            val escaped = JSONObject.quote(selector)
                            wv.evaluateJavascript(
                                "(function(){try{const el=document.querySelector($escaped);if(!el)return 'not_found';const r=el.getBoundingClientRect();el.click();return JSON.stringify({clicked:true,x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),text:(el.innerText||el.tagName||'').slice(0,40)});}catch(e){return String(e);}})()"
                            ) { res ->
                                logActionResult(name, "Clicked selector $selector -> $res")
                            }
                        } else if (root != null) {
                            val x = args.optDouble("x", (root.width / 2.0).coerceAtLeast(100.0)).toFloat()
                            val y = args.optDouble("y", (root.height / 2.0).coerceAtLeast(100.0)).toFloat()
                            dispatchSyntheticTap(root, x, y)
                            _state.value = _state.value.copy(
                                lastClick = ClickMarker(x, y, "Click (${x.toInt()}, ${y.toInt()})")
                            )
                            logActionResult(name, "Tapped screen coordinates (${x.toInt()}, ${y.toInt()})")
                        } else {
                            logActionResult(name, "No active browser view available to click")
                        }
                    }
                    "browser_type" -> {
                        val text = args.optString("text", "Hello from Fable MCP")
                        val selector = args.optString("selector", "input,textarea,[contenteditable=true]")
                        val submit = args.optBoolean("submit", false)
                        val wv = ctrl.activeBlinkWebView()
                        if (wv != null) {
                            val qSel = JSONObject.quote(selector)
                            val qTxt = JSONObject.quote(text)
                            wv.evaluateJavascript(
                                "(function(){try{const el=document.querySelector($qSel)||document.activeElement;if(!el)return 'no_input';el.focus();if('value' in el){el.value=$qTxt;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}else{el.innerText=$qTxt;}if($submit && el.form){el.form.requestSubmit?el.form.requestSubmit():el.form.submit();}return 'typed:'+($qTxt).slice(0,40);}catch(e){return String(e);}})()"
                            ) { res ->
                                logActionResult(name, "Typed into page -> $res")
                            }
                        } else {
                            logActionResult(name, "Active tab is in Gecko mode; switch tab to Blink × Gecko for DOM typing or use URL navigation")
                        }
                    }
                    "browser_scroll" -> {
                        val dx = args.optInt("dx", 0)
                        val dy = args.optInt("dy", 360)
                        val wv = ctrl.activeBlinkWebView()
                        if (wv != null) {
                            wv.scrollBy(dx, dy)
                            wv.evaluateJavascript("window.scrollBy($dx,$dy);", null)
                            logActionResult(name, "Scrolled page by dx=$dx, dy=$dy")
                        } else {
                            logActionResult(name, "Scroll requested (dx=$dx, dy=$dy)")
                        }
                    }
                    "browser_tabs" -> {
                        when (args.optString("action", "list").lowercase(Locale.US)) {
                            "new" -> {
                                val u = args.optString("url").ifBlank { "https://www.google.com" }
                                ctrl.openNewTab(u, false)
                                logActionResult(name, "Opened new tab: $u")
                            }
                            "close" -> {
                                ctrl.closeActiveTab()
                                logActionResult(name, "Closed active tab")
                            }
                            "back" -> {
                                ctrl.goBack()
                                logActionResult(name, "Navigated back")
                            }
                            "forward" -> {
                                ctrl.goForward()
                                logActionResult(name, "Navigated forward")
                            }
                            "reload" -> {
                                ctrl.reload()
                                logActionResult(name, "Reloaded active tab")
                            }
                            else -> {
                                logActionResult(name, ctrl.listTabsSummary())
                            }
                        }
                    }
                    "browser_eval_js" -> {
                        val script = args.optString("script", "document.title + ' | ' + location.href")
                        val wv = ctrl.activeBlinkWebView()
                        if (wv != null) {
                            wv.evaluateJavascript(script) { res ->
                                logActionResult(name, "JS result: ${res?.take(300)}")
                            }
                        } else {
                            logActionResult(name, "Blink WebView not active on current tab")
                        }
                    }
                    "browser_game_input" -> {
                        val key = args.optString("key", "KeyW")
                        val dur = args.optLong("durationMs", 350L).coerceIn(50L, 5_000L)
                        val wv = ctrl.activeBlinkWebView()
                        if (wv != null) {
                            val qKey = JSONObject.quote(key)
                            wv.evaluateJavascript(
                                "(function(){try{const k=$qKey;const down=new KeyboardEvent('keydown',{code:k,key:k,bubbles:true});window.dispatchEvent(down);document.dispatchEvent(down);setTimeout(()=>{const up=new KeyboardEvent('keyup',{code:k,key:k,bubbles:true});window.dispatchEvent(up);document.dispatchEvent(up);},$dur);return 'sent:'+k+' for '+$dur+'ms';}catch(e){return String(e);}})()"
                            ) { res ->
                                logActionResult(name, "Game input -> $res")
                            }
                        } else {
                            logActionResult(name, "Open a game in Blink × Gecko mode to dispatch game keys")
                        }
                    }
                }
            } catch (t: Throwable) {
                logActionResult(name, "Error: ${t.message ?: "failed"}")
            }
        }
    }

    private fun dispatchSyntheticTap(view: View, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 45L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun logActionResult(toolName: String, message: String) {
        _state.value = _state.value.copy(
            currentAction = "$toolName: ${message.take(56)}",
            activity = appendLog("$toolName -> $message")
        )
    }

    fun disconnect(reason: String = "Disconnected by user") {
        expiryJob?.cancel()
        expiryJob = null
        mcpSessionIdHeader = null
        _state.value = _state.value.copy(
            connected = false,
            connecting = false,
            paused = false,
            expiresAtMs = null,
            remainingMinutes = 0,
            sessionId = "",
            currentAction = "Idle",
            lastClick = null,
            status = reason,
            activity = appendLog(reason)
        )
    }

    private fun startExpiry(expires: Long) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (System.currentTimeMillis() < expires && _state.value.connected) {
                val remMin = ((expires - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
                if (_state.value.remainingMinutes != remMin) {
                    _state.value = _state.value.copy(remainingMinutes = remMin)
                }
                delay(2_000L)
            }
            if (_state.value.connected) disconnect("Session expired")
        }
    }

    private fun parseTools(arr: JSONArray?): List<Tool> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Tool(
                    name = it.optString("name"),
                    description = it.optString("description"),
                    inputSchema = it.optJSONObject("inputSchema")?.toString().orEmpty(),
                    builtIn = false
                )
            }
        }
    }

    private fun rpc(url: String, method: String, params: JSONObject?, notification: Boolean = false): JSONObject {
        val id = if (notification) null else ++requestId
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (id != null) body.put("id", id)
        if (params != null) body.put("params", params)
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        mcpSessionIdHeader?.takeIf { it.isNotBlank() }?.let { sid ->
            conn.setRequestProperty("Mcp-Session-Id", sid)
        }
        val st = _state.value
        if (st.customHeaders && st.customHeaderText.isNotBlank()) {
            st.customHeaderText.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (':' in trimmed) {
                        val k = trimmed.substringBefore(':').trim()
                        val v = trimmed.substringAfter(':').trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) conn.setRequestProperty(k, v)
                    } else {
                        conn.setRequestProperty("Authorization", if (trimmed.startsWith("Bearer ", true)) trimmed else "Bearer $trimmed")
                    }
                }
            }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val respSessionId = conn.getHeaderField("Mcp-Session-Id") ?: conn.getHeaderField("mcp-session-id")
        if (!respSessionId.isNullOrBlank()) {
            mcpSessionIdHeader = respSessionId
        }
        if (code == 401 || code == 403) {
            conn.disconnect()
            throw AuthRequired()
        }
        if (code !in 200..299) {
            val msg = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            throw IllegalStateException("HTTP $code ${msg.take(180)}")
        }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        if (notification || text.isBlank()) return JSONObject()
        val jsonText = text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("data:") && it.substringAfter("data:").trim().startsWith("{") }
            ?.substringAfter("data:")
            ?.trim()
            ?: text.trim()
        val response = JSONObject(jsonText)
        if (response.has("error")) throw IllegalStateException(response.optJSONObject("error")?.toString() ?: "MCP error")
        return response.optJSONObject("result") ?: response
    }

    private fun update(status: String, connecting: Boolean) {
        _state.value = _state.value.copy(
            status = status,
            connecting = connecting,
            activity = appendLog(status)
        )
    }

    private class AuthRequired : Exception()
}
