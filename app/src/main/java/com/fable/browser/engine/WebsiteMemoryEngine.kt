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

    private val CHANNEL_ID: String = "_mev_" + UUID.randomUUID().toString().replace("-", "").take(14)

    fun clampPanelScalePct(pct: Int): Int = pct.coerceIn(MIN_PANEL_SCALE_PCT, MAX_PANEL_SCALE_PCT)

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

    fun mergeMultiFrameResults(payloads: List<String>, op: String, maxReturn: Int = 150): String {
        val parsed = payloads.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed.isEmpty()) return "{\"ok\":false,\"error\":\"Empty page response\"}"
        if (parsed.size == 1) return parsed[0].toString()

        if (op == "readBatch") {
            val mergedVals = JSONObject()
            for (o in parsed) {
                val vals = o.optJSONObject("values") ?: continue
                vals.keys().forEach { k -> mergedVals.put(k, vals.optString(k)) }
            }
            return JSONObject().put("ok", true).put("values", mergedVals).toString()
        }
        if (op == "write" || op == "hexDump") {
            val hit = parsed.firstOrNull { it.optBoolean("ok", false) } ?: parsed[0]
            return hit.toString()
        }
        if (op == "regions") {
            var totalKb = 0L
            val allRegs = JSONArray()
            var anyOk = false
            for (o in parsed) {
                if (!o.optBoolean("ok", false)) continue
                anyOk = true
                totalKb += o.optLong("totalKb", 0L)
                val regs = o.optJSONArray("regions") ?: continue
                for (i in 0 until regs.length()) {
                    if (allRegs.length() < 120) allRegs.put(regs.get(i))
                }
            }
            if (!anyOk) return parsed[0].toString()
            return JSONObject()
                .put("ok", true)
                .put("count", allRegs.length())
                .put("totalKb", totalKb)
                .put("frameCount", parsed.count { it.optBoolean("ok", false) })
                .put("regions", allRegs)
                .toString()
        }

        var anyOk = false
        var total = 0
        var canUndo = false
        var wasmCount = 0
        var bufCount = 0
        var wroteTotal = 0
        var hasWrote = false
        var frameCount = 0
        var firstErr: String? = null
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
            .put("frameCount", frameCount)
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
        return """
        (function() {
            var CH = $chJson;
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

            var wasmEntries = [];
            var wasmSeen = new WeakSet();
            var nextWasmId = 0;
            var bufRefs = [];
            var bufSeen = new WeakSet();
            var MAX_TRACKED_BUFS = 2500;
            var MAX_SCAN_BYTES = 512 * 1024 * 1024;

            function trackWasmMem(mem) {
                try {
                    if (!mem || !mem.buffer || wasmSeen.has(mem)) return;
                    wasmSeen.add(mem);
                    var id = nextWasmId++;
                    wasmEntries.push({
                        id: id,
                        ref: typeof WeakRef === "function" ? new WeakRef(mem) : { deref: function() { return mem; } }
                    });
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
                    bufRefs.push(typeof WeakRef === "function" ? new WeakRef(ab) : { deref: function() { return ab; } });
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

            var hits = [];
            var prevHits = [];
            var lastType = "auto";
            var taRegistry = [];
            var abRegistry = [];
            var watchRefs = new Map();

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
                "Reflect", "Proxy", "JSON", "Math", "Atomics", "WebAssembly", "_sentryDebugIds", "__SENTRY__", "__sentry__", "Sentry", "dataLayer", "google_tag_manager", "gtag", "ga"
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
                var bytes = [], mask = [];
                for (var i = 0; i < parts.length; i++) {
                    var p = parts[i];
                    if (p === "?" || p === "??" || p === "*" || p === "**") {
                        bytes.push(0); mask.push(0);
                    } else {
                        var v = parseInt(p, 16);
                        if (!Number.isNaN(v) && v >= 0 && v <= 255) { bytes.push(v); mask.push(1); }
                    }
                }
                return { bytes: bytes, mask: mask };
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

            function writeViewVal(dv, off, dt, valStr) {
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
                    if (dt === "i8")  { dv.setInt8(off, num | 0); return true; }
                    if (dt === "i16") { dv.setInt16(off, num | 0, true); return true; }
                    if (dt === "i32") { dv.setInt32(off, num | 0, true); return true; }
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

            function getBufferForHit(h) {
                if (h.kind === "wasm") {
                    var m = h.memRef ? h.memRef.deref() : null;
                    if (m && m.buffer && h.off < m.buffer.byteLength) return { buf: m.buffer, byteOff: 0, byteLen: m.buffer.byteLength };
                    return null;
                }
                if (h.kind === "ab") {
                    var ab = h.abRef ? h.abRef.deref() : null;
                    if (ab && ab.byteLength > 0 && h.off < ab.byteLength) return { buf: ab, byteOff: 0, byteLen: ab.byteLength };
                    return null;
                }
                if (h.kind === "ta") {
                    var ref = taRegistry[h.bufIdx];
                    var ta = ref ? ref.deref() : null;
                    if (ta && ta.buffer && h.off < ta.byteLength) return { buf: ta.buffer, byteOff: ta.byteOffset, byteLen: ta.byteLength };
                    return null;
                }
                return null;
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
                        return { ok: true, val: readViewVal(dv, h.off, h.dt) };
                    }
                } catch (_) {}
                return { ok: false, val: "<err>" };
            }

            function writeHitValue(h, valStr) {
                try {
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
                        if (typeof curM === "number" || (h.dt !== "str" && !Number.isNaN(Number(csM)) && csM !== "")) {
                            mp.set(h.key, Number(csM));
                        } else {
                            mp.set(h.key, String(valStr));
                        }
                        return true;
                    }
                    if (h.kind === "ls" || h.kind === "ss") {
                        var st = h.kind === "ls" ? window.localStorage : window.sessionStorage;
                        st.setItem(h.key, String(valStr));
                        return true;
                    }
                    if (h.kind === "wasm" || h.kind === "ta" || h.kind === "ab") {
                        var bi = getBufferForHit(h);
                        if (!bi) return false;
                        var dv = new DataView(bi.buf, bi.byteOff, bi.byteLen);
                        return writeViewVal(dv, h.off, h.dt, valStr);
                    }
                } catch (_) {}
                return false;
            }

            function scanBuffer(buf, byteOff, byteLen, kind, bufIdx, labelPrefix, dt, sc, v1, v2, s1, aob, out, cap, extraRef) {
                if (!buf || byteLen <= 0 || out.length >= cap) return;
                var scanLen = Math.min(byteLen, MAX_SCAN_BYTES);
                var memRef = kind === "wasm" ? extraRef : null;
                var abRef = kind === "ab" ? extraRef : null;

                function pushHit(off, typeStr, len, prevVal) {
                    out.push({
                        id: labelPrefix + "+0x" + off.toString(16).toUpperCase() + (typeStr === "str" || typeStr === "aob" ? "" : (":" + typeStr)),
                        kind: kind,
                        bufIdx: bufIdx,
                        memRef: memRef,
                        abRef: abRef,
                        off: off,
                        dt: typeStr,
                        len: len,
                        prev: prevVal
                    });
                }

                if (dt === "str") {
                    var enc = new TextEncoder().encode(s1);
                    if (!enc.length || scanLen < enc.length) return;
                    var u8 = new Uint8Array(buf, byteOff, scanLen);
                    var lim = scanLen - enc.length;
                    var b0 = enc[0];
                    for (var o = 0; o <= lim && out.length < cap; o++) {
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
                        if (i32Fast[fi] === targetI32) pushHit(fi << 2, "i32", 4, targetI32);
                    }
                    return;
                }
                if (dt === "f32" && aligned4 && sc === "exact") {
                    var f32Fast = new Float32Array(buf, byteOff, scanLen >>> 2);
                    for (var ff = 0, ffl = f32Fast.length; ff < ffl && out.length < cap; ff++) {
                        var fv = f32Fast[ff];
                        if (Math.abs(fv - v1) <= 0.01) pushHit(ff << 2, "f32", 4, fv);
                    }
                    return;
                }
                if (dt === "f64" && aligned8 && sc === "exact") {
                    var f64Fast = new Float64Array(buf, byteOff, scanLen >>> 3);
                    for (var fd = 0, fdl = f64Fast.length; fd < fdl && out.length < cap; fd++) {
                        var fdv = f64Fast[fd];
                        if (Math.abs(fdv - v1) <= 0.001) pushHit(fd << 3, "f64", 8, fdv);
                    }
                    return;
                }
                if (dt === "auto" && aligned4) {
                    var len4 = scanLen >>> 2;
                    var i32a = new Int32Array(buf, byteOff, len4);
                    var f32a = new Float32Array(buf, byteOff, len4);
                    var f64a = aligned8 ? new Float64Array(buf, byteOff, scanLen >>> 3) : null;
                    var isIntQuery = (sc !== "unknown") && Number.isInteger(v1);
                    var fitsI32 = isIntQuery && v1 >= -2147483648 && v1 <= 2147483647;
                    var fitsI16 = isIntQuery && v1 >= -32768 && v1 <= 32767 && v1 !== 0;
                    var dvAuto = (!fitsI32 && isIntQuery) || fitsI16 ? new DataView(buf, byteOff, scanLen) : null;
                    var isExact = sc === "exact";
                    var target32 = v1 | 0;

                    for (var idx = 0; idx < len4 && out.length < cap; idx++) {
                        var off4 = idx << 2;
                        if (sc === "unknown") {
                            var uv = i32a[idx];
                            if (uv !== 0) pushHit(off4, "i32", 4, uv);
                            continue;
                        }
                        if (isExact) {
                            if (fitsI32 && i32a[idx] === target32) {
                                pushHit(off4, "i32", 4, target32);
                                continue;
                            }
                            if (!fitsI32 && isIntQuery && dvAuto && (off4 + 8) <= scanLen) {
                                var i64e = readViewVal(dvAuto, off4, "i64");
                                if (i64e === v1) { pushHit(off4, "i64", 8, i64e); continue; }
                            }
                            var f32e = f32a[idx];
                            if ((v1 === 0 ? f32e === 0 : (f32e !== 0 && Math.abs(f32e - v1) <= 0.01))) {
                                pushHit(off4, "f32", 4, f32e);
                                continue;
                            }
                            if (f64a && (idx & 1) === 0) {
                                var f64e = f64a[idx >>> 1];
                                if ((v1 === 0 ? f64e === 0 : (f64e !== 0 && Math.abs(f64e - v1) <= 0.001))) {
                                    pushHit(off4, "f64", 8, f64e);
                                    continue;
                                }
                            }
                            if (fitsI16 && dvAuto) {
                                var s16a = dvAuto.getInt16(off4, true);
                                if (s16a === target32) pushHit(off4, "i16", 2, s16a);
                                var s16b = dvAuto.getInt16(off4 + 2, true);
                                if (s16b === target32) pushHit(off4 + 2, "i16", 2, s16b);
                            }
                            continue;
                        }
                        var iv = i32a[idx];
                        if ((fitsI32 || !isIntQuery) && matchNum(iv, 0, false, "i32", sc, v1, v2)) {
                            pushHit(off4, "i32", 4, iv);
                            continue;
                        }
                        var fv2 = f32a[idx];
                        if (Number.isFinite(fv2) && (v1 === 0 || Math.abs(fv2) >= 1e-6) && matchNum(fv2, 0, false, "f32", sc, v1, v2)) {
                            pushHit(off4, "f32", 4, fv2);
                            continue;
                        }
                        if (f64a && (idx & 1) === 0) {
                            var dv2 = f64a[idx >>> 1];
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
                    var val = readViewVal(dv, off2, dt);
                    if (matchNum(val, 0, false, dt, sc, v1, v2)) {
                        pushHit(off2, dt, sz, val);
                    }
                }
            }

            function seedFrameworkRoots(win, queue, visited) {
                try {
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
                            if (ek.indexOf("__react") === 0 || ek.indexOf("__vue") === 0 || ek.indexOf("_") === 0 || ek === "game" || ek === "engine" || ek === "scene" || ek === "state" || ek === "store") {
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

            function registerAndScanFullBuffer(ab, labelHint, dt, sc, v1, v2, s1, aob, out, cap, scannedBufs) {
                if (!ab || typeof ab.byteLength !== "number" || ab.byteLength < 4 || ab.byteLength > MAX_SCAN_BYTES) return;
                if (scannedBufs.has(ab)) return;
                scannedBufs.add(ab);
                trackRawBuf(ab);
                var abIdx = abRegistry.length;
                var ref = typeof WeakRef === "function" ? new WeakRef(ab) : { deref: function() { return ab; } };
                abRegistry.push(ref);
                var lbl = labelHint ? ("buf#" + abIdx + "(" + labelHint.slice(-20) + ")") : ("buf#" + abIdx);
                scanBuffer(ab, 0, ab.byteLength, "ab", abIdx, lbl, dt, sc, v1, v2, s1, aob, out, cap, ref);
            }

            function isSkippedBuiltinObject(obj) {
                if (!obj) return true;
                try {
                    if (typeof Window === "function" && safeInstanceOf(obj, Window)) {
                        void obj.document;
                    }
                    if (safeInstanceOf(obj, typeof Node === "function" ? Node : null)) return true;
                    if (safeInstanceOf(obj, typeof Event === "function" ? Event : null)) return true;
                    if (safeInstanceOf(obj, typeof Promise === "function" ? Promise : null)) return true;
                    if (safeInstanceOf(obj, typeof RegExp === "function" ? RegExp : null)) return true;
                    if (safeInstanceOf(obj, typeof Error === "function" ? Error : null)) return true;
                    var ctor = null;
                    try { ctor = obj.constructor; } catch (_) { return true; }
                    var cname = "";
                    try { cname = ctor && typeof ctor.name === "string" ? ctor.name : ""; } catch (_) { return true; }
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
                    return true;
                }
            }

            function collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, cap, scannedBufs) {
                var visited = new WeakSet();
                var queue = [{ obj: window, path: "window", depth: 0 }];
                seedFrameworkRoots(window, queue, visited);

                var maxNodes = 45000;
                var nodesVisited = 0;
                var taSeen = new WeakSet();
                taRegistry = [];

                while (queue.length > 0 && nodesVisited < maxNodes && out.length < cap) {
                    var cur = queue.shift();
                    var obj = cur.obj;
                    if (!obj || (typeof obj !== "object" && typeof obj !== "function")) continue;
                    try {
                        if (visited.has(obj)) continue;
                        visited.add(obj);
                    } catch (_) { continue; }
                    nodesVisited++;

                    if (typeof WebAssembly === "object" && safeInstanceOf(obj, WebAssembly.Memory)) {
                        trackWasmMem(obj);
                        continue;
                    }
                    if (safeIsView(obj)) {
                        try {
                            if (obj.buffer) {
                                registerAndScanFullBuffer(obj.buffer, cur.path, dt, sc, v1, v2, s1, aob, out, cap, scannedBufs);
                            }
                            if (!taSeen.has(obj) && obj.byteLength >= 4) {
                                taSeen.add(obj);
                                taRegistry.push(typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } });
                            }
                        } catch (_) {}
                        continue;
                    }
                    if (typeof ArrayBuffer === "function" && (safeInstanceOf(obj, ArrayBuffer) || (typeof SharedArrayBuffer === "function" && safeInstanceOf(obj, SharedArrayBuffer)))) {
                        registerAndScanFullBuffer(obj, cur.path, dt, sc, v1, v2, s1, aob, out, cap, scannedBufs);
                        continue;
                    }
                    if (cur.depth > 0 && isSkippedBuiltinObject(obj)) continue;

                    if (safeInstanceOf(obj, Map)) {
                        var mCount = 0;
                        try {
                            obj.forEach(function(mv, mk) {
                                if (mCount >= 600 || out.length >= cap) return;
                                mCount++;
                                var keyLabel = String(mk).slice(0, 28);
                                if (SENSITIVE_KEY_RE.test(keyLabel) || INTERNAL_KEY_RE.test(keyLabel)) return;
                                if (typeof mv === "number") {
                                    if (dt !== "str" && dt !== "aob" && matchNum(mv, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
                                        var refM = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                        out.push({ id: "map:" + cur.path + "[" + keyLabel + "]", kind: "map", objRef: refM, key: mk, dt: Number.isInteger(mv) ? "i32" : "f64", prev: mv });
                                    }
                                } else if (mv && typeof mv === "object" && cur.depth < 10 && !visited.has(mv)) {
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
                                if (sCount >= 400 || out.length >= cap) return;
                                sCount++;
                                if (sv && typeof sv === "object" && cur.depth < 10 && !visited.has(sv)) {
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
                        var numScanCap = Math.min(arrLen, 4096);
                        var objEnqueued = 0;
                        for (var ai = 0; ai < numScanCap && out.length < cap; ai++) {
                            var aval;
                            try { aval = obj[ai]; } catch (_) { continue; }
                            if (typeof aval === "number") {
                                if (dt !== "str" && dt !== "aob" && matchNum(aval, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
                                    var refA = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                    out.push({ id: "js:" + cur.path + "[" + ai + "]", kind: "js", objRef: refA, key: ai, dt: Number.isInteger(aval) ? "i32" : "f64", prev: aval });
                                }
                            } else if (aval && typeof aval === "object" && cur.depth < 10 && objEnqueued < 120) {
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
                        try { keys = Object.keys(obj); } catch (_2) { continue; }
                    }
                    var maxKeys = cur.depth === 0 ? keys.length : Math.min(keys.length, 400);
                    for (var i = 0; i < maxKeys && out.length < cap; i++) {
                        var k = keys[i];
                        if (SKIP_KEYS.has(k) || INTERNAL_KEY_RE.test(k) || SENSITIVE_KEY_RE.test(k)) continue;
                        if (k.length > 2 && k[0] === "o" && k[1] === "n" && k[2] >= "a" && k[2] <= "z") continue;
                        var desc = null;
                        try { desc = Object.getOwnPropertyDescriptor(obj, k); } catch (_) { continue; }
                        if (desc && !("value" in desc)) continue;
                        var isWritable = !desc || desc.writable === true;
                        var val = desc ? desc.value : undefined;
                        if (val === undefined && !desc) {
                            try { val = obj[k]; } catch (_) { continue; }
                        }
                        var nextPath = cur.depth === 0 ? k : (cur.path + "." + k);
                        if (typeof val === "number") {
                            if (!isWritable) continue;
                            if (dt !== "str" && dt !== "aob" && matchNum(val, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
                                var ref = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                out.push({ id: "js:" + nextPath, kind: "js", objRef: ref, key: k, dt: Number.isInteger(val) ? "i32" : "f64", prev: val });
                            }
                        } else if (typeof val === "string") {
                            if (!isWritable) continue;
                            if ((dt === "str" || dt === "auto") && s1 && val.indexOf(s1) !== -1 && val.length <= 256) {
                                var refS = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                out.push({ id: "js:" + nextPath, kind: "js", objRef: refS, key: k, dt: "str", prev: val });
                            }
                        } else if (val && (typeof val === "object" || typeof val === "function") && cur.depth < 10) {
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
                            if (raw === null || raw.length > 256) continue;
                            if (dt === "str") {
                                if (s1 && raw.indexOf(s1) !== -1) out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "str", prev: raw });
                            } else if (dt !== "aob") {
                                var num = Number(cleanNumStr(raw));
                                if (raw.trim() !== "" && Number.isFinite(num) && matchNum(num, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
                                    out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "f64", prev: num });
                                } else if (dt === "auto" && s1 && raw.indexOf(s1) !== -1) {
                                    out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "str", prev: raw });
                                }
                            }
                        }
                    } catch (_) {}
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

            function formatHitsResult(maxRet) {
                var lim = Math.min(hits.length, maxRet || 150);
                var items = [];
                for (var i = 0; i < lim; i++) {
                    var h = hits[i];
                    watchRefs.set(h.id, h);
                    var rd = readHitCurrent(h);
                    items.push({
                        addr: qualifyId(h.id),
                        val: formatVal(rd.val, h.dt),
                        prev: formatVal(h.prev, h.dt),
                        type: h.dt,
                        kind: h.kind
                    });
                }
                return {
                    ok: true,
                    frameId: FRAME_ID,
                    total: hits.length,
                    canUndo: prevHits.length > 0,
                    wasmCount: liveWasmEntries().length,
                    bufCount: abRegistry.length + taRegistry.length,
                    items: items
                };
            }

            function handleCmd(cmd) {
                try {
                    if (cmd.targetFrame && cmd.targetFrame !== FRAME_ID) {
                        return { ok: false, skippedFrame: true };
                    }
                    if (cmd.op === "ping") return { ok: true, frameId: FRAME_ID };
                    if (cmd.op === "reset") {
                        hits = []; prevHits = [];
                        return { ok: true, frameId: FRAME_ID, total: 0, canUndo: false, wasmCount: liveWasmEntries().length, bufCount: abRegistry.length + taRegistry.length, items: [] };
                    }
                    if (cmd.op === "refreshHits") {
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "undo") {
                        if (!prevHits.length) return { ok: false, error: "Nothing to undo" };
                        hits = prevHits; prevHits = [];
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "writeAll") {
                        var valAll = String(cmd.val || "");
                        var limW = Math.min(hits.length, cmd.maxWrite || 50);
                        var wrote = 0;
                        for (var wi = 0; wi < limW; wi++) {
                            if (writeHitValue(hits[wi], valAll)) wrote++;
                        }
                        var resAll = formatHitsResult(cmd.maxReturn || 150);
                        resAll.wrote = wrote;
                        return resAll;
                    }
                    if (cmd.op === "firstScan") {
                        prevHits = []; hits = []; abRegistry = [];
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
                        var cap = sc === "unknown" ? 60000 : 120000;
                        var out = [];
                        var scannedBufs = new WeakSet();
                        var mems = liveWasmEntries();
                        for (var mi = 0; mi < mems.length && out.length < cap; mi++) {
                            var mb = mems[mi].mem.buffer;
                            scannedBufs.add(mb);
                            scanBuffer(mb, 0, mb.byteLength, "wasm", mems[mi].id, "wasm#" + mems[mi].id, dt, sc, v1, v2, s1, aob, out, cap, mems[mi].ref);
                        }
                        collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, cap, scannedBufs);
                        var memsAfter = liveWasmEntries();
                        for (var mi2 = 0; mi2 < memsAfter.length && out.length < cap; mi2++) {
                            var mb2 = memsAfter[mi2].mem.buffer;
                            if (!scannedBufs.has(mb2)) {
                                scannedBufs.add(mb2);
                                scanBuffer(mb2, 0, mb2.byteLength, "wasm", memsAfter[mi2].id, "wasm#" + memsAfter[mi2].id, dt, sc, v1, v2, s1, aob, out, cap, memsAfter[mi2].ref);
                            }
                        }
                        var rawBufs = liveRawBuffers(scannedBufs);
                        for (var bi = 0; bi < rawBufs.length && out.length < cap; bi++) {
                            registerAndScanFullBuffer(rawBufs[bi], "", dt, sc, v1, v2, s1, aob, out, cap, scannedBufs);
                        }
                        scanStorage(dt, sc, v1, v2, s1, out, cap);
                        hits = out;
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "nextScan") {
                        if (!hits.length) return { ok: true, frameId: FRAME_ID, total: 0, canUndo: prevHits.length > 0, wasmCount: liveWasmEntries().length, bufCount: abRegistry.length + taRegistry.length, items: [] };
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
                        var next = [];
                        for (var i = 0; i < hits.length; i++) {
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
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "readBatch") {
                        var ids = Array.isArray(cmd.ids) ? cmd.ids : [];
                        var vals = {};
                        for (var ri = 0; ri < ids.length; ri++) {
                            var fullId = ids[ri];
                            if (typeof fullId !== "string") continue;
                            if (fullId.charAt(0) === "@") {
                                var pref = "@" + FRAME_ID + "|";
                                if (fullId.indexOf(pref) !== 0) continue;
                            } else if (!IS_TOP) {
                                continue;
                            }
                            var localId = stripOwnFramePrefix(fullId);
                            var rec = watchRefs.get(localId);
                            if (rec) {
                                var r = readHitCurrent(rec);
                                vals[fullId] = r.ok ? formatVal(r.val, rec.dt) : String(r.val);
                            }
                        }
                        return { ok: true, frameId: FRAME_ID, values: vals };
                    }
                    if (cmd.op === "write") {
                        var localW = stripOwnFramePrefix(cmd.id);
                        var recW = watchRefs.get(localW);
                        if (!recW) return { ok: false, error: "Entry not found in frame" };
                        var okW = writeHitValue(recW, cmd.val);
                        var rdW = readHitCurrent(recW);
                        return { ok: okW, frameId: FRAME_ID, val: formatVal(rdW.val, recW.dt) };
                    }
                    if (cmd.op === "hexDump") {
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
                        for (var rmi = 0; rmi < memsR.length; rmi++) {
                            var kb = Math.round(memsR[rmi].mem.buffer.byteLength / 1024);
                            totalKb += kb;
                            regs.push({ start: qualifyId("wasm#" + memsR[rmi].id), end: "0x" + memsR[rmi].mem.buffer.byteLength.toString(16).toUpperCase(), kb: kb, perms: "rw-wasm", label: fTag + "WebAssembly.Memory #" + memsR[rmi].id });
                        }
                        var seenR = new WeakSet();
                        for (var rmi2 = 0; rmi2 < memsR.length; rmi2++) seenR.add(memsR[rmi2].mem.buffer);
                        var rawR = liveRawBuffers(seenR);
                        for (var rbi = 0; rbi < Math.min(rawR.length, 60); rbi++) {
                            var bkb = Math.max(1, Math.round(rawR[rbi].byteLength / 1024));
                            totalKb += bkb;
                            regs.push({ start: qualifyId("buf#" + rbi), end: "0x" + rawR[rbi].byteLength.toString(16).toUpperCase(), kb: bkb, perms: "rw-buf", label: fTag + "ArrayBuffer #" + rbi });
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
                                    regs.push({ start: qualifyId("ta#" + tai), end: "0x" + tLen.toString(16).toUpperCase(), kb: tkb, perms: "rw-ta", label: fTag + tName });
                                } catch (_) {}
                            }
                        }
                        return { ok: true, frameId: FRAME_ID, count: regs.length, totalKb: totalKb, regions: regs };
                    }
                    return { ok: false, error: "Unknown op" };
                } catch (e) {
                    return { ok: false, error: String(e && e.message ? e.message : e) };
                }
            }

            function relayToChildFrames(packet) {
                try {
                    var fc = window.frames ? window.frames.length : 0;
                    for (var i = 0; i < fc && i < 24; i++) {
                        try { window.frames[i].postMessage(packet, "*"); } catch (_) {}
                    }
                } catch (_) {}
            }

            window.addEventListener("message", function(ev) {
                try {
                    var d = ev && ev.data;
                    if (!d || typeof d !== "object" || d.__mch !== CH || !d.__mreq) return;
                    relayToChildFrames(d);
                    if (IS_TOP) return;
                    var out = handleCmd(d.cmd || { op: "ping" });
                    if (out && out.skippedFrame) return;
                    if (window.FableHybridBridge && typeof window.FableHybridBridge.onFrameMemResult === "function") {
                        window.FableHybridBridge.onFrameMemResult(String(d.__mreq), FRAME_ID, JSON.stringify(out));
                    }
                } catch (_) {}
            }, false);

            window.addEventListener(CH, function(ev) {
                var d = ev && ev.detail;
                if (!d || typeof d !== "object" || !d.box) return;
                if (IS_TOP && d.__mreq) {
                    var cleanCmd = {};
                    for (var ck in d) {
                        if (ck !== "box" && ck !== "__mreq") cleanCmd[ck] = d[ck];
                    }
                    relayToChildFrames({ __mch: CH, __mreq: d.__mreq, cmd: cleanCmd });
                }
                d.box.ok = true;
                d.box.out = handleCmd(d);
            }, false);
        })();
        """.trimIndent()
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
            try { window.dispatchEvent(new CustomEvent($chJson, { detail: { op: "reset", box: box } })); } catch (_) {}
        })();
        """.trimIndent()
    }
}
