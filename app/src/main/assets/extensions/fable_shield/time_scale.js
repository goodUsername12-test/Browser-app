(() => {
  const CFG = typeof FABLE_TIME_CFG === "object" && FABLE_TIME_CFG ? FABLE_TIME_CFG : null;
  if (!CFG || CFG.enabled !== true) return;
  if (globalThis.__fableTimeScaleContentLoaded) return;
  globalThis.__fableTimeScaleContentLoaded = true;

  const clamp = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return 1.0;
    return Math.max(0.1, Math.min(100.0, n));
  };

  let currentRate = clamp(CFG.rate);
  let optCss = CFG.css !== false;
  let optMedia = CFG.media !== false;
  let optWorkers = CFG.workers !== false;

  const W = window.wrappedJSObject || window;
  const useXray = typeof exportFunction === "function" && W !== window;

  const origPerfNow = (window.performance && typeof window.performance.now === "function")
    ? window.performance.now.bind(window.performance)
    : () => Date.now();
  const OrigDate = window.Date;
  const origDateNow = OrigDate.now.bind(OrigDate);
  const origSetTimeout = window.setTimeout.bind(window);
  const origClearTimeout = window.clearTimeout.bind(window);
  const origSetInterval = window.setInterval.bind(window);
  const origClearInterval = window.clearInterval.bind(window);
  const origRAF = typeof window.requestAnimationFrame === "function" ? window.requestAnimationFrame.bind(window) : null;
  const origCAF = typeof window.cancelAnimationFrame === "function" ? window.cancelAnimationFrame.bind(window) : null;

  let realPerfAnchor = origPerfNow();
  let virtPerfAnchor = realPerfAnchor;
  let realDateAnchor = origDateNow();
  let virtDateAnchor = realDateAnchor;

  function virtPerfNow() {
    return virtPerfAnchor + (origPerfNow() - realPerfAnchor) * currentRate;
  }
  function virtDateNow() {
    return Math.floor(virtDateAnchor + (origDateNow() - realDateAnchor) * currentRate);
  }

  function exportFn(fn) {
    return useXray ? exportFunction(fn, window) : fn;
  }

  // 1. performance.now
  try {
    const perfObj = W.performance;
    if (perfObj) {
      W.Object.defineProperty(perfObj, "now", useXray ? cloneInto({
        value: exportFn(() => virtPerfNow()),
        configurable: true,
        writable: true
      }, window, { cloneFunctions: true }) : {
        value: () => virtPerfNow(),
        configurable: true,
        writable: true
      });
    }
  } catch (_) {}

  // 2. Date & Date.now (using page-realm Proxy for zero-CSP-violation constructor override)
  try {
    const PageDate = W.Date;
    const handler = {
      construct(target, args, newTarget) {
        if (!args || args.length === 0) {
          return W.Reflect.construct(target, useXray ? cloneInto([virtDateNow()], window) : [virtDateNow()], newTarget);
        }
        return W.Reflect.construct(target, args, newTarget);
      },
      apply() {
        return new PageDate(virtDateNow()).toString();
      },
      get(target, prop) {
        if (prop === "now") return exportFn(() => virtDateNow());
        return W.Reflect.get(target, prop, target);
      }
    };
    const proxyHandler = useXray ? cloneInto(handler, window, { cloneFunctions: true }) : handler;
    W.Date = new W.Proxy(PageDate, proxyHandler);
  } catch (_) {
    try { W.Date.now = exportFn(() => virtDateNow()); } catch (__) {}
  }

  // 3. Dynamic setTimeout / setInterval with live rescheduling
  let nextTimerId = 1;
  const timeouts = new Map();
  const intervals = new Map();

  function invokePageFn(fn, args) {
    try {
      if (typeof fn === "function") {
        W.Function.prototype.apply.call(fn, W, args);
      } else if (typeof fn === "string") {
        W.eval(fn);
      }
    } catch (_) {}
  }

  function scheduleTimeoutEntry(entry) {
    if (entry.nativeId !== null) {
      origClearTimeout(entry.nativeId);
      entry.nativeId = null;
    }
    const remVirt = entry.virtFire - virtPerfNow();
    const realDelay = remVirt <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(remVirt / currentRate)));
    entry.nativeId = origSetTimeout(() => {
      if (!timeouts.has(entry.id)) return;
      const left = entry.virtFire - virtPerfNow();
      if (left > 1.5 && currentRate < 1.0) {
        scheduleTimeoutEntry(entry);
        return;
      }
      timeouts.delete(entry.id);
      invokePageFn(entry.fn, entry.args);
    }, realDelay);
  }

  function scheduleIntervalEntry(entry) {
    if (entry.nativeId !== null) {
      origClearTimeout(entry.nativeId);
      entry.nativeId = null;
    }
    const remVirt = entry.virtNext - virtPerfNow();
    const realDelay = remVirt <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(remVirt / currentRate)));
    entry.nativeId = origSetTimeout(() => {
      if (!intervals.has(entry.id)) return;
      const t0 = origPerfNow();
      let steps = 0;
      const maxSteps = currentRate > 1.0 ? Math.min(20, Math.ceil(currentRate)) : 1;
      while (intervals.has(entry.id) && steps < maxSteps) {
        const nowV = virtPerfNow();
        if (nowV + 0.5 < entry.virtNext) break;
        entry.virtNext += entry.intervalVirt;
        steps++;
        invokePageFn(entry.fn, entry.args);
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

  try {
    W.setTimeout = exportFn(function(fn, delay) {
      const args = W.Array.prototype.slice.call(arguments, 2);
      const d = Math.max(0, Number(delay) || 0);
      const id = nextTimerId++;
      const entry = { id, fn, args, virtFire: virtPerfNow() + d, nativeId: null };
      timeouts.set(id, entry);
      scheduleTimeoutEntry(entry);
      return id;
    });
    W.clearTimeout = exportFn(function(id) {
      const entry = timeouts.get(id);
      if (entry) {
        if (entry.nativeId !== null) origClearTimeout(entry.nativeId);
        timeouts.delete(id);
      } else {
        origClearTimeout(id);
      }
    });
    W.setInterval = exportFn(function(fn, delay) {
      const args = W.Array.prototype.slice.call(arguments, 2);
      const d = Math.max(4, Number(delay) || 4);
      const id = nextTimerId++;
      const entry = { id, fn, args, intervalVirt: d, virtNext: virtPerfNow() + d, nativeId: null };
      intervals.set(id, entry);
      scheduleIntervalEntry(entry);
      return id;
    });
    W.clearInterval = exportFn(function(id) {
      const entry = intervals.get(id);
      if (entry) {
        if (entry.nativeId !== null) origClearTimeout(entry.nativeId);
        intervals.delete(id);
      } else {
        origClearInterval(id);
      }
    });
  } catch (_) {}

  // 4. requestAnimationFrame
  if (origRAF && origCAF) {
    try {
      let nextRafId = 1;
      const rafMap = new Map();
      W.requestAnimationFrame = exportFn(function(cb) {
        if (typeof cb !== "function") return 0;
        const id = nextRafId++;
        const nativeId = origRAF(() => {
          if (!rafMap.has(id)) return;
          rafMap.delete(id);
          try {
            W.Function.prototype.call.call(cb, W, virtPerfNow());
          } catch (_) {}
        });
        rafMap.set(id, nativeId);
        return id;
      });
      W.cancelAnimationFrame = exportFn(function(id) {
        const nativeId = rafMap.get(id);
        if (nativeId !== undefined) {
          rafMap.delete(id);
          origCAF(nativeId);
        } else {
          origCAF(id);
        }
      });
    } catch (_) {}
  }

  // 5. CSS Animations, Transitions & Web Animations API
  const baseAnimRates = new WeakMap();
  function syncAnimation(anim) {
    if (!anim) return;
    try {
      if (!baseAnimRates.has(anim)) {
        const cur = typeof anim.playbackRate === "number" && anim.playbackRate !== 0 ? anim.playbackRate : 1.0;
        baseAnimRates.set(anim, cur);
      }
      const base = baseAnimRates.get(anim) || 1.0;
      const target = optCss ? base * currentRate : base;
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
    if (W.Element && W.Element.prototype && typeof W.Element.prototype.animate === "function") {
      const origAnim = W.Element.prototype.animate;
      W.Element.prototype.animate = exportFn(function() {
        const anim = W.Function.prototype.apply.call(origAnim, this, arguments);
        syncAnimation(anim);
        return anim;
      });
    }
  } catch (_) {}

  // 6. HTML5 Video & Audio playbackRate
  const baseMediaRates = new WeakMap();
  function syncMediaEl(el) {
    if (!el) return;
    try {
      if (!baseMediaRates.has(el)) {
        const cur = typeof el.playbackRate === "number" && el.playbackRate > 0 ? el.playbackRate : 1.0;
        baseMediaRates.set(el, cur);
      }
      const base = baseMediaRates.get(el) || 1.0;
      const target = Math.max(0.0625, Math.min(16.0, optMedia ? base * currentRate : base));
      if (Math.abs(el.playbackRate - target) > 0.001) el.playbackRate = target;
    } catch (_) {}
  }
  function syncAllMedia() {
    try {
      const els = document.querySelectorAll("video, audio");
      for (let i = 0; i < els.length; i++) syncMediaEl(els[i]);
    } catch (_) {}
  }

  try {
    document.addEventListener("animationstart", () => syncAllAnimations(), true);
    document.addEventListener("transitionrun", () => syncAllAnimations(), true);
    document.addEventListener("transitionstart", () => syncAllAnimations(), true);
    document.addEventListener("play", (ev) => { if (ev && ev.target) syncMediaEl(ev.target); }, true);
    document.addEventListener("loadedmetadata", (ev) => { if (ev && ev.target) syncMediaEl(ev.target); }, true);
    document.addEventListener("DOMContentLoaded", () => { syncAllAnimations(); syncAllMedia(); }, true);
    window.addEventListener("load", () => { syncAllAnimations(); syncAllMedia(); }, true);
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
    timeouts.forEach((entry) => scheduleTimeoutEntry(entry));
    intervals.forEach((entry) => scheduleIntervalEntry(entry));
    syncAllAnimations();
    syncAllMedia();
  }

  // Expose live controller on page window for diagnostics & live sync
  try {
    const apiObj = {
      __installed: true,
      getRate: exportFn(() => currentRate),
      setRate: exportFn((r) => setConfig(r, optCss, optMedia, optWorkers)),
      setConfig: exportFn(setConfig),
      syncNow: exportFn(() => { syncAllAnimations(); syncAllMedia(); })
    };
    W.Object.defineProperty(W, "__fableTimeScale", useXray ? cloneInto({
      value: cloneInto(apiObj, window, { cloneFunctions: true }),
      configurable: true,
      enumerable: false
    }, window, { cloneFunctions: true }) : {
      value: apiObj,
      configurable: true,
      enumerable: false
    });
  } catch (_) {}

  try {
    if (typeof browser !== "undefined" && browser.runtime && browser.runtime.onMessage) {
      browser.runtime.onMessage.addListener((msg) => {
        if (msg && msg.type === "fable-time-scale-update") {
          setConfig(msg.enabled ? msg.rate : 1.0, msg.css, msg.media, msg.workers);
        }
      });
    }
  } catch (_) {}
})();
