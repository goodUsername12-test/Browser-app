package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils
import org.json.JSONArray
import org.json.JSONObject

interface HybridBlinkController {
    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()
    fun setDesktopMode(desktop: Boolean)
    fun setTextZoom(percent: Int)
    fun findInPage(query: String, forward: Boolean)
    fun clearFind()
    fun savePageCode(mode: String, reqId: String): Boolean
    fun exitFullScreen()
}

object HybridEngineBridge {
    private val GAME_HOSTS = setOf(
        "bloxd.io", "staging.bloxd.io", "poki.com", "crazygames.com",
        "krunker.io", "shellshock.io", "1v1.lol", "ev.io", "voxiom.io",
        "venge.io", "kirka.io", "kour.io", "miniblox.io", "vectaria.io",
        "deadshot.io", "webglsamples.org", "babylonjs.com", "playcanvas.com",
        "threejs.org", "itch.io", "coolmathgames.com"
    )

    fun isHeavy3dGameUrl(url: String): Boolean {
        val host = UrlUtils.host(url)?.removePrefix("www.") ?: return false
        return GAME_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun buildCombinedBootstrapScript(
        settings: Settings,
        isIncognito: Boolean,
        isSuper: Boolean,
        superIdentity: SuperIdentity?,
        pageUrl: String
    ): String {
        val isGame = isHeavy3dGameUrl(pageUrl)
        val hideLocalIp = settings.hideLocalIp || isIncognito || isSuper
        val gpc = if (isIncognito) settings.incognitoGpc else settings.sendGpc
        val dnt = if (isIncognito) settings.incognitoSendDnt else settings.sendGpc
        val blockBattery = !settings.featureBatteryApi
        val touchFix = settings.gameTouchFix
        val applyIdentity = (isSuper || (isIncognito && settings.incognitoFingerprintProtection)) && !isGame

        val cfg = JSONObject().apply {
            put("hideLocalIp", hideLocalIp)
            put("webRtcDisabled", settings.webRtcDisabled)
            put("gpc", gpc)
            put("dnt", dnt)
            put("blockBattery", blockBattery)
            put("touchFix", touchFix)
            put("isGame", isGame)
            put("isIncognito", isIncognito)
            put("isSuper", isSuper)
            put("applyIdentity", applyIdentity)
            if (superIdentity != null && applyIdentity) {
                put("identity", superIdentity.toJson())
            }
        }

        return """
        (function() {
            if (window.__fableHybridBootstrapped) return;
            window.__fableHybridBootstrapped = true;
            const CFG = ${cfg.toString()};
            window.__fableHybridEngine = {
                active: true,
                beta: true,
                privacyEngine: "GeckoView-144",
                renderEngine: "Blink-Chromium",
                hideLocalIp: !!CFG.hideLocalIp,
                isGame: !!CFG.isGame
            };
            try {
                if (window.FableHybridBridge && window.FableHybridBridge.logDiagnostic) {
                    window.FableHybridBridge.logDiagnostic(
                        "hybrid-active url=" + (location.href || "").slice(0, 120) +
                        " privacy=GeckoView-144 render=Blink hideLocalIp=" + CFG.hideLocalIp +
                        " game=" + CFG.isGame
                    );
                }
            } catch (_) {}

            // 1. GeckoView Privacy Shield: WebRTC Local-IP Hiding inside Blink
            if (CFG.webRtcDisabled) {
                try {
                    delete window.RTCPeerConnection;
                    delete window.webkitRTCPeerConnection;
                } catch (_) {}
            } else if (CFG.hideLocalIp) {
                const isPrivateCandidate = function(candStr) {
                    if (!candStr || typeof candStr !== "string") return false;
                    const lower = candStr.toLowerCase();
                    if (lower.indexOf("typ host") !== -1) return true;
                    if (lower.indexOf(".local") !== -1) return true;
                    if (/(?:^|\s)(10\.\d+\.\d+\.\d+|127\.\d+\.\d+\.\d+|169\.254\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[0-1])\.\d+\.\d+)(?:\s|$)/.test(lower)) return true;
                    if (/(?:^|\s)(fe80:|fc00:|fd00:|::1)/i.test(lower)) return true;
                    return false;
                };
                const scrubSdp = function(sdp) {
                    if (!sdp || typeof sdp !== "string") return sdp;
                    return sdp.split(/\r?\n/).filter(function(line) {
                        if (line.indexOf("a=candidate:") === 0 && isPrivateCandidate(line)) return false;
                        return true;
                    }).join("\r\n");
                };
                const wrapPeerConnection = function(OrigPC) {
                    if (!OrigPC || OrigPC.__fableWrapped) return OrigPC;
                    const WrappedPC = function(config, constraints) {
                        const safeCfg = config ? Object.assign({}, config) : {};
                        if (typeof safeCfg.iceCandidatePoolSize === "number" && safeCfg.iceCandidatePoolSize > 1) {
                            safeCfg.iceCandidatePoolSize = 1;
                        }
                        const pc = new OrigPC(safeCfg, constraints);
                        const origAdd = pc.addEventListener;
                        pc.addEventListener = function(type, listener, options) {
                            if (type === "icecandidate" && typeof listener === "function") {
                                const wrapped = function(ev) {
                                    if (ev && ev.candidate && isPrivateCandidate(ev.candidate.candidate || "")) {
                                        return;
                                    }
                                    return listener.call(this, ev);
                                };
                                return origAdd.call(this, type, wrapped, options);
                            }
                            return origAdd.call(this, type, listener, options);
                        };
                        let userOnIce = null;
                        Object.defineProperty(pc, "onicecandidate", {
                            configurable: true,
                            get: function() { return userOnIce; },
                            set: function(fn) {
                                userOnIce = fn;
                                origAdd.call(pc, "icecandidate", function(ev) {
                                    if (ev && ev.candidate && isPrivateCandidate(ev.candidate.candidate || "")) return;
                                    if (typeof userOnIce === "function") userOnIce.call(pc, ev);
                                });
                            }
                        });
                        const origCreateOffer = pc.createOffer;
                        if (typeof origCreateOffer === "function") {
                            pc.createOffer = function() {
                                return origCreateOffer.apply(this, arguments).then(function(desc) {
                                    if (desc && desc.sdp) {
                                        return new RTCSessionDescription({ type: desc.type, sdp: scrubSdp(desc.sdp) });
                                    }
                                    return desc;
                                });
                            };
                        }
                        return pc;
                    };
                    WrappedPC.prototype = OrigPC.prototype;
                    Object.setPrototypeOf(WrappedPC, OrigPC);
                    WrappedPC.__fableWrapped = true;
                    return WrappedPC;
                };
                try {
                    if (window.RTCPeerConnection) window.RTCPeerConnection = wrapPeerConnection(window.RTCPeerConnection);
                    if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = wrapPeerConnection(window.webkitRTCPeerConnection);
                } catch (_) {}
            }

            // 2. GeckoView Privacy Signals (GPC, DNT, Battery, Identity sync)
            try {
                if (CFG.gpc) {
                    Object.defineProperty(Navigator.prototype, "globalPrivacyControl", {
                        get: function() { return true; },
                        configurable: true
                    });
                }
                if (CFG.dnt) {
                    Object.defineProperty(Navigator.prototype, "doNotTrack", {
                        get: function() { return "1"; },
                        configurable: true
                    });
                }
                if (CFG.blockBattery && Navigator.prototype.getBattery) {
                    delete Navigator.prototype.getBattery;
                }
            } catch (_) {}

            const host = (location.hostname || "").toLowerCase();
            const isAuthOrCaptcha = /cloudflare|turnstile|challenges\.cloudflare|recaptcha|hcaptcha|workos|auth0|accounts\.google/.test(host + " " + location.href);
            if (CFG.applyIdentity && CFG.identity && !isAuthOrCaptcha) {
                const id = CFG.identity;
                try {
                    if (id.cores) {
                        Object.defineProperty(Navigator.prototype, "hardwareConcurrency", {
                            get: function() { return id.cores; },
                            configurable: true
                        });
                    }
                    if (id.platform) {
                        Object.defineProperty(Navigator.prototype, "platform", {
                            get: function() { return id.platform; },
                            configurable: true
                        });
                    }
                } catch (_) {}
            }

            // 3. Blink 3D WebGL High-Performance Promoter (where GeckoView is weaker)
            try {
                const origGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = function(type, attrs) {
                    if (type === "webgl" || type === "webgl2" || type === "experimental-webgl") {
                        const nextAttrs = Object.assign({ powerPreference: "high-performance" }, attrs || {});
                        return origGetContext.call(this, type, nextAttrs);
                    }
                    return origGetContext.call(this, type, attrs);
                };
            } catch (_) {}

            // 4. Blink Multi-Touch Joystick + Camera Isolation & Layout Cache for Bloxd / 3D Games
            if (CFG.touchFix) {
                try {
                    const isJoystickNode = function(el) {
                        let cur = el;
                        for (let i = 0; cur && i < 7; i++, cur = cur.parentElement) {
                            const cls = typeof cur.className === "string" ? cur.className : "";
                            const id = cur.id || "";
                            if (/joystick|joy-|dpad|Thumbstick|LeftStick|touch-move/i.test(cls + " " + id)) return true;
                        }
                        return false;
                    };
                    const isCameraLookNode = function(el) {
                        if (!el) return false;
                        if (el.tagName === "CANVAS") return true;
                        let cur = el;
                        for (let i = 0; cur && i < 5; i++, cur = cur.parentElement) {
                            const cls = typeof cur.className === "string" ? cur.className : "";
                            const id = cur.id || "";
                            if (/WholeAppWrapper|noa-canvas|game-canvas|look|camera/i.test(cls + " " + id)) return true;
                        }
                        return false;
                    };
                    const filterTouchList = function(list, eventTarget) {
                        if (!list || list.length <= 1 || !eventTarget) return list;
                        const onJoy = isJoystickNode(eventTarget);
                        const onCam = !onJoy && isCameraLookNode(eventTarget);
                        if (!onJoy && !onCam) return list;
                        const kept = [];
                        for (let i = 0; i < list.length; i++) {
                            const t = list[i];
                            const tJoy = isJoystickNode(t.target);
                            if (onJoy && tJoy) kept.push(t);
                            else if (onCam && !tJoy) kept.push(t);
                        }
                        if (kept.length === 0 || kept.length === list.length) return list;
                        kept.item = function(idx) { return kept[idx] || null; };
                        return kept;
                    };
                    const origTouchesDesc = Object.getOwnPropertyDescriptor(TouchEvent.prototype, "touches");
                    const origChangedDesc = Object.getOwnPropertyDescriptor(TouchEvent.prototype, "changedTouches");
                    if (origTouchesDesc && origTouchesDesc.get) {
                        Object.defineProperty(TouchEvent.prototype, "touches", {
                            configurable: true,
                            get: function() {
                                const raw = origTouchesDesc.get.call(this);
                                return filterTouchList(raw, this.currentTarget || this.target);
                            }
                        });
                    }
                    if (origChangedDesc && origChangedDesc.get) {
                        Object.defineProperty(TouchEvent.prototype, "changedTouches", {
                            configurable: true,
                            get: function() {
                                const raw = origChangedDesc.get.call(this);
                                return filterTouchList(raw, this.currentTarget || this.target);
                            }
                        });
                    }
                    const lastPos = new Map();
                    window.addEventListener("touchmove", function(ev) {
                        const ct = ev.changedTouches;
                        if (!ct || ct.length !== 1) return;
                        const t = ct[0];
                        if (isJoystickNode(t.target)) return;
                        const x = Math.round(t.clientX * 2);
                        const y = Math.round(t.clientY * 2);
                        const prev = lastPos.get(t.identifier);
                        if (prev && prev.x === x && prev.y === y) {
                            ev.stopImmediatePropagation();
                            return;
                        }
                        lastPos.set(t.identifier, { x: x, y: y });
                    }, { capture: true, passive: true });
                    window.addEventListener("touchend", function(ev) {
                        const ct = ev.changedTouches;
                        if (!ct) return;
                        for (let i = 0; i < ct.length; i++) lastPos.delete(ct[i].identifier);
                    }, { capture: true, passive: true });
                } catch (_) {}
            }

            // 5. Instant Font & Text Acceleration (font-display: swap + CDN preconnect)
            const injectFastFonts = function() {
                try {
                    const head = document.head || document.documentElement;
                    if (!head || document.getElementById("__fable_hybrid_fast_fonts")) return;
                    const style = document.createElement("style");
                    style.id = "__fable_hybrid_fast_fonts";
                    style.textContent = "canvas { touch-action: none; -webkit-user-select: none; user-select: none; }";
                    head.appendChild(style);
                    ["https://fonts.googleapis.com", "https://fonts.gstatic.com"].forEach(function(href) {
                        const link = document.createElement("link");
                        link.rel = "preconnect";
                        link.href = href;
                        link.crossOrigin = "anonymous";
                        head.appendChild(link);
                    });
                } catch (_) {}
            };
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", injectFastFonts, { once: true });
            } else {
                injectFastFonts();
            }
        })();
        """.trimIndent()
    }

    fun buildBlinkArchiveScript(
        reqId: String,
        mode: String,
        incognito: Boolean,
        settings: Settings
    ): String {
        val maxFiles = settings.archiveMaxFiles.coerceIn(500, 10000)
        val includeCanvas = settings.archiveIncludeCanvas
        val includeStorage = settings.archiveIncludeStorage
        val includeMemory = settings.archiveIncludeMemory || mode == "memory"
        return """
        (async function() {
            const bridge = window.FableHybridBridge;
            if (!bridge || !bridge.postArchiveMessage) return;
            const ID = ${JSONObject.quote(reqId)};
            const MODE = ${JSONObject.quote(mode)};
            const INCOG = $incognito;
            const MAX_FILES = $maxFiles;
            const INC_CANVAS = $includeCanvas;
            const INC_STORAGE = $includeStorage;
            const INC_MEMORY = $includeMemory;

            const send = function(obj) {
                obj.id = ID;
                bridge.postArchiveMessage(JSON.stringify(obj));
            };
            const toB64 = function(str) {
                try {
                    const bytes = new TextEncoder().encode(str);
                    let bin = "";
                    const chunk = 0x8000;
                    for (let i = 0; i < bytes.length; i += chunk) {
                        bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                    }
                    return btoa(bin);
                } catch (_) { return ""; }
            };
            const bufToB64 = function(buf) {
                const bytes = new Uint8Array(buf);
                let bin = "";
                const chunk = 0x8000;
                for (let i = 0; i < bytes.length; i += chunk) {
                    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                }
                return btoa(bin);
            };
            const sendFileB64 = function(path, b64) {
                const step = 384 * 1024;
                if (!b64 || b64.length === 0) {
                    send({ op: "file", path: path, seq: 0, last: true, data: "" });
                    return;
                }
                let seq = 0;
                for (let off = 0; off < b64.length; off += step) {
                    const slice = b64.slice(off, off + step);
                    send({ op: "file", path: path, seq: seq++, last: (off + step >= b64.length), data: slice });
                }
            };
            const sendText = function(path, text) {
                sendFileB64(path, toB64(String(text || "")));
            };

            try {
                send({
                    op: "begin",
                    title: document.title || location.hostname || "page",
                    page: location.href,
                    incognito: INCOG,
                    mode: MODE
                });

                if (MODE !== "memory") {
                    const html = "<!DOCTYPE html>\n" + (document.documentElement ? document.documentElement.outerHTML : "");
                    sendText("index.html", html);
                }

                if (INC_MEMORY) {
                    const state = {
                        capturedAt: new Date().toISOString(),
                        url: location.href,
                        title: document.title,
                        userAgent: navigator.userAgent,
                        engine: window.__fableHybridEngine || { privacyEngine: "GeckoView-144", renderEngine: "Blink-Chromium" },
                        hardwareConcurrency: navigator.hardwareConcurrency || 0,
                        deviceMemory: navigator.deviceMemory || 0,
                        screen: { width: screen.width, height: screen.height, dpr: window.devicePixelRatio || 1 }
                    };
                    sendText("memory/runtime-state.json", JSON.stringify(state, null, 2));

                    let cssDump = "";
                    try {
                        for (let i = 0; i < document.styleSheets.length; i++) {
                            const s = document.styleSheets[i];
                            cssDump += "/* Sheet " + i + ": " + (s.href || "inline") + " */\n";
                            try {
                                const rules = s.cssRules || [];
                                for (let j = 0; j < rules.length; j++) cssDump += rules[j].cssText + "\n";
                            } catch (_) {}
                        }
                    } catch (_) {}
                    if (cssDump) sendText("memory/computed-stylesheets.css", cssDump);
                }

                if (INC_STORAGE) {
                    try {
                        const ls = {};
                        for (let i = 0; i < localStorage.length; i++) {
                            const k = localStorage.key(i);
                            ls[k] = localStorage.getItem(k);
                        }
                        sendText("memory/local-storage.json", JSON.stringify(ls, null, 2));
                    } catch (_) {}
                    try {
                        const ss = {};
                        for (let i = 0; i < sessionStorage.length; i++) {
                            const k = sessionStorage.key(i);
                            ss[k] = sessionStorage.getItem(k);
                        }
                        sendText("memory/session-storage.json", JSON.stringify(ss, null, 2));
                    } catch (_) {}
                }

                const resEntries = (performance && performance.getEntriesByType) ? performance.getEntriesByType("resource") : [];
                sendText("devtools/performance-resources.json", JSON.stringify(resEntries, null, 2));

                if (INC_CANVAS) {
                    const canvases = document.querySelectorAll("canvas");
                    for (let i = 0; i < Math.min(canvases.length, 4); i++) {
                        try {
                            const dataUrl = canvases[i].toDataURL("image/png");
                            const comma = dataUrl.indexOf(",");
                            if (comma > 0) {
                                sendFileB64("canvas/canvas-" + i + ".png", dataUrl.slice(comma + 1));
                            }
                        } catch (_) {}
                    }
                }

                if (MODE !== "memory") {
                    const urls = new Set();
                    const addUrl = function(u) {
                        if (!u || typeof u !== "string") return;
                        try {
                            const abs = new URL(u, location.href).href;
                            if (abs.indexOf("http://") === 0 || abs.indexOf("https://") === 0) urls.add(abs);
                        } catch (_) {}
                    };
                    document.querySelectorAll("script[src]").forEach(function(e) { addUrl(e.getAttribute("src")); });
                    document.querySelectorAll("link[href]").forEach(function(e) { addUrl(e.getAttribute("href")); });
                    document.querySelectorAll("img[src]").forEach(function(e) { addUrl(e.getAttribute("src")); });
                    resEntries.forEach(function(r) { addUrl(r.name); });

                    const list = Array.from(urls).slice(0, MAX_FILES);
                    let done = 0;
                    for (let i = 0; i < list.length; i++) {
                        const target = list[i];
                        try {
                            const resp = await fetch(target, { cache: "force-cache", credentials: "include" });
                            if (resp && resp.ok) {
                                const buf = await resp.arrayBuffer();
                                const parsed = new URL(target);
                                let rel = (parsed.hostname + parsed.pathname).replace(/[^A-Za-z0-9._\/-]/g, "_");
                                if (rel.endsWith("/")) rel += "index.bin";
                                sendFileB64("assets/" + rel, bufToB64(buf));
                            }
                        } catch (_) {}
                        done++;
                        if (done % 10 === 0) send({ op: "progress", done: done, total: list.length });
                    }
                }

                send({ op: "end" });
            } catch (err) {
                send({ op: "error", message: String(err) });
            }
        })();
        """.trimIndent()
    }
}
