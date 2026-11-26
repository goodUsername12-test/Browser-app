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

enum class MemFreezeRule(val jsCode: String, val nativeCode: Int, val symbol: String, val label: String) {
    EXACT("eq", 0, "=", "Exact Lock"),
    MIN("min", 1, ">=", "Min Floor (>=)"),
    MAX("max", 2, "<=", "Max Cap (<=)");

    fun next(): MemFreezeRule = when (this) {
        EXACT -> MIN
        MIN -> MAX
        MAX -> EXACT
    }

    companion object {
        fun fromCode(code: String?): MemFreezeRule = when (code) {
            "min" -> MIN
            "max" -> MAX
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
    val f32Val: String
)

data class MemScanResultItem(
    val addr: String,
    val value: String,
    val prevValue: String = "",
    val type: String = "i32",
    val kind: String = "native",
    val engine: String = "",
    val lastChangedAtMs: Long = 0L
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
    val frozenValue: String = "",
    val freezeRule: MemFreezeRule = MemFreezeRule.EXACT,
    val freezeMode: String = "",
    val blockedWrites: Long = 0L,
    val freezeStatus: String = "",
    val lastChangedAtMs: Long = 0L
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
    private var activeScanJob: Job? = null
    private var scanTimerJob: Job? = null
    @Volatile private var engineActive: Boolean = false

    fun setEngineActive(enabled: Boolean) {
        engineActive = enabled
        if (!enabled) {
            cancelActiveScan()
            unfreezeAll()
            resetScan()
            stopWatchLoop()
        } else {
            ensureWatchLoop()
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

    fun cancelActiveScan() {
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
                statusText = "Scanning ${st.scope.label} (budget ${st.scanTimeoutSec}s)..."
            )
        }
        startScanTimer()
        activeScanJob = scope.launch {
            try {
                val n1 = WebsiteMemoryEngine.normalizeNumericInput(val1, st.dataType)
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
        val effectiveScanType = when {
            (st.scanType == MemScanType.UNKNOWN || st.scanType == MemScanType.CHANGED ||
                st.scanType == MemScanType.INCREASED || st.scanType == MemScanType.DECREASED ||
                st.scanType == MemScanType.UNCHANGED) && clean1.isNotEmpty() -> MemScanType.EXACT
            st.scanType == MemScanType.UNKNOWN -> MemScanType.CHANGED
            else -> st.scanType
        }
        val timeoutMs = WebsiteMemoryEngine.clampTimeoutSec(st.scanTimeoutSec) * 1000
        _state.update {
            it.copy(
                busy = true,
                scanElapsedMs = 0L,
                scanType = effectiveScanType,
                selectedScanAddrs = emptySet(),
                statusText = "Filtering matches..."
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
                val entry = MemWatchEntry(
                    id = watchId,
                    scope = curScope,
                    addr = item.addr,
                    label = item.addr.removePrefix("js:").takeLast(32),
                    dataType = dt,
                    currentValue = item.value,
                    originalValue = item.value,
                    frozen = freezeImmediately,
                    frozenValue = if (freezeImmediately) item.value else ""
                )
                toAdd.add(entry)
                if (freezeImmediately) toFreezeEntries.add(entry)
            } else if (existing != null && freezeImmediately && !existing.frozen) {
                toFreezeEntries.add(existing.copy(frozen = true, frozenValue = existing.currentValue))
            }
        }

        _state.update { cur ->
            val updatedExisting = if (freezeImmediately) {
                val fIds = toFreezeEntries.map { it.id }.toSet()
                cur.watchList.map { e ->
                    if (e.id in fIds && !e.frozen) e.copy(frozen = true, frozenValue = e.currentValue) else e
                }
            } else cur.watchList
            cur.copy(
                watchList = updatedExisting + toAdd,
                selectedScanAddrs = emptySet(),
                statusText = when {
                    freezeImmediately -> "Added ${toAdd.size} & locked ${toFreezeEntries.size} in Watch Table"
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
        if (st.busy || clean.isEmpty()) return
        val targets = if (st.selectedScanAddrs.isNotEmpty()) {
            st.results.filter { it.addr in st.selectedScanAddrs }
        } else {
            st.results
        }
        if (targets.isEmpty()) return
        _state.update { it.copy(busy = true, statusText = "Writing ${targets.size} selected matches...") }
        scope.launch {
            var wrote = 0
            val updatedVals = HashMap<String, String>()
            if (st.scope == MemScope.PAGE_WASM) {
                val arr = JSONArray()
                for (item in targets.take(200)) {
                    val dt = resolveDataType(item.type, st.dataType)
                    arr.put(JSONObject().apply {
                        put("id", item.addr)
                        put("val", WebsiteMemoryEngine.normalizeNumericInput(clean, dt))
                    })
                }
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "writeBatch")
                    put("entries", arr)
                })
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                wrote = obj?.optInt("wrote", 0) ?: 0
                val valsObj = obj?.optJSONObject("values")
                valsObj?.keys()?.forEach { k -> updatedVals[k] = valsObj.optString(k) }
            } else {
                withContext(Dispatchers.IO) {
                    for (item in targets.take(200)) {
                        val dt = resolveDataType(item.type, st.dataType)
                        val norm = WebsiteMemoryEngine.normalizeNumericInput(clean, dt)
                        if (NativeMemoryScanner.writeValue(item.addr, dt, norm)) {
                            wrote++
                            updatedVals[item.addr] = norm
                        }
                    }
                }
            }
            _state.update { cur ->
                cur.copy(
                    busy = false,
                    results = cur.results.map { r ->
                        updatedVals[r.addr]?.let { v -> r.copy(prevValue = r.value, value = v) } ?: r
                    },
                    statusText = "Wrote $wrote / ${targets.size} selected = $clean"
                )
            }
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
                    put("val", WebsiteMemoryEngine.normalizeNumericInput(clean, st.dataType))
                    put("maxWrite", 150)
                    put("maxReturn", 300)
                })
                applyScanResponse(raw, isFirst = false)
            } else {
                var wrote = 0
                withContext(Dispatchers.IO) {
                    for (item in st.results.take(150)) {
                        val dt = resolveDataType(item.type, st.dataType)
                        if (NativeMemoryScanner.writeValue(item.addr, dt, WebsiteMemoryEngine.normalizeNumericInput(clean, dt))) wrote++
                    }
                }
                val raw = withContext(Dispatchers.IO) { NativeMemoryScanner.refreshHits(300) }
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
                MemScope.FABLE_NATIVE -> withContext(Dispatchers.IO) { NativeMemoryScanner.refreshHits(300) }
                MemScope.PAGE_WASM -> runPageCommand(JSONObject().apply {
                    put("op", "refreshHits")
                    put("maxReturn", 300)
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
            label = item.addr.removePrefix("js:").takeLast(32),
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
        val existing = _state.value.watchList.any { it.id == watchId }
        if (existing) {
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
            val okIds = HashSet<String>()
            val pageTargets = targets.filter { it.scope == MemScope.PAGE_WASM }
            val nativeTargets = targets.filter { it.scope == MemScope.FABLE_NATIVE }
            if (pageTargets.isNotEmpty()) {
                val arr = JSONArray()
                for (w in pageTargets) {
                    arr.put(JSONObject().apply {
                        put("id", w.addr)
                        put("val", WebsiteMemoryEngine.normalizeNumericInput(clean, w.dataType))
                    })
                }
                val raw = runPageCommand(JSONObject().apply {
                    put("op", "writeBatch")
                    put("entries", arr)
                })
                val valsObj = runCatching { JSONObject(raw).optJSONObject("values") }.getOrNull()
                for (w in pageTargets) {
                    if (valsObj?.has(w.addr) == true) okIds.add(w.id)
                }
            }
            if (nativeTargets.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    for (w in nativeTargets) {
                        val norm = WebsiteMemoryEngine.normalizeNumericInput(clean, w.dataType)
                        if (NativeMemoryScanner.writeValue(w.addr, w.dataType, norm)) {
                            okIds.add(w.id)
                            if (w.frozen) NativeMemoryScanner.setFreeze(w.addr, w.dataType, norm, w.freezeRule.nativeCode)
                        }
                    }
                }
            }
            val frozenPageTargets = pageTargets.filter { it.frozen && it.id in okIds }.map { it.copy(frozenValue = clean) }
            if (frozenPageTargets.isNotEmpty()) {
                applyFreezeBatch(frozenPageTargets, freeze = true)
            }
            _state.update { cur ->
                cur.copy(
                    watchList = cur.watchList.map { e ->
                        if (e.id in okIds) e.copy(
                            currentValue = clean,
                            frozenValue = if (e.frozen) clean else e.frozenValue
                        ) else e
                    },
                    statusText = "Edited ${okIds.size} / ${targets.size} watched = $clean"
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
                frozenValue = if (freeze) (e.frozenValue.ifBlank { e.currentValue }) else e.frozenValue,
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
                        frozenValue = if (freeze) (e.frozenValue.ifBlank { e.currentValue }) else e.frozenValue,
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
        scope.launch { applyFreezeBatch(targets.filter { it.frozen }, freeze = false) }
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
        if (entry != null && entry.frozen) {
            scope.launch { applyFreezeSingle(entry, freeze = false) }
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
        val curNum = WebsiteMemoryEngine.normalizeNumericInput(entry.currentValue, entry.dataType).toDoubleOrNull()
        val nextStr = when (mathOp) {
            "+100" -> if (curNum != null) formatMathNum(curNum + 100.0, entry.dataType) else "100"
            "+1000" -> if (curNum != null) formatMathNum(curNum + 1000.0, entry.dataType) else "1000"
            "x2" -> if (curNum != null) formatMathNum(curNum * 2.0, entry.dataType) else "200"
            "x10" -> if (curNum != null) formatMathNum(curNum * 10.0, entry.dataType) else "1000"
            "999999" -> "999999"
            "0" -> "0"
            else -> return
        }
        setWatchValue(watchId, nextStr)
    }

    private fun formatMathNum(v: Double, dt: MemDataType): String {
        return if (dt == MemDataType.FLOAT32 || dt == MemDataType.FLOAT64) {
            if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.3f", v)
        } else {
            v.toLong().toString()
        }
    }

    fun cycleWatchFreezeRule(watchId: String) {
        val existing = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        val nextRule = existing.freezeRule.next()
        val updated = existing.copy(freezeRule = nextRule)
        _state.update { st ->
            st.copy(
                watchList = st.watchList.map { e -> if (e.id == watchId) updated else e },
                statusText = "${existing.label} freeze mode: ${nextRule.label}"
            )
        }
        if (updated.frozen) {
            scope.launch { applyFreezeSingle(updated, freeze = true) }
        }
    }

    fun setWatchValue(watchId: String, newValue: String) {
        val entry = _state.value.watchList.firstOrNull { it.id == watchId } ?: return
        scope.launch {
            val ok = writeEntry(entry, newValue)
            if (ok && entry.frozen) {
                applyFreezeSingle(entry.copy(frozenValue = newValue), freeze = true)
            }
            _state.update { st ->
                st.copy(
                    watchList = st.watchList.map { e ->
                        if (e.id == watchId) e.copy(
                            currentValue = if (ok) newValue else e.currentValue,
                            frozenValue = if (e.frozen && ok) newValue else e.frozenValue,
                            lastChangedAtMs = if (ok) System.currentTimeMillis() else e.lastChangedAtMs
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
        val lockVal = if (nextFreeze) existing.currentValue else existing.frozenValue
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

    private suspend fun applyFreezeSingle(entry: MemWatchEntry, freeze: Boolean): Boolean {
        when (entry.scope) {
            MemScope.FABLE_NATIVE -> {
                if (!freeze) {
                    withContext(Dispatchers.IO) { NativeMemoryScanner.unfreeze(entry.addr) }
                    return true
                }
                val norm = WebsiteMemoryEngine.normalizeNumericInput(entry.frozenValue.ifBlank { entry.currentValue }, entry.dataType)
                val ok = withContext(Dispatchers.IO) {
                    NativeMemoryScanner.setFreeze(entry.addr, entry.dataType, norm, entry.freezeRule.nativeCode)
                }
                _state.update { st ->
                    st.copy(
                        watchList = st.watchList.map { e ->
                            if (e.id == entry.id) {
                                if (ok) e.copy(frozen = true, freezeMode = "500Hz", freezeStatus = "locked")
                                else e.copy(frozen = false, freezeMode = "", freezeStatus = "failed")
                            } else e
                        },
                        statusText = if (ok) "Confirmed 500Hz ${entry.freezeRule.symbol} lock on ${entry.label}" else "Freeze FAILED on ${entry.label}: native address invalid or worker unavailable"
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
                        if (NativeMemoryScanner.setFreeze(e.addr, e.dataType, norm, e.freezeRule.nativeCode)) {
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
                            e.id in confirmedIds -> e.copy(frozen = true, freezeMode = "500Hz", freezeStatus = "locked")
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
                    freezeStatus = info.optString("status", e.freezeStatus)
                )
            })
        }
    }

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
                            f32Val = f.optString("f32")
                        )
                    )
                }
                _state.update {
                    it.copy(
                        structOriginAddr = addr,
                        structScope = scopeOverride,
                        structFields = fields,
                        statusText = "Found ${fields.size} nearby struct fields (±${radius}B)"
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
            originalValue = field.value
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
            while (isActive && engineActive) {
                val watches = _state.value.watchList
                if (watches.isEmpty()) {
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
                }
                if (nativeWatches.isNotEmpty()) {
                    val natStatsRaw = withContext(Dispatchers.IO) {
                        for (w in nativeWatches) {
                            updatedMap[w.id] = NativeMemoryScanner.readValue(w.addr, w.dataType)
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
                                e.copy(
                                    currentValue = nv,
                                    lastChangedAtMs = if (nv != e.currentValue) nowMs else e.lastChangedAtMs
                                )
                            } else e
                        })
                    }
                }
                val hasFrozen = _state.value.watchList.any { it.frozen }
                delay(if (hasFrozen) 320L else 600L)
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
                NativeMemoryScanner.writeValue(entry.addr, entry.dataType, WebsiteMemoryEngine.normalizeNumericInput(newValue, entry.dataType))
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
                    engine = o.optString("engine", "")
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
                "${engLabel}Snapshot ready (${total} slots · $wasmCnt WASM, $bufCnt buf$timeLabel) · change value & tap Next"
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
                totalMatches = total,
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
