(() => {
  const CFG = typeof __TS_CFG === "object" && __TS_CFG ? __TS_CFG : null;
  if (!CFG || CFG.enabled !== true) return;
  const CH = typeof CFG.ch === "string" && CFG.ch ? CFG.ch : "_ev_ts";
  if (globalThis.__tsContentReady === CH) return;
  globalThis.__tsContentReady = CH;

  const clamp = (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return 1.0;
    return Math.max(0.1, Math.min(300.0, n));
  };

  const initRate = clamp(CFG.rate);
  const initCss = CFG.css !== false;
  const initMedia = CFG.media !== false;
  const initWorkers = CFG.workers !== false;

  const W = window.wrappedJSObject || window;
  const useXray = typeof exportFunction === "function" && W !== window;

  const pageEngineCode = `(${(function(ch, r0, c0, m0, w0) {
    function dispatchCtrl(win, r, c, m, k) {
      var ack = { ok: false };
      try {
        win.dispatchEvent(new win.CustomEvent(ch, { detail: { r: r, c: c, m: m, k: k, a: ack } }));
      } catch (_) {}
      return ack.ok;
    }
    if (dispatchCtrl(window, r0, c0, m0, w0)) {
      try { window.dispatchEvent(new Event(ch + "_ok")); } catch (_) {}
      return;
    }
    function installIn(win) {
      if (!win) return;
      if (dispatchCtrl(win, r0, c0, m0, w0)) return;
      var clampR = function(v) {
        var n = Number(v);
        return Number.isFinite(n) ? Math.max(0.1, Math.min(300.0, n)) : 1.0;
      };
      var curRate = clampR(r0);
      var optCss = !!c0;
      var optMedia = !!m0;
      var optWorkers = !!w0;

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

      var origSTRaw = win.setTimeout;
      var origCTRaw = win.clearTimeout;
      var origSIRaw = win.setInterval;
      var origCIRaw = win.clearInterval;
      var origST = origSTRaw.bind(win);
      var origCT = origCTRaw.bind(win);
      var origSI = origSIRaw.bind(win);
      var origCI = origCIRaw.bind(win);
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

      function rawVirtPerf() { return virtPerfAnchor + (origPerfNow() - realPerfAnchor) * curRate; }
      function rawVirtDate() { return Math.floor(virtDateAnchor + (origDateNow() - realDateAnchor) * curRate); }
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

      var wrappedPerfNow = mask(function now() { return virtPerf(); }, origPerfNowRaw, "now");
      try {
        if (perfProto && typeof perfProto.now === "function") {
          win.Object.defineProperty(perfProto, "now", { value: wrappedPerfNow, configurable: true, writable: true });
        } else if (perfObj) {
          win.Object.defineProperty(perfObj, "now", { value: wrappedPerfNow, configurable: true, writable: true });
        }
      } catch (_) {
        try {
          if (perfObj) win.Object.defineProperty(perfObj, "now", { value: wrappedPerfNow, configurable: true, writable: true });
        } catch (__) {}
      }

      var wrappedDateNow = mask(function now() { return virtDate(); }, OrigDate.now, "now");
      try {
        var WrappedDate = new win.Proxy(OrigDate, {
          construct: function(target, args, newTarget) {
            if (!args || args.length === 0) return Reflect.construct(target, [virtDate()], newTarget);
            return Reflect.construct(target, args, newTarget);
          },
          apply: function() { return new OrigDate(virtDate()).toString(); },
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

      var nextTid = 1;
      var timeouts = new win.Map();
      var intervals = new win.Map();

      function invokeCb(fn, args) {
        if (typeof fn === "function") fn.apply(win, args);
        else if (typeof fn === "string") { try { (0, win.eval)(fn); } catch (_) {} }
      }

      function scheduleTimeout(entry) {
        if (entry.nid !== null) { origCT(entry.nid); entry.nid = null; }
        var rem = entry.virtFire - rawVirtPerf();
        var realDelay = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / curRate)));
        entry.nid = origST(function() {
          if (!timeouts.has(entry.id)) return;
          var left = entry.virtFire - rawVirtPerf();
          if (left > 1.5 && curRate < 1.0) { scheduleTimeout(entry); return; }
          timeouts.delete(entry.id);
          invokeCb(entry.fn, entry.args);
        }, realDelay);
      }

      function scheduleInterval(entry) {
        if (entry.nid !== null) { origCT(entry.nid); entry.nid = null; }
        var rem = entry.virtNext - rawVirtPerf();
        var realDelay = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / curRate)));
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
            if (entry.virtNext < nowV2 - entry.ivVirt * 16) entry.virtNext = nowV2 + entry.ivVirt;
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
        if (e) { if (e.nid !== null) origCT(e.nid); timeouts.delete(id); }
        else origCT(id);
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
        if (e) { if (e.nid !== null) origCT(e.nid); intervals.delete(id); }
        else origCI(id);
      }, origCIRaw, "clearInterval");

      if (origRIC && origCIC) {
        win.requestIdleCallback = mask(function requestIdleCallback(cb, opts) {
          if (opts && typeof opts.timeout === "number" && Number.isFinite(opts.timeout)) {
            return origRIC(cb, { timeout: Math.max(0, Math.round(opts.timeout / curRate)) });
          }
          return origRIC(cb, opts);
        }, origRICRaw, "requestIdleCallback");
        win.cancelIdleCallback = mask(function cancelIdleCallback(id) {
          return origCIC(id);
        }, origCICRaw, "cancelIdleCallback");
      }

      if (origRAF && origCAF) {
        var nextRafId = 1;
        var rafQueue = new win.Map();
        var cancelledInFrame = new win.Set();
        var pumpId = null;
        var slowCarry = 0;
        var cbUsesClockHint = true;

        function ensurePump() {
          if (pumpId === null && rafQueue.size > 0) pumpId = origRAF(onVsync);
        }

        function onVsync() {
          pumpId = null;
          if (rafQueue.size === 0) return;
          if (curRate < 1.0 && !cbUsesClockHint) {
            slowCarry += curRate;
            if (slowCarry < 1.0) { ensurePump(); return; }
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
                try { cb(ts); } catch (err) { origST(function() { throw err; }, 0); }
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
          if (rafQueue.size === 0 && pumpId !== null) { origCAF(pumpId); pumpId = null; }
        }, origCAFRaw, "cancelAnimationFrame");
      }

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
            try { anim.playbackRate = target; } catch (_) { anim.playbackRate = Math.max(0.05, Math.min(100.0, target)); }
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
          var origAnim = win.Element.prototype.animate;
          win.Element.prototype.animate = mask(function animate() {
            var a = origAnim.apply(this, arguments);
            syncAnim(a);
            return a;
          }, origAnim, "animate");
        }
        if (win.Animation && win.Animation.prototype && typeof win.Animation.prototype.play === "function") {
          var origPlay = win.Animation.prototype.play;
          win.Animation.prototype.play = mask(function play() {
            syncAnim(this);
            return origPlay.apply(this, arguments);
          }, origPlay, "play");
        }
      } catch (_) {}

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
          var target = Math.max(0.0625, Math.min(16.0, optMedia ? (base * curRate) : base));
          if (Math.abs(el.playbackRate - target) > 0.001) {
            syncingMedia.add(el);
            try { el.playbackRate = target; } finally { syncingMedia.delete(el); }
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
          node.playbackRate.value = Math.max(0.1, Math.min(16.0, optMedia ? (base * curRate) : base));
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
                var sn = this;
                this.addEventListener("ended", function() { activeAudioNodes.delete(sn); }, { once: true });
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
        var moAttached = false;
        function attachObserver() {
          if (moAttached) return;
          try {
            if (typeof win.MutationObserver === "function" && win.document.documentElement) {
              moAttached = true;
              var mo = new win.MutationObserver(function(muts) {
                for (var i = 0; i < muts.length; i++) {
                  if (muts[i].addedNodes && muts[i].addedNodes.length > 0) { queueDomSync(); break; }
                }
              });
              mo.observe(win.document.documentElement, { childList: true, subtree: true });
            }
          } catch (_) {}
        }
        if (win.document && win.document.addEventListener) {
          win.document.addEventListener("animationstart", ah, true);
          win.document.addEventListener("animationiteration", ah, true);
          win.document.addEventListener("transitionrun", ah, true);
          win.document.addEventListener("transitionstart", ah, true);
          win.document.addEventListener("play", mh, true);
          win.document.addEventListener("playing", mh, true);
          win.document.addEventListener("loadedmetadata", mh, true);
          win.document.addEventListener("ratechange", rch, true);
          win.document.addEventListener("DOMContentLoaded", function() {
            syncAllAnims();
            syncAllMedia();
            attachObserver();
          }, true);
          if (win.document.readyState && win.document.readyState !== "loading") attachObserver();
        }
        origAddEv("load", function() { syncAllAnims(); syncAllMedia(); }, true);
      } catch (_) {}

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
                "ost=setTimeout.bind(self),osi=setInterval.bind(self),ostR=setTimeout,osiR=setInterval;" +
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
        curRate = clampR(newRate);
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
        activeWorkers.forEach(function(w) { try { w.postMessage(wkMsg); } catch (_) {} });
        try {
          for (var i = 0; i < win.frames.length; i++) {
            try {
              var fw = win.frames[i];
              if (fw && fw !== win) dispatchCtrl(fw, curRate, optCss, optMedia, optWorkers);
            } catch (_) {}
          }
        } catch (_) {}
      }

      origAddEv(ch, function(ev) {
        var d = ev && ev.detail;
        if (!d || typeof d !== "object") return;
        if (d.a && typeof d.a === "object") d.a.ok = true;
        setConfig(d.r, d.c, d.m, d.k);
      }, false);

      syncAllAnims();
      syncAllMedia();
    }

    installIn(window);
    try { window.dispatchEvent(new Event(ch + "_ok")); } catch (_) {}
  }).toString()})(${JSON.stringify(CH)}, ${initRate}, ${initCss}, ${initMedia}, ${initWorkers});`;

  // Tier 1: Synchronous page-realm <script> injection (verified via synchronous neutral Event, zero window globals!)
  let injectedInPage = false;
  try {
    const onOk = () => { injectedInPage = true; };
    window.addEventListener(CH + "_ok", onOk, { once: true });
    const parent = document.documentElement || document.head || document;
    if (parent && typeof document.createElement === "function") {
      const sc = document.createElement("script");
      sc.textContent = pageEngineCode;
      parent.appendChild(sc);
      sc.remove();
    }
    window.removeEventListener(CH + "_ok", onOk);
  } catch (_) {}

  // Tier 2: Strict-CSP Xray fallback (zero window globals, supports 0.1x..300x, setTimeout/setInterval/rAF/CSS/media)
  let fallbackSetCfg = null;
  if (!injectedInPage) {
    let curRate = initRate;
    let optCss = initCss;
    let optMedia = initMedia;
    const origPerfNow = (window.performance && typeof window.performance.now === "function")
      ? window.performance.now.bind(window.performance) : () => Date.now();
    const origDateNow = Date.now.bind(Date);
    const origST = window.setTimeout.bind(window);
    const origCT = window.clearTimeout.bind(window);
    const origCI = window.clearInterval.bind(window);
    const origRAF = typeof window.requestAnimationFrame === "function" ? window.requestAnimationFrame.bind(window) : null;
    const origCAF = typeof window.cancelAnimationFrame === "function" ? window.cancelAnimationFrame.bind(window) : null;
    let rPA = origPerfNow(), vPA = rPA, rDA = origDateNow(), vDA = rDA;
    const vp = () => vPA + (origPerfNow() - rPA) * curRate;
    const vd = () => Math.floor(vDA + (origDateNow() - rDA) * curRate);
    const exp = (fn) => useXray ? exportFunction(fn, window) : fn;
    const tMap = new Map();
    const iMap = new Map();
    let tid = 1;
    const schedT = (e) => {
      if (e.nid !== null) { origCT(e.nid); e.nid = null; }
      const rem = e.vf - vp();
      const rd = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / curRate)));
      e.nid = origST(() => {
        if (!tMap.has(e.id)) return;
        const left = e.vf - vp();
        if (left > 1.5 && curRate < 1.0) { schedT(e); return; }
        tMap.delete(e.id);
        try { if (typeof e.fn === "function") e.fn(); } catch (_) {}
      }, rd);
    };
    const schedI = (e) => {
      if (e.nid !== null) { origCT(e.nid); e.nid = null; }
      const rem = e.vn - vp();
      const rd = rem <= 0 ? 0 : Math.min(2147483000, Math.max(0, Math.round(rem / curRate)));
      e.nid = origST(() => {
        if (!iMap.has(e.id)) return;
        const t0 = origPerfNow();
        let steps = 0;
        const maxSteps = curRate > 1.0 ? Math.min(64, Math.ceil(curRate)) : 1;
        while (iMap.has(e.id) && steps < maxSteps) {
          if (vp() + 0.5 < e.vn) break;
          e.vn += e.iv;
          steps++;
          try { if (typeof e.fn === "function") e.fn(); } catch (_) {}
          if (origPerfNow() - t0 > 9.0) break;
        }
        if (iMap.has(e.id)) schedI(e);
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
    const syncMed = () => {
      try {
        if (!document || !document.querySelectorAll) return;
        for (const el of document.querySelectorAll("video, audio")) {
          const t = Math.max(0.0625, Math.min(16.0, optMedia ? curRate : 1.0));
          if (Math.abs(el.playbackRate - t) > 0.001) el.playbackRate = t;
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
      W.setInterval = exp(function(fn, delay) {
        const id = tid++;
        const iv = Math.max(4, Number(delay) || 4);
        const e = { id, fn, iv, vn: vp() + iv, nid: null };
        iMap.set(id, e);
        schedI(e);
        return id;
      });
      W.clearInterval = exp(function(id) {
        const e = iMap.get(id);
        if (e) { if (e.nid !== null) origCT(e.nid); iMap.delete(id); } else origCI(id);
      });
      if (origRAF && origCAF) {
        const rMap = new Map();
        let rid = 1;
        W.requestAnimationFrame = exp(function(cb) {
          if (typeof cb !== "function") return 0;
          const id = rid++;
          const nid = origRAF(() => {
            if (!rMap.has(id)) return;
            rMap.delete(id);
            try { cb(vp()); } catch (_) {}
          });
          rMap.set(id, nid);
          return id;
        });
        W.cancelAnimationFrame = exp(function(id) {
          const nid = rMap.get(id);
          if (nid !== undefined) { rMap.delete(id); origCAF(nid); } else origCAF(id);
        });
      }
    } catch (_) {}
    fallbackSetCfg = (nr, c, m) => {
      const np = origPerfNow(), nd = origDateNow();
      vPA += (np - rPA) * curRate; rPA = np;
      vDA += (nd - rDA) * curRate; rDA = nd;
      curRate = clamp(nr);
      if (typeof c === "boolean") optCss = c;
      if (typeof m === "boolean") optMedia = m;
      tMap.forEach(schedT);
      iMap.forEach(schedI);
      syncAnims();
      syncMed();
    };
    try {
      document.addEventListener("animationstart", syncAnims, true);
      document.addEventListener("play", syncMed, true);
      document.addEventListener("DOMContentLoaded", () => { syncAnims(); syncMed(); }, true);
      syncAnims();
      syncMed();
    } catch (_) {}
  }

  // Live update bridge from background blocker.js (zero page globals!)
  try {
    if (typeof browser !== "undefined" && browser.runtime && browser.runtime.onMessage) {
      browser.runtime.onMessage.addListener((msg) => {
        if (msg && msg.type === "fable-time-scale-update") {
          const nextRate = msg.enabled ? clamp(msg.rate) : 1.0;
          const nextCss = msg.enabled ? (msg.css !== false) : false;
          const nextMedia = msg.enabled ? (msg.media !== false) : false;
          const nextWorkers = msg.enabled ? (msg.workers !== false) : false;
          if (injectedInPage) {
            try {
              const payload = { r: nextRate, c: nextCss, m: nextMedia, k: nextWorkers };
              const detail = useXray && typeof cloneInto === "function" ? cloneInto(payload, window) : payload;
              window.dispatchEvent(new window.CustomEvent(CH, { detail }));
            } catch (_) {}
          } else if (fallbackSetCfg) {
            try { fallbackSetCfg(nextRate, nextCss, nextMedia); } catch (_) {}
          }
        }
      });
    }
  } catch (_) {}
})();
