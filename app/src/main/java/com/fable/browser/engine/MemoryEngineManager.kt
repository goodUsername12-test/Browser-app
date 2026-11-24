package com.fable.browser.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MemScanResultItem(
    val addr: String,
    val value: String,
    val prevValue: String = "",
    val type: String = "i32",
    val kind: String = "native"
)

data class MemWatchEntry(
    val id: String,
    val scope: MemScope,
    val addr: String,
    val label: String,
    val dataType: MemDataType,
    val currentValue: String,
    val originalValue: String,
    val frozen: Boolean = false,
    val frozenValue: String = ""
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
    val busy: Boolean = false,
    val hasActiveScan: Boolean = false,
    val canUndo: Boolean = false,
    val totalMatches: Int = 0,
    val wasmCount: Int = 0,
    val bufCount: Int = 0,
    val results: List<MemScanResultItem> = emptyList(),
    val watchList: List<MemWatchEntry> = emptyList(),
    val hex: MemHexState = MemHexState(),
    val regions: List<MemRegionItem> = emptyList(),
    val regionsTotalKb: Long = 0L,
    val statusText: String = "Enter a number and tap First Scan",
    val activeEngineLabel: String = "Auto"
)

@Singleton
class MemoryEngineManager @Inject constructor(
    private val tabManager: TabManager,
    private val proxyConfig: ProxyConfig
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MemoryEngineUiState())
    val state: StateFlow<MemoryEngineUiState> = _state.asStateFlow()

    private var watchLoopJob: Job? = null
    @Volatile private var engineActive: Boolean = false

    fun setEngineActive(enabled: Boolean) {
        engineActive = enabled
        if (!enabled) {
            unfreezeAll()
            resetScan()
            stopWatchLoop()
        } else {
            ensureWatchLoop()
        }
    }

    fun setScope(newScope: MemScope) {
        if (_state.value.scope == newScope) return
        _state.update {
            it.copy(
                scope = newScope,
                hasActiveScan = false,
                canUndo = false,
                totalMatches = 0,
                results = emptyList(),
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

    fun firstScan(val1: String, val2: String = "") {
        val st = _state.value
        if (st.busy) return
        _state.update { it.copy(busy = true, statusText = "Scanning ${st.scope.label}...") }
        scope.launch {
            val raw = when (st.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    NativeMemoryScanner.firstScan(st.dataType, st.scanType, val1.trim(), val2.trim(), 150)
                }
                MemScope.PAGE_WASM -> {
                    val cmd = JSONObject().apply {
                        put("op", "firstScan")
                        put("dtype", st.dataType.jsCode)
                        put("scanType", st.scanType.jsCode)
                        put("val1", val1.trim())
                        put("val2", val2.trim())
                        put("maxReturn", 150)
                    }
                    runPageCommand(cmd)
                }
            }
            applyScanResponse(raw, isFirst = true)
        }
    }

    fun nextScan(val1: String, val2: String = "") {
        val st = _state.value
        if (st.busy) return
        val clean1 = val1.trim()
        val effectiveScanType = when {
            (st.scanType == MemScanType.UNKNOWN || st.scanType == MemScanType.CHANGED ||
                st.scanType == MemScanType.INCREASED || st.scanType == MemScanType.DECREASED ||
                st.scanType == MemScanType.UNCHANGED) && clean1.isNotEmpty() -> MemScanType.EXACT
            st.scanType == MemScanType.UNKNOWN -> MemScanType.CHANGED
            else -> st.scanType
        }
        _state.update { it.copy(busy = true, scanType = effectiveScanType, statusText = "Filtering matches...") }
        scope.launch {
            val raw = when (st.scope) {
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) {
                    NativeMemoryScanner.nextScan(effectiveScanType, clean1, val2.trim(), 150)
                }
                MemScope.PAGE_WASM -> {
                    val cmd = JSONObject().apply {
                        put("op", "nextScan")
                        put("scanType", effectiveScanType.jsCode)
                        put("val1", clean1)
                        put("val2", val2.trim())
                        put("maxReturn", 150)
                    }
                    runPageCommand(cmd)
                }
            }
            applyScanResponse(raw, isFirst = false)
        }
    }

    fun writeAllScanResults(newValue: String) {
        val st = _state.value
        val clean = newValue.trim()
        if (st.busy || clean.isEmpty() || st.results.isEmpty()) return
        _state.update { it.copy(busy = true, statusText = "Writing all matches...") }
        scope.launch {
            if (st.scope == MemScope.PAGE_WASM) {
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "writeAll")
                    put("val", clean)
                    put("maxWrite", 50)
                    put("maxReturn", 150)
                })
                applyScanResponse(raw, isFirst = false)
            } else {
                var wrote = 0
                withContext(Dispatchers.IO) {
                    for (item in st.results.take(50)) {
                        val dt = resolveDataType(item.type, st.dataType)
                        if (NativeMemoryScanner.writeValue(item.addr, dt, clean)) wrote++
                    }
                }
                val raw = withContext(Dispatchers.IO) { NativeMemoryScanner.refreshHits(150) }
                applyScanResponse(raw, isFirst = false)
                _state.update { it.copy(statusText = "Wrote $wrote match${if (wrote == 1) "" else "es"} = $clean") }
            }
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
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.refreshHits(150) }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply {
                    put("op", "refreshHits")
                    put("maxReturn", 150)
                })
            }
            applyScanResponse(raw, isFirst = false)
        }
    }

    fun writeScanItem(item: MemScanResultItem, newValue: String) {
        val curScope = _state.value.scope
        val dt = resolveDataType(item.type, _state.value.dataType)
        val tmp = MemWatchEntry(
            id = "${curScope.name}|${item.addr}",
            scope = curScope,
            addr = item.addr,
            label = item.addr.removePrefix("js:").takeLast(28),
            dataType = dt,
            currentValue = item.value,
            originalValue = item.value
        )
        scope.launch {
            val ok = writeEntry(tmp, newValue)
            _state.update { st ->
                st.copy(
                    results = if (ok) st.results.map {
                        if (it.addr == item.addr) it.copy(prevValue = it.value, value = newValue) else it
                    } else st.results,
                    statusText = if (ok) "Wrote ${tmp.label} = $newValue" else "Write blocked or address invalid"
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
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.undoScan(150) }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply {
                    put("op", "undo")
                    put("maxReturn", 150)
                })
            }
            applyScanResponse(raw, isFirst = false)
        }
    }

    fun resetScan() {
        scope.launch {
            withContext(Dispatchers.IO) { NativeMemoryScanner.resetScan() }
            if (engineActive) {
                runCatching { runPageCommand(JSONObject().apply { put("op", "reset") }) }
            }
            _state.update {
                it.copy(
                    hasActiveScan = false,
                    canUndo = false,
                    totalMatches = 0,
                    results = emptyList(),
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
        val existing = _state.value.watchList.any { it.id == watchId }
        if (existing) {
            _state.update { it.copy(statusText = "Already in Watch List") }
            return
        }
        val label = customLabel.ifBlank {
            item.addr.removePrefix("js:").takeLast(28)
        }
        val entry = MemWatchEntry(
            id = watchId,
            scope = curScope,
            addr = item.addr,
            label = label,
            dataType = dt,
            currentValue = item.value,
            originalValue = item.value
        )
        _state.update {
            it.copy(
                watchList = it.watchList + entry,
                statusText = "Added ${entry.label} to Watch"
            )
        }
        ensureWatchLoop()
    }

    fun removeWatch(watchId: String) {
        _state.update { it.copy(watchList = it.watchList.filterNot { e -> e.id == watchId }) }
    }

    fun setWatchValue(watchId: String, newValue: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        scope.launch {
            val ok = writeEntry(entry, newValue)
            _state.update { st ->
                st.copy(
                    watchList = st.watchList.map { e ->
                        if (e.id == watchId) e.copy(
                            currentValue = if (ok) newValue else e.currentValue,
                            frozenValue = if (e.frozen && ok) newValue else e.frozenValue
                        ) else e
                    },
                    statusText = if (ok) "Set ${entry.label} = $newValue" else "Write blocked or address invalid"
                )
            }
        }
    }

    fun restoreWatchOriginal(watchId: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        _state.update { st ->
            st.copy(watchList = st.watchList.map { if (it.id == watchId) it.copy(frozen = false) else it })
        }
        setWatchValue(watchId, entry.originalValue)
    }

    fun toggleWatchFreeze(watchId: String) {
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { e ->
                    if (e.id == watchId) {
                        val nextFreeze = !e.frozen
                        e.copy(frozen = nextFreeze, frozenValue = if (nextFreeze) e.currentValue else e.frozenValue)
                    } else e
                }
            )
        }
        ensureWatchLoop()
    }

    fun unfreezeAll() {
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { it.copy(frozen = false) },
                statusText = "All frozen values unlocked"
            )
        }
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
                    val cmd = JSONObject().apply {
                        put("op", "hexDump")
                        put("id", addr)
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
                _state.update {
                    it.copy(
                        regions = list,
                        regionsTotalKb = obj.optLong("totalKb", 0L),
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
            while (isActive && engineActive) {
                val watches = _state.value.watchList
                if (watches.isEmpty()) {
                    delay(500)
                    continue
                }
                for (w in watches) {
                    if (w.frozen && w.frozenValue.isNotEmpty()) {
                        writeEntry(w, w.frozenValue)
                    }
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
                    val vals = obj?.optJSONObject("values")
                    if (vals != null) {
                        for (w in pageWatches) {
                            val v = vals.optString(w.addr, w.currentValue)
                            updatedMap[w.id] = v
                        }
                    }
                }
                if (nativeWatches.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        for (w in nativeWatches) {
                            updatedMap[w.id] = NativeMemoryScanner.readValue(w.addr, w.dataType)
                        }
                    }
                }
                if (updatedMap.isNotEmpty()) {
                    _state.update { st ->
                        st.copy(watchList = st.watchList.map { e ->
                            updatedMap[e.id]?.let { e.copy(currentValue = it) } ?: e
                        })
                    }
                }
                val hasFrozen = _state.value.watchList.any { it.frozen }
                delay(if (hasFrozen) 150L else 600L)
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
                NativeMemoryScanner.writeValue(entry.addr, entry.dataType, newValue)
            }
            MemScope.PAGE_WASM -> {
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "write")
                    put("id", entry.addr)
                    put("val", newValue)
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

    private fun applyScanResponse(rawJson: String, isFirst: Boolean) {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            val err = obj?.optString("error", "Scan failed") ?: "Scan failed"
            _state.update { it.copy(busy = false, statusText = err) }
            return
        }
        val total = obj.optInt("total", 0)
        val canUndo = obj.optBoolean("canUndo", false)
        val wasmCnt = obj.optInt("wasmCount", 0)
        val bufCnt = obj.optInt("bufCount", 0)
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
                    kind = o.optString("kind", if (_state.value.scope == MemScope.FABLE_NATIVE) "native" else "js")
                )
            )
        }
        val curScope = _state.value.scope
        val status = when {
            total == 0 && curScope == MemScope.PAGE_WASM && wasmCnt == 0 ->
                "0 matches · Reload page with CE ON to hook WASM/game memory"
            total == 0 ->
                "0 matches found"
            curScope == MemScope.PAGE_WASM ->
                "$total match${if (total == 1) "" else "es"} (${wasmCnt} WASM, ${bufCnt} buf) · change value & tap Next"
            else ->
                "$total match${if (total == 1) "" else "es"} (showing ${list.size}) · change value & tap Next"
        }
        val wroteCnt = obj.optInt("wrote", -1)
        val finalStatus = if (wroteCnt >= 0) "Wrote $wroteCnt match${if (wroteCnt == 1) "" else "es"} · $status" else status
        _state.update {
            it.copy(
                busy = false,
                hasActiveScan = total > 0 || !isFirst,
                canUndo = canUndo,
                totalMatches = total,
                wasmCount = wasmCnt,
                bufCount = bufCnt,
                results = list,
                scanType = if (isFirst && it.scanType == MemScanType.UNKNOWN && total > 0) MemScanType.CHANGED else it.scanType,
                statusText = finalStatus
            )
        }
    }

    private fun resolveDataType(jsCode: String, fallback: MemDataType): MemDataType =
        MemDataType.entries.firstOrNull { it.jsCode == jsCode } ?: fallback
}
