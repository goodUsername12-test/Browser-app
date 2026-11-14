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
    private val BENCHMARK_HOSTS = setOf(
        "browserbench.org", "web.basemark.com", "html5test.com"
    )

    private val PURE_GAME_HOSTS = setOf(
        "bloxd.io", "staging.bloxd.io", "testufo.com", "ufo.test",
        "vsynctester.com", "displayhz.com", "poki.com", "crazygames.com",
        "krunker.io", "shellshock.io", "1v1.lol", "ev.io", "voxiom.io",
        "venge.io", "kirka.io", "kour.io", "miniblox.io", "vectaria.io",
        "deadshot.io", "babylonjs.com", "playcanvas.com",
        "threejs.org", "itch.io", "coolmathgames.com"
    )

    fun isPure3dGameUrl(url: String, customDomains: String = ""): Boolean {
        val host = UrlUtils.host(url)?.removePrefix("www.") ?: return false
        if (BENCHMARK_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        if (url.contains("hybrid=1", ignoreCase = true)) return true
        if (PURE_GAME_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (customDomains.isNotBlank()) {
            val extra = customDomains.split(',', ';', ' ', '\n', '\r', '\t')
                .map { it.trim().lowercase().removePrefix("www.") }
                .filter { it.isNotEmpty() && it !in BENCHMARK_HOSTS }
            if (extra.any { host == it || host.endsWith(".$it") }) return true
        }
        return false
    }

    fun isBenchmarkUrl(url: String): Boolean {
        val host = UrlUtils.host(url)?.removePrefix("www.") ?: return false
        return BENCHMARK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isHeavy3dGameUrl(url: String, customDomains: String = ""): Boolean {
        if (isBenchmarkUrl(url)) return true
        return isPure3dGameUrl(url, customDomains)
    }

    fun buildCombinedBootstrapScript(
        settings: Settings,
        isIncognito: Boolean,
        isSuper: Boolean,
        superIdentity: SuperIdentity?,
        pageUrl: String,
        isDesktopMode: Boolean = false
    ): String {
        val isPureGame = isPure3dGameUrl(pageUrl, settings.hybridCustomDomains)
        val isGameOrBench = isHeavy3dGameUrl(pageUrl, settings.hybridCustomDomains)
        val plan = FableBatteryGovernor.evaluatePlan(settings, isPureGame)
        val hideLocalIp = settings.hybridHideLocalIp && (settings.hideLocalIp || isIncognito || isSuper)
        val gpc = if (isIncognito) settings.incognitoGpc else settings.sendGpc
        val dnt = if (isIncognito) settings.incognitoSendDnt else settings.sendGpc
        val blockBattery = !settings.featureBatteryApi
        val touchFix = settings.gameTouchFix && settings.hybridCameraAntiShutter && isPureGame
        val applyIdentity = (isSuper || (isIncognito && settings.incognitoFingerprintProtection) || settings.normalTabShield) && !isGameOrBench

        val cfg = JSONObject().apply {
            put("hideLocalIp", hideLocalIp)
            put("webRtcDisabled", settings.webRtcDisabled)
            put("gpc", gpc)
            put("dnt", dnt)
            put("blockBattery", blockBattery)
            put("touchFix", touchFix)
            put("isGame", isPureGame)
            put("gameMaxFps", plan.targetGameFps)
            put("renderScalePct", plan.renderScalePercent)
            put("idleGameFps", settings.batteryIdleGameFps)
            put("idleTimeoutMs", settings.batteryIdleTimeoutSec.coerceIn(3, 60) * 1000)
            put("reduceMotion", settings.batteryReduceMotion && !isPureGame)
            put("desktopMode", isDesktopMode)
            put("pageZoomPct", settings.textZoomPercent.coerceIn(50, 200))
            put("isIncognito", isIncognito)
            put("isSuper", isSuper)
            put("applyIdentity", applyIdentity)
            put("highPerfWebGl", settings.hybridHighPerfWebGl)
            put("webGpuBoost", settings.hybridWebGpuBoost)
            put("accel3d2d", settings.hybrid3d2dAccel)
            put("teleportStabilizer", settings.hybridTeleportStabilizer)
            put("reportedCores", settings.hybridReportedCores.coerceIn(2, 16))
            put("suppressPostTimer", settings.hybridSuppressPostRenderTimer)
            put("zeroDelayClicks", settings.hybridZeroDelayClicks)
            put("canvasCacheMs", settings.hybridCanvasRectCacheMs.coerceIn(0, 1000))
            put("fastFontSwap", settings.hybridFastFontSwap)
            if (superIdentity != null && applyIdentity) {
                put("identity", superIdentity.toJson())
            }
        }

        val zoomInit = settings.textZoomPercent.coerceIn(50, 200)
        return """
        (function() {
            if (window.__fableHybridBootstrapped) {
                try {
                    if (window.__fableSetPageZoom) window.__fableSetPageZoom($zoomInit);
                    if (window.__fableEnforceDesktopViewport && $isDesktopMode) window.__fableEnforceDesktopViewport();
                } catch (_) {}
                return;
            }
            window.__fableHybridBootstrapped = true;
            const CFG = ${cfg.toString()};

            // 0a. Persistent Game Account Storage Vault (prevents Bloxd / 3D games from ever resetting to a new account!)
            if (CFG.isGame && !CFG.isIncognito && !CFG.isSuper) try {
                const vHost = (location.hostname || "").toLowerCase();
                if (vHost && window.FableHybridBridge && typeof window.FableHybridBridge.readAccountVault === "function") {
                    const rawVault = window.FableHybridBridge.readAccountVault(vHost);
                    if (rawVault && rawVault.length > 2) {
                        const savedMap = JSON.parse(rawVault);
                        const keys = Object.keys(savedMap);
                        for (let i = 0; i < keys.length; i++) {
                            const k = keys[i];
                            if (localStorage.getItem(k) === null && typeof savedMap[k] === "string") {
                                localStorage.setItem(k, savedMap[k]);
                            }
                        }
                    }
                    let syncTimer = 0;
                    const flushVaultNow = function() {
                        syncTimer = 0;
                        try {
                            const len = localStorage.length;
                            if (len <= 0) return;
                            const dump = {};
                            let count = 0;
                            for (let i = 0; i < len && count < 120; i++) {
                                const k = localStorage.key(i);
                                if (!k || k.indexOf("__fable_") === 0) continue;
                                const v = localStorage.getItem(k);
                                if (typeof v === "string" && v.length <= 16384) {
                                    dump[k] = v;
                                    count++;
                                }
                            }
                            if (count > 0) {
                                window.FableHybridBridge.writeAccountVault(vHost, JSON.stringify(dump));
                            }
                        } catch (_) {}
                    };
                    const scheduleVaultSync = function() {
                        if (syncTimer) return;
                        syncTimer = window.setTimeout(flushVaultNow, 800);
                    };
                    const origSetItem = Storage.prototype.setItem;
                    Storage.prototype.setItem = function(k, v) {
                        const r = origSetItem.apply(this, arguments);
                        if (this === window.localStorage) scheduleVaultSync();
                        return r;
                    };
                    window.addEventListener("pagehide", flushVaultNow, { passive: true });
                    document.addEventListener("visibilitychange", function() {
                        if (document.visibilityState === "hidden") flushVaultNow();
                    }, { passive: true });
                    window.addEventListener("load", scheduleVaultSync, { once: true, passive: true });
                }
            } catch (_) {}

            // 0. Full-Page Zoom & PC Desktop Mode Engine (works on all Hybrid pages)
            try {
                let currentZoomPct = CFG.pageZoomPct || 100;
                const applyZoomDom = function(pct) {
                    currentZoomPct = pct || 100;
                    const root = document.documentElement;
                    if (!root) return;
                    if (currentZoomPct === 100) {
                        if (root.style.zoom) root.style.zoom = "";
                    } else {
                        root.style.zoom = String(currentZoomPct / 100);
                    }
                };
                window.__fableSetPageZoom = applyZoomDom;
                if (currentZoomPct !== 100) {
                    applyZoomDom(currentZoomPct);
                    document.addEventListener("DOMContentLoaded", function() { applyZoomDom(currentZoomPct); }, { once: true });
                }

                if (CFG.desktopMode) {
                    try {
                        const deskBrands = [
                            { brand: "Chromium", version: "134" },
                            { brand: "Not:A-Brand", version: "24" },
                            { brand: "Google Chrome", version: "134" }
                        ];
                        const deskUaData = {
                            brands: deskBrands,
                            mobile: false,
                            platform: "Windows",
                            getHighEntropyValues: function() {
                                return Promise.resolve({
                                    architecture: "x86",
                                    bitness: "64",
                                    brands: deskBrands,
                                    fullVersionList: deskBrands,
                                    mobile: false,
                                    model: "",
                                    platform: "Windows",
                                    platformVersion: "15.0.0",
                                    uaFullVersion: "134.0.0.0"
                                });
                            },
                            toJSON: function() { return { brands: deskBrands, mobile: false, platform: "Windows" }; }
                        };
                        Object.defineProperty(Navigator.prototype, "userAgentData", { get: function() { return deskUaData; }, configurable: true });
                        Object.defineProperty(Navigator.prototype, "platform", { get: function() { return "Win32"; }, configurable: true });
                        Object.defineProperty(Screen.prototype, "width", { get: function() { return 1920; }, configurable: true });
                        Object.defineProperty(Screen.prototype, "height", { get: function() { return 1080; }, configurable: true });
                        Object.defineProperty(Screen.prototype, "availWidth", { get: function() { return 1920; }, configurable: true });
                        Object.defineProperty(Screen.prototype, "availHeight", { get: function() { return 1040; }, configurable: true });
                    } catch (_) {}

                    const DESK_VP = "width=1280, initial-scale=0.1, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes";
                    const enforceDesktopVp = function() {
                        const head = document.head || document.documentElement;
                        if (!head) return;
                        const metas = document.querySelectorAll('meta[name="viewport"], meta[name="VIEWPORT"]');
                        if (metas.length === 0) {
                            const m = document.createElement("meta");
                            m.name = "viewport";
                            m.content = DESK_VP;
                            head.appendChild(m);
                        } else {
                            for (let i = 0; i < metas.length; i++) {
                                if (metas[i].content !== DESK_VP) metas[i].content = DESK_VP;
                            }
                        }
                    };
                    window.__fableEnforceDesktopViewport = enforceDesktopVp;
                    enforceDesktopVp();
                    document.addEventListener("DOMContentLoaded", enforceDesktopVp, { once: true });
                    window.addEventListener("load", enforceDesktopVp, { once: true });
                    if (typeof MutationObserver === "function") {
                        const vpObs = new MutationObserver(function() { enforceDesktopVp(); });
                        const startVpObs = function() {
                            const h = document.head || document.documentElement;
                            if (h) vpObs.observe(h, { childList: true, subtree: true, attributes: true, attributeFilter: ["content"] });
                        };
                        startVpObs();
                        document.addEventListener("DOMContentLoaded", startVpObs, { once: true });
                        setTimeout(function() { try { vpObs.disconnect(); } catch (_) {} }, 12000);
                    }
                }
            } catch (_) {}
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
                        "hybrid-active url=" + (location.href || "").slice(0, 100) +
                        " privacy=GeckoView-144 render=Blink hideLocalIp=" + CFG.hideLocalIp +
                        " dnt=" + CFG.dnt + " gpc=" + CFG.gpc +
                        " shield=" + CFG.applyIdentity + " game=" + CFG.isGame +
                        " webgpu=" + CFG.webGpuBoost + " accel3d2d=" + CFG.accel3d2d +
                        " teleport=" + CFG.teleportStabilizer + " wasmCache=true"
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
            if (CFG.applyIdentity && !isAuthOrCaptcha) {
                const id = CFG.identity || {};
                const shieldCores = id.cores || 8;
                const shieldPlatform = id.platform || "Linux armv81";
                const shieldLangs = (Array.isArray(id.languages) && id.languages.length > 0) ? id.languages : ["en-US", "en"];
                const shieldLang = shieldLangs[0] || "en-US";
                const shieldTz = id.timeZone || "UTC";
                const shieldGlVendor = id.glVendor || "Qualcomm";
                const shieldGlRenderer = id.glRenderer || "Adreno (TM) 650";
                const shieldW = id.screenWidth || 360;
                const shieldH = id.screenHeight || 800;
                const shieldAvailH = id.availHeight || 752;
                const shieldDepth = id.colorDepth || 24;
                const shieldTouch = (typeof id.touchPoints === "number") ? id.touchPoints : 5;
                const noiseDelta = ((id.seed ? Number(id.seed & 7) : 3) % 5) + 1;

                try {
                    Object.defineProperty(Navigator.prototype, "hardwareConcurrency", { get: function() { return shieldCores; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "deviceMemory", { get: function() { return 8; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "platform", { get: function() { return shieldPlatform; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "language", { get: function() { return shieldLang; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "languages", { get: function() { return shieldLangs.slice(); }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "maxTouchPoints", { get: function() { return shieldTouch; }, configurable: true });
                    if ("userAgentData" in Navigator.prototype) {
                        Object.defineProperty(Navigator.prototype, "userAgentData", { get: function() { return undefined; }, configurable: true });
                    }
                } catch (_) {}

                try {
                    Object.defineProperty(Screen.prototype, "width", { get: function() { return shieldW; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "height", { get: function() { return shieldH; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "availWidth", { get: function() { return shieldW; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "availHeight", { get: function() { return shieldAvailH; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "colorDepth", { get: function() { return shieldDepth; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "pixelDepth", { get: function() { return shieldDepth; }, configurable: true });
                } catch (_) {}

                try {
                    if (shieldTz === "UTC") {
                        Date.prototype.getTimezoneOffset = function() { return 0; };
                    }
                    if (window.Intl && Intl.DateTimeFormat) {
                        const origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                        Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                            const res = origResolved.apply(this, arguments);
                            if (res && typeof res === "object") res.timeZone = shieldTz;
                            return res;
                        };
                    }
                } catch (_) {}

                try {
                    const patchWebGl = function(Proto) {
                        if (!Proto || !Proto.getParameter) return;
                        const origGetParam = Proto.getParameter;
                        Proto.getParameter = function(p) {
                            if (p === 37445) return shieldGlVendor;
                            if (p === 37446) return shieldGlRenderer;
                            return origGetParam.apply(this, arguments);
                        };
                    };
                    if (window.WebGLRenderingContext) patchWebGl(WebGLRenderingContext.prototype);
                    if (window.WebGL2RenderingContext) patchWebGl(WebGL2RenderingContext.prototype);
                } catch (_) {}

                try {
                    const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                    CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {
                        const img = origGetImageData.apply(this, arguments);
                        if (img && img.data && img.data.length >= 16 && sw <= 600 && sh <= 400) {
                            img.data[0] = (img.data[0] + noiseDelta) & 255;
                            img.data[4] = (img.data[4] ^ noiseDelta) & 255;
                        }
                        return img;
                    };
                    const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    HTMLCanvasElement.prototype.toDataURL = function() {
                        try {
                            if (this.width > 0 && this.width <= 600 && this.height > 0 && this.height <= 400) {
                                const ctx2d = this.getContext("2d");
                                if (ctx2d) {
                                    const px = origGetImageData.call(ctx2d, 0, 0, 1, 1);
                                    px.data[0] = (px.data[0] + noiseDelta) & 255;
                                    ctx2d.putImageData(px, 0, 0);
                                }
                            }
                        } catch (_) {}
                        return origToDataURL.apply(this, arguments);
                    };
                } catch (_) {}

                try {
                    if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
                        const origChannel = AudioBuffer.prototype.getChannelData;
                        AudioBuffer.prototype.getChannelData = function() {
                            const data = origChannel.apply(this, arguments);
                            if (data && data.length > 0 && !data.__fableNoised) {
                                data[0] = data[0] + (noiseDelta * 1e-7);
                                Object.defineProperty(data, "__fableNoised", { value: true });
                            }
                            return data;
                        };
                    }
                    if (window.speechSynthesis) {
                        window.speechSynthesis.getVoices = function() { return []; };
                    }
                } catch (_) {}
            }

            // 3. Blink 3D/2D WebGL, OffscreenCanvas & WebGPU High-Performance Promoter
            if (CFG.highPerfWebGl !== false || CFG.accel3d2d !== false) try {
                const warmGlExtensions = function(gl) {
                    if (!gl || gl.__fableExtWarmed || typeof gl.getExtension !== "function") return;
                    gl.__fableExtWarmed = true;
                    const fastExts = [
                        "OES_vertex_array_object",
                        "ANGLE_instanced_arrays",
                        "OES_texture_float_linear",
                        "OES_texture_half_float_linear",
                        "EXT_color_buffer_float",
                        "WEBGL_compressed_texture_astc",
                        "WEBGL_compressed_texture_etc"
                    ];
                    for (let i = 0; i < fastExts.length; i++) {
                        try { gl.getExtension(fastExts[i]); } catch (_) {}
                    }
                };
                const origGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = function(type, attrs) {
                    if (type === "webgl" || type === "webgl2" || type === "experimental-webgl") {
                        const nextAttrs = Object.assign({
                            powerPreference: "high-performance",
                            desynchronized: !!CFG.isGame && (CFG.accel3d2d !== false)
                        }, attrs || {});
                        const ctx = origGetContext.call(this, type, nextAttrs);
                        if (ctx && CFG.isGame) warmGlExtensions(ctx);
                        if (this && !this.__fableCtxLostHooked && typeof this.addEventListener === "function") {
                            this.__fableCtxLostHooked = true;
                            this.addEventListener("webglcontextlost", function() {
                                try {
                                    if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                        window.FableHybridBridge.onHeavyBurst();
                                    }
                                } catch (_) {}
                            }, { passive: true });
                        }
                        return ctx;
                    }
                    if (type === "2d" && CFG.isGame && CFG.accel3d2d !== false) {
                        const next2d = Object.assign({ desynchronized: true }, attrs || {});
                        return origGetContext.call(this, type, next2d);
                    }
                    return origGetContext.call(this, type, attrs);
                };
                if (typeof window.OffscreenCanvas === "function" && OffscreenCanvas.prototype && OffscreenCanvas.prototype.getContext) {
                    const origOffGetContext = OffscreenCanvas.prototype.getContext;
                    OffscreenCanvas.prototype.getContext = function(type, attrs) {
                        if (type === "webgl" || type === "webgl2" || type === "experimental-webgl") {
                            const nextAttrs = Object.assign({
                                powerPreference: "high-performance",
                                desynchronized: !!CFG.isGame && (CFG.accel3d2d !== false)
                            }, attrs || {});
                            const ctx = origOffGetContext.call(this, type, nextAttrs);
                            if (ctx && CFG.isGame) warmGlExtensions(ctx);
                            return ctx;
                        }
                        return origOffGetContext.call(this, type, attrs);
                    };
                }
            } catch (_) {}
            if (CFG.webGpuBoost !== false && navigator.gpu && typeof navigator.gpu.requestAdapter === "function") try {
                const origReqAdapter = navigator.gpu.requestAdapter.bind(navigator.gpu);
                navigator.gpu.requestAdapter = function(opts) {
                    const nextOpts = Object.assign({ powerPreference: "high-performance" }, opts || {});
                    return origReqAdapter(nextOpts).then(function(adapter) {
                        if (adapter && typeof adapter.requestDevice === "function" && !adapter.__fableWrapped) {
                            const origReqDevice = adapter.requestDevice.bind(adapter);
                            adapter.requestDevice = function(desc) {
                                return origReqDevice(desc).then(function(device) {
                                    if (device && typeof device.createShaderModule === "function" && !device.__fableShaderCached) {
                                        const shaderCache = new Map();
                                        const origCreateShader = device.createShaderModule.bind(device);
                                        device.createShaderModule = function(sDesc) {
                                            if (sDesc && typeof sDesc.code === "string" && sDesc.code.length > 0 && sDesc.code.length <= 131072) {
                                                const hit = shaderCache.get(sDesc.code);
                                                if (hit) return hit;
                                                const mod = origCreateShader(sDesc);
                                                if (shaderCache.size > 256) shaderCache.clear();
                                                shaderCache.set(sDesc.code, mod);
                                                return mod;
                                            }
                                            return origCreateShader(sDesc);
                                        };
                                        if (typeof device.createRenderPipelineAsync === "function") {
                                            const pipeCache = new Map();
                                            const origRenderAsync = device.createRenderPipelineAsync.bind(device);
                                            device.createRenderPipelineAsync = function(pDesc) {
                                                let key = null;
                                                try { key = pDesc && pDesc.label ? ("L:" + pDesc.label) : JSON.stringify(pDesc); } catch (_) { key = null; }
                                                if (!key || key.length > 16384) return origRenderAsync(pDesc);
                                                const hit = pipeCache.get(key);
                                                if (hit) return hit;
                                                const p = origRenderAsync(pDesc).catch(function(err) {
                                                    pipeCache.delete(key);
                                                    throw err;
                                                });
                                                if (pipeCache.size >= 128) pipeCache.delete(pipeCache.keys().next().value);
                                                pipeCache.set(key, p);
                                                return p;
                                            };
                                        }
                                        if (typeof device.createComputePipelineAsync === "function") {
                                            const compCache = new Map();
                                            const origCompAsync = device.createComputePipelineAsync.bind(device);
                                            device.createComputePipelineAsync = function(cDesc) {
                                                let key = null;
                                                try { key = cDesc && cDesc.label ? ("C:" + cDesc.label) : JSON.stringify(cDesc); } catch (_) { key = null; }
                                                if (!key || key.length > 16384) return origCompAsync(cDesc);
                                                const hit = compCache.get(key);
                                                if (hit) return hit;
                                                const p = origCompAsync(cDesc).catch(function(err) {
                                                    compCache.delete(key);
                                                    throw err;
                                                });
                                                if (compCache.size >= 128) compCache.delete(compCache.keys().next().value);
                                                compCache.set(key, p);
                                                return p;
                                            };
                                        }
                                        device.__fableShaderCached = true;
                                    }
                                    if (device && device.lost && typeof device.lost.then === "function") {
                                        device.lost.then(function() {
                                            if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                                window.FableHybridBridge.onHeavyBurst();
                                            }
                                        });
                                    }
                                    return device;
                                });
                            };
                            adapter.__fableWrapped = true;
                        }
                        return adapter;
                    });
                };
            } catch (_) {}

            // 3b. WebAssembly Module Cache, Streaming Fallback & C++ 8-Core Burst
            if (typeof window.WebAssembly === "object" && window.WebAssembly) try {
                const wasmCache = new Map();
                const hashWasmBytes = function(buf) {
                    const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf.buffer || buf, buf.byteOffset || 0, buf.byteLength || 0);
                    const len = u8.byteLength || 0;
                    if (len < 64) return null;
                    let h = (2166136261 ^ len) >>> 0;
                    const step = Math.max(1, Math.floor(len / 64));
                    for (let i = 0; i < len; i += step) {
                        h ^= u8[i];
                        h = Math.imul(h, 16777619) >>> 0;
                    }
                    return len + ":" + h.toString(16);
                };
                const origCompile = WebAssembly.compile;
                const origInstantiate = WebAssembly.instantiate;
                if (typeof origCompile === "function") {
                    WebAssembly.compile = function(bytes) {
                        try {
                            if (bytes && bytes.byteLength > 32768 && window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                window.FableHybridBridge.onHeavyBurst();
                            }
                            const key = bytes ? hashWasmBytes(bytes) : null;
                            if (key && wasmCache.has(key)) return Promise.resolve(wasmCache.get(key));
                            return origCompile.call(WebAssembly, bytes).then(function(mod) {
                                if (key && mod) {
                                    if (wasmCache.size > 16) wasmCache.clear();
                                    wasmCache.set(key, mod);
                                }
                                return mod;
                            });
                        } catch (_) {
                            return origCompile.apply(WebAssembly, arguments);
                        }
                    };
                }
                if (typeof origInstantiate === "function") {
                    WebAssembly.instantiate = function(first, imports) {
                        try {
                            if (first && !(first instanceof WebAssembly.Module) && first.byteLength > 32768) {
                                if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                    window.FableHybridBridge.onHeavyBurst();
                                }
                                const key = hashWasmBytes(first);
                                if (key && wasmCache.has(key)) {
                                    const cachedMod = wasmCache.get(key);
                                    return origInstantiate.call(WebAssembly, cachedMod, imports).then(function(inst) {
                                        return { module: cachedMod, instance: inst };
                                    });
                                }
                            }
                        } catch (_) {}
                        return origInstantiate.apply(WebAssembly, arguments);
                    };
                }
                if (typeof WebAssembly.compileStreaming === "function") {
                    const origCompStream = WebAssembly.compileStreaming;
                    WebAssembly.compileStreaming = function(source) {
                        if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                            window.FableHybridBridge.onHeavyBurst();
                        }
                        return origCompStream.call(WebAssembly, source).catch(function() {
                            return Promise.resolve(source).then(function(r) { return r.clone().arrayBuffer(); }).then(function(b) {
                                return WebAssembly.compile(b);
                            });
                        });
                    };
                }
                if (typeof WebAssembly.instantiateStreaming === "function") {
                    const origInstStream = WebAssembly.instantiateStreaming;
                    WebAssembly.instantiateStreaming = function(source, imports) {
                        if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                            window.FableHybridBridge.onHeavyBurst();
                        }
                        return origInstStream.call(WebAssembly, source, imports).catch(function() {
                            return Promise.resolve(source).then(function(r) { return r.clone().arrayBuffer(); }).then(function(b) {
                                return WebAssembly.instantiate(b, imports);
                            });
                        });
                    };
                }
            } catch (_) {}

            // 3c. Native Clipboard Bridge & Dynamic Webpack Chunk Auto-Retry (all Hybrid pages + staging.bloxd.io)
            try {
                const readNativeClip = function() {
                    try {
                        if (window.FableHybridBridge && typeof window.FableHybridBridge.getClipboardText === "function") {
                            return String(window.FableHybridBridge.getClipboardText() || "");
                        }
                    } catch (_) {}
                    return "";
                };
                const writeNativeClip = function(txt) {
                    try {
                        if (window.FableHybridBridge && typeof window.FableHybridBridge.setClipboardText === "function") {
                            window.FableHybridBridge.setClipboardText(String(txt || ""));
                            return true;
                        }
                    } catch (_) {}
                    return false;
                };
                const clipShim = {
                    readText: function() { return Promise.resolve(readNativeClip()); },
                    writeText: function(t) { writeNativeClip(t); return Promise.resolve(); }
                };
                if (!navigator.clipboard) {
                    Object.defineProperty(Navigator.prototype, "clipboard", {
                        configurable: true,
                        get: function() { return clipShim; }
                    });
                } else {
                    const clipObj = navigator.clipboard;
                    const origRead = typeof clipObj.readText === "function" ? clipObj.readText.bind(clipObj) : null;
                    const origWrite = typeof clipObj.writeText === "function" ? clipObj.writeText.bind(clipObj) : null;
                    try {
                        Object.defineProperty(clipObj, "readText", {
                            configurable: true,
                            value: function() {
                                const nativeTxt = readNativeClip();
                                if (nativeTxt.length > 0) return Promise.resolve(nativeTxt);
                                if (origRead) return origRead().catch(function() { return readNativeClip(); });
                                return Promise.resolve(nativeTxt);
                            }
                        });
                        Object.defineProperty(clipObj, "writeText", {
                            configurable: true,
                            value: function(t) {
                                writeNativeClip(t);
                                if (origWrite) return origWrite(t).catch(function() {});
                                return Promise.resolve();
                            }
                        });
                    } catch (_) {}
                }
                if (navigator.permissions && typeof navigator.permissions.query === "function") {
                    const origQuery = navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query = function(desc) {
                        if (desc && (desc.name === "clipboard-read" || desc.name === "clipboard-write")) {
                            return Promise.resolve({ state: "granted", onchange: null });
                        }
                        return origQuery(desc);
                    };
                }
            } catch (_) {}

            try {
                const retriedChunks = new Set();
                window.addEventListener("error", function(ev) {
                    const t = ev && ev.target;
                    if (t && t.tagName === "SCRIPT" && t.src && /\.js(\?|$)/i.test(t.src) && !retriedChunks.has(t.src)) {
                        retriedChunks.add(t.src);
                        const origOnErr = t.onerror;
                        const origOnLoad = t.onload;
                        t.onerror = null;
                        ev.stopImmediatePropagation();
                        ev.preventDefault();
                        const retryScript = document.createElement("script");
                        retryScript.src = t.src + (t.src.indexOf("?") === -1 ? "?" : "&") + "_fable_retry=" + Date.now();
                        retryScript.async = true;
                        if (t.crossOrigin) retryScript.crossOrigin = t.crossOrigin;
                        retryScript.onload = origOnLoad;
                        retryScript.onerror = origOnErr;
                        (document.head || document.documentElement).appendChild(retryScript);
                    }
                }, true);
                window.addEventListener("unhandledrejection", function(ev) {
                    const r = ev && ev.reason;
                    const msg = r ? String(r.message || r.name || r) : "";
                    if (msg.indexOf("NotAllowedError") !== -1 || msg.indexOf("Clipboard") !== -1) {
                        ev.preventDefault();
                    }
                });
            } catch (_) {}

            // 4. Blink Multi-Touch Joystick + Camera Isolation, Anti-Shutter & Lag-Spike Eliminator for Bloxd / 3D Games
            if (CFG.isGame) {
                try {
                    const targetCores = CFG.reportedCores || 8;
                    if (!navigator.hardwareConcurrency || navigator.hardwareConcurrency < targetCores) {
                        Object.defineProperty(Navigator.prototype, "hardwareConcurrency", {
                            get: function() { return targetCores; },
                            configurable: true
                        });
                    }

                    // 4b. Live 3D Render Resolution Scale & Power Plan Updater (Mali/Adreno Fill-Rate Battery Saver)
                    const rawNativeDpr = window.devicePixelRatio || 1;
                    const powerState = {
                        maxFps: CFG.gameMaxFps || 120,
                        idleFps: CFG.idleGameFps || 0,
                        idleTimeoutMs: CFG.idleTimeoutMs || 8000,
                        scalePct: CFG.renderScalePct || 100
                    };
                    const applyDprScale = function(pct) {
                        powerState.scalePct = pct;
                        try {
                            if (pct > 0 && pct < 100) {
                                const scaled = Math.max(0.75, Math.round((rawNativeDpr * pct)) / 100);
                                Object.defineProperty(window, "devicePixelRatio", {
                                    configurable: true,
                                    get: function() { return scaled; }
                                });
                            } else {
                                Object.defineProperty(window, "devicePixelRatio", {
                                    configurable: true,
                                    get: function() { return rawNativeDpr; }
                                });
                            }
                        } catch (_) {}
                    };
                    if (powerState.scalePct < 100) applyDprScale(powerState.scalePct);
                    let lastUserInputTs = performance.now();
                    const markInput = function() { lastUserInputTs = performance.now(); };
                    ["touchstart", "touchmove", "touchend", "pointerdown", "pointermove", "pointerup", "mousedown", "mousemove", "keydown", "wheel"].forEach(function(evName) {
                        window.addEventListener(evName, markInput, { capture: true, passive: true });
                    });
                    window.__fableSetPowerPlan = function(maxFps, idleFps, idleTimeoutMs, scalePct) {
                        powerState.maxFps = maxFps || 120;
                        powerState.idleFps = idleFps || 0;
                        powerState.idleTimeoutMs = idleTimeoutMs || 8000;
                        applyDprScale(scalePct || 100);
                    };

                    // 4c. Zero-Lag VSync-Locked 120 FPS Fast Path + Optional VSync-Aligned Eco Frame Skipper (zero setTimeout jitter!)
                    if (window.requestAnimationFrame) {
                        let lastFrameTs = 0;
                        let lastBurstTs = 0;
                        const origRaf = window.requestAnimationFrame;
                        window.requestAnimationFrame = function(cb) {
                            const idleCap = (powerState.idleFps > 0 && (performance.now() - lastUserInputTs) >= powerState.idleTimeoutMs)
                                ? powerState.idleFps
                                : 120;
                            const effCap = Math.min(powerState.maxFps || 120, idleCap);
                            if (effCap >= 115) {
                                return origRaf.call(window, function(ts) {
                                    if (CFG.teleportStabilizer !== false && lastFrameTs > 0 && (ts - lastFrameTs) > 20 && (ts - lastBurstTs) > 350) {
                                        lastBurstTs = ts;
                                        try {
                                            if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                                window.FableHybridBridge.onHeavyBurst();
                                            }
                                        } catch (_) {}
                                    }
                                    lastFrameTs = ts;
                                    return cb(ts);
                                });
                            }
                            const stepVSync = function(ts) {
                                const recheckIdle = (powerState.idleFps > 0 && (ts - lastUserInputTs) >= powerState.idleTimeoutMs);
                                const curCap = Math.min(powerState.maxFps || 120, recheckIdle ? powerState.idleFps : 120);
                                if (curCap < 115 && lastFrameTs > 0 && (ts - lastFrameTs) < ((1000 / Math.max(15, curCap)) * 0.86)) {
                                    origRaf.call(window, stepVSync);
                                    return;
                                }
                                lastFrameTs = ts;
                                cb(ts);
                            };
                            return origRaf.call(window, stepVSync);
                        };
                    }
                    if (CFG.teleportStabilizer !== false && window.Worker && Worker.prototype.postMessage) {
                        let lastWorkerBurst = 0;
                        const origPostMsg = Worker.prototype.postMessage;
                        Worker.prototype.postMessage = function(msg, transfer) {
                            try {
                                const len = (msg && (msg.byteLength || (msg.buffer && msg.buffer.byteLength))) || 0;
                                if (len > 65536) {
                                    const now = performance.now();
                                    if ((now - lastWorkerBurst) > 350 && window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                        lastWorkerBurst = now;
                                        window.FableHybridBridge.onHeavyBurst();
                                    }
                                }
                            } catch (_) {}
                            return origPostMsg.apply(this, arguments);
                        };
                    }
                    if (CFG.accel3d2d !== false) {
                        const patchGlStateCache = function(Proto) {
                            if (!Proto || Proto.__fableStateCached) return;
                            const origUseProg = Proto.useProgram;
                            if (typeof origUseProg === "function") {
                                Proto.useProgram = function(prog) {
                                    if (this.__fableLastProg === prog && prog !== null) return;
                                    this.__fableLastProg = prog;
                                    return origUseProg.call(this, prog);
                                };
                            }
                            const origDeleteProg = Proto.deleteProgram;
                            if (typeof origDeleteProg === "function") {
                                Proto.deleteProgram = function(prog) {
                                    if (this.__fableLastProg === prog) this.__fableLastProg = null;
                                    return origDeleteProg.call(this, prog);
                                };
                            }
                            const origActiveTex = Proto.activeTexture;
                            if (typeof origActiveTex === "function") {
                                Proto.activeTexture = function(unit) {
                                    if (this.__fableActiveUnit === unit) return;
                                    this.__fableActiveUnit = unit;
                                    return origActiveTex.call(this, unit);
                                };
                            }
                            const origBindTex = Proto.bindTexture;
                            if (typeof origBindTex === "function") {
                                Proto.bindTexture = function(target, tex) {
                                    if (this.__fableTexBypass === true) return origBindTex.call(this, target, tex);
                                    const slot = ((this.__fableActiveUnit || 0x84C0) - 0x84C0) & 31;
                                    if (target === 0x0DE1) {
                                        const arr = this.__fableTex2d || (this.__fableTex2d = new Array(32));
                                        const samples = (this.__fableTexSamples = (this.__fableTexSamples || 0) + 1);
                                        if (tex !== null && arr[slot] === tex) {
                                            this.__fableTexSkipped = (this.__fableTexSkipped || 0) + 1;
                                            return;
                                        }
                                        if (samples === 512 && ((this.__fableTexSkipped || 0) * 5) < samples) {
                                            this.__fableTexBypass = true;
                                        }
                                        arr[slot] = tex;
                                    } else if (target === 0x8513) {
                                        const arr = this.__fableTexCube || (this.__fableTexCube = new Array(32));
                                        if (tex !== null && arr[slot] === tex) return;
                                        arr[slot] = tex;
                                    }
                                    return origBindTex.call(this, target, tex);
                                };
                            }
                            const origDeleteTex = Proto.deleteTexture;
                            if (typeof origDeleteTex === "function") {
                                Proto.deleteTexture = function(tex) {
                                    if (this.__fableTex2d) this.__fableTex2d.fill(undefined);
                                    if (this.__fableTexCube) this.__fableTexCube.fill(undefined);
                                    return origDeleteTex.call(this, tex);
                                };
                            }
                            const origDepthFunc = Proto.depthFunc;
                            if (typeof origDepthFunc === "function") {
                                Proto.depthFunc = function(fn) {
                                    if (this.__fableLastDepthFn === fn) return;
                                    this.__fableLastDepthFn = fn;
                                    return origDepthFunc.call(this, fn);
                                };
                            }
                            const origDepthMask = Proto.depthMask;
                            if (typeof origDepthMask === "function") {
                                Proto.depthMask = function(flag) {
                                    const b = !!flag;
                                    if (this.__fableLastDepthMask === b) return;
                                    this.__fableLastDepthMask = b;
                                    return origDepthMask.call(this, flag);
                                };
                            }
                            const origCullFace = Proto.cullFace;
                            if (typeof origCullFace === "function") {
                                Proto.cullFace = function(mode) {
                                    if (this.__fableLastCull === mode) return;
                                    this.__fableLastCull = mode;
                                    return origCullFace.call(this, mode);
                                };
                            }
                            const origFrontFace = Proto.frontFace;
                            if (typeof origFrontFace === "function") {
                                Proto.frontFace = function(mode) {
                                    if (this.__fableLastFront === mode) return;
                                    this.__fableLastFront = mode;
                                    return origFrontFace.call(this, mode);
                                };
                            }
                            const origBlendFunc = Proto.blendFunc;
                            if (typeof origBlendFunc === "function") {
                                Proto.blendFunc = function(s, d) {
                                    const k = (s << 16) ^ d;
                                    if (this.__fableLastBlend === k) return;
                                    this.__fableLastBlend = k;
                                    return origBlendFunc.call(this, s, d);
                                };
                            }
                            const origBlendFuncSep = Proto.blendFuncSeparate;
                            if (typeof origBlendFuncSep === "function") {
                                Proto.blendFuncSeparate = function(sr, dr, sa, da) {
                                    this.__fableLastBlend = null;
                                    return origBlendFuncSep.call(this, sr, dr, sa, da);
                                };
                            }
                            const origEnable = Proto.enable;
                            const origDisable = Proto.disable;
                            if (typeof origEnable === "function" && typeof origDisable === "function") {
                                Proto.enable = function(cap) {
                                    if (cap === 0x0B71) { if (this.__cDepth === 1) return; this.__cDepth = 1; }
                                    else if (cap === 0x0B44) { if (this.__cCull === 1) return; this.__cCull = 1; }
                                    else if (cap === 0x0BE2) { if (this.__cBlend === 1) return; this.__cBlend = 1; }
                                    else if (cap === 0x0C11) { if (this.__cScis === 1) return; this.__cScis = 1; }
                                    return origEnable.call(this, cap);
                                };
                                Proto.disable = function(cap) {
                                    if (cap === 0x0B71) { if (this.__cDepth === 0) return; this.__cDepth = 0; }
                                    else if (cap === 0x0B44) { if (this.__cCull === 0) return; this.__cCull = 0; }
                                    else if (cap === 0x0BE2) { if (this.__cBlend === 0) return; this.__cBlend = 0; }
                                    else if (cap === 0x0C11) { if (this.__cScis === 0) return; this.__cScis = 0; }
                                    return origDisable.call(this, cap);
                                };
                            }
                            const origViewport = Proto.viewport;
                            if (typeof origViewport === "function") {
                                Proto.viewport = function(x, y, w, h) {
                                    if (this.__vpX === x && this.__vpY === y && this.__vpW === w && this.__vpH === h) return;
                                    this.__vpX = x; this.__vpY = y; this.__vpW = w; this.__vpH = h;
                                    return origViewport.call(this, x, y, w, h);
                                };
                            }
                            const origScissor = Proto.scissor;
                            if (typeof origScissor === "function") {
                                Proto.scissor = function(x, y, w, h) {
                                    if (this.__scX === x && this.__scY === y && this.__scW === w && this.__scH === h) return;
                                    this.__scX = x; this.__scY = y; this.__scW = w; this.__scH = h;
                                    return origScissor.call(this, x, y, w, h);
                                };
                            }
                            Proto.__fableStateCached = true;
                        };
                        if (window.WebGLRenderingContext) patchGlStateCache(WebGLRenderingContext.prototype);
                        if (window.WebGL2RenderingContext) patchGlStateCache(WebGL2RenderingContext.prototype);
                    }
                    // Mobile 3D PointerLock Compatibility Shim (prevents NotSupportedError in 3D games)
                    if (Element.prototype && typeof Element.prototype.requestPointerLock !== "function") {
                        Element.prototype.requestPointerLock = function() {
                            return Promise.resolve();
                        };
                    }
                    if (window.HTMLImageElement && HTMLImageElement.prototype) {
                        const origSrcDesc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "src");
                        if (origSrcDesc && origSrcDesc.set) {
                            Object.defineProperty(HTMLImageElement.prototype, "src", {
                                configurable: true,
                                get: origSrcDesc.get,
                                set: function(v) {
                                    if (!this.decoding || this.decoding === "auto") this.decoding = "async";
                                    return origSrcDesc.set.call(this, v);
                                }
                            });
                        }
                    }
                } catch (_) {}
            }
            if (CFG.touchFix && !/browserbench\.org$/i.test(host)) {
                try {
                    const uiTouchIds = new Set();
                    const camTouchIds = new Set();
                    const lastCamPos = new Map();
                    let lastTapTime = 0;
                    let cachedRect = null;
                    let cachedRectTime = 0;

                    const isEditableNode = function(el) {
                        if (!el || typeof el.closest !== "function") return false;
                        return !!el.closest("input, textarea, select, [contenteditable='true'], [contenteditable='']");
                    };
                    const isUiNode = function(el) {
                        if (!el || typeof el.closest !== "function") return false;
                        return !!el.closest(
                            ".JoystickZone, .Joystick, .TouchMovementWrapper, .TouchComponentsButtons, .FloatingAction, [data-camera-keeper], button, input, select, textarea, a, [role='button']"
                        );
                    };
                    const isCameraLookNode = function(el) {
                        if (!el || isUiNode(el)) return false;
                        if (el.tagName === "CANVAS" || el.id === "noa-container" || el.id === "noa-canvas") return true;
                        if (typeof el.closest === "function" && el.closest("#noa-container")) return true;
                        return false;
                    };
                    const isUiTouch = function(t) {
                        if (!t) return false;
                        if (uiTouchIds.has(t.identifier)) return true;
                        if (camTouchIds.has(t.identifier)) return false;
                        return isUiNode(t.target);
                    };

                    const origTouchesDesc = Object.getOwnPropertyDescriptor(TouchEvent.prototype, "touches");
                    const origChangedDesc = Object.getOwnPropertyDescriptor(TouchEvent.prototype, "changedTouches");
                    const origTargetDesc = Object.getOwnPropertyDescriptor(TouchEvent.prototype, "targetTouches");
                    const rawTouchesGet = origTouchesDesc && origTouchesDesc.get;
                    const rawChangedGet = origChangedDesc && origChangedDesc.get;
                    const rawTargetGet = origTargetDesc && origTargetDesc.get;

                    if (rawTouchesGet && rawChangedGet && rawTargetGet) {
                        const makeTouchArray = function(arr) {
                            arr.item = function(idx) { return arr[idx] || null; };
                            return arr;
                        };
                        Object.defineProperty(TouchEvent.prototype, "touches", {
                            configurable: true,
                            get: function() {
                                const raw = rawTouchesGet.call(this);
                                if (!raw || raw.length <= 1) return raw;
                                const tgt = this.currentTarget || this.target;
                                const onUi = isUiNode(tgt);
                                const onCam = !onUi && isCameraLookNode(tgt);
                                if (!onUi && !onCam) return raw;
                                const kept = [];
                                for (let i = 0; i < raw.length; i++) {
                                    const t = raw[i];
                                    const ui = isUiTouch(t);
                                    if (onUi && ui) kept.push(t);
                                    else if (onCam && !ui) kept.push(t);
                                }
                                if (kept.length === raw.length) return raw;
                                return makeTouchArray(kept);
                            }
                        });
                        Object.defineProperty(TouchEvent.prototype, "changedTouches", {
                            configurable: true,
                            get: function() {
                                const raw = rawChangedGet.call(this);
                                if (!raw) return raw;
                                const tgt = this.currentTarget || this.target;
                                const onUi = isUiNode(tgt);
                                const onCam = !onUi && isCameraLookNode(tgt);
                                if (!onUi && !onCam) return raw;
                                const kept = [];
                                for (let i = 0; i < raw.length; i++) {
                                    const t = raw[i];
                                    const ui = isUiTouch(t);
                                    if (onUi && ui) kept.push(t);
                                    else if (onCam && !ui) kept.push(t);
                                }
                                if (kept.length > 0) {
                                    return kept.length === raw.length ? raw : makeTouchArray(kept);
                                }
                                // Fallback to stationary touch of same role so joystick touch NEVER leaks into camera changedTouches[0]
                                const all = rawTouchesGet.call(this);
                                if (all) {
                                    for (let i = 0; i < all.length; i++) {
                                        const t = all[i];
                                        const ui = isUiTouch(t);
                                        if ((onUi && ui) || (onCam && !ui)) {
                                            return makeTouchArray([t]);
                                        }
                                    }
                                }
                                return raw;
                            }
                        });
                    }

                    const origCanvasRect = HTMLCanvasElement.prototype.getBoundingClientRect;
                    HTMLCanvasElement.prototype.getBoundingClientRect = function() {
                        const t = performance.now();
                        if (cachedRect && (t - cachedRectTime) < (CFG.canvasCacheMs || 250)) {
                            return cachedRect;
                        }
                        cachedRect = origCanvasRect.call(this);
                        cachedRectTime = t;
                        return cachedRect;
                    };
                    let cachedCw = 0, cachedCh = 0;
                    const cwDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientWidth");
                    const chDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientHeight");
                    if (cwDesc && cwDesc.get && chDesc && chDesc.get) {
                        Object.defineProperty(HTMLCanvasElement.prototype, "clientWidth", {
                            configurable: true,
                            get: function() {
                                if (cachedCw > 0) return cachedCw;
                                cachedCw = cwDesc.get.call(this);
                                return cachedCw;
                            }
                        });
                        Object.defineProperty(HTMLCanvasElement.prototype, "clientHeight", {
                            configurable: true,
                            get: function() {
                                if (cachedCh > 0) return cachedCh;
                                cachedCh = chDesc.get.call(this);
                                return cachedCh;
                            }
                        });
                    }
                    window.addEventListener("resize", function() { cachedRect = null; cachedCw = 0; cachedCh = 0; }, { passive: true });
                    // Zero-delay rapid clicking (20+ CPS): prevent browser double-tap zoom or contextmenu stalls on game canvases,
                    // while ALWAYS preserving native long-press Paste / Select All on <input>, <textarea>, and [contenteditable]!
                    window.addEventListener("dblclick", function(ev) {
                        if (isEditableNode(ev.target)) return;
                        if (isCameraLookNode(ev.target) || isUiNode(ev.target)) ev.preventDefault();
                    }, { capture: true, passive: false });
                    window.addEventListener("contextmenu", function(ev) {
                        if (isEditableNode(ev.target)) return;
                        if (isCameraLookNode(ev.target)) ev.preventDefault();
                    }, { capture: true, passive: false });

                    window.addEventListener("touchstart", function(ev) {
                        lastTapTime = performance.now();
                        const ct = rawChangedGet ? rawChangedGet.call(ev) : ev.changedTouches;
                        if (!ct) return;
                        for (let i = 0; i < ct.length; i++) {
                            const t = ct[i];
                            if (!t) continue;
                            if (isUiNode(t.target)) {
                                uiTouchIds.add(t.identifier);
                                camTouchIds.delete(t.identifier);
                            } else if (isCameraLookNode(t.target)) {
                                camTouchIds.add(t.identifier);
                                uiTouchIds.delete(t.identifier);
                                lastCamPos.set(t.identifier, { x: t.clientX, y: t.clientY });
                            }
                        }
                    }, { capture: true, passive: true });

                    window.addEventListener("touchmove", function(ev) {
                        if (!isCameraLookNode(ev.target)) return;
                        const ct = rawChangedGet ? rawChangedGet.call(ev) : ev.changedTouches;
                        if (!ct || ct.length === 0) return;
                        let camMoved = false;
                        for (let i = 0; i < ct.length; i++) {
                            const t = ct[i];
                            if (!t || isUiTouch(t)) continue;
                            const prev = lastCamPos.get(t.identifier);
                            if (!prev || Math.abs(t.clientX - prev.x) >= 0.03 || Math.abs(t.clientY - prev.y) >= 0.03) {
                                lastCamPos.set(t.identifier, { x: t.clientX, y: t.clientY });
                                camMoved = true;
                            }
                        }
                        if (!camMoved) {
                            ev.stopImmediatePropagation();
                        }
                    }, { capture: true, passive: true });

                    const cleanTouches = function(ev) {
                        lastTapTime = performance.now();
                        const ct = rawChangedGet ? rawChangedGet.call(ev) : ev.changedTouches;
                        if (!ct) return;
                        for (let i = 0; i < ct.length; i++) {
                            const t = ct[i];
                            if (!t) continue;
                            uiTouchIds.delete(t.identifier);
                            camTouchIds.delete(t.identifier);
                            lastCamPos.delete(t.identifier);
                        }
                    };
                    window.addEventListener("touchend", cleanTouches, { capture: true, passive: true });
                    window.addEventListener("touchcancel", cleanTouches, { capture: true, passive: true });

                    // Prevent synthetic mousemove on touchscreen from double-adding camera deltas or running hover raycasts
                    window.addEventListener("mousemove", function(ev) {
                        if (!document.pointerLockElement && (uiTouchIds.size > 0 || camTouchIds.size > 0 || (performance.now() - lastTapTime) < 500) && isCameraLookNode(ev.target)) {
                            ev.stopImmediatePropagation();
                        }
                    }, { capture: true, passive: true });
                } catch (_) {}
            }

            // 5. Privacy-Safe Smart Font Loader, Same-Origin WOFF2 Preload & Dynamic Text Batching
            try {
                const pendingTextNodes = new Map();
                let textFlushQueued = false;
                window.__fableBatchTextUpdate = function(node, val) {
                    if (!node) return;
                    pendingTextNodes.set(node, String(val));
                    if (textFlushQueued) return;
                    textFlushQueued = true;
                    requestAnimationFrame(function() {
                        textFlushQueued = false;
                        pendingTextNodes.forEach(function(txt, el) {
                            if (el.textContent !== txt) el.textContent = txt;
                        });
                        pendingTextNodes.clear();
                    });
                };
            } catch (_) {}
            const injectFastFonts = function() {
                try {
                    const head = document.head || document.documentElement;
                    if (!head || document.getElementById("__fable_hybrid_fast_fonts")) return;
                    const style = document.createElement("style");
                    style.id = "__fable_hybrid_fast_fonts";
                    const gameHudCss = CFG.isGame ? " #noa-container, .hud, .chat, .scoreboard { font-kerning: none; text-rendering: optimizeSpeed; }" : (CFG.reduceMotion ? " *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }" : "");
                    style.textContent = "*, *::before, *::after { -webkit-tap-highlight-color: transparent !important; } canvas, #noa-container { touch-action: none !important; -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; } input, textarea, [contenteditable], [contenteditable='true'] { -webkit-user-select: text !important; user-select: text !important; -webkit-touch-callout: default !important; touch-action: auto !important; }" + gameHudCss;
                    head.appendChild(style);

                    const sheets = document.styleSheets || [];
                    const preloadedFonts = new Set();
                    let usesGoogleFonts = false;
                    const links = document.querySelectorAll("link[href]");
                    for (let i = 0; i < links.length; i++) {
                        const h = links[i].href || "";
                        if (h.indexOf("fonts.googleapis.com") !== -1 || h.indexOf("fonts.gstatic.com") !== -1) {
                            usesGoogleFonts = true;
                            break;
                        }
                    }
                    if (usesGoogleFonts) {
                        const link = document.createElement("link");
                        link.rel = "preconnect";
                        link.href = "https://fonts.gstatic.com";
                        link.crossOrigin = "anonymous";
                        head.appendChild(link);
                    }
                    for (let i = 0; i < sheets.length; i++) {
                        let rules = null;
                        try { rules = sheets[i].cssRules; } catch (_) { continue; }
                        if (!rules) continue;
                        for (let j = 0; j < rules.length; j++) {
                            const r = rules[j];
                            if (r && r.type === 5 && r.style) {
                                try {
                                    if (!r.style.fontDisplay) r.style.fontDisplay = "swap";
                                    if (preloadedFonts.size < 2) {
                                        const srcVal = r.style.getPropertyValue("src") || "";
                                        const m = /url\(["']?([^"')]+?\.woff2(?:\?[^"')]*)?)["']?\)/i.exec(srcVal);
                                        if (m && m[1]) {
                                            const parsed = new URL(m[1], location.href);
                                            if (parsed.origin === location.origin && !preloadedFonts.has(parsed.href)) {
                                                preloadedFonts.add(parsed.href);
                                                const pl = document.createElement("link");
                                                pl.rel = "preload";
                                                pl.as = "font";
                                                pl.type = "font/woff2";
                                                pl.crossOrigin = "anonymous";
                                                pl.href = parsed.href;
                                                head.appendChild(pl);
                                            }
                                        }
                                    }
                                } catch (_) {}
                            }
                        }
                    }
                } catch (_) {}
            };
            if (CFG.fastFontSwap !== false) {
                if (document.readyState === "loading") {
                    document.addEventListener("DOMContentLoaded", injectFastFonts, { once: true });
                } else {
                    injectFastFonts();
                }
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
