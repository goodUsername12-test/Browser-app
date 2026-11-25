package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object WebsiteMemoryEngine {
    const val MIN_PANEL_SCALE_PCT = 65
    const val MAX_PANEL_SCALE_PCT = 150
    const val DEFAULT_PANEL_SCALE_PCT = 100
    const val DEFAULT_PANEL_POS_X = 0.06f
    const val DEFAULT_PANEL_POS_Y = 0.24f

    const val MIN_TIMEOUT_SEC = 1
    const val MAX_TIMEOUT_SEC = 80
    const val DEFAULT_TIMEOUT_SEC = 15

    private val CHANNEL_ID: String = "_mev_" + UUID.randomUUID().toString().replace("-", "").take(14)

    fun clampPanelScalePct(pct: Int): Int = pct.coerceIn(MIN_PANEL_SCALE_PCT, MAX_PANEL_SCALE_PCT)

    fun clampTimeoutSec(sec: Int): Int = sec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    fun normalizeNumericInput(raw: String, dataType: MemDataType): String {
        val t = raw.trim()
        if (dataType == MemDataType.STRING || dataType == MemDataType.AOB) return t
        val cleaned = t.replace(",", "").replace("_", "").replace(" ", "")
        return if (cleaned.isNotEmpty() && cleaned.toDoubleOrNull() != null) cleaned else t
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
            for (o in parsed) {
                val vals = o.optJSONObject("values")
                if (vals != null) {
                    vals.keys().forEach { k -> mergedVals.put(k, vals.optString(k)) }
                }
                val fs = o.optJSONObject("freezeStats")
                if (fs != null) {
                    fs.keys().forEach { k -> mergedStats.put(k, fs.opt(k)) }
                }
            }
            return JSONObject()
                .put("ok", true)
                .put("values", mergedVals)
                .put("freezeStats", mergedStats)
                .toString()
        }
        if (op == "writeBatch") {
            var wrote = 0
            val mergedVals = JSONObject()
            for (o in parsed) {
                if (!o.optBoolean("ok", false)) continue
                wrote += o.optInt("wrote", 0)
                val vals = o.optJSONObject("values")
                if (vals != null) {
                    vals.keys().forEach { k -> mergedVals.put(k, vals.optString(k)) }
                }
            }
            return JSONObject().put("ok", true).put("wrote", wrote).put("values", mergedVals).toString()
        }
        if (op == "freezeBatch") {
            var locked = 0
            val mergedStats = JSONObject()
            for (o in parsed) {
                if (!o.optBoolean("ok", false)) continue
                locked += o.optInt("locked", 0)
                val fs = o.optJSONObject("freezeStats")
                if (fs != null) {
                    fs.keys().forEach { k -> mergedStats.put(k, fs.opt(k)) }
                }
            }
            return JSONObject().put("ok", true).put("locked", locked).put("freezeStats", mergedStats).toString()
        }
        if (op == "unfreeze" || op == "unfreezeBatch" || op == "unfreezeAll") {
            return JSONObject().put("ok", true).toString()
        }
        if (op == "write" || op == "hexDump" || op == "setFreeze") {
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
        var total = 0
        var canUndo = false
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
        val wasmItems = ArrayList<JSONObject>()
        val bufItems = ArrayList<JSONObject>()
        val otherItems = ArrayList<JSONObject>()

        for (o in parsed) {
            if (!o.optBoolean("ok", false)) {
                if (firstErr == null) firstErr = o.optString("error").takeIf { it.isNotBlank() }
                continue
            }
            anyOk = true
            frameCount++
            total += o.optInt("total", 0)
            if (o.optBoolean("canUndo", false)) canUndo = true
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
                when (item.optString("kind")) {
                    "wasm" -> wasmItems.add(item)
                    "ab", "ta" -> bufItems.add(item)
                    else -> otherItems.add(item)
                }
            }
        }
        if (!anyOk) {
            return JSONObject().put("ok", false).put("error", firstErr ?: "Scan failed").toString()
        }
        val mergedItems = JSONArray()
        for (item in wasmItems) { if (mergedItems.length() < maxReturn) mergedItems.put(item) }
        for (item in bufItems) { if (mergedItems.length() < maxReturn) mergedItems.put(item) }
        for (item in otherItems) { if (mergedItems.length() < maxReturn) mergedItems.put(item) }

        val out = JSONObject()
            .put("ok", true)
            .put("total", total)
            .put("canUndo", canUndo)
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
        val sb = StringBuilder(82_000)
        sb.append(JS_PART_1).append('\n')
        sb.append(JS_PART_2).append('\n')
        sb.append(JS_PART_3).append('\n')
        sb.append(JS_PART_4)
        return sb.toString().replace("__CH_ARG__", chJson)
    }

    fun buildBlinkCommandScript(cmdJson: String, reqId: String = ""): String {
        val chJson = JSONObject.quote(CHANNEL_ID)
        val reqJson = JSONObject.quote(reqId)
        val boot = buildBootstrapScriptInternal(CHANNEL_ID)
        return """
        (function() {
            var CH = $chJson;
            var cmd = $cmdJson;
            var rid = $reqJson;
            if (rid) cmd.__mreq = rid;
            function send() {
                var box = { ok: false, out: null };
                cmd.box = box;
                try { window.dispatchEvent(new CustomEvent(CH, { detail: cmd })); } catch (_) {}
                return box;
            }
            var res = send();
            if (!res.ok) {
                $boot
                res = send();
            }
            var outObj = (res.ok && res.out) ? res.out : { ok: false, error: "Page scanner unavailable" };
            try { outObj.childFrames = window.frames ? window.frames.length : 0; } catch (_) { outObj.childFrames = 0; }
            return JSON.stringify(outObj);
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
var MAX_TRACKED_BUFS = 3000;
var MAX_TRACKED_CLOSURE = 600;
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
if (!o || typeof o !== "object" || closureObjSeen.has(o)) return;
closureObjSeen.add(o);
if (closureObjRefs.length >= MAX_TRACKED_CLOSURE) {
closureObjRefs = closureObjRefs.filter(function(r) { return !!r.deref(); }).slice(-Math.floor(MAX_TRACKED_CLOSURE * 0.75));
}
closureObjRefs.push(mkRef(o));
} catch (_) {}
}
function trackClosureMap(m) {
try {
if (!m || closureMapSeen.has(m)) return;
closureMapSeen.add(m);
if (closureMapRefs.length >= MAX_TRACKED_CLOSURE) {
closureMapRefs = closureMapRefs.filter(function(r) { return !!r.deref(); }).slice(-Math.floor(MAX_TRACKED_CLOSURE * 0.75));
}
closureMapRefs.push(mkRef(m));
} catch (_) {}
}
function trackClosureSet(s) {
try {
if (!s || closureSetSeen.has(s)) return;
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
var prevHits = [];
var unknownSnaps = null; // Binary snapshot pool for PC-CE style Unknown Initial scans
var prevUnknownSnaps = null;
var lastType = "auto";
var taRegistry = [];
var abRegistry = [];
var watchRefs = new Map();
var skippedXOriginCount = 0;
var frozenMap = new Map(); // localId -> { rec, valStr, parsedVal, mode, blocked, lastBlockedAt, rateCount, origDesc, trapInstalled, status, origObj }
var mapWrapState = new WeakMap(); // Map -> { origSet, keys: Map<key, frozenEntry> }
var storageHooked = { ls: null, ss: null }; // { origSetItem, keys: Map<storageKey, Set<frozenEntry>> }
var freezeIntervalId = null;
var freezeRafId = null;
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
if (dt === "i8") {
Atomics.store(new Int8Array(dv.buffer, absOff, 1), 0, num | 0);
return true;
}
if (dt === "i16" && (absOff & 1) === 0) {
Atomics.store(new Int16Array(dv.buffer, absOff, 1), 0, num | 0);
return true;
}
if (dt === "i32" && (absOff & 3) === 0) {
Atomics.store(new Int32Array(dv.buffer, absOff, 1), 0, num | 0);
return true;
}
if (dt === "f32" && (absOff & 3) === 0) {
Atomics.store(new Int32Array(dv.buffer, absOff, 1), 0, f32ToI32Bits(num));
return true;
}
} catch (_) {}
}
if (dt === "i8")  { dv.setInt8(off, num | 0); return true; }
if (dt === "i16") { dv.setInt16(off, num | 0, true); return true; }
if (dt === "i32") { dv.setInt32(off, num | 0, true); return true; }
if (dt === "i64") { dv.setBigInt64(off, BigInt(Math.round(num)), true); return true; }

""".trimStart('\n').trimEnd('\n')

    private val JS_PART_2 = """
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
function readHitCurrent(h) {
try {
if (h.kind === "js") {
var obj = h.objRef.deref();
if (!obj) return { ok: false, val: "<gc>" };
return { ok: true, val: obj[h.key] };
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
function writeHitValue(h, valStr, bypassFreeze) {
try {
var fe = !bypassFreeze ? frozenMap.get(h.id) : null;
if (fe) {
fe.valStr = String(valStr);
fe.parsedVal = parseTargetVal(h, valStr);
if (fe.trapInstalled && h.kind === "js") return true;
}
if (h.kind === "js") {
var obj = h.objRef.deref();
if (!obj) return false;
var cur = obj[h.key];
var cs = cleanNumStr(valStr);
if (typeof cur === "number" || (h.dt !== "str" && !Number.isNaN(Number(cs)) && cs !== "")) {
obj[h.key] = Number(cs);
} else if (typeof cur === "boolean") {
obj[h.key] = (valStr === "true" || valStr === "1");
} else {
obj[h.key] = String(valStr);
}
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
var sh = storageHooked[h.kind];
if (sh && sh.origSetItem) sh.origSetItem.call(st, h.key, String(valStr));
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
var shJ = storageHooked[tagJ];
if (shJ && shJ.origSetItem) shJ.origSetItem.call(stJ, h.key, outStr);
else stJ.setItem(h.key, outStr);
return true;
}
if (h.kind === "wasm" || h.kind === "ta" || h.kind === "ab") {
var bi = getBufferForHit(h);
if (!bi) return false;
var dv = new DataView(bi.buf, bi.byteOff, bi.byteLen);
var wStr = valStr;
if (h.scale) {
var sn = Number(cleanNumStr(valStr));
if (Number.isFinite(sn)) wStr = String(Math.round(sn * h.scale));
}
return writeViewVal(dv, h.off, h.dt, wStr, bi.shared);
}
} catch (_) {}
return false;
}
function ensureStorageHook(tag) {
if (storageHooked[tag]) return storageHooked[tag];
var st = tag === "ls" ? window.localStorage : window.sessionStorage;
if (!st) return null;
var origSetItem = st.setItem;
var state = { st: st, origSetItem: origSetItem, keys: new Map() };
try {
st.setItem = mask(function setItem(k, v) {
var set = state.keys.get(k);
if (set && set.size > 0) {
var directBlocked = false;
var jsonMod = null;
set.forEach(function(fe) {
if (fe.rec.kind === tag) {
if (String(v) !== String(fe.valStr)) {
fe.blocked++;
fe.recentBlocked++;
}
directBlocked = true;
} else if (fe.rec.kind === tag + "json") {
try {
if (!jsonMod) jsonMod = JSON.parse(String(v));
var curG = getJsonPathVal(jsonMod, fe.rec.jpath);
if (curG.ok && curG.val !== fe.parsedVal) {
fe.blocked++;
fe.recentBlocked++;
}
setJsonPathVal(jsonMod, fe.rec.jpath, fe.parsedVal);
} catch (_) {}
}
});
if (directBlocked) return;
if (jsonMod) {
return origSetItem.call(this, k, JSON.stringify(jsonMod));
}
}
return origSetItem.apply(this, arguments);
}, origSetItem, "setItem");
} catch (_) {}
storageHooked[tag] = state;
return state;
}
function maybeRemoveStorageHook(tag) {
var state = storageHooked[tag];
if (!state || state.keys.size > 0) return;
try { state.st.setItem = state.origSetItem; } catch (_) {}
storageHooked[tag] = null;
}
function enforceBinaryFreezes() {
if (frozenMap.size === 0) {
stopFreezeLoops();
return;
}
var now = Date.now();
frozenMap.forEach(function(fe) {
if (now - fe.rateWindowStart >= 1000) {
fe.ratePerSec = fe.recentBlocked;
fe.recentBlocked = 0;
fe.rateWindowStart = now;
}
if (fe.trapInstalled) {
fe.status = fe.ratePerSec >= 10 ? "fighting" : (fe.blocked > 0 ? "enforcing" : "locked");
return;
}
var h = fe.rec;
var rd = readHitCurrent(h);
if (!rd.ok) {
fe.status = "detached";
return;
}
var differed = false;
if (typeof fe.parsedVal === "number" && typeof rd.val === "number") {
var eps = numEps(h.dt);
differed = Math.abs(rd.val - fe.parsedVal) > (eps > 0 ? eps : 0);
} else {
differed = String(rd.val) !== String(fe.parsedVal);
}
if (differed) {
if (writeHitValue(h, fe.valStr, true)) {
fe.blocked++;
fe.recentBlocked++;
} else {
fe.status = "detached";
return;
}
}
fe.status = fe.ratePerSec >= 10 ? "fighting" : (fe.blocked > 0 ? "enforcing" : "locked");
});
}
function startFreezeLoops() {
if (freezeIntervalId === null) {
freezeIntervalId = setInterval(enforceBinaryFreezes, 4);
}
if (freezeRafId === null && typeof window.requestAnimationFrame === "function") {
var rafTick = function() {
if (frozenMap.size === 0) {
freezeRafId = null;
return;
}
enforceBinaryFreezes();
freezeRafId = window.requestAnimationFrame(rafTick);
};
freezeRafId = window.requestAnimationFrame(rafTick);
}
}
function stopFreezeLoops() {
if (freezeIntervalId !== null) {
clearInterval(freezeIntervalId);
freezeIntervalId = null;
}
if (freezeRafId !== null && typeof window.cancelAnimationFrame === "function") {
try { window.cancelAnimationFrame(freezeRafId); } catch (_) {}
freezeRafId = null;
}
}
function installFreezeEntry(localId, valStr) {
var rec = watchRefs.get(localId);
if (!rec) return { ok: false, error: "Entry not found in frame" };
var existing = frozenMap.get(localId);
if (existing) {
existing.valStr = String(valStr);
existing.parsedVal = parseTargetVal(rec, valStr);
writeHitValue(rec, valStr, true);
return { ok: true, mode: existing.mode, blocked: existing.blocked, status: existing.status };
}
var fe = {
localId: localId,
rec: rec,
valStr: String(valStr),
parsedVal: parseTargetVal(rec, valStr),
mode: "250Hz",
blocked: 0,
recentBlocked: 0,
ratePerSec: 0,
rateWindowStart: Date.now(),
trapInstalled: false,
origDesc: null,
origObj: null,
status: "locked"
};
writeHitValue(rec, valStr, true);
if (rec.kind === "js") {
var obj = rec.objRef ? rec.objRef.deref() : null;
if (obj) {
try {
var desc = Object.getOwnPropertyDescriptor(obj, rec.key);
if (!desc || desc.configurable !== false) {
fe.origDesc = desc || { value: fe.parsedVal, writable: true, enumerable: true, configurable: true };
fe.origObj = obj;
Object.defineProperty(obj, rec.key, {
configurable: true,
enumerable: fe.origDesc.enumerable !== false,
get: function() { return fe.parsedVal; },
set: function(attempted) {
if (attempted !== fe.parsedVal) {
fe.blocked++;
fe.recentBlocked++;
}
}
});
fe.trapInstalled = true;
fe.mode = "trap";
}
} catch (_) {}
}
}
else if (rec.kind === "map") {
var mp = rec.objRef ? rec.objRef.deref() : null;
if (mp) {
try {
var mw = mapWrapState.get(mp);
if (!mw) {
var origSet = mp.set;
mw = { origSet: origSet, keys: new Map() };
mp.set = mask(function set(mk, mv) {
var f = mw.keys.get(mk);
if (f) {
if (mv !== f.parsedVal) {
f.blocked++;
f.recentBlocked++;
}
return this;
}
return origSet.apply(this, arguments);
}, origSet, "set");
mapWrapState.set(mp, mw);
}
mw.keys.set(rec.key, fe);
mw.origSet.call(mp, rec.key, fe.parsedVal);
fe.trapInstalled = true;
fe.mode = "trap";
} catch (_) {}
}
}
else if (rec.kind === "ls" || rec.kind === "ss" || rec.kind === "lsjson" || rec.kind === "ssjson") {
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
return { ok: true, mode: fe.mode, blocked: fe.blocked, status: fe.status };
}
function removeFreezeEntry(localId) {
var fe = frozenMap.get(localId);
if (!fe) return false;
frozenMap.delete(localId);
try {
if (fe.trapInstalled && fe.rec.kind === "js" && fe.origObj) {
var restoreDesc = fe.origDesc || { value: fe.parsedVal, writable: true, enumerable: true, configurable: true };
if ("value" in restoreDesc) {
restoreDesc = {
value: fe.parsedVal,
writable: restoreDesc.writable !== false,
enumerable: restoreDesc.enumerable !== false,
configurable: true
};
}
Object.defineProperty(fe.origObj, fe.rec.key, restoreDesc);
} else if (fe.trapInstalled && fe.rec.kind === "map") {
var mp = fe.rec.objRef ? fe.rec.objRef.deref() : null;
if (mp) {
var mw = mapWrapState.get(mp);
if (mw) {
mw.keys.delete(fe.rec.key);
if (mw.keys.size === 0) {
try { delete mp.set; } catch (_) { mp.set = mw.origSet; }
mapWrapState.delete(mp);
}
}
}
} else if (fe.trapInstalled && (fe.rec.kind === "ls" || fe.rec.kind === "ss" || fe.rec.kind === "lsjson" || fe.rec.kind === "ssjson")) {
var tagS = (fe.rec.kind === "ls" || fe.rec.kind === "lsjson") ? "ls" : "ss";
var sh = storageHooked[tagS];
if (sh) {
var setS = sh.keys.get(fe.rec.key);
if (setS) {
setS.delete(fe);
if (setS.size === 0) sh.keys.delete(fe.rec.key);
}
maybeRemoveStorageHook(tagS);
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
var out = {};
frozenMap.forEach(function(fe, localId) {
out[qualifyId(localId)] = {
mode: fe.mode,
blocked: fe.blocked,
rate: fe.ratePerSec || 0,
status: fe.status
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

""".trimStart('\n').trimEnd('\n')

    private val JS_PART_3 = """
var off4 = idx2 << 2;
if (sc === "unknown") {
var uv = i32a[idx2];
if (uv !== 0) pushHit(off4, "i32", 4, uv);
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
var sz = dt === "i8" ? 1 : (dt === "i16" ? 2 : ((dt === "i32" || dt === "f32") ? 4 : 8));
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
try {
if (win.cc.director && typeof win.cc.director.getScene === "function") {
enqueue(win.cc.director.getScene(), "cc.scene");
}
} catch (_) {}
}
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
var maxNodes = 60000;
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
} else if (mv && typeof mv === "object" && cur.depth < 12 && !visited.has(mv)) {
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
if (sv && typeof sv === "object" && cur.depth < 12 && !visited.has(sv)) {
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
} else if (aval && typeof aval === "object" && cur.depth < 12 && objEnqueued < 160) {
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
var maxKeys = cur.depth === 0 ? keys.length : Math.min(keys.length, 450);
for (var i = 0; i < maxKeys && out.length < cap; i++) {
var k = keys[i];
if (SKIP_KEYS.has(k) || INTERNAL_KEY_RE.test(k) || SENSITIVE_KEY_RE.test(k)) continue;
if (k.length > 2 && k[0] === "o" && k[1] === "n" && k[2] >= "a" && k[2] <= "z") continue;
var desc = null;
try { desc = Object.getOwnPropertyDescriptor(obj, k); } catch (_) { skippedXOriginCount++; continue; }
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
function diffUnknownSnapshots(snapObj, sc, v1, v2, out, cap, deadlineMs, stateFlags) {
if (!snapObj || !snapObj.snaps) return;
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
var cmpLen = Math.min(s.len, liveBuf.byteLength) & ~3;
if (cmpLen < 4) continue;
var len4 = cmpLen >>> 2;
var oldI32 = new Int32Array(s.snapBuf, 0, len4);
var newI32 = new Int32Array(liveBuf, 0, len4);
var oldF32 = new Float32Array(s.snapBuf, 0, len4);
var newF32 = new Float32Array(liveBuf, 0, len4);
var dt = s.dt || "auto";
for (var idx = 0; idx < len4 && out.length < cap; idx++) {
if ((idx & 131071) === 0 && deadlineMs && Date.now() >= deadlineMs) { if (stateFlags) stateFlags.timedOut = true; return; }
var oi = oldI32[idx], ni = newI32[idx];
if (oi === ni && (sc === "changed" || sc === "increased" || sc === "decreased" ||
(sc === "increased_by" && v1 > 0) || (sc === "decreased_by" && v1 > 0))) continue;
if (dt === "f32") {
var ofv = oldF32[idx], nfv = newF32[idx];

""".trimStart('\n').trimEnd('\n')

    private val JS_PART_4 = """
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
function formatHitsResult(maxRet, meta) {
var lim = Math.min(hits.length, maxRet || 300);
var items = [];
var engs = detectEngines();
var primaryEng = engs.length ? engs[0] : "";
for (var i = 0; i < lim; i++) {
var h = hits[i];
watchRefs.set(h.id, h);
var rd = readHitCurrent(h);
items.push({
addr: qualifyId(h.id),
val: formatVal(rd.val, h.dt),
prev: formatVal(h.prev, h.dt),
type: h.dt,
kind: h.kind,
engine: primaryEng
});
}
var totalSlots = unknownSnaps ? (hits.length + unknownSnaps.totalSlots) : hits.length;
return {
ok: true,
frameId: FRAME_ID,
total: totalSlots,
snapshotActive: !!unknownSnaps,
canUndo: prevHits.length > 0 || !!prevUnknownSnaps,
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
function handleCmd(cmd) {
try {
if (cmd.targetFrame && cmd.targetFrame !== FRAME_ID) {
return { ok: false, skippedFrame: true };
}
if (cmd.op === "ping") return { ok: true, frameId: FRAME_ID, engines: detectEngines() };
if (cmd.op === "reset") {
hits = []; prevHits = []; unknownSnaps = null; prevUnknownSnaps = null;
if (cmd.clearFreezes) clearAllFreezes();
return formatHitsResult(0, { elapsedMs: 0, timedOut: false });
}
if (cmd.op === "refreshHits") {
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0 });
}
if (cmd.op === "undo") {
if (!prevHits.length && !prevUnknownSnaps) return { ok: false, error: "Nothing to undo" };
hits = prevHits; prevHits = [];
unknownSnaps = prevUnknownSnaps; prevUnknownSnaps = null;
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0 });
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
if (!isForThisFrame(cmd.id)) return { ok: false, skippedFrame: true };
var fr = installFreezeEntry(stripOwnFramePrefix(cmd.id), cmd.val);
fr.frameId = FRAME_ID;
return fr;
}
if (cmd.op === "freezeBatch") {
var entriesF = Array.isArray(cmd.entries) ? cmd.entries : [];
var locked = 0;
for (var fbi = 0; fbi < entriesF.length; fbi++) {
var ef = entriesF[fbi];
if (!ef || !isForThisFrame(ef.id)) continue;
var rF = installFreezeEntry(stripOwnFramePrefix(ef.id), ef.val);
if (rF.ok) locked++;
}
return { ok: true, frameId: FRAME_ID, locked: locked, freezeStats: collectFreezeStatsMap() };
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
if (cmd.op === "firstScan") {
var tStart = Date.now();
var budgetMs = Math.max(1000, Math.min(80000, Number(cmd.timeoutMs) || 15000));
var deadlineMs = tStart + budgetMs;
var stateFlags = { timedOut: false, fallbackUsed: false };
prevHits = []; hits = []; abRegistry = []; unknownSnaps = null; prevUnknownSnaps = null;
skippedXOriginCount = 0;
var dt = cmd.dtype || "auto";
var sc = cmd.scanType || "exact";
lastType = dt;
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
var out = [];
var scannedBufs = new WeakSet();
if (sc === "unknown" && dt !== "str" && dt !== "aob") {
unknownSnaps = captureUnknownSnapshots(dt, deadlineMs, stateFlags);
var memsU = liveWasmEntries();
for (var umi = 0; umi < memsU.length; umi++) scannedBufs.add(memsU[umi].mem.buffer);
var rawU = liveRawBuffers(null);
for (var ubi2 = 0; ubi2 < rawU.length; ubi2++) scannedBufs.add(rawU[ubi2]);
collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, 30000, scannedBufs, deadlineMs, stateFlags);
scanStorage(dt, sc, v1, v2, s1, out, 30000);
hits = out;
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: Date.now() - tStart, timedOut: stateFlags.timedOut });
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
fallbackUsed: stateFlags.fallbackUsed
});
}
if (cmd.op === "nextScan") {
var tStart2 = Date.now();
var budgetMs2 = Math.max(1000, Math.min(80000, Number(cmd.timeoutMs) || 15000));
var deadlineMs2 = tStart2 + budgetMs2;
var stateFlags2 = { timedOut: false };
if (!hits.length && !unknownSnaps) {
return formatHitsResult(cmd.maxReturn || 300, { elapsedMs: 0 });
}
var sc2 = cmd.scanType || "exact";
var s1n = (lastType === "str" || lastType === "aob") ? String(cmd.val1 || "") : cleanNumStr(cmd.val1);
var s2n = (lastType === "str" || lastType === "aob") ? String(cmd.val2 || "") : cleanNumStr(cmd.val2);
var v1n = Number(s1n), v2n = Number(s2n);
if ((sc2 === "unknown" || sc2 === "changed" || sc2 === "increased" || sc2 === "decreased" || sc2 === "unchanged") &&
s1n.trim() !== "" && Number.isFinite(v1n)) {
sc2 = "exact";
} else if (sc2 === "unknown") {
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
prevHits = hits;
prevUnknownSnaps = unknownSnaps;
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
timedOut: stateFlags2.timedOut
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
return { ok: true, frameId: FRAME_ID, values: vals, freezeStats: collectFreezeStatsMap() };
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
