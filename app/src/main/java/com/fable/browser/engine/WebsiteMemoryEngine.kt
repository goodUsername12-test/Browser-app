package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToLong

object WebsiteMemoryEngine {
    const val MIN_PANEL_WIDTH_PCT = 55
    const val MAX_PANEL_WIDTH_PCT = 100
    const val DEFAULT_PANEL_WIDTH_PCT = 88

    const val MIN_PANEL_HEIGHT_PCT = 40
    const val MAX_PANEL_HEIGHT_PCT = 95
    const val DEFAULT_PANEL_HEIGHT_PCT = 62

    const val MIN_PANEL_SCALE_PCT = 60
    const val MAX_PANEL_SCALE_PCT = 140
    const val DEFAULT_PANEL_SCALE_PCT = 100

    const val DEFAULT_PANEL_POS_X = 0.06f
    const val DEFAULT_PANEL_POS_Y = 0.20f

    const val MIN_TIMEOUT_SEC = 1
    const val MAX_TIMEOUT_SEC = 80
    const val DEFAULT_TIMEOUT_SEC = 15

    private val CHANNEL_ID: String = "_mev_" + UUID.randomUUID().toString().replace("-", "").take(14)

    fun clampPanelWidthPct(pct: Int): Int = pct.coerceIn(MIN_PANEL_WIDTH_PCT, MAX_PANEL_WIDTH_PCT)

    fun clampPanelHeightPct(pct: Int): Int = pct.coerceIn(MIN_PANEL_HEIGHT_PCT, MAX_PANEL_HEIGHT_PCT)

    fun clampPanelScalePct(pct: Int): Int = pct.coerceIn(MIN_PANEL_SCALE_PCT, MAX_PANEL_SCALE_PCT)

    fun clampTimeoutSec(sec: Int): Int = sec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    fun normalizeNumericInput(raw: String, dataType: MemDataType): String {
        val t = raw.trim()
        if (dataType == MemDataType.STRING || dataType == MemDataType.AOB) return t
        if (t.contains("..") || t.contains(";") || t.contains(":")) return t
        val cleaned = t.replace(",", "").replace("_", "").replace(" ", "")
        if (cleaned.startsWith("0x", ignoreCase = true) || cleaned.startsWith("-0x", ignoreCase = true)) {
            val neg = cleaned.startsWith("-")
            val hexPart = cleaned.removePrefix("-").removePrefix("0x").removePrefix("0X")
            val parsed = runCatching { java.lang.Long.parseUnsignedLong(hexPart, 16) }.getOrNull()
            if (parsed != null) return if (neg) "-$parsed" else parsed.toString()
        }
        return if (cleaned.isNotEmpty() && cleaned.toDoubleOrNull() != null) cleaned else t
    }

    /**
     * Evaluates relative math expressions like "+500", "-25", "*2", "x10", "/2",
     * or returns the normalized direct input when not a relative expression.
     */
    fun evaluateRelativeMath(currentValStr: String, rawExpr: String, dataType: MemDataType): String {
        val expr = rawExpr.trim()
        if (expr.isEmpty()) return expr
        if (dataType == MemDataType.STRING || dataType == MemDataType.AOB) return expr
        if (expr.contains("..")) return expr
        val first = expr[0]
        val isRelOp = (first == '+' || first == '*' || first == 'x' || first == 'X' || first == '/') ||
            (first == '-' && expr.length > 1 && expr.startsWith("--"))
        if (!isRelOp) return normalizeNumericInput(expr, dataType)

        val cur = normalizeNumericInput(currentValStr, dataType).toDoubleOrNull() ?: return normalizeNumericInput(expr, dataType)
        val operandStr = when {
            expr.startsWith("--") -> expr.substring(2)
            else -> expr.substring(1)
        }.replace(",", "").replace("_", "").replace(" ", "")
        val opNum = operandStr.toDoubleOrNull() ?: return normalizeNumericInput(expr, dataType)
        val next = when {
            expr.startsWith("+") -> cur + opNum
            expr.startsWith("--") -> cur - opNum
            first == '*' || first == 'x' || first == 'X' -> cur * opNum
            first == '/' -> if (opNum != 0.0) cur / opNum else cur
            else -> opNum
        }
        if (!next.isFinite()) return currentValStr
        return when (dataType) {
            MemDataType.FLOAT, MemDataType.DOUBLE -> {
                if (next == next.roundToLong().toDouble()) next.roundToLong().toString()
                else String.format(java.util.Locale.US, "%.4f", next).trimEnd('0').trimEnd('.')
            }
            MemDataType.UINT32 -> next.roundToLong().coerceAtLeast(0L).toString()
            else -> next.roundToLong().toString()
        }
    }

    fun splitFrameAddr(addr: String): Pair<String?, String> {
        if (!addr.startsWith("@")) return null to addr
        val sep = addr.indexOf('|')
        if (sep <= 1) return null to addr
        return addr.substring(1, sep) to addr.substring(sep + 1)
    }

    fun mergeMultiFrameResults(payloads: List<String>, op: String, maxReturn: Int = 300): String {
        val parsed = payloads.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filterNot { it.optBoolean("skippedFrame", false) }
        if (parsed.isEmpty()) return "{\"ok\":false,\"error\":\"Empty page response\"}"
        if (parsed.size == 1) return parsed[0].toString()

        if (op == "readBatch") {
            val mergedVals = JSONObject()
            val mergedStats = JSONObject()
            val mergedTraces = JSONObject()
            for (o in parsed) {
                val vals = o.optJSONObject("values")
                if (vals != null) vals.keys().forEach { k -> mergedVals.put(k, vals.optString(k)) }
                val fs = o.optJSONObject("freezeStats")
                if (fs != null) fs.keys().forEach { k -> mergedStats.put(k, fs.opt(k)) }
                val tr = o.optJSONObject("traces")
                if (tr != null) tr.keys().forEach { k -> mergedTraces.put(k, tr.opt(k)) }
            }
            var docGen = ""
            var hookedEarly = false
            for (o in parsed) {
                if (docGen.isEmpty()) docGen = o.optString("docGen")
                if (o.optBoolean("hookedEarly", false)) hookedEarly = true
            }
            return JSONObject()
                .put("ok", true)
                .put("docGen", docGen)
                .put("hookedEarly", hookedEarly)
                .put("values", mergedVals)
                .put("freezeStats", mergedStats)
                .put("traces", mergedTraces)
                .toString()
        }
        if (op == "ping") {
            var docGen = ""
            var hookedEarly = false
            var freezeStrength = 1
            var wasmCount = 0
            var bufCount = 0
            var workerCount = 0
            var fc = 0
            val enginesSet = LinkedHashSet<String>()
            for (o in parsed) {
                if (!o.optBoolean("ok", false)) continue
                fc++
                if (docGen.isEmpty()) docGen = o.optString("docGen")
                if (o.optBoolean("hookedEarly", false)) hookedEarly = true
                if (o.has("freezeStrength")) freezeStrength = o.optInt("freezeStrength", freezeStrength)
                wasmCount += o.optInt("wasmCount", 0)
                bufCount += o.optInt("bufCount", 0)
                workerCount += o.optInt("workerCount", 0)
                val engs = o.optJSONArray("engines")
                if (engs != null) for (ei in 0 until engs.length()) {
                    val en = engs.optString(ei)
                    if (en.isNotBlank()) enginesSet.add(en)
                }
            }
            return JSONObject()
                .put("ok", fc > 0)
                .put("docGen", docGen)
                .put("hookedEarly", hookedEarly)
                .put("freezeStrength", freezeStrength)
                .put("wasmCount", wasmCount)
                .put("bufCount", bufCount)
                .put("workerCount", workerCount)
                .put("frameCount", fc)
                .put("engines", JSONArray(enginesSet.toList()))
                .toString()
        }
        if (op == "getTraceLogs") {
            val mergedTraces = JSONObject()
            for (o in parsed) {
                val tr = o.optJSONObject("traces")
                if (tr != null) tr.keys().forEach { k -> mergedTraces.put(k, tr.opt(k)) }
            }
            return JSONObject().put("ok", true).put("traces", mergedTraces).toString()
        }
        if (op == "writeBatch") {
            var wrote = 0
            val mergedVals = JSONObject()
            for (o in parsed) {
                if (!o.optBoolean("ok", false)) continue
                wrote += o.optInt("wrote", 0)
                val vals = o.optJSONObject("values")
                if (vals != null) vals.keys().forEach { k -> mergedVals.put(k, vals.optString(k)) }
            }
            return JSONObject().put("ok", true).put("wrote", wrote).put("values", mergedVals).toString()
        }
        if (op == "freezeBatch") {
            var locked = 0
            val lockedIds = JSONArray()
            val failedIds = JSONArray()
            val mergedStats = JSONObject()
            for (o in parsed) {
                locked += o.optInt("locked", 0)
                val lArr = o.optJSONArray("lockedIds")
                if (lArr != null) for (i in 0 until lArr.length()) lockedIds.put(lArr.optString(i))
                val fArr = o.optJSONArray("failedIds")
                if (fArr != null) for (i in 0 until fArr.length()) failedIds.put(fArr.optString(i))
                val fs = o.optJSONObject("freezeStats")
                if (fs != null) fs.keys().forEach { k -> mergedStats.put(k, fs.opt(k)) }
            }
            return JSONObject()
                .put("ok", locked > 0)
                .put("locked", locked)
                .put("lockedIds", lockedIds)
                .put("failedIds", failedIds)
                .put("freezeStats", mergedStats)
                .toString()
        }
        if (op == "unfreeze" || op == "unfreezeBatch" || op == "unfreezeAll" || op == "clearTrace" || op == "setFreezeStrength") {
            return JSONObject().put("ok", true).toString()
        }
        if (op == "write" || op == "hexDump" || op == "writeHex" || op == "setFreeze" ||
            op == "peekStruct" || op == "pointerScan" || op == "setTrace" || op == "reattach" || op == "findMirrors") {
            val hit = parsed.firstOrNull { it.optBoolean("ok", false) } ?: parsed[0]
            return hit.toString()
        }
        if (op == "regions") {
            var totalKb = 0L
            var workerCount = 0
            val allRegs = JSONArray()
            val enginesSet = LinkedHashSet<String>()
            var anyOk = false
            for (o in parsed) {
                if (!o.optBoolean("ok", false)) continue
                anyOk = true
                totalKb += o.optLong("totalKb", 0L)
                workerCount += o.optInt("workerCount", 0)
                val engs = o.optJSONArray("engines")
                if (engs != null) {
                    for (ei in 0 until engs.length()) {
                        val en = engs.optString(ei)
                        if (en.isNotBlank()) enginesSet.add(en)
                    }
                }
                val regs = o.optJSONArray("regions") ?: continue
                for (i in 0 until regs.length()) {
                    if (allRegs.length() < 160) allRegs.put(regs.get(i))
                }
            }
            if (!anyOk) return parsed[0].toString()
            return JSONObject()
                .put("ok", true)
                .put("count", allRegs.length())
                .put("totalKb", totalKb)
                .put("workerCount", workerCount)
                .put("frameCount", parsed.count { it.optBoolean("ok", false) })
                .put("engines", JSONArray(enginesSet.toList()))
                .put("regions", allRegs)
                .toString()
        }

        var anyOk = false
        var docGen = ""
        var hookedEarly = false
        var total = 0
        var uiHiddenCount = 0
        var canUndo = false
        var undoDepth = 0
        var wasmCount = 0
        var bufCount = 0
        var workerCount = 0
        var skippedXOrigin = 0
        var maxElapsedMs = 0L
        var anyTimedOut = false
        var anyFallback = false
        var anySnapshot = false
        var wroteTotal = 0
        var hasWrote = false
        var frameCount = 0
        var firstErr: String? = null
        val enginesSet = LinkedHashSet<String>()
        val allItems = ArrayList<JSONObject>()

        for (o in parsed) {
            if (!o.optBoolean("ok", false)) {
                if (firstErr == null) firstErr = o.optString("error").takeIf { it.isNotBlank() }
                continue
            }
            anyOk = true
            frameCount++
            if (docGen.isEmpty()) docGen = o.optString("docGen")
            if (o.optBoolean("hookedEarly", false)) hookedEarly = true
            total += o.optInt("total", 0)
            uiHiddenCount += o.optInt("uiHiddenCount", 0)
            if (o.optBoolean("canUndo", false)) canUndo = true
            undoDepth = maxOf(undoDepth, o.optInt("undoDepth", 0))
            wasmCount += o.optInt("wasmCount", 0)
            bufCount += o.optInt("bufCount", 0)
            workerCount += o.optInt("workerCount", 0)
            skippedXOrigin += o.optInt("skippedXOrigin", 0)
            maxElapsedMs = maxOf(maxElapsedMs, o.optLong("elapsedMs", 0L))
            if (o.optBoolean("timedOut", false)) anyTimedOut = true
            if (o.optBoolean("fallbackUsed", false)) anyFallback = true
            if (o.optBoolean("snapshotActive", false)) anySnapshot = true
            val engs = o.optJSONArray("engines")
            if (engs != null) {
                for (ei in 0 until engs.length()) {
                    val en = engs.optString(ei)
                    if (en.isNotBlank()) enginesSet.add(en)
                }
            }
            if (o.has("wrote")) {
                hasWrote = true
                wroteTotal += o.optInt("wrote", 0)
            }
            val arr = o.optJSONArray("items") ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                allItems.add(item)
            }
        }
        if (!anyOk) {
            return JSONObject().put("ok", false).put("error", firstErr ?: "Scan failed").toString()
        }
        allItems.sortByDescending {
            val def = when (it.optString("kind")) {
                "wasm" -> 100
                "ab", "ta" -> 92
                else -> 50
            }
            if (it.has("score")) it.optInt("score", def) else def
        }
        val mergedItems = JSONArray()
        for (item in allItems) { if (mergedItems.length() < maxReturn) mergedItems.put(item) }

        val out = JSONObject()
            .put("ok", true)
            .put("docGen", docGen)
            .put("hookedEarly", hookedEarly)
            .put("total", total)
            .put("uiHiddenCount", uiHiddenCount)
            .put("canUndo", canUndo)
            .put("undoDepth", undoDepth)
            .put("wasmCount", wasmCount)
            .put("bufCount", bufCount)
            .put("workerCount", workerCount)
            .put("skippedXOrigin", skippedXOrigin)
            .put("frameCount", frameCount)
            .put("elapsedMs", maxElapsedMs)
            .put("timedOut", anyTimedOut)
            .put("complete", !anyTimedOut)
            .put("fallbackUsed", anyFallback)
            .put("snapshotActive", anySnapshot)
            .put("engines", JSONArray(enginesSet.toList()))
            .put("items", mergedItems)
        if (hasWrote) out.put("wrote", wroteTotal)
        return out.toString()
    }

    fun buildBootstrapScript(settings: Settings): String {
        if (!settings.memoryEngineEnabled) return ""
        return buildBootstrapScriptInternal(CHANNEL_ID)
    }

    fun buildBootstrapScriptInternal(channelId: String = CHANNEL_ID): String {
        val chJson = JSONObject.quote(channelId)
        val sb = StringBuilder(170_000)
        sb.append(JS_PART_1.replace("__CH_ARG__", chJson))
        sb.append("\n").append(JS_PART_2)
        sb.append("\n").append(JS_PART_3)
        sb.append("\n").append(JS_PART_4)
        sb.append("\n").append(JS_PART_5)
        sb.append("\n").append(JS_PART_6)
        sb.append("\n").append(JS_PART_7)
        return sb.toString()
    }

    fun buildBlinkCommandScript(cmdJson: String, reqId: String = ""): String {
        val chJson = JSONObject.quote(CHANNEL_ID)
        val reqJson = JSONObject.quote(reqId)
        return """
        (function() {
            var cmd = $cmdJson;
            cmd.__mreq = $reqJson;
            var box = { ok: false, out: null };
            cmd.box = box;
            var childFrames = 0;
            try { childFrames = window.frames ? window.frames.length : 0; } catch (_) {}
            function send() {
                try { window.dispatchEvent(new CustomEvent($chJson, { detail: cmd })); } catch (_) {}
            }
            send();
            if (!box.ok) {
                return JSON.stringify({ ok: false, notHooked: true, error: "Memory scanner auto-hooking...", childFrames: childFrames });
            }
            var res = box.out || { ok: false, error: "Empty response" };
            if (typeof res === "object" && res !== null) {
                res.childFrames = childFrames;
            }
            return JSON.stringify(res);
        })();
        """.trimIndent()
    }

    fun buildBlinkResetIfInstalledScript(): String {
        val chJson = JSONObject.quote(CHANNEL_ID)
        return """
        (function() {
            var box = { ok: false, out: null };
            try { window.dispatchEvent(new CustomEvent($chJson, { detail: { op: "reset", clearFreezes: true, box: box } })); } catch (_) {}
        })();
        """.trimIndent()
    }

    private val JS_PART_1 = """
(function() {
var CH = __CH_ARG__;
function ping() {
var box = { ok: false, out: null };
try { window.dispatchEvent(new CustomEvent(CH, { detail: { op: "ping", box: box } })); } catch (_) {}
return box.ok;
}
if (ping()) return;
var IS_TOP = false;
try { IS_TOP = (window === window.top); } catch (_) { IS_TOP = false; }
var FRAME_ID = IS_TOP ? "f0" : ("f_" + Math.random().toString(36).slice(2, 8));
var DOC_GEN = "d_" + Math.random().toString(36).slice(2, 10);
var HOOKED_EARLY = false;
try { HOOKED_EARLY = (typeof document !== "undefined" && document && document.readyState === "loading"); } catch (_) {}
var freezeStrength = 1; // 0=Normal, 1=Strong, 2=Max (Titan)
var internalOpDepth = 0;
var burstChannel = null;
var burstPending = false;
var burstFrameStart = 0;
var burstFrameCount = 0;
var origDateNow = Date.now;
var origSetInterval = typeof window.setInterval === "function" ? window.setInterval.bind(window) : null;
var origClearInterval = typeof window.clearInterval === "function" ? window.clearInterval.bind(window) : null;
var origRAF = typeof window.requestAnimationFrame === "function" ? window.requestAnimationFrame.bind(window) : null;
var origCancelRAF = typeof window.cancelAnimationFrame === "function" ? window.cancelAnimationFrame.bind(window) : null;
var origToStr = Function.prototype.toString;
var sigMap = new WeakMap();
function mask(fn, orig, fallback) {
if (typeof fn !== "function") return fn;
var sig = "function " + (fallback || "") + "() { [native code] }";
if (typeof orig === "function") {
try {
var rawSig = origToStr.call(orig);
if (rawSig && rawSig.indexOf("[native code]") !== -1) sig = rawSig;
} catch (_) {}
try { Object.defineProperty(fn, "name", { value: orig.name, configurable: true }); } catch (_) {}
try { Object.defineProperty(fn, "length", { value: orig.length, configurable: true }); } catch (_) {}
}
sigMap.set(fn, sig);
return fn;
}
try {
var maskedTs = function toString() {
if (sigMap.has(this)) return sigMap.get(this);
return origToStr.call(this);
};
mask(maskedTs, origToStr, "toString");
Object.defineProperty(Function.prototype, "toString", {
value: maskedTs,
configurable: true,
writable: true
});
} catch (_) {}
function safeInstanceOf(obj, ctor) {
try { return typeof ctor === "function" && obj instanceof ctor; } catch (_) { return false; }
}
function safeIsView(obj) {
try { return typeof ArrayBuffer === "function" && ArrayBuffer.isView(obj); } catch (_) { return false; }
}
function isSharedBuf(ab) {
try { return typeof SharedArrayBuffer === "function" && ab instanceof SharedArrayBuffer; } catch (_) { return false; }
}
var wasmEntries = [];
var wasmSeen = new WeakSet();
var nextWasmId = 0;
var bufRefs = [];
var bufSeen = new WeakSet();
var workerRefs = [];
var workerSeen = new WeakSet();
var closureObjRefs = [];
var closureObjSeen = new WeakSet();
var closureMapRefs = [];
var closureMapSeen = new WeakSet();
var closureSetRefs = [];
var closureSetSeen = new WeakSet();
var MAX_TRACKED_BUFS = 5000;
var MAX_TRACKED_CLOSURE = 2000;
var MAX_SCAN_BYTES = 512 * 1024 * 1024;
var MAX_UNKNOWN_SNAP_BYTES = 128 * 1024 * 1024;
function mkRef(o) {
return typeof WeakRef === "function" ? new WeakRef(o) : { deref: function() { return o; } };
}
function trackWasmMem(mem) {
try {
if (!mem || !mem.buffer || wasmSeen.has(mem)) return;
wasmSeen.add(mem);
var id = nextWasmId++;
wasmEntries.push({ id: id, ref: mkRef(mem) });
} catch (_) {}
}
function trackRawBuf(ab) {
try {
if (!ab || typeof ab.byteLength !== "number" || ab.byteLength < 16 || ab.byteLength > MAX_SCAN_BYTES) return;
if (bufSeen.has(ab)) return;
bufSeen.add(ab);
if (bufRefs.length >= MAX_TRACKED_BUFS) {
var nextB = [];
for (var i = 0; i < bufRefs.length; i++) {
var d = bufRefs[i].deref();
if (d && d.byteLength >= 16) nextB.push(bufRefs[i]);
}
bufRefs = nextB.slice(-Math.floor(MAX_TRACKED_BUFS * 0.8));
}
bufRefs.push(mkRef(ab));
} catch (_) {}
}
function trackWorker(w) {
try {
if (!w || workerSeen.has(w)) return;
workerSeen.add(w);
if (workerRefs.length > 128) {
workerRefs = workerRefs.filter(function(r) { return !!r.deref(); }).slice(-96);
}
workerRefs.push(mkRef(w));
} catch (_) {}
}
function trackClosureObj(o) {
try {
if (internalOpDepth > 0 || !o || typeof o !== "object" || closureObjSeen.has(o)) return;
closureObjSeen.add(o);
if (closureObjRefs.length >= MAX_TRACKED_CLOSURE) {
closureObjRefs = closureObjRefs.filter(function(r) { return !!r.deref(); }).slice(-Math.floor(MAX_TRACKED_CLOSURE * 0.75));
}
closureObjRefs.push(mkRef(o));
} catch (_) {}
}
function trackClosureMap(m) {
try {
if (internalOpDepth > 0 || !m || closureMapSeen.has(m)) return;
closureMapSeen.add(m);
if (closureMapRefs.length >= MAX_TRACKED_CLOSURE) {
closureMapRefs = closureMapRefs.filter(function(r) { return !!r.deref(); }).slice(-Math.floor(MAX_TRACKED_CLOSURE * 0.75));
}
closureMapRefs.push(mkRef(m));
} catch (_) {}
}
function trackClosureSet(s) {
try {
if (internalOpDepth > 0 || !s || closureSetSeen.has(s)) return;
closureSetSeen.add(s);
if (closureSetRefs.length >= 400) {
closureSetRefs = closureSetRefs.filter(function(r) { return !!r.deref(); }).slice(-300);
}
closureSetRefs.push(mkRef(s));
} catch (_) {}
}
function inspectMessagePayload(val, depth) {
if (!val || typeof val !== "object" || depth > 3) return;
try {
if (typeof WebAssembly === "object" && safeInstanceOf(val, WebAssembly.Memory)) {
trackWasmMem(val);
return;
}
if (safeIsView(val)) {
if (val.buffer) trackRawBuf(val.buffer);
return;
}
if ((typeof ArrayBuffer === "function" && safeInstanceOf(val, ArrayBuffer)) || isSharedBuf(val)) {
trackRawBuf(val);
return;
}
if (Array.isArray(val)) {
var limA = Math.min(val.length, 24);
for (var i = 0; i < limA; i++) inspectMessagePayload(val[i], depth + 1);
return;
}
var ks = Object.keys(val);
var limK = Math.min(ks.length, 24);
for (var j = 0; j < limK; j++) inspectMessagePayload(val[ks[j]], depth + 1);
} catch (_) {}
}
function scanImportsForMem(imp) {
try {
if (!imp || typeof imp !== "object") return;
var ks = Object.keys(imp);
for (var i = 0; i < ks.length; i++) {
var v = imp[ks[i]];
if (!v) continue;
if (typeof WebAssembly === "object" && safeInstanceOf(v, WebAssembly.Memory)) {
trackWasmMem(v);
} else if (typeof v === "object") {
var ks2 = Object.keys(v);
for (var j = 0; j < ks2.length; j++) {
var v2 = v[ks2[j]];
if (v2 && typeof WebAssembly === "object" && safeInstanceOf(v2, WebAssembly.Memory)) {
trackWasmMem(v2);
}
}
}
}
} catch (_) {}
}
function scanExportsForMem(inst) {
try {
var ex = inst && inst.exports ? inst.exports : (inst && inst.instance && inst.instance.exports ? inst.instance.exports : null);
if (!ex) return;
var keys = Object.keys(ex);
for (var i = 0; i < keys.length; i++) {
var v = ex[keys[i]];
if (v && typeof WebAssembly === "object" && safeInstanceOf(v, WebAssembly.Memory)) trackWasmMem(v);
}
} catch (_) {}
}
try {
if (typeof WebAssembly === "object" && WebAssembly) {
if (typeof WebAssembly.Memory === "function") {
var OrigMem = WebAssembly.Memory;
if (OrigMem.prototype && typeof OrigMem.prototype.grow === "function") {
var origWasmGrow = OrigMem.prototype.grow;
OrigMem.prototype.grow = mask(function grow() {
var ret = origWasmGrow.apply(this, arguments);
try {
trackWasmMem(this);
if (typeof rebindWasmFreezes === "function") rebindWasmFreezes(this);
} catch (_) {}
return ret;
}, origWasmGrow, "grow");
}
var ProxMem = new Proxy(OrigMem, {
construct: function(target, args, newTarget) {
var m = Reflect.construct(target, args, newTarget);
trackWasmMem(m);
return m;
}
});
sigMap.set(ProxMem, origToStr.call(OrigMem));
WebAssembly.Memory = ProxMem;
}
if (typeof WebAssembly.instantiate === "function") {
var origInst = WebAssembly.instantiate;
WebAssembly.instantiate = mask(function instantiate() {
if (arguments.length > 1) scanImportsForMem(arguments[1]);
var p = origInst.apply(WebAssembly, arguments);
if (p && typeof p.then === "function") {
p.then(function(r) { scanExportsForMem(r); }, function() {});
}
return p;
}, origInst, "instantiate");
}
if (typeof WebAssembly.instantiateStreaming === "function") {
var origInstS = WebAssembly.instantiateStreaming;
WebAssembly.instantiateStreaming = mask(function instantiateStreaming() {
if (arguments.length > 1) scanImportsForMem(arguments[1]);
var p = origInstS.apply(WebAssembly, arguments);
if (p && typeof p.then === "function") {
p.then(function(r) { scanExportsForMem(r); }, function() {});
}
return p;
}, origInstS, "instantiateStreaming");
}
if (typeof WebAssembly.Instance === "function") {
var OrigInstance = WebAssembly.Instance;
var ProxInstance = new Proxy(OrigInstance, {
construct: function(target, args, newTarget) {
if (args && args.length > 1) scanImportsForMem(args[1]);
var inst = Reflect.construct(target, args, newTarget);
scanExportsForMem(inst);
return inst;
}
});
sigMap.set(ProxInstance, origToStr.call(OrigInstance));
WebAssembly.Instance = ProxInstance;
}
}
if (typeof ArrayBuffer === "function") {
var OrigAB = ArrayBuffer;
var ProxAB = new Proxy(OrigAB, {
construct: function(target, args, newTarget) {
var ab = Reflect.construct(target, args, newTarget);
trackRawBuf(ab);
return ab;
}
});
sigMap.set(ProxAB, origToStr.call(OrigAB));
window.ArrayBuffer = ProxAB;
}
if (typeof SharedArrayBuffer === "function") {
var OrigSAB = SharedArrayBuffer;
var ProxSAB = new Proxy(OrigSAB, {
construct: function(target, args, newTarget) {
var sab = Reflect.construct(target, args, newTarget);
trackRawBuf(sab);
return sab;
}
});
sigMap.set(ProxSAB, origToStr.call(OrigSAB));
window.SharedArrayBuffer = ProxSAB;
}
["Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array", "DataView"].forEach(function(name) {
try {
var OrigView = window[name];
if (typeof OrigView !== "function") return;
var ProxView = new Proxy(OrigView, {
construct: function(target, args, newTarget) {
var view = Reflect.construct(target, args, newTarget);
if (view && view.buffer) trackRawBuf(view.buffer);
return view;
}
});
sigMap.set(ProxView, origToStr.call(OrigView));
window[name] = ProxView;
} catch (_) {}
});
if (typeof Map === "function") {
var OrigMap = Map;
var ProxMap = new Proxy(OrigMap, {
construct: function(target, args, newTarget) {
var mp = Reflect.construct(target, args, newTarget);
trackClosureMap(mp);
return mp;
}
});
sigMap.set(ProxMap, origToStr.call(OrigMap));
window.Map = ProxMap;
}
if (typeof Set === "function") {
var OrigSet = Set;
var ProxSet = new Proxy(OrigSet, {
construct: function(target, args, newTarget) {
var st = Reflect.construct(target, args, newTarget);
trackClosureSet(st);
return st;
}
});
sigMap.set(ProxSet, origToStr.call(OrigSet));
window.Set = ProxSet;
}
if (typeof JSON === "object" && typeof JSON.parse === "function") {
var origJsonParse = JSON.parse;
JSON.parse = mask(function parse() {
var res = origJsonParse.apply(JSON, arguments);
if (res && typeof res === "object") trackClosureObj(res);
return res;
}, origJsonParse, "parse");
}
try {

if (typeof ArrayBuffer === "function" && ArrayBuffer.prototype && typeof ArrayBuffer.prototype.slice === "function") {
var origAbSlice = ArrayBuffer.prototype.slice;
ArrayBuffer.prototype.slice = mask(function slice() {
var outAb = origAbSlice.apply(this, arguments);
if (outAb && outAb.byteLength >= 16) trackRawBuf(outAb);
return outAb;
}, origAbSlice, "slice");
}
if (typeof Response === "function" && Response.prototype) {
if (typeof Response.prototype.arrayBuffer === "function") {
var origRespAb = Response.prototype.arrayBuffer;
Response.prototype.arrayBuffer = mask(function arrayBuffer() {
var p = origRespAb.apply(this, arguments);
if (p && typeof p.then === "function") {
p.then(function(ab) { if (ab && ab.byteLength >= 16) trackRawBuf(ab); }, function() {});
}
return p;
}, origRespAb, "arrayBuffer");
}
if (typeof Response.prototype.json === "function") {
var origRespJson = Response.prototype.json;
Response.prototype.json = mask(function json() {
var p = origRespJson.apply(this, arguments);
if (p && typeof p.then === "function") {
p.then(function(o) { if (o && typeof o === "object") trackClosureObj(o); }, function() {});
}
return p;
}, origRespJson, "json");
}
}
} catch (_) {}
if (typeof Worker === "function") {
var OrigWorker = Worker;
var ProxWorker = new Proxy(OrigWorker, {
construct: function(target, args, newTarget) {
var w = Reflect.construct(target, args, newTarget);
trackWorker(w);
try {
w.addEventListener("message", function(ev) {
if (ev && ev.data) inspectMessagePayload(ev.data, 0);
}, true);
} catch (_) {}
return w;
}
});
sigMap.set(ProxWorker, origToStr.call(OrigWorker));
window.Worker = ProxWorker;
if (OrigWorker.prototype && typeof OrigWorker.prototype.postMessage === "function") {
var origWPost = OrigWorker.prototype.postMessage;
OrigWorker.prototype.postMessage = mask(function postMessage(msg, transfer) {
inspectMessagePayload(msg, 0);
if (Array.isArray(transfer)) inspectMessagePayload(transfer, 0);
return origWPost.apply(this, arguments);
}, origWPost, "postMessage");
}
}
if (typeof MessagePort === "function" && MessagePort.prototype && typeof MessagePort.prototype.postMessage === "function") {
var origMpPost = MessagePort.prototype.postMessage;
MessagePort.prototype.postMessage = mask(function postMessage(msg, transfer) {
inspectMessagePayload(msg, 0);
if (Array.isArray(transfer)) inspectMessagePayload(transfer, 0);
return origMpPost.apply(this, arguments);
}, origMpPost, "postMessage");
}
} catch (_) {}
function liveWasmEntries() {
var live = [];
var kept = [];
for (var i = 0; i < wasmEntries.length; i++) {
var e = wasmEntries[i];
var m = e.ref.deref();
if (m && m.buffer && m.buffer.byteLength > 0) {
kept.push(e);
live.push({ id: e.id, mem: m, ref: e.ref });
}
}
wasmEntries = kept;
return live;
}
function liveRawBuffers(excludeSet) {
var live = [];
var kept = [];
for (var i = 0; i < bufRefs.length; i++) {
var ab = bufRefs[i].deref();
if (ab && ab.byteLength >= 16) {
kept.push(bufRefs[i]);
if (!excludeSet || !excludeSet.has(ab)) live.push(ab);
}
}
bufRefs = kept;
return live;
}
function liveWorkerCount() {
var kept = [];
for (var i = 0; i < workerRefs.length; i++) {
if (workerRefs[i].deref()) kept.push(workerRefs[i]);
}
workerRefs = kept;
return kept.length;
}
function detectEngines() {
var eng = [];
function add(name) { if (eng.indexOf(name) === -1) eng.push(name); }
try {
if (window.unityInstance || window.gameInstance || typeof window.createUnityInstance === "function" ||
(window.Module && (window.Module.unityFramework || window.Module.SendMessage || window.Module.webglContextAttributes))) {
add("Unity");
}
try {
if (window.document && window.document.querySelector && window.document.querySelector("#unity-canvas,#unityContainer,[id*='unity']")) add("Unity");
} catch (_) {}
if (window.Godot || window.godot || (typeof window.Engine === "function" && window.Engine.prototype && typeof window.Engine.prototype.startGame === "function")) {
add("Godot");
}
if (window.UE4 || (window.Module && window.Module.UE4)) add("Unreal");
if (window.Phaser) add("Phaser");
if (window.PIXI) add("PixiJS");
if (window.pc && (window.pc.Application || window.pc.Entity)) add("PlayCanvas");
if (window.THREE) add("Three.js");
if (window.BABYLON) add("Babylon.js");
if (window.cc && (window.cc.director || window.cc.game)) add("Cocos");
if (window.cr_getC2Runtime || window.c3_runtimeInterface || window.C3 || window._c3Runtime) add("Construct");
if (window.EngineLoader && window.Module) add("Defold");
if (window.g_pBuiltIn || window.g_RunRoom) add("GameMaker");
if (window.${'$'}gameParty || window.${'$'}gameActors || window.${'$'}gameVariables) add("RPGMaker");
if (window.gdjs) add("GDevelop");
if (window.kaboom || window.kaplay) add("Kaboom");
if (liveWasmEntries().length > 0 && eng.length === 0) add("WASM");
if (eng.length === 0) {
try {
if (window.document && window.document.querySelector && window.document.querySelector("canvas")) add("Canvas");
} catch (_) {}
}
} catch (_) {}
return eng;
}
var hits = [];
var undoStack = [];
var MAX_UNDO_DEPTH = 8;
var unknownSnaps = null;
var lastType = "auto";
var taRegistry = [];
var abRegistry = [];
var watchRefs = new Map();
var skippedXOriginCount = 0;
var frozenMap = new Map();
var traceMap = new Map();
var MAX_TRACE_EVENTS = 40;
var selfWriteDepth = 0;
var mapWrapState = new WeakMap();
var storageProtoHook = null;
var freezeIntervalId = null;
var freezeRafId = null;
var traceRafId = null;
var lastEnforceTickMs = 0;
var SKIP_KEYS = new Set([
"window", "self", "globalThis", "document", "location", "navigator", "history",
"screen", "performance", "crypto", "caches", "indexedDB", "localStorage", "sessionStorage",
"chrome", "webkit", "frames", "top", "parent", "opener", "styleMedia", "visualViewport",
"customElements", "speechSynthesis", "FableHybridBridge", "external", "console",
"innerWidth", "innerHeight", "outerWidth", "outerHeight", "screenX", "screenY",
"screenLeft", "screenTop", "scrollX", "scrollY", "pageXOffset", "pageYOffset",
"devicePixelRatio", "length", "byteLength", "byteOffset", "BYTES_PER_ELEMENT",
"isSecureContext", "crossOriginIsolated", "origin", "name", "status", "closed",
"toolbar", "menubar", "personalbar", "scrollbars", "statusbar", "locationbar",
"clientInformation", "scheduler", "navigation", "cookieStore", "trustedTypes",
"credentialless", "launchQueue", "documentPictureInPicture", "CSS", "Intl",
"Reflect", "Proxy", "JSON", "Math", "Atomics", "WebAssembly", "_sentryDebugIds",
"__SENTRY__", "__sentry__", "Sentry", "dataLayer", "google_tag_manager", "gtag", "ga"
]);
var SKIP_SUBTREE_KEYS = new Set([
"geometry", "material", "materials", "textures", "_textures", "_materials",
"_vertexBuffers", "_indexBuffer", "attributes", "index", "uv", "normals",
"morphAttributes", "boundingBox", "boundingSphere", "matrixWorld", "matrix",
"projectionMatrix", "modelViewMatrix", "normalMatrix", "_gl", "gl",
"programs", "shaders", "style", "classList", "dataset", "children",
"childNodes", "firstChild", "lastChild", "nextSibling", "previousSibling",
"parentNode", "parentElement", "ownerDocument", "shadowRoot"
]);
var INTERNAL_KEY_RE = /^(__fable|_mev_|_ts_|Fable|__TS_|__MEM_|__reactInternal|__webpack)/;
var SENSITIVE_KEY_RE = /(password|passwd|secret|token|jwt|auth|session_?id|csrf|cookie|api_?key|credit|card)/i;
function cleanNumStr(raw) {
var s = String(raw || "").trim();
var stripped = s.replace(/[,\s_]/g, "");
if (stripped && Number.isFinite(Number(stripped))) return stripped;
return s;
}
function parseAob(txt) {
var parts = String(txt || "").trim().split(/[\s,\-]+/).filter(Boolean);
var bytes = [], maskArr = [];
for (var i = 0; i < parts.length; i++) {
var p = parts[i];
if (p === "?" || p === "??" || p === "*" || p === "**") {
bytes.push(0); maskArr.push(0);
} else {
var v = parseInt(p, 16);
if (!Number.isNaN(v) && v >= 0 && v <= 255) { bytes.push(v); maskArr.push(1); }
}
}
return { bytes: bytes, mask: maskArr };
}
function numEps(dt) {
if (dt === "f32") return 0.01;
if (dt === "f64" || dt === "auto") return 0.001;
return 0.0;
}
function matchNum(cur, prev, hasPrev, dt, sc, v1, v2) {
if (typeof cur !== "number" || !Number.isFinite(cur)) return false;
var eps = numEps(dt);
switch (sc) {
case "exact":        return Math.abs(cur - v1) <= eps;
case "bigger":       return cur > v1;
case "smaller":      return cur < v1;
case "between":      return cur >= v1 && cur <= v2;
case "unknown":      return true;
case "increased":    return hasPrev && cur > prev + eps;
case "decreased":    return hasPrev && cur < prev - eps;
case "increased_by": return hasPrev && Math.abs((cur - prev) - v1) <= (eps > 0 ? eps : 1e-6);
case "decreased_by": return hasPrev && Math.abs((prev - cur) - v1) <= (eps > 0 ? eps : 1e-6);
case "changed":      return hasPrev && Math.abs(cur - prev) > eps;
case "unchanged":    return hasPrev && Math.abs(cur - prev) <= eps;
default:             return false;
}
}
var scratchAB = new ArrayBuffer(8);
var scratchDV = new DataView(scratchAB);
var scratchI32 = new Int32Array(scratchAB);
var scratchF32 = new Float32Array(scratchAB);
var scratchF64 = new Float64Array(scratchAB);
function f32ToI32Bits(v) {
scratchF32[0] = v;
return scratchI32[0];
}
function f64ToI32Pair(v) {
scratchF64[0] = v;
return [scratchI32[0], scratchI32[1]];
}
function i32PairToF64(lo, hi) {
scratchI32[0] = lo;
scratchI32[1] = hi;
return scratchF64[0];
}
function i32PairToI64Num(lo, hi) {
scratchI32[0] = lo;
scratchI32[1] = hi;
return Number(scratchDV.getBigInt64(0, true));
}
function readViewVal(dv, off, dt) {
try {
if (dt === "i8")  return dv.getInt8(off);
if (dt === "i16") return dv.getInt16(off, true);
if (dt === "i32") return dv.getInt32(off, true);
if (dt === "u32") return dv.getUint32(off, true);
if (dt === "i64") return Number(dv.getBigInt64(off, true));
if (dt === "f32") return dv.getFloat32(off, true);
if (dt === "f64") return dv.getFloat64(off, true);
} catch (_) {}
return NaN;
}
function writeViewVal(dv, off, dt, valStr, isShared) {
try {
if (dt === "str") {
var enc = new TextEncoder().encode(String(valStr));
var u8 = new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength);
for (var i = 0; i < enc.length && (off + i) < u8.length; i++) u8[off + i] = enc[i];
return true;
}
if (dt === "aob") {
var pat = parseAob(valStr);
var u8b = new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength);
for (var j = 0; j < pat.bytes.length && (off + j) < u8b.length; j++) {
if (pat.mask[j]) u8b[off + j] = pat.bytes[j];
}
return true;
}
var num = Number(cleanNumStr(valStr));
if (!Number.isFinite(num)) return false;
var absOff = dv.byteOffset + off;
if (isShared && typeof Atomics === "object") {
try {
if (dt === "i8") { Atomics.store(new Int8Array(dv.buffer, absOff, 1), 0, num | 0); return true; }
if (dt === "i16" && (absOff & 1) === 0) { Atomics.store(new Int16Array(dv.buffer, absOff, 1), 0, num | 0); return true; }
if (dt === "i32" && (absOff & 3) === 0) { Atomics.store(new Int32Array(dv.buffer, absOff, 1), 0, num | 0); return true; }
    """.trimStart('\n').trimEnd('\n')

    private val JS_PART_2 = """
if (dt === "u32" && (absOff & 3) === 0) { Atomics.store(new Uint32Array(dv.buffer, absOff, 1), 0, (num < 0 ? 0 : num) >>> 0); return true; }
if (dt === "f32" && (absOff & 3) === 0) { Atomics.store(new Int32Array(dv.buffer, absOff, 1), 0, f32ToI32Bits(num)); return true; }
} catch (_) {}
}
if (dt === "i8")  { dv.setInt8(off, num | 0); return true; }
if (dt === "i16") { dv.setInt16(off, num | 0, true); return true; }
if (dt === "i32") { dv.setInt32(off, num | 0, true); return true; }
if (dt === "u32") { dv.setUint32(off, (num < 0 ? 0 : num) >>> 0, true); return true; }
if (dt === "i64") { dv.setBigInt64(off, BigInt(Math.round(num)), true); return true; }
if (dt === "f32") { dv.setFloat32(off, num, true); return true; }
if (dt === "f64") { dv.setFloat64(off, num, true); return true; }
} catch (_) {}
return false;
}
function formatVal(v, dt) {
if (v === undefined || v === null) return String(v);
if (typeof v === "number") {
if (!Number.isFinite(v)) return String(v);
if (dt === "f32" || dt === "f64" || !Number.isInteger(v)) return Number(v.toPrecision(6)).toString();
return String(v);
}
return String(v).slice(0, 64);
}
function parseRangePair(valStr) {
var s = String(valStr || "").trim();
var parts = s.indexOf("..") !== -1 ? s.split("..") : (s.indexOf(",") !== -1 ? s.split(",") : null);
if (!parts || parts.length < 2) return null;
var a = Number(cleanNumStr(parts[0]));
var b = Number(cleanNumStr(parts[1]));
if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
return a <= b ? [a, b] : [b, a];
}
function parseGroupQuery(raw) {
var s = String(raw || "").trim();
var ordered = false;
var rangeBytes = 64;
var body = s;
var dbl = s.lastIndexOf("::");
if (dbl !== -1) {
ordered = false;
body = s.slice(0, dbl);
var r1 = Number(cleanNumStr(s.slice(dbl + 2)));
if (Number.isFinite(r1)) rangeBytes = Math.max(4, Math.min(4096, Math.round(r1)));
} else {
var sgl = s.lastIndexOf(":");
if (sgl !== -1) {
ordered = true;
body = s.slice(0, sgl);
var r2 = Number(cleanNumStr(s.slice(sgl + 1)));
if (Number.isFinite(r2)) rangeBytes = Math.max(4, Math.min(4096, Math.round(r2)));
}
}
var rawTerms = body.split(";").map(function(x) { return x.trim(); }).filter(Boolean);
if (rawTerms.length < 2 || rawTerms.length > 12) return null;
var terms = [];
for (var i = 0; i < rawTerms.length; i++) {
var tok = rawTerms[i];
var dt = "auto";
var isHex = /^[-+]?0[xX]/.test(tok);
if (!isHex && tok.length >= 2) {
var last = tok.charAt(tok.length - 1).toLowerCase();
if (last === "b") { dt = "i8"; tok = tok.slice(0, -1); }
else if (last === "s") { dt = "i16"; tok = tok.slice(0, -1); }
else if (last === "i") { dt = "i32"; tok = tok.slice(0, -1); }
else if (last === "u") { dt = "u32"; tok = tok.slice(0, -1); }
else if (last === "q") { dt = "i64"; tok = tok.slice(0, -1); }
else if (last === "f") { dt = "f32"; tok = tok.slice(0, -1); }
else if (last === "d") { dt = "f64"; tok = tok.slice(0, -1); }
}
var cleaned = cleanNumStr(tok);
var num = Number(cleaned);
if (!cleaned || !Number.isFinite(num)) return null;
terms.push({ dt: dt, val: num, isInt: Number.isInteger(num) });
}
return { terms: terms, rangeBytes: rangeBytes, ordered: ordered };
}
function getBufferForHit(h) {
if (h.kind === "wasm") {
var m = h.memRef ? h.memRef.deref() : null;
if (m && m.buffer && h.off < m.buffer.byteLength) return { buf: m.buffer, byteOff: 0, byteLen: m.buffer.byteLength, shared: isSharedBuf(m.buffer) };
return null;
}
if (h.kind === "ab") {
var ab = h.abRef ? h.abRef.deref() : null;
if (ab && ab.byteLength > 0 && h.off < ab.byteLength) return { buf: ab, byteOff: 0, byteLen: ab.byteLength, shared: isSharedBuf(ab) };
return null;
}
if (h.kind === "ta") {
var ref = taRegistry[h.bufIdx];
var ta = ref ? ref.deref() : null;
if (ta && ta.buffer && h.off < ta.byteLength) return { buf: ta.buffer, byteOff: ta.byteOffset, byteLen: ta.byteLength, shared: isSharedBuf(ta.buffer) };
return null;
}
return null;
}
function getJsonPathVal(root, pathParts) {
var cur = root;
for (var i = 0; i < pathParts.length; i++) {
if (cur === null || cur === undefined || typeof cur !== "object") return { ok: false };
cur = cur[pathParts[i]];
}
return { ok: true, val: cur };
}
function setJsonPathVal(root, pathParts, newVal) {
var cur = root;
for (var i = 0; i < pathParts.length - 1; i++) {
if (cur === null || cur === undefined || typeof cur !== "object") return false;
cur = cur[pathParts[i]];
}
if (cur === null || cur === undefined || typeof cur !== "object") return false;
cur[pathParts[pathParts.length - 1]] = newVal;
return true;
}
function readLiveXorKey(h, bi) {
if (!h || !h.xorMode) return { ok: true, key: 0 };
if (h.xorMode === 1) return { ok: true, key: h.xorKey >>> 0 };
if (h.xorMode === 2) {
var b = bi || getBufferForHit(h);
if (!b || h.xorKeyOff < 0 || h.xorKeyOff + 4 > b.byteLen) return { ok: false, key: 0 };
var dv = new DataView(b.buf, b.byteOff, b.byteLen);
return { ok: true, key: dv.getUint32(h.xorKeyOff, true) >>> 0 };
}
if (h.xorMode === 3) {
var o = h.objRef ? h.objRef.deref() : null;
if (!o) return { ok: false, key: 0 };
var kv = o[h.xorKeyProp];
if (typeof kv !== "number" || !Number.isFinite(kv)) return { ok: false, key: 0 };
return { ok: true, key: kv >>> 0 };
}
return { ok: true, key: 0 };
}
function readHitCurrent(h) {
try {
if (h.kind === "js") {
var obj = h.objRef ? h.objRef.deref() : null;
if (!obj) {
var reb = resolveJsPathFromWindow(h.id);
if (reb) { obj = reb.obj; h.objRef = mkRef(obj); h.key = reb.key; }
else return { ok: false, val: "<gc>" };
}
var rawJs = obj[h.key];
if (typeof rawJs === "bigint") {
var bn = Number(rawJs);
return { ok: true, val: Number.isSafeInteger(bn) ? bn : rawJs.toString() };
}
if (h.xorMode === 3 && typeof rawJs === "number") {
var lkJ = readLiveXorKey(h, null);
if (!lkJ.ok) return { ok: false, val: "<key err>" };
return { ok: true, val: ((rawJs | 0) ^ (lkJ.key | 0)) | 0 };
}
return { ok: true, val: rawJs };
}
if (h.kind === "map") {
var mp = h.objRef.deref();
if (!mp || typeof mp.get !== "function") return { ok: false, val: "<gc>" };
if (!mp.has(h.key)) return { ok: false, val: "<removed>" };
return { ok: true, val: mp.get(h.key) };
}
if (h.kind === "ls" || h.kind === "ss") {
var st = h.kind === "ls" ? window.localStorage : window.sessionStorage;
var raw = st.getItem(h.key);
if (raw === null) return { ok: false, val: "<removed>" };
var num = Number(cleanNumStr(raw));
return { ok: true, val: (h.dt !== "str" && raw.trim() !== "" && Number.isFinite(num)) ? num : raw };
}
if (h.kind === "lsjson" || h.kind === "ssjson") {
var stJ = h.kind === "lsjson" ? window.localStorage : window.sessionStorage;
var rawJ = stJ.getItem(h.key);
if (rawJ === null) return { ok: false, val: "<removed>" };
var parsedJ = JSON.parse(rawJ);
var got = getJsonPathVal(parsedJ, h.jpath);
if (!got.ok) return { ok: false, val: "<removed>" };
return { ok: true, val: got.val };
}
if (h.kind === "wasm" || h.kind === "ta" || h.kind === "ab") {
var bi = getBufferForHit(h);
if (!bi) return { ok: false, val: "<detached>" };
if (h.dt === "str") {
var u8 = new Uint8Array(bi.buf, bi.byteOff + h.off, Math.min(h.len || 16, bi.byteLen - h.off));
return { ok: true, val: new TextDecoder().decode(u8).replace(/\0.*/, "") };
}
if (h.dt === "aob") {
var u8a = new Uint8Array(bi.buf, bi.byteOff + h.off, Math.min(h.len || 8, bi.byteLen - h.off));
var parts = [];
for (var i = 0; i < u8a.length; i++) parts.push(u8a[i].toString(16).toUpperCase().padStart(2, "0"));
return { ok: true, val: parts.join(" ") };
}
var dv = new DataView(bi.buf, bi.byteOff, bi.byteLen);
if (h.xorMode) {
var lk = readLiveXorKey(h, bi);
if (!lk.ok) return { ok: false, val: "<key err>" };
var rawEnc = dv.getUint32(h.off, true);
return { ok: true, val: ((rawEnc ^ lk.key) | 0) };
}
var rawV = readViewVal(dv, h.off, h.dt);
return { ok: true, val: h.scale ? (rawV / h.scale) : rawV };
}
} catch (_) {}
return { ok: false, val: "<err>" };
}
function parseTargetVal(h, valStr) {
var cs = cleanNumStr(valStr);
if (h.dt === "str" || h.dt === "aob") return String(valStr);
var num = Number(cs);
if (cs !== "" && Number.isFinite(num)) return num;
if (valStr === "true") return true;
if (valStr === "false") return false;
return String(valStr);
}
function findPropertyDescriptorChain(obj, key) {
var cur = obj;
var depth = 0;
while (cur && depth < 10) {
try {
var d = Object.getOwnPropertyDescriptor(cur, key);
if (d) return { desc: d, owner: cur, isOwn: cur === obj };
} catch (_) { return null; }
try { cur = Object.getPrototypeOf(cur); } catch (_) { return null; }
depth++;
}
return null;
}
function ruleEval(curVal, fe) {
var rule = fe.rule || "eq";
if (rule === "step") return { violated: false, nextVal: curVal };
if (typeof curVal === "number" && Number.isFinite(curVal)) {
if (rule === "range") {
if (typeof fe.rangeLo === "number" && curVal < fe.rangeLo) return { violated: true, nextVal: fe.rangeLo };
if (typeof fe.rangeHi === "number" && curVal > fe.rangeHi) return { violated: true, nextVal: fe.rangeHi };
return { violated: false, nextVal: curVal };
}
if (typeof fe.parsedVal === "number" && Number.isFinite(fe.parsedVal)) {
if (rule === "min") return curVal < fe.parsedVal ? { violated: true, nextVal: fe.parsedVal } : { violated: false, nextVal: curVal };
if (rule === "max") return curVal > fe.parsedVal ? { violated: true, nextVal: fe.parsedVal } : { violated: false, nextVal: curVal };
return curVal !== fe.parsedVal ? { violated: true, nextVal: fe.parsedVal } : { violated: false, nextVal: curVal };
}
}
if (rule === "eq" && String(curVal) !== String(fe.parsedVal)) {
return { violated: true, nextVal: fe.parsedVal };
}
return { violated: false, nextVal: curVal };
}
function captureTargetBytes(h) {
if (h.kind !== "wasm" && h.kind !== "ta" && h.kind !== "ab") return null;
if (h.xorMode) return null;
var bi = getBufferForHit(h);
if (!bi) return null;
var sz = h.dt === "i8" ? 1 : (h.dt === "i16" ? 2 : ((h.dt === "i32" || h.dt === "u32" || h.dt === "f32") ? 4 : ((h.dt === "i64" || h.dt === "f64") ? 8 : Math.min(h.len || 16, bi.byteLen - h.off))));
if (sz <= 0 || h.off + sz > bi.byteLen) return null;
return new Uint8Array(bi.buf, bi.byteOff + h.off, sz).slice();
}
function binaryBytesDiffer(h, targetBytes) {
if (!targetBytes || !targetBytes.length) return false;
var bi = getBufferForHit(h);
if (!bi || h.off + targetBytes.length > bi.byteLen) return null;
var live = new Uint8Array(bi.buf, bi.byteOff + h.off, targetBytes.length);
for (var i = 0; i < targetBytes.length; i++) {
if (live[i] !== targetBytes[i]) return true;
}
return false;
}
function extractCallerFrame() {
try {
var st = String(new Error().stack || "");
var rows = st.split(/\r?\n/);
var skippedSetter = false;
for (var i = 1; i < rows.length && i < 14; i++) {
var line = rows[i].trim();
if (!line) continue;
if (line.indexOf("extractCallerFrame") !== -1 || line.indexOf("recordTraceEvent") !== -1 ||
line.indexOf("installJsPropertyHook") !== -1 || line.indexOf("enforceBinaryFreezes") !== -1) continue;
if (!skippedSetter && (line.indexOf("set [as ") !== -1 || line.indexOf("Object.set") !== -1 || /^set@/.test(line))) {
skippedSetter = true;
continue;
}
return line.slice(0, 96);
}
} catch (_) {}
return "script";
}
function recordTraceEvent(localId, oldVal, newVal, callerStr) {
if (selfWriteDepth > 0) return;
var te = traceMap.get(localId);
if (!te) return;
var deltaStr = "";
if (typeof oldVal === "number" && typeof newVal === "number" && Number.isFinite(oldVal) && Number.isFinite(newVal)) {
var d = newVal - oldVal;
deltaStr = (d >= 0 ? "+" : "") + formatVal(d, te.rec.dt);
}
te.events.push({
ts: origDateNow(),
oldVal: formatVal(oldVal, te.rec.dt),
newVal: formatVal(newVal, te.rec.dt),
delta: deltaStr,
caller: callerStr || "write"
});
if (te.events.length > MAX_TRACE_EVENTS) te.events.shift();
te.writeCount++;
}
function configureFreezeRuleValues(fe, h, valStr, ruleStr) {
var rule = (ruleStr === "min" || ruleStr === "max" || ruleStr === "range" || ruleStr === "step") ? ruleStr : "eq";
fe.rule = rule;
fe.valStr = String(valStr);
fe.rangeLo = null;
fe.rangeHi = null;
fe.stepDelta = 0;
if (rule === "range") {
var rp = parseRangePair(valStr);
if (rp) {
fe.rangeLo = rp[0];
fe.rangeHi = rp[1];
fe.parsedVal = rp[0];
} else {
var singleN = parseTargetVal(h, valStr);
fe.rangeLo = typeof singleN === "number" ? singleN : 0;
fe.rangeHi = fe.rangeLo;
fe.parsedVal = fe.rangeLo;
}
} else if (rule === "step") {
var dn = Number(cleanNumStr(valStr));
fe.stepDelta = Number.isFinite(dn) ? dn : 1;
fe.parsedVal = fe.stepDelta;
fe.lastStepMs = origDateNow();
} else {
fe.parsedVal = parseTargetVal(h, valStr);
}
}
function writeHitValue(h, valStr, bypassFreeze) {
selfWriteDepth++;
try {
var fe = !bypassFreeze ? frozenMap.get(h.id) : null;
if (fe) {
configureFreezeRuleValues(fe, h, valStr, fe.rule);
if (fe.trapInstalled && h.kind === "js") {
if (fe.rule === "eq") {
fe.backingVal = fe.parsedVal;
} else if (fe.rule === "min" || fe.rule === "max" || fe.rule === "range") {
var curB = fe.backingVal;
var evB = ruleEval(curB, fe);
fe.backingVal = evB.violated ? evB.nextVal : (fe.rule === "min" || fe.rule === "max" ? fe.parsedVal : curB);
}
if (fe.origSet && fe.origObj) {
var rawToSet = fe.backingVal;
if (h.xorMode === 3 && typeof rawToSet === "number") {
var lkS = readLiveXorKey(h, null);
if (lkS.ok) rawToSet = ((rawToSet | 0) ^ (lkS.key | 0)) | 0;
}
try { fe.origSet.call(fe.origObj, rawToSet); } catch (_) {}
}
return true;
}
}
if (h.kind === "js") {
var obj = h.objRef ? h.objRef.deref() : null;
if (!obj) {
var rebW = resolveJsPathFromWindow(h.id);
if (rebW) { obj = rebW.obj; h.objRef = mkRef(obj); h.key = rebW.key; }
else return false;
}
var cur = undefined;
try { cur = obj[h.key]; } catch (_) {}
var cs = cleanNumStr(valStr);
var nextJsVal = (typeof cur === "bigint" && typeof BigInt === "function" && !Number.isNaN(Number(cs)) && cs !== "")
? BigInt(Math.trunc(Number(cs)))
: ((typeof cur === "number" || (h.dt !== "str" && !Number.isNaN(Number(cs)) && cs !== ""))
? Number(cs)
: (typeof cur === "boolean" ? (valStr === "true" || valStr === "1") : String(valStr)));
var rawJsWrite = nextJsVal;
if (h.xorMode === 3 && typeof nextJsVal === "number") {
var lkJw = readLiveXorKey(h, null);
if (!lkJw.ok) return false;
rawJsWrite = ((nextJsVal | 0) ^ (lkJw.key | 0)) | 0;
}
var feJs = frozenMap.get(h.id);
if (feJs && feJs.trapInstalled) {
feJs.backingVal = nextJsVal;
if (feJs.origSet) feJs.origSet.call(obj, rawJsWrite);
return true;
}
var teJs = traceMap.get(h.id);
if (teJs && teJs.trapInstalled) {
teJs.backingVal = nextJsVal;
if (teJs.origSet) teJs.origSet.call(obj, rawJsWrite);
return true;
}
obj[h.key] = rawJsWrite;
return true;
}
if (h.kind === "map") {
var mp = h.objRef.deref();
if (!mp || typeof mp.set !== "function") return false;
var curM = mp.get(h.key);
var csM = cleanNumStr(valStr);
var vM = (typeof curM === "number" || (h.dt !== "str" && !Number.isNaN(Number(csM)) && csM !== "")) ? Number(csM) : String(valStr);
var mw = mapWrapState.get(mp);
if (mw && mw.origSet) mw.origSet.call(mp, h.key, vM);
else mp.set(h.key, vM);
return true;
}
if (h.kind === "ls" || h.kind === "ss") {
var st = h.kind === "ls" ? window.localStorage : window.sessionStorage;
if (storageProtoHook && storageProtoHook.origSetItem) storageProtoHook.origSetItem.call(st, h.key, String(valStr));
else st.setItem(h.key, String(valStr));
return true;
}
if (h.kind === "lsjson" || h.kind === "ssjson") {
var tagJ = h.kind === "lsjson" ? "ls" : "ss";
var stJ = tagJ === "ls" ? window.localStorage : window.sessionStorage;
var rawJ = stJ.getItem(h.key);
if (rawJ === null) return false;
var parsedJ = JSON.parse(rawJ);
var nextV = parseTargetVal(h, valStr);
if (!setJsonPathVal(parsedJ, h.jpath, nextV)) return false;
var outStr = JSON.stringify(parsedJ);
if (storageProtoHook && storageProtoHook.origSetItem) storageProtoHook.origSetItem.call(stJ, h.key, outStr);
else stJ.setItem(h.key, outStr);
return true;
}
if (h.kind === "wasm" || h.kind === "ta" || h.kind === "ab") {
var bi = getBufferForHit(h);
if (!bi) return false;
var dv = new DataView(bi.buf, bi.byteOff, bi.byteLen);
var wStr = valStr;
if (h.xorMode) {
var lkW = readLiveXorKey(h, bi);
if (!lkW.ok) return false;
var plainN = Number(cleanNumStr(valStr));
if (!Number.isFinite(plainN)) return false;
var encVal = (((plainN | 0) ^ (lkW.key | 0)) >>> 0);
var okXor = writeViewVal(dv, h.off, "u32", String(encVal), bi.shared);
return okXor;
}
if (h.scale) {
var sn = Number(cleanNumStr(valStr));
if (Number.isFinite(sn)) wStr = String(Math.round(sn * h.scale));
}
var okBin = writeViewVal(dv, h.off, h.dt, wStr, bi.shared);
if (okBin && fe && fe.rule === "eq") {
fe.targetBytes = captureTargetBytes(h);
var rdB = readHitCurrent(h);
if (rdB.ok) fe.parsedVal = rdB.val;
}
return okBin;
}
} catch (_) {
} finally {
selfWriteDepth--;
}
return false;
}
function ensureStorageHook(tag) {
var st = tag === "ls" ? window.localStorage : window.sessionStorage;
if (!st) return null;
if (!storageProtoHook) {
var proto = null;
try {
proto = (typeof Storage === "function" && Storage.prototype && typeof Storage.prototype.setItem === "function")
? Storage.prototype
: Object.getPrototypeOf(st);
} catch (_) {}
var targetObj = (proto && typeof proto.setItem === "function") ? proto : st;
var origSetItem = targetObj.setItem;
var origRemoveItem = typeof targetObj.removeItem === "function" ? targetObj.removeItem : null;
var origClear = typeof targetObj.clear === "function" ? targetObj.clear : null;
if (typeof origSetItem !== "function") return null;
var state = {
targetObj: targetObj,
origSetItem: origSetItem,
origRemoveItem: origRemoveItem,
origClear: origClear,
lsKeys: new Map(),
ssKeys: new Map()
};
var resolveActive = function(self) {
var isLs = false, isSs = false;
try { isLs = (self === window.localStorage); } catch (_) {}
try { if (!isLs) isSs = (self === window.sessionStorage); } catch (_) {}
if (isLs) return { tag: "ls", keyMap: state.lsKeys };
if (isSs) return { tag: "ss", keyMap: state.ssKeys };
return null;
};
var wrappedSetItem = mask(function setItem(k, v) {
var ctx = resolveActive(this);
var set = ctx ? ctx.keyMap.get(String(k)) : null;
if (set && set.size > 0) {
var directOverride = null;
var jsonMod = null;
set.forEach(function(fe) {
if (fe.rec.kind === ctx.tag) {
var numV = Number(cleanNumStr(v));
var cmpV = (typeof fe.parsedVal === "number" && Number.isFinite(numV)) ? numV : String(v);
var ev = ruleEval(cmpV, fe);
if (ev.violated) {
fe.blocked++;
fe.recentBlocked++;
directOverride = String(ev.nextVal);
}
} else if (fe.rec.kind === ctx.tag + "json") {
try {
if (!jsonMod) jsonMod = JSON.parse(String(v));
var curG = getJsonPathVal(jsonMod, fe.rec.jpath);
if (curG.ok) {
var evJ = ruleEval(curG.val, fe);
if (evJ.violated) {
fe.blocked++;
fe.recentBlocked++;
setJsonPathVal(jsonMod, fe.rec.jpath, evJ.nextVal);
}
}
} catch (_) {}
}
});
if (directOverride !== null) return origSetItem.call(this, k, directOverride);
if (jsonMod) return origSetItem.call(this, k, JSON.stringify(jsonMod));
}
return origSetItem.apply(this, arguments);
}, origSetItem, "setItem");

var wrappedRemoveItem = origRemoveItem ? mask(function removeItem(k) {
var ctx = resolveActive(this);
var set = ctx ? ctx.keyMap.get(String(k)) : null;
if (set && set.size > 0) {
var blockedAny = false;
set.forEach(function(fe) {
if (fe.rule !== "step") {
fe.blocked++;
fe.recentBlocked++;
blockedAny = true;
}
});
if (blockedAny) return;
}
return origRemoveItem.apply(this, arguments);
}, origRemoveItem, "removeItem") : null;

var wrappedClear = origClear ? mask(function clear() {
var ctx = resolveActive(this);
if (ctx && ctx.keyMap.size > 0) {
var saved = [];
ctx.keyMap.forEach(function(set, keyName) {
try {
var curRaw = this.getItem(keyName);
if (curRaw !== null) saved.push([keyName, curRaw]);
set.forEach(function(fe) { fe.blocked++; fe.recentBlocked++; });
} catch (_) {}
}, this);
var r = origClear.apply(this, arguments);
for (var si = 0; si < saved.length; si++) {
try { origSetItem.call(this, saved[si][0], saved[si][1]); } catch (_) {}
}
return r;
}
return origClear.apply(this, arguments);
}, origClear, "clear") : null;

var installFn = function(name, fn) {
if (!fn) return false;
try {
Object.defineProperty(targetObj, name, { value: fn, writable: true, configurable: true, enumerable: true });
return true;
} catch (_) {
try { targetObj[name] = fn; return true; } catch (_2) { return false; }
}
};
if (!installFn("setItem", wrappedSetItem)) return null;
if (wrappedRemoveItem) installFn("removeItem", wrappedRemoveItem);
if (wrappedClear) installFn("clear", wrappedClear);
storageProtoHook = state;
}
return tag === "ls" ? { keys: storageProtoHook.lsKeys } : { keys: storageProtoHook.ssKeys };
}
function maybeRemoveStorageHook() {
if (!storageProtoHook) return;
if (storageProtoHook.lsKeys.size > 0 || storageProtoHook.ssKeys.size > 0) return;
var restoreFn = function(name, origFn) {
if (!origFn) return;
try {
Object.defineProperty(storageProtoHook.targetObj, name, { value: origFn, writable: true, configurable: true, enumerable: true });
} catch (_) {
try { storageProtoHook.targetObj[name] = origFn; } catch (_2) {}
}
};
restoreFn("setItem", storageProtoHook.origSetItem);
restoreFn("removeItem", storageProtoHook.origRemoveItem);
restoreFn("clear", storageProtoHook.origClear);
storageProtoHook = null;
}
function enforceSingleBinaryPass() {
var now = origDateNow();
var anyFightingBinary = false;
frozenMap.forEach(function(fe) {
if (now - fe.rateWindowStart >= 1000) {
fe.ratePerSec = fe.recentBlocked;
fe.recentBlocked = 0;
fe.rateWindowStart = now;
}
var h = fe.rec;
if (fe.rule === "step") {
if (now - fe.lastStepMs >= 1000) {
fe.lastStepMs = now;
var rdS = readHitCurrent(h);
if (rdS.ok && typeof rdS.val === "number" && Number.isFinite(rdS.val)) {
var nextS = rdS.val + fe.stepDelta;
if (writeHitValue(h, String(nextS), true)) {
fe.blocked++;
fe.recentBlocked++;
fe.status = "enforcing";
} else {
fe.status = "detached";
}
} else {
fe.status = "detached";
}
}
return;
}
var isStorage = (h.kind === "ls" || h.kind === "ss" || h.kind === "lsjson" || h.kind === "ssjson");
if (fe.trapInstalled && !isStorage) {
    """.trimStart('\n').trimEnd('\n')

    private val JS_PART_3 = """
if (fe.origGet && fe.origSet && fe.origObj) {
try {
var liveAcc = fe.origGet.call(fe.origObj);
var cmpAcc = liveAcc;
if (h.xorMode === 3 && typeof liveAcc === "number") {
var lkA = readLiveXorKey(h, null);
if (lkA.ok) cmpAcc = ((liveAcc | 0) ^ (lkA.key | 0)) | 0;
}
var evAcc = ruleEval(cmpAcc, fe);
if (evAcc.violated) {
var toSet = evAcc.nextVal;
if (h.xorMode === 3 && typeof toSet === "number") {
var lkA2 = readLiveXorKey(h, null);
if (lkA2.ok) toSet = ((toSet | 0) ^ (lkA2.key | 0)) | 0;
}
selfWriteDepth++;
try { fe.origSet.call(fe.origObj, toSet); } finally { selfWriteDepth--; }
fe.backingVal = evAcc.nextVal;
fe.blocked++;
fe.recentBlocked++;
}
} catch (_) {}
}
fe.status = fe.ratePerSec >= 10 ? "fighting" : (fe.blocked > 0 ? "enforcing" : "locked");
return;
}
if ((h.kind === "wasm" || h.kind === "ta" || h.kind === "ab") && fe.rule === "eq" && fe.targetBytes && !h.xorMode) {
var diffB = binaryBytesDiffer(h, fe.targetBytes);
if (diffB === null) {
fe.status = "detached";
return;
}
if (diffB) {
if (writeHitValue(h, fe.valStr, true)) {
fe.blocked++;
fe.recentBlocked++;
anyFightingBinary = true;
} else {
fe.status = "detached";
return;
}
}
fe.status = fe.ratePerSec >= 10 ? "fighting" : (fe.blocked > 0 ? "enforcing" : "locked");
if (fe.status === "fighting") anyFightingBinary = true;
return;
}
var rd = readHitCurrent(h);
if (!rd.ok) {
if (isStorage && fe.rule === "eq") {
if (writeHitValue(h, fe.valStr, true)) {
fe.blocked++;
fe.recentBlocked++;
fe.mode = "trap+poll";
fe.status = "enforcing";
return;
}
}
fe.status = "detached";
return;
}
var ev = ruleEval(rd.val, fe);
if (ev.violated) {
if (writeHitValue(h, String(ev.nextVal), true)) {
fe.blocked++;
fe.recentBlocked++;
if (isStorage && fe.trapInstalled) fe.mode = "trap+poll";
if (!isStorage) anyFightingBinary = true;
} else {
fe.status = "detached";
return;
}
}
fe.status = fe.ratePerSec >= 10 ? "fighting" : (fe.blocked > 0 ? "enforcing" : "locked");
if (!isStorage && fe.status === "fighting") anyFightingBinary = true;
});
return anyFightingBinary;
}
function rebindWasmFreezes(memObj) {
if (!memObj || frozenMap.size === 0) return;
frozenMap.forEach(function(fe) {
var h = fe.rec;
if (!h || h.kind !== "wasm") return;
var curM = h.memRef ? h.memRef.deref() : null;
if (curM !== memObj) return;
if (fe.rule === "eq") {
if (writeHitValue(h, fe.valStr, true)) {
fe.targetBytes = captureTargetBytes(h);
fe.status = "enforcing";
}
} else {
var rdG = readHitCurrent(h);
if (rdG.ok) {
var evG = ruleEval(rdG.val, fe);
if (evG.violated) writeHitValue(h, String(evG.nextVal), true);
}
}
});
}
function tryRebindFrozenJs(fe) {
if (!fe || !fe.rec || fe.rec.kind !== "js") return false;
var curObj = fe.rec.objRef ? fe.rec.objRef.deref() : null;
var resolved = resolveJsPathFromWindow(fe.localId);
if (!resolved || !resolved.obj) return false;
if (curObj && curObj === resolved.obj) return false;
fe.rec.objRef = mkRef(resolved.obj);
fe.rec.key = resolved.key;
fe.trapInstalled = false;
if (fe.rule === "eq") writeHitValue(fe.rec, fe.valStr, true);
installJsPropertyHook(fe.localId, resolved.obj, resolved.key, fe);
fe.status = "enforcing";
return true;
}
function scheduleBurstPass() {
if (freezeStrength < 2 || frozenMap.size === 0 || burstPending) return;
var nowB = origDateNow();
if (nowB - burstFrameStart >= 16) {
burstFrameStart = nowB;
burstFrameCount = 0;
}
if (burstFrameCount >= 6) return;
burstFrameCount++;
if (typeof MessageChannel === "function") {
if (!burstChannel) {
try {
burstChannel = new MessageChannel();
burstChannel.port1.onmessage = function() {
burstPending = false;
if (frozenMap.size > 0) {
var stillFight = enforceSingleBinaryPass();
if (stillFight && freezeStrength >= 2) scheduleBurstPass();
}
};
} catch (_) { burstChannel = null; }
}
if (burstChannel) {
burstPending = true;
try { burstChannel.port2.postMessage(0); return; } catch (_) { burstPending = false; }
}
}
if (typeof Promise === "function") {
burstPending = true;
Promise.resolve().then(function() {
burstPending = false;
if (frozenMap.size > 0) enforceSingleBinaryPass();
});
}
}
function enforceBinaryFreezes() {
lastEnforceTickMs = origDateNow();
if (frozenMap.size === 0) {
stopFreezeLoops();
return;
}
if (freezeStrength >= 1) {
frozenMap.forEach(function(fe) {
if (fe.rec && fe.rec.kind === "js" && fe.rec.id && fe.rec.id.indexOf("js:") === 0) {
var liveO = fe.rec.objRef ? fe.rec.objRef.deref() : null;
if (!liveO) tryRebindFrozenJs(fe);
}
});
}
var fighting = enforceSingleBinaryPass();
if (fighting) {
if (freezeStrength >= 2) {
scheduleBurstPass();
} else if (typeof Promise === "function") {
Promise.resolve().then(function() {
if (frozenMap.size > 0) enforceSingleBinaryPass();
});
}
}
}
function activeBinaryModeLabel(hasXor) {
if (freezeStrength >= 2) return hasXor ? "MAX-XOR" : "MAX-1000Hz";
if (freezeStrength === 0) return hasXor ? "60Hz-XOR" : "60Hz";
return hasXor ? "250Hz-XOR" : "250Hz";
}
function startFreezeLoops() {
var periodMs = freezeStrength >= 2 ? 2 : (freezeStrength === 0 ? 16 : 4);
if (freezeIntervalId === null) {
var si = origSetInterval || setInterval;
freezeIntervalId = si(enforceBinaryFreezes, periodMs);
}
if (freezeStrength >= 1 && freezeRafId === null && (origRAF || typeof window.requestAnimationFrame === "function")) {
var rafFn = origRAF || window.requestAnimationFrame.bind(window);
var rafTick = function() {
if (frozenMap.size === 0 || freezeStrength < 1) {
freezeRafId = null;
return;
}
enforceBinaryFreezes();
freezeRafId = rafFn(rafTick);
};
freezeRafId = rafFn(rafTick);
}
}
function stopFreezeLoops() {
burstPending = false;
if (burstChannel) {
try { burstChannel.port1.close(); burstChannel.port2.close(); } catch (_) {}
burstChannel = null;
}
if (freezeIntervalId !== null) {
try { (origClearInterval || clearInterval)(freezeIntervalId); } catch (_) {}
freezeIntervalId = null;
}
if (freezeRafId !== null) {
try {
if (origCancelRAF) origCancelRAF(freezeRafId);
else if (typeof window.cancelAnimationFrame === "function") window.cancelAnimationFrame(freezeRafId);
} catch (_) {}
freezeRafId = null;
}
}
function ensureFreezeLoopAlive() {
if (frozenMap.size === 0) return;
var now = origDateNow();
if (freezeIntervalId === null || (lastEnforceTickMs > 0 && (now - lastEnforceTickMs) > 120)) {
stopFreezeLoops();
startFreezeLoops();
enforceBinaryFreezes();
}
}
function installJsPropertyHook(localId, obj, key, ownerEntry) {
try {
var chain = findPropertyDescriptorChain(obj, key);
var ownDesc = chain && chain.isOwn ? chain.desc : null;
var chainDesc = chain ? chain.desc : null;
var isAccessor = !!(chainDesc && (typeof chainDesc.get === "function" || typeof chainDesc.set === "function"));
if (ownDesc && ownDesc.configurable === false) return false;
ownerEntry.origDesc = ownDesc;
ownerEntry.origWasOwn = !!ownDesc;
ownerEntry.origGet = isAccessor && typeof chainDesc.get === "function" ? chainDesc.get : null;
ownerEntry.origSet = isAccessor && typeof chainDesc.set === "function" ? chainDesc.set : null;
ownerEntry.origObj = obj;
var rec = ownerEntry.rec;
var curInit = undefined;
try { curInit = isAccessor && ownerEntry.origGet ? ownerEntry.origGet.call(obj) : obj[key]; } catch (_) {}
if (rec.xorMode === 3 && typeof curInit === "number") {
var lk0 = readLiveXorKey(rec, null);
if (lk0.ok) curInit = ((curInit | 0) ^ (lk0.key | 0)) | 0;
}
ownerEntry.backingVal = (ownerEntry.rule === "eq") ? ownerEntry.parsedVal : curInit;
Object.defineProperty(obj, key, {
configurable: true,
enumerable: chainDesc ? (chainDesc.enumerable !== false) : true,
get: function() {
var feNow = frozenMap.get(localId);
var teNow = traceMap.get(localId);
var holder = feNow || teNow || ownerEntry;
if (holder.origGet) {
var liveRaw = holder.origGet.call(this);
var liveCmp = liveRaw;
if (rec.xorMode === 3 && typeof liveRaw === "number") {
var lkG = readLiveXorKey(rec, null);
if (lkG.ok) liveCmp = ((liveRaw | 0) ^ (lkG.key | 0)) | 0;
}
if (feNow) {
var evG = ruleEval(liveCmp, feNow);
if (evG.violated) {
var setRaw = evG.nextVal;
if (rec.xorMode === 3 && typeof setRaw === "number") {
var lkG2 = readLiveXorKey(rec, null);
if (lkG2.ok) setRaw = ((setRaw | 0) ^ (lkG2.key | 0)) | 0;
}
if (holder.origSet) {
selfWriteDepth++;
try { holder.origSet.call(this, setRaw); } catch (_) {} finally { selfWriteDepth--; }
}
feNow.blocked++;
feNow.recentBlocked++;
feNow.backingVal = evG.nextVal;
return setRaw;
}
}
return liveRaw;
}
var valOut = feNow && feNow.rule === "eq" ? feNow.parsedVal : holder.backingVal;
if (rec.xorMode === 3 && typeof valOut === "number") {
var lkG3 = readLiveXorKey(rec, null);
if (lkG3.ok) return ((valOut | 0) ^ (lkG3.key | 0)) | 0;
}
return valOut;
},
set: function(attemptedRaw) {
var feNow = frozenMap.get(localId);
var teNow = traceMap.get(localId);
var holder = feNow || teNow || ownerEntry;
var attempted = attemptedRaw;
if (rec.xorMode === 3 && typeof attemptedRaw === "number") {
var lkS = readLiveXorKey(rec, null);
if (lkS.ok) attempted = ((attemptedRaw | 0) ^ (lkS.key | 0)) | 0;
}
var prevPlain = holder.backingVal;
if (teNow && selfWriteDepth === 0) {
recordTraceEvent(localId, prevPlain, attempted, extractCallerFrame());
}
if (feNow) {
var evS = ruleEval(attempted, feNow);
if (evS.violated) {
feNow.blocked++;
feNow.recentBlocked++;
feNow.backingVal = evS.nextVal;
if (teNow) teNow.backingVal = evS.nextVal;
if (holder.origSet) {
var setRaw = evS.nextVal;
if (rec.xorMode === 3 && typeof setRaw === "number") {
var lkS2 = readLiveXorKey(rec, null);
if (lkS2.ok) setRaw = ((setRaw | 0) ^ (lkS2.key | 0)) | 0;
}
selfWriteDepth++;
try { holder.origSet.call(this, setRaw); } catch (_) {} finally { selfWriteDepth--; }
}
return;
}
}
holder.backingVal = attempted;
if (feNow) feNow.backingVal = attempted;
if (teNow) teNow.backingVal = attempted;
if (holder.origSet) {
try { holder.origSet.call(this, attemptedRaw); } catch (_) {}
}
}
});
ownerEntry.trapInstalled = true;
ownerEntry.mode = isAccessor ? "accessor-trap" : "trap";
return true;
} catch (_) {
return false;
}
}
function restoreJsPropertyHook(entry) {
try {
if (!entry || !entry.trapInstalled || !entry.origObj) return;
if (!entry.origWasOwn) {
try { delete entry.origObj[entry.rec.key]; } catch (_) {}
return;
}
var finalVal = entry.backingVal;
if (entry.rec.xorMode === 3 && typeof finalVal === "number") {
var lkR = readLiveXorKey(entry.rec, null);
if (lkR.ok) finalVal = ((finalVal | 0) ^ (lkR.key | 0)) | 0;
}
var restoreDesc = entry.origDesc || { value: finalVal, writable: true, enumerable: true, configurable: true };
if ("value" in restoreDesc) {
restoreDesc = {
value: finalVal,
writable: restoreDesc.writable !== false,
enumerable: restoreDesc.enumerable !== false,
configurable: true
};
}
Object.defineProperty(entry.origObj, entry.rec.key, restoreDesc);
} catch (_) {}
}
function installFreezeEntry(localId, valStr, ruleStr) {
var rec = watchRefs.get(localId);
if (!rec) return { ok: false, error: "Entry not found in frame" };
var existing = frozenMap.get(localId);
if (existing) {
configureFreezeRuleValues(existing, rec, valStr, ruleStr);
if (existing.rule === "eq") {
if (!writeHitValue(rec, valStr, true)) return { ok: false, error: "Write failed (detached)" };
existing.backingVal = existing.parsedVal;
} else if (existing.rule !== "step") {
var rdE = readHitCurrent(rec);
if (!rdE.ok) return { ok: false, error: "Target detached" };
var evE = ruleEval(rdE.val, existing);
if (evE.violated) {
if (!writeHitValue(rec, String(evE.nextVal), true)) return { ok: false, error: "Write failed (detached)" };
existing.backingVal = evE.nextVal;
} else {
existing.backingVal = rdE.val;
}
}
if (rec.kind === "wasm" || rec.kind === "ta" || rec.kind === "ab") {
existing.targetBytes = (existing.rule === "eq") ? captureTargetBytes(rec) : null;
var rdSnapE = readHitCurrent(rec);
if (rdSnapE.ok && existing.rule === "eq") existing.parsedVal = rdSnapE.val;
}
ensureFreezeLoopAlive();
return { ok: true, mode: existing.mode, rule: existing.rule, blocked: existing.blocked, status: existing.status };
}
var fe = {
localId: localId,
rec: rec,
valStr: String(valStr),
parsedVal: null,
rangeLo: null,
rangeHi: null,
stepDelta: 0,
lastStepMs: origDateNow(),
backingVal: null,
rule: "eq",
mode: activeBinaryModeLabel(!!rec.xorMode),
blocked: 0,
recentBlocked: 0,
ratePerSec: 0,
rateWindowStart: origDateNow(),
trapInstalled: false,
origDesc: null,
origWasOwn: false,
origGet: null,
origSet: null,
origObj: null,
targetBytes: null,
status: "locked"
};
configureFreezeRuleValues(fe, rec, valStr, ruleStr);
if (fe.rule === "eq") {
if (!writeHitValue(rec, valStr, true)) return { ok: false, error: "Write failed (detached)" };
fe.backingVal = fe.parsedVal;
} else if (fe.rule !== "step") {
var rdInit = readHitCurrent(rec);
if (!rdInit.ok) return { ok: false, error: "Target detached" };
var evInit = ruleEval(rdInit.val, fe);
if (evInit.violated) {
if (!writeHitValue(rec, String(evInit.nextVal), true)) return { ok: false, error: "Write failed (detached)" };
fe.backingVal = evInit.nextVal;
} else {
fe.backingVal = rdInit.val;
}
} else {
var rdSt = readHitCurrent(rec);
if (!rdSt.ok) return { ok: false, error: "Target detached" };
fe.backingVal = rdSt.val;
}
if (rec.kind === "wasm" || rec.kind === "ta" || rec.kind === "ab") {
fe.targetBytes = (fe.rule === "eq") ? captureTargetBytes(rec) : null;
var rdSnap = readHitCurrent(rec);
if (rdSnap.ok && fe.rule === "eq") fe.parsedVal = rdSnap.val;
}
if (rec.kind === "js") {
var obj = rec.objRef ? rec.objRef.deref() : null;
if (!obj) return { ok: false, error: "Object garbage-collected" };
var existingTrace = traceMap.get(localId);
if (existingTrace && existingTrace.trapInstalled) {
fe.origDesc = existingTrace.origDesc;
fe.origWasOwn = existingTrace.origWasOwn;
fe.origGet = existingTrace.origGet;
fe.origSet = existingTrace.origSet;
fe.origObj = existingTrace.origObj;
fe.trapInstalled = true;
fe.mode = existingTrace.origGet ? "accessor-trap" : "trap";
existingTrace.backingVal = fe.backingVal;
} else {
installJsPropertyHook(localId, obj, rec.key, fe);
}
} else if (rec.kind === "map") {
var mp = rec.objRef ? rec.objRef.deref() : null;
if (!mp) return { ok: false, error: "Map garbage-collected" };
try {
var mw = mapWrapState.get(mp);
if (!mw) {
var origSet = mp.set;
var origDel = mp.delete;
var origClr = mp.clear;
mw = { origSet: origSet, origDel: origDel, origClr: origClr, keys: new Map() };
mp.set = mask(function set(mk, mv) {
var f = mw.keys.get(mk);
if (f) {
var evM = ruleEval(mv, f);
if (evM.violated) {
f.blocked++;
f.recentBlocked++;
return origSet.call(this, mk, evM.nextVal);
}
}
return origSet.apply(this, arguments);
}, origSet, "set");
mp.delete = mask(function del(mk) {
var f = mw.keys.get(mk);
if (f) {
f.blocked++;
f.recentBlocked++;
return false;
}
return origDel.apply(this, arguments);
}, origDel, "delete");
mp.clear = mask(function clear() {
var saved = [];
mw.keys.forEach(function(feM, mk) {
saved.push([mk, feM.rule === "eq" ? feM.parsedVal : (feM.backingVal !== null ? feM.backingVal : feM.parsedVal), feM]);
});
var r = origClr.apply(this, arguments);
for (var si = 0; si < saved.length; si++) {
origSet.call(this, saved[si][0], saved[si][1]);
saved[si][2].blocked++;
saved[si][2].recentBlocked++;
}
return r;
}, origClr, "clear");
mapWrapState.set(mp, mw);
}
mw.keys.set(rec.key, fe);
if (fe.rule === "eq") mw.origSet.call(mp, rec.key, fe.parsedVal);
fe.trapInstalled = true;
fe.mode = "trap";
} catch (_) {}
} else if (rec.kind === "ls" || rec.kind === "ss" || rec.kind === "lsjson" || rec.kind === "ssjson") {
var tagS = (rec.kind === "ls" || rec.kind === "lsjson") ? "ls" : "ss";
var sh = ensureStorageHook(tagS);
if (sh) {
var setS = sh.keys.get(rec.key);
if (!setS) { setS = new Set(); sh.keys.set(rec.key, setS); }
setS.add(fe);
fe.trapInstalled = true;
fe.mode = "trap";
}
}
frozenMap.set(localId, fe);
startFreezeLoops();
return { ok: true, mode: fe.mode, rule: fe.rule, blocked: fe.blocked, status: fe.status };
}
function removeFreezeEntry(localId) {
var fe = frozenMap.get(localId);
if (!fe) return false;
frozenMap.delete(localId);
try {
if (fe.trapInstalled && fe.rec.kind === "js" && fe.origObj) {
var teKeep = traceMap.get(localId);
if (teKeep && teKeep.trapInstalled) {
teKeep.backingVal = fe.backingVal;
} else {
restoreJsPropertyHook(fe);
}
} else if (fe.trapInstalled && fe.rec.kind === "map") {
var mp = fe.rec.objRef ? fe.rec.objRef.deref() : null;
if (mp) {
var mw = mapWrapState.get(mp);
if (mw) {
mw.keys.delete(fe.rec.key);
if (mw.keys.size === 0) {
try { delete mp.set; } catch (_) { mp.set = mw.origSet; }
try { delete mp.delete; } catch (_) { mp.delete = mw.origDel; }
try { delete mp.clear; } catch (_) { mp.clear = mw.origClr; }
mapWrapState.delete(mp);
}
}
}
} else if (fe.trapInstalled && (fe.rec.kind === "ls" || fe.rec.kind === "ss" || fe.rec.kind === "lsjson" || fe.rec.kind === "ssjson")) {
var tagS = (fe.rec.kind === "ls" || fe.rec.kind === "lsjson") ? "ls" : "ss";
if (storageProtoHook) {
var kMap = tagS === "ls" ? storageProtoHook.lsKeys : storageProtoHook.ssKeys;
var setS = kMap.get(fe.rec.key);
if (setS) {
setS.delete(fe);
if (setS.size === 0) kMap.delete(fe.rec.key);
}
maybeRemoveStorageHook();
}
}
} catch (_) {}
if (frozenMap.size === 0) stopFreezeLoops();
return true;
}
function clearAllFreezes() {
var keys = Array.from(frozenMap.keys());
for (var i = 0; i < keys.length; i++) removeFreezeEntry(keys[i]);
stopFreezeLoops();
}
function collectFreezeStatsMap() {
ensureFreezeLoopAlive();
var out = {};
frozenMap.forEach(function(fe, localId) {
out[qualifyId(localId)] = {
mode: fe.mode,
rule: fe.rule || "eq",
blocked: fe.blocked,
rate: fe.ratePerSec || 0,
status: fe.status
};
});
return out;
}
function pollBinaryTraces() {
if (traceMap.size === 0) {
if (traceRafId !== null) {
try { (origCancelRAF || window.cancelAnimationFrame)(traceRafId); } catch (_) {}
traceRafId = null;
}
return;
}
traceMap.forEach(function(te, localId) {
if (te.trapInstalled) return;
var rd = readHitCurrent(te.rec);
if (!rd.ok) return;
if (rd.val !== te.lastPolledVal) {
var prevV = te.lastPolledVal;
te.lastPolledVal = rd.val;
recordTraceEvent(localId, prevV, rd.val, te.rec.kind + "-write");
}
});
if (traceMap.size > 0 && (origRAF || typeof window.requestAnimationFrame === "function")) {
var rf = origRAF || window.requestAnimationFrame.bind(window);
traceRafId = rf(pollBinaryTraces);
}
}
function installTraceEntry(localId, enabled) {
if (!enabled) {
var old = traceMap.get(localId);
if (!old) return { ok: true, active: false };
traceMap.delete(localId);
if (old.trapInstalled && old.rec.kind === "js" && !frozenMap.has(localId)) {
restoreJsPropertyHook(old);
}
return { ok: true, active: false };
}
var rec = watchRefs.get(localId);
if (!rec) return { ok: false, error: "Entry not found in frame" };
if (traceMap.has(localId)) return { ok: true, active: true };
var rd = readHitCurrent(rec);
var te = {
localId: localId,
rec: rec,
events: [],
writeCount: 0,
lastPolledVal: rd.ok ? rd.val : null,
backingVal: rd.ok ? rd.val : null,
trapInstalled: false,
origDesc: null,
origWasOwn: false,
origGet: null,
origSet: null,
origObj: null,
rule: "trace"
};
if (rec.kind === "js") {
var obj = rec.objRef ? rec.objRef.deref() : null;
if (obj) {
var existingF = frozenMap.get(localId);
if (existingF && existingF.trapInstalled) {
te.origDesc = existingF.origDesc;
te.origWasOwn = existingF.origWasOwn;
te.origGet = existingF.origGet;
te.origSet = existingF.origSet;
te.origObj = existingF.origObj;
te.backingVal = existingF.backingVal;
te.trapInstalled = true;
} else {
installJsPropertyHook(localId, obj, rec.key, te);
}
}
}
traceMap.set(localId, te);
if (!te.trapInstalled && traceRafId === null && (origRAF || typeof window.requestAnimationFrame === "function")) {
var rf = origRAF || window.requestAnimationFrame.bind(window);
traceRafId = rf(pollBinaryTraces);
}
return { ok: true, active: true, mode: te.trapInstalled ? "js-spy" : "poll-spy" };
}
function clearAllTraces() {
var ks = Array.from(traceMap.keys());
for (var i = 0; i < ks.length; i++) installTraceEntry(ks[i], false);
if (traceRafId !== null) {
try { (origCancelRAF || window.cancelAnimationFrame)(traceRafId); } catch (_) {}
traceRafId = null;
}
}
function collectTraceLogsMap() {
var out = {};
traceMap.forEach(function(te, localId) {
out[qualifyId(localId)] = {
active: true,
mode: te.trapInstalled ? "js-spy" : "poll-spy",
count: te.writeCount,
events: te.events.slice(-20)
};
});
return out;
}
function scanBuffer(buf, byteOff, byteLen, kind, bufIdx, labelPrefix, dt, sc, v1, v2, s1, aob, out, cap, extraRef, deadlineMs, stateFlags) {
if (!buf || byteLen <= 0 || out.length >= cap) return;
if (deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var scanLen = Math.min(byteLen, MAX_SCAN_BYTES);
var memRef = kind === "wasm" ? extraRef : null;
var abRef = kind === "ab" ? extraRef : null;
function pushHit(off, typeStr, len, prevVal, extraTag, scaleVal) {
var idStr = labelPrefix + "+0x" + off.toString(16).toUpperCase() + (typeStr === "str" || typeStr === "aob" ? "" : (":" + typeStr)) + (extraTag || "");
out.push({
id: idStr,
kind: kind,
bufIdx: bufIdx,
memRef: memRef,
abRef: abRef,
off: off,
dt: typeStr,
len: len,
prev: prevVal,
scale: scaleVal || 0
});
}
if (dt === "str") {
var enc = new TextEncoder().encode(s1);
if (!enc.length || scanLen < enc.length) return;
var u8 = new Uint8Array(buf, byteOff, scanLen);
var lim = scanLen - enc.length;
var b0 = enc[0];
for (var o = 0; o <= lim && out.length < cap; o++) {
if ((o & 262143) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
if (u8[o] !== b0) continue;
var ok = true;
for (var k = 1; k < enc.length; k++) { if (u8[o + k] !== enc[k]) { ok = false; break; } }
if (ok) pushHit(o, "str", enc.length, s1);
}
return;
}
if (dt === "aob") {
if (!aob.bytes.length || scanLen < aob.bytes.length) return;
var u8b = new Uint8Array(buf, byteOff, scanLen);
var lim2 = scanLen - aob.bytes.length;
for (var o2 = 0; o2 <= lim2 && out.length < cap; o2++) {
    """.trimStart('\n').trimEnd('\n')

    private val JS_PART_4 = """
if ((o2 & 262143) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var ok2 = true;
for (var k2 = 0; k2 < aob.bytes.length; k2++) {
if (aob.mask[k2] && u8b[o2 + k2] !== aob.bytes[k2]) { ok2 = false; break; }
}
if (ok2) pushHit(o2, "aob", aob.bytes.length, s1);
}
return;
}
var aligned4 = (byteOff & 3) === 0;
var aligned8 = (byteOff & 7) === 0;
if (dt === "i32" && aligned4 && sc === "exact") {
var i32Fast = new Int32Array(buf, byteOff, scanLen >>> 2);
var targetI32 = v1 | 0;
for (var fi = 0, fl = i32Fast.length; fi < fl && out.length < cap; fi++) {
if ((fi & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
if (i32Fast[fi] === targetI32) pushHit(fi << 2, "i32", 4, targetI32);
}
return;
}
if (dt === "u32" && aligned4 && sc === "exact") {
var u32Fast = new Uint32Array(buf, byteOff, scanLen >>> 2);
var targetU32 = (v1 < 0 ? 0 : v1) >>> 0;
for (var fu = 0, ful = u32Fast.length; fu < ful && out.length < cap; fu++) {
if ((fu & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
if (u32Fast[fu] === targetU32) pushHit(fu << 2, "u32", 4, targetU32);
}
return;
}
if (dt === "f32" && aligned4 && sc === "exact") {
var f32Fast = new Float32Array(buf, byteOff, scanLen >>> 2);
for (var ff = 0, ffl = f32Fast.length; ff < ffl && out.length < cap; ff++) {
if ((ff & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var fv = f32Fast[ff];
if (Math.abs(fv - v1) <= 0.01) pushHit(ff << 2, "f32", 4, fv);
}
return;
}
if (dt === "f64" && aligned4 && sc === "exact") {
var i32D = new Int32Array(buf, byteOff, scanLen >>> 2);
var hiLo = f64ToI32Pair(v1 - 0.001)[1];
var hiHi = f64ToI32Pair(v1 + 0.001)[1];
var hiMin = Math.min(hiLo, hiHi), hiMax = Math.max(hiLo, hiHi);
var zeroF64 = Math.abs(v1) <= 0.001;
for (var fd = 0, fdl = i32D.length - 1; fd < fdl && out.length < cap; fd++) {
if ((fd & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var hw = i32D[fd + 1];
if (zeroF64 || (hw >= hiMin && hw <= hiMax)) {
var fdv = i32PairToF64(i32D[fd], hw);
if (Math.abs(fdv - v1) <= 0.001) pushHit(fd << 2, "f64", 8, fdv);
}
}
return;
}
if (dt === "i64" && aligned4 && sc === "exact") {
var i32Q = new Int32Array(buf, byteOff, scanLen >>> 2);
scratchDV.setBigInt64(0, BigInt(Math.round(v1)), true);
var qLo = scratchI32[0], qHi = scratchI32[1];
for (var fq = 0, fql = i32Q.length - 1; fq < fql && out.length < cap; fq++) {
if ((fq & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
if (i32Q[fq] === qLo && i32Q[fq + 1] === qHi) pushHit(fq << 2, "i64", 8, v1);
}
return;
}
if (dt === "auto" && aligned4) {
var len4 = scanLen >>> 2;
var i32a = new Int32Array(buf, byteOff, len4);
var isIntQuery = (sc !== "unknown") && Number.isInteger(v1);
var fitsI32 = isIntQuery && v1 >= -2147483648 && v1 <= 2147483647;
var fitsI16 = isIntQuery && v1 >= -32768 && v1 <= 32767 && v1 !== 0;
var targetU16 = fitsI16 ? (v1 & 0xFFFF) : -1;
var target32 = v1 | 0;
if (sc === "exact") {
var f32CrossZero = Math.abs(v1) <= 0.01;
var f32bA = f32ToI32Bits(v1 - 0.01);
var f32bB = f32ToI32Bits(v1 + 0.01);
var f32Min = Math.min(f32bA, f32bB);
var f32Max = Math.max(f32bA, f32bB);
var f64CrossZero = Math.abs(v1) <= 0.001;
var f64HiA = f64ToI32Pair(v1 - 0.001)[1];
var f64HiB = f64ToI32Pair(v1 + 0.001)[1];
var f64HiMin = Math.min(f64HiA, f64HiB);
var f64HiMax = Math.max(f64HiA, f64HiB);
var checkI64 = isIntQuery && !fitsI32;
var i64Lo = 0, i64Hi = 0;
if (checkI64) {
scratchDV.setBigInt64(0, BigInt(Math.round(v1)), true);
i64Lo = scratchI32[0];
i64Hi = scratchI32[1];
}
var f32a = f32CrossZero ? new Float32Array(buf, byteOff, len4) : null;
var lastIdx = len4 - 1;
var wNext = len4 > 0 ? i32a[0] : 0;
for (var idx = 0; idx < len4 && out.length < cap; idx++) {
if ((idx & 262143) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var w = wNext;
wNext = idx < lastIdx ? i32a[idx + 1] : 0;
if (fitsI32 && w === target32) {
pushHit(idx << 2, "i32", 4, target32);
continue;
}
if (checkI64 && idx < lastIdx && w === i64Lo && wNext === i64Hi) {
pushHit(idx << 2, "i64", 8, v1);
continue;
}
if (f32CrossZero) {
var fvZ = f32a[idx];
if (v1 === 0 ? fvZ === 0 : (fvZ !== 0 && Math.abs(fvZ - v1) <= 0.01)) {
pushHit(idx << 2, "f32", 4, fvZ);
continue;
}
} else if (w >= f32Min && w <= f32Max) {
scratchI32[0] = w;
var fvE = scratchF32[0];
if (fvE !== 0 && Math.abs(fvE - v1) <= 0.01) {
pushHit(idx << 2, "f32", 4, fvE);
continue;
}
}
if (idx < lastIdx && (f64CrossZero || (wNext >= f64HiMin && wNext <= f64HiMax))) {
var dvE = i32PairToF64(w, wNext);
if (v1 === 0 ? dvE === 0 : (dvE !== 0 && Math.abs(dvE - v1) <= 0.001)) {
pushHit(idx << 2, "f64", 8, dvE);
continue;
}
}
if (fitsI16) {
if ((w & 0xFFFF) === targetU16) pushHit(idx << 2, "i16", 2, v1);
if ((w >>> 16) === targetU16) pushHit((idx << 2) + 2, "i16", 2, v1);
}
}
return;
}
var f32n = new Float32Array(buf, byteOff, len4);
for (var idx2 = 0; idx2 < len4 && out.length < cap; idx2++) {
if ((idx2 & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var off4 = idx2 << 2;
if (sc === "unknown") {
var uv = i32a[idx2];
pushHit(off4, "i32", 4, uv);
continue;
}
var iv = i32a[idx2];
if ((fitsI32 || !isIntQuery) && matchNum(iv, 0, false, "i32", sc, v1, v2)) {
pushHit(off4, "i32", 4, iv);
continue;
}
var fv2 = f32n[idx2];
if (Number.isFinite(fv2) && (v1 === 0 || Math.abs(fv2) >= 1e-6) && matchNum(fv2, 0, false, "f32", sc, v1, v2)) {
pushHit(off4, "f32", 4, fv2);
continue;
}
if (idx2 + 1 < len4) {
var dv2 = i32PairToF64(i32a[idx2], i32a[idx2 + 1]);
if (Number.isFinite(dv2) && (v1 === 0 || Math.abs(dv2) >= 1e-6) && matchNum(dv2, 0, false, "f64", sc, v1, v2)) {
pushHit(off4, "f64", 8, dv2);
}
}
}
return;
}
var dv = new DataView(buf, byteOff, scanLen);
var sz = dt === "i8" ? 1 : (dt === "i16" ? 2 : ((dt === "i32" || dt === "u32" || dt === "f32") ? 4 : 8));
var endOff = scanLen - sz;
for (var off2 = 0; off2 <= endOff && out.length < cap; off2 += sz) {
if ((off2 & 262143) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var val = readViewVal(dv, off2, dt);
if (matchNum(val, 0, false, dt, sc, v1, v2)) {
pushHit(off2, dt, sz, val);
}
}
}
function scanBufferFallback(buf, byteOff, byteLen, kind, bufIdx, labelPrefix, v1, out, cap, extraRef, deadlineMs, stateFlags) {
if (!buf || byteLen < 8 || out.length >= cap || !Number.isInteger(v1) || v1 === 0) return;
if (deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var scanLen = Math.min(byteLen, MAX_SCAN_BYTES);
var memRef = kind === "wasm" ? extraRef : null;
var abRef = kind === "ab" ? extraRef : null;
function pushFb(off, typeStr, len, prevVal, tag, scaleVal) {
out.push({
id: labelPrefix + "+0x" + off.toString(16).toUpperCase() + ":" + typeStr + tag,
kind: kind,
bufIdx: bufIdx,
memRef: memRef,
abRef: abRef,
off: off,
dt: typeStr,
len: len,
prev: prevVal,
scale: scaleVal || 0
});
}
var len4 = scanLen >>> 2;
var i32a = new Int32Array(buf, byteOff, len4);
var f32LoB = f32ToI32Bits(v1 > 0 ? v1 : (v1 - 0.9999));
var f32HiB = f32ToI32Bits(v1 > 0 ? (v1 + 0.9999) : v1);
var fMin = Math.min(f32LoB, f32HiB), fMax = Math.max(f32LoB, f32HiB);
var scaled100 = (Math.abs(v1) <= 21474836) ? (v1 * 100) | 0 : null;
var scaled10 = (Math.abs(v1) <= 214748364) ? (v1 * 10) | 0 : null;
for (var i = 0; i < len4 && out.length < cap; i++) {
if ((i & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var w = i32a[i];
if (w >= fMin && w <= fMax) {
scratchI32[0] = w;
var fv = scratchF32[0];
if (Math.trunc(fv) === v1) pushFb(i << 2, "f32", 4, fv, "~rnd", 0);
} else if (scaled100 !== null && w === scaled100) {
pushFb(i << 2, "i32", 4, v1, "~x100", 100);
} else if (scaled10 !== null && w === scaled10) {
pushFb(i << 2, "i32", 4, v1, "~x10", 10);
}
}
if (out.length < cap && v1 >= -2147483648 && v1 <= 2147483647) {
var u8 = new Uint8Array(buf, byteOff, scanLen);
var t0 = v1 & 0xFF, t1 = (v1 >>> 8) & 0xFF, t2 = (v1 >>> 16) & 0xFF, t3 = (v1 >>> 24) & 0xFF;
var limU = scanLen - 4;
for (var o = 0; o <= limU && out.length < cap; o++) {
if ((o & 262143) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
if ((o & 3) === 0) continue; // already checked 4B-aligned
if (u8[o] === t0 && u8[o + 1] === t1 && u8[o + 2] === t2 && u8[o + 3] === t3) {
pushFb(o, "i32", 4, v1, "~unalign", 0);
}
}
}
}
function seedEngineAndFrameworkRoots(win, queue, visited) {
function enqueue(o, p) {
try {
if (o && (typeof o === "object" || typeof o === "function") && !visited.has(o)) {
queue.push({ obj: o, path: p, depth: 1 });
}
} catch (_) {}
}
try {
enqueue(win.unityInstance, "unityInstance");
enqueue(win.gameInstance, "gameInstance");
enqueue(win.Module, "Module");
enqueue(win.UE4, "UE4");
enqueue(win.Godot, "Godot");
enqueue(win.godot, "godot");
if (win.Phaser && Array.isArray(win.Phaser.GAMES)) {
for (var pi = 0; pi < win.Phaser.GAMES.length && pi < 4; pi++) {
enqueue(win.Phaser.GAMES[pi], "Phaser.GAMES[" + pi + "]");
}
}
if (win.pc) {
enqueue(win.pc.app, "pc.app");
try {
if (win.pc.Application && typeof win.pc.Application.getApplication === "function") {
enqueue(win.pc.Application.getApplication(), "pc.Application");
}
} catch (_) {}
}
if (win.cc) {
enqueue(win.cc.game, "cc.game");
enqueue(win.cc.director, "cc.director");
enqueue(win.cc.assetManager, "cc.assetManager");
try {
if (win.cc.director) {
if (typeof win.cc.director.getScene === "function") enqueue(win.cc.director.getScene(), "cc.scene");
if (win.cc.director._scene) enqueue(win.cc.director._scene, "cc._scene");
if (win.cc.director._compScheduler) enqueue(win.cc.director._compScheduler, "cc.compSched");
if (win.cc.director._scheduler) enqueue(win.cc.director._scheduler, "cc.sched");
}
} catch (_) {}
}
if (win.System) enqueue(win.System, "System");
enqueue(win.Laya, "Laya");
enqueue(win.egret, "egret");
enqueue(win.createjs, "createjs");
enqueue(win.THREE, "THREE");
enqueue(win.spine, "spine");
enqueue(win.ig, "ig");
enqueue(win.Crafty, "Crafty");
enqueue(win.me, "me");
try {
var wTopKeys = Object.getOwnPropertyNames(win);
for (var wti = 0; wti < wTopKeys.length; wti++) {
var wtk = wTopKeys[wti];
if (wtk.indexOf("webpackChunk") === 0 || wtk.indexOf("webpackJsonp") === 0 || wtk === "__webpack_require__") {
try { enqueue(win[wtk], wtk); } catch (_) {}
}
}
} catch (_) {}
if (win.BABYLON && win.BABYLON.Engine && Array.isArray(win.BABYLON.Engine.Instances)) {
for (var bi = 0; bi < win.BABYLON.Engine.Instances.length && bi < 4; bi++) {
enqueue(win.BABYLON.Engine.Instances[bi], "BABYLON[" + bi + "]");
}
}
try { if (typeof win.cr_getC2Runtime === "function") enqueue(win.cr_getC2Runtime(), "c2Runtime"); } catch (_) {}
enqueue(win.c3_runtimeInterface, "c3_runtime");
enqueue(win._c3Runtime, "c3Runtime");
enqueue(win.C3, "C3");
enqueue(win.${'$'}gameParty, "${'$'}gameParty");
enqueue(win.${'$'}gameActors, "${'$'}gameActors");
enqueue(win.${'$'}gameVariables, "${'$'}gameVariables");
enqueue(win.${'$'}gamePlayer, "${'$'}gamePlayer");
enqueue(win.${'$'}gameSystem, "${'$'}gameSystem");
enqueue(win.g_pBuiltIn, "g_pBuiltIn");
enqueue(win.g_RunRoom, "g_RunRoom");
enqueue(win.gdjs, "gdjs");
enqueue(win.PIXI, "PIXI");
enqueue(win.game, "game");
enqueue(win.app, "app");
var doc = win.document;
if (!doc || typeof doc.querySelectorAll !== "function") return;
var els = doc.querySelectorAll("canvas,#root,#app,#game,#unity-canvas,[id*='game'],[class*='game']");
var maxEls = Math.min(els.length, 16);
for (var ei = 0; ei < maxEls; ei++) {
var el = els[ei];
if (!el) continue;
var eKeys;
try { eKeys = Object.getOwnPropertyNames(el); } catch (_) { continue; }
for (var ki = 0; ki < eKeys.length; ki++) {
var ek = eKeys[ki];
if (INTERNAL_KEY_RE.test(ek)) continue;
if (ek.indexOf("__react") === 0 || ek.indexOf("__vue") === 0 || ek.indexOf("__svelte") === 0 || ek.indexOf("_") === 0 ||
ek === "game" || ek === "engine" || ek === "scene" || ek === "state" || ek === "store") {
try {
var ev = el[ek];
if (ev && typeof ev === "object" && !visited.has(ev)) {
queue.push({ obj: ev, path: "dom." + ek.slice(0, 18), depth: 1 });
}
} catch (_) {}
}
}
}
} catch (_) {}
}
function registerAndScanFullBuffer(ab, labelHint, dt, sc, v1, v2, s1, aob, out, cap, scannedBufs, deadlineMs, stateFlags) {
if (!ab || typeof ab.byteLength !== "number" || ab.byteLength < 4 || ab.byteLength > MAX_SCAN_BYTES) return;
if (scannedBufs.has(ab)) return;
scannedBufs.add(ab);
trackRawBuf(ab);
var abIdx = abRegistry.length;
var ref = mkRef(ab);
abRegistry.push(ref);
var lbl = labelHint ? ("buf#" + abIdx + "(" + labelHint.slice(-20) + ")") : ("buf#" + abIdx);
scanBuffer(ab, 0, ab.byteLength, "ab", abIdx, lbl, dt, sc, v1, v2, s1, aob, out, cap, ref, deadlineMs, stateFlags);
}
function isSkippedBuiltinObject(obj) {
if (!obj) return true;
try {
if (typeof Window === "function" && safeInstanceOf(obj, Window)) {
try { void obj.document; } catch (_) { skippedXOriginCount++; return true; }
}
if (safeInstanceOf(obj, typeof Node === "function" ? Node : null)) return true;
if (safeInstanceOf(obj, typeof Event === "function" ? Event : null)) return true;
if (safeInstanceOf(obj, typeof Promise === "function" ? Promise : null)) return true;
if (safeInstanceOf(obj, typeof RegExp === "function" ? RegExp : null)) return true;
if (safeInstanceOf(obj, typeof Error === "function" ? Error : null)) return true;
var ctor = null;
try { ctor = obj.constructor; } catch (_) { skippedXOriginCount++; return true; }
var cname = "";
try { cname = ctor && typeof ctor.name === "string" ? ctor.name : ""; } catch (_) { skippedXOriginCount++; return true; }
if (cname === "CSSStyleDeclaration" || cname === "DOMTokenList" || cname === "NamedNodeMap" ||
cname === "HTMLCollection" || cname === "NodeList" || cname === "Performance" ||
cname === "Navigator" || cname === "Screen" || cname === "History" || cname === "Location" ||
cname === "Window" || cname === "WindowProxy" || cname === "CrossOriginWindow" ||
cname === "WebGLRenderingContext" || cname === "WebGL2RenderingContext" ||
cname === "WebGLProgram" || cname === "WebGLShader" || cname === "WebGLBuffer" ||
cname === "WebGLTexture" || cname === "WebGLFramebuffer" || cname === "AudioContext") {
return true;
}
return false;
} catch (_) {
skippedXOriginCount++;
return true;
}
}
function collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, cap, scannedBufs, deadlineMs, stateFlags) {
var visited = new WeakSet();
var queue = [{ obj: window, path: "window", depth: 0 }];
seedEngineAndFrameworkRoots(window, queue, visited);
var maxNodes = 65000;
var nodesVisited = 0;
var qHead = 0;
var taSeen = new WeakSet();
var closureSeeded = false;
taRegistry = [];
while (nodesVisited < maxNodes && out.length < cap) {
if (qHead >= queue.length) {
if (closureSeeded) break;
closureSeeded = true;
var keptC = [];
for (var ci = 0; ci < closureObjRefs.length; ci++) {
var co = closureObjRefs[ci].deref();
if (co) { keptC.push(closureObjRefs[ci]); try { if (!visited.has(co)) queue.push({ obj: co, path: "closure#" + ci, depth: 1 }); } catch (_) {} }
}
closureObjRefs = keptC;
var keptM = [];
for (var cmi = 0; cmi < closureMapRefs.length; cmi++) {
var cm = closureMapRefs[cmi].deref();
if (cm) { keptM.push(closureMapRefs[cmi]); try { if (!visited.has(cm)) queue.push({ obj: cm, path: "cmap#" + cmi, depth: 1 }); } catch (_) {} }
}
closureMapRefs = keptM;
var keptS = [];
for (var csi = 0; csi < closureSetRefs.length; csi++) {
var cs = closureSetRefs[csi].deref();
if (cs) { keptS.push(closureSetRefs[csi]); try { if (!visited.has(cs)) queue.push({ obj: cs, path: "cset#" + csi, depth: 1 }); } catch (_) {} }
}
closureSetRefs = keptS;
if (qHead >= queue.length) break;
}
if ((nodesVisited & 511) === 0 && deadlineMs && Date.now() >= deadlineMs) {
if (stateFlags) stateFlags.timedOut = true;
break;
}
var cur = queue[qHead++];
var obj = cur.obj;
if (!obj || (typeof obj !== "object" && typeof obj !== "function")) continue;
try {
if (visited.has(obj)) continue;
visited.add(obj);
} catch (_) { skippedXOriginCount++; continue; }
nodesVisited++;
if (typeof WebAssembly === "object" && safeInstanceOf(obj, WebAssembly.Memory)) {
trackWasmMem(obj);
continue;
}
if (safeIsView(obj)) {
try {
if (obj.buffer) {
registerAndScanFullBuffer(obj.buffer, cur.path, dt, sc, v1, v2, s1, aob, out, cap, scannedBufs, deadlineMs, stateFlags);
}
if (!taSeen.has(obj) && obj.byteLength >= 4) {
taSeen.add(obj);
taRegistry.push(mkRef(obj));
}
} catch (_) {}
continue;
}
if ((typeof ArrayBuffer === "function" && safeInstanceOf(obj, ArrayBuffer)) || isSharedBuf(obj)) {
registerAndScanFullBuffer(obj, cur.path, dt, sc, v1, v2, s1, aob, out, cap, scannedBufs, deadlineMs, stateFlags);
continue;
}
if (cur.depth > 0 && isSkippedBuiltinObject(obj)) continue;
if (safeInstanceOf(obj, Map)) {
var mCount = 0;
try {
obj.forEach(function(mv, mk) {
if (mCount >= 800 || out.length >= cap) return;
mCount++;
var keyLabel = String(mk).slice(0, 28);
if (SENSITIVE_KEY_RE.test(keyLabel) || INTERNAL_KEY_RE.test(keyLabel)) return;
if (typeof mv === "number") {
if (dt !== "str" && dt !== "aob" && matchNum(mv, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
out.push({ id: "map:" + cur.path + "[" + keyLabel + "]", kind: "map", objRef: mkRef(obj), key: mk, dt: Number.isInteger(mv) ? "i32" : "f64", prev: mv });
}
} else if (mv && typeof mv === "object" && cur.depth < 16 && !visited.has(mv)) {
queue.push({ obj: mv, path: cur.path + "[" + keyLabel + "]", depth: cur.depth + 1 });
}
});
} catch (_) {}
continue;
}
if (safeInstanceOf(obj, Set)) {
var sCount = 0;
try {
obj.forEach(function(sv) {
if (sCount >= 500 || out.length >= cap) return;
sCount++;
if (sv && typeof sv === "object" && cur.depth < 16 && !visited.has(sv)) {
queue.push({ obj: sv, path: cur.path + "{#" + sCount + "}", depth: cur.depth + 1 });
}
});
} catch (_) {}
continue;
}
var isArr = false;
try { isArr = Array.isArray(obj); } catch (_) { isArr = false; }
if (isArr) {
var arrLen = 0;
try { arrLen = obj.length; } catch (_) { continue; }
var numScanCap = Math.min(arrLen, 8192);
var objEnqueued = 0;
for (var ai = 0; ai < numScanCap && out.length < cap; ai++) {
var aval;
try { aval = obj[ai]; } catch (_) { continue; }
if (typeof aval === "number") {
if (dt !== "str" && dt !== "aob" && matchNum(aval, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
out.push({ id: "js:" + cur.path + "[" + ai + "]", kind: "js", objRef: mkRef(obj), key: ai, dt: Number.isInteger(aval) ? "i32" : "f64", prev: aval });
}
} else if (aval && typeof aval === "object" && cur.depth < 14 && objEnqueued < 220) {
try {
if (!visited.has(aval)) {
objEnqueued++;
queue.push({ obj: aval, path: cur.path + "[" + ai + "]", depth: cur.depth + 1 });
}
} catch (_) {}
}
}
continue;
}
var keys;
try {
keys = cur.depth <= 2 ? Object.getOwnPropertyNames(obj) : Object.keys(obj);
} catch (_) {
try { keys = Object.keys(obj); } catch (_2) { skippedXOriginCount++; continue; }
}
if (cur.depth > 0 && cur.depth <= 4) {
try {
var proto = Object.getPrototypeOf(obj);
var kSet = null;
var hop = 0;
while (proto && proto !== Object.prototype && proto !== Array.prototype && proto !== Function.prototype && hop < 2) {
hop++;
var pKeys = Object.getOwnPropertyNames(proto);
if (pKeys.length > 0 && pKeys.length <= 96) {
if (!kSet) kSet = new Set(keys);
for (var pki = 0; pki < pKeys.length; pki++) {
var pk = pKeys[pki];
if (pk === "constructor" || kSet.has(pk) || SKIP_KEYS.has(pk) || INTERNAL_KEY_RE.test(pk)) continue;
var pd = null;
try { pd = Object.getOwnPropertyDescriptor(proto, pk); } catch (_) {}
if (pd && typeof pd.get === "function" && typeof pd.set === "function") {
keys.push(pk);
kSet.add(pk);
}
}
}
proto = Object.getPrototypeOf(proto);
}
} catch (_) {}
}
if (cur.depth > 0 && cur.depth <= 5 && typeof Object.getOwnPropertySymbols === "function") {
try {
var syms = Object.getOwnPropertySymbols(obj);
for (var syi = 0; syi < syms.length && syi < 16 && out.length < cap; syi++) {
var sym = syms[syi];
var sv = undefined;
try { sv = obj[sym]; } catch (_) { continue; }
if (sv && typeof sv === "object" && !visited.has(sv)) {
queue.push({ obj: sv, path: cur.path + "[Sym#" + syi + "]", depth: cur.depth + 1 });
}
}
} catch (_) {}
}
var maxKeys = cur.depth === 0 ? keys.length : Math.min(keys.length, 450);
for (var i = 0; i < maxKeys && out.length < cap; i++) {
var k = keys[i];
if (SKIP_KEYS.has(k) || INTERNAL_KEY_RE.test(k) || SENSITIVE_KEY_RE.test(k)) continue;
if (k.length > 2 && k[0] === "o" && k[1] === "n" && k[2] >= "a" && k[2] <= "z") continue;
var desc = null;
try {
desc = Object.getOwnPropertyDescriptor(obj, k);
if (!desc && cur.depth > 0) {
var pCh = findPropertyDescriptorChain(obj, k);
if (pCh && pCh.desc && typeof pCh.desc.get === "function" && typeof pCh.desc.set === "function") desc = pCh.desc;
}
} catch (_) { skippedXOriginCount++; continue; }
var isWritable = false;
var val = undefined;
if (desc) {
if ("value" in desc) {
isWritable = desc.writable === true;
val = desc.value;
} else if (cur.depth > 0 && typeof desc.get === "function" && typeof desc.set === "function") {
isWritable = true;
try { val = obj[k]; } catch (_) { continue; }
} else {
continue;
}
} else {
isWritable = true;
try { val = obj[k]; } catch (_) { continue; }
}
var nextPath = cur.depth === 0 ? k : (cur.path + "." + k);
if (typeof val === "number") {
if (!isWritable) continue;
if (dt !== "str" && dt !== "aob" && matchNum(val, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
out.push({ id: "js:" + nextPath, kind: "js", objRef: mkRef(obj), key: k, dt: Number.isInteger(val) ? "i32" : "f64", prev: val });
}
} else if (typeof val === "bigint") {
if (!isWritable) continue;
var bNum = Number(val);
    """.trimStart('\n').trimEnd('\n')

    private val JS_PART_5 = """
if (dt !== "str" && dt !== "aob" && Number.isFinite(bNum) && matchNum(bNum, 0, false, "i64", sc, v1, v2)) {
out.push({ id: "js:" + nextPath, kind: "js", objRef: mkRef(obj), key: k, dt: "i64", prev: bNum });
}
} else if (typeof val === "string") {
if (!isWritable) continue;
if ((dt === "str" || dt === "auto") && s1 && val.indexOf(s1) !== -1 && val.length <= 256) {
out.push({ id: "js:" + nextPath, kind: "js", objRef: mkRef(obj), key: k, dt: "str", prev: val });
}
} else if (val && (typeof val === "object" || typeof val === "function") && cur.depth < 12) {
if (SKIP_SUBTREE_KEYS.has(k)) continue;
if (typeof val === "function" && cur.depth > 1) continue;
try {
if (!visited.has(val)) queue.push({ obj: val, path: nextPath, depth: cur.depth + 1 });
} catch (_) {}
}
}
}
}
function scanStorage(dt, sc, v1, v2, s1, out, cap) {
var stores = [];
try { if (window.localStorage) stores.push(["ls", window.localStorage]); } catch (_) {}
try { if (window.sessionStorage) stores.push(["ss", window.sessionStorage]); } catch (_) {}
for (var si = 0; si < stores.length; si++) {
var tag = stores[si][0], st = stores[si][1];
try {
for (var i = 0; i < st.length && out.length < cap; i++) {
var k = st.key(i);
if (!k || SENSITIVE_KEY_RE.test(k) || INTERNAL_KEY_RE.test(k)) continue;
var raw = st.getItem(k);
if (raw === null || raw.length > 65536) continue;
var trimmed = raw.trim();
if ((trimmed[0] === "{" || trimmed[0] === "[") && dt !== "aob") {
try {
var jroot = JSON.parse(trimmed);
if (jroot && typeof jroot === "object") {
var jq = [{ node: jroot, jp: [] }];
var jv = 0;
while (jq.length > 0 && jv < 800 && out.length < cap) {
var jc = jq.shift(); jv++;
var jn = jc.node;
var jks = Array.isArray(jn) ? null : Object.keys(jn);
var jlen = Array.isArray(jn) ? Math.min(jn.length, 200) : Math.min(jks.length, 200);
for (var ji = 0; ji < jlen && out.length < cap; ji++) {
var jk = Array.isArray(jn) ? ji : jks[ji];
if (typeof jk === "string" && (SENSITIVE_KEY_RE.test(jk) || INTERNAL_KEY_RE.test(jk))) continue;
var jval = jn[jk];
var nextJp = jc.jp.concat([jk]);
if (typeof jval === "number") {
if (dt !== "str" && matchNum(jval, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
out.push({
id: tag + ":" + k + "${'$'}." + nextJp.join("."),
kind: tag + "json",
key: k,
jpath: nextJp,
dt: Number.isInteger(jval) ? "i32" : "f64",
prev: jval
});
}
} else if (typeof jval === "string" && (dt === "str" || dt === "auto") && s1 && jval.indexOf(s1) !== -1 && jval.length <= 256) {
out.push({
id: tag + ":" + k + "${'$'}." + nextJp.join("."),
kind: tag + "json",
key: k,
jpath: nextJp,
dt: "str",
prev: jval
});
} else if (jval && typeof jval === "object" && nextJp.length < 6) {
jq.push({ node: jval, jp: nextJp });
}
}
}
continue;
}
} catch (_) {}
}
if (raw.length > 256) continue;
if (dt === "str") {
if (s1 && raw.indexOf(s1) !== -1) out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "str", prev: raw });
} else if (dt !== "aob") {
var num = Number(cleanNumStr(raw));
if (trimmed !== "" && Number.isFinite(num) && matchNum(num, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "f64", prev: num });
} else if (dt === "auto" && s1 && raw.indexOf(s1) !== -1) {
out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "str", prev: raw });
}
}
}
} catch (_) {}
}
}
function captureUnknownSnapshots(dt, deadlineMs, stateFlags) {
var snaps = [];
var usedBytes = 0;
var seen = new WeakSet();
var mems = liveWasmEntries();
for (var i = 0; i < mems.length; i++) {
if (deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; break; }
var mb = mems[i].mem.buffer;
if (!mb || seen.has(mb)) continue;
seen.add(mb);
var takeLen = Math.min(mb.byteLength, MAX_UNKNOWN_SNAP_BYTES - usedBytes);
if (takeLen < 4) continue;
takeLen = takeLen & ~3;
try {
var copy = new Uint8Array(mb, 0, takeLen).slice();
usedBytes += takeLen;
snaps.push({
kind: "wasm",
bufIdx: mems[i].id,
labelPrefix: "wasm#" + mems[i].id,
memRef: mems[i].ref,
abRef: null,
snapBuf: copy.buffer,
len: takeLen,
dt: dt
});
} catch (_) {}
}
var rawBufs = liveRawBuffers(seen);
for (var bi = 0; bi < rawBufs.length && usedBytes < MAX_UNKNOWN_SNAP_BYTES; bi++) {
if (deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; break; }
var ab = rawBufs[bi];
if (!ab || seen.has(ab) || ab.byteLength < 16) continue;
seen.add(ab);
var blen = Math.min(ab.byteLength, MAX_UNKNOWN_SNAP_BYTES - usedBytes) & ~3;
if (blen < 4) continue;
try {
var ref = mkRef(ab);
var abIdx = abRegistry.length;
abRegistry.push(ref);
var bcopy = new Uint8Array(ab, 0, blen).slice();
usedBytes += blen;
snaps.push({
kind: "ab",
bufIdx: abIdx,
labelPrefix: "buf#" + abIdx,
memRef: null,
abRef: ref,
snapBuf: bcopy.buffer,
len: blen,
dt: dt
});
} catch (_) {}
}
return { snaps: snaps, totalSlots: usedBytes >>> 2, snapBytes: usedBytes };
}
function matchGroupTermBuf(dv, byteLen, off, term) {
if (term.dt !== "auto") {
var sz = term.dt === "i8" ? 1 : (term.dt === "i16" ? 2 : ((term.dt === "i32" || term.dt === "u32" || term.dt === "f32") ? 4 : 8));
if (off + sz > byteLen) return null;
var v = readViewVal(dv, off, term.dt);
if (matchNum(v, 0, false, term.dt, "exact", term.val, 0)) return { dt: term.dt, val: v, len: sz };
return null;
}
if (off + 4 <= byteLen) {
var iv = dv.getInt32(off, true);
if (term.isInt && term.val >= -2147483648 && term.val <= 2147483647 && iv === (term.val | 0)) return { dt: "i32", val: iv, len: 4 };
if (term.isInt && term.val > 2147483647 && term.val <= 4294967295 && dv.getUint32(off, true) === (term.val >>> 0)) return { dt: "u32", val: term.val >>> 0, len: 4 };
var fv = dv.getFloat32(off, true);
if (Number.isFinite(fv) && (term.val === 0 || Math.abs(fv) >= 1e-6) && Math.abs(fv - term.val) <= 0.01) return { dt: "f32", val: fv, len: 4 };
}
if (off + 8 <= byteLen) {
var dv64 = dv.getFloat64(off, true);
if (Number.isFinite(dv64) && (term.val === 0 || Math.abs(dv64) >= 1e-6) && Math.abs(dv64 - term.val) <= 0.001) return { dt: "f64", val: dv64, len: 8 };
}
return null;
}
function scanBufferGroup(buf, byteOff, byteLen, kind, bufIdx, labelPrefix, gq, out, cap, extraRef, deadlineMs, stateFlags) {
if (!buf || byteLen < 4 || !gq || out.length >= cap) return;
var scanLen = Math.min(byteLen, MAX_SCAN_BYTES);
var dv = new DataView(buf, byteOff, scanLen);
var memRef = kind === "wasm" ? extraRef : null;
var abRef = kind === "ab" ? extraRef : null;
var t0 = gq.terms[0];
var step0 = t0.dt === "i8" ? 1 : (t0.dt === "i16" ? 2 : 4);
var last0 = scanLen - Math.min(4, scanLen);
for (var off0 = 0; off0 <= last0 && out.length < cap; off0 += step0) {
if ((off0 & 262143) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var m0 = matchGroupTermBuf(dv, scanLen, off0, t0);
if (!m0) continue;
var found = [{ off: off0, dt: m0.dt, val: m0.val, len: m0.len }];
var used = [off0];
var prevOff = off0;
var allOk = true;
for (var ti = 1; ti < gq.terms.length; ti++) {
var tk = gq.terms[ti];
var stepk = tk.dt === "i8" ? 1 : (tk.dt === "i16" ? 2 : 4);
var winLo = gq.ordered ? (prevOff + 1) : Math.max(0, off0 - gq.rangeBytes);
var winHi = Math.min(scanLen, off0 + gq.rangeBytes + 1);
var aLo = Math.ceil(winLo / stepk) * stepk;
var matchedK = false;
for (var okOff = aLo; okOff < winHi; okOff += stepk) {
if (used.indexOf(okOff) !== -1) continue;
var mk = matchGroupTermBuf(dv, scanLen, okOff, tk);
if (mk) {
found.push({ off: okOff, dt: mk.dt, val: mk.val, len: mk.len });
used.push(okOff);
prevOff = okOff;
matchedK = true;
break;
}
}
if (!matchedK) { allOk = false; break; }
}
if (allOk) {
for (var fi = 0; fi < found.length && out.length < cap; fi++) {
var fh = found[fi];
out.push({
id: labelPrefix + "+0x" + fh.off.toString(16).toUpperCase() + ":" + fh.dt,
kind: kind, bufIdx: bufIdx, memRef: memRef, abRef: abRef,
off: fh.off, dt: fh.dt, len: fh.len, prev: fh.val, scale: 0
});
}
}
}
}
function scanBufferXor(buf, byteOff, byteLen, kind, bufIdx, labelPrefix, targetI32, hasExplicitKey, explicitKey, out, cap, extraRef, deadlineMs, stateFlags) {
if (!buf || byteLen < 4 || out.length >= cap) return;
var scanLen = Math.min(byteLen, MAX_SCAN_BYTES) & ~3;
if (scanLen < 4) return;
var u32 = new Uint32Array(buf, byteOff, scanLen >>> 2);
var memRef = kind === "wasm" ? extraRef : null;
var abRef = kind === "ab" ? extraRef : null;
var tU = targetI32 >>> 0;
if (hasExplicitKey) {
var enc = (tU ^ (explicitKey >>> 0)) >>> 0;
for (var i = 0; i < u32.length && out.length < cap; i++) {
if ((i & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
if (u32[i] === enc) {
var off = i << 2;
out.push({
id: labelPrefix + "+0x" + off.toString(16).toUpperCase() + ":i32^0x" + (explicitKey >>> 0).toString(16).toUpperCase(),
kind: kind, bufIdx: bufIdx, memRef: memRef, abRef: abRef,
off: off, dt: "i32", len: 4, prev: targetI32 | 0, scale: 0,
xorMode: 1, xorKey: explicitKey >>> 0, xorKeyOff: -1
});
}
}
return;
}
var deltas = [-1, 1, -2, 2, -3, 3, -4, 4];
for (var j = 0; j < u32.length && out.length < cap; j++) {
if ((j & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var w = u32[j];
if (w === 0 || w === 0xFFFFFFFF || w === tU) continue;
var needK = (w ^ tU) >>> 0;
if (needK === 0 || needK === 0xFFFFFFFF || needK === w) continue;
for (var di = 0; di < deltas.length; di++) {
var nj = j + deltas[di];
if (nj < 0 || nj >= u32.length) continue;
if (u32[nj] === needK) {
var off2 = j << 2;
var koff = nj << 2;
out.push({
id: labelPrefix + "+0x" + off2.toString(16).toUpperCase() + ":i32^@0x" + koff.toString(16).toUpperCase(),
kind: kind, bufIdx: bufIdx, memRef: memRef, abRef: abRef,
off: off2, dt: "i32", len: 4, prev: targetI32 | 0, scale: 0,
xorMode: 2, xorKey: needK, xorKeyOff: koff
});
break;
}
}
}
}
function scanJsSpecial(mode, gq, targetI32, hasExplicitKey, explicitKey, out, cap, scannedBufs, deadlineMs, stateFlags) {
var visited = new WeakSet();
var queue = [{ obj: window, path: "window", depth: 0 }];
seedEngineAndFrameworkRoots(window, queue, visited);
var qHead = 0, nodesVisited = 0, maxNodes = 25000;
var closureSeeded = false;
while (nodesVisited < maxNodes && out.length < cap) {
if (qHead >= queue.length) {
if (closureSeeded) break;
closureSeeded = true;
for (var ci = 0; ci < closureObjRefs.length; ci++) {
var co = closureObjRefs[ci].deref();
if (co) { try { if (!visited.has(co)) queue.push({ obj: co, path: "closure#" + ci, depth: 1 }); } catch (_) {} }
}
if (qHead >= queue.length) break;
}
if ((nodesVisited & 511) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; break; }
var cur = queue[qHead++];
var obj = cur.obj;
if (!obj || (typeof obj !== "object" && typeof obj !== "function")) continue;
try { if (visited.has(obj)) continue; visited.add(obj); } catch (_) { continue; }
nodesVisited++;
if (typeof WebAssembly === "object" && safeInstanceOf(obj, WebAssembly.Memory)) { trackWasmMem(obj); continue; }
if (safeIsView(obj)) {
try {
if (obj.buffer && !scannedBufs.has(obj.buffer)) {
scannedBufs.add(obj.buffer);
trackRawBuf(obj.buffer);
var abIdx = abRegistry.length; var ref = mkRef(obj.buffer); abRegistry.push(ref);
var lbl = "buf#" + abIdx + "(" + cur.path.slice(-20) + ")";
if (mode === "group") scanBufferGroup(obj.buffer, 0, obj.buffer.byteLength, "ab", abIdx, lbl, gq, out, cap, ref, deadlineMs, stateFlags);
else scanBufferXor(obj.buffer, 0, obj.buffer.byteLength, "ab", abIdx, lbl, targetI32, hasExplicitKey, explicitKey, out, cap, ref, deadlineMs, stateFlags);
}
} catch (_) {}
continue;
}
if ((typeof ArrayBuffer === "function" && safeInstanceOf(obj, ArrayBuffer)) || isSharedBuf(obj)) {
if (!scannedBufs.has(obj)) {
scannedBufs.add(obj);
trackRawBuf(obj);
var abIdx2 = abRegistry.length; var ref2 = mkRef(obj); abRegistry.push(ref2);
var lbl2 = "buf#" + abIdx2 + "(" + cur.path.slice(-20) + ")";
if (mode === "group") scanBufferGroup(obj, 0, obj.byteLength, "ab", abIdx2, lbl2, gq, out, cap, ref2, deadlineMs, stateFlags);
else scanBufferXor(obj, 0, obj.byteLength, "ab", abIdx2, lbl2, targetI32, hasExplicitKey, explicitKey, out, cap, ref2, deadlineMs, stateFlags);
}
continue;
}
if (cur.depth > 0 && isSkippedBuiltinObject(obj)) continue;
var keys;
try { keys = cur.depth <= 2 ? Object.getOwnPropertyNames(obj) : Object.keys(obj); } catch (_) { continue; }
var numProps = [];
var maxK = cur.depth === 0 ? keys.length : Math.min(keys.length, 300);
for (var ki = 0; ki < maxK; ki++) {
var k = keys[ki];
if (SKIP_KEYS.has(k) || INTERNAL_KEY_RE.test(k) || SENSITIVE_KEY_RE.test(k)) continue;
var val;
try {
var d = Object.getOwnPropertyDescriptor(obj, k);
if (d && "value" in d) { if (d.writable !== true) continue; val = d.value; }
else if (cur.depth > 0 && d && typeof d.get === "function" && typeof d.set === "function") val = obj[k];
else continue;
} catch (_) { continue; }
if (typeof val === "number" && Number.isFinite(val)) {
numProps.push({ k: k, v: val });
} else if (val && (typeof val === "object" || typeof val === "function") && cur.depth < 10) {
if (SKIP_SUBTREE_KEYS.has(k)) continue;
if (typeof val === "function" && cur.depth > 1) continue;
try { if (!visited.has(val)) queue.push({ obj: val, path: cur.depth === 0 ? k : (cur.path + "." + k), depth: cur.depth + 1 }); } catch (_) {}
}
}
if (cur.depth > 0 && numProps.length >= 2) {
if (mode === "group" && gq) {
var matchedEntries = [];
var usedIdx = [];
var okAll = true;
for (var ti = 0; ti < gq.terms.length; ti++) {
var tm = gq.terms[ti];
var foundIdx = -1;
for (var pi = 0; pi < numProps.length; pi++) {
if (usedIdx.indexOf(pi) !== -1) continue;
var epsG = tm.dt === "f32" ? 0.01 : (tm.dt === "f64" || tm.dt === "auto" ? 0.001 : 0);
if (Math.abs(numProps[pi].v - tm.val) <= epsG) { foundIdx = pi; break; }
}
if (foundIdx === -1) { okAll = false; break; }
usedIdx.push(foundIdx);
matchedEntries.push(numProps[foundIdx]);
}
if (okAll) {
for (var mi = 0; mi < matchedEntries.length && out.length < cap; mi++) {
var me = matchedEntries[mi];
out.push({ id: "js:" + cur.path + "." + me.k, kind: "js", objRef: mkRef(obj), key: me.k, dt: Number.isInteger(me.v) ? "i32" : "f64", prev: me.v });
}
}
} else if (mode === "xor") {
var tI = targetI32 | 0;
if (hasExplicitKey) {
var encJ = (tI ^ (explicitKey | 0)) | 0;
for (var xi = 0; xi < numProps.length && out.length < cap; xi++) {
if (Number.isInteger(numProps[xi].v) && (numProps[xi].v | 0) === encJ) {
out.push({ id: "js:" + cur.path + "." + numProps[xi].k + "^0x" + (explicitKey >>> 0).toString(16).toUpperCase(), kind: "js", objRef: mkRef(obj), key: numProps[xi].k, dt: "i32", prev: tI, xorMode: 1, xorKey: explicitKey >>> 0 });
}
}
} else if (numProps.length <= 16) {
for (var a = 0; a < numProps.length && out.length < cap; a++) {
if (!Number.isInteger(numProps[a].v)) continue;
var va = numProps[a].v | 0;
if (va === 0 || va === -1 || va === tI) continue;
var wantK = (va ^ tI) | 0;
if (wantK === 0 || wantK === -1 || wantK === va) continue;
for (var b = 0; b < numProps.length; b++) {
if (a === b || !Number.isInteger(numProps[b].v)) continue;
if ((numProps[b].v | 0) === wantK) {
out.push({
id: "js:" + cur.path + "." + numProps[a].k + "^" + numProps[b].k,
kind: "js", objRef: mkRef(obj), key: numProps[a].k, dt: "i32", prev: tI,
xorMode: 3, xorKey: wantK >>> 0, xorKeyProp: numProps[b].k
});
break;
}
}
}
}
}
}
}
}
function diffUnknownSnapshots(snapObj, sc, v1, v2, out, cap, deadlineMs, stateFlags) {
if (!snapObj || !snapObj.snaps) return;
var skipEqual = (sc === "changed" || sc === "increased" || sc === "decreased" ||
(sc === "increased_by" && v1 > 0) || (sc === "decreased_by" && v1 > 0));
for (var si = 0; si < snapObj.snaps.length && out.length < cap; si++) {
var s = snapObj.snaps[si];
var liveBuf = null;
if (s.kind === "wasm") {
var m = s.memRef ? s.memRef.deref() : null;
if (m && m.buffer) liveBuf = m.buffer;
} else {
var a = s.abRef ? s.abRef.deref() : null;
if (a && a.byteLength > 0) liveBuf = a;
}
if (!liveBuf) continue;
var dt = s.dt || "auto";
var sz = dt === "i8" ? 1 : (dt === "i16" ? 2 : ((dt === "i64" || dt === "f64") ? 8 : 4));
var cmpLen = Math.min(s.len, liveBuf.byteLength);
cmpLen = cmpLen - (cmpLen % sz);
if (cmpLen < sz) continue;
if (dt === "i8" || dt === "i16" || dt === "u32" || dt === "i64" || dt === "f64") {
var oldDv = new DataView(s.snapBuf, 0, cmpLen);
var newDv = new DataView(liveBuf, 0, cmpLen);
for (var off = 0; off + sz <= cmpLen && out.length < cap; off += sz) {
if ((off & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var ov = readViewVal(oldDv, off, dt);
var nv = readViewVal(newDv, off, dt);
if (skipEqual && ov === nv) continue;
if (matchNum(nv, ov, true, dt, sc, v1, v2)) {
out.push({ id: s.labelPrefix + "+0x" + off.toString(16).toUpperCase() + ":" + dt, kind: s.kind, bufIdx: s.bufIdx, memRef: s.memRef, abRef: s.abRef, off: off, dt: dt, len: sz, prev: nv });
}
}
continue;
}
var len4 = cmpLen >>> 2;
var oldI32 = new Int32Array(s.snapBuf, 0, len4);
var newI32 = new Int32Array(liveBuf, 0, len4);
var oldF32 = new Float32Array(s.snapBuf, 0, len4);
var newF32 = new Float32Array(liveBuf, 0, len4);
for (var idx = 0; idx < len4 && out.length < cap; idx++) {
if ((idx & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var oi = oldI32[idx], ni = newI32[idx];
if (skipEqual && oi === ni) continue;
if (dt === "f32") {
var ofv = oldF32[idx], nfv = newF32[idx];
if (matchNum(nfv, ofv, true, "f32", sc, v1, v2)) {
out.push({ id: s.labelPrefix + "+0x" + (idx << 2).toString(16).toUpperCase() + ":f32", kind: s.kind, bufIdx: s.bufIdx, memRef: s.memRef, abRef: s.abRef, off: idx << 2, dt: "f32", len: 4, prev: nfv });
}
continue;
}
if (dt === "i32") {
if (matchNum(ni, oi, true, "i32", sc, v1, v2)) {
out.push({ id: s.labelPrefix + "+0x" + (idx << 2).toString(16).toUpperCase() + ":i32", kind: s.kind, bufIdx: s.bufIdx, memRef: s.memRef, abRef: s.abRef, off: idx << 2, dt: "i32", len: 4, prev: ni });
}
continue;
}
var ofa = oldF32[idx], nfa = newF32[idx];
var looksFloat = Number.isFinite(ofa) && Number.isFinite(nfa) &&
((Math.abs(ofa) >= 0.001 && Math.abs(ofa) <= 1e8) || (Math.abs(nfa) >= 0.001 && Math.abs(nfa) <= 1e8)) &&
(Math.abs(oi) > 100000000 || Math.abs(ni) > 100000000);
if (looksFloat) {
if (matchNum(nfa, ofa, true, "f32", sc, v1, v2)) {
out.push({ id: s.labelPrefix + "+0x" + (idx << 2).toString(16).toUpperCase() + ":f32", kind: s.kind, bufIdx: s.bufIdx, memRef: s.memRef, abRef: s.abRef, off: idx << 2, dt: "f32", len: 4, prev: nfa });
}
} else if (matchNum(ni, oi, true, "i32", sc, v1, v2)) {
out.push({ id: s.labelPrefix + "+0x" + (idx << 2).toString(16).toUpperCase() + ":i32", kind: s.kind, bufIdx: s.bufIdx, memRef: s.memRef, abRef: s.abRef, off: idx << 2, dt: "i32", len: 4, prev: ni });
}
}
}
}
function qualifyId(rawId) {
return IS_TOP ? rawId : ("@" + FRAME_ID + "|" + rawId);
}
function stripOwnFramePrefix(id) {
if (!id || typeof id !== "string") return id;
var pref = "@" + FRAME_ID + "|";
if (id.indexOf(pref) === 0) return id.slice(pref.length);
return id;
}
function isForThisFrame(fullId) {
if (typeof fullId !== "string") return false;
if (fullId.charAt(0) === "@") {
return fullId.indexOf("@" + FRAME_ID + "|") === 0;
}
return IS_TOP;
}
function pushUndoState() {
if (!hits.length && !unknownSnaps) return;
if (undoStack.length >= MAX_UNDO_DEPTH) undoStack.shift();
undoStack.push({ hits: hits.slice(), unknownSnaps: unknownSnaps, lastType: lastType });
}
var UI_PATH_RE = /(?:^|[.\[\]_\-])(node|parent|children|label|string|richtext|bitmaptext|textcontent|innertext|innerhtml|font|color|rgba|alpha|opacity|style|css|matrix|quat|rect|bounds|anchor|width|height|scalex|scaley|scalez|rotation|angle|vertex|uv|canvas|hud|tooltip|sprite|mesh|material|camera|light|shadow|view|render|display|caption|title|text)(?:${'$'}|[.\[\]_\-\d])/i;
var GAME_KEY_RE = /(?:^|[.\[\]_\-])(coin|coins|gold|gem|gems|hp|health|maxhp|life|lives|move|moves|step|steps|score|points|ammo|mana|mp|energy|stamina|star|stars|target|targets|goal|goals|count|level|stage|wave|dmg|damage|atk|attack|def|defense|speed|xp|exp|money|cash|credit|credits|diamond|diamonds|token|tokens|time|timer|cd|cooldown|shield|armor|fuel|power|qty|amount|remain|remaining|left|turn|turns|bomb|booster|hint|hints|heart|hearts|key|keys|item|items|inv|inventory|bag|wallet|bank|balance|ruby|rubies|crystal|crystals|trophy|rank|combo|streak|multiplier|progress)(?:${'$'}|[.\[\]_\-\d])/i;
function classifyHitAuthority(h) {
if (!h) return { role: "game", score: 50 };
if (h.kind === "wasm") return { role: "wasm", score: 100 };
if (h.kind === "ab" || h.kind === "ta") return { role: "buf", score: 92 };
if (h.kind === "ls" || h.kind === "ss" || h.kind === "lsjson" || h.kind === "ssjson") {
return { role: "store", score: GAME_KEY_RE.test(h.id) ? 88 : 76 };
}
var idStr = String(h.id || "");
var keyStr = String(h.key || "");
var isStrDuringNum = (h.dt === "str" && lastType !== "str");
if (isStrDuringNum || UI_PATH_RE.test(idStr) || UI_PATH_RE.test(keyStr)) {
return { role: "ui", score: isStrDuringNum ? 8 : 16 };
}
if (GAME_KEY_RE.test(keyStr)) return { role: "game", score: 96 };
if (GAME_KEY_RE.test(idStr)) return { role: "game", score: 88 };
if (h.kind === "map") return { role: "game", score: 84 };
return { role: "game", score: 78 };
}
function formatHitsResult(maxRet, meta) {
var hideUi = !!(meta && meta.hideUi);
var engs = detectEngines();
var primaryEng = engs.length ? engs[0] : "";
var scored = [];
var uiFilteredOut = 0;
var scanLim = Math.min(hits.length, Math.max((maxRet || 300) * 6, 1500));
for (var si = 0; si < scanLim; si++) {
var hh = hits[si];
var cl = classifyHitAuthority(hh);
if (hideUi && cl.role === "ui") { uiFilteredOut++; continue; }
scored.push({ h: hh, role: cl.role, score: cl.score });
}
scored.sort(function(a, b) { return b.score - a.score; });
var lim = Math.min(scored.length, maxRet || 300);
var items = [];
for (var i = 0; i < lim; i++) {
var sh = scored[i];
var h = sh.h;
    """.trimStart('\n').trimEnd('\n')

    private val JS_PART_6 = """
watchRefs.set(h.id, h);
var rd = readHitCurrent(h);
items.push({
addr: qualifyId(h.id),
val: formatVal(rd.val, h.dt),
prev: formatVal(h.prev, h.dt),
type: h.dt,
kind: h.kind,
role: sh.role,
score: sh.score,
engine: primaryEng
});
}
var totalSlots = unknownSnaps ? (hits.length + unknownSnaps.totalSlots) : hits.length;
return {
ok: true,
frameId: FRAME_ID,
docGen: DOC_GEN,
hookedEarly: HOOKED_EARLY,
freezeStrength: freezeStrength,
total: totalSlots,
uiHiddenCount: uiFilteredOut,
snapshotActive: !!unknownSnaps,
canUndo: undoStack.length > 0,
undoDepth: undoStack.length,
wasmCount: liveWasmEntries().length,
bufCount: abRegistry.length + taRegistry.length,
workerCount: liveWorkerCount(),
skippedXOrigin: skippedXOriginCount,
engines: engs,
elapsedMs: meta && typeof meta.elapsedMs === "number" ? meta.elapsedMs : 0,
timedOut: !!(meta && meta.timedOut),
complete: !(meta && meta.timedOut),
fallbackUsed: !!(meta && meta.fallbackUsed),
items: items
};
}
function resolveJsPathFromWindow(pathStr) {
if (!pathStr || typeof pathStr !== "string") return null;
var raw = pathStr.indexOf("js:") === 0 ? pathStr.slice(3) : pathStr;
if (!raw || raw.indexOf("^") !== -1) return null;
var segs = [];
var cur = "";
for (var i = 0; i < raw.length; i++) {
var ch = raw.charAt(i);
if (ch === ".") { if (cur) { segs.push(cur); cur = ""; } }
else if (ch === "[") { if (cur) { segs.push(cur); cur = ""; } }
else if (ch === "]") { if (cur) { segs.push(cur); cur = ""; } }
else cur += ch;
}
if (cur) segs.push(cur);
if (segs.length === 0) return null;
var obj = window;
for (var s = 0; s < segs.length - 1; s++) {
if (!obj || (typeof obj !== "object" && typeof obj !== "function")) return null;
try { obj = obj[segs[s]]; } catch (_) { return null; }
}
if (!obj || (typeof obj !== "object" && typeof obj !== "function")) return null;
var lastKey = segs[segs.length - 1];
var key = Array.isArray(obj) && /^\d+${'$'}/.test(lastKey) ? Number(lastKey) : lastKey;
if (!(key in obj)) return null;
return { obj: obj, key: key };
}
function handleCmd(cmd) {
internalOpDepth++;
try {
if (cmd.targetFrame && cmd.targetFrame !== FRAME_ID) {
return { ok: false, skippedFrame: true };
}
if (cmd.op === "ping") {
return {
ok: true,
frameId: FRAME_ID,
docGen: DOC_GEN,
hookedEarly: HOOKED_EARLY,
freezeStrength: freezeStrength,
wasmCount: liveWasmEntries().length,
bufCount: abRegistry.length + taRegistry.length,
workerCount: liveWorkerCount(),
engines: detectEngines()
};
}
if (cmd.op === "setFreezeStrength") {
var lvl = Number(cmd.level);
if (Number.isFinite(lvl)) {
freezeStrength = Math.max(0, Math.min(2, Math.round(lvl)));
frozenMap.forEach(function(fe) {
if (!fe.trapInstalled && fe.rec && (fe.rec.kind === "wasm" || fe.rec.kind === "ta" || fe.rec.kind === "ab")) {
fe.mode = activeBinaryModeLabel(!!fe.rec.xorMode);
}
});
if (frozenMap.size > 0) { stopFreezeLoops(); startFreezeLoops(); }
}
return { ok: true, frameId: FRAME_ID, freezeStrength: freezeStrength };
}
if (cmd.op === "findMirrors") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var locMir = stripOwnFramePrefix(cmd.id);
var recMir = watchRefs.get(locMir);
if (!recMir) return { ok: false, error: "Entry not found in frame" };
var rdMir = readHitCurrent(recMir);
if (!rdMir.ok) return { ok: false, error: "Target unreadable" };
var mVal = Number(cleanNumStr(rdMir.val));
if (!Number.isFinite(mVal)) return { ok: false, error: "Mirrors requires a numeric value" };
var mOut = [];
var mSeen = new WeakSet();
var mDead = Date.now() + 6000;
var mFlags = { timedOut: false };
var mCap = 400;
var mWasm = liveWasmEntries();
for (var mwi = 0; mwi < mWasm.length && mOut.length < mCap; mwi++) {
var mwb = mWasm[mwi].mem.buffer;
mSeen.add(mwb);
scanBuffer(mwb, 0, mwb.byteLength, "wasm", mWasm[mwi].id, "wasm#" + mWasm[mwi].id, "auto", "exact", mVal, 0, "", { bytes: [], mask: [] }, mOut, mCap, mWasm[mwi].ref, mDead, mFlags);
}
collectJsAndTypedArrays("auto", "exact", mVal, 0, "", { bytes: [], mask: [] }, mOut, mCap, mSeen, mDead, mFlags);
var mItems = [];
var mEng = detectEngines();
var mPrim = mEng.length ? mEng[0] : "";
for (var mri = 0; mri < mOut.length; mri++) {
var mh = mOut[mri];
if (mh.id === locMir) continue;
var mcl = classifyHitAuthority(mh);
if (mcl.role === "ui") continue;
watchRefs.set(mh.id, mh);
mItems.push({
addr: qualifyId(mh.id),
val: formatVal(mh.prev, mh.dt),
type: mh.dt,
kind: mh.kind,
role: mcl.role,
score: mcl.score,
engine: mPrim
});
}
mItems.sort(function(a, b) { return b.score - a.score; });
return { ok: true, frameId: FRAME_ID, count: Math.min(mItems.length, 60), items: mItems.slice(0, 60) };
}
if (cmd.op === "reset") {
hits = []; undoStack = []; unknownSnaps = null;
if (cmd.clearFreezes) { clearAllFreezes(); clearAllTraces(); }
return formatHitsResult(0, { elapsedMs: 0, timedOut: false });
}
if (cmd.op === "refreshHits") {
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0, hideUi: !!cmd.hideUi });
}
if (cmd.op === "undo") {
if (!undoStack.length) return { ok: false, error: "Nothing to undo" };
var prevState = undoStack.pop();
hits = prevState.hits || [];
unknownSnaps = prevState.unknownSnaps || null;
lastType = prevState.lastType || lastType;
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0, hideUi: !!cmd.hideUi });
}
if (cmd.op === "writeAll") {
var valAll = String(cmd.val || "");
var limW = Math.min(hits.length, cmd.maxWrite || 150);
var wrote = 0;
for (var wi = 0; wi < limW; wi++) {
if (writeHitValue(hits[wi], valAll)) wrote++;
}
var resAll = formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0 });
resAll.wrote = wrote;
return resAll;
}
if (cmd.op === "writeBatch") {
var entriesW = Array.isArray(cmd.entries) ? cmd.entries : [];
var wroteB = 0;
var valsW = {};
for (var wbi = 0; wbi < entriesW.length; wbi++) {
var ew = entriesW[wbi];
if (!ew || !isForThisFrame(ew.id)) continue;
var locW = stripOwnFramePrefix(ew.id);
var rW = watchRefs.get(locW);
if (rW && writeHitValue(rW, ew.val)) {
wroteB++;
var rdWb = readHitCurrent(rW);
valsW[ew.id] = formatVal(rdWb.val, rW.dt);
}
}
return { ok: true, frameId: FRAME_ID, wrote: wroteB, values: valsW };
}
if (cmd.op === "setFreeze") {
if (typeof cmd.strength === "number" && Number.isFinite(cmd.strength)) {
var nl = Math.max(0, Math.min(2, Math.round(cmd.strength)));
if (nl !== freezeStrength) { freezeStrength = nl; if (frozenMap.size > 0) { stopFreezeLoops(); startFreezeLoops(); } }
}
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var fr = installFreezeEntry(stripOwnFramePrefix(cmd.id), cmd.val, cmd.rule || "eq");
fr.frameId = FRAME_ID;
return fr;
}
if (cmd.op === "freezeBatch") {
if (typeof cmd.strength === "number" && Number.isFinite(cmd.strength)) {
var nlB = Math.max(0, Math.min(2, Math.round(cmd.strength)));
if (nlB !== freezeStrength) { freezeStrength = nlB; if (frozenMap.size > 0) { stopFreezeLoops(); startFreezeLoops(); } }
}
var entriesF = Array.isArray(cmd.entries) ? cmd.entries : [];
var locked = 0;
var lockedIds = [];
var failedIds = [];
for (var fbi = 0; fbi < entriesF.length; fbi++) {
var ef = entriesF[fbi];
if (!ef || !isForThisFrame(ef.id)) continue;
var rF = installFreezeEntry(stripOwnFramePrefix(ef.id), ef.val, ef.rule || "eq");
if (rF.ok) {
locked++;
lockedIds.push(ef.id);
} else {
failedIds.push(ef.id);
}
}
return { ok: locked > 0, frameId: FRAME_ID, locked: locked, lockedIds: lockedIds, failedIds: failedIds, freezeStats: collectFreezeStatsMap() };
}
if (cmd.op === "unfreeze") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
removeFreezeEntry(stripOwnFramePrefix(cmd.id));
return { ok: true, frameId: FRAME_ID };
}
if (cmd.op === "unfreezeBatch") {
var idsU = Array.isArray(cmd.ids) ? cmd.ids : [];
for (var ubi = 0; ubi < idsU.length; ubi++) {
if (isForThisFrame(idsU[ubi])) removeFreezeEntry(stripOwnFramePrefix(idsU[ubi]));
}
return { ok: true, frameId: FRAME_ID };
}
if (cmd.op === "unfreezeAll") {
clearAllFreezes();
return { ok: true, frameId: FRAME_ID };
}
if (cmd.op === "setTrace") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var tr = installTraceEntry(stripOwnFramePrefix(cmd.id), !!cmd.enabled);
tr.frameId = FRAME_ID;
return tr;
}
if (cmd.op === "getTraceLogs") {
return { ok: true, frameId: FRAME_ID, traces: collectTraceLogsMap() };
}
if (cmd.op === "clearTrace") {
if (cmd.id) {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var tOnly = traceMap.get(stripOwnFramePrefix(cmd.id));
if (tOnly) { tOnly.events = []; tOnly.writeCount = 0; }
} else {
clearAllTraces();
}
return { ok: true, frameId: FRAME_ID };
}
if (cmd.op === "reattach") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var locR = stripOwnFramePrefix(cmd.id);
var existingR = watchRefs.get(locR);
if (existingR) {
var rdEx = readHitCurrent(existingR);
if (rdEx.ok) return { ok: true, frameId: FRAME_ID, val: formatVal(rdEx.val, existingR.dt) };
}
var dtR = cmd.dt || "i32";
if (locR.indexOf("js:") === 0) {
var resJ = resolveJsPathFromWindow(locR);
if (!resJ) return { ok: false, frameId: FRAME_ID, error: "JS path not found" };
var vJ = resJ.obj[resJ.key];
var recJ = { id: locR, kind: "js", objRef: mkRef(resJ.obj), key: resJ.key, dt: dtR, prev: vJ };
watchRefs.set(locR, recJ);
return { ok: true, frameId: FRAME_ID, val: formatVal(vJ, dtR) };
}
if (locR.indexOf("ls:") === 0 || locR.indexOf("ss:") === 0) {
var tagR = locR.slice(0, 2);
var bodyR = locR.slice(3);
var stR = tagR === "ls" ? window.localStorage : window.sessionStorage;
var jPos = bodyR.indexOf("${'$'}.");
var recS = null;
if (jPos !== -1) {
var kName = bodyR.slice(0, jPos);
var jp = bodyR.slice(jPos + 2).split(".");
recS = { id: locR, kind: tagR + "json", key: kName, jpath: jp, dt: dtR, prev: 0 };
} else {
recS = { id: locR, kind: tagR, key: bodyR, dt: dtR, prev: 0 };
}
var rdS = readHitCurrent(recS);
if (!rdS.ok) return { ok: false, frameId: FRAME_ID, error: "Storage key missing" };
recS.prev = rdS.val;
watchRefs.set(locR, recS);
return { ok: true, frameId: FRAME_ID, val: formatVal(rdS.val, dtR) };
}
var wm = /^wasm#(\d+)\+0x([0-9a-fA-F]+):([a-z0-9]+)${'$'}/.exec(locR);
if (wm) {
var wid = Number(wm[1]), woff = parseInt(wm[2], 16), wdt = wm[3];
var liveM = liveWasmEntries();
var foundM = null;
for (var mi = 0; mi < liveM.length; mi++) { if (liveM[mi].id === wid) { foundM = liveM[mi]; break; } }
if (!foundM || woff >= foundM.mem.buffer.byteLength) return { ok: false, frameId: FRAME_ID, error: "WASM offset invalid" };
var recW = { id: locR, kind: "wasm", bufIdx: wid, memRef: foundM.ref, abRef: null, off: woff, dt: wdt, len: 4, prev: 0, scale: 0 };
var rdWm = readHitCurrent(recW);
if (!rdWm.ok) return { ok: false, frameId: FRAME_ID, error: "WASM unreadable" };
recW.prev = rdWm.val;
watchRefs.set(locR, recW);
return { ok: true, frameId: FRAME_ID, val: formatVal(rdWm.val, wdt) };
}
return { ok: false, frameId: FRAME_ID, error: "Cannot safely reattach target" };
}
if (cmd.op === "firstScan") {
var tStart = Date.now();
var budgetMs = Math.max(1000, Math.min(80000, Number(cmd.timeoutMs) || 15000));
var deadlineMs = tStart + budgetMs;
var stateFlags = { timedOut: false, fallbackUsed: false };
undoStack = []; hits = []; abRegistry = []; unknownSnaps = null;
skippedXOriginCount = 0;
var dt = cmd.dtype || "auto";
var sc = cmd.scanType || "exact";
lastType = dt;
var out = [];
var scannedBufs = new WeakSet();
if (sc === "group") {
var gq = parseGroupQuery(cmd.val1);
if (!gq) return { ok: false, error: "Invalid group syntax (e.g. 100;200;50::64 or 100;200:32)" };
var capG = 100000;
var memsG = liveWasmEntries();
for (var gmi = 0; gmi < memsG.length && out.length < capG && !stateFlags.timedOut; gmi++) {
var gmb = memsG[gmi].mem.buffer;
scannedBufs.add(gmb);
scanBufferGroup(gmb, 0, gmb.byteLength, "wasm", memsG[gmi].id, "wasm#" + memsG[gmi].id, gq, out, capG, memsG[gmi].ref, deadlineMs, stateFlags);
}
if (!stateFlags.timedOut) scanJsSpecial("group", gq, 0, false, 0, out, capG, scannedBufs, deadlineMs, stateFlags);
var rawG = liveRawBuffers(scannedBufs);
for (var gbi = 0; gbi < rawG.length && out.length < capG && !stateFlags.timedOut; gbi++) {
scannedBufs.add(rawG[gbi]);
var giIdx = abRegistry.length; var giRef = mkRef(rawG[gbi]); abRegistry.push(giRef);
scanBufferGroup(rawG[gbi], 0, rawG[gbi].byteLength, "ab", giIdx, "buf#" + giIdx, gq, out, capG, giRef, deadlineMs, stateFlags);
}
hits = out;
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: Date.now() - tStart, timedOut: stateFlags.timedOut, hideUi: !!cmd.hideUi });
}
if (sc === "xor") {
var xs1 = cleanNumStr(cmd.val1);
var xs2 = cleanNumStr(cmd.val2);
var xv1 = Number(xs1);
if (!xs1.trim() || !Number.isFinite(xv1)) return { ok: false, error: "Enter target integer value for XOR scan" };
var hasKey = xs2.trim() !== "";
var keyVal = 0;
if (hasKey) {
var kn = Number(xs2);
if (!Number.isFinite(kn)) return { ok: false, error: "Invalid XOR key (use number or 0xHEX)" };
keyVal = kn >>> 0;
}
var capX = 100000;
var memsX = liveWasmEntries();
for (var xmi = 0; xmi < memsX.length && out.length < capX && !stateFlags.timedOut; xmi++) {
var xmb = memsX[xmi].mem.buffer;
scannedBufs.add(xmb);
scanBufferXor(xmb, 0, xmb.byteLength, "wasm", memsX[xmi].id, "wasm#" + memsX[xmi].id, xv1 | 0, hasKey, keyVal, out, capX, memsX[xmi].ref, deadlineMs, stateFlags);
}
if (!stateFlags.timedOut) scanJsSpecial("xor", null, xv1 | 0, hasKey, keyVal, out, capX, scannedBufs, deadlineMs, stateFlags);
var rawX = liveRawBuffers(scannedBufs);
for (var xbi = 0; xbi < rawX.length && out.length < capX && !stateFlags.timedOut; xbi++) {
scannedBufs.add(rawX[xbi]);
var xiIdx = abRegistry.length; var xiRef = mkRef(rawX[xbi]); abRegistry.push(xiRef);
scanBufferXor(rawX[xbi], 0, rawX[xbi].byteLength, "ab", xiIdx, "buf#" + xiIdx, xv1 | 0, hasKey, keyVal, out, capX, xiRef, deadlineMs, stateFlags);
}
hits = out;
lastType = "i32";
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: Date.now() - tStart, timedOut: stateFlags.timedOut, hideUi: !!cmd.hideUi });
}
var s1 = (dt === "str" || dt === "aob") ? String(cmd.val1 || "") : cleanNumStr(cmd.val1);
var s2 = (dt === "str" || dt === "aob") ? String(cmd.val2 || "") : cleanNumStr(cmd.val2);
var v1 = Number(s1), v2 = Number(s2);
var aob = dt === "aob" ? parseAob(s1) : { bytes: [], mask: [] };
if (dt === "str" && !s1) return { ok: false, error: "Enter text to search" };
if (dt === "aob" && !aob.bytes.length) return { ok: false, error: "Enter hex bytes (e.g. 48 8B ?? 01)" };
if (dt !== "str" && dt !== "aob" && sc !== "unknown") {
if (dt === "auto" && (!s1.trim() || !Number.isFinite(v1))) {
dt = "str"; lastType = "str";
} else if (!s1.trim() || !Number.isFinite(v1)) {
return { ok: false, error: "Enter a valid number" };
}
if (sc === "between") {
if (!s2.trim() || !Number.isFinite(v2)) return { ok: false, error: "Enter max value" };
if (v2 < v1) { var tmp = v1; v1 = v2; v2 = tmp; }
}
}
var cap = sc === "unknown" ? 60000 : 200000;
if (sc === "unknown" && dt !== "str" && dt !== "aob") {
unknownSnaps = captureUnknownSnapshots(dt, deadlineMs, stateFlags);
var memsU = liveWasmEntries();
for (var umi = 0; umi < memsU.length; umi++) scannedBufs.add(memsU[umi].mem.buffer);
var rawU = liveRawBuffers(null);
for (var ubi2 = 0; ubi2 < rawU.length; ubi2++) scannedBufs.add(rawU[ubi2]);
collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, 30000, scannedBufs, deadlineMs, stateFlags);
scanStorage(dt, sc, v1, v2, s1, out, 30000);
hits = out;
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: Date.now() - tStart, timedOut: stateFlags.timedOut, hideUi: !!cmd.hideUi });
}
var mems = liveWasmEntries();
for (var mi = 0; mi < mems.length && out.length < cap && !stateFlags.timedOut; mi++) {
var mb = mems[mi].mem.buffer;
scannedBufs.add(mb);
scanBuffer(mb, 0, mb.byteLength, "wasm", mems[mi].id, "wasm#" + mems[mi].id, dt, sc, v1, v2, s1, aob, out, cap, mems[mi].ref, deadlineMs, stateFlags);
}
if (!stateFlags.timedOut) {
collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, cap, scannedBufs, deadlineMs, stateFlags);
}
var memsAfter = liveWasmEntries();
for (var mi2 = 0; mi2 < memsAfter.length && out.length < cap && !stateFlags.timedOut; mi2++) {
var mb2 = memsAfter[mi2].mem.buffer;
if (!scannedBufs.has(mb2)) {
scannedBufs.add(mb2);
scanBuffer(mb2, 0, mb2.byteLength, "wasm", memsAfter[mi2].id, "wasm#" + memsAfter[mi2].id, dt, sc, v1, v2, s1, aob, out, cap, memsAfter[mi2].ref, deadlineMs, stateFlags);
}
}
var rawBufs = liveRawBuffers(scannedBufs);
for (var bi = 0; bi < rawBufs.length && out.length < cap && !stateFlags.timedOut; bi++) {
registerAndScanFullBuffer(rawBufs[bi], "", dt, sc, v1, v2, s1, aob, out, cap, scannedBufs, deadlineMs, stateFlags);
}
if (!stateFlags.timedOut) {
scanStorage(dt, sc, v1, v2, s1, out, cap);
}
if (out.length === 0 && dt === "auto" && sc === "exact" && Number.isInteger(v1) && v1 !== 0 && !stateFlags.timedOut) {
stateFlags.fallbackUsed = true;
var fbCap = 20000;
for (var fmi = 0; fmi < memsAfter.length && out.length < fbCap && !stateFlags.timedOut; fmi++) {
var fmb = memsAfter[fmi].mem.buffer;
scanBufferFallback(fmb, 0, fmb.byteLength, "wasm", memsAfter[fmi].id, "wasm#" + memsAfter[fmi].id, v1, out, fbCap, memsAfter[fmi].ref, deadlineMs, stateFlags);
}
for (var fai = 0; fai < abRegistry.length && out.length < fbCap && !stateFlags.timedOut; fai++) {
var fab = abRegistry[fai].deref();
if (fab && fab.byteLength >= 8) {
scanBufferFallback(fab, 0, fab.byteLength, "ab", fai, "buf#" + fai, v1, out, fbCap, abRegistry[fai], deadlineMs, stateFlags);
}
}
}
hits = out;
return formatHitsResult(cmd.maxReturn || 300, {
elapsedMs: Date.now() - tStart,
timedOut: stateFlags.timedOut,
fallbackUsed: stateFlags.fallbackUsed,
hideUi: !!cmd.hideUi
});
}
if (cmd.op === "nextScan") {
var tStart2 = Date.now();
var budgetMs2 = Math.max(1000, Math.min(80000, Number(cmd.timeoutMs) || 15000));
var deadlineMs2 = tStart2 + budgetMs2;
var stateFlags2 = { timedOut: false };
if (!hits.length && !unknownSnaps) {
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0, hideUi: !!cmd.hideUi });
}
var sc2 = cmd.scanType || "exact";
var s1n = (lastType === "str" || lastType === "aob") ? String(cmd.val1 || "") : cleanNumStr(cmd.val1);
var s2n = (lastType === "str" || lastType === "aob") ? String(cmd.val2 || "") : cleanNumStr(cmd.val2);
var v1n = Number(s1n), v2n = Number(s2n);
if (sc2 === "unknown") {
sc2 = (s1n.trim() !== "" && Number.isFinite(v1n)) ? "exact" : "changed";
} else if (sc2 === "xor") {
sc2 = "exact";
} else if (sc2 === "group") {
sc2 = "changed";
}
var needsV1 = (sc2 === "exact" || sc2 === "bigger" || sc2 === "smaller" || sc2 === "between" || sc2 === "increased_by" || sc2 === "decreased_by");
if (lastType !== "str" && lastType !== "aob" && needsV1 && (!s1n.trim() || !Number.isFinite(v1n))) {
return { ok: false, error: "Enter the new number (or pick Changed / Inc / Dec)" };
}
if (sc2 === "between" && (!s2n.trim() || !Number.isFinite(v2n))) {
return { ok: false, error: "Enter max value" };
}
if (sc2 === "between" && v2n < v1n) { var t2 = v1n; v1n = v2n; v2n = t2; }
pushUndoState();
var next = [];
if (unknownSnaps) {
diffUnknownSnapshots(unknownSnaps, sc2, v1n, v2n, next, 150000, deadlineMs2, stateFlags2);
unknownSnaps = null;
}
for (var i = 0; i < hits.length; i++) {
if ((i & 4095) === 0 && Date.now() >= deadlineMs2) { stateFlags2.timedOut = true; break; }
var h = hits[i];
var rd = readHitCurrent(h);
if (!rd.ok) continue;
if (h.dt === "str") {
if (sc2 === "changed" ? (rd.val !== h.prev) : (sc2 === "unchanged" ? (rd.val === h.prev) : String(rd.val).indexOf(s1n) !== -1)) {
h.prev = rd.val; next.push(h);
}
} else if (h.dt === "aob") {
if (sc2 === "changed" ? (rd.val !== h.prev) : (sc2 === "unchanged" ? (rd.val === h.prev) : true)) {
h.prev = rd.val; next.push(h);
}
} else {
if (matchNum(rd.val, h.prev, true, h.dt, sc2, v1n, v2n)) {
h.prev = rd.val; next.push(h);
}
}
}
hits = next;
return formatHitsResult(cmd.maxReturn || 300, {
elapsedMs: Date.now() - tStart2,
timedOut: stateFlags2.timedOut,
hideUi: !!cmd.hideUi
});
}
if (cmd.op === "readBatch") {
var ids = Array.isArray(cmd.ids) ? cmd.ids : [];
var vals = {};
for (var ri = 0; ri < ids.length; ri++) {
var fullId = ids[ri];
if (!isForThisFrame(fullId)) continue;
var localId = stripOwnFramePrefix(fullId);
var rec = watchRefs.get(localId);
if (rec) {
var r = readHitCurrent(rec);
vals[fullId] = r.ok ? formatVal(r.val, rec.dt) : String(r.val);
}
}
return { ok: true, frameId: FRAME_ID, docGen: DOC_GEN, hookedEarly: HOOKED_EARLY, values: vals, freezeStats: collectFreezeStatsMap(), traces: collectTraceLogsMap() };
}
if (cmd.op === "write") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var localW = stripOwnFramePrefix(cmd.id);
var recW = watchRefs.get(localW);
if (!recW) return { ok: false, error: "Entry not found in frame" };
var okW = writeHitValue(recW, cmd.val);
var rdW = readHitCurrent(recW);
return { ok: okW, frameId: FRAME_ID, val: formatVal(rdW.val, recW.dt) };
}
if (cmd.op === "hexDump") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var localH = stripOwnFramePrefix(cmd.id);
var recH = watchRefs.get(localH);
if (!recH || (recH.kind !== "wasm" && recH.kind !== "ta" && recH.kind !== "ab")) {
return { ok: false, error: "Hex view supports WASM / ArrayBuffer / TypedArray" };
}
var biH = getBufferForHit(recH);
if (!biH) return { ok: false, error: "Buffer detached" };
var baseOff = Math.max(0, Math.min(biH.byteLen - 16, (typeof cmd.offset === "number" ? cmd.offset : recH.off)));
var len = Math.min(256, Math.max(16, Math.min(cmd.len || 128, biH.byteLen - baseOff)));
var u8h = new Uint8Array(biH.buf, biH.byteOff + baseOff, len);
var hex = "";
for (var hi = 0; hi < u8h.length; hi++) hex += u8h[hi].toString(16).toUpperCase().padStart(2, "0");
return { ok: true, frameId: FRAME_ID, addr: "0x" + baseOff.toString(16).toUpperCase(), offset: baseOff, len: len, hex: hex };
}
if (cmd.op === "writeHex") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var localWH = stripOwnFramePrefix(cmd.id);
var recWH = watchRefs.get(localWH);
    """.trimStart('\n').trimEnd('\n')

    private val JS_PART_7 = """
if (!recWH || (recWH.kind !== "wasm" && recWH.kind !== "ta" && recWH.kind !== "ab")) {
return { ok: false, error: "Hex write supports WASM / ArrayBuffer / TypedArray" };
}
var biWH = getBufferForHit(recWH);
if (!biWH) return { ok: false, error: "Buffer detached" };
var patWH = parseAob(cmd.hex || "");
if (!patWH.bytes.length || patWH.bytes.length > 64) return { ok: false, error: "Enter 1..64 hex bytes" };
var offWH = typeof cmd.offset === "number" ? Math.max(0, cmd.offset) : recWH.off;
if (offWH + patWH.bytes.length > biWH.byteLen) return { ok: false, error: "Hex write out of buffer bounds" };
var u8w = new Uint8Array(biWH.buf, biWH.byteOff + offWH, patWH.bytes.length);
var patchedW = 0;
selfWriteDepth++;
try {
for (var wi2 = 0; wi2 < patWH.bytes.length; wi2++) {
if (!patWH.mask[wi2]) continue;
if (biWH.shared && typeof Atomics === "object") {
Atomics.store(u8w, wi2, patWH.bytes[wi2]);
} else {
u8w[wi2] = patWH.bytes[wi2];
}
patchedW++;
}
} finally {
selfWriteDepth--;
}
var rbHex = "";
for (var ri2 = 0; ri2 < u8w.length; ri2++) rbHex += u8w[ri2].toString(16).toUpperCase().padStart(2, "0");
return { ok: true, frameId: FRAME_ID, wrote: patchedW, offset: offWH, hex: rbHex };
}
if (cmd.op === "pointerScan") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var localPtr = stripOwnFramePrefix(cmd.id);
var recPtr = watchRefs.get(localPtr);
if (!recPtr) return { ok: false, error: "Entry not found in frame" };
var maxOffP = Math.max(0, Math.min(65536, Number(cmd.maxOffset) || 4096));
var pItems = [];
if (recPtr.kind === "wasm" || recPtr.kind === "ab" || recPtr.kind === "ta") {
var biPtr = getBufferForHit(recPtr);
if (!biPtr) return { ok: false, error: "Buffer detached" };
var targetOff = recPtr.off >>> 0;
var loOff = targetOff > maxOffP ? (targetOff - maxOffP) : 0;
var u32p = new Uint32Array(biPtr.buf, biPtr.byteOff, biPtr.byteLen >>> 2);
var prefP = recPtr.id.split("+0x")[0];
for (var pi = 0; pi < u32p.length && pItems.length < 120; pi++) {
var pval = u32p[pi];
if (pval >= loOff && pval <= targetOff && (pval & 3) === 0 && (pi << 2) !== targetOff) {
var pOff = pi << 2;
var pLocalId = prefP + "+0x" + pOff.toString(16).toUpperCase() + ":u32";
if (!watchRefs.has(pLocalId)) {
watchRefs.set(pLocalId, { id: pLocalId, kind: recPtr.kind, bufIdx: recPtr.bufIdx, memRef: recPtr.memRef, abRef: recPtr.abRef, off: pOff, dt: "u32", len: 4, prev: pval, scale: 0 });
}
pItems.push({
ptrAddr: qualifyId(pLocalId),
pointsTo: "0x" + pval.toString(16).toUpperCase(),
offset: "+0x" + (targetOff - pval).toString(16).toUpperCase(),
region: prefP
});
}
}
return { ok: true, frameId: FRAME_ID, count: pItems.length, items: pItems };
}
if (recPtr.kind === "js") {
var targetObj = recPtr.objRef ? recPtr.objRef.deref() : null;
if (!targetObj) return { ok: false, error: "Object garbage-collected" };
var visP = new WeakSet();
var qP = [{ obj: window, path: "window", depth: 0 }];
var qH = 0, visN = 0;
while (qH < qP.length && visN < 12000 && pItems.length < 60) {
var cP = qP[qH++];
var oP = cP.obj;
if (!oP || (typeof oP !== "object" && typeof oP !== "function")) continue;
try { if (visP.has(oP)) continue; visP.add(oP); } catch (_) { continue; }
visN++;
if (cP.depth > 0 && isSkippedBuiltinObject(oP)) continue;
var ksP;
try { ksP = cP.depth <= 2 ? Object.getOwnPropertyNames(oP) : Object.keys(oP); } catch (_) { continue; }
for (var kpi = 0; kpi < Math.min(ksP.length, 250) && pItems.length < 60; kpi++) {
var kk = ksP[kpi];
if (SKIP_KEYS.has(kk) || INTERNAL_KEY_RE.test(kk)) continue;
var vv;
try { vv = oP[kk]; } catch (_) { continue; }
var fullP = cP.depth === 0 ? kk : (cP.path + "." + kk);
if (vv === targetObj) {
pItems.push({ ptrAddr: "js:" + fullP, pointsTo: recPtr.id, offset: "." + String(recPtr.key), region: "JS Ref" });
} else if (vv && typeof vv === "object" && cP.depth < 7 && !SKIP_SUBTREE_KEYS.has(kk)) {
try { if (!visP.has(vv)) qP.push({ obj: vv, path: fullP, depth: cP.depth + 1 }); } catch (_) {}
}
}
}
return { ok: true, frameId: FRAME_ID, count: pItems.length, items: pItems };
}
return { ok: false, error: "Pointer scan supports WASM / Buffer / JS objects" };
}
if (cmd.op === "peekStruct") {
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var localP = stripOwnFramePrefix(cmd.id);
var recP = watchRefs.get(localP);
if (!recP) return { ok: false, error: "Entry not found in frame" };
if (recP.kind === "js") {
var objP = recP.objRef ? recP.objRef.deref() : null;
if (!objP) return { ok: false, error: "Object garbage-collected" };
var jsFields = [];
var ksObj = [];
try { ksObj = Object.getOwnPropertyNames(objP); } catch (_) { try { ksObj = Object.keys(objP); } catch (_2) {} }
var parentBase = recP.id.slice(0, Math.max(3, recP.id.lastIndexOf(".")));
for (var ji = 0; ji < Math.min(ksObj.length, 64); ji++) {
var jk = ksObj[ji];
if (INTERNAL_KEY_RE.test(jk) || SENSITIVE_KEY_RE.test(jk)) continue;
var jv;
try { jv = objP[jk]; } catch (_) { continue; }
if (typeof jv === "number" || (typeof jv === "string" && jv.length <= 48)) {
var jdt = typeof jv === "number" ? (Number.isInteger(jv) ? "i32" : "f64") : "str";
var jLocalId = parentBase + "." + jk;
if (!watchRefs.has(jLocalId)) {
watchRefs.set(jLocalId, { id: jLocalId, kind: "js", objRef: mkRef(objP), key: jk, dt: jdt, prev: jv });
}
jsFields.push({
addr: qualifyId(jLocalId),
delta: "." + jk,
type: jdt,
val: formatVal(jv, jdt),
i32: typeof jv === "number" ? String(Math.round(jv)) : jv,
u32: typeof jv === "number" ? String(jv >>> 0) : "",
f32: typeof jv === "number" ? formatVal(jv, "f64") : ""
});
}
}
return { ok: true, frameId: FRAME_ID, fields: jsFields };
}
if (recP.kind !== "wasm" && recP.kind !== "ta" && recP.kind !== "ab") {
return { ok: false, error: "Struct peek supports WASM / Buffer / JS" };
}
var biP = getBufferForHit(recP);
if (!biP) return { ok: false, error: "Buffer detached" };
var rad = Math.max(32, Math.min(256, Number(cmd.radius) || 64)) & ~3;
var baseP = recP.off & ~3;
var startP = Math.max(0, baseP - rad);
var endP = Math.min(biP.byteLen, baseP + rad + 4) & ~3;
var dvP = new DataView(biP.buf, biP.byteOff, biP.byteLen);
var fields = [];
var lblPref = recP.id.split("+0x")[0];
for (var po = startP; po + 4 <= endP; po += 4) {
var delta = po - baseP;
var iv = dvP.getInt32(po, true);
var uv = dvP.getUint32(po, true);
var fv = dvP.getFloat32(po, true);
if (delta !== 0 && iv === 0) continue;
var looksF = Number.isFinite(fv) && Math.abs(fv) >= 0.001 && Math.abs(fv) <= 1e7 && Math.abs(iv) > 100000000;
var fType = looksF ? "f32" : "i32";
var fLocalId = lblPref + "+0x" + po.toString(16).toUpperCase() + ":" + fType;
if (!watchRefs.has(fLocalId)) {
watchRefs.set(fLocalId, {
id: fLocalId, kind: recP.kind, bufIdx: recP.bufIdx, memRef: recP.memRef, abRef: recP.abRef,
off: po, dt: fType, len: 4, prev: looksF ? fv : iv, scale: 0
});
}
fields.push({
addr: qualifyId(fLocalId),
delta: (delta < 0 ? "-0x" : "+0x") + Math.abs(delta).toString(16).toUpperCase(),
type: fType,
val: looksF ? formatVal(fv, "f32") : String(iv),
i32: String(iv),
u32: String(uv),
f32: (Number.isFinite(fv) && Math.abs(fv) <= 1e9) ? formatVal(fv, "f32") : "NaN"
});
}
return { ok: true, frameId: FRAME_ID, fields: fields };
}
if (cmd.op === "regions") {
var memsR = liveWasmEntries();
var regs = [];
var totalKb = 0;
var fTag = IS_TOP ? "" : ("[" + FRAME_ID + "] ");
var engTag = detectEngines().join("/");
var prefixLbl = engTag ? (fTag + engTag + " · ") : fTag;
for (var rmi = 0; rmi < memsR.length; rmi++) {
var kb = Math.round(memsR[rmi].mem.buffer.byteLength / 1024);
totalKb += kb;
var isSh = isSharedBuf(memsR[rmi].mem.buffer);
regs.push({ start: qualifyId("wasm#" + memsR[rmi].id), end: "0x" + memsR[rmi].mem.buffer.byteLength.toString(16).toUpperCase(), kb: kb, perms: isSh ? "rw-sab-wasm" : "rw-wasm", label: prefixLbl + "WebAssembly.Memory #" + memsR[rmi].id });
}
var seenR = new WeakSet();
for (var rmi2 = 0; rmi2 < memsR.length; rmi2++) seenR.add(memsR[rmi2].mem.buffer);
var rawR = liveRawBuffers(seenR);
for (var rbi = 0; rbi < Math.min(rawR.length, 60); rbi++) {
var bkb = Math.max(1, Math.round(rawR[rbi].byteLength / 1024));
totalKb += bkb;
var shB = isSharedBuf(rawR[rbi]);
regs.push({ start: qualifyId("buf#" + rbi), end: "0x" + rawR[rbi].byteLength.toString(16).toUpperCase(), kb: bkb, perms: shB ? "rw-sab" : "rw-buf", label: prefixLbl + (shB ? "SharedArrayBuffer #" : "ArrayBuffer #") + rbi });
}
for (var tai = 0; tai < Math.min(taRegistry.length, 40); tai++) {
var taObj = taRegistry[tai].deref();
if (taObj) {
try {
var tLen = taObj.byteLength;
var tkb = Math.max(1, Math.round(tLen / 1024));
var tName = "TypedArray";
try { tName = taObj.constructor && taObj.constructor.name ? taObj.constructor.name : "TypedArray"; } catch (_) {}
totalKb += tkb;
regs.push({ start: qualifyId("ta#" + tai), end: "0x" + tLen.toString(16).toUpperCase(), kb: tkb, perms: "rw-ta", label: prefixLbl + tName });
} catch (_) {}
}
}
return { ok: true, frameId: FRAME_ID, count: regs.length, totalKb: totalKb, workerCount: liveWorkerCount(), engines: detectEngines(), regions: regs };
}
return { ok: false, error: "Unknown op" };
} catch (e) {
return { ok: false, error: String(e && e.message ? e.message : e) };
} finally {
internalOpDepth--;
}
}
function relayToChildFrames(packet) {
try {
var fc = window.frames ? window.frames.length : 0;
for (var i = 0; i < fc && i < 32; i++) {
try { window.frames[i].postMessage(packet, "*"); } catch (_) {}
}
} catch (_) {}
}
function emitFrameAck(reqId, childCount) {
try {
if (window.FableHybridBridge && typeof window.FableHybridBridge.onFrameMemAck === "function") {
window.FableHybridBridge.onFrameMemAck(String(reqId), FRAME_ID, childCount | 0);
} else if (!IS_TOP && window.top) {
window.top.postMessage({ __mch: CH, __mack: String(reqId), __mfid: FRAME_ID, __mc: childCount | 0 }, "*");
}
} catch (_) {}
}
function emitFrameResult(reqId, out) {
try {
var payload = JSON.stringify(out);
if (window.FableHybridBridge && typeof window.FableHybridBridge.onFrameMemResult === "function") {
window.FableHybridBridge.onFrameMemResult(String(reqId), FRAME_ID, payload);
} else if (!IS_TOP && window.top) {
window.top.postMessage({ __mch: CH, __mres: String(reqId), __mfid: FRAME_ID, __mp: payload }, "*");
}
} catch (_) {}
}
window.addEventListener("message", function(ev) {
try {
var d = ev && ev.data;
if (!d || typeof d !== "object" || d.__mch !== CH) return;
if (typeof ev.stopImmediatePropagation === "function") ev.stopImmediatePropagation();
if (IS_TOP && d.__mack) {
if (window.FableHybridBridge && typeof window.FableHybridBridge.onFrameMemAck === "function") {
window.FableHybridBridge.onFrameMemAck(String(d.__mack), String(d.__mfid || ""), Number(d.__mc) || 0);
}
return;
}
if (IS_TOP && d.__mres) {
if (window.FableHybridBridge && typeof window.FableHybridBridge.onFrameMemResult === "function") {
window.FableHybridBridge.onFrameMemResult(String(d.__mres), String(d.__mfid || ""), String(d.__mp || ""));
}
return;
}
if (!d.__mreq) return;
var childCnt = 0;
try { childCnt = window.frames ? window.frames.length : 0; } catch (_) {}
relayToChildFrames(d);
if (IS_TOP) return;
var cmdObj = d.cmd || { op: "ping" };
if (cmdObj.targetFrame && cmdObj.targetFrame !== FRAME_ID) return;
emitFrameAck(d.__mreq, childCnt);
var out = handleCmd(cmdObj);
if (out && out.skippedFrame) return;
emitFrameResult(d.__mreq, out);
} catch (_) {}
}, true);
window.addEventListener(CH, function(ev) {
var d = ev && ev.detail;
if (!d) return;
if (typeof d === "string") {
var parsed = null;
try { parsed = JSON.parse(d); } catch (_) {}
var resObj = parsed ? handleCmd(parsed) : { ok: false, error: "Bad cmd" };
try { window.dispatchEvent(new CustomEvent(CH + "_ret", { detail: JSON.stringify(resObj) })); } catch (_) {}
return;
}
if (typeof d === "object" && d.box) {
if (IS_TOP && d.__mreq) {
var cleanCmd = {};
for (var ck in d) {
if (ck !== "box" && ck !== "__mreq") cleanCmd[ck] = d[ck];
}
relayToChildFrames({ __mch: CH, __mreq: d.__mreq, cmd: cleanCmd });
}
d.box.ok = true;
d.box.out = handleCmd(d);
}
}, false);
try { window.dispatchEvent(new Event(CH + "_ok")); } catch (_) {}
})();
    """.trimStart('\n').trimEnd('\n')
}
