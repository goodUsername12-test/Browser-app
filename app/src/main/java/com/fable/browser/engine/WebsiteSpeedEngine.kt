package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object WebsiteSpeedEngine {
    const val MIN_RATE = 0.1f
    const val MAX_RATE = 300.0f
    const val MIN_TENTHS = 1
    const val MAX_TENTHS = 3000

    const val MIN_PANEL_SCALE_PCT = 60
    const val MAX_PANEL_SCALE_PCT = 160
    const val DEFAULT_PANEL_SCALE_PCT = 100
    const val DEFAULT_PANEL_POS_X = 0.94f
    const val DEFAULT_PANEL_POS_Y = 0.14f

    val PRESET_TENTHS: List<Int> = listOf(1, 5, 10, 30, 35, 60, 100, 500, 1000, 3000)

    // Random per-process neutral event channel name: zero "fable"/"speed"/"time" globals on window.
    private val CHANNEL_ID: String = "_ev_" + UUID.randomUUID().toString().replace("-", "").take(14)

    fun clampRate(rate: Float): Float =
        if (rate.isNaN() || rate.isInfinite()) 1.0f else rate.coerceIn(MIN_RATE, MAX_RATE)

    fun clampTenths(tenths: Int): Int = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun clampPanelScalePct(pct: Int): Int = pct.coerceIn(MIN_PANEL_SCALE_PCT, MAX_PANEL_SCALE_PCT)

    fun rateFromTenths(tenths: Int): Float = clampTenths(tenths) / 10.0f

    fun exactRateDoubleFromTenths(tenths: Int): Double = clampTenths(tenths) / 10.0

    fun tenthsFromRate(rate: Float): Int =
        (clampRate(rate) * 10.0f).roundToInt().coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun formatTenths(tenths: Int): String {
        val t = clampTenths(tenths)
        val whole = t / 10
        val frac = t % 10
        return if (frac == 0) "${whole}x" else "${whole}.${frac}x"
    }

    fun formatRate(rate: Float): String {
        val r = clampRate(rate)
        val t = (r * 10.0f).roundToInt().coerceIn(MIN_TENTHS, MAX_TENTHS)
        return formatTenths(t)
    }

    /**
     * Parses user-typed speed values like "3", "3.5", "3,5", "x6", "12.5x", "300" into exact tenths (1..3000).
     * Returns null when the input is blank or not a valid finite positive number.
     */
    fun parseSpeedInputToTenths(raw: String): Int? {
        val cleaned = raw.trim()
            .removePrefix("x").removePrefix("X").removePrefix("×")
            .removeSuffix("x").removeSuffix("X").removeSuffix("×")
            .trim()
            .replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return (value * 10.0).roundToLong().coerceIn(MIN_TENTHS.toLong(), MAX_TENTHS.toLong()).toInt()
    }

    /**
     * 4-zone exact precision slider mapping over [0f..1f]:
     * - [0.00 .. 0.25]: 0.1x .. 2.0x in exact 0.1x steps (tenths 1..20)
     * - [0.25 .. 0.55]: 2.0x .. 10.0x in exact 0.1x steps (tenths 20..100) — exact 3x, 3.5x, 6x, etc.
     * - [0.55 .. 0.80]: 10x .. 100x in exact 1x steps (tenths 100..1000)
     * - [0.80 .. 1.00]: 100x .. 300x in exact 1x steps (tenths 1000..3000)
     */
    fun sliderPosFromTenths(tenths: Int): Float {
        val t = clampTenths(tenths)
        return when {
            t <= 20 -> ((t - 1) / 19.0f) * 0.25f
            t <= 100 -> 0.25f + ((t - 20) / 80.0f) * 0.30f
            t <= 1000 -> 0.55f + ((t - 100) / 900.0f) * 0.25f
            else -> 0.80f + ((t - 1000) / 2000.0f) * 0.20f
        }.coerceIn(0f, 1f)
    }

    fun tenthsFromSliderPos(pos: Float): Int {
        val p = if (pos.isNaN() || pos.isInfinite()) 0.12f else pos.coerceIn(0f, 1f)
        val t = when {
            p <= 0.25f -> 1 + ((p / 0.25f) * 19.0f).roundToInt()
            p <= 0.55f -> 20 + (((p - 0.25f) / 0.30f) * 80.0f).roundToInt()
            p <= 0.80f -> {
                val whole = 10 + (((p - 0.55f) / 0.25f) * 90.0f).roundToInt()
                whole * 10
            }
            else -> {
                val whole = 100 + (((p - 0.80f) / 0.20f) * 200.0f).roundToInt()
                whole * 10
            }
        }
        return clampTenths(t)
    }

    fun nudgeTenths(tenths: Int, deltaSign: Int): Int {
        val t = clampTenths(tenths)
        val step = when {
            t < 200 -> 1 // 0.1x steps up to 20x
            t == 200 && deltaSign < 0 -> 1
            t < 1000 -> 10 // 1x steps 20x..100x
            t == 1000 && deltaSign < 0 -> 10
            else -> 50 // 5x steps 100x..300x
        }
        val next = if (deltaSign >= 0) t + step else t - step
        return clampTenths(next)
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
        val safeRate = formatDecimal(clampRate(rate))
        val ch = CHANNEL_ID
        return """
        (function() {
            var ch = "$ch";
            function dispatchCtrl(win, r, c, m, k) {
                var ack = { ok: false };
                try {
                    var ev = new win.CustomEvent(ch, { detail: { r: r, c: c, m: m, k: k, a: ack } });
                    win.dispatchEvent(ev);
                } catch (_) {
                    try {
                        var ev2 = win.document.createEvent("CustomEvent");
                        ev2.initCustomEvent(ch, false, false, { r: r, c: c, m: m, k: k, a: ack });
                        win.dispatchEvent(ev2);
                    } catch (__) {}
                }
                return ack.ok;
            }
            if (dispatchCtrl(window, $safeRate, $applyCss, $applyMedia, $applyWorkers)) {
                return;
            }
            function installIn(win) {
                if (!win) return;
                if (dispatchCtrl(win, $safeRate, $applyCss, $applyMedia, $applyWorkers)) return;
                var clamp = function(v) {
                    var n = Number(v);
                    if (!Number.isFinite(n)) return 1.0;
                    return Math.max(0.1, Math.min(300.0, n));
                };
                var curRate = clamp($safeRate);
                var optCss = $applyCss;
                var optMedia = $applyMedia;
                var optWorkers = $applyWorkers;

                var origFnToStr = win.Function.prototype.toString;
                var sigMap = new win.WeakMap();
                function mask(fn, orig, fallback) {
                    if (typeof fn !== "function") return fn;
                    var sig = "function " + (fallback || "") + "() { [native code] }";
                    if (typeof orig === "function") {
                        try { sig = origFnToStr.call(orig); } catch (_) {}
                        try { win.Object.defineProperty(fn, "name", { value: orig.name, configurable: true }); } catch (_) {}
                        try { win.Object.defineProperty(fn, "length", { value: orig.length, configurable: true }); } catch (_) {}
                    }
                    sigMap.set(fn, sig);
                    return fn;
                }
                try {
                    var maskedToStr = function toString() {
                        if (sigMap.has(this)) return sigMap.get(this);
                        return origFnToStr.call(this);
                    };
                    mask(maskedToStr, origFnToStr, "toString");
                    win.Object.defineProperty(win.Function.prototype, "toString", {
                        value: maskedToStr,
                        configurable: true,
                        writable: true
                    });
                } catch (_) {}

                var OrigDate = win.Date;
                var origDateNow = OrigDate.now.bind(OrigDate);
                var bootEpoch = origDateNow();
                var perfObj = win.performance;
                var perfProto = (win.Performance && win.Performance.prototype) ? win.Performance.prototype : null;
                var origPerfNowRaw = (perfProto && typeof perfProto.now === "function")
                    ? perfProto.now
                    : (perfObj && typeof perfObj.now === "function" ? perfObj.now : null);
                var origPerfNow = origPerfNowRaw
                    ? function() { return origPerfNowRaw.call(perfObj); }
                    : function() { return origDateNow() - bootEpoch; };

                var origST = win.setTimeout.bind(win);
                var origCT = win.clearTimeout.bind(win);
                var origSI = win.setInterval.bind(win);
                var origCI = win.clearInterval.bind(win);
                var origSTRaw = win.setTimeout;
                var origCTRaw = win.clearTimeout;
                var origSIRaw = win.setInterval;
                var origCIRaw = win.clearInterval;
                var origRAFRaw = typeof win.requestAnimationFrame === "function" ? win.requestAnimationFrame : null;
                var origCAFRaw = typeof win.cancelAnimationFrame === "function" ? win.cancelAnimationFrame : null;
                var origRAF = origRAFRaw ? origRAFRaw.bind(win) : null;
                var origCAF = origCAFRaw ? origCAFRaw.bind(win) : null;
                var origRICRaw = typeof win.requestIdleCallback === "function" ? win.requestIdleCallback : null;
                var origCICRaw = typeof win.cancelIdleCallback === "function" ? win.cancelIdleCallback : null;
                var origRIC = origRICRaw ? origRICRaw.bind(win) : null;
                var origCIC = origCICRaw ? origCICRaw.bind(win) : null;
                var origAddEv = win.addEventListener.bind(win);

                var realPerfAnchor = origPerfNow();
                var virtPerfAnchor = realPerfAnchor;
                var realDateAnchor = origDateNow();
                var virtDateAnchor = realDateAnchor;
                var lastExposedPerf = realPerfAnchor;
                var lastExposedDate = realDateAnchor;
                var stepPerf = null;
                var stepDate = null;
                var clockReadInTick = false;

                function rawVirtPerf() {
                    return virtPerfAnchor + (origPerfNow() - realPerfAnchor) * curRate;
                }
                function rawVirtDate() {
                    return Math.floor(virtDateAnchor + (origDateNow() - realDateAnchor) * curRate);
                }
                function virtPerf() {
                    clockReadInTick = true;
                    var v = stepPerf !== null ? stepPerf : rawVirtPerf();
                    if (v > lastExposedPerf) lastExposedPerf = v;
                    return lastExposedPerf;
                }
                function virtDate() {
                    clockReadInTick = true;
                    var v = stepDate !== null ? stepDate : rawVirtDate();
                    if (v > lastExposedDate) lastExposedDate = v;
                    return lastExposedDate;
                }

                // 1. Performance.prototype.now (preserves prototype placement so performance has no suspicious own property)
                var wrappedPerfNow = mask(function now() { return virtPerf(); }, origPerfNowRaw, "now");
                try {
                    if (perfProto && typeof perfProto.now === "function") {
                        win.Object.defineProperty(perfProto, "now", {
                            value: wrappedPerfNow,
                            configurable: true,
                            writable: true
                        });
                    } else if (perfObj) {
                        win.Object.defineProperty(perfObj, "now", {
                            value: wrappedPerfNow,
                            configurable: true,
                            writable: true
                        });
                    }
                } catch (_) {
                    try {
                        if (perfObj) {
                            win.Object.defineProperty(perfObj, "now", {
                                value: wrappedPerfNow,
                                configurable: true,
                                writable: true
                            });
                        }
                    } catch (__) {}
                }

                // 2. Date & Date.now (full Proxy preserving instanceof, constructor parity, Date.parse/UTC, native toString)
                var wrappedDateNow = mask(function now() { return virtDate(); }, OrigDate.now, "now");
                try {
                    var WrappedDate = new win.Proxy(OrigDate, {
                        construct: function(target, args, newTarget) {
                            if (!args || args.length === 0) {
                                return Reflect.construct(target, [virtDate()], newTarget);
                            }
                            return Reflect.construct(target, args, newTarget);
                        },
                        apply: function() {
                            return new OrigDate(virtDate()).toString();
                        },
                        get: function(target, prop) {
                            if (prop === "now") return wrappedDateNow;
                            return Reflect.get(target, prop, target);
                        }
                    });
                    sigMap.set(WrappedDate, origFnToStr.call(OrigDate));
                    win.Date = WrappedDate;
                    try {
                        win.Object.defineProperty(OrigDate.prototype, "constructor", {
                            value: WrappedDate,
                            configurable: true,
                            writable: true,
                            enumerable: false
                        });
                    } catch (_) {}
                } catch (_) {
                    try { OrigDate.now = wrappedDateNow; } catch (__) {}
                }

                // 3. Event.prototype.timeStamp consistency (low-overhead getter)
                try {
                    if (win.Event && win.Event.prototype) {
                        var tsDesc = win.Object.getOwnPropertyDescriptor(win.Event.prototype, "timeStamp");
                        if (tsDesc && typeof tsDesc.get === "function" && tsDesc.configurable) {
                            var origTsGet = tsDesc.get;
                            var evTsCache = new win.WeakMap();
                            var wrappedTsGet = mask(function timeStamp() {
                                if (evTsCache.has(this)) return evTsCache.get(this);
                                var raw = origTsGet.call(this);
                                if (typeof raw !== "number" || !Number.isFinite(raw)) return raw;
                                var nowReal = origPerfNow();
                                var out = (Math.abs(nowReal - raw) < 60000)
                                    ? Math.max(0, virtPerfAnchor + (raw - realPerfAnchor) * curRate)
                                    : raw;
                                evTsCache.set(this, out);
                                return out;
                            }, origTsGet, "get timeStamp");
                            win.Object.defineProperty(win.Event.prototype, "timeStamp", {
                                get: wrappedTsGet,
                                enumerable: tsDesc.enumerable,
                                configurable: true
                            });
                        }
                    }
                } catch (_) {}

                // 4. Live-rescheduling setTimeout / setInterval (0.1x to 300x, frame-budgeted bursts)
                var nextTid = 1;
                var timeouts = new win.Map();
                var intervals = new win.Map();

                function invokeCb(fn, args) {
                    if (typeof fn === "function") {
                        fn.apply(win, args);
                    } else if (typeof fn === "string") {
                        try { (0, win.eval)(fn); } catch (_) {}
                    }
                }

                function scheduleTimeout(entry) {
                    if (entry.nid !== null) {
                        origCT(entry.nid);
                        entry.nid = null;
                    }
                    var remVirt = entry.virtFire - rawVirtPerf();
                    var realDelay = remVirt <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(remVirt / curRate)));
                    entry.nid = origST(function() {
                        if (!timeouts.has(entry.id)) return;
                        var left = entry.virtFire - rawVirtPerf();
                        if (left > 1.5 && curRate < 1.0) {
                            scheduleTimeout(entry);
                            return;
                        }
                        timeouts.delete(entry.id);
                        invokeCb(entry.fn, entry.args);
                    }, realDelay);
                }

                function scheduleInterval(entry) {
                    if (entry.nid !== null) {
                        origCT(entry.nid);
                        entry.nid = null;
                    }
                    var remVirt = entry.virtNext - rawVirtPerf();
                    var realDelay = remVirt <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(remVirt / curRate)));
                    entry.nid = origST(function() {
                        if (!intervals.has(entry.id)) return;
                        var t0 = origPerfNow();
                        var steps = 0;
                        var maxSteps = curRate > 1.0 ? Math.min(64, Math.ceil(curRate)) : 1;
                        while (intervals.has(entry.id) && steps < maxSteps) {
                            var nowV = rawVirtPerf();
                            if (nowV + 0.5 < entry.virtNext) break;
                            entry.virtNext += entry.ivVirt;
                            steps++;
                            invokeCb(entry.fn, entry.args);
                            if (origPerfNow() - t0 > 9.0) break;
                        }
                        if (intervals.has(entry.id)) {
                            var nowV2 = rawVirtPerf();
                            if (entry.virtNext < nowV2 - entry.ivVirt * 16) {
                                entry.virtNext = nowV2 + entry.ivVirt;
                            }
                            scheduleInterval(entry);
                        }
                    }, realDelay);
                }

                win.setTimeout = mask(function setTimeout(fn, delay) {
                    var args = Array.prototype.slice.call(arguments, 2);
                    var d = Math.max(0, Number(delay) || 0);
                    var id = nextTid++;
                    var entry = { id: id, fn: fn, args: args, virtFire: rawVirtPerf() + d, nid: null };
                    timeouts.set(id, entry);
                    scheduleTimeout(entry);
                    return id;
                }, origSTRaw, "setTimeout");

                win.clearTimeout = mask(function clearTimeout(id) {
                    var e = timeouts.get(id);
                    if (e) {
                        if (e.nid !== null) origCT(e.nid);
                        timeouts.delete(id);
                    } else {
                        origCT(id);
                    }
                }, origCTRaw, "clearTimeout");

                win.setInterval = mask(function setInterval(fn, delay) {
                    var args = Array.prototype.slice.call(arguments, 2);
                    var d = Math.max(4, Number(delay) || 4);
                    var id = nextTid++;
                    var entry = { id: id, fn: fn, args: args, ivVirt: d, virtNext: rawVirtPerf() + d, nid: null };
                    intervals.set(id, entry);
                    scheduleInterval(entry);
                    return id;
                }, origSIRaw, "setInterval");

                win.clearInterval = mask(function clearInterval(id) {
                    var e = intervals.get(id);
                    if (e) {
                        if (e.nid !== null) origCT(e.nid);
                        intervals.delete(id);
                    } else {
                        origCI(id);
                    }
                }, origCIRaw, "clearInterval");

                // 5. requestIdleCallback / cancelIdleCallback
                if (origRIC && origCIC) {
                    win.requestIdleCallback = mask(function requestIdleCallback(cb, opts) {
                        if (opts && typeof opts.timeout === "number" && Number.isFinite(opts.timeout)) {
                            var scaledOpts = { timeout: Math.max(0, Math.round(opts.timeout / curRate)) };
                            return origRIC(cb, scaledOpts);
                        }
                        return origRIC(cb, opts);
                    }, origRICRaw, "requestIdleCallback");
                    win.cancelIdleCallback = mask(function cancelIdleCallback(id) {
                        return origCIC(id);
                    }, origCICRaw, "cancelIdleCallback");
                }

                // 6. Smart requestAnimationFrame (budgeted virtual sub-stepping for >1x + smooth dt / frame-counter throttle for <1x)
                if (origRAF && origCAF) {
                    var nextRafId = 1;
                    var rafQueue = new win.Map();
                    var cancelledInFrame = new win.Set();
                    var pumpId = null;
                    var lastRealRaf = origPerfNow();
                    var slowCarry = 0;
                    var cbUsesClockHint = true;

                    function ensurePump() {
                        if (pumpId === null && rafQueue.size > 0) {
                            pumpId = origRAF(onVsync);
                        }
                    }

                    function onVsync() {
                        pumpId = null;
                        if (rafQueue.size === 0) return;
                        var realNow = origPerfNow();
                        lastRealRaf = realNow;

                        if (curRate < 1.0 && !cbUsesClockHint) {
                            slowCarry += curRate;
                            if (slowCarry < 1.0) {
                                ensurePump();
                                return;
                            }
                            slowCarry -= 1.0;
                        } else {
                            slowCarry = 0;
                        }

                        var endP = rawVirtPerf();
                        var endD = rawVirtDate();
                        var startP = lastExposedPerf;
                        var startD = lastExposedDate;
                        var virtSpan = Math.max(0, endP - startP);
                        var targetSteps = curRate > 1.05
                            ? Math.max(1, Math.min(32, Math.round(virtSpan / 16.6667), Math.ceil(curRate)))
                            : 1;
                        var cpuStart = origPerfNow();
                        var anyClock = false;
                        var anyArg = false;

                        for (var step = 1; step <= targetSteps; step++) {
                            if (rafQueue.size === 0) break;
                            var batch = Array.from(rafQueue.entries());
                            rafQueue.clear();
                            cancelledInFrame.clear();
                            var frac = step / targetSteps;
                            var sp = step === targetSteps ? endP : (startP + (endP - startP) * frac);
                            var sd = step === targetSteps ? endD : Math.floor(startD + (endD - startD) * frac);
                            stepPerf = Math.max(lastExposedPerf, sp);
                            stepDate = Math.max(lastExposedDate, sd);
                            try {
                                var ts = virtPerf();
                                for (var i = 0; i < batch.length; i++) {
                                    var bid = batch[i][0];
                                    var cb = batch[i][1];
                                    if (cancelledInFrame.has(bid)) continue;
                                    if (cb.length > 0) anyArg = true;
                                    clockReadInTick = false;
                                    try {
                                        cb(ts);
                                    } catch (err) {
                                        origST(function() { throw err; }, 0);
                                    }
                                    if (clockReadInTick) anyClock = true;
                                }
                            } finally {
                                stepPerf = null;
                                stepDate = null;
                                cancelledInFrame.clear();
                            }
                            if (step < targetSteps && (origPerfNow() - cpuStart) > 9.5) break;
                        }
                        cbUsesClockHint = anyArg || anyClock;
                        if (rafQueue.size > 0) ensurePump();
                    }

                    win.requestAnimationFrame = mask(function requestAnimationFrame(cb) {
                        if (typeof cb !== "function") return 0;
                        var id = nextRafId++;
                        rafQueue.set(id, cb);
                        ensurePump();
                        return id;
                    }, origRAFRaw, "requestAnimationFrame");

                    win.cancelAnimationFrame = mask(function cancelAnimationFrame(id) {
                        rafQueue.delete(id);
                        cancelledInFrame.add(id);
                        if (rafQueue.size === 0 && pumpId !== null) {
                            origCAF(pumpId);
                            pumpId = null;
                        }
                    }, origCAFRaw, "cancelAnimationFrame");
                }

                // 7. CSS Animations, Transitions & Web Animations API (private WeakMap, zero DOM properties)
                var animBase = new win.WeakMap();
                function syncAnim(anim) {
                    if (!anim) return;
                    try {
                        var base = animBase.get(anim);
                        if (typeof base !== "number") {
                            base = (typeof anim.playbackRate === "number" && anim.playbackRate !== 0) ? anim.playbackRate : 1.0;
                            animBase.set(anim, base);
                        }
                        var target = optCss ? (base * curRate) : base;
                        if (anim.playbackRate !== target) {
                            try {
                                anim.playbackRate = target;
                            } catch (_) {
                                anim.playbackRate = Math.max(0.05, Math.min(100.0, target));
                            }
                        }
                    } catch (_) {}
                }
                function syncAllAnims() {
                    try {
                        if (win.document && typeof win.document.getAnimations === "function") {
                            var list = win.document.getAnimations();
                            for (var i = 0; i < list.length; i++) syncAnim(list[i]);
                        }
                    } catch (_) {}
                }
                try {
                    if (win.Element && win.Element.prototype && typeof win.Element.prototype.animate === "function") {
                        var origElAnim = win.Element.prototype.animate;
                        win.Element.prototype.animate = mask(function animate() {
                            var a = origElAnim.apply(this, arguments);
                            syncAnim(a);
                            return a;
                        }, origElAnim, "animate");
                    }
                    if (win.Animation && win.Animation.prototype && typeof win.Animation.prototype.play === "function") {
                        var origPlay = win.Animation.prototype.play;
                        win.Animation.prototype.play = mask(function play() {
                            syncAnim(this);
                            return origPlay.apply(this, arguments);
                        }, origPlay, "play");
                    }
                } catch (_) {}

                // 8. HTML5 Video & Audio + Web Audio AudioBufferSourceNode (private WeakMaps)
                var mediaBase = new win.WeakMap();
                var syncingMedia = new win.WeakSet();
                function syncMedia(el) {
                    if (!el) return;
                    try {
                        var base = mediaBase.get(el);
                        if (typeof base !== "number") {
                            base = (typeof el.playbackRate === "number" && el.playbackRate > 0) ? el.playbackRate : 1.0;
                            mediaBase.set(el, base);
                        }
                        var rawTarget = optMedia ? (base * curRate) : base;
                        var clamped = Math.max(0.0625, Math.min(16.0, rawTarget));
                        if (Math.abs(el.playbackRate - clamped) > 0.001) {
                            syncingMedia.add(el);
                            try { el.playbackRate = clamped; } finally { syncingMedia.delete(el); }
                        }
                    } catch (_) {}
                }
                function syncAllMedia() {
                    try {
                        if (!win.document || !win.document.querySelectorAll) return;
                        var els = win.document.querySelectorAll("video, audio");
                        for (var i = 0; i < els.length; i++) syncMedia(els[i]);
                    } catch (_) {}
                }

                var audioBase = new win.WeakMap();
                var activeAudioNodes = new win.Set();
                function syncAudioNode(node) {
                    if (!node || !node.playbackRate) return;
                    try {
                        var base = audioBase.get(node);
                        if (typeof base !== "number") {
                            base = (typeof node.playbackRate.value === "number" && node.playbackRate.value > 0) ? node.playbackRate.value : 1.0;
                            audioBase.set(node, base);
                        }
                        var target = Math.max(0.1, Math.min(16.0, optMedia ? (base * curRate) : base));
                        node.playbackRate.value = target;
                    } catch (_) {}
                }
                function syncAllAudio() {
                    activeAudioNodes.forEach(function(n) { syncAudioNode(n); });
                }
                try {
                    if (win.AudioBufferSourceNode && win.AudioBufferSourceNode.prototype && typeof win.AudioBufferSourceNode.prototype.start === "function") {
                        var origAudioStart = win.AudioBufferSourceNode.prototype.start;
                        win.AudioBufferSourceNode.prototype.start = mask(function start() {
                            try {
                                activeAudioNodes.add(this);
                                syncAudioNode(this);
                                if (typeof this.addEventListener === "function") {
                                    var selfNode = this;
                                    this.addEventListener("ended", function() { activeAudioNodes.delete(selfNode); }, { once: true });
                                }
                            } catch (_) {}
                            return origAudioStart.apply(this, arguments);
                        }, origAudioStart, "start");
                    }
                } catch (_) {}

                var domSyncQueued = false;
                function queueDomSync() {
                    if (domSyncQueued) return;
                    domSyncQueued = true;
                    origST(function() {
                        domSyncQueued = false;
                        if (optCss) syncAllAnims();
                        if (optMedia) syncAllMedia();
                    }, 16);
                }

                try {
                    var ah = function() { if (optCss) syncAllAnims(); };
                    var mh = function(ev) { if (ev && ev.target && optMedia) syncMedia(ev.target); };
                    var rch = function(ev) {
                        var el = ev && ev.target;
                        if (!el || syncingMedia.has(el)) return;
                        if (typeof el.playbackRate === "number" && el.playbackRate > 0) {
                            mediaBase.set(el, optMedia && curRate > 0 ? Math.max(0.0625, Math.min(16.0, el.playbackRate / curRate)) : el.playbackRate);
                            if (optMedia) syncMedia(el);
                        }
                    };
                    if (win.document && win.document.addEventListener) {
                        win.document.addEventListener("animationstart", ah, true);
                        win.document.addEventListener("animationiteration", ah, true);
                        win.document.addEventListener("transitionrun", ah, true);
                        win.document.addEventListener("transitionstart", ah, true);
                        win.document.addEventListener("play", mh, true);
                        win.document.addEventListener("playing", mh, true);
                        win.document.addEventListener("loadedmetadata", mh, true);
                        win.document.addEventListener("ratechange", rch, true);
                        var moAttached = false;
                        function attachObserver() {
                            if (moAttached) return;
                            try {
                                if (typeof win.MutationObserver === "function" && win.document.documentElement) {
                                    moAttached = true;
                                    var mo = new win.MutationObserver(function(muts) {
                                        for (var i = 0; i < muts.length; i++) {
                                            if (muts[i].addedNodes && muts[i].addedNodes.length > 0) {
                                                queueDomSync();
                                                break;
                                            }
                                        }
                                    });
                                    mo.observe(win.document.documentElement, { childList: true, subtree: true });
                                }
                            } catch (_) {}
                        }
                        win.document.addEventListener("DOMContentLoaded", function() {
                            syncAllAnims();
                            syncAllMedia();
                            attachObserver();
                        }, true);
                        if (win.document.readyState && win.document.readyState !== "loading") {
                            attachObserver();
                        }
                    }
                    origAddEv("load", function() {
                        syncAllAnims();
                        syncAllMedia();
                    }, true);
                } catch (_) {}

                // 9. Stealth Classic Web Worker propagation (up to 300x, private token key, stops message leak)
                var activeWorkers = new win.Set();
                try {
                    if (typeof win.Worker === "function") {
                        var OrigWorker = win.Worker;
                        var WrappedWorker = mask(function Worker(scriptURL, options) {
                            if (!optWorkers || (options && options.type === "module")) {
                                return new OrigWorker(scriptURL, options);
                            }
                            try {
                                var absUrl = new win.URL(String(scriptURL), win.location.href).href;
                                var wkKey = JSON.stringify(ch);
                                var shim =
                                    "(function(){var K=" + wkKey + ",R=" + curRate +
                                    ",ots=Function.prototype.toString,sm=new WeakMap();" +
                                    "function mk(f,o){if(typeof o==='function'){try{sm.set(f,ots.call(o));}catch(_){}try{Object.defineProperty(f,'name',{value:o.name,configurable:true});}catch(_){}}return f;}" +
                                    "try{var wt=function toString(){return sm.has(this)?sm.get(this):ots.call(this);};mk(wt,ots);Object.defineProperty(Function.prototype,'toString',{value:wt,configurable:true,writable:true});}catch(_){}" +
                                    "var op=performance.now.bind(performance),OD=Date,od=OD.now.bind(OD),ra=op(),va=ra,da=od(),vda=da," +
                                    "ost=setTimeout.bind(self),oct=clearTimeout.bind(self),osi=setInterval.bind(self),oci=clearInterval.bind(self)," +
                                    "ostR=setTimeout,osiR=setInterval;" +
                                    "function vp(){return va+(op()-ra)*R;}function vd(){return Math.floor(vda+(od()-da)*R);}" +
                                    "try{performance.now=mk(function now(){return vp();},performance.now);}catch(_){}" +
                                    "var wdn=mk(function now(){return vd();},OD.now);" +
                                    "try{var wd=new Proxy(OD,{construct:function(t,a,nt){return Reflect.construct(t,(!a||!a.length)?[vd()]:a,nt);},apply:function(){return new OD(vd()).toString();},get:function(t,p){return p==='now'?wdn:Reflect.get(t,p,t);}});sm.set(wd,ots.call(OD));self.Date=wd;}catch(_){}" +
                                    "self.setTimeout=mk(function setTimeout(f,d){var a=Array.prototype.slice.call(arguments,2);return ost(function(){if(typeof f==='function')f.apply(self,a);},Math.max(0,Math.round((Number(d)||0)/R)));},ostR);" +
                                    "self.setInterval=mk(function setInterval(f,d){var a=Array.prototype.slice.call(arguments,2);return osi(function(){if(typeof f==='function')f.apply(self,a);},Math.max(4,Math.round((Number(d)||4)/R)));},osiR);" +
                                    "self.addEventListener('message',function(e){if(e&&e.data&&typeof e.data==='object'&&e.data[K]!==undefined){e.stopImmediatePropagation();va=vp();ra=op();vda=vd();da=od();R=Math.max(0.1,Math.min(300,Number(e.data[K])||1));}},true);" +
                                    "importScripts(" + JSON.stringify(absUrl) + ");})();";
                                var blobUrl = win.URL.createObjectURL(new win.Blob([shim], { type: "application/javascript" }));
                                var w = new OrigWorker(blobUrl, options);
                                activeWorkers.add(w);
                                return w;
                            } catch (_) {
                                return new OrigWorker(scriptURL, options);
                            }
                        }, OrigWorker, "Worker");
                        WrappedWorker.prototype = OrigWorker.prototype;
                        win.Worker = WrappedWorker;
                    }
                } catch (_) {}

                function setConfig(newRate, css, media, workers) {
                    var nowP = origPerfNow();
                    var nowD = origDateNow();
                    virtPerfAnchor = virtPerfAnchor + (nowP - realPerfAnchor) * curRate;
                    realPerfAnchor = nowP;
                    virtDateAnchor = virtDateAnchor + (nowD - realDateAnchor) * curRate;
                    realDateAnchor = nowD;
                    curRate = clamp(newRate);
                    if (typeof css === "boolean") optCss = css;
                    if (typeof media === "boolean") optMedia = media;
                    if (typeof workers === "boolean") optWorkers = workers;

                    timeouts.forEach(function(e) { scheduleTimeout(e); });
                    intervals.forEach(function(e) { scheduleInterval(e); });
                    syncAllAnims();
                    syncAllMedia();
                    syncAllAudio();
                    var wkMsg = {};
                    wkMsg[ch] = curRate;
                    activeWorkers.forEach(function(w) {
                        try { w.postMessage(wkMsg); } catch (_) {}
                    });
                    // Propagate live changes to same-origin child frames
                    try {
                        for (var i = 0; i < win.frames.length; i++) {
                            try {
                                var fw = win.frames[i];
                                if (fw && fw !== win) dispatchCtrl(fw, curRate, optCss, optMedia, optWorkers);
                            } catch (_) {}
                        }
                    } catch (_) {}
                }

                // Private non-enumerable event listener (zero global properties on window!)
                origAddEv(ch, function(ev) {
                    var d = ev && ev.detail;
                    if (!d || typeof d !== "object") return;
                    if (d.a && typeof d.a === "object") d.a.ok = true;
                    setConfig(d.r, d.c, d.m, d.k);
                }, false);

                // Initial immediate sync for pages where engine was installed live without refresh
                syncAllAnims();
                syncAllMedia();
            }

            installIn(window);
            try {
                for (var fi = 0; fi < window.frames.length; fi++) {
                    try {
                        var fw = window.frames[fi];
                        if (fw && fw !== window) installIn(fw);
                    } catch (_) {}
                }
            } catch (_) {}
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
        val safeRate = formatDecimal(effectiveRate)
        val ch = CHANNEL_ID
        val bootstrap = if (enabled) buildBootstrapScript(effectiveRate, applyCss, applyMedia, applyWorkers) else ""
        return if (enabled) {
            """
            (function() {
                var ch = "$ch";
                var ack = { ok: false };
                try {
                    window.dispatchEvent(new CustomEvent(ch, { detail: { r: $safeRate, c: $applyCss, m: $applyMedia, k: $applyWorkers, a: ack } }));
                } catch (_) {
                    try {
                        var ev = document.createEvent("CustomEvent");
                        ev.initCustomEvent(ch, false, false, { r: $safeRate, c: $applyCss, m: $applyMedia, k: $applyWorkers, a: ack });
                        window.dispatchEvent(ev);
                    } catch (__) {}
                }
                if (!ack.ok) {
                    $bootstrap
                }
            })();
            """.trimIndent()
        } else {
            // When turned OFF: only reset already-installed pages to 1.0x via private event; NEVER install anything!
            """
            (function() {
                var ch = "$ch";
                var ack = { ok: false };
                try {
                    window.dispatchEvent(new CustomEvent(ch, { detail: { r: 1.0, c: false, m: false, k: false, a: ack } }));
                } catch (_) {
                    try {
                        var ev = document.createEvent("CustomEvent");
                        ev.initCustomEvent(ch, false, false, { r: 1.0, c: false, m: false, k: false, a: ack });
                        window.dispatchEvent(ev);
                    } catch (__) {}
                }
            })();
            """.trimIndent()
        }
    }

    private fun formatDecimal(rate: Float): String {
        val t = (clampRate(rate) * 10.0f).roundToInt().coerceIn(MIN_TENTHS, MAX_TENTHS)
        val whole = t / 10
        val frac = t % 10
        return if (abs(rate * 10f - t) < 0.001f) {
            "$whole.$frac"
        } else {
            String.format(Locale.US, "%.3f", clampRate(rate))
        }
    }
}
