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

  const initRate = clamp(CFG.rate);
  const initCss = CFG.css !== false;
  const initMedia = CFG.media !== false;
  const initWorkers = CFG.workers !== false;

  const W = window.wrappedJSObject || window;
  const useXray = typeof exportFunction === "function" && W !== window;

  // Build native page-realm engine source (identical logic to Blink WebsiteSpeedEngine)
  const pageEngineCode = `(${(function(r0, c0, m0, w0) {
    if (window.__fableTimeScale && window.__fableTimeScale.__installed) {
      window.__fableTimeScale.setConfig(r0, c0, m0, w0);
      return;
    }
    const clampR = function(v) {
      const n = Number(v);
      return Number.isFinite(n) ? Math.max(0.1, Math.min(100.0, n)) : 1.0;
    };
    let currentRate = clampR(r0);
    let optCss = !!c0;
    let optMedia = !!m0;
    let optWorkers = !!w0;

    const OrigDate = window.Date;
    const origDateNow = OrigDate.now.bind(OrigDate);
    const bootEpoch = origDateNow();
    const origPerfNow = (window.performance && typeof performance.now === "function")
      ? performance.now.bind(performance)
      : function() { return origDateNow() - bootEpoch; };
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

    function virtPerfNow() { return virtPerfAnchor + (origPerfNow() - realPerfAnchor) * currentRate; }
    function virtDateNow() { return Math.floor(virtDateAnchor + (origDateNow() - realDateAnchor) * currentRate); }

    try {
      if (window.performance) {
        Object.defineProperty(window.performance, "now", {
          value: function now() { return virtPerfNow(); },
          configurable: true,
          writable: true
        });
      }
    } catch (_) {}

    try {
      window.Date = new Proxy(OrigDate, {
        construct(target, args, newTarget) {
          if (!args || args.length === 0) return Reflect.construct(target, [virtDateNow()], newTarget);
          return Reflect.construct(target, args, newTarget);
        },
        apply() { return new OrigDate(virtDateNow()).toString(); },
        get(target, prop) {
          if (prop === "now") return virtDateNow;
          return Reflect.get(target, prop, target);
        }
      });
    } catch (_) {
      try { OrigDate.now = virtDateNow; } catch (__) {}
    }

    let nextTimerId = 1;
    const timeouts = new Map();
    const intervals = new Map();

    function invokeCb(fn, args) {
      if (typeof fn === "function") fn.apply(window, args);
      else if (typeof fn === "string") { try { (0, eval)(fn); } catch (_) {} }
    }

    function scheduleTimeoutEntry(entry) {
      if (entry.nativeId !== null) { origClearTimeout(entry.nativeId); entry.nativeId = null; }
      const rem = entry.virtFire - virtPerfNow();
      const realDelay = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / currentRate)));
      entry.nativeId = origSetTimeout(function() {
        if (!timeouts.has(entry.id)) return;
        const left = entry.virtFire - virtPerfNow();
        if (left > 1.5 && currentRate < 1.0) { scheduleTimeoutEntry(entry); return; }
        timeouts.delete(entry.id);
        invokeCb(entry.fn, entry.args);
      }, realDelay);
    }

    function scheduleIntervalEntry(entry) {
      if (entry.nativeId !== null) { origClearTimeout(entry.nativeId); entry.nativeId = null; }
      const rem = entry.virtNext - virtPerfNow();
      const realDelay = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / currentRate)));
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
          invokeCb(entry.fn, entry.args);
          if (origPerfNow() - t0 > 8.0) break;
        }
        if (intervals.has(entry.id)) {
          const nowV2 = virtPerfNow();
          if (entry.virtNext < nowV2 - entry.intervalVirt * 10) entry.virtNext = nowV2 + entry.intervalVirt;
          scheduleIntervalEntry(entry);
        }
      }, realDelay);
    }

    window.setTimeout = function(fn, delay) {
      const args = Array.prototype.slice.call(arguments, 2);
      const d = Math.max(0, Number(delay) || 0);
      const id = nextTimerId++;
      const entry = { id, fn, args, virtFire: virtPerfNow() + d, nativeId: null };
      timeouts.set(id, entry);
      scheduleTimeoutEntry(entry);
      return id;
    };
    window.clearTimeout = function(id) {
      const e = timeouts.get(id);
      if (e) { if (e.nativeId !== null) origClearTimeout(e.nativeId); timeouts.delete(id); }
      else origClearTimeout(id);
    };
    window.setInterval = function(fn, delay) {
      const args = Array.prototype.slice.call(arguments, 2);
      const d = Math.max(4, Number(delay) || 4);
      const id = nextTimerId++;
      const entry = { id, fn, args, intervalVirt: d, virtNext: virtPerfNow() + d, nativeId: null };
      intervals.set(id, entry);
      scheduleIntervalEntry(entry);
      return id;
    };
    window.clearInterval = function(id) {
      const e = intervals.get(id);
      if (e) { if (e.nativeId !== null) origClearTimeout(e.nativeId); intervals.delete(id); }
      else origClearInterval(id);
    };

    if (origRAF && origCAF) {
      let nextRafId = 1;
      const rafMap = new Map();
      window.requestAnimationFrame = function(cb) {
        if (typeof cb !== "function") return 0;
        const id = nextRafId++;
        const nid = origRAF(function() {
          if (!rafMap.has(id)) return;
          rafMap.delete(id);
          cb(virtPerfNow());
        });
        rafMap.set(id, nid);
        return id;
      };
      window.cancelAnimationFrame = function(id) {
        const nid = rafMap.get(id);
        if (nid !== undefined) { rafMap.delete(id); origCAF(nid); }
        else origCAF(id);
      };
    }

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
        const origAnim = Element.prototype.animate;
        Element.prototype.animate = function() {
          const anim = origAnim.apply(this, arguments);
          syncAnimation(anim);
          return anim;
        };
      }
    } catch (_) {}

    function syncMediaEl(el) {
      if (!el) return;
      try {
        if (typeof el.__fableBaseMediaRate !== "number") {
          el.__fableBaseMediaRate = (typeof el.playbackRate === "number" && el.playbackRate > 0) ? el.playbackRate : 1.0;
        }
        const target = Math.max(0.0625, Math.min(16.0, optMedia ? (el.__fableBaseMediaRate * currentRate) : el.__fableBaseMediaRate));
        if (Math.abs(el.playbackRate - target) > 0.001) el.playbackRate = target;
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
      const ah = function() { syncAllAnimations(); };
      const mh = function(ev) { if (ev && ev.target) syncMediaEl(ev.target); };
      if (document && document.addEventListener) {
        document.addEventListener("animationstart", ah, true);
        document.addEventListener("transitionrun", ah, true);
        document.addEventListener("transitionstart", ah, true);
        document.addEventListener("play", mh, true);
        document.addEventListener("loadedmetadata", mh, true);
        document.addEventListener("DOMContentLoaded", function() { syncAllAnimations(); syncAllMedia(); }, true);
      }
      window.addEventListener("load", function() { syncAllAnimations(); syncAllMedia(); }, true);
    } catch (_) {}

    function setConfig(newRate, css, media, workers) {
      const nowP = origPerfNow();
      const nowD = origDateNow();
      virtPerfAnchor = virtPerfAnchor + (nowP - realPerfAnchor) * currentRate;
      realPerfAnchor = nowP;
      virtDateAnchor = virtDateAnchor + (nowD - realDateAnchor) * currentRate;
      realDateAnchor = nowD;
      currentRate = clampR(newRate);
      if (typeof css === "boolean") optCss = css;
      if (typeof media === "boolean") optMedia = media;
      if (typeof workers === "boolean") optWorkers = workers;
      timeouts.forEach(function(e) { scheduleTimeoutEntry(e); });
      intervals.forEach(function(e) { scheduleIntervalEntry(e); });
      syncAllAnimations();
      syncAllMedia();
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
  }).toString()})(${initRate}, ${initCss}, ${initMedia}, ${initWorkers});`;

  // Tier 1: Synchronous page-realm <script> injection (works cleanly with zero Xray overhead)
  let injectedInPage = false;
  try {
    const parent = document.documentElement || document.head || document;
    if (parent && typeof document.createElement === "function") {
      const sc = document.createElement("script");
      sc.textContent = pageEngineCode;
      parent.appendChild(sc);
      sc.remove();
      injectedInPage = !!(W.__fableTimeScale && W.__fableTimeScale.__installed);
    }
  } catch (_) {}

  // Tier 2: Strict-CSP fallback (clean single-level exportFunction, no nested cloneInto!)
  if (!injectedInPage) {
    let curRate = initRate;
    let optCss = initCss;
    let optMedia = initMedia;
    const origPerfNow = (window.performance && typeof window.performance.now === "function")
      ? window.performance.now.bind(window.performance) : () => Date.now();
    const origDateNow = Date.now.bind(Date);
    const origST = window.setTimeout.bind(window);
    const origCT = window.clearTimeout.bind(window);
    let rPA = origPerfNow(), vPA = rPA, rDA = origDateNow(), vDA = rDA;
    const vp = () => vPA + (origPerfNow() - rPA) * curRate;
    const vd = () => Math.floor(vDA + (origDateNow() - rDA) * curRate);
    const exp = (fn) => useXray ? exportFunction(fn, window) : fn;
    const tMap = new Map();
    let tid = 1;
    const schedT = (e) => {
      if (e.nid !== null) { origCT(e.nid); e.nid = null; }
      const rem = e.vf - vp();
      const rd = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / curRate)));
      e.nid = origST(() => {
        if (!tMap.has(e.id)) return;
        tMap.delete(e.id);
        try { if (typeof e.fn === "function") e.fn(); } catch (_) {}
      }, rd);
    };
    const syncAnims = () => {
      try {
        if (document && document.getAnimations) {
          for (const a of document.getAnimations()) {
            const t = optCss ? curRate : 1.0;
            if (a.playbackRate !== t) a.playbackRate = t;
          }
        }
      } catch (_) {}
    };
    try { W.performance.now = exp(() => vp()); } catch (_) {}
    try { W.Date.now = exp(() => vd()); } catch (_) {}
    try {
      W.setTimeout = exp(function(fn, delay) {
        const id = tid++;
        const e = { id, fn, vf: vp() + Math.max(0, Number(delay) || 0), nid: null };
        tMap.set(id, e);
        schedT(e);
        return id;
      });
      W.clearTimeout = exp(function(id) {
        const e = tMap.get(id);
        if (e) { if (e.nid !== null) origCT(e.nid); tMap.delete(id); } else origCT(id);
      });
    } catch (_) {}
    const setCfg = (nr, c, m) => {
      const np = origPerfNow(), nd = origDateNow();
      vPA += (np - rPA) * curRate; rPA = np;
      vDA += (nd - rDA) * curRate; rDA = nd;
      curRate = clamp(nr);
      if (typeof c === "boolean") optCss = c;
      if (typeof m === "boolean") optMedia = m;
      tMap.forEach(schedT);
      syncAnims();
    };
    try {
      const api = { __installed: true, getRate: () => curRate, setRate: (r) => setCfg(r, optCss, optMedia), setConfig: setCfg, syncNow: syncAnims };
      W.__fableTimeScale = useXray ? cloneInto(api, window, { cloneFunctions: true }) : api;
    } catch (_) {}
    try {
      document.addEventListener("animationstart", syncAnims, true);
      document.addEventListener("DOMContentLoaded", syncAnims, true);
    } catch (_) {}
  }

  // Live update bridge from background blocker.js
  try {
    if (typeof browser !== "undefined" && browser.runtime && browser.runtime.onMessage) {
      browser.runtime.onMessage.addListener((msg) => {
        if (msg && msg.type === "fable-time-scale-update") {
          try {
            if (W.__fableTimeScale && typeof W.__fableTimeScale.setConfig === "function") {
              W.__fableTimeScale.setConfig(msg.enabled ? msg.rate : 1.0, msg.css, msg.media, msg.workers);
            }
          } catch (_) {}
        }
      });
    }
  } catch (_) {}
})();
