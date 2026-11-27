package com.fable.browser.engine

import com.fable.browser.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class MemFreezeStrength(val level: Int, val label: String, val shortLabel: String) {
    NORMAL(0, "Normal (Battery)", "Normal"),
    STRONG(1, "Strong (667Hz)", "Strong"),
    MAX(2, "MAX Titan (2500Hz)", "MAX");

    companion object {
        fun fromLevel(lvl: Int): MemFreezeStrength = entries.firstOrNull { it.level == lvl } ?: STRONG
    }
}

enum class MemFreezeRule(val jsCode: String, val nativeCode: Int, val symbol: String, val label: String) {
    EXACT("eq", 0, "=", "Exact Lock"),
    MIN("min", 1, ">=", "Min Floor (>=)"),
    MAX("max", 2, "<=", "Max Cap (<=)"),
    RANGE("range", 3, "[a..b]", "Range Clamp (a..b)"),
    STEP("step", 4, "+=/s", "Step Ramp (+/s)");

    fun next(): MemFreezeRule = when (this) {
        EXACT -> MIN
        MIN -> MAX
        MAX -> RANGE
        RANGE -> STEP
        STEP -> EXACT
    }

    companion object {
        fun fromCode(code: String?): MemFreezeRule = when (code) {
            "min" -> MIN
            "max" -> MAX
            "range" -> RANGE
            "step" -> STEP
            else -> EXACT
        }
    }
}

data class MemStructField(
    val addr: String,
    val delta: String,
    val type: String,
    val value: String,
    val i32Val: String,
    val f32Val: String,
    val u32Val: String = ""
)

data class MemPointerItem(
    val ptrAddr: String,
    val pointsTo: String,
    val offset: String,
    val region: String
)

data class MemTraceEvent(
    val ts: Long,
    val oldVal: String,
    val newVal: String,
    val delta: String,
    val caller: String
)

data class MemScanResultItem(
    val addr: String,
    val value: String,
    val prevValue: String = "",
    val type: String = "i32",
    val kind: String = "native",
    val role: String = "game",
    val score: Int = 78,
    val engine: String = "",
    val lastChangedAtMs: Long = 0L,
    val xorMode: Int = 0,
    val xorKey: Long = 0L,
    val xorKeyAddr: String = ""
)

data class MemWatchEntry(
    val id: String,
    val scope: MemScope,
    val addr: String,
    val label: String,
    val dataType: MemDataType,
    val currentValue: String,
    val originalValue: String,
    val role: String = "game",
    val frozen: Boolean = false,
    val frozenValue: String = "",
    val freezeRule: MemFreezeRule = MemFreezeRule.EXACT,
    val freezeMode: String = "",
    val blockedWrites: Long = 0L,
    val writesPerSec: Int = 0,
    val freezeStatus: String = "",
    val lastChangedAtMs: Long = 0L,
    val valueHistory: List<String> = emptyList(),
    val uiWarningReason: String = "",
    val xorMode: Int = 0,
    val xorKey: Long = 0L,
    val xorKeyAddr: String = "",
    val tracing: Boolean = false,
    val traceCount: Int = 0,
    val traceEvents: List<MemTraceEvent> = emptyList(),
    val attached: Boolean = true
)

data class MemSavedTable(
    val id: String,
    val name: String,
    val origin: String,
    val savedAtMs: Long,
    val entries: List<MemWatchEntry>
)

data class MemRegionItem(
    val start: String,
    val end: String,
    val kb: Long,
    val perms: String,
    val label: String
)

data class MemHexState(
    val targetAddr: String = "",
    val scope: MemScope = MemScope.PAGE_WASM,
    val displayAddr: String = "",
    val baseOffset: Long = 0L,
    val hexBytes: String = "",
    val error: String? = null
)

data class MemoryEngineUiState(
    val scope: MemScope = MemScope.PAGE_WASM,
    val dataType: MemDataType = MemDataType.AUTO,
    val scanType: MemScanType = MemScanType.EXACT,
    val scanTimeoutSec: Int = WebsiteMemoryEngine.DEFAULT_TIMEOUT_SEC,
    val busy: Boolean = false,
    val scanElapsedMs: Long = 0L,
    val lastScanDurationMs: Long = 0L,
    val lastScanTimedOut: Boolean = false,
    val fallbackUsed: Boolean = false,
    val snapshotActive: Boolean = false,
    val hasActiveScan: Boolean = false,
    val canUndo: Boolean = false,
    val undoDepth: Int = 0,
    val liveScanRefresh: Boolean = false,
    val hexLiveRefresh: Boolean = false,
    val totalMatches: Int = 0,
    val wasmCount: Int = 0,
    val bufCount: Int = 0,
    val workerCount: Int = 0,
    val frameCount: Int = 1,
    val skippedXOrigin: Int = 0,
    val detectedEngines: List<String> = emptyList(),
    val results: List<MemScanResultItem> = emptyList(),
    val selectedScanAddrs: Set<String> = emptySet(),
    val watchList: List<MemWatchEntry> = emptyList(),
    val selectedWatchIds: Set<String> = emptySet(),
    val hex: MemHexState = MemHexState(),
    val structOriginAddr: String = "",
    val structScope: MemScope = MemScope.PAGE_WASM,
    val structFields: List<MemStructField> = emptyList(),
    val pointerOriginAddr: String = "",
    val pointerScope: MemScope = MemScope.PAGE_WASM,
    val pointerResults: List<MemPointerItem> = emptyList(),
    val savedTables: List<MemSavedTable> = emptyList(),
    val regions: List<MemRegionItem> = emptyList(),
    val regionsTotalKb: Long = 0L,
    val freezeStrength: MemFreezeStrength = MemFreezeStrength.STRONG,
    val autoReattachOnReload: Boolean = true,
    val hideUiNoise: Boolean = false,
    val uiHiddenCount: Int = 0,
    val pageHookLive: Boolean = false,
    val hookedEarly: Boolean = false,
    val docGen: String = "",
    val mirrorOriginAddr: String = "",
    val mirrorScope: MemScope = MemScope.PAGE_WASM,
    val mirrorResults: List<MemScanResultItem> = emptyList(),
    val statusText: String = "Enter a number and tap First Scan",
    val activeEngineLabel: String = "Auto"
)

object MemoryCheatTableCodec {
    private const val MAX_TABLES = 20
    private const val MAX_ENTRIES_PER_TABLE = 80
    private const val MAX_JSON_BYTES = 60_000

    fun encodeTables(tables: List<MemSavedTable>): String {
        val arr = JSONArray()
        for (t in tables.take(MAX_TABLES)) {
            arr.put(encodeSingleTableObj(t))
        }
        return arr.toString()
    }

    fun encodeSingleTable(table: MemSavedTable): String = encodeSingleTableObj(table).toString()

    private fun encodeSingleTableObj(t: MemSavedTable): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", t.id)
        put("name", t.name.take(40))
        put("origin", t.origin.take(120))
        put("savedAtMs", t.savedAtMs)
        val eArr = JSONArray()
        for (e in t.entries.take(MAX_ENTRIES_PER_TABLE)) {
            eArr.put(JSONObject().apply {
                put("scope", e.scope.name)
                put("addr", e.addr)
                put("label", e.label.take(36))
                put("dt", e.dataType.jsCode)
                put("val", e.currentValue)
                put("orig", e.originalValue)
                put("rule", e.freezeRule.jsCode)
                put("fval", e.frozenValue)
                if (e.xorMode != 0) {
                    put("xm", e.xorMode)
                    put("xk", e.xorKey)
                    put("xka", e.xorKeyAddr)
                }
            })
        }
        put("entries", eArr)
    }

    fun decodeTables(raw: String): List<MemSavedTable> {
        if (raw.isBlank() || raw.length > MAX_JSON_BYTES) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<MemSavedTable>()
        for (i in 0 until minOf(arr.length(), MAX_TABLES)) {
            val o = arr.optJSONObject(i) ?: continue
            decodeSingleTableObj(o)?.let { out.add(it) }
        }
        return out
    }

    fun decodeSingleTable(raw: String): MemSavedTable? {
        if (raw.isBlank() || raw.length > MAX_JSON_BYTES) return null
        val trimmed = raw.trim()
        val obj = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed).optJSONObject(0)
            else JSONObject(trimmed)
        }.getOrNull() ?: return null
        return decodeSingleTableObj(obj)
    }

    private fun decodeSingleTableObj(o: JSONObject): MemSavedTable? {
        val name = o.optString("name").trim().take(40)
        val eArr = o.optJSONArray("entries") ?: return null
        val entries = ArrayList<MemWatchEntry>()
        for (i in 0 until minOf(eArr.length(), MAX_ENTRIES_PER_TABLE)) {
            val eo = eArr.optJSONObject(i) ?: continue
            val addr = eo.optString("addr").trim()
            if (addr.isEmpty() || addr.length > 200) continue
            val sc = runCatching { MemScope.valueOf(eo.optString("scope", MemScope.PAGE_WASM.name)) }
                .getOrDefault(MemScope.PAGE_WASM)
            val dt = MemDataType.entries.firstOrNull { it.jsCode == eo.optString("dt") } ?: MemDataType.INT32
            val rule = MemFreezeRule.fromCode(eo.optString("rule"))
            val valStr = eo.optString("val", "0").take(64)
            val origStr = eo.optString("orig", valStr).take(64)
            entries.add(
                MemWatchEntry(
                    id = "${sc.name}|$addr",
                    scope = sc,
                    addr = addr,
                    label = eo.optString("label", addr.takeLast(28)).take(36),
                    dataType = dt,
                    currentValue = valStr,
                    originalValue = origStr,
                    frozen = false, // Never auto-freeze imported entries before verifying attachment!
                    frozenValue = eo.optString("fval", valStr).take(64),
                    freezeRule = rule,
                    xorMode = eo.optInt("xm", 0),
                    xorKey = eo.optLong("xk", 0L),
                    xorKeyAddr = eo.optString("xka", ""),
                    attached = false
                )
            )
        }
        if (entries.isEmpty()) return null
        return MemSavedTable(
            id = o.optString("id").ifBlank { "tbl_${System.currentTimeMillis()}" },
            name = name.ifBlank { "Cheat Table" },
            origin = o.optString("origin", ""),
            savedAtMs = o.optLong("savedAtMs", System.currentTimeMillis()),
            entries = entries
        )
    }
}

@Singleton
class MemoryEngineManager @Inject constructor(
    private val tabManager: TabManager,
    private val proxyConfig: ProxyConfig,
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MemoryEngineUiState())
    val state: StateFlow<MemoryEngineUiState> = _state.asStateFlow()

    private var watchLoopJob: Job? = null
    private var activeScanJob: Job? = null
    private var scanTimerJob: Job? = null
    @Volatile private var engineActive: Boolean = false
    private var savedTablesLoaded = false
    private var lastObservedUrl: String = ""
    private var lastObservedLoading: Boolean = false
    private var lastKnownDocGen: String = ""
    private var pageHookPingJob: Job? = null

    fun setEngineActive(enabled: Boolean) {
        engineActive = enabled
        if (!enabled) {
            cancelActiveScan()
            unfreezeAll()
            clearAllTraces()
            resetScan()
            stopWatchLoop()
        } else {
            loadSavedTablesIfNeeded()
            ensureWatchLoop()
            refreshAutoHookStatus(autoReattach = false)
        }
    }

    private fun loadSavedTablesIfNeeded() {
        if (savedTablesLoaded) return
        savedTablesLoaded = true
        scope.launch {
            val raw = runCatching { settingsStore.settings.first().memoryEngineSavedTablesJson }.getOrDefault("[]")
            val parsed = MemoryCheatTableCodec.decodeTables(raw)
            _state.update { it.copy(savedTables = parsed) }
        }
    }

    fun setScanTimeoutSec(sec: Int) {
        val clamped = WebsiteMemoryEngine.clampTimeoutSec(sec)
        _state.update { it.copy(scanTimeoutSec = clamped) }
    }

    fun setScope(newScope: MemScope) {
        if (_state.value.scope == newScope) return
        cancelActiveScan()
        _state.update {
            it.copy(
                scope = newScope,
                hasActiveScan = false,
                canUndo = false,
                undoDepth = 0,
                snapshotActive = false,
                totalMatches = 0,
                results = emptyList(),
                selectedScanAddrs = emptySet(),
                statusText = "Switched to ${newScope.label}"
            )
        }
    }

    fun setDataType(dt: MemDataType) {
        _state.update { it.copy(dataType = dt) }
    }

    fun setScanType(st: MemScanType) {
        _state.update { it.copy(scanType = st) }
    }

    fun setFreezeStrength(strength: MemFreezeStrength) {
        _state.update {
            it.copy(
                freezeStrength = strength,
                statusText = "Freeze strength: ${strength.label}"
            )
        }
        scope.launch {
            withContext(Dispatchers.IO) { NativeMemoryScanner.setFreezeStrength(strength.level) }
            if (engineActive) {
                runCatching {
                    runPageCommand(JSONObject().apply {
                        put("op", "setFreezeStrength")
                        put("level", strength.level)
                    })
                }
            }
        }
    }

    fun setAutoReattachOnReload(enabled: Boolean) {
        _state.update { it.copy(autoReattachOnReload = enabled) }
    }

    fun toggleHideUiNoise() {
        _state.update {
            val next = !it.hideUiNoise
            it.copy(
                hideUiNoise = next,
                statusText = if (next) "Hide UI labels ON (prioritizing gameplay values)" else "Showing all hits (including UI labels)"
            )
        }
    }

    fun onActiveTabPageStateChanged(url: String, isLoading: Boolean) {
        if (!engineActive) {
            lastObservedUrl = url
            lastObservedLoading = isLoading
            return
        }
        val wasLoading = lastObservedLoading
        val urlChanged = url.isNotBlank() && url != lastObservedUrl
        lastObservedUrl = url
        lastObservedLoading = isLoading
        if (!isLoading && url.startsWith("http") && (wasLoading || urlChanged || !_state.value.pageHookLive)) {
            refreshAutoHookStatus(autoReattach = wasLoading || urlChanged)
        }
    }

    fun refreshAutoHookStatus(autoReattach: Boolean = false) {
        if (!engineActive) return
        pageHookPingJob?.cancel()
        pageHookPingJob = scope.launch {
            if (autoReattach) delay(220L)
            val raw = runPageCommand(JSONObject().apply { put("op", "ping") })
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                val newGen = obj.optString("docGen")
                val early = obj.optBoolean("hookedEarly", false)
                val engs = parseEngines(obj.optJSONArray("engines"))
                val docChanged = newGen.isNotBlank() && lastKnownDocGen.isNotBlank() && newGen != lastKnownDocGen
                if (newGen.isNotBlank()) lastKnownDocGen = newGen
                // Sync current freeze strength into the freshly hooked page!
                val curLvl = _state.value.freezeStrength.level
                runCatching {
                    runPageCommand(JSONObject().apply {
                        put("op", "setFreezeStrength")
                        put("level", curLvl)
                    })
                }
                _state.update {
                    it.copy(
                        pageHookLive = true,
                        hookedEarly = early,
                        docGen = newGen.ifBlank { it.docGen },
                        wasmCount = obj.optInt("wasmCount", it.wasmCount),
                        bufCount = obj.optInt("bufCount", it.bufCount),
                        workerCount = obj.optInt("workerCount", it.workerCount),
                        frameCount = obj.optInt("frameCount", it.frameCount),
                        detectedEngines = if (engs.isNotEmpty()) engs else it.detectedEngines,
                        statusText = if (docChanged) {
                            "Auto-hooked after reload (${if (early) "doc_start" else "live"})"
                        } else it.statusText
                    )
                }
                if ((docChanged || autoReattach) && _state.value.autoReattachOnReload &&
                    _state.value.watchList.any { it.scope == MemScope.PAGE_WASM }) {
                    autoReattachAndRearmAfterReload()
                }
            }
        }
    }

    private suspend fun autoReattachAndRearmAfterReload() {
        val pageWatches = _state.value.watchList.filter { it.scope == MemScope.PAGE_WASM }
        if (pageWatches.isEmpty()) return
        val updatedById = HashMap<String, MemWatchEntry>()
        val toRearm = ArrayList<MemWatchEntry>()
        var reattachedCnt = 0
        for (e in pageWatches) {
            val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(e.addr)
            val raw = runPageCommand(JSONObject().apply {
                put("op", "reattach")
                put("id", e.addr)
                put("dt", e.dataType.jsCode)
                if (tFrame != null) put("targetFrame", tFrame)
            })
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                reattachedCnt++
                val liveVal = obj.optString("val", e.currentValue)
                val upd = e.copy(
                    currentValue = liveVal,
                    attached = true,
                    freezeStatus = if (e.frozen) "arming" else ""
                )
                updatedById[e.id] = upd
                if (e.frozen) toRearm.add(upd)
            } else {
                updatedById[e.id] = e.copy(
                    attached = false,
                    freezeStatus = if (e.frozen) "detached" else ""
                )
            }
        }
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { updatedById[it.id] ?: it },
                statusText = "Auto-hooked reload: reattached $reattachedCnt/${pageWatches.size} watches" +
                    if (toRearm.isNotEmpty()) " · re-arming ${toRearm.size} lock(s)" else ""
            )
        }
        if (toRearm.isNotEmpty()) {
            applyFreezeBatch(toRearm, freeze = true)
        }
    }

    fun findMirrorsForWatch(watchId: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        _state.update { it.copy(busy = true, statusText = "Finding mirror copies of ${entry.label} (${entry.currentValue})...") }
        scope.launch {
            val raw = when (entry.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    NativeMemoryScanner.findMirrors(entry.addr, entry.dataType, 60, 8_000)
                }
                MemScope.PAGE_WASM -> {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(entry.addr)
                    runPageCommand(JSONObject().apply {
                        put("op", "findMirrors")
                        put("id", entry.addr)
                        if (tFrame != null) put("targetFrame", tFrame)
                    })
                }
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                val arr = obj.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<MemScanResultItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    items.add(
                        MemScanResultItem(
                            addr = o.optString("addr"),
                            value = o.optString("val"),
                            prevValue = o.optString("val"),
                            type = o.optString("type", entry.dataType.jsCode),
                            kind = o.optString("kind", if (entry.scope == MemScope.FABLE_NATIVE) "native" else "js"),
                            role = o.optString("role", "game"),
                            score = o.optInt("score", 80),
                            engine = o.optString("engine", "")
                        )
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        mirrorOriginAddr = entry.addr,
                        mirrorScope = entry.scope,
                        mirrorResults = items,
                        statusText = "Found ${items.size} mirror copy${if (items.size == 1) "" else "ies"} of ${entry.label}"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        mirrorOriginAddr = entry.addr,
                        mirrorResults = emptyList(),
                        statusText = obj?.optString("error") ?: "Mirror search failed"
                    )
                }
            }
        }
    }

    fun lockAllMirrors() {
        val st = _state.value
        val mirrors = st.mirrorResults.take(30)
        if (mirrors.isEmpty()) return
        val origin = st.watchList.firstOrNull { it.addr == st.mirrorOriginAddr }
        val freezeVal = origin?.frozenValue?.ifBlank { origin.currentValue } ?: mirrors.first().value
        val freezeRule = origin?.freezeRule ?: MemFreezeRule.EXACT
        val existingIds = st.watchList.map { it.id }.toHashSet()
        val added = ArrayList<MemWatchEntry>()
        val toFreeze = ArrayList<MemWatchEntry>()
        for (m in mirrors) {
            val dt = resolveDataType(m.type, origin?.dataType ?: MemDataType.INT32)
            val wid = "${st.mirrorScope.name}|${m.addr}"
            val ex = st.watchList.firstOrNull { it.id == wid }
            if (ex == null && wid !in existingIds) {
                existingIds.add(wid)
                val ne = MemWatchEntry(
                    id = wid,
                    scope = st.mirrorScope,
                    addr = m.addr,
                    label = "Mirror: " + m.addr.removePrefix("js:").takeLast(24),
                    dataType = dt,
                    currentValue = m.value,
                    originalValue = m.value,
                    role = m.role,
                    frozen = true,
                    frozenValue = freezeVal,
                    freezeRule = freezeRule,
                    freezeStatus = "arming",
                    attached = true
                )
                added.add(ne)
                toFreeze.add(ne)
            } else if (ex != null) {
                toFreeze.add(ex.copy(frozen = true, frozenValue = freezeVal, freezeRule = freezeRule, freezeStatus = "arming"))
            }
        }
        _state.update { cur ->
            cur.copy(
                watchList = cur.watchList + added,
                mirrorResults = emptyList(),
                statusText = "Arming Mirror Lock on ${toFreeze.size} copies..."
            )
        }
        scope.launch { applyFreezeBatch(toFreeze, freeze = true) }
        ensureWatchLoop()
    }

    fun findRealValueFromUiWatch(watchId: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val latestAttempt = entry.traceEvents.lastOrNull()?.newVal?.takeIf { it.isNotBlank() }
            ?: entry.originalValue.takeIf { it.isNotBlank() }
            ?: entry.currentValue
        val cleanNum = WebsiteMemoryEngine.normalizeNumericInput(latestAttempt, MemDataType.INT32)
        _state.update {
            it.copy(
                scope = MemScope.PAGE_WASM,
                dataType = MemDataType.AUTO,
                scanType = MemScanType.EXACT,
                hideUiNoise = true,
                statusText = "Scanning real gameplay values for '$cleanNum' (UI labels hidden)..."
            )
        }
        if (cleanNum.isNotBlank()) {
            firstScan(cleanNum, "")
        }
    }

    private fun detectUiWarningReason(role: String, addr: String, events: List<MemTraceEvent>): String {
        val uiCallerRe = Regex("(_setString|setText|updateLabel|renderText|fillText|textContent|innerText|Label\\.)", RegexOption.IGNORE_CASE)
        val badEv = events.lastOrNull { uiCallerRe.containsMatchIn(it.caller) }
        if (badEv != null) {
            return "UI renderer detected (${badEv.caller.take(28)}) · tap Find Real"
        }
        if (role == "ui" || addr.contains("._string") || addr.contains("._node._parent")) {
            return "Likely UI display label · tap Find Real for gameplay value"
        }
        return ""
    }

    fun toggleLiveScanRefresh() {
        _state.update {
            val next = !it.liveScanRefresh
            it.copy(
                liveScanRefresh = next,
                statusText = if (next) "Live scan auto-refresh ON" else "Live scan auto-refresh OFF"
            )
        }
        ensureWatchLoop()
    }

    fun toggleHexLiveRefresh() {
        _state.update {
            val next = !it.hexLiveRefresh
            it.copy(
                hexLiveRefresh = next,
                statusText = if (next) "Live hex auto-refresh ON" else "Live hex auto-refresh OFF"
            )
        }
        ensureWatchLoop()
    }

    fun cancelActiveScan() {
        NativeMemoryScanner.cancelScan()
        activeScanJob?.cancel()
        activeScanJob = null
        stopScanTimer()
        if (_state.value.busy) {
            _state.update { it.copy(busy = false, scanElapsedMs = 0L, statusText = "Scan cancelled") }
        }
    }

    private fun startScanTimer() {
        stopScanTimer()
        val startedAt = System.currentTimeMillis()
        scanTimerJob = scope.launch {
            while (isActive) {
                _state.update { it.copy(scanElapsedMs = System.currentTimeMillis() - startedAt) }
                delay(150L)
            }
        }
    }

    private fun stopScanTimer() {
        scanTimerJob?.cancel()
        scanTimerJob = null
    }

    fun firstScan(val1: String, val2: String = "") {
        val st = _state.value
        if (st.busy) return
        val timeoutMs = WebsiteMemoryEngine.clampTimeoutSec(st.scanTimeoutSec) * 1000
        _state.update {
            it.copy(
                busy = true,
                scanElapsedMs = 0L,
                lastScanTimedOut = false,
                fallbackUsed = false,
                selectedScanAddrs = emptySet(),
                statusText = "Scanning ${st.scope.label} (${st.scanType.label} · ${st.scanTimeoutSec}s)..."
            )
        }
        startScanTimer()
        activeScanJob = scope.launch {
            try {
                val n1 = if (st.scanType == MemScanType.GROUP) val1.trim() else WebsiteMemoryEngine.normalizeNumericInput(val1, st.dataType)
                val n2 = WebsiteMemoryEngine.normalizeNumericInput(val2, st.dataType)
                val raw = when (st.scope) {
                    MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                        NativeMemoryScanner.firstScan(st.dataType, st.scanType, n1, n2, 300, timeoutMs)
                    }
                    MemScope.PAGE_WASM -> {
                        val cmd = JSONObject().apply {
                            put("op", "firstScan")
                            put("dtype", st.dataType.jsCode)
                            put("scanType", st.scanType.jsCode)
                            put("val1", n1)
                            put("val2", n2)
                            put("maxReturn", 300)
                            put("timeoutMs", timeoutMs)
                            put("hideUi", st.hideUiNoise)
                        }
                        runPageCommand(cmd)
                    }
                }
                applyScanResponse(raw, isFirst = true)
            } finally {
                stopScanTimer()
                activeScanJob = null
            }
        }
    }

    fun nextScan(val1: String, val2: String = "") {
        val st = _state.value
        if (st.busy) return
        val clean1 = WebsiteMemoryEngine.normalizeNumericInput(val1, st.dataType)
        // BUG FIX #1: Never silently override CHANGED / INCREASED / DECREASED / UNCHANGED to EXACT
        // just because the input box still holds the old First Scan query!
        val effectiveScanType = when (st.scanType) {
            MemScanType.UNKNOWN -> if (clean1.isNotEmpty()) MemScanType.EXACT else MemScanType.CHANGED
            MemScanType.XOR_ENCRYPTED -> MemScanType.EXACT
            MemScanType.GROUP -> MemScanType.CHANGED
            else -> st.scanType
        }
        val timeoutMs = WebsiteMemoryEngine.clampTimeoutSec(st.scanTimeoutSec) * 1000
        _state.update {
            it.copy(
                busy = true,
                scanElapsedMs = 0L,
                scanType = effectiveScanType,
                selectedScanAddrs = emptySet(),
                statusText = "Filtering (${effectiveScanType.label})..."
            )
        }
        startScanTimer()
        activeScanJob = scope.launch {
            try {
                val raw = when (st.scope) {
                    MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                        NativeMemoryScanner.nextScan(
                            effectiveScanType,
                            clean1,
                            WebsiteMemoryEngine.normalizeNumericInput(val2, st.dataType),
                            300,
                            timeoutMs
                        )
                    }
                    MemScope.PAGE_WASM -> {
                        val cmd = JSONObject().apply {
                            put("op", "nextScan")
                            put("scanType", effectiveScanType.jsCode)
                            put("val1", clean1)
                            put("val2", WebsiteMemoryEngine.normalizeNumericInput(val2, st.dataType))
                            put("maxReturn", 300)
                            put("timeoutMs", timeoutMs)
                            put("hideUi", st.hideUiNoise)
                        }
                        runPageCommand(cmd)
                    }
                }
                applyScanResponse(raw, isFirst = false)
            } finally {
                stopScanTimer()
                activeScanJob = null
            }
        }
    }

    // ---------- SCAN BULK SELECTION ----------
    fun toggleSelectScanAddr(addr: String) {
        _state.update { st ->
            val next = if (addr in st.selectedScanAddrs) st.selectedScanAddrs - addr else st.selectedScanAddrs + addr
            st.copy(selectedScanAddrs = next)
        }
    }

    fun selectAllScanResults(addrs: List<String> = _state.value.results.map { it.addr }) {
        _state.update { it.copy(selectedScanAddrs = addrs.toSet()) }
    }

    fun clearScanSelection() {
        _state.update { it.copy(selectedScanAddrs = emptySet()) }
    }

    fun addSelectedScanResultsToWatch(freezeImmediately: Boolean = false) {
        val st = _state.value
        val targets = if (st.selectedScanAddrs.isNotEmpty()) {
            st.results.filter { it.addr in st.selectedScanAddrs }
        } else {
            st.results
        }
        if (targets.isEmpty()) return
        addItemsToWatch(targets, freezeImmediately)
    }

    fun addAllScanResultsToWatch(freezeImmediately: Boolean = false) {
        val targets = _state.value.results
        if (targets.isEmpty()) return
        addItemsToWatch(targets, freezeImmediately)
    }

    private fun addItemsToWatch(targets: List<MemScanResultItem>, freezeImmediately: Boolean) {
        val st = _state.value
        val curScope = st.scope
        val existingIds = st.watchList.map { it.id }.toHashSet()
        val toAdd = ArrayList<MemWatchEntry>()
        val toFreezeEntries = ArrayList<MemWatchEntry>()

        for (item in targets.take(200)) {
            val dt = resolveDataType(item.type, st.dataType)
            val watchId = "${curScope.name}|${item.addr}"
            val existing = st.watchList.firstOrNull { it.id == watchId }
            if (existing == null && watchId !in existingIds) {
                existingIds.add(watchId)
                // BUG FIX #4: Set freezeStatus="arming" so watchLoop never double-arms mid-flight
                val entry = MemWatchEntry(
                    id = watchId,
                    scope = curScope,
                    addr = item.addr,
                    label = item.addr.removePrefix("js:").takeLast(32),
                    dataType = dt,
                    currentValue = item.value,
                    originalValue = item.value,
                    role = item.role,
                    valueHistory = listOf(item.value),
                    uiWarningReason = detectUiWarningReason(item.role, item.addr, emptyList()),
                    frozen = freezeImmediately,
                    frozenValue = if (freezeImmediately) item.value else "",
                    freezeStatus = if (freezeImmediately) "arming" else "",
                    xorMode = item.xorMode,
                    xorKey = item.xorKey,
                    xorKeyAddr = item.xorKeyAddr,
                    attached = true
                )
                toAdd.add(entry)
                if (freezeImmediately) toFreezeEntries.add(entry)
            } else if (existing != null && freezeImmediately && !existing.frozen) {
                toFreezeEntries.add(existing.copy(frozen = true, frozenValue = existing.currentValue, freezeStatus = "arming"))
            }
        }

        _state.update { cur ->
            val updatedExisting = if (freezeImmediately) {
                val fIds = toFreezeEntries.map { it.id }.toSet()
                cur.watchList.map { e ->
                    if (e.id in fIds && !e.frozen) e.copy(frozen = true, frozenValue = e.currentValue, freezeStatus = "arming") else e
                }
            } else cur.watchList
            cur.copy(
                watchList = updatedExisting + toAdd,
                selectedScanAddrs = emptySet(),
                statusText = when {
                    freezeImmediately -> "Arming freeze on ${toFreezeEntries.size} entries..."
                    toAdd.isNotEmpty() -> "Placed ${toAdd.size} match${if (toAdd.size == 1) "" else "es"} into Watch Table"
                    else -> "Selected items already in Watch Table"
                }
            )
        }
        if (toFreezeEntries.isNotEmpty()) {
            scope.launch { applyFreezeBatch(toFreezeEntries, freeze = true) }
        }
        ensureWatchLoop()
    }

    fun editSelectedScanResults(newValue: String) {
        val st = _state.value
        val clean = newValue.trim()
        if (clean.isEmpty() || st.busy) return
        val targets = if (st.selectedScanAddrs.isNotEmpty()) {
            st.results.filter { it.addr in st.selectedScanAddrs }
        } else {
            st.results
        }
        if (targets.isEmpty()) return
        scope.launch {
            val wroteMap = HashMap<String, String>()
            when (st.scope) {
                MemScope.FABLE_NATIVE -> {
                    withContext(Dispatchers.IO) {
                        for (item in targets) {
                            val dt = resolveDataType(item.type, st.dataType)
                            val targetVal = WebsiteMemoryEngine.evaluateRelativeMath(item.value, clean, dt)
                            if (NativeMemoryScanner.writeValue(item.addr, dt, targetVal, item.xorMode, item.xorKey, item.xorKeyAddr)) {
                                wroteMap[item.addr] = targetVal
                            }
                        }
                    }
                }
                MemScope.PAGE_WASM -> {
                    val arr = JSONArray()
                    for (item in targets) {
                        val dt = resolveDataType(item.type, st.dataType)
                        val targetVal = WebsiteMemoryEngine.evaluateRelativeMath(item.value, clean, dt)
                        arr.put(JSONObject().apply {
                            put("id", item.addr)
                            put("val", targetVal)
                        })
                    }
                    val raw = runPageCommand(JSONObject().apply {
                        put("op", "writeBatch")
                        put("entries", arr)
                    })
                    val valsObj = runCatching { JSONObject(raw).optJSONObject("values") }.getOrNull()
                    if (valsObj != null) {
                        valsObj.keys().forEach { k -> wroteMap[k] = valsObj.optString(k) }
                    }
                }
            }
            _state.update { cur ->
                cur.copy(
                    results = cur.results.map { r ->
                        val nv = wroteMap[r.addr]
                        if (nv != null) r.copy(prevValue = r.value, value = nv, lastChangedAtMs = System.currentTimeMillis()) else r
                    },
                    statusText = "Wrote ${wroteMap.size} / ${targets.size} selected match${if (targets.size == 1) "" else "es"} ($clean)"
                )
            }
        }
    }

    fun writeAllScanResults(newValue: String) {
        val st = _state.value
        val clean = newValue.trim()
        if (st.busy || !st.hasActiveScan || clean.isEmpty()) return
        // If relative math expression, route through editSelectedScanResults over all visible results
        if (clean.startsWith("+") || clean.startsWith("--") || clean.startsWith("*") || clean.startsWith("x", true) || clean.startsWith("/")) {
            editSelectedScanResults(clean)
            return
        }
        val norm = WebsiteMemoryEngine.normalizeNumericInput(clean, st.dataType)
        _state.update { it.copy(busy = true, statusText = "Writing $norm to matches...") }
        scope.launch {
            val raw = when (st.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    var wrote = 0
                    for (item in st.results.take(150)) {
                        val dt = resolveDataType(item.type, st.dataType)
                        if (NativeMemoryScanner.writeValue(item.addr, dt, norm, item.xorMode, item.xorKey, item.xorKeyAddr)) wrote++
                    }
                    val refreshed = NativeMemoryScanner.refreshHits(300)
                    runCatching { JSONObject(refreshed).put("wrote", wrote).toString() }.getOrDefault(refreshed)
                }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply {
                    put("op", "writeAll")
                    put("val", norm)
                    put("maxWrite", 150)
                    put("maxReturn", 300)
                })
            }
            applyScanResponse(raw, isFirst = false)
        }
    }

    fun reloadAndHookActiveTab() {
        resetScan()
        tabManager.reload()
        _state.update { it.copy(statusText = "Reloading page with CE hooks active from startup...") }
    }

    fun refreshScanResults() {
        val st = _state.value
        if (st.busy || !st.hasActiveScan) return
        _state.update { it.copy(busy = true, statusText = "Refreshing values...") }
        scope.launch {
            val raw = when (st.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.refreshHits(300) }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply {
                    put("op", "refreshHits")
                    put("maxReturn", 300)
                    put("hideUi", st.hideUiNoise)
                })
            }
            applyScanResponse(raw, isFirst = false)
        }
    }

    fun writeScanItem(item: MemScanResultItem, newValue: String) {
        val curScope = _state.value.scope
        val dt = resolveDataType(item.type, _state.value.dataType)
        val evaluated = WebsiteMemoryEngine.evaluateRelativeMath(item.value, newValue, dt)
        scope.launch {
            val ok = when (curScope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    NativeMemoryScanner.writeValue(item.addr, dt, evaluated, item.xorMode, item.xorKey, item.xorKeyAddr)
                }
                MemScope.PAGE_WASM -> {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(item.addr)
                    val raw = runPageCommand(JSONObject().apply {
                        put("op", "write")
                        put("id", item.addr)
                        if (tFrame != null) put("targetFrame", tFrame)
                        put("val", evaluated)
                    })
                    runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
                }
            }
            _state.update { st ->
                st.copy(
                    results = st.results.map { r ->
                        if (r.addr == item.addr && ok) r.copy(prevValue = r.value, value = evaluated, lastChangedAtMs = System.currentTimeMillis()) else r
                    },
                    statusText = if (ok) "Wrote $evaluated to ${item.addr.takeLast(24)}" else "Write blocked or address detached"
                )
            }
        }
    }

    fun undoScan() {
        val st = _state.value
        if (st.busy || !st.canUndo) return
        _state.update { it.copy(busy = true, statusText = "Undoing last scan...") }
        scope.launch {
            val raw = when (st.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.undoScan(300) }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply {
                    put("op", "undo")
                    put("maxReturn", 300)
                })
            }
            applyScanResponse(raw, isFirst = false)
        }
    }

    fun resetScan() {
        cancelActiveScan()
        scope.launch {
            withContext(Dispatchers.IO) { NativeMemoryScanner.resetScan() }
            if (engineActive) {
                runCatching { runPageCommand(JSONObject().apply { put("op", "reset") }) }
            }
            _state.update {
                it.copy(
                    hasActiveScan = false,
                    canUndo = false,
                    undoDepth = 0,
                    snapshotActive = false,
                    fallbackUsed = false,
                    totalMatches = 0,
                    results = emptyList(),
                    selectedScanAddrs = emptySet(),
                    scanType = if (it.scanType.nextOnly) MemScanType.EXACT else it.scanType,
                    statusText = "Ready for First Scan"
                )
            }
        }
    }

    fun addToWatch(item: MemScanResultItem, customLabel: String = "") {
        val curScope = _state.value.scope
        val dt = resolveDataType(item.type, _state.value.dataType)
        val watchId = "${curScope.name}|${item.addr}"
        if (_state.value.watchList.any { it.id == watchId }) {
            _state.update { it.copy(statusText = "Already in Watch Table") }
            return
        }
        val label = customLabel.ifBlank { item.addr.removePrefix("js:").takeLast(32) }
        val entry = MemWatchEntry(
            id = watchId,
            scope = curScope,
            addr = item.addr,
            label = label,
            dataType = dt,
            currentValue = item.value,
            originalValue = item.value,
            role = item.role,
            valueHistory = listOf(item.value),
            uiWarningReason = detectUiWarningReason(item.role, item.addr, emptyList()),
            xorMode = item.xorMode,
            xorKey = item.xorKey,
            xorKeyAddr = item.xorKeyAddr,
            attached = true
        )
        _state.update {
            it.copy(
                watchList = it.watchList + entry,
                statusText = "Added ${entry.label} to Watch"
            )
        }
        ensureWatchLoop()
    }

    // ---------- WATCH TABLE BULK SELECTION & ACTIONS ----------
    fun toggleSelectWatchId(watchId: String) {
        _state.update { st ->
            val next = if (watchId in st.selectedWatchIds) st.selectedWatchIds - watchId else st.selectedWatchIds + watchId
            st.copy(selectedWatchIds = next)
        }
    }

    fun selectAllWatchEntries() {
        _state.update { st -> st.copy(selectedWatchIds = st.watchList.map { it.id }.toSet()) }
    }

    fun clearWatchSelection() {
        _state.update { it.copy(selectedWatchIds = emptySet()) }
    }

    fun editSelectedWatchEntries(newValue: String) {
        val clean = newValue.trim()
        if (clean.isEmpty()) return
        val st = _state.value
        val targets = if (st.selectedWatchIds.isNotEmpty()) {
            st.watchList.filter { it.id in st.selectedWatchIds }
        } else {
            st.watchList
        }
        if (targets.isEmpty()) return
        scope.launch {
            val okValues = HashMap<String, String>()
            val pageTargets = targets.filter { it.scope == MemScope.PAGE_WASM }
            val nativeTargets = targets.filter { it.scope == MemScope.FABLE_NATIVE }
            if (pageTargets.isNotEmpty()) {
                val arr = JSONArray()
                val planned = HashMap<String, String>()
                for (w in pageTargets) {
                    val evalVal = WebsiteMemoryEngine.evaluateRelativeMath(w.currentValue, clean, w.dataType)
                    planned[w.id] = evalVal
                    arr.put(JSONObject().apply {
                        put("id", w.addr)
                        put("val", evalVal)
                    })
                }
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "writeBatch")
                    put("entries", arr)
                })
                val valsObj = runCatching { JSONObject(raw).optJSONObject("values") }.getOrNull()
                for (w in pageTargets) {
                    if (valsObj?.has(w.addr) == true) {
                        okValues[w.id] = planned[w.id] ?: clean
                    }
                }
            }
            if (nativeTargets.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    for (w in nativeTargets) {
                        val evalVal = WebsiteMemoryEngine.evaluateRelativeMath(w.currentValue, clean, w.dataType)
                        if (NativeMemoryScanner.writeValue(w.addr, w.dataType, evalVal, w.xorMode, w.xorKey, w.xorKeyAddr)) {
                            okValues[w.id] = evalVal
                            if (w.frozen) {
                                NativeMemoryScanner.setFreeze(w.addr, w.dataType, evalVal, w.freezeRule.nativeCode, w.xorMode, w.xorKey, w.xorKeyAddr)
                            }
                        }
                    }
                }
            }
            val frozenPageTargets = pageTargets.filter { it.frozen && it.id in okValues }.map {
                it.copy(frozenValue = okValues[it.id] ?: clean)
            }
            if (frozenPageTargets.isNotEmpty()) {
                applyFreezeBatch(frozenPageTargets, freeze = true)
            }
            _state.update { cur ->
                cur.copy(
                    watchList = cur.watchList.map { e ->
                        val nv = okValues[e.id]
                        if (nv != null) e.copy(
                            currentValue = nv,
                            frozenValue = if (e.frozen) nv else e.frozenValue,
                            lastChangedAtMs = System.currentTimeMillis()
                        ) else e
                    },
                    statusText = "Edited ${okValues.size} / ${targets.size} watched ($clean)"
                )
            }
        }
    }

    fun freezeSelectedWatchEntries(freeze: Boolean) {
        val st = _state.value
        val targets = if (st.selectedWatchIds.isNotEmpty()) {
            st.watchList.filter { it.id in st.selectedWatchIds }
        } else {
            st.watchList
        }
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }.toSet()
        val updatedTargets = targets.map { e ->
            e.copy(
                frozen = freeze,
                frozenValue = if (freeze) defaultFreezeParam(e) else e.frozenValue,
                freezeStatus = if (freeze) "arming" else "",
                freezeMode = if (freeze) e.freezeMode else "",
                blockedWrites = if (freeze) e.blockedWrites else 0L
            )
        }
        _state.update { cur ->
            cur.copy(
                watchList = cur.watchList.map { e ->
                    if (e.id in targetIds) e.copy(
                        frozen = freeze,
                        frozenValue = if (freeze) defaultFreezeParam(e) else e.frozenValue,
                        freezeStatus = if (freeze) "arming" else "",
                        freezeMode = if (freeze) e.freezeMode else "",
                        blockedWrites = if (freeze) e.blockedWrites else 0L
                    ) else e
                },
                statusText = if (freeze) "Arming freeze on ${targets.size} entries..." else "Unfroze ${targets.size} entries"
            )
        }
        scope.launch {
            applyFreezeBatch(updatedTargets, freeze = freeze)
        }
        ensureWatchLoop()
    }

    private fun defaultFreezeParam(e: MemWatchEntry): String {
        if (e.frozenValue.isNotBlank()) return e.frozenValue
        return when (e.freezeRule) {
            MemFreezeRule.STEP -> "10"
            MemFreezeRule.RANGE -> {
                val cur = WebsiteMemoryEngine.normalizeNumericInput(e.currentValue, e.dataType).toDoubleOrNull()
                if (cur != null) "${formatMathNum(cur, e.dataType)}..${formatMathNum(cur + 100.0, e.dataType)}"
                else "0..100"
            }
            else -> e.currentValue
        }
    }

    fun restoreSelectedWatchEntries() {
        val st = _state.value
        val targets = if (st.selectedWatchIds.isNotEmpty()) {
            st.watchList.filter { it.id in st.selectedWatchIds }
        } else {
            st.watchList
        }
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }.toSet()
        _state.update { cur ->
            cur.copy(
                watchList = cur.watchList.map { e ->
                    if (e.id in targetIds) e.copy(frozen = false, freezeMode = "", freezeStatus = "", blockedWrites = 0L) else e
                }
            )
        }
        scope.launch {
            applyFreezeBatch(targets, freeze = false)
            for (w in targets) {
                writeEntry(w, w.originalValue)
            }
            _state.update { cur ->
                cur.copy(
                    watchList = cur.watchList.map { e ->
                        if (e.id in targetIds) e.copy(currentValue = e.originalValue) else e
                    },
                    statusText = "Restored ${targets.size} original values"
                )
            }
        }
    }

    fun removeSelectedWatchEntries() {
        val st = _state.value
        val targets = if (st.selectedWatchIds.isNotEmpty()) {
            st.watchList.filter { it.id in st.selectedWatchIds }
        } else {
            st.watchList
        }
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }.toSet()
        scope.launch {
            applyFreezeBatch(targets.filter { it.frozen }, freeze = false)
            for (t in targets.filter { it.tracing }) {
                applyTraceSingle(t, false)
            }
        }
        _state.update { cur ->
            cur.copy(
                watchList = cur.watchList.filterNot { it.id in targetIds },
                selectedWatchIds = cur.selectedWatchIds - targetIds,
                statusText = "Removed ${targets.size} entries from Watch Table"
            )
        }
    }

    fun removeWatch(watchId: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId }
        if (entry != null) {
            scope.launch {
                if (entry.frozen) applyFreezeSingle(entry, freeze = false)
                if (entry.tracing) applyTraceSingle(entry, enabled = false)
            }
        }
        _state.update {
            it.copy(
                watchList = it.watchList.filterNot { e -> e.id == watchId },
                selectedWatchIds = it.selectedWatchIds - watchId
            )
        }
    }

    fun renameWatch(watchId: String, newLabel: String) {
        val clean = newLabel.trim().take(36)
        if (clean.isEmpty()) return
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { e -> if (e.id == watchId) e.copy(label = clean) else e },
                statusText = "Renamed watch entry to $clean"
            )
        }
    }

    fun applyWatchQuickMath(watchId: String, mathOp: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val nextStr = when (mathOp) {
            "+100", "+1000", "x2", "x10", "/2", "--100" -> WebsiteMemoryEngine.evaluateRelativeMath(entry.currentValue, mathOp, entry.dataType)
            "999999", "0" -> mathOp
            else -> return
        }
        setWatchValue(watchId, nextStr)
    }

    private fun formatMathNum(v: Double, dt: MemDataType): String {
        return if (dt == MemDataType.FLOAT || dt == MemDataType.DOUBLE) {
            if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.3f", v)
        } else {
            v.toLong().toString()
        }
    }

    fun cycleWatchFreezeRule(watchId: String) {
        val existing = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val nextRule = existing.freezeRule.next()
        val nextParam = when (nextRule) {
            MemFreezeRule.RANGE -> {
                if (existing.frozenValue.contains("..")) existing.frozenValue
                else {
                    val cur = WebsiteMemoryEngine.normalizeNumericInput(existing.currentValue, existing.dataType).toDoubleOrNull()
                    if (cur != null) "${formatMathNum(cur, existing.dataType)}..${formatMathNum(cur + 100.0, existing.dataType)}"
                    else "0..100"
                }
            }
            MemFreezeRule.STEP -> "10"
            else -> if (existing.frozenValue.contains("..")) existing.currentValue else existing.frozenValue.ifBlank { existing.currentValue }
        }
        val updated = existing.copy(freezeRule = nextRule, frozenValue = nextParam)
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { e -> if (e.id == watchId) updated else e },
                statusText = "${existing.label} freeze mode: ${nextRule.label} ($nextParam)"
            )
        }
        if (updated.frozen) {
            scope.launch { applyFreezeSingle(updated, freeze = true) }
        }
    }

    fun setWatchFreezeParam(watchId: String, paramStr: String) {
        val existing = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val clean = paramStr.trim()
        if (clean.isEmpty()) return
        val updated = existing.copy(frozenValue = clean)
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { e -> if (e.id == watchId) updated else e },
                statusText = "${existing.label} ${existing.freezeRule.symbol} target set to $clean"
            )
        }
        if (updated.frozen) {
            scope.launch { applyFreezeSingle(updated, freeze = true) }
        }
    }

    fun setWatchValue(watchId: String, newValue: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val evaluated = WebsiteMemoryEngine.evaluateRelativeMath(entry.currentValue, newValue, entry.dataType)
        scope.launch {
            val ok = writeEntry(entry, evaluated)
            if (ok && entry.frozen && entry.freezeRule != MemFreezeRule.RANGE && entry.freezeRule != MemFreezeRule.STEP) {
                applyFreezeSingle(entry.copy(frozenValue = evaluated), freeze = true)
            }
            _state.update { st ->
                st.copy(
                    watchList = st.watchList.map { e ->
                        if (e.id == watchId) e.copy(
                            currentValue = if (ok) evaluated else e.currentValue,
                            frozenValue = if (e.frozen && ok && e.freezeRule != MemFreezeRule.RANGE && e.freezeRule != MemFreezeRule.STEP) evaluated else e.frozenValue,
                            lastChangedAtMs = if (ok) System.currentTimeMillis() else e.lastChangedAtMs
                        ) else e
                    },
                    statusText = if (ok) "Set ${entry.label} = $evaluated" else "Write blocked or address invalid"
                )
            }
        }
    }

    fun restoreWatchOriginal(watchId: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        _state.update { st ->
            st.copy(watchList = st.watchList.map {
                if (it.id == watchId) it.copy(frozen = false, freezeMode = "", freezeStatus = "", blockedWrites = 0L) else it
            })
        }
        scope.launch {
            if (entry.frozen) applyFreezeSingle(entry, freeze = false)
            setWatchValue(watchId, entry.originalValue)
        }
    }

    fun toggleWatchFreeze(watchId: String) {
        val existing = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val nextFreeze = !existing.frozen
        val lockVal = if (nextFreeze) defaultFreezeParam(existing) else existing.frozenValue
        val updated = existing.copy(
            frozen = nextFreeze,
            frozenValue = lockVal,
            freezeStatus = if (nextFreeze) "arming" else "",
            freezeMode = if (nextFreeze) existing.freezeMode else "",
            blockedWrites = if (nextFreeze) existing.blockedWrites else 0L
        )
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { e -> if (e.id == watchId) updated else e },
                statusText = if (nextFreeze) "Arming ${existing.freezeRule.symbol} freeze @ $lockVal..." else "Unlocked ${existing.label}"
            )
        }
        scope.launch { applyFreezeSingle(updated, freeze = nextFreeze) }
        ensureWatchLoop()
    }

    // ---------- WRITE-WATCHER / BREAKPOINT TRACE ----------
    fun toggleWatchTrace(watchId: String) {
        val existing = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val nextTrace = !existing.tracing
        scope.launch {
            val ok = applyTraceSingle(existing, nextTrace)
            _state.update { st ->
                st.copy(
                    watchList = st.watchList.map { e ->
                        if (e.id == watchId) e.copy(
                            tracing = if (ok) nextTrace else false,
                            traceEvents = if (!nextTrace) emptyList() else e.traceEvents,
                            traceCount = if (!nextTrace) 0 else e.traceCount
                        ) else e
                    },
                    statusText = when {
                        nextTrace && ok -> "Write-Watcher ON for ${existing.label} (logging writes)"
                        nextTrace -> "Write-Watcher unavailable for ${existing.label}"
                        else -> "Write-Watcher OFF for ${existing.label}"
                    }
                )
            }
            if (nextTrace && ok) ensureWatchLoop()
        }
    }

    fun clearWatchTrace(watchId: String) {
        val existing = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        scope.launch {
            if (existing.scope == MemScope.PAGE_WASM) {
                val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(existing.addr)
                runPageCommand(JSONObject().apply {
                    put("op", "clearTrace")
                    put("id", existing.addr)
                    if (tFrame != null) put("targetFrame", tFrame)
                })
            }
            _state.update { st ->
                st.copy(watchList = st.watchList.map { e ->
                    if (e.id == watchId) e.copy(traceEvents = emptyList(), traceCount = 0) else e
                })
            }
        }
    }

    private fun clearAllTraces() {
        scope.launch {
            if (engineActive) {
                runCatching { runPageCommand(JSONObject().apply { put("op", "clearTrace") }) }
            }
        }
        _state.update { st ->
            st.copy(watchList = st.watchList.map { it.copy(tracing = false, traceEvents = emptyList(), traceCount = 0) })
        }
    }

    private suspend fun applyTraceSingle(entry: MemWatchEntry, enabled: Boolean): Boolean {
        return when (entry.scope) {
            MemScope.FABLE_NATIVE -> true // Native change-history tracked in watch loop
            MemScope.PAGE_WASM -> {
                val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(entry.addr)
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "setTrace")
                    put("id", entry.addr)
                    put("enabled", enabled)
                    if (tFrame != null) put("targetFrame", tFrame)
                })
                runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
            }
        }
    }

    fun clearWatchList() {
        unfreezeAll()
        clearAllTraces()
        _state.update { it.copy(watchList = emptyList(), selectedWatchIds = emptySet()) }
    }

    fun unfreezeAll() {
        scope.launch {
            withContext(Dispatchers.IO) { NativeMemoryScanner.unfreezeAll() }
            if (engineActive) {
                runCatching { runPageCommand(JSONObject().apply { put("op", "unfreezeAll") }) }
            }
        }
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { it.copy(frozen = false, freezeMode = "", freezeStatus = "", blockedWrites = 0L) },
                statusText = "All frozen values unlocked"
            )
        }
    }

    // ---------- PERSISTENT CHEAT TABLES ----------
    fun saveCurrentCheatTable(customName: String = "") {
        val st = _state.value
        if (st.watchList.isEmpty()) {
            _state.update { it.copy(statusText = "Watch Table is empty — add entries before saving") }
            return
        }
        val origin = runCatching {
            val u = tabManager.activeTab?.url?.value.orEmpty()
            java.net.URI(u).host.orEmpty()
        }.getOrDefault("")
        val tableName = customName.trim().ifBlank { origin.ifBlank { "Table #${st.savedTables.size + 1}" } }.take(40)
        val tbl = MemSavedTable(
            id = "tbl_${System.currentTimeMillis()}",
            name = tableName,
            origin = origin,
            savedAtMs = System.currentTimeMillis(),
            entries = st.watchList.map { it.copy(frozen = false, tracing = false, traceEvents = emptyList()) }
        )
        val updated = (listOf(tbl) + st.savedTables.filterNot { it.name == tableName }).take(20)
        _state.update { it.copy(savedTables = updated, statusText = "Saved Cheat Table '$tableName' (${tbl.entries.size} entries)") }
        scope.launch {
            settingsStore.setMemoryEngineSavedTablesJson(MemoryCheatTableCodec.encodeTables(updated))
        }
    }

    fun loadCheatTable(tableId: String) {
        val tbl = _state.value.savedTables.firstOrNull { it.id == tableId } ?: return
        loadCheatTableEntries(tbl)
    }

    fun deleteCheatTable(tableId: String) {
        val updated = _state.value.savedTables.filterNot { it.id == tableId }
        _state.update { it.copy(savedTables = updated, statusText = "Deleted saved Cheat Table") }
        scope.launch {
            settingsStore.setMemoryEngineSavedTablesJson(MemoryCheatTableCodec.encodeTables(updated))
        }
    }

    fun exportWatchTableJson(): String {
        val origin = runCatching { java.net.URI(tabManager.activeTab?.url?.value.orEmpty()).host.orEmpty() }.getOrDefault("")
        val tbl = MemSavedTable(
            id = "export_${System.currentTimeMillis()}",
            name = origin.ifBlank { "Fable Cheat Table" },
            origin = origin,
            savedAtMs = System.currentTimeMillis(),
            entries = _state.value.watchList
        )
        return MemoryCheatTableCodec.encodeSingleTable(tbl)
    }

    fun importCheatTableJson(rawJson: String): Boolean {
        val tbl = MemoryCheatTableCodec.decodeSingleTable(rawJson)
        if (tbl == null) {
            _state.update { it.copy(statusText = "Invalid Cheat Table JSON") }
            return false
        }
        loadCheatTableEntries(tbl)
        return true
    }

    private fun loadCheatTableEntries(tbl: MemSavedTable) {
        scope.launch {
            val resolved = ArrayList<MemWatchEntry>(tbl.entries.size)
            var attachedCnt = 0
            for (e in tbl.entries) {
                var ok = false
                var liveVal = e.currentValue
                if (e.scope == MemScope.PAGE_WASM) {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(e.addr)
                    val raw = runPageCommand(JSONObject().apply {
                        put("op", "reattach")
                        put("id", e.addr)
                        put("dt", e.dataType.jsCode)
                        if (tFrame != null) put("targetFrame", tFrame)
                    })
                    val obj = runCatching { JSONObject(raw) }.getOrNull()
                    if (obj != null && obj.optBoolean("ok", false)) {
                        ok = true
                        liveVal = obj.optString("val", e.currentValue)
                    }
                } else {
                    val rd = withContext(Dispatchers.IO) {
                        NativeMemoryScanner.readValue(e.addr, e.dataType, 16, e.xorMode, e.xorKey, e.xorKeyAddr)
                    }
                    if (!rd.startsWith("<")) {
                        ok = true
                        liveVal = rd
                    }
                }
                if (ok) attachedCnt++
                resolved.add(
                    e.copy(
                        currentValue = liveVal,
                        frozen = false,
                        freezeStatus = if (ok) "" else "detached",
                        attached = ok
                    )
                )
            }
            _state.update { st ->
                val existingIds = st.watchList.map { it.id }.toHashSet()
                val merged = st.watchList + resolved.filterNot { it.id in existingIds }
                st.copy(
                    watchList = merged,
                    statusText = "Loaded '${tbl.name}': $attachedCnt/${resolved.size} attached cleanly"
                )
            }
            ensureWatchLoop()
        }
    }

    // ---------- POINTER SCANNER ----------
    fun pointerScan(addr: String, scopeOverride: MemScope = _state.value.scope, maxOffset: Int = 4096) {
        _state.update { it.copy(busy = true, statusText = "Pointer scanning for ${addr.takeLast(20)} (±${maxOffset}B)...") }
        scope.launch {
            val raw = when (scopeOverride) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    NativeMemoryScanner.pointerScan(addr, maxOffset, 120, 12_000)
                }
                MemScope.PAGE_WASM -> {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(addr)
                    runPageCommand(JSONObject().apply {
                        put("op", "pointerScan")
                        put("id", addr)
                        if (tFrame != null) put("targetFrame", tFrame)
                        put("maxOffset", maxOffset)
                    })
                }
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                val arr = obj.optJSONArray("items") ?: JSONArray()
                val list = ArrayList<MemPointerItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    list.add(
                        MemPointerItem(
                            ptrAddr = p.optString("ptrAddr"),
                            pointsTo = p.optString("pointsTo"),
                            offset = p.optString("offset"),
                            region = p.optString("region")
                        )
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        pointerOriginAddr = addr,
                        pointerScope = scopeOverride,
                        pointerResults = list,
                        statusText = "Found ${list.size} pointer${if (list.size == 1) "" else "s"} to ${addr.takeLast(20)}"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        pointerOriginAddr = addr,
                        pointerResults = emptyList(),
                        statusText = obj?.optString("error") ?: "Pointer scan failed"
                    )
                }
            }
        }
    }

    // ---------- FREEZE BACKEND ----------
    private suspend fun applyFreezeSingle(entry: MemWatchEntry, freeze: Boolean): Boolean {
        when (entry.scope) {
            MemScope.FABLE_NATIVE -> {
                if (!freeze) {
                    withContext(Dispatchers.IO) { NativeMemoryScanner.unfreeze(entry.addr) }
                    return true
                }
                val norm = WebsiteMemoryEngine.normalizeNumericInput(entry.frozenValue.ifBlank { entry.currentValue }, entry.dataType)
                val ok = withContext(Dispatchers.IO) {
                    NativeMemoryScanner.setFreeze(entry.addr, entry.dataType, norm, entry.freezeRule.nativeCode, entry.xorMode, entry.xorKey, entry.xorKeyAddr)
                }
                val modeStr = if (entry.xorMode != 0) "500Hz-XOR" else "500Hz"
                _state.update { st ->
                    st.copy(
                        watchList = st.watchList.map { e ->
                            if (e.id == entry.id) {
                                if (ok) e.copy(frozen = true, freezeMode = modeStr, freezeStatus = "locked")
                                else e.copy(frozen = false, freezeMode = "", freezeStatus = "failed")
                            } else e
                        },
                        statusText = if (ok) "Confirmed $modeStr ${entry.freezeRule.symbol} lock on ${entry.label}" else "Freeze FAILED on ${entry.label}: native address invalid or worker unavailable"
                    )
                }
                return ok
            }
            MemScope.PAGE_WASM -> {
                val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(entry.addr)
                if (!freeze) {
                    runPageCommand(JSONObject().apply {
                        put("op", "unfreeze")
                        put("id", entry.addr)
                        if (tFrame != null) put("targetFrame", tFrame)
                    })
                    return true
                }
                val cmd = JSONObject().apply {
                    put("op", "setFreeze")
                    put("id", entry.addr)
                    put("rule", entry.freezeRule.jsCode)
                    put("strength", _state.value.freezeStrength.level)
                    if (tFrame != null) put("targetFrame", tFrame)
                    put("val", WebsiteMemoryEngine.normalizeNumericInput(entry.frozenValue.ifBlank { entry.currentValue }, entry.dataType))
                }
                var raw = runPageCommand(cmd)
                var obj = runCatching { JSONObject(raw) }.getOrNull()
                if (obj == null || !obj.optBoolean("ok", false)) {
                    delay(80)
                    raw = runPageCommand(cmd)
                    obj = runCatching { JSONObject(raw) }.getOrNull()
                }
                val ok = obj != null && obj.optBoolean("ok", false)
                val mode = obj?.optString("mode").orEmpty()
                val stStr = if (ok) obj?.optString("status", "locked").orEmpty().ifBlank { "locked" } else "failed"
                val errMsg = obj?.optString("error").orEmpty().ifBlank { "entry not in active frame" }
                _state.update { st ->
                    st.copy(
                        watchList = st.watchList.map { e ->
                            if (e.id == entry.id) {
                                if (ok) e.copy(frozen = true, freezeMode = mode, freezeStatus = stStr)
                                else e.copy(frozen = false, freezeMode = "", freezeStatus = "failed", blockedWrites = 0L)
                            } else e
                        },
                        statusText = if (ok) "Confirmed $mode (${entry.freezeRule.symbol}) lock on ${entry.label}" else "Freeze FAILED on ${entry.label}: $errMsg"
                    )
                }
                return ok
            }
        }
    }

    private suspend fun applyFreezeBatch(entries: List<MemWatchEntry>, freeze: Boolean): Int {
        val pageEntries = entries.filter { it.scope == MemScope.PAGE_WASM }
        val nativeEntries = entries.filter { it.scope == MemScope.FABLE_NATIVE }
        val confirmedIds = HashSet<String>()
        val failedIds = HashSet<String>()

        if (nativeEntries.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (e in nativeEntries) {
                    if (freeze) {
                        val norm = WebsiteMemoryEngine.normalizeNumericInput(e.frozenValue.ifBlank { e.currentValue }, e.dataType)
                        if (NativeMemoryScanner.setFreeze(e.addr, e.dataType, norm, e.freezeRule.nativeCode, e.xorMode, e.xorKey, e.xorKeyAddr)) {
                            confirmedIds.add(e.id)
                        } else {
                            failedIds.add(e.id)
                        }
                    } else {
                        NativeMemoryScanner.unfreeze(e.addr)
                    }
                }
            }
            if (freeze) {
                _state.update { st ->
                    st.copy(watchList = st.watchList.map { e ->
                        when {
                            e.id in confirmedIds -> e.copy(frozen = true, freezeMode = if (e.xorMode != 0) "500Hz-XOR" else "500Hz", freezeStatus = "locked")
                            e.id in failedIds -> e.copy(frozen = false, freezeMode = "", freezeStatus = "failed", blockedWrites = 0L)
                            else -> e
                        }
                    })
                }
            }
        }
        if (pageEntries.isNotEmpty()) {
            if (freeze) {
                val arr = JSONArray()
                for (e in pageEntries) {
                    arr.put(JSONObject().apply {
                        put("id", e.addr)
                        put("rule", e.freezeRule.jsCode)
                        put("val", WebsiteMemoryEngine.normalizeNumericInput(e.frozenValue.ifBlank { e.currentValue }, e.dataType))
                    })
                }
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "freezeBatch")
                    put("strength", _state.value.freezeStrength.level)
                    put("entries", arr)
                })
                val respObj = runCatching { JSONObject(raw) }.getOrNull()
                val lockedAddrSet = HashSet<String>()
                val lArr = respObj?.optJSONArray("lockedIds")
                if (lArr != null) {
                    for (i in 0 until lArr.length()) lockedAddrSet.add(lArr.optString(i))
                }
                val statsObj = respObj?.optJSONObject("freezeStats")
                for (e in pageEntries) {
                    val isLocked = e.addr in lockedAddrSet || (statsObj != null && statsObj.has(e.addr))
                    if (isLocked) confirmedIds.add(e.id) else failedIds.add(e.id)
                }
                _state.update { st ->
                    st.copy(
                        watchList = st.watchList.map { e ->
                            if (e.scope != MemScope.PAGE_WASM) return@map e
                            when {
                                e.id in confirmedIds -> {
                                    val info = statsObj?.optJSONObject(e.addr)
                                    e.copy(
                                        frozen = true,
                                        freezeMode = info?.optString("mode", e.freezeMode).orEmpty().ifBlank { e.freezeMode },
                                        blockedWrites = info?.optLong("blocked", e.blockedWrites) ?: e.blockedWrites,
                                        freezeStatus = info?.optString("status", "locked").orEmpty().ifBlank { "locked" }
                                    )
                                }
                                e.id in failedIds -> e.copy(frozen = false, freezeMode = "", freezeStatus = "failed", blockedWrites = 0L)
                                else -> e
                            }
                        },
                        statusText = when {
                            confirmedIds.isNotEmpty() && failedIds.isEmpty() -> "Confirmed freeze on ${confirmedIds.size} entries"
                            confirmedIds.isNotEmpty() -> "Frozen ${confirmedIds.size} entries (${failedIds.size} failed/detached)"
                            else -> "Freeze FAILED (0/${entries.size} locked) · tap Hook Reload"
                        }
                    )
                }
            } else {
                val arr = JSONArray()
                pageEntries.forEach { arr.put(it.addr) }
                runPageCommand(JSONObject().apply {
                    put("op", "unfreezeBatch")
                    put("ids", arr)
                })
            }
        }
        return confirmedIds.size
    }

    private fun applyFreezeStatsJson(targetScope: MemScope, statsObj: JSONObject) {
        _state.update { st ->
            st.copy(watchList = st.watchList.map { e ->
                if (e.scope != targetScope || !e.frozen) return@map e
                val info = statsObj.optJSONObject(e.addr) ?: return@map e
                e.copy(
                    freezeMode = info.optString("mode", e.freezeMode),
                    blockedWrites = info.optLong("blocked", e.blockedWrites),
                    writesPerSec = info.optInt("rate", e.writesPerSec),
                    freezeStatus = info.optString("status", e.freezeStatus)
                )
            })
        }
    }

    private fun applyTraceLogsJson(tracesObj: JSONObject) {
        _state.update { st ->
            st.copy(watchList = st.watchList.map { e ->
                if (e.scope != MemScope.PAGE_WASM || !e.tracing) return@map e
                val tInfo = tracesObj.optJSONObject(e.addr) ?: return@map e
                val evArr = tInfo.optJSONArray("events")
                val evList = ArrayList<MemTraceEvent>()
                if (evArr != null) {
                    for (i in 0 until evArr.length()) {
                        val eo = evArr.optJSONObject(i) ?: continue
                        evList.add(
                            MemTraceEvent(
                                ts = eo.optLong("ts", 0L),
                                oldVal = eo.optString("oldVal"),
                                newVal = eo.optString("newVal"),
                                delta = eo.optString("delta"),
                                caller = eo.optString("caller")
                            )
                        )
                    }
                }
                e.copy(
                    traceCount = tInfo.optInt("count", evList.size),
                    traceEvents = evList,
                    uiWarningReason = detectUiWarningReason(e.role, e.addr, evList)
                )
            })
        }
    }

    // ---------- STRUCT DISSECTOR & LIVE HEX EDITOR ----------
    fun peekNearbyStruct(addr: String, scopeOverride: MemScope = _state.value.scope, radius: Int = 64) {
        scope.launch {
            val raw = when (scopeOverride) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.peekStruct(addr, radius) }
                MemScope.PAGE_WASM -> {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(addr)
                    runPageCommand(JSONObject().apply {
                        put("op", "peekStruct")
                        put("id", addr)
                        if (tFrame != null) put("targetFrame", tFrame)
                        put("radius", radius)
                    })
                }
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                val arr = obj.optJSONArray("fields") ?: JSONArray()
                val fields = ArrayList<MemStructField>(arr.length())
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    fields.add(
                        MemStructField(
                            addr = f.optString("addr"),
                            delta = f.optString("delta"),
                            type = f.optString("type", "i32"),
                            value = f.optString("val"),
                            i32Val = f.optString("i32"),
                            f32Val = f.optString("f32"),
                            u32Val = f.optString("u32")
                        )
                    )
                }
                _state.update {
                    it.copy(
                        structOriginAddr = addr,
                        structScope = scopeOverride,
                        structFields = fields,
                        statusText = "Found ${fields.size} nearby struct fields"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        structOriginAddr = addr,
                        structFields = emptyList(),
                        statusText = obj?.optString("error") ?: "Struct peek unavailable for this address"
                    )
                }
            }
        }
    }

    fun addStructFieldToWatch(field: MemStructField) {
        val st = _state.value
        val dt = resolveDataType(field.type, MemDataType.INT32)
        val curScope = st.structScope
        val watchId = "${curScope.name}|${field.addr}"
        if (st.watchList.any { it.id == watchId }) {
            _state.update { it.copy(statusText = "Field ${field.delta} already in Watch Table") }
            return
        }
        val entry = MemWatchEntry(
            id = watchId,
            scope = curScope,
            addr = field.addr,
            label = "Struct ${field.delta} (${field.type})",
            dataType = dt,
            currentValue = field.value,
            originalValue = field.value,
            attached = true
        )
        _state.update {
            it.copy(
                watchList = it.watchList + entry,
                statusText = "Added nearby field ${field.delta} to Watch Table"
            )
        }
        ensureWatchLoop()
    }

    fun inspectHex(addr: String, scopeOverride: MemScope = _state.value.scope, offsetDelta: Long = 0L) {
        scope.launch {
            val curHex = _state.value.hex
            val raw = when (scopeOverride) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    val base = NativeMemoryScanner.parseHexAddress(addr)
                    val target = if (base != null) (base + offsetDelta).coerceAtLeast(0L) else 0L
                    val targetHex = "0x${java.lang.Long.toHexString(target).uppercase()}"
                    NativeMemoryScanner.hexDump(targetHex, 128)
                }
                MemScope.PAGE_WASM -> {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(addr)
                    val cmd = JSONObject().apply {
                        put("op", "hexDump")
                        put("id", addr)
                        if (tFrame != null) put("targetFrame", tFrame)
                        if (offsetDelta != 0L && curHex.targetAddr == addr) {
                            put("offset", (curHex.baseOffset + offsetDelta).coerceAtLeast(0L))
                        }
                        put("len", 128)
                    }
                    runPageCommand(cmd)
                }
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                val disp = obj.optString("addr", addr)
                val off = if (scopeOverride == MemScope.FABLE_NATIVE) {
                    NativeMemoryScanner.parseHexAddress(disp) ?: 0L
                } else {
                    obj.optLong("offset", 0L)
                }
                _state.update {
                    it.copy(
                        hex = MemHexState(
                            targetAddr = if (scopeOverride == MemScope.FABLE_NATIVE) disp else addr,
                            scope = scopeOverride,
                            displayAddr = disp,
                            baseOffset = off,
                            hexBytes = obj.optString("hex", ""),
                            error = null
                        )
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        hex = MemHexState(
                            targetAddr = addr,
                            scope = scopeOverride,
                            error = obj?.optString("error", "Cannot inspect bytes") ?: "Cannot inspect bytes"
                        )
                    )
                }
            }
        }
    }

    fun writeHexBytes(hexStr: String, cursorOffsetDelta: Long = 0L) {
        val hx = _state.value.hex
        if (hx.targetAddr.isBlank() || hexStr.isBlank()) return
        scope.launch {
            val raw = when (hx.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    val base = NativeMemoryScanner.parseHexAddress(hx.displayAddr.ifBlank { hx.targetAddr }) ?: 0L
                    val target = (base + cursorOffsetDelta).coerceAtLeast(0L)
                    NativeMemoryScanner.writeHex("0x${java.lang.Long.toHexString(target).uppercase()}", hexStr)
                }
                MemScope.PAGE_WASM -> {
                    val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(hx.targetAddr)
                    runPageCommand(JSONObject().apply {
                        put("op", "writeHex")
                        put("id", hx.targetAddr)
                        if (tFrame != null) put("targetFrame", tFrame)
                        put("offset", (hx.baseOffset + cursorOffsetDelta).coerceAtLeast(0L))
                        put("hex", hexStr)
                    })
                }
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                _state.update { it.copy(statusText = "Patched ${obj.optInt("wrote", 0)} hex byte(s) @ +0x${cursorOffsetDelta.toString(16).uppercase()}") }
                inspectHex(hx.targetAddr, hx.scope, 0L)
            } else {
                _state.update { it.copy(statusText = obj?.optString("error") ?: "Hex write failed") }
            }
        }
    }

    fun jumpHexToOffset(rawAddrOrOff: String) {
        val hx = _state.value.hex
        val clean = rawAddrOrOff.trim()
        if (clean.isEmpty()) return
        val parsed = NativeMemoryScanner.parseHexAddress(clean)
            ?: clean.toLongOrNull()
            ?: return
        if (hx.scope == MemScope.FABLE_NATIVE || hx.targetAddr.isBlank()) {
            inspectHex("0x${java.lang.Long.toHexString(parsed).uppercase()}", MemScope.FABLE_NATIVE, 0L)
        } else {
            val delta = parsed - hx.baseOffset
            inspectHex(hx.targetAddr, hx.scope, delta)
        }
    }

    fun refreshRegions() {
        val curScope = _state.value.scope
        scope.launch {
            val raw = when (curScope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.listRegions() }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply { put("op", "regions") })
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.optBoolean("ok", false)) {
                val arr = obj.optJSONArray("regions") ?: JSONArray()
                val list = ArrayList<MemRegionItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    list.add(
                        MemRegionItem(
                            start = r.optString("start"),
                            end = r.optString("end"),
                            kb = r.optLong("kb", 0L),
                            perms = r.optString("perms"),
                            label = r.optString("label")
                        )
                    )
                }
                val engs = parseEngines(obj.optJSONArray("engines"))
                _state.update {
                    it.copy(
                        regions = list,
                        regionsTotalKb = obj.optLong("totalKb", 0L),
                        workerCount = obj.optInt("workerCount", it.workerCount),
                        frameCount = obj.optInt("frameCount", it.frameCount),
                        detectedEngines = if (engs.isNotEmpty()) engs else it.detectedEngines,
                        statusText = "${list.size} regions (${obj.optLong("totalKb", 0L)} KB)"
                    )
                }
            } else {
                _state.update { it.copy(statusText = obj?.optString("error") ?: "Failed to read memory map") }
            }
        }
    }

    private fun ensureWatchLoop() {
        if (watchLoopJob?.isActive == true) return
        watchLoopJob = scope.launch {
            var tickCount = 0
            while (isActive && engineActive) {
                tickCount++
                val stCur = _state.value
                val watches = stCur.watchList
                val needsWork = watches.isNotEmpty() || (stCur.liveScanRefresh && stCur.hasActiveScan && !stCur.busy) ||
                    (stCur.hexLiveRefresh && stCur.hex.targetAddr.isNotBlank())
                if (!needsWork) {
                    delay(500)
                    continue
                }
                val pageWatches = watches.filter { it.scope == MemScope.PAGE_WASM }
                val nativeWatches = watches.filter { it.scope == MemScope.FABLE_NATIVE }
                val updatedMap = HashMap<String, String>()

                if (pageWatches.isNotEmpty()) {
                    val ids = JSONArray().apply { pageWatches.forEach { put(it.addr) } }
                    val raw = runPageCommand(JSONObject().apply {
                        put("op", "readBatch")
                        put("ids", ids)
                    })
                    val obj = runCatching { JSONObject(raw) }.getOrNull()
                    val rbGen = obj?.optString("docGen").orEmpty()
                    if (rbGen.isNotBlank()) {
                        val genChanged = lastKnownDocGen.isNotBlank() && rbGen != lastKnownDocGen
                        lastKnownDocGen = rbGen
                        if (!_state.value.pageHookLive || genChanged) {
                            _state.update { it.copy(pageHookLive = true, hookedEarly = obj?.optBoolean("hookedEarly", it.hookedEarly) ?: it.hookedEarly, docGen = rbGen) }
                        }
                        if (genChanged && _state.value.autoReattachOnReload) {
                            autoReattachAndRearmAfterReload()
                            continue
                        }
                    }
                    val vals = obj?.optJSONObject("values")
                    if (vals != null) {
                        for (w in pageWatches) {
                            val v = vals.optString(w.addr, w.currentValue)
                            updatedMap[w.id] = v
                        }
                    }
                    val fStats = obj?.optJSONObject("freezeStats")
                    if (fStats != null) {
                        applyFreezeStatsJson(MemScope.PAGE_WASM, fStats)
                        val missingFrozen = pageWatches.filter { it.frozen && it.freezeStatus != "arming" && !fStats.has(it.addr) }
                        if (missingFrozen.isNotEmpty()) {
                            applyFreezeBatch(missingFrozen, freeze = true)
                        }
                    }
                    val trObj = obj?.optJSONObject("traces")
                    if (trObj != null && pageWatches.any { it.tracing }) {
                        applyTraceLogsJson(trObj)
                    }
                }
                if (nativeWatches.isNotEmpty()) {
                    val natStatsRaw = withContext(Dispatchers.IO) {
                        for (w in nativeWatches) {
                            updatedMap[w.id] = NativeMemoryScanner.readValue(w.addr, w.dataType, 16, w.xorMode, w.xorKey, w.xorKeyAddr)
                        }
                        if (nativeWatches.any { it.frozen }) NativeMemoryScanner.getFreezeStats() else null
                    }
                    val natStats = natStatsRaw?.let { runCatching { JSONObject(it).optJSONObject("stats") }.getOrNull() }
                    if (natStats != null) {
                        applyFreezeStatsJson(MemScope.FABLE_NATIVE, natStats)
                    }
                }
                val nowMs = System.currentTimeMillis()
                if (updatedMap.isNotEmpty()) {
                    _state.update { st ->
                        st.copy(watchList = st.watchList.map { e ->
                            val nv = updatedMap[e.id]
                            if (nv != null) {
                                val changed = nv != e.currentValue
                                val nextEvents = if (changed && e.tracing && e.scope == MemScope.FABLE_NATIVE) {
                                    val oNum = e.currentValue.toDoubleOrNull()
                                    val nNum = nv.toDoubleOrNull()
                                    val dStr = if (oNum != null && nNum != null) {
                                        val d = nNum - oNum
                                        (if (d >= 0) "+" else "") + formatMathNum(d, e.dataType)
                                    } else ""
                                    (e.traceEvents + MemTraceEvent(nowMs, e.currentValue, nv, dStr, "native-mem")).takeLast(20)
                                } else e.traceEvents
                                val nextHist = if (changed) (e.valueHistory + nv).takeLast(8) else if (e.valueHistory.isEmpty()) listOf(nv) else e.valueHistory
                                e.copy(
                                    currentValue = nv,
                                    lastChangedAtMs = if (changed) nowMs else e.lastChangedAtMs,
                                    valueHistory = nextHist,
                                    traceEvents = nextEvents,
                                    traceCount = if (changed && e.tracing && e.scope == MemScope.FABLE_NATIVE) e.traceCount + 1 else e.traceCount
                                )
                            } else e
                        })
                    }
                }
                // Live Scan Auto-Refresh every ~2 ticks (~800ms)
                if (tickCount % 2 == 0 && stCur.liveScanRefresh && stCur.hasActiveScan && !stCur.busy && stCur.results.isNotEmpty()) {
                    val rawRef = when (stCur.scope) {
                        MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.refreshHits(120) }
                        MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply { put("op", "refreshHits"); put("maxReturn", 120) })
                    }
                    val objRef = runCatching { JSONObject(rawRef) }.getOrNull()
                    val arrRef = objRef?.optJSONArray("items")
                    if (arrRef != null && arrRef.length() > 0) {
                        val valByAddr = HashMap<String, String>(arrRef.length())
                        for (i in 0 until arrRef.length()) {
                            val itObj = arrRef.optJSONObject(i) ?: continue
                            valByAddr[itObj.optString("addr")] = itObj.optString("val")
                        }
                        _state.update { cur ->
                            cur.copy(results = cur.results.map { r ->
                                val nv = valByAddr[r.addr]
                                if (nv != null && nv != r.value) r.copy(prevValue = r.value, value = nv, lastChangedAtMs = nowMs) else r
                            })
                        }
                    }
                }
                // Live Hex Auto-Refresh every ~2 ticks
                if (tickCount % 2 == 0 && stCur.hexLiveRefresh && stCur.hex.targetAddr.isNotBlank()) {
                    inspectHex(stCur.hex.targetAddr, stCur.hex.scope, 0L)
                }
                val hasActive = _state.value.watchList.any { it.frozen || it.tracing }
                delay(if (hasActive) 320L else 550L)
            }
        }
    }

    private fun stopWatchLoop() {
        watchLoopJob?.cancel()
        watchLoopJob = null
    }

    private suspend fun writeEntry(entry: MemWatchEntry, newValue: String): Boolean {
        return when (entry.scope) {
            MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                NativeMemoryScanner.writeValue(
                    entry.addr,
                    entry.dataType,
                    WebsiteMemoryEngine.normalizeNumericInput(newValue, entry.dataType),
                    entry.xorMode,
                    entry.xorKey,
                    entry.xorKeyAddr
                )
            }
            MemScope.PAGE_WASM -> {
                val (tFrame, _) = WebsiteMemoryEngine.splitFrameAddr(entry.addr)
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "write")
                    put("id", entry.addr)
                    if (tFrame != null) put("targetFrame", tFrame)
                    put("val", WebsiteMemoryEngine.normalizeNumericInput(newValue, entry.dataType))
                })
                runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
            }
        }
    }

    private suspend fun runPageCommand(cmd: JSONObject): String {
        val tab = tabManager.activeTab
        if (tab != null && tab.isHybridBlink.value) {
            _state.update { it.copy(activeEngineLabel = "Blink x Gecko") }
            val ctrl = tab.hybridController
                ?: return "{\"ok\":false,\"error\":\"Blink page not ready\"}"
            return ctrl.runMemoryCommand(cmd.toString())
        }
        _state.update { it.copy(activeEngineLabel = "GeckoView") }
        return proxyConfig.executeMemoryCommand(cmd)
    }

    private fun parseEngines(arr: JSONArray?): List<String> {
        if (arr == null || arr.length() == 0) return emptyList()
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) list.add(s)
        }
        return list
    }

    private fun applyScanResponse(rawJson: String, isFirst: Boolean) {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            val err = obj?.optString("error", "Scan failed") ?: "Scan failed"
            _state.update { it.copy(busy = false, scanElapsedMs = 0L, statusText = err) }
            return
        }
        val total = obj.optInt("total", 0)
        val canUndo = obj.optBoolean("canUndo", false)
        val undoDepth = obj.optInt("undoDepth", if (canUndo) 1 else 0)
        val wasmCnt = obj.optInt("wasmCount", 0)
        val bufCnt = obj.optInt("bufCount", 0)
        val workerCnt = obj.optInt("workerCount", 0)
        val frameCnt = obj.optInt("frameCount", 1)
        val skippedXO = obj.optInt("skippedXOrigin", 0)
        val elapsedMs = obj.optLong("elapsedMs", 0L)
        val timedOut = obj.optBoolean("timedOut", false)
        val fallbackUsed = obj.optBoolean("fallbackUsed", false)
        val snapActive = obj.optBoolean("snapshotActive", false)
        val engines = parseEngines(obj.optJSONArray("engines"))
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<MemScanResultItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val curVal = o.optString("val")
            list.add(
                MemScanResultItem(
                    addr = o.optString("addr"),
                    value = curVal,
                    prevValue = o.optString("prev", curVal),
                    type = o.optString("type", _state.value.dataType.jsCode),
                    kind = o.optString("kind", if (_state.value.scope == MemScope.FABLE_NATIVE) "native" else "js"),
                    role = o.optString("role", if (_state.value.scope == MemScope.FABLE_NATIVE) "game" else "game"),
                    score = o.optInt("score", 78),
                    engine = o.optString("engine", ""),
                    xorMode = o.optInt("xorMode", 0),
                    xorKey = o.optLong("xorKey", 0L),
                    xorKeyAddr = o.optString("xorKeyAddr", "")
                )
            )
        }
        val curScope = _state.value.scope
        val engLabel = if (engines.isNotEmpty()) "${engines.take(2).joinToString("+")} · " else ""
        val timeLabel = if (elapsedMs > 0L) " · ${elapsedMs}ms" else ""
        val warnTimeout = if (timedOut) " · timeout hit (raise s)" else ""
        val fbLabel = if (fallbackUsed && total > 0) " [approx/unalign]" else ""
        val status = when {
            snapActive ->
                "${engLabel}Snapshot ready (${total} slots$timeLabel) · change value & tap Next"
            total == 0 && curScope == MemScope.PAGE_WASM && wasmCnt == 0 ->
                "${engLabel}0 matches · Tap Hook Reload before starting game$timeLabel"
            total == 0 ->
                "${engLabel}0 matches found$timeLabel$warnTimeout"
            curScope == MemScope.PAGE_WASM -> {
                val fTxt = if (frameCnt > 1) ", ${frameCnt}f" else ""
                val wTxt = if (workerCnt > 0) ", ${workerCnt}w" else ""
                "$engLabel$total match${if (total == 1) "" else "es"}$fbLabel ($wasmCnt WASM, $bufCnt buf$fTxt$wTxt$timeLabel)$warnTimeout"
            }
            else ->
                "$total match${if (total == 1) "" else "es"} (showing ${list.size}$timeLabel)$warnTimeout"
        }
        val wroteCnt = obj.optInt("wrote", -1)
        val finalStatus = if (wroteCnt >= 0) "Wrote $wroteCnt match${if (wroteCnt == 1) "" else "es"} · $status" else status
        _state.update {
            it.copy(
                busy = false,
                scanElapsedMs = 0L,
                lastScanDurationMs = elapsedMs,
                lastScanTimedOut = timedOut,
                fallbackUsed = fallbackUsed,
                snapshotActive = snapActive,
                hasActiveScan = total > 0 || snapActive || !isFirst,
                canUndo = canUndo,
                undoDepth = undoDepth,
                totalMatches = total,
                uiHiddenCount = obj.optInt("uiHiddenCount", 0),
                pageHookLive = if (_state.value.scope == MemScope.PAGE_WASM) true else it.pageHookLive,
                hookedEarly = obj.optBoolean("hookedEarly", it.hookedEarly),
                docGen = obj.optString("docGen").ifBlank { it.docGen },
                wasmCount = wasmCnt,
                bufCount = bufCnt,
                workerCount = workerCnt,
                frameCount = frameCnt,
                skippedXOrigin = skippedXO,
                detectedEngines = engines,
                results = list,
                selectedScanAddrs = emptySet(),
                scanType = if (isFirst && it.scanType == MemScanType.UNKNOWN && (total > 0 || snapActive)) MemScanType.CHANGED else it.scanType,
                statusText = finalStatus
            )
        }
    }

    private fun resolveDataType(jsCode: String, fallback: MemDataType): MemDataType =
        MemDataType.entries.firstOrNull { it.jsCode == jsCode } ?: fallback
}
