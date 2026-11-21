package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import java.util.Locale

object WebsiteSpeedEngine {
    const val MIN_RATE = 0.1f
    const val MAX_RATE = 100.0f
    const val MIN_TENTHS = 1
    const val MAX_TENTHS = 1000

    val PRESET_TENTHS: List<Int> = listOf(1, 2, 5, 10, 20, 50, 100, 500, 1000)

    fun clampRate(rate: Float): Float =
        if (rate.isNaN() || rate.isInfinite()) 1.0f else rate.coerceIn(MIN_RATE, MAX_RATE)

    fun rateFromTenths(tenths: Int): Float =
        (tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)) / 10.0f

    fun tenthsFromRate(rate: Float): Int =
        Math.round(clampRate(rate) * 10.0f).coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun formatRate(rate: Float): String {
        val r = clampRate(rate)
        return if (r >= 10f && kotlin.math.abs(r - Math.round(r)) < 0.05f) {
            "${Math.round(r)}x"
        } else if (kotlin.math.abs(r - Math.round(r)) < 0.01f) {
            "${Math.round(r)}x"
        } else {
            String.format(Locale.US, "%.1fx", r)
        }
    }

    /**
     * Returns empty string when feature is OFF so ZERO bytes are injected into pages.
     */
    fun buildBootstrapScript(settings: Settings): String {
        if (!settings.timeScaleEnabled) return ""
        return buildBootstrapScript(
            rate = rateFromTenths(settings.timeScaleMultiplierTenths),
            applyCss = settings.timeScaleCssAnimations,
            applyMedia = settings.timeScaleMedia,
            applyWorkers = settings.timeScaleWorkers
        )
    }

    fun buildBootstrapScript(
        rate: Float,
        applyCss: Boolean = true,
        applyMedia: Boolean = true,
        applyWorkers: Boolean = true
    ): String {
        val safeRate = String.format(Locale.US, "%.3f", clampRate(rate))
        return """
        (function() {
            if (window.__fableTimeScale && window.__fableTimeScale.__installed) {
                window.__fableTimeScale.setConfig($safeRate, $applyCss, $applyMedia, $applyWorkers);
                return;
            }
            const clamp = function(v) {
                const n = Number(v);
                if (!Number.isFinite(n)) return 1.0;
                return Math.max(0.1, Math.min(100.0, n));
            };
            let currentRate = clamp($safeRate);
            let optCss = $applyCss;
            let optMedia = $applyMedia;
            let optWorkers = $applyWorkers;

            const origPerfNow = (window.performance && typeof performance.now === "function")
                ? performance.now.bind(performance)
                : function() { return origDateNow() - bootEpoch; };
            const OrigDate = window.Date;
            const origDateNow = OrigDate.now.bind(OrigDate);
            const bootEpoch = origDateNow();

            const origSetTimeout = window.setTimeout.bind(window);
            const origClearTimeout = window.clearTimeout.bind(window);
            const origSetInterval = window.setInterval.bind(window);
            const origClearInterval = window.clearInterval.bind(window);
            const origRAF = (typeof window.requestAnimationFrame === "function")
                ? window.requestAnimationFrame.bind(window) : null;
            const origCAF = (typeof window.cancelAnimationFrame === "function")
                ? window.cancelAnimationFrame.bind(window) : null;

            let realPerfAnchor = origPerfNow();
            let virtPerfAnchor = realPerfAnchor;
            let realDateAnchor = origDateNow();
            let virtDateAnchor = realDateAnchor;

            function virtPerfNow() {
                const r = origPerfNow();
                return virtPerfAnchor + (r - realPerfAnchor) * currentRate;
            }
            function virtDateNow() {
                const r = origDateNow();
                return Math.floor(virtDateAnchor + (r - realDateAnchor) * currentRate);
            }

            // 1. performance.now
            try {
                if (window.performance) {
                    Object.defineProperty(window.performance, "now", {
                        value: function now() { return virtPerfNow(); },
                        configurable: true,
                        writable: true
                    });
                }
            } catch (_) {}

            // 2. Date & Date.now (preserves instanceof Date, static Date.parse/UTC, and multi-arg constructors)
            try {
                const WrappedDate = new Proxy(OrigDate, {
                    construct(target, args, newTarget) {
                        if (!args || args.length === 0) {
                            return Reflect.construct(target, [virtDateNow()], newTarget);
                        }
                        return Reflect.construct(target, args, newTarget);
                    },
                    apply(target, thisArg, args) {
                        return new OrigDate(virtDateNow()).toString();
                    },
                    get(target, prop, receiver) {
                        if (prop === "now") return virtDateNow;
                        return Reflect.get(target, prop, target);
                    }
                });
                window.Date = WrappedDate;
            } catch (_) {
                try { OrigDate.now = virtDateNow; } catch (__) {}
            }

            // 3. Dynamic setTimeout / setInterval (reschedules active timers LIVE when speed changes!)
            let nextTimerId = 1;
            const timeouts = new Map();
            const intervals = new Map();

            function invokeCallback(fn, args) {
                if (typeof fn === "function") {
                    fn.apply(window, args);
                } else if (typeof fn === "string") {
                    try { (0, eval)(fn); } catch (_) {}
                }
            }

            function scheduleTimeoutEntry(entry) {
                if (entry.nativeId !== null) {
                    origClearTimeout(entry.nativeId);
                    entry.nativeId = null;
                }
                const remVirt = entry.virtFire - virtPerfNow();
                const realDelay = remVirt <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(remVirt / currentRate)));
                entry.nativeId = origSetTimeout(function() {
                    if (!timeouts.has(entry.id)) return;
                    const left = entry.virtFire - virtPerfNow();
                    if (left > 1.5 && currentRate < 1.0) {
                        scheduleTimeoutEntry(entry);
                        return;
                    }
                    timeouts.delete(entry.id);
                    invokeCallback(entry.fn, entry.args);
                }, realDelay);
            }

            function scheduleIntervalEntry(entry) {
                if (entry.nativeId !== null) {
                    origClearTimeout(entry.nativeId);
                    entry.nativeId = null;
                }
                const remVirt = entry.virtNext - virtPerfNow();
                const realDelay = remVirt <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(remVirt / currentRate)));
                entry.nativeId = origSetTimeout(function() {
                    if (!intervals.has(entry.id)) return;
                    const t0 = origPerfNow();
                    let steps = 0;
                    const maxSteps = currentRate > 1.0 ? Math.min(20, Math.ceil(currentRate)) : 1;
                    while (intervals.has(entry.id) && steps < maxSteps) {
                        const nowV = virtPerfNow();
                        if (nowV + 0.5 < entry.virtNext) break;
                        entry.virtNext += entry.intervalVirt;
                        steps++;
                        invokeCallback(entry.fn, entry.args);
                        if (origPerfNow() - t0 > 8.0) break;
                    }
                    if (intervals.has(entry.id)) {
                        const nowV2 = virtPerfNow();
                        if (entry.virtNext < nowV2 - entry.intervalVirt * 10) {
                            entry.virtNext = nowV2 + entry.intervalVirt;
                        }
                        scheduleIntervalEntry(entry);
                    }
                }, realDelay);
            }

            window.setTimeout = function(fn, delay) {
                const args = Array.prototype.slice.call(arguments, 2);
                const d = Math.max(0, Number(delay) || 0);
                const id = nextTimerId++;
                const entry = { id: id, fn: fn, args: args, virtFire: virtPerfNow() + d, nativeId: null };
                timeouts.set(id, entry);
                scheduleTimeoutEntry(entry);
                return id;
            };
            window.clearTimeout = function(id) {
                const entry = timeouts.get(id);
                if (entry) {
                    if (entry.nativeId !== null) origClearTimeout(entry.nativeId);
                    timeouts.delete(id);
                } else {
                    origClearTimeout(id);
                }
            };
            window.setInterval = function(fn, delay) {
                const args = Array.prototype.slice.call(arguments, 2);
                const d = Math.max(4, Number(delay) || 4);
                const id = nextTimerId++;
                const entry = { id: id, fn: fn, args: args, intervalVirt: d, virtNext: virtPerfNow() + d, nativeId: null };
                intervals.set(id, entry);
                scheduleIntervalEntry(entry);
                return id;
            };
            window.clearInterval = function(id) {
                const entry = intervals.get(id);
                if (entry) {
                    if (entry.nativeId !== null) origClearTimeout(entry.nativeId);
                    intervals.delete(id);
                } else {
                    origClearInterval(id);
                }
            };

            // 4. requestAnimationFrame (smooth virtual timestamp + budgeted fast-forward sub-steps when rate > 1)
            if (origRAF && origCAF) {
                let nextRafId = 1;
                const rafMap = new Map();
                window.requestAnimationFrame = function(cb) {
                    if (typeof cb !== "function") return 0;
                    const id = nextRafId++;
                    const nativeId = origRAF(function() {
                        if (!rafMap.has(id)) return;
                        rafMap.delete(id);
                        const vts = virtPerfNow();
                        cb(vts);
                    });
                    rafMap.set(id, nativeId);
                    return id;
                };
                window.cancelAnimationFrame = function(id) {
                    const nativeId = rafMap.get(id);
                    if (nativeId !== undefined) {
                        rafMap.delete(id);
                        origCAF(nativeId);
                    } else {
                        origCAF(id);
                    }
                };
            }

            // 5. CSS Animations, Transitions & Web Animations API
            function syncAnimation(anim) {
                if (!anim) return;
                try {
                    if (typeof anim.__fableBaseRate !== "number") {
                        anim.__fableBaseRate = (typeof anim.playbackRate === "number" && anim.playbackRate !== 0) ? anim.playbackRate : 1.0;
                    }
                    const target = optCss ? (anim.__fableBaseRate * currentRate) : anim.__fableBaseRate;
                    if (anim.playbackRate !== target) anim.playbackRate = target;
                } catch (_) {}
            }
            function syncAllAnimations() {
                try {
                    if (document && typeof document.getAnimations === "function") {
                        const list = document.getAnimations();
                        for (let i = 0; i < list.length; i++) syncAnimation(list[i]);
                    }
                } catch (_) {}
            }
            try {
                if (window.Element && Element.prototype && typeof Element.prototype.animate === "function") {
                    const origAnimate = Element.prototype.animate;
                    Element.prototype.animate = function() {
                        const anim = origAnimate.apply(this, arguments);
                        syncAnimation(anim);
                        return anim;
                    };
                }
            } catch (_) {}

            // 6. HTML5 Video & Audio playbackRate
            function syncMediaEl(el) {
                if (!el) return;
                try {
                    if (typeof el.__fableBaseMediaRate !== "number") {
                        el.__fableBaseMediaRate = (typeof el.playbackRate === "number" && el.playbackRate > 0) ? el.playbackRate : 1.0;
                    }
                    const rawTarget = optMedia ? (el.__fableBaseMediaRate * currentRate) : el.__fableBaseMediaRate;
                    const clamped = Math.max(0.0625, Math.min(16.0, rawTarget));
                    if (Math.abs(el.playbackRate - clamped) > 0.001) el.playbackRate = clamped;
                } catch (_) {}
            }
            function syncAllMedia() {
                try {
                    if (!document || !document.querySelectorAll) return;
                    const els = document.querySelectorAll("video, audio");
                    for (let i = 0; i < els.length; i++) syncMediaEl(els[i]);
                } catch (_) {}
            }

            try {
                const animHandler = function() { syncAllAnimations(); };
                const mediaHandler = function(ev) { if (ev && ev.target) syncMediaEl(ev.target); };
                if (document && document.addEventListener) {
                    document.addEventListener("animationstart", animHandler, true);
                    document.addEventListener("transitionrun", animHandler, true);
                    document.addEventListener("transitionstart", animHandler, true);
                    document.addEventListener("play", mediaHandler, true);
                    document.addEventListener("loadedmetadata", mediaHandler, true);
                    document.addEventListener("DOMContentLoaded", function() {
                        syncAllAnimations();
                        syncAllMedia();
                    }, true);
                }
                window.addEventListener("load", function() {
                    syncAllAnimations();
                    syncAllMedia();
                }, true);
            } catch (_) {}

            // 7. Optional Web Worker clock propagation (graceful fallback on CSP/module workers)
            const activeWorkers = new Set();
            try {
                if (typeof window.Worker === "function") {
                    const OrigWorker = window.Worker;
                    window.Worker = function FableScaledWorker(scriptURL, options) {
                        if (!optWorkers || (options && options.type === "module")) {
                            return new OrigWorker(scriptURL, options);
                        }
                        try {
                            const absUrl = new URL(String(scriptURL), location.href).href;
                            const shim =
                                "(function(){var R=" + currentRate + ",op=performance.now.bind(performance),od=Date.now.bind(Date)," +
                                "ra=op(),va=ra,da=od(),vda=da,ost=setTimeout.bind(self),osi=setInterval.bind(self);" +
                                "function vp(){return va+(op()-ra)*R;}function vd(){return Math.floor(vda+(od()-da)*R);}" +
                                "try{performance.now=vp;}catch(_){}try{Date.now=vd;}catch(_){}" +
                                "self.setTimeout=function(f,d){var a=Array.prototype.slice.call(arguments,2);return ost(function(){if(typeof f==='function')f.apply(self,a);},Math.max(0,Math.round((Number(d)||0)/R)));};" +
                                "self.setInterval=function(f,d){var a=Array.prototype.slice.call(arguments,2);return osi(function(){if(typeof f==='function')f.apply(self,a);},Math.max(4,Math.round((Number(d)||4)/R)));};" +
                                "self.addEventListener('message',function(e){if(e&&e.data&&e.data.__fableTimeRate){va=vp();ra=op();vda=vd();da=od();R=Math.max(0.1,Math.min(100,Number(e.data.__fableTimeRate)||1));}},true);" +
                                "importScripts(" + JSON.stringify(absUrl) + ");})();";
                            const blobUrl = URL.createObjectURL(new Blob([shim], { type: "application/javascript" }));
                            const w = new OrigWorker(blobUrl, options);
                            activeWorkers.add(w);
                            return w;
                        } catch (_) {
                            return new OrigWorker(scriptURL, options);
                        }
                    };
                    window.Worker.prototype = OrigWorker.prototype;
                }
            } catch (_) {}

            function setConfig(newRate, css, media, workers) {
                const nowP = origPerfNow();
                const nowD = origDateNow();
                virtPerfAnchor = virtPerfAnchor + (nowP - realPerfAnchor) * currentRate;
                realPerfAnchor = nowP;
                virtDateAnchor = virtDateAnchor + (nowD - realDateAnchor) * currentRate;
                realDateAnchor = nowD;
                currentRate = clamp(newRate);
                if (typeof css === "boolean") optCss = css;
                if (typeof media === "boolean") optMedia = media;
                if (typeof workers === "boolean") optWorkers = workers;

                // Reschedule all live timers seamlessly!
                timeouts.forEach(function(entry) { scheduleTimeoutEntry(entry); });
                intervals.forEach(function(entry) { scheduleIntervalEntry(entry); });
                syncAllAnimations();
                syncAllMedia();
                activeWorkers.forEach(function(w) {
                    try { w.postMessage({ __fableTimeRate: currentRate }); } catch (_) {}
                });
            }

            Object.defineProperty(window, "__fableTimeScale", {
                value: {
                    __installed: true,
                    getRate: function() { return currentRate; },
                    setRate: function(r) { setConfig(r, optCss, optMedia, optWorkers); },
                    setConfig: setConfig,
                    syncNow: function() { syncAllAnimations(); syncAllMedia(); }
                },
                configurable: true,
                enumerable: false
            });
        })();
        """.trimIndent()
    }

    fun buildLiveUpdateScript(
        enabled: Boolean,
        rate: Float,
        applyCss: Boolean,
        applyMedia: Boolean,
        applyWorkers: Boolean
    ): String {
        val effectiveRate = if (enabled) clampRate(rate) else 1.0f
        val safeRate = String.format(Locale.US, "%.3f", effectiveRate)
        val bootstrap = if (enabled) buildBootstrapScript(effectiveRate, applyCss, applyMedia, applyWorkers) else ""
        return if (enabled) {
            """
            (function() {
                if (window.__fableTimeScale && window.__fableTimeScale.__installed) {
                    window.__fableTimeScale.setConfig($safeRate, $applyCss, $applyMedia, $applyWorkers);
                } else {
                    $bootstrap
                }
            })();
            """.trimIndent()
        } else {
            // When turned OFF: only reset to 1.0x if it was previously installed in this already-open tab.
            // Never install anything when OFF!
            """
            (function() {
                if (window.__fableTimeScale && window.__fableTimeScale.__installed) {
                    window.__fableTimeScale.setConfig(1.0, false, false, false);
                }
            })();
            """.trimIndent()
        }
    }
}
