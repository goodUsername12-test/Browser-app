package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
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

            var wasmRefs = [];
            var wasmSeen = new WeakSet();
            var bufRefs = [];
            var bufSeen = new WeakSet();
            var MAX_TRACKED_BUFS = 180;

            function trackWasmMem(mem) {
                try {
                    if (!mem || !mem.buffer || wasmSeen.has(mem)) return;
                    wasmSeen.add(mem);
                    wasmRefs.push(typeof WeakRef === "function" ? new WeakRef(mem) : { deref: function() { return mem; } });
                } catch (_) {}
            }
            function trackRawBuf(ab) {
                try {
                    if (!ab || typeof ab.byteLength !== "number" || ab.byteLength < 2048 || ab.byteLength > 64 * 1024 * 1024) return;
                    if (bufSeen.has(ab)) return;
                    bufSeen.add(ab);
                    if (bufRefs.length >= MAX_TRACKED_BUFS) {
                        var nextB = [];
                        for (var i = 0; i < bufRefs.length; i++) {
                            var d = bufRefs[i].deref();
                            if (d && d.byteLength >= 2048) nextB.push(bufRefs[i]);
                        }
                        bufRefs = nextB.slice(-Math.floor(MAX_TRACKED_BUFS * 0.75));
                    }
                    bufRefs.push(typeof WeakRef === "function" ? new WeakRef(ab) : { deref: function() { return ab; } });
                } catch (_) {}
            }
            function scanExportsForMem(inst) {
                try {
                    var ex = inst && inst.exports ? inst.exports : (inst && inst.instance && inst.instance.exports ? inst.instance.exports : null);
                    if (!ex) return;
                    var keys = Object.keys(ex);
                    for (var i = 0; i < keys.length; i++) {
                        var v = ex[keys[i]];
                        if (v && typeof WebAssembly === "object" && v instanceof WebAssembly.Memory) trackWasmMem(v);
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
            } catch (_) {}

            function liveWasmMemories() {
                var live = [];
                var kept = [];
                for (var i = 0; i < wasmRefs.length; i++) {
                    var m = wasmRefs[i].deref();
                    if (m && m.buffer && m.buffer.byteLength > 0) {
                        kept.push(wasmRefs[i]);
                        live.push(m);
                    }
                }
                wasmRefs = kept;
                return live;
            }
            function liveRawBuffers(excludeSet) {
                var live = [];
                var kept = [];
                for (var i = 0; i < bufRefs.length; i++) {
                    var ab = bufRefs[i].deref();
                    if (ab && ab.byteLength >= 2048) {
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
                "devicePixelRatio", "length", "isSecureContext", "crossOriginIsolated", "origin",
                "name", "status", "closed", "toolbar", "menubar", "personalbar", "scrollbars",
                "statusbar", "locationbar", "clientInformation", "scheduler", "navigation",
                "cookieStore", "trustedTypes", "credentialless", "launchQueue", "documentPictureInPicture"
            ]);
            var SENSITIVE_KEY_RE = /(password|passwd|secret|token|jwt|auth|session_?id|csrf|cookie|api_?key|credit|card)/i;

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
                    var num = Number(valStr);
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
                    var mems = liveWasmMemories();
                    var m = mems[h.bufIdx];
                    if (m && m.buffer && h.off < m.buffer.byteLength) return { buf: m.buffer, byteOff: 0, byteLen: m.buffer.byteLength };
                    return null;
                }
                if (h.kind === "ab") {
                    var refA = abRegistry[h.bufIdx];
                    var ab = refA ? refA.deref() : null;
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
                        var num = Number(raw);
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
                        if (typeof cur === "number" || (h.dt !== "str" && !Number.isNaN(Number(valStr)) && String(valStr).trim() !== "")) {
                            obj[h.key] = Number(valStr);
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
                        if (typeof curM === "number" || (h.dt !== "str" && !Number.isNaN(Number(valStr)) && String(valStr).trim() !== "")) {
                            mp.set(h.key, Number(valStr));
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

            function scanBuffer(buf, byteOff, byteLen, kind, bufIdx, labelPrefix, dt, sc, v1, v2, s1, aob, out, cap) {
                if (!buf || byteLen <= 0 || out.length >= cap) return;
                var scanLen = Math.min(byteLen, 32 * 1024 * 1024);
                var dv = new DataView(buf, byteOff, scanLen);
                if (dt === "str") {
                    var enc = new TextEncoder().encode(s1);
                    if (!enc.length) return;
                    var u8 = new Uint8Array(buf, byteOff, scanLen);
                    var lim = scanLen - enc.length;
                    for (var o = 0; o <= lim && out.length < cap; o++) {
                        if (u8[o] !== enc[0]) continue;
                        var ok = true;
                        for (var k = 1; k < enc.length; k++) { if (u8[o + k] !== enc[k]) { ok = false; break; } }
                        if (ok) {
                            var id = labelPrefix + "+0x" + o.toString(16).toUpperCase();
                            out.push({ id: id, kind: kind, bufIdx: bufIdx, off: o, dt: "str", len: enc.length, prev: s1 });
                        }
                    }
                    return;
                }
                if (dt === "aob") {
                    if (!aob.bytes.length) return;
                    var u8b = new Uint8Array(buf, byteOff, scanLen);
                    var lim2 = scanLen - aob.bytes.length;
                    for (var o2 = 0; o2 <= lim2 && out.length < cap; o2++) {
                        var ok2 = true;
                        for (var k2 = 0; k2 < aob.bytes.length; k2++) {
                            if (aob.mask[k2] && u8b[o2 + k2] !== aob.bytes[k2]) { ok2 = false; break; }
                        }
                        if (ok2) {
                            var id2 = labelPrefix + "+0x" + o2.toString(16).toUpperCase();
                            out.push({ id: id2, kind: kind, bufIdx: bufIdx, off: o2, dt: "aob", len: aob.bytes.length, prev: s1 });
                        }
                    }
                    return;
                }
                var types = dt === "auto" ? (sc === "unknown" ? ["i32", "f32"] : ["i32", "f32", "f64"]) : [dt];
                for (var ti = 0; ti < types.length && out.length < cap; ti++) {
                    var t = types[ti];
                    var sz = t === "i8" ? 1 : (t === "i16" ? 2 : ((t === "i32" || t === "f32") ? 4 : 8));
                    var endOff = scanLen - sz;
                    for (var off = 0; off <= endOff && out.length < cap; off += sz) {
                        var val = readViewVal(dv, off, t);
                        if (matchNum(val, 0, false, t, sc, v1, v2)) {
                            var idn = labelPrefix + "+0x" + off.toString(16).toUpperCase() + ":" + t;
                            out.push({ id: idn, kind: kind, bufIdx: bufIdx, off: off, dt: t, len: sz, prev: val });
                        }
                    }
                }
            }

            function seedFrameworkRoots(win, queue, visited) {
                try {
                    var doc = win.document;
                    if (!doc || typeof doc.querySelectorAll !== "function") return;
                    var els = doc.querySelectorAll("canvas,#root,#app,#game,#unity-canvas");
                    var maxEls = Math.min(els.length, 10);
                    for (var ei = 0; ei < maxEls; ei++) {
                        var el = els[ei];
                        if (!el) continue;
                        var eKeys;
                        try { eKeys = Object.keys(el); } catch (_) { continue; }
                        for (var ki = 0; ki < eKeys.length; ki++) {
                            var ek = eKeys[ki];
                            if (ek.indexOf("__react") === 0 || ek.indexOf("__vue") === 0 || ek.indexOf("_") === 0 || ek === "game" || ek === "engine" || ek === "scene") {
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

            function collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, cap, scannedBufs) {
                var visited = new WeakSet();
                var queue = [{ obj: window, path: "window", depth: 0 }];
                seedFrameworkRoots(window, queue, visited);
                try {
                    var fCount = Math.min(window.frames ? window.frames.length : 0, 8);
                    for (var fi = 0; fi < fCount; fi++) {
                        try {
                            var fw = window.frames[fi];
                            if (fw && fw !== window && fw.document) {
                                queue.push({ obj: fw, path: "frame" + fi, depth: 0 });
                                seedFrameworkRoots(fw, queue, visited);
                            }
                        } catch (_) {}
                    }
                } catch (_) {}

                var maxNodes = 12000;
                var nodesVisited = 0;
                var taSeen = new WeakSet();
                taRegistry = [];

                while (queue.length > 0 && nodesVisited < maxNodes && out.length < cap) {
                    var cur = queue.shift();
                    var obj = cur.obj;
                    if (!obj || (typeof obj !== "object" && typeof obj !== "function")) continue;
                    if (visited.has(obj)) continue;
                    visited.add(obj);
                    nodesVisited++;

                    if (typeof WebAssembly === "object" && obj instanceof WebAssembly.Memory) {
                        trackWasmMem(obj);
                        continue;
                    }
                    if (ArrayBuffer.isView(obj) && obj.byteLength >= 4 && obj.byteLength <= 32 * 1024 * 1024 && !taSeen.has(obj)) {
                        taSeen.add(obj);
                        if (obj.buffer) scannedBufs.add(obj.buffer);
                        var taIdx = taRegistry.length;
                        taRegistry.push(typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } });
                        scanBuffer(obj.buffer, obj.byteOffset, obj.byteLength, "ta", taIdx, "ta#" + taIdx + "(" + cur.path.slice(-22) + ")", dt, sc, v1, v2, s1, aob, out, cap);
                        continue;
                    }
                    if (typeof ArrayBuffer === "function" && (obj instanceof ArrayBuffer || (typeof SharedArrayBuffer === "function" && obj instanceof SharedArrayBuffer))) {
                        if (!scannedBufs.has(obj) && obj.byteLength >= 4 && obj.byteLength <= 32 * 1024 * 1024) {
                            scannedBufs.add(obj);
                            var abIdx = abRegistry.length;
                            abRegistry.push(typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } });
                            scanBuffer(obj, 0, obj.byteLength, "ab", abIdx, "buf#" + abIdx + "(" + cur.path.slice(-20) + ")", dt, sc, v1, v2, s1, aob, out, cap);
                        }
                        continue;
                    }
                    if (typeof Node === "function" && obj instanceof Node) continue;

                    if (obj instanceof Map) {
                        var mCount = 0;
                        try {
                            obj.forEach(function(mv, mk) {
                                if (mCount >= 250 || out.length >= cap) return;
                                mCount++;
                                var keyLabel = String(mk).slice(0, 24);
                                if (SENSITIVE_KEY_RE.test(keyLabel)) return;
                                if (typeof mv === "number") {
                                    if (dt !== "str" && dt !== "aob" && matchNum(mv, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
                                        var refM = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                        out.push({ id: "map:" + cur.path + "[" + keyLabel + "]", kind: "map", objRef: refM, key: mk, dt: Number.isInteger(mv) ? "i32" : "f64", prev: mv });
                                    }
                                } else if (mv && typeof mv === "object" && cur.depth < 7 && !visited.has(mv)) {
                                    queue.push({ obj: mv, path: cur.path + "[" + keyLabel + "]", depth: cur.depth + 1 });
                                }
                            });
                        } catch (_) {}
                        continue;
                    }
                    if (obj instanceof Set) {
                        var sCount = 0;
                        try {
                            obj.forEach(function(sv) {
                                if (sCount >= 200 || out.length >= cap) return;
                                sCount++;
                                if (sv && typeof sv === "object" && cur.depth < 7 && !visited.has(sv)) {
                                    queue.push({ obj: sv, path: cur.path + "{#" + sCount + "}", depth: cur.depth + 1 });
                                }
                            });
                        } catch (_) {}
                        continue;
                    }

                    var keys;
                    try { keys = Object.keys(obj); } catch (_) { continue; }
                    var maxKeys = cur.depth === 0 ? keys.length : Math.min(keys.length, 240);
                    for (var i = 0; i < maxKeys && out.length < cap; i++) {
                        var k = keys[i];
                        if (cur.depth === 0 && (SKIP_KEYS.has(k) || (k.length > 2 && k[0] === "o" && k[1] === "n"))) continue;
                        if (SENSITIVE_KEY_RE.test(k)) continue;
                        var val;
                        try { val = obj[k]; } catch (_) { continue; }
                        var nextPath = cur.depth === 0 ? k : (cur.path + "." + k);
                        if (typeof val === "number") {
                            if (dt !== "str" && dt !== "aob" && matchNum(val, 0, false, dt === "auto" ? "f64" : dt, sc, v1, v2)) {
                                var ref = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                out.push({ id: "js:" + nextPath, kind: "js", objRef: ref, key: k, dt: Number.isInteger(val) ? "i32" : "f64", prev: val });
                            }
                        } else if (typeof val === "string") {
                            if ((dt === "str" || dt === "auto") && s1 && val.indexOf(s1) !== -1 && val.length <= 256) {
                                var refS = typeof WeakRef === "function" ? new WeakRef(obj) : { deref: function() { return obj; } };
                                out.push({ id: "js:" + nextPath, kind: "js", objRef: refS, key: k, dt: "str", prev: val });
                            }
                        } else if (val && typeof val === "object" && cur.depth < 7) {
                            if (!visited.has(val)) queue.push({ obj: val, path: nextPath, depth: cur.depth + 1 });
                        }
                    }
                }
            }

            function scanStorage(dt, sc, v1, v2, s1, out, cap) {
                var stores = [["ls", window.localStorage], ["ss", window.sessionStorage]];
                for (var si = 0; si < stores.length; si++) {
                    var tag = stores[si][0], st = stores[si][1];
                    if (!st) continue;
                    try {
                        for (var i = 0; i < st.length && out.length < cap; i++) {
                            var k = st.key(i);
                            if (!k || SENSITIVE_KEY_RE.test(k)) continue;
                            var raw = st.getItem(k);
                            if (raw === null || raw.length > 256) continue;
                            if (dt === "str") {
                                if (s1 && raw.indexOf(s1) !== -1) out.push({ id: tag + ":" + k, kind: tag, key: k, dt: "str", prev: raw });
                            } else if (dt !== "aob") {
                                var num = Number(raw);
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

            function formatHitsResult(maxRet) {
                var lim = Math.min(hits.length, maxRet || 150);
                var items = [];
                for (var i = 0; i < lim; i++) {
                    var h = hits[i];
                    watchRefs.set(h.id, h);
                    var rd = readHitCurrent(h);
                    items.push({
                        addr: h.id,
                        val: formatVal(rd.val, h.dt),
                        prev: formatVal(h.prev, h.dt),
                        type: h.dt,
                        kind: h.kind
                    });
                }
                return {
                    ok: true,
                    total: hits.length,
                    canUndo: prevHits.length > 0,
                    wasmCount: liveWasmMemories().length,
                    bufCount: abRegistry.length + taRegistry.length,
                    items: items
                };
            }

            function handleCmd(cmd) {
                try {
                    if (cmd.op === "ping") return { ok: true };
                    if (cmd.op === "reset") {
                        hits = []; prevHits = [];
                        return { ok: true, total: 0, canUndo: false, wasmCount: liveWasmMemories().length, bufCount: abRegistry.length + taRegistry.length, items: [] };
                    }
                    if (cmd.op === "refreshHits") {
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "undo") {
                        if (!prevHits.length) return { ok: false, error: "Nothing to undo" };
                        hits = prevHits; prevHits = [];
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "firstScan") {
                        prevHits = []; hits = []; abRegistry = [];
                        var dt = cmd.dtype || "auto";
                        var sc = cmd.scanType || "exact";
                        lastType = dt;
                        var s1 = String(cmd.val1 || "");
                        var s2 = String(cmd.val2 || "");
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
                        var mems = liveWasmMemories();
                        for (var mi = 0; mi < mems.length && out.length < cap; mi++) {
                            var mb = mems[mi].buffer;
                            scannedBufs.add(mb);
                            scanBuffer(mb, 0, mb.byteLength, "wasm", mi, "wasm#" + mi, dt, sc, v1, v2, s1, aob, out, cap);
                        }
                        collectJsAndTypedArrays(dt, sc, v1, v2, s1, aob, out, cap, scannedBufs);
                        var memsAfter = liveWasmMemories();
                        for (var mi2 = mems.length; mi2 < memsAfter.length && out.length < cap; mi2++) {
                            var mb2 = memsAfter[mi2].buffer;
                            if (!scannedBufs.has(mb2)) {
                                scannedBufs.add(mb2);
                                scanBuffer(mb2, 0, mb2.byteLength, "wasm", mi2, "wasm#" + mi2, dt, sc, v1, v2, s1, aob, out, cap);
                            }
                        }
                        var rawBufs = liveRawBuffers(scannedBufs);
                        for (var bi = 0; bi < rawBufs.length && out.length < cap; bi++) {
                            var rb = rawBufs[bi];
                            var abIdx = abRegistry.length;
                            abRegistry.push(typeof WeakRef === "function" ? new WeakRef(rb) : { deref: function() { return rb; } });
                            scanBuffer(rb, 0, rb.byteLength, "ab", abIdx, "buf#" + abIdx, dt, sc, v1, v2, s1, aob, out, cap);
                        }
                        scanStorage(dt, sc, v1, v2, s1, out, cap);
                        hits = out;
                        return formatHitsResult(cmd.maxReturn || 150);
                    }
                    if (cmd.op === "nextScan") {
                        if (!hits.length) return { ok: false, error: "Run First Scan first" };
                        var sc2 = cmd.scanType || "exact";
                        var s1n = String(cmd.val1 || "");
                        var s2n = String(cmd.val2 || "");
                        var v1n = Number(s1n), v2n = Number(s2n);
                        var needsV1 = (sc2 === "exact" || sc2 === "bigger" || sc2 === "smaller" || sc2 === "between" || sc2 === "increased_by" || sc2 === "decreased_by");
                        if (lastType !== "str" && lastType !== "aob" && needsV1 && (!s1n.trim() || !Number.isFinite(v1n))) {
                            return { ok: false, error: "Enter a valid number" };
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
                            var id = ids[ri];
                            var rec = watchRefs.get(id);
                            if (rec) {
                                var r = readHitCurrent(rec);
                                vals[id] = r.ok ? formatVal(r.val, rec.dt) : String(r.val);
                            } else {
                                vals[id] = "<expired>";
                            }
                        }
                        return { ok: true, values: vals };
                    }
                    if (cmd.op === "write") {
                        var recW = watchRefs.get(cmd.id);
                        if (!recW) return { ok: false, error: "Entry not found in page" };
                        var okW = writeHitValue(recW, cmd.val);
                        var rdW = readHitCurrent(recW);
                        return { ok: okW, val: formatVal(rdW.val, recW.dt) };
                    }
                    if (cmd.op === "hexDump") {
                        var recH = watchRefs.get(cmd.id);
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
                        return { ok: true, addr: "0x" + baseOff.toString(16).toUpperCase(), offset: baseOff, len: len, hex: hex };
                    }
                    if (cmd.op === "regions") {
                        var memsR = liveWasmMemories();
                        var regs = [];
                        var totalKb = 0;
                        for (var rmi = 0; rmi < memsR.length; rmi++) {
                            var kb = Math.round(memsR[rmi].buffer.byteLength / 1024);
                            totalKb += kb;
                            regs.push({ start: "wasm#" + rmi, end: "0x" + memsR[rmi].buffer.byteLength.toString(16).toUpperCase(), kb: kb, perms: "rw-wasm", label: "WebAssembly.Memory #" + rmi });
                        }
                        var seenR = new WeakSet();
                        for (var rmi2 = 0; rmi2 < memsR.length; rmi2++) seenR.add(memsR[rmi2].buffer);
                        var rawR = liveRawBuffers(seenR);
                        for (var rbi = 0; rbi < Math.min(rawR.length, 40); rbi++) {
                            var bkb = Math.max(1, Math.round(rawR[rbi].byteLength / 1024));
                            totalKb += bkb;
                            regs.push({ start: "buf#" + rbi, end: "0x" + rawR[rbi].byteLength.toString(16).toUpperCase(), kb: bkb, perms: "rw-buf", label: "ArrayBuffer #" + rbi });
                        }
                        for (var tai = 0; tai < Math.min(taRegistry.length, 40); tai++) {
                            var taObj = taRegistry[tai].deref();
                            if (taObj) {
                                var tkb = Math.max(1, Math.round(taObj.byteLength / 1024));
                                totalKb += tkb;
                                regs.push({ start: "ta#" + tai, end: "0x" + taObj.byteLength.toString(16).toUpperCase(), kb: tkb, perms: "rw-ta", label: taObj.constructor ? taObj.constructor.name : "TypedArray" });
                            }
                        }
                        return { ok: true, count: regs.length, totalKb: totalKb, regions: regs };
                    }
                    return { ok: false, error: "Unknown op" };
                } catch (e) {
                    return { ok: false, error: String(e && e.message ? e.message : e) };
                }
            }

            window.addEventListener(CH, function(ev) {
                var d = ev && ev.detail;
                if (!d || typeof d !== "object" || !d.box) return;
                d.box.ok = true;
                d.box.out = handleCmd(d);
            }, false);
        })();
        """.trimIndent()
    }

    fun buildBlinkCommandScript(cmdJson: String): String {
        val chJson = JSONObject.quote(CHANNEL_ID)
        val boot = buildBootstrapScriptInternal(CHANNEL_ID)
        return """
        (function() {
            var CH = $chJson;
            var cmd = $cmdJson;
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
            return JSON.stringify(res.ok && res.out ? res.out : { ok: false, error: "Page scanner unavailable" });
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
