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
    fun savePageCode(mode: String, reqId: String, continuous: Boolean = false): Boolean
    fun applyTimeScale(enabled: Boolean, rate: Float, css: Boolean, media: Boolean, workers: Boolean)
    suspend fun runMemoryCommand(cmdJson: String): String
    fun resetMemoryEngine()
    suspend fun exportSiteStorage(): String
    fun applyRestoredSiteStorage(jsonPayload: String)
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

    fun isLocalHtmlAppUrl(url: String): Boolean {
        if (url.startsWith("https://local.fable.app/opened_html/", ignoreCase = true)) return true
        if (url.startsWith("file://", ignoreCase = true) && url.contains("/cache/opened_html/")) return true
        return false
    }

    fun isBenchmarkUrl(url: String): Boolean {
        if (isLocalHtmlAppUrl(url)) return true
        val host = UrlUtils.host(url)?.removePrefix("www.") ?: return false
        return BENCHMARK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isHeavy3dGameUrl(url: String, customDomains: String = ""): Boolean {
        if (isLocalHtmlAppUrl(url) || isBenchmarkUrl(url)) return true
        return isPure3dGameUrl(url, customDomains)
    }

    fun buildCombinedBootstrapScript(
        settings: Settings,
        isIncognito: Boolean,
        isSuper: Boolean,
        superIdentity: SuperIdentity?,
        pageUrl: String,
        isDesktopMode: Boolean = false,
        bridgeToken: String = ""
    ): String {
        val isPureGame = isPure3dGameUrl(pageUrl, settings.hybridCustomDomains)
        val isGameOrBench = isHeavy3dGameUrl(pageUrl, settings.hybridCustomDomains)
        val plan = FableBatteryGovernor.evaluatePlan(settings, isPureGame)
        val decision = FablePrivacyPolicy.evaluate(settings, pageUrl, isIncognito, isSuper)
        val bp = settings.blinkPrivacyParity
        val hideLocalIp = bp && settings.hybridHideLocalIp && (decision.hideLocalIp || isIncognito || isSuper)
        val gpc = bp && decision.sendGpc
        val dnt = bp && decision.sendDnt
        val blockBattery = bp && decision.blockBatteryApi
        val blockNetInfo = bp && decision.blockNetworkInfoApi
        val touchFix = settings.gameTouchFix && settings.hybridCameraAntiShutter && isPureGame
        val applyIdentity = bp && decision.fingerprintProtectionActive && !isGameOrBench

        val cfg = JSONObject().apply {
            put("hideLocalIp", hideLocalIp)
            put("webRtcDisabled", bp && decision.webRtcDisabled)
            put("gpc", gpc)
            put("dnt", dnt)
            put("blockBattery", blockBattery)
            put("blockNetInfo", blockNetInfo)
            put("stealthLowDetect", decision.stealthLowDetectability)
            put("isolateBridge", settings.isolateNativeBridge)
            put("bridgeToken", bridgeToken)
            put("passwordAutofill", settings.passwordAutofill && (!isIncognito || settings.passwordIncognito))
            put("passwordSave", settings.passwordSave && (!isIncognito || settings.passwordIncognito))
            put("fpCanvas", applyIdentity && decision.fpCanvas)
            put("fpWebGlVendor", applyIdentity && decision.fpWebGlVendor)
            put("fpWebGlLimits", applyIdentity && decision.fpWebGlLimits)
            put("fpAudio", applyIdentity && decision.fpAudio)
            put("fpHardware", applyIdentity && decision.fpHardware)
            put("fpScreen", applyIdentity && decision.fpScreen)
            put("fpTimezone", applyIdentity && decision.fpTimezone)
            put("fpLanguage", applyIdentity && decision.fpLanguage)
            put("fpMedia", applyIdentity && decision.fpMedia)
            put("fpSensors", applyIdentity && decision.fpSensors)
            put("fpTimers", applyIdentity && decision.fpTimers)
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
            put("webGpuProfile", settings.hybridWebGpuProfile.wire)
            put("disableGameMsaa", settings.hybridDisableGameMsaa)
            put("customDomains", settings.hybridCustomDomains)
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
        val timeScaleBlock = WebsiteSpeedEngine.buildBootstrapScript(settings)
        val memBlock = WebsiteMemoryEngine.buildBootstrapScript(settings)
        val prefix = if (timeScaleBlock.isNotEmpty()) "$timeScaleBlock\n" else ""
        val suffix = if (memBlock.isNotEmpty()) "\n$memBlock" else ""
        return prefix + """
        (function() {
            if (window.__fableHybridBootstrapped) {
                try {
                    if (window.__fableSetPageZoom) window.__fableSetPageZoom($zoomInit);
                    if (window.__fableEnforceDesktopViewport && $isDesktopMode) window.__fableEnforceDesktopViewport();
                } catch (_) {}
                return;
            }
            const dynHost = (typeof location !== "undefined" && location.hostname ? location.hostname : "").toLowerCase();
            if (/(?:^|\.)(challenges\.cloudflare\.com|recaptcha\.net|hcaptcha\.com|arkoselabs\.com|funcaptcha\.com)$/i.test(dynHost)) {
                return;
            }
            if (window.__fableHybridBootstrapped) return;
            window.__fableHybridBootstrapped = true;
            const CFG = ${cfg.toString()};
            const __fableTok = CFG.bridgeToken || "";
            if (!CFG.isGame && dynHost) {
                if (/(?:^|\.)(bloxd\.io|staging\.bloxd\.io|miniblox\.io|krunker\.io|1v1\.lol|voxiom\.io|kirka\.io|kour\.io|poki\.com|crazygames\.com)$/i.test(dynHost)) {
                    CFG.isGame = true;
                } else if (typeof CFG.customDomains === "string" && CFG.customDomains.length > 0) {
                    const cList = CFG.customDomains.split(",");
                    for (let ci = 0; ci < cList.length; ci++) {
                        const cd = cList[ci].trim().toLowerCase();
                        if (cd && (dynHost === cd || dynHost.endsWith("." + cd))) {
                            CFG.isGame = true;
                            break;
                        }
                    }
                }
            }
            const __fableNativeToStringMap = typeof WeakMap === "function" ? new WeakMap() : null;
            const markNativeFn = function(wrappedFn, fnName) {
                if (__fableNativeToStringMap && typeof wrappedFn === "function") {
                    try { __fableNativeToStringMap.set(wrappedFn, "function " + (fnName || wrappedFn.name || "") + "() { [native code] }"); } catch (_) {}
                }
                return wrappedFn;
            };
            if (!CFG.stealthLowDetect && __fableNativeToStringMap && Function.prototype && !Function.prototype.__fableToStringCloaked) {
                try {
                    const origFnToString = Function.prototype.toString;
                    const wrappedToString = function() {
                        if (__fableNativeToStringMap.has(this)) return __fableNativeToStringMap.get(this);
                        return origFnToString.call(this);
                    };
                    __fableNativeToStringMap.set(wrappedToString, "function toString() { [native code] }");
                    Object.defineProperty(Function.prototype, "toString", {
                        configurable: true,
                        writable: true,
                        value: wrappedToString
                    });
                    Object.defineProperty(Function.prototype, "__fableToStringCloaked", {
                        configurable: false,
                        enumerable: false,
                        value: true
                    });
                } catch (_) {}
            }

            if (!CFG.isIncognito && !CFG.isSuper) try {
                const curOrigin = (location.origin || "");
                if (curOrigin && curOrigin !== "null" && window.FableHybridBridge) {
                    if (typeof window.FableHybridBridge.readSiteRestorePayload === "function") {
                        const rawRest = window.FableHybridBridge.readSiteRestorePayload(curOrigin, __fableTok);
                        if (rawRest && rawRest.length > 2) {
                            const parsedRest = JSON.parse(rawRest);
                            if (parsedRest.ls && typeof parsedRest.ls === "object") {
                                const lsKeys = Object.keys(parsedRest.ls);
                                for (let i = 0; i < lsKeys.length; i++) {
                                    const k = lsKeys[i];
                                    if (typeof parsedRest.ls[k] === "string") localStorage.setItem(k, parsedRest.ls[k]);
                                }
                            }
                            if (parsedRest.ss && typeof parsedRest.ss === "object") {
                                const ssKeys = Object.keys(parsedRest.ss);
                                for (let j = 0; j < ssKeys.length; j++) {
                                    const k2 = ssKeys[j];
                                    if (typeof parsedRest.ss[k2] === "string") sessionStorage.setItem(k2, parsedRest.ss[k2]);
                                }
                            }
                        }
                    }
                    if (typeof window.FableHybridBridge.writeSiteSnapshot === "function") {
                        const snapSiteNow = function() {
                            try {
                                const ls = {}, ss = {};
                                let c1 = 0, c2 = 0;
                                for (let i = 0; i < localStorage.length && c1 < 160; i++) {
                                    const k = localStorage.key(i);
                                    if (!k || k.indexOf("__fable_") === 0) continue;
                                    const v = localStorage.getItem(k);
                                    if (typeof v === "string" && v.length <= 24576) { ls[k] = v; c1++; }
                                }
                                for (let j = 0; j < sessionStorage.length && c2 < 80; j++) {
                                    const k2 = sessionStorage.key(j);
                                    if (!k2 || k2.indexOf("__fable_") === 0) continue;
                                    const v2 = sessionStorage.getItem(k2);
                                    if (typeof v2 === "string" && v2.length <= 24576) { ss[k2] = v2; c2++; }
                                }
                                if (c1 > 0 || c2 > 0) {
                                    window.FableHybridBridge.writeSiteSnapshot(curOrigin, JSON.stringify({ ls, ss }), __fableTok);
                                }
                            } catch (_) {}
                        };
                        window.addEventListener("pagehide", snapSiteNow, { passive: true });
                        document.addEventListener("visibilitychange", function() {
                            if (document.visibilityState === "hidden") snapSiteNow();
                        }, { passive: true });
                        window.addEventListener("load", function() { window.setTimeout(snapSiteNow, 1500); }, { once: true, passive: true });
                    }
                }
            } catch (_) {}

            if ((CFG.passwordAutofill || CFG.passwordSave) && window.FableHybridBridge) try {
                const setNativeInput = function(el, val) {
                    try {
                        const proto = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
                        if (proto && proto.set) proto.set.call(el, val);
                        else el.value = val;
                        el.dispatchEvent(new Event("input", { bubbles: true }));
                        el.dispatchEvent(new Event("change", { bubbles: true }));
                    } catch (_) {}
                };
                const findUserInput = function(pwEl) {
                    const form = pwEl.form || pwEl.closest("form") || document;
                    const inputs = form.querySelectorAll("input");
                    let best = null;
                    for (let i = 0; i < inputs.length; i++) {
                        const inp = inputs[i];
                        if (inp === pwEl) break;
                        const t = (inp.type || "text").toLowerCase();
                        if (t === "text" || t === "email" || t === "tel" || t === "username") best = inp;
                    }
                    return best;
                };
                if (CFG.passwordAutofill && typeof window.FableHybridBridge.readSavedLogin === "function") {
                    let filledOnce = false;
                    const tryAutofill = function() {
                        if (filledOnce) return;
                        try {
                            const pwEl = document.querySelector("input[type='password']");
                            if (!pwEl) return;
                            const host = (location.hostname || "").toLowerCase();
                            if (!host) return;
                            const raw = window.FableHybridBridge.readSavedLogin(host, __fableTok);
                            if (!raw || raw.length < 3) return;
                            const cred = JSON.parse(raw);
                            if (!cred || !cred.password) return;
                            const uEl = findUserInput(pwEl);
                            if (uEl && !uEl.value && cred.username) setNativeInput(uEl, cred.username);
                            if (!pwEl.value) setNativeInput(pwEl, cred.password);
                            filledOnce = true;
                        } catch (_) {}
                    };
                    if (document.readyState === "loading") {
                        document.addEventListener("DOMContentLoaded", function() { window.setTimeout(tryAutofill, 250); }, { once: true });
                    } else {
                        window.setTimeout(tryAutofill, 250);
                    }
                    window.addEventListener("load", function() { window.setTimeout(tryAutofill, 500); }, { once: true, passive: true });
                }
                if (CFG.passwordSave && typeof window.FableHybridBridge.saveSiteLogin === "function") {
                    document.addEventListener("submit", function(ev) {
                        try {
                            const form = ev && ev.target ? ev.target : null;
                            if (!form || !form.querySelector) return;
                            const pwEl = form.querySelector("input[type='password']");
                            if (!pwEl || !pwEl.value) return;
                            const uEl = findUserInput(pwEl);
                            const user = uEl && uEl.value ? String(uEl.value).trim() : "";
                            const pass = String(pwEl.value);
                            if (pass.length > 0 && location.origin && location.origin !== "null") {
                                window.FableHybridBridge.saveSiteLogin(location.origin, user, pass, __fableTok);
                            }
                        } catch (_) {}
                    }, true);
                }
            } catch (_) {}

            // 0a. Persistent Game Account Storage Vault (prevents Bloxd / 3D games from ever resetting to a new account!)
            if (CFG.isGame && !CFG.isIncognito && !CFG.isSuper) try {
                const vHost = (location.hostname || "").toLowerCase();
                if (vHost && window.FableHybridBridge && typeof window.FableHybridBridge.readAccountVault === "function") {
                    const rawVault = window.FableHybridBridge.readAccountVault(vHost, __fableTok);
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
                                window.FableHybridBridge.writeAccountVault(vHost, JSON.stringify(dump), __fableTok);
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

                    const DESK_VP = "width=1024, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes";
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
            if (!CFG.isolateBridge) {
                window.__fableHybridEngine = {
                    active: true,
                    beta: true,
                    privacyEngine: "GeckoView-144",
                    renderEngine: "Blink-Chromium",
                    hideLocalIp: !!CFG.hideLocalIp,
                    isGame: !!CFG.isGame
                };
            }
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
                if (CFG.blockNetInfo && "connection" in Navigator.prototype) {
                    Object.defineProperty(Navigator.prototype, "connection", {
                        get: function() { return undefined; },
                        configurable: true
                    });
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

                if (CFG.fpHardware) try {
                    Object.defineProperty(Navigator.prototype, "hardwareConcurrency", { get: function() { return shieldCores; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "deviceMemory", { get: function() { return 8; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "platform", { get: function() { return shieldPlatform; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "maxTouchPoints", { get: function() { return shieldTouch; }, configurable: true });
                    if ("userAgentData" in Navigator.prototype) {
                        Object.defineProperty(Navigator.prototype, "userAgentData", { get: function() { return undefined; }, configurable: true });
                    }
                } catch (_) {}

                if (CFG.fpLanguage) try {
                    Object.defineProperty(Navigator.prototype, "language", { get: function() { return shieldLang; }, configurable: true });
                    Object.defineProperty(Navigator.prototype, "languages", { get: function() { return shieldLangs.slice(); }, configurable: true });
                } catch (_) {}

                if (CFG.fpScreen) try {
                    Object.defineProperty(Screen.prototype, "width", { get: function() { return shieldW; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "height", { get: function() { return shieldH; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "availWidth", { get: function() { return shieldW; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "availHeight", { get: function() { return shieldAvailH; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "colorDepth", { get: function() { return shieldDepth; }, configurable: true });
                    Object.defineProperty(Screen.prototype, "pixelDepth", { get: function() { return shieldDepth; }, configurable: true });
                } catch (_) {}

                if (CFG.fpTimezone) try {
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

                if (CFG.fpWebGlVendor || CFG.fpWebGlLimits) try {
                    const patchWebGl = function(Proto) {
                        if (!Proto || !Proto.getParameter) return;
                        const origGetParam = Proto.getParameter;
                        Proto.getParameter = function(p) {
                            if (CFG.fpWebGlVendor && p === 37445) return shieldGlVendor;
                            if (CFG.fpWebGlVendor && p === 37446) return shieldGlRenderer;
                            if (CFG.fpWebGlLimits && p === 3379) return 4096;
                            if (CFG.fpWebGlLimits && p === 34076) return 4096;
                            return origGetParam.apply(this, arguments);
                        };
                    };
                    if (window.WebGLRenderingContext) patchWebGl(WebGLRenderingContext.prototype);
                    if (window.WebGL2RenderingContext) patchWebGl(WebGL2RenderingContext.prototype);
                } catch (_) {}

                if (CFG.fpCanvas) try {
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

                if (CFG.fpAudio) try {
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
                } catch (_) {}

                if (CFG.fpMedia) try {
                    if (window.speechSynthesis) {
                        window.speechSynthesis.getVoices = function() { return []; };
                    }
                } catch (_) {}

                if (CFG.fpTimers) try {
                    if (window.performance && typeof performance.now === "function") {
                        const origNow = performance.now.bind(performance);
                        performance.now = function() { return Math.floor(origNow() * 10) / 10; };
                    }
                } catch (_) {}

                if (CFG.fpSensors) try {
                    if ("DeviceOrientationEvent" in window) window.ondeviceorientation = null;
                    if ("DeviceMotionEvent" in window) window.ondevicemotion = null;
                } catch (_) {}
            }

            // 3. Blink 3D/2D WebGL2 Tile-Memory Promoter, OffscreenCanvas & WebGPU Vulkan Hyper-Core (v46)
            const gpuStats = {
                supported: !!(navigator.gpu && typeof navigator.gpu.requestAdapter === "function"),
                adapter: "",
                backendMode: "",
                format: "",
                features: [],
                shadersCached: 0,
                renderPipesCached: 0,
                computePipesCached: 0,
                opaqueHits: 0,
                deviceLossDelta: 0
            };
            let gpuTelemetryTimer = 0;
            const pushGpuTelemetry = function(immediate) {
                const sendNow = function() {
                    gpuTelemetryTimer = 0;
                    try {
                        if (window.FableHybridBridge && typeof window.FableHybridBridge.onWebGpuTelemetry === "function") {
                            window.FableHybridBridge.onWebGpuTelemetry(JSON.stringify(gpuStats));
                            gpuStats.deviceLossDelta = 0;
                        }
                    } catch (_) {}
                };
                if (immediate) {
                    sendNow();
                } else if (!gpuTelemetryTimer) {
                    gpuTelemetryTimer = setTimeout(sendNow, 350);
                }
            };

            if (CFG.highPerfWebGl !== false || CFG.accel3d2d !== false) try {
                const warmGlExtensions = function(gl) {
                    if (!gl || gl.__fableExtWarmed || typeof gl.getExtension !== "function") return;
                    gl.__fableExtWarmed = true;
                    const fastExts = [
                        "KHR_parallel_shader_compile",
                        "OES_vertex_array_object",
                        "ANGLE_instanced_arrays",
                        "OES_texture_float_linear",
                        "OES_texture_half_float_linear",
                        "EXT_color_buffer_float",
                        "EXT_color_buffer_half_float",
                        "WEBGL_compressed_texture_astc",
                        "WEBGL_compressed_texture_etc",
                        "EXT_disjoint_timer_query_webgl2"
                    ];
                    for (let i = 0; i < fastExts.length; i++) {
                        try { gl.getExtension(fastExts[i]); } catch (_) {}
                    }
                };
                const isPrimary3dGameCanvas = function(el) {
                    if (!CFG.isGame || !el) return false;
                    if (el.id === "noa-canvas" || el.id === "game-canvas" || el.id === "unity-canvas") return true;
                    const w = el.clientWidth || el.width || 0;
                    const h = el.clientHeight || el.height || 0;
                    return w >= (window.innerWidth * 0.65) && h >= (window.innerHeight * 0.65);
                };
                const buildOptimalGlAttrs = function(canvasEl, callerAttrs) {
                    const base = { powerPreference: "high-performance" };
                    if (CFG.isGame) {
                        // Keep tile-based Adreno GMEM / Mali Tile-RAM fast resolve unless caller explicitly demands preserveDrawingBuffer
                        if (!callerAttrs || callerAttrs.preserveDrawingBuffer !== true) {
                            base.preserveDrawingBuffer = false;
                        }
                    }
                    const merged = Object.assign(base, callerAttrs || {});
                    // On High-DPI mobile displays (DPR >= 1.75) in Ultra mode, 4x MSAA on full-screen voxel/3D canvases burns 35-50% fragment bandwidth
                    if (CFG.disableGameMsaa !== false && CFG.webGpuProfile === "ultra" &&
                        (window.devicePixelRatio || 1) >= 1.75 && isPrimary3dGameCanvas(canvasEl) &&
                        (!callerAttrs || callerAttrs.antialias === undefined)) {
                        merged.antialias = false;
                    }
                    return merged;
                };
                const origGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = markNativeFn(function(type, attrs) {
                    if (type === "webgl" || type === "webgl2" || type === "experimental-webgl") {
                        const nextAttrs = buildOptimalGlAttrs(this, attrs);
                        const ctx = origGetContext.call(this, type, nextAttrs) || origGetContext.call(this, type, attrs);
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
                    if (type === "webgpu" && CFG.webGpuBoost !== false) {
                        const gpuCtx = origGetContext.call(this, type, attrs);
                        if (gpuCtx && !gpuCtx.__fableCanvasHooked && typeof gpuCtx.configure === "function") {
                            gpuCtx.__fableCanvasHooked = true;
                            const canvasRef = this;
                            const origConfigure = gpuCtx.configure.bind(gpuCtx);
                            gpuCtx.configure = markNativeFn(function(cDesc) {
                                if (!cDesc || typeof cDesc !== "object") return origConfigure(cDesc);
                                const nextCfg = Object.assign({}, cDesc);
                                if (!nextCfg.format && navigator.gpu && typeof navigator.gpu.getPreferredCanvasFormat === "function") {
                                    nextCfg.format = navigator.gpu.getPreferredCanvasFormat();
                                }
                                // Full-screen 3D game canvas opaque scanout avoids per-pixel SurfaceFlinger alpha blending
                                if (!cDesc.alphaMode && isPrimary3dGameCanvas(canvasRef)) {
                                    nextCfg.alphaMode = "opaque";
                                    gpuStats.opaqueHits++;
                                } else if (cDesc.alphaMode === "opaque") {
                                    gpuStats.opaqueHits++;
                                }
                                if (nextCfg.device && nextCfg.device.limits && nextCfg.device.limits.maxTextureDimension2D) {
                                    const maxDim = nextCfg.device.limits.maxTextureDimension2D;
                                    if (canvasRef.width > maxDim) canvasRef.width = maxDim;
                                    if (canvasRef.height > maxDim) canvasRef.height = maxDim;
                                }
                                gpuStats.format = String(nextCfg.format || "rgba8unorm");
                                try {
                                    if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                        window.FableHybridBridge.onHeavyBurst();
                                    }
                                } catch (_) {}
                                pushGpuTelemetry(false);
                                return origConfigure(nextCfg);
                            }, "configure");
                        }
                        return gpuCtx;
                    }
                    // Never force desynchronized:true on 2D canvases in Android WebView:
                    // it blanks small HUD overlay canvases like Bloxd's 48x16 FpsCanvas and hurts willReadFrequently video frame grabs!
                    return origGetContext.call(this, type, attrs);
                }, "getContext");

                if (typeof window.OffscreenCanvas === "function" && OffscreenCanvas.prototype && OffscreenCanvas.prototype.getContext) {
                    const origOffGetContext = OffscreenCanvas.prototype.getContext;
                    OffscreenCanvas.prototype.getContext = markNativeFn(function(type, attrs) {
                        if (type === "webgl" || type === "webgl2" || type === "experimental-webgl") {
                            const nextAttrs = Object.assign({ powerPreference: "high-performance" }, attrs || {});
                            const ctx = origOffGetContext.call(this, type, nextAttrs) || origOffGetContext.call(this, type, attrs);
                            if (ctx && CFG.isGame) warmGlExtensions(ctx);
                            return ctx;
                        }
                        if (type === "webgpu" && CFG.webGpuBoost !== false) {
                            const gpuCtx = origOffGetContext.call(this, type, attrs);
                            if (gpuCtx && !gpuCtx.__fableCanvasHooked && typeof gpuCtx.configure === "function") {
                                gpuCtx.__fableCanvasHooked = true;
                                const origConfigure = gpuCtx.configure.bind(gpuCtx);
                                gpuCtx.configure = markNativeFn(function(cDesc) {
                                    if (cDesc && typeof cDesc === "object" && !cDesc.format && navigator.gpu && typeof navigator.gpu.getPreferredCanvasFormat === "function") {
                                        return origConfigure(Object.assign({ format: navigator.gpu.getPreferredCanvasFormat() }, cDesc));
                                    }
                                    return origConfigure(cDesc);
                                }, "configure");
                            }
                            return gpuCtx;
                        }
                        return origOffGetContext.call(this, type, attrs);
                    }, "getContext");
                }
                // High-FPS Hardware VideoDecoder (WebCodecs) & <video> Frame Playback Accelerator
                if (typeof window.VideoDecoder === "function" && VideoDecoder.prototype && typeof VideoDecoder.prototype.configure === "function") {
                    const origVdConfig = VideoDecoder.prototype.configure;
                    VideoDecoder.prototype.configure = markNativeFn(function(cfg) {
                        if (cfg && typeof cfg === "object" && !cfg.hardwareAcceleration) {
                            try {
                                const hwCfg = Object.assign({ hardwareAcceleration: "prefer-hardware" }, cfg);
                                if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                    window.FableHybridBridge.onHeavyBurst();
                                }
                                return origVdConfig.call(this, hwCfg);
                            } catch (_) {}
                        }
                        return origVdConfig.call(this, cfg);
                    }, "configure");
                }
                if (typeof window.HTMLMediaElement === "function" && HTMLMediaElement.prototype && typeof HTMLMediaElement.prototype.play === "function") {
                    const origMediaPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = markNativeFn(function() {
                        try {
                            if (this.tagName === "VIDEO") {
                                this.playsInline = true;
                                if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                    window.FableHybridBridge.onHeavyBurst();
                                }
                            }
                        } catch (_) {}
                        return origMediaPlay.apply(this, arguments);
                    }, "play");
                }
            } catch (_) {}

            if (CFG.webGpuBoost !== false && navigator.gpu && typeof navigator.gpu.requestAdapter === "function") try {
                let nextGpuObjUid = 1;
                const stampGpuUid = function(obj) {
                    if (obj && (typeof obj === "object" || typeof obj === "function") && !obj.__fableGpuUid) {
                        try {
                            Object.defineProperty(obj, "__fableGpuUid", {
                                value: nextGpuObjUid++,
                                enumerable: false,
                                configurable: false,
                                writable: false
                            });
                        } catch (_) {}
                    }
                    return obj;
                };
                // Collision-free serializer that encodes opaque GPUShaderModule / GPUPipelineLayout / GPUBindGroupLayout references by exact UID!
                const serializePipelineDesc = function(prefix, desc) {
                    if (!desc || typeof desc !== "object") return null;
                    try {
                        const s = JSON.stringify(desc, function(_k, v) {
                            if (v && typeof v === "object" && v.__fableGpuUid) {
                                return "${"$"}gpu#" + v.__fableGpuUid;
                            }
                            return v;
                        });
                        if (!s || s.length > 24576) return null;
                        return prefix + ":" + s;
                    } catch (_) {
                        return null;
                    }
                };
                const fnvHashStr = function(str) {
                    let h = 2166136261 >>> 0;
                    const len = str.length;
                    for (let i = 0; i < len; i++) {
                        h ^= str.charCodeAt(i);
                        h = Math.imul(h, 16777619) >>> 0;
                    }
                    return len + ":" + h.toString(16);
                };

                const origReqAdapter = navigator.gpu.requestAdapter.bind(navigator.gpu);
                const profileMode = CFG.webGpuProfile || "ultra";

                navigator.gpu.requestAdapter = markNativeFn(function(opts) {
                    const callerOpts = opts && typeof opts === "object" ? opts : {};
                    const primaryOpts = profileMode === "compat"
                        ? Object.assign({ featureLevel: "compatibility" }, callerOpts)
                        : Object.assign({ powerPreference: "high-performance" }, callerOpts);

                    // 3-Stage Hardware Adapter Cascade: High-Performance Core -> Default Core -> Compatibility Mode Fallback
                    return origReqAdapter(primaryOpts)
                        .catch(function() { return null; })
                        .then(function(a1) {
                            if (a1) return { adapter: a1, tier: profileMode === "compat" ? "Compatibility" : "Vulkan/Core High-Perf" };
                            return origReqAdapter(callerOpts).catch(function() { return null; }).then(function(a2) {
                                if (a2) return { adapter: a2, tier: "Core Default" };
                                const compatOpts = Object.assign({}, callerOpts, { featureLevel: "compatibility" });
                                return origReqAdapter(compatOpts).catch(function() { return null; }).then(function(a3) {
                                    return { adapter: a3, tier: a3 ? "Compatibility Fallback" : "Unavailable" };
                                });
                            });
                        })
                        .then(function(res) {
                            const adapter = res && res.adapter;
                            if (!adapter) return null;

                            // Polyfill deprecated/removed adapter.requestAdapterInfo() so 2023-2025 WebGPU engines never crash on modern Blink!
                            if (typeof adapter.requestAdapterInfo !== "function") {
                                try {
                                    Object.defineProperty(adapter, "requestAdapterInfo", {
                                        configurable: true,
                                        writable: true,
                                        value: markNativeFn(function() {
                                            return Promise.resolve(adapter.info || {});
                                        }, "requestAdapterInfo")
                                    });
                                } catch (_) {}
                            }

                            if (typeof adapter.requestDevice === "function" && !adapter.__fableWrapped) {
                                const origReqDevice = adapter.requestDevice.bind(adapter);
                                adapter.requestDevice = markNativeFn(function(desc) {
                                    const callerDesc = desc && typeof desc === "object" ? desc : {};
                                    const enhancedDesc = Object.assign({}, callerDesc);

                                    // 1. Unlock safe hardware features supported by the phone's GPU (FP16, Subgroups, ASTC/ETC2, Float32 Filterable)
                                    try {
                                        const supported = adapter.features;
                                        if (supported && typeof supported.has === "function") {
                                            const reqSet = new Set(Array.isArray(callerDesc.requiredFeatures) ? callerDesc.requiredFeatures.filter(function(f) { return supported.has(f); }) : []);
                                            if (profileMode !== "compat") {
                                                const boostFeatures = [
                                                    "shader-f16",
                                                    "subgroups",
                                                    "texture-compression-astc",
                                                    "texture-compression-etc2",
                                                    "float32-filterable",
                                                    "rg11b10ufloat-renderable",
                                                    "bgra8unorm-storage",
                                                    "depth32float-stencil8",
                                                    "indirect-first-instance"
                                                ];
                                                for (let fi = 0; fi < boostFeatures.length; fi++) {
                                                    if (supported.has(boostFeatures[fi])) reqSet.add(boostFeatures[fi]);
                                                }
                                            }
                                            if (reqSet.size > 0) enhancedDesc.requiredFeatures = Array.from(reqSet);
                                        }
                                    } catch (_) {}

                                    // 2. Clamp any out-of-range desktop game requiredLimits to adapter.limits & raise key buffer/compute ceilings safely
                                    try {
                                        const aLim = adapter.limits;
                                        if (aLim) {
                                            const nextLim = {};
                                            if (callerDesc.requiredLimits && typeof callerDesc.requiredLimits === "object") {
                                                const keys = Object.keys(callerDesc.requiredLimits);
                                                for (let ki = 0; ki < keys.length; ki++) {
                                                    const k = keys[ki];
                                                    const reqV = callerDesc.requiredLimits[k];
                                                    const maxV = aLim[k];
                                                    if (typeof reqV === "number" && typeof maxV === "number") {
                                                        nextLim[k] = Math.min(reqV, maxV);
                                                    }
                                                }
                                            }
                                            if (profileMode === "ultra") {
                                                if (typeof aLim.maxBufferSize === "number") nextLim.maxBufferSize = Math.max(nextLim.maxBufferSize || 0, Math.min(aLim.maxBufferSize, 536870912));
                                                if (typeof aLim.maxStorageBufferBindingSize === "number") nextLim.maxStorageBufferBindingSize = Math.max(nextLim.maxStorageBufferBindingSize || 0, Math.min(aLim.maxStorageBufferBindingSize, 268435456));
                                                if (typeof aLim.maxComputeWorkgroupStorageSize === "number") nextLim.maxComputeWorkgroupStorageSize = Math.max(nextLim.maxComputeWorkgroupStorageSize || 0, aLim.maxComputeWorkgroupStorageSize);
                                            }
                                            if (Object.keys(nextLim).length > 0) enhancedDesc.requiredLimits = nextLim;
                                        }
                                    } catch (_) {}

                                    return origReqDevice(enhancedDesc)
                                        .catch(function() { return origReqDevice(desc); })
                                        .then(function(device) {
                                            if (!device) return device;
                                            if (!device.adapterInfo && adapter.info) {
                                                try {
                                                    Object.defineProperty(device, "adapterInfo", { configurable: true, get: function() { return adapter.info; } });
                                                } catch (_) {}
                                            }

                                            // Record live adapter & feature telemetry
                                            try {
                                                const inf = adapter.info || {};
                                                gpuStats.supported = true;
                                                gpuStats.adapter = [inf.vendor, inf.architecture, inf.device || inf.description].filter(Boolean).join(" / ") || "Hardware GPU";
                                                gpuStats.backendMode = res.tier;
                                                gpuStats.format = navigator.gpu.getPreferredCanvasFormat ? navigator.gpu.getPreferredCanvasFormat() : "rgba8unorm";
                                                gpuStats.features = device.features ? Array.from(device.features) : [];
                                                if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                                    window.FableHybridBridge.onHeavyBurst();
                                                }
                                                pushGpuTelemetry(true);
                                            } catch (_) {}

                                            // 3. Collision-Free UID-Stamped WGSL Shader, Layout & Sync/Async Pipeline Cache
                                            if (!device.__fableShaderCached) {
                                                if (typeof device.createBindGroupLayout === "function") {
                                                    const origBgl = device.createBindGroupLayout.bind(device);
                                                    device.createBindGroupLayout = markNativeFn(function(d) {
                                                        return stampGpuUid(origBgl(d));
                                                    }, "createBindGroupLayout");
                                                }
                                                if (typeof device.createPipelineLayout === "function") {
                                                    const origPl = device.createPipelineLayout.bind(device);
                                                    device.createPipelineLayout = markNativeFn(function(d) {
                                                        return stampGpuUid(origPl(d));
                                                    }, "createPipelineLayout");
                                                }
                                                if (typeof device.createShaderModule === "function") {
                                                    const shaderCache = new Map();
                                                    const origCreateShader = device.createShaderModule.bind(device);
                                                    device.createShaderModule = markNativeFn(function(sDesc) {
                                                        if (sDesc && typeof sDesc.code === "string" && sDesc.code.length > 0 && sDesc.code.length <= 262144) {
                                                            const skey = fnvHashStr(sDesc.code) + "|" + sDesc.code;
                                                            const hit = shaderCache.get(skey);
                                                            if (hit) return hit;
                                                            const mod = stampGpuUid(origCreateShader(sDesc));
                                                            if (shaderCache.size >= 384) shaderCache.delete(shaderCache.keys().next().value);
                                                            shaderCache.set(skey, mod);
                                                            gpuStats.shadersCached = shaderCache.size;
                                                            pushGpuTelemetry(false);
                                                            return mod;
                                                        }
                                                        return stampGpuUid(origCreateShader(sDesc));
                                                    }, "createShaderModule");
                                                }
                                                const renderSyncCache = new Map();
                                                if (typeof device.createRenderPipeline === "function") {
                                                    const origRenderSync = device.createRenderPipeline.bind(device);
                                                    device.createRenderPipeline = markNativeFn(function(pDesc) {
                                                        const key = serializePipelineDesc("RS", pDesc);
                                                        if (!key) return origRenderSync(pDesc);
                                                        const hit = renderSyncCache.get(key);
                                                        if (hit) return hit;
                                                        const pipe = origRenderSync(pDesc);
                                                        if (renderSyncCache.size >= 256) renderSyncCache.delete(renderSyncCache.keys().next().value);
                                                        renderSyncCache.set(key, pipe);
                                                        gpuStats.renderPipesCached = renderSyncCache.size;
                                                        pushGpuTelemetry(false);
                                                        return pipe;
                                                    }, "createRenderPipeline");
                                                }
                                                if (typeof device.createRenderPipelineAsync === "function") {
                                                    const pipeCache = new Map();
                                                    const origRenderAsync = device.createRenderPipelineAsync.bind(device);
                                                    device.createRenderPipelineAsync = markNativeFn(function(pDesc) {
                                                        const key = serializePipelineDesc("RA", pDesc);
                                                        if (!key) return origRenderAsync(pDesc);
                                                        const hit = pipeCache.get(key);
                                                        if (hit) return hit;
                                                        const p = origRenderAsync(pDesc).catch(function(err) {
                                                            pipeCache.delete(key);
                                                            throw err;
                                                        });
                                                        if (pipeCache.size >= 256) pipeCache.delete(pipeCache.keys().next().value);
                                                        pipeCache.set(key, p);
                                                        gpuStats.renderPipesCached = Math.max(gpuStats.renderPipesCached, pipeCache.size);
                                                        pushGpuTelemetry(false);
                                                        return p;
                                                    }, "createRenderPipelineAsync");
                                                }
                                                const compSyncCache = new Map();
                                                if (typeof device.createComputePipeline === "function") {
                                                    const origCompSync = device.createComputePipeline.bind(device);
                                                    device.createComputePipeline = markNativeFn(function(cDesc) {
                                                        const key = serializePipelineDesc("CS", cDesc);
                                                        if (!key) return origCompSync(cDesc);
                                                        const hit = compSyncCache.get(key);
                                                        if (hit) return hit;
                                                        const pipe = origCompSync(cDesc);
                                                        if (compSyncCache.size >= 192) compSyncCache.delete(compSyncCache.keys().next().value);
                                                        compSyncCache.set(key, pipe);
                                                        gpuStats.computePipesCached = compSyncCache.size;
                                                        pushGpuTelemetry(false);
                                                        return pipe;
                                                    }, "createComputePipeline");
                                                }
                                                if (typeof device.createComputePipelineAsync === "function") {
                                                    const compCache = new Map();
                                                    const origCompAsync = device.createComputePipelineAsync.bind(device);
                                                    device.createComputePipelineAsync = markNativeFn(function(cDesc) {
                                                        const key = serializePipelineDesc("CA", cDesc);
                                                        if (!key) return origCompAsync(cDesc);
                                                        const hit = compCache.get(key);
                                                        if (hit) return hit;
                                                        const p = origCompAsync(cDesc).catch(function(err) {
                                                            compCache.delete(key);
                                                            throw err;
                                                        });
                                                        if (compCache.size >= 192) compCache.delete(compCache.keys().next().value);
                                                        compCache.set(key, p);
                                                        gpuStats.computePipesCached = Math.max(gpuStats.computePipesCached, compCache.size);
                                                        pushGpuTelemetry(false);
                                                        return p;
                                                    }, "createComputePipelineAsync");
                                                }
                                                device.__fableShaderCached = true;
                                            }

                                            if (device.lost && typeof device.lost.then === "function") {
                                                device.lost.then(function(lossInfo) {
                                                    const reason = lossInfo && lossInfo.reason ? String(lossInfo.reason) : "unknown";
                                                    if (reason !== "destroyed") {
                                                        gpuStats.deviceLossDelta++;
                                                        pushGpuTelemetry(true);
                                                        try {
                                                            if (window.FableHybridBridge && window.FableHybridBridge.onHeavyBurst) {
                                                                window.FableHybridBridge.onHeavyBurst();
                                                            }
                                                            window.dispatchEvent(new CustomEvent("fable-webgpu-device-lost", { detail: { reason: reason } }));
                                                        } catch (_) {}
                                                    }
                                                });
                                            }
                                            return device;
                                        });
                                }, "requestDevice");
                                adapter.__fableWrapped = true;
                            }
                            return adapter;
                        });
                }, "requestAdapter");
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
                            return String(window.FableHybridBridge.getClipboardText(__fableTok) || "");
                        }
                    } catch (_) {}
                    return "";
                };
                const writeNativeClip = function(txt) {
                    try {
                        if (window.FableHybridBridge && typeof window.FableHybridBridge.setClipboardText === "function") {
                            window.FableHybridBridge.setClipboardText(String(txt || ""), __fableTok);
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
                // Complete Blink Download Engine: retains Blob across immediate URL.revokeObjectURL,
                // handles detached off-DOM a.click(), <a download>, blob:, and data: with chunked native streaming!
                const blobRegistry = new Map();
                const recentDlKeys = new Map();
                if (typeof URL === "function" && typeof URL.createObjectURL === "function") {
                    const origCreateObjUrl = URL.createObjectURL.bind(URL);
                    const origRevokeObjUrl = typeof URL.revokeObjectURL === "function" ? URL.revokeObjectURL.bind(URL) : null;
                    URL.createObjectURL = function(obj) {
                        const u = origCreateObjUrl(obj);
                        try {
                            if (u && (obj instanceof Blob)) {
                                if (blobRegistry.size > 64) {
                                    const firstKey = blobRegistry.keys().next().value;
                                    if (firstKey) blobRegistry.delete(firstKey);
                                }
                                blobRegistry.set(u, obj);
                            }
                        } catch (_) {}
                        return u;
                    };
                    if (origRevokeObjUrl) {
                        URL.revokeObjectURL = function(u) {
                            if (u && blobRegistry.has(u)) {
                                setTimeout(function() {
                                    blobRegistry.delete(u);
                                    try { origRevokeObjUrl(u); } catch (_) {}
                                }, 25000);
                                return;
                            }
                            return origRevokeObjUrl(u);
                        };
                    }
                }
                const bytesToB64Fast = function(u8) {
                    let bin = "";
                    const step = 0x8000;
                    for (let i = 0; i < u8.length; i += step) {
                        bin += String.fromCharCode.apply(null, u8.subarray(i, Math.min(u8.length, i + step)));
                    }
                    return btoa(bin);
                };
                const streamBlobToNative = async function(blob, fileName, mimeFallback) {
                    const br = window.FableHybridBridge;
                    if (!br || !blob) return false;
                    const mime = (blob.type && blob.type.length > 0) ? blob.type : (mimeFallback || "application/octet-stream");
                    const safeName = fileName || "download";
                    if (typeof br.beginJsDownload === "function" && typeof br.appendJsDownloadChunk === "function" && typeof br.finishJsDownload === "function") {
                        const dlId = String(Date.now()) + "_" + Math.random().toString(36).slice(2, 8);
                        br.beginJsDownload(dlId, safeName, mime);
                        const chunkBytes = 1536 * 1024;
                        for (let off = 0; off < blob.size; off += chunkBytes) {
                            const ab = await blob.slice(off, Math.min(blob.size, off + chunkBytes)).arrayBuffer();
                            br.appendJsDownloadChunk(dlId, bytesToB64Fast(new Uint8Array(ab)));
                        }
                        br.finishJsDownload(dlId);
                        return true;
                    }
                    if (typeof br.saveBlobBase64 === "function") {
                        const ab = await blob.arrayBuffer();
                        br.saveBlobBase64(bytesToB64Fast(new Uint8Array(ab)), safeName, mime);
                        return true;
                    }
                    return false;
                };
                const triggerFableDownload = function(href, suggestedName, mimeHint) {
                    if (!href || typeof href !== "string") return false;
                    const br = window.FableHybridBridge;
                    if (!br) return false;
                    const now = Date.now();
                    const dedupKey = href.slice(0, 180) + "|" + (suggestedName || "");
                    const prevAt = recentDlKeys.get(dedupKey) || 0;
                    if (now - prevAt < 1200) return true;
                    recentDlKeys.set(dedupKey, now);
                    if (recentDlKeys.size > 40) recentDlKeys.clear();

                    if (href.indexOf("blob:") === 0) {
                        const cachedBlob = blobRegistry.get(href);
                        if (cachedBlob) {
                            const fName = suggestedName || (cachedBlob instanceof File && cachedBlob.name ? cachedBlob.name : "download");
                            streamBlobToNative(cachedBlob, fName, mimeHint);
                            return true;
                        }
                        fetch(href).then(function(r) { return r.blob(); }).then(function(b) {
                            streamBlobToNative(b, suggestedName || "download", mimeHint);
                        }).catch(function() {});
                        return true;
                    }
                    if (href.indexOf("data:") === 0) {
                        const comma = href.indexOf(",");
                        if (comma > 4) {
                            const meta = href.slice(5, comma);
                            const rawData = href.slice(comma + 1);
                            const mType = meta.split(";")[0] || mimeHint || "application/octet-stream";
                            const b64 = meta.toLowerCase().indexOf(";base64") !== -1
                                ? rawData
                                : bytesToB64Fast(new TextEncoder().encode(decodeURIComponent(rawData)));
                            if (typeof br.saveBlobBase64 === "function") {
                                br.saveBlobBase64(b64, suggestedName || "download", mType);
                                return true;
                            }
                        }
                    }
                    return false;
                };
                window.__fableDownloadUrl = triggerFableDownload;
                if (typeof HTMLAnchorElement === "function" && HTMLAnchorElement.prototype && typeof HTMLAnchorElement.prototype.click === "function") {
                    const origAnchorClick = HTMLAnchorElement.prototype.click;
                    HTMLAnchorElement.prototype.click = function() {
                        try {
                            const href = this.href || "";
                            const hasDl = this.hasAttribute("download");
                            const dlAttr = this.getAttribute("download") || "";
                            if (href.indexOf("blob:") === 0 || (hasDl && href.indexOf("data:") === 0)) {
                                if (triggerFableDownload(href, dlAttr || "download", "")) return;
                            }
                            if (hasDl && (href.indexOf("http://") === 0 || href.indexOf("https://") === 0) &&
                                window.FableHybridBridge && typeof window.FableHybridBridge.enqueueHttpDownload === "function") {
                                const parsedOrigin = (new URL(href, location.href)).origin;
                                if (parsedOrigin === location.origin || dlAttr.length > 0) {
                                    window.FableHybridBridge.enqueueHttpDownload(href, dlAttr);
                                    return;
                                }
                            }
                        } catch (_) {}
                        return origAnchorClick.apply(this, arguments);
                    };
                }
                document.addEventListener("click", function(ev) {
                    const a = ev.target && typeof ev.target.closest === "function" ? ev.target.closest("a[href]") : null;
                    if (!a) return;
                    const href = a.href || "";
                    const hasDl = a.hasAttribute("download");
                    const dlName = a.getAttribute("download") || "";
                    if (href.indexOf("blob:") === 0 || (hasDl && href.indexOf("data:") === 0)) {
                        if (triggerFableDownload(href, dlName || "download", "")) {
                            ev.preventDefault();
                            ev.stopPropagation();
                        }
                    } else if (hasDl && (href.indexOf("http://") === 0 || href.indexOf("https://") === 0) &&
                        window.FableHybridBridge && typeof window.FableHybridBridge.enqueueHttpDownload === "function") {
                        ev.preventDefault();
                        window.FableHybridBridge.enqueueHttpDownload(href, dlName);
                    }
                }, true);
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
                            const origBindVao = Proto.bindVertexArray;
                            const origDeleteVao = Proto.deleteVertexArray;
                            if (typeof origBindVao === "function") {
                                Proto.bindVertexArray = function(vao) {
                                    if (vao !== null && this.__fableLastVao === vao) return;
                                    this.__fableLastVao = vao;
                                    return origBindVao.call(this, vao);
                                };
                                if (typeof origDeleteVao === "function") {
                                    Proto.deleteVertexArray = function(vao) {
                                        if (this.__fableLastVao === vao) this.__fableLastVao = null;
                                        return origDeleteVao.call(this, vao);
                                    };
                                }
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

                    let rectGeneration = 1;
                    const origCanvasRect = HTMLCanvasElement.prototype.getBoundingClientRect;
                    HTMLCanvasElement.prototype.getBoundingClientRect = function() {
                        const ttl = CFG.canvasCacheMs || 150;
                        if (ttl <= 0) return origCanvasRect.call(this);
                        const t = performance.now();
                        if (this.__fableRect &&
                            this.__fableRectGen === rectGeneration &&
                            this.__fableRectW === this.width &&
                            this.__fableRectH === this.height &&
                            (t - (this.__fableRectTs || 0)) < ttl) {
                            return this.__fableRect;
                        }
                        const r = origCanvasRect.call(this);
                        this.__fableRect = r;
                        this.__fableRectGen = rectGeneration;
                        this.__fableRectW = this.width;
                        this.__fableRectH = this.height;
                        this.__fableRectTs = t;
                        return r;
                    };
                    window.addEventListener("resize", function() { rectGeneration++; }, { passive: true });
                    const notifyFs = function() {
                        rectGeneration++;
                        try {
                            const fsOn = !!(document.fullscreenElement || document.webkitFullscreenElement);
                            if (window.FableHybridBridge && typeof window.FableHybridBridge.onJsFullScreenChanged === "function") {
                                window.FableHybridBridge.onJsFullScreenChanged(fsOn);
                            }
                        } catch (_) {}
                    };
                    document.addEventListener("fullscreenchange", notifyFs, { passive: true });
                    document.addEventListener("webkitfullscreenchange", notifyFs, { passive: true });
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
        """.trimIndent() + suffix
    }

    fun buildBlinkArchiveScript(
        reqId: String,
        mode: String,
        incognito: Boolean,
        settings: Settings,
        continuous: Boolean = false,
        extraObservedUrls: Collection<String> = emptyList()
    ): String {
        val maxFiles = settings.archiveMaxFiles.coerceIn(500, 10000)
        val maxTotalMb = settings.archiveMaxTotalMb.coerceIn(100, 2000)
        val maxFileMb = settings.archiveMaxFileMb.coerceIn(20, 500)
        val splitPartMb = settings.archiveSplitPartMb.coerceIn(0, 200)
        val includeCanvas = settings.archiveIncludeCanvas
        val includeStorage = settings.archiveIncludeStorage
        val includeMemory = settings.archiveIncludeMemory || mode == "memory"
        val guessAssets = settings.archiveGuessAssets
        val includeCookies = settings.archiveIncludeCookies
        val readOnly = settings.archiveReadOnly
        val extraJson = JSONArray().apply { extraObservedUrls.take(2500).forEach { put(it) } }.toString()
        return """
        (async function() {
            const bridge = window.FableHybridBridge;
            if (!bridge || !bridge.postArchiveMessage) return;
            const ID = ${JSONObject.quote(reqId)};
            const MODE = ${JSONObject.quote(mode)};
            const INCOG = $incognito;
            const CONTINUOUS = $continuous;
            const READ_ONLY = $readOnly;
            const INC_COOKIES = $includeCookies;
            const EXTRA_URLS = $extraJson;
            const MAX_FILES = $maxFiles;
            const MAX_TOTAL_MB = $maxTotalMb;
            const MAX_FILE_MB = $maxFileMb;
            const SPLIT_PART_MB = $splitPartMb;
            const INC_CANVAS = $includeCanvas;
            const INC_STORAGE = $includeStorage;
            const INC_MEMORY = $includeMemory;
            const GUESS_ASSETS = $guessAssets;

            const send = function(obj) {
                obj.id = ID;
                bridge.postArchiveMessage(JSON.stringify(obj));
            };
            const bytesToB64 = function(bytes) {
                let bin = "";
                const chunk = 0x8000;
                for (let i = 0; i < bytes.length; i += chunk) {
                    bin += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(bytes.length, i + chunk)));
                }
                return btoa(bin);
            };
            const toB64 = function(str) {
                try {
                    return bytesToB64(new TextEncoder().encode(str));
                } catch (_) { return ""; }
            };
            const sendFileB64 = function(path, b64) {
                const step = 512 * 1024;
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
                const resEntries = (performance && performance.getEntriesByType) ? performance.getEntriesByType("resource") : [];
                const navEntries = (performance && performance.getEntriesByType) ? performance.getEntriesByType("navigation") : [];

                send({
                    op: "begin",
                    title: document.title || location.hostname || "page",
                    page: location.href,
                    incognito: INCOG,
                    mode: MODE,
                    continuous: CONTINUOUS,
                    readOnly: READ_ONLY,
                    includeCookies: INC_COOKIES,
                    engine: "Blink-Chromium + GeckoView-144",
                    splitPartMb: SPLIT_PART_MB,
                    observedResources: resEntries.length + EXTRA_URLS.length
                });

                const renderedHtml = "<!DOCTYPE html>\n" + (document.documentElement ? document.documentElement.outerHTML : "");
                sendText("page/rendered-dom.html", renderedHtml);
                if (MODE !== "memory") {
                    sendText("index.html", renderedHtml);
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

                    // JS custom globals & WebGL context state
                    try {
                        const glStates = [];
                        const cvs = document.querySelectorAll("canvas");
                        for (let i = 0; i < Math.min(cvs.length, 8); i++) {
                            const c = cvs[i];
                            const gl = c.getContext("webgl2") || c.getContext("webgl");
                            if (gl && typeof gl.getParameter === "function") {
                                const dbg = gl.getExtension("WEBGL_debug_renderer_info");
                                glStates.push({
                                    index: i,
                                    className: c.className || "",
                                    width: c.width,
                                    height: c.height,
                                    version: gl.getParameter(gl.VERSION),
                                    vendor: gl.getParameter(gl.VENDOR),
                                    renderer: gl.getParameter(gl.RENDERER),
                                    unmaskedVendor: dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : null,
                                    unmaskedRenderer: dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : null,
                                    maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE),
                                    extensions: (gl.getSupportedExtensions && gl.getSupportedExtensions()) || []
                                });
                            }
                        }
                        sendText("memory/js-heap-and-webgl.json", JSON.stringify({
                            jsHeap: performance && performance.memory ? {
                                usedJSHeapSize: performance.memory.usedJSHeapSize,
                                totalJSHeapSize: performance.memory.totalJSHeapSize,
                                jsHeapSizeLimit: performance.memory.jsHeapSizeLimit
                            } : null,
                            webglContexts: glStates
                        }, null, 2));
                    } catch (_) {}

                    // Accessible same-origin iframes
                    try {
                        const frames = document.querySelectorAll("iframe, frame");
                        for (let i = 0; i < Math.min(frames.length, 12); i++) {
                            try {
                                const fdoc = frames[i].contentDocument;
                                if (fdoc && fdoc.documentElement) {
                                    sendText("memory/frames/frame-" + (i + 1) + ".html", "<!DOCTYPE html>\n" + fdoc.documentElement.outerHTML);
                                }
                            } catch (_) {}
                        }
                    } catch (_) {}
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

                    // Full IndexedDB dump (captures bloxd_localforage, worldCodeAi, etc.)
                    if (window.indexedDB && typeof indexedDB.databases === "function") {
                        try {
                            const dbs = await indexedDB.databases();
                            for (let d = 0; d < Math.min(dbs.length, 16); d++) {
                                const dbName = dbs[d] && dbs[d].name;
                                if (!dbName) continue;
                                try {
                                    const dbDump = await new Promise(function(resolve) {
                                        const timer = setTimeout(function() { resolve(null); }, 6000);
                                        const req = indexedDB.open(dbName);
                                        req.onerror = function() { clearTimeout(timer); resolve(null); };
                                        req.onsuccess = function() {
                                            const db = req.result;
                                            const storeNames = Array.from(db.objectStoreNames || []);
                                            if (storeNames.length === 0) {
                                                db.close();
                                                clearTimeout(timer);
                                                resolve({});
                                                return;
                                            }
                                            const out = {};
                                            let pending = storeNames.length;
                                            let bytesEst = 0;
                                            const tx = db.transaction(storeNames, "readonly");
                                            storeNames.forEach(function(sName) {
                                                try {
                                                    const st = tx.objectStore(sName);
                                                    const rows = [];
                                                    const curReq = st.openCursor();
                                                    curReq.onsuccess = function(ev) {
                                                        const cursor = ev.target.result;
                                                        if (cursor && rows.length < 2500 && bytesEst < 24 * 1024 * 1024) {
                                                            let val = cursor.value;
                                                            if (val instanceof ArrayBuffer) {
                                                                val = { __binaryB64: bytesToB64(new Uint8Array(val)) };
                                                                bytesEst += val.__binaryB64.length;
                                                            } else if (ArrayBuffer.isView(val)) {
                                                                val = { __binaryB64: bytesToB64(new Uint8Array(val.buffer, val.byteOffset, val.byteLength)) };
                                                                bytesEst += val.__binaryB64.length;
                                                            } else if (val instanceof Blob) {
                                                                val = { __blobSize: val.size, __blobType: val.type };
                                                            } else {
                                                                bytesEst += 256;
                                                            }
                                                            rows.push({ key: cursor.key, value: val });
                                                            cursor.continue();
                                                        } else {
                                                            out[sName] = rows;
                                                            if (--pending <= 0) {
                                                                db.close();
                                                                clearTimeout(timer);
                                                                resolve(out);
                                                            }
                                                        }
                                                    };
                                                    curReq.onerror = function() {
                                                        out[sName] = rows;
                                                        if (--pending <= 0) {
                                                            db.close();
                                                            clearTimeout(timer);
                                                            resolve(out);
                                                        }
                                                    };
                                                } catch (_) {
                                                    if (--pending <= 0) {
                                                        db.close();
                                                        clearTimeout(timer);
                                                        resolve(out);
                                                    }
                                                }
                                            });
                                        };
                                    });
                                    if (dbDump) {
                                        const safeDb = dbName.replace(/[^A-Za-z0-9._-]/g, "_");
                                        sendText("memory/indexeddb/" + safeDb + ".json", JSON.stringify(dbDump));
                                    }
                                } catch (_) {}
                            }
                        } catch (_) {}
                    }
                }

                // Build HAR 1.2 + performance dump
                sendText("devtools/performance.json", JSON.stringify({ navigation: navEntries, resources: resEntries }, null, 2));
                try {
                    const epochStart = performance.timeOrigin || (Date.now() - performance.now());
                    const harEntries = resEntries.map(function(r) {
                        const startedIso = new Date(epochStart + (r.startTime || 0)).toISOString();
                        const waitMs = Math.max(0, (r.responseStart || r.startTime || 0) - (r.requestStart || r.startTime || 0));
                        const recvMs = Math.max(0, (r.responseEnd || r.startTime || 0) - (r.responseStart || r.startTime || 0));
                        return {
                            startedDateTime: startedIso,
                            time: Math.max(0, r.duration || 0),
                            request: {
                                method: "GET",
                                url: r.name,
                                httpVersion: r.nextHopProtocol || "HTTP/2.0",
                                headers: [],
                                queryString: [],
                                cookies: [],
                                headersSize: -1,
                                bodySize: 0
                            },
                            response: {
                                status: r.responseStatus || 200,
                                statusText: "OK",
                                httpVersion: r.nextHopProtocol || "HTTP/2.0",
                                headers: r.contentType ? [{ name: "content-type", value: r.contentType }] : [],
                                cookies: [],
                                content: {
                                    size: r.decodedBodySize || r.transferSize || 0,
                                    mimeType: r.contentType || "application/octet-stream"
                                },
                                redirectURL: "",
                                headersSize: -1,
                                bodySize: r.encodedBodySize || r.transferSize || 0
                            },
                            cache: {},
                            timings: { blocked: 0, dns: -1, connect: -1, send: 0, wait: waitMs, receive: recvMs, ssl: -1 }
                        };
                    });
                    sendText("devtools/network.har", JSON.stringify({
                        log: {
                            version: "1.2",
                            creator: { name: "Fable Unified Engine", version: "1.43.0" },
                            pages: [{
                                startedDateTime: new Date(epochStart).toISOString(),
                                id: "page_1",
                                title: document.title || location.href,
                                pageTimings: {}
                            }],
                            entries: harEntries
                        }
                    }));
                } catch (_) {}

                // Capture 2D & WebGL canvases inside requestAnimationFrame so preserveDrawingBuffer:false WebGL frames are live!
                if (INC_CANVAS) {
                    await new Promise(function(resolve) {
                        let settled = false;
                        const finishCv = function() {
                            if (settled) return;
                            settled = true;
                            try {
                                const canvases = document.querySelectorAll("canvas");
                                for (let i = 0; i < Math.min(canvases.length, 6); i++) {
                                    try {
                                        const c = canvases[i];
                                        if (c.width > 0 && c.height > 0) {
                                            const dataUrl = c.toDataURL("image/png");
                                            const comma = dataUrl.indexOf(",");
                                            if (comma > 0) {
                                                sendFileB64("memory/canvas-" + (i + 1) + "-" + c.width + "x" + c.height + ".png", dataUrl.slice(comma + 1));
                                            }
                                        }
                                    } catch (_) {}
                                }
                            } catch (_) {}
                            resolve();
                        };
                        setTimeout(finishCv, 250);
                        if (window.requestAnimationFrame) window.requestAnimationFrame(finishCv);
                    });
                }

                // Collect all page, CSS, ServiceWorker CacheStorage, and deep-scanned asset URLs!
                const urls = new Set();
                const addUrl = function(u, baseHref) {
                    if (!u || typeof u !== "string") return;
                    const trimmed = u.trim();
                    if (!trimmed || trimmed.indexOf("data:") === 0 || trimmed.indexOf("javascript:") === 0) return;
                    try {
                        const abs = new URL(trimmed, baseHref || location.href).href.split("#")[0];
                        if (abs.indexOf("http://") === 0 || abs.indexOf("https://") === 0) urls.add(abs);
                    } catch (_) {}
                };
                addUrl(location.href);
                document.querySelectorAll("script[src]").forEach(function(e) { addUrl(e.getAttribute("src")); });
                document.querySelectorAll("link[href]").forEach(function(e) { addUrl(e.getAttribute("href")); });
                document.querySelectorAll("img[src], audio[src], video[src], source[src]").forEach(function(e) { addUrl(e.getAttribute("src")); });
                document.querySelectorAll("video[poster]").forEach(function(e) { addUrl(e.getAttribute("poster")); });
                document.querySelectorAll("[srcset]").forEach(function(e) {
                    const ss = e.getAttribute("srcset") || "";
                    ss.split(",").forEach(function(part) {
                        const tok = part.trim().split(/\s+/)[0];
                        if (tok) addUrl(tok);
                    });
                });
                resEntries.forEach(function(r) { addUrl(r.name); });
                if (Array.isArray(EXTRA_URLS)) EXTRA_URLS.forEach(function(u) { addUrl(u); });
                try {
                    if (document.fonts && typeof document.fonts.forEach === "function") {
                        const flist = [];
                        document.fonts.forEach(function(f) {
                            flist.push({ family: f.family, status: f.status, style: f.style, weight: f.weight, display: f.display || "" });
                        });
                        if (flist.length > 0) sendText("memory/fonts.json", JSON.stringify(flist, null, 2));
                    }
                } catch (_) {}
                try {
                    if (navigator.serviceWorker && typeof navigator.serviceWorker.getRegistrations === "function") {
                        const regs = await navigator.serviceWorker.getRegistrations();
                        if (regs && regs.length > 0) {
                            const swDump = regs.map(function(r) {
                                const sc = (r.active && r.active.scriptURL) || (r.waiting && r.waiting.scriptURL) || (r.installing && r.installing.scriptURL) || "";
                                if (sc) addUrl(sc);
                                return { scope: r.scope, scriptURL: sc, updateViaCache: r.updateViaCache || "" };
                            });
                            sendText("memory/service-workers.json", JSON.stringify(swDump, null, 2));
                        }
                    }
                } catch (_) {}

                // Scan CSSOM rules for url(...) fonts, icons, skyboxes, backgrounds
                try {
                    const urlRe = /url\(\s*["']?([^"')]+)["']?\s*\)/gi;
                    for (let i = 0; i < document.styleSheets.length; i++) {
                        const sheet = document.styleSheets[i];
                        const base = sheet.href || location.href;
                        let rules = null;
                        try { rules = sheet.cssRules; } catch (_) { continue; }
                        if (!rules) continue;
                        for (let j = 0; j < rules.length; j++) {
                            const cssTxt = rules[j].cssText || "";
                            let m;
                            while ((m = urlRe.exec(cssTxt)) !== null) {
                                addUrl(m[1], base);
                            }
                        }
                    }
                } catch (_) {}

                // Service Worker CacheStorage URLs & cached bodies
                if (window.caches && typeof caches.keys === "function") {
                    try {
                        const cacheNames = await caches.keys();
                        const cacheIndex = [];
                        for (let c = 0; c < Math.min(cacheNames.length, 10); c++) {
                            const cObj = await caches.open(cacheNames[c]);
                            const reqs = await cObj.keys();
                            let cachedBodyTotal = 0;
                            for (let r = 0; r < Math.min(reqs.length, 400); r++) {
                                addUrl(reqs[r].url);
                                cacheIndex.push({ cache: cacheNames[c], url: reqs[r].url });
                                if (cachedBodyTotal < 24 * 1024 * 1024) {
                                    try {
                                        const cResp = await cObj.match(reqs[r]);
                                        if (cResp && cResp.ok) {
                                            const ab = await cResp.arrayBuffer();
                                            if (ab && ab.byteLength > 0 && ab.byteLength <= 4 * 1024 * 1024) {
                                                cachedBodyTotal += ab.byteLength;
                                                const cSafe = String(cacheNames[c]).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 40);
                                                sendFileB64("memory/cache-storage/" + cSafe + "/item_" + r + ".bin", bytesToB64(new Uint8Array(ab)));
                                            }
                                        }
                                    } catch (_) {}
                                }
                            }
                        }
                        if (cacheIndex.length > 0) {
                            sendText("memory/cache-storage.json", JSON.stringify(cacheIndex, null, 2));
                        }
                    } catch (_) {}
                }

                // Deep-scan same-origin JS chunks & manifests for referenced .wasm, .glb, textures, sounds, chunks!
                if (GUESS_ASSETS) {
                    try {
                        const assetRe = /["']((?:https?:\/\/[^"'\s<>]+|\/[A-Za-z0-9._\/-]+|static\/[A-Za-z0-9._\/-]+|textures\/[A-Za-z0-9._\/-]+|sounds\/[A-Za-z0-9._\/-]+)\.(?:js|css|json|wasm|glb|gltf|babylon|bin|ktx2|png|jpg|jpeg|webp|svg|woff2|mp3|ogg|mp4))["']/gi;
                        const jsCandidates = Array.from(urls).filter(function(u) {
                            return u.indexOf(location.origin) === 0 && /\.(?:js|json|css)(\?|$)/i.test(u);
                        }).slice(0, 28);
                        await Promise.all(jsCandidates.map(async function(jsUrl) {
                            try {
                                const resp = await fetch(jsUrl, { cache: "force-cache", credentials: "include" });
                                if (!resp || !resp.ok) return;
                                const txt = await resp.text();
                                if (txt.length > 8 * 1024 * 1024) return;
                                let m;
                                let found = 0;
                                while ((m = assetRe.exec(txt)) !== null && found < 400) {
                                    const cand = m[1].indexOf("/") === 0 || m[1].indexOf("http") === 0 ? m[1] : ("/" + m[1]);
                                    addUrl(cand, jsUrl);
                                    found++;
                                }
                            } catch (_) {}
                        }));
                    } catch (_) {}
                }

                // Dispatch all HTTP/HTTPS asset URLs in ONE batch to Kotlin PageArchiver's 12-stream native parallel downloader!
                // (Zero CORS failures, zero JS Base64 overhead, 5x-10x faster!)
                const allHttpUrls = Array.from(urls).slice(0, MAX_FILES);
                if (allHttpUrls.length > 0) {
                    send({
                        op: "fetch_batch",
                        urls: allHttpUrls,
                        userAgent: navigator.userAgent || "",
                        referer: location.href,
                        maxFileMb: MAX_FILE_MB,
                        maxTotalMb: MAX_TOTAL_MB,
                        observedResources: resEntries.length
                    });
                }

                send({ op: CONTINUOUS ? "phase_snapshot_ready" : "end" });
            } catch (err) {
                send({ op: "error", message: String(err) });
            }
        })();
        """.trimIndent()
    }
}
