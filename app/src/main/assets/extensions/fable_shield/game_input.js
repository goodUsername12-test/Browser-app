(() => {
  "use strict";
  if (globalThis.__fableGameInput) return;
  globalThis.__fableGameInput = true;

  let enabled = true;
  const last = new Map();
  const before = new WeakMap();
  const moves = new WeakMap();
  const recentRing = new Float64Array(8 * 4);
  let ringIdx = 0;
  let activeTouches = 0;
  let lastTouchAt = 0;
  let lastRealMouseAt = 0;
  let mouseLast = null;

  function now() {
    return typeof performance !== "undefined" && performance.now ? performance.now() : Date.now();
  }

  function limit() {
    let w = 400;
    let h = 800;
    try {
      w = screen.width || w;
      h = screen.height || h;
    } catch (_) {
    }
    return Math.max(120, Math.min(w, h) * 0.35);
  }

  function isUiControlElement(el) {
    if (!el || typeof el.closest !== "function") return false;
    try {
      return !!el.closest(
        ".JoystickZone, .Joystick, .TouchMovementWrapper, .TouchComponentsButtons, [data-camera-keeper], button, input, select, textarea, a"
      );
    } catch (_) {
      return false;
    }
  }

  function isGameLookElement(el) {
    if (!el) return false;
    try {
      if (isUiControlElement(el)) return false;
      if (el.tagName === "CANVAS" || el.id === "noa-container") return true;
      if (typeof el.closest === "function" && el.closest("#noa-container")) return true;
      return marked.has(el);
    } catch (_) {
      return false;
    }
  }

  function clampMove(dx, dy) {
    if (!isFinite(dx) || !isFinite(dy)) return [0, 0];
    const max = limit();
    if (Math.abs(dx) > max || Math.abs(dy) > max) return [0, 0];
    return [dx, dy];
  }

  const lastDelta = new Map();
  const ZERO_MOVE = [0, 0];

  function remember(e, m, x, y) {
    moves.set(e, m);
    const base = (ringIdx & 7) << 2;
    recentRing[base] = x;
    recentRing[base + 1] = y;
    recentRing[base + 2] = m[0];
    recentRing[base + 3] = m[1];
    ringIdx = (ringIdx + 1) & 7;
  }

  function lookup(ev) {
    const m = moves.get(ev);
    if (m) return m;
    try {
      const sx = ev.screenX;
      const sy = ev.screenY;
      for (let i = 0; i < 8; i++) {
        const base = i << 2;
        if (recentRing[base] === sx && recentRing[base + 1] === sy) {
          return [recentRing[base + 2], recentRing[base + 3]];
        }
      }
    } catch (_) {
    }
    return null;
  }

  const uiPointerIds = new Set();

  function trackPointer(e) {
    try {
      if (e.pointerType === "mouse") {
        lastRealMouseAt = now();
        return;
      }
      if (e.pointerType !== "touch" && e.pointerType !== "pen") return;
      const id = e.pointerId;
      const x = e.screenX;
      const y = e.screenY;
      if (e.type === "pointerdown") {
        if (isUiControlElement(e.target)) {
          uiPointerIds.add(id);
          remember(e, ZERO_MOVE, x, y);
          lastTouchAt = now();
          return;
        }
        uiPointerIds.delete(id);
        last.set(id, [x, y]);
        lastDelta.delete(id);
        remember(e, ZERO_MOVE, x, y);
        activeTouches = last.size;
        lastTouchAt = now();
      } else if (e.type === "pointermove") {
        if (uiPointerIds.has(id)) {
          remember(e, ZERO_MOVE, x, y);
          lastTouchAt = now();
          return;
        }
        const p = last.get(id);
        before.set(e, p || [x, y]);
        const m = p ? clampMove(x - p[0], y - p[1]) : ZERO_MOVE;
        remember(e, m, x, y);
        last.set(id, [x, y]);
        lastDelta.set(id, [m[0], m[1], x, y, false]);
        lastTouchAt = now();
      } else {
        uiPointerIds.delete(id);
        last.delete(id);
        lastDelta.delete(id);
        remember(e, ZERO_MOVE, x, y);
        activeTouches = last.size;
        lastTouchAt = now();
      }
    } catch (_) {
    }
  }

  function touchDerived() {
    if (now() - lastRealMouseAt < 400) return false;
    return activeTouches > 0 || uiPointerIds.size > 0 || now() - lastTouchAt < 1200;
  }

  function trackMouse(e) {
    try {
      if (!touchDerived()) {
        mouseLast = null;
        return;
      }
      const x = e.screenX;
      const y = e.screenY;
      if (isUiControlElement(e.target)) {
        remember(e, ZERO_MOVE, x, y);
        return;
      }
      if (e.type === "mousemove") {
        let match = null;
        for (const d of lastDelta.values()) {
          if (Math.abs(d[2] - x) <= 2 && Math.abs(d[3] - y) <= 2) {
            match = d;
            break;
          }
        }
        if (match) {
          remember(e, match[4] ? ZERO_MOVE : [match[0], match[1]], x, y);
          match[4] = true;
        } else if (lastDelta.size === 0 && uiPointerIds.size === 0 && mouseLast) {
          remember(e, clampMove(x - mouseLast[0], y - mouseLast[1]), x, y);
        } else {
          remember(e, ZERO_MOVE, x, y);
        }
        mouseLast = [x, y];
      } else if (e.type === "mousedown") {
        mouseLast = [x, y];
        remember(e, ZERO_MOVE, x, y);
      } else {
        mouseLast = null;
        remember(e, ZERO_MOVE, x, y);
      }
    } catch (_) {
    }
  }

  const opts = { capture: true, passive: true };
  for (const t of ["pointerdown", "pointermove", "pointerup", "pointercancel"]) {
    try {
      window.addEventListener(t, trackPointer, opts);
    } catch (_) {
    }
  }
  for (const t of ["mousedown", "mousemove", "mouseup"]) {
    try {
      window.addEventListener(t, trackMouse, opts);
    } catch (_) {
    }
  }

  try {
    const pageWin = window.wrappedJSObject;
    if (pageWin && typeof exportFunction === "function" && pageWin.MouseEvent) {
      const mouseProto = pageWin.MouseEvent.prototype;
      const make = (prop, idx) => {
        const desc = Object.getOwnPropertyDescriptor(mouseProto, prop);
        if (!desc || typeof desc.get !== "function") return;
        const orig = desc.get;
        const xDesc = window.MouseEvent ? Object.getOwnPropertyDescriptor(window.MouseEvent.prototype, prop) : null;
        const xOrig = xDesc && typeof xDesc.get === "function" ? xDesc.get : null;
        const getter = exportFunction(function () {
          try {
            if (enabled) {
              const m = lookup(this);
              if (m) return m[idx];
            }
            if (xOrig) return xOrig.call(this);
            return orig.call((this && this.wrappedJSObject) || this);
          } catch (_) {
            return 0;
          }
        }, window);
        Object.defineProperty(mouseProto, prop, {
          get: getter,
          set: desc.set,
          configurable: true,
          enumerable: desc.enumerable,
        });
        if (pageWin.PointerEvent) {
          const own = Object.getOwnPropertyDescriptor(pageWin.PointerEvent.prototype, prop);
          if (own) {
            Object.defineProperty(pageWin.PointerEvent.prototype, prop, {
              get: getter,
              configurable: true,
              enumerable: own.enumerable,
            });
          }
        }
      };
      make("movementX", 0);
      make("movementY", 1);

      if (pageWin.PointerEvent) {
        const ptrProto = pageWin.PointerEvent.prototype;
        const cd = Object.getOwnPropertyDescriptor(ptrProto, "getCoalescedEvents");
        const xCd = window.PointerEvent ? Object.getOwnPropertyDescriptor(window.PointerEvent.prototype, "getCoalescedEvents") : null;
        const xCoal = xCd && typeof xCd.value === "function" ? xCd.value : null;
        if (cd && typeof cd.value === "function") {
          const origCoalesced = cd.value;
          Object.defineProperty(ptrProto, "getCoalescedEvents", {
            value: exportFunction(function () {
              let list;
              try {
                list = xCoal ? xCoal.call(this) : origCoalesced.call((this && this.wrappedJSObject) || this);
              } catch (_) {
                return new pageWin.Array();
              }
              try {
                if (enabled && list && list.length > 0) {
                  const start = before.get(this);
                  if (start) {
                    let px = start[0];
                    let py = start[1];
                    for (let i = 0; i < list.length; i++) {
                      const c = list[i];
                      const x = c.screenX;
                      const y = c.screenY;
                      moves.set(c, clampMove(x - px, y - py));
                      px = x;
                      py = y;
                    }
                  }
                }
              } catch (_) {
              }
              return list;
            }, window),
            writable: true,
            configurable: true,
            enumerable: cd.enumerable,
          });
        }
      }

      if (pageWin.TouchEvent && window.TouchEvent) {
        const tProto = pageWin.TouchEvent.prototype;
        const xTProto = window.TouchEvent.prototype;
        const xTouchesDesc = Object.getOwnPropertyDescriptor(xTProto, "touches");
        const xChangedDesc = Object.getOwnPropertyDescriptor(xTProto, "changedTouches");
        const xTargetDesc = Object.getOwnPropertyDescriptor(xTProto, "targetTouches");
        const xTouchesGet = xTouchesDesc && xTouchesDesc.get;
        const xChangedGet = xChangedDesc && xChangedDesc.get;
        const xTargetGet = xTargetDesc && xTargetDesc.get;

        if (xTouchesGet && xChangedGet && xTargetGet) {
          const filterTouchList = (rawList, fallbackList) => {
            const len = rawList ? rawList.length : 0;
            if (len <= 1) return null;
            let hasUi = false;
            const kept = [];
            for (let i = 0; i < len; i++) {
              const t = rawList[i];
              if (t && isUiControlElement(t.target)) {
                hasUi = true;
              } else if (t) {
                kept.push(t);
              }
            }
            if (!hasUi) return null;
            if (kept.length === 0 && fallbackList && fallbackList.length > 0) {
              for (let i = 0; i < fallbackList.length; i++) {
                const ft = fallbackList[i];
                if (ft && !isUiControlElement(ft.target)) kept.push(ft);
              }
            }
            if (kept.length === 0) return null;
            const out = new pageWin.Array();
            for (let i = 0; i < kept.length; i++) {
              out.push(kept[i].wrappedJSObject || kept[i]);
            }
            out.item = exportFunction(function (idx) {
              return out[idx] || null;
            }, window);
            return out;
          };

          Object.defineProperty(tProto, "touches", {
            get: exportFunction(function () {
              const raw = xTouchesGet.call(this);
              try {
                if (enabled && isGameLookElement(this.target)) {
                  const filtered = filterTouchList(raw, null);
                  if (filtered) return filtered;
                }
              } catch (_) {
              }
              return (this.wrappedJSObject ? raw : raw);
            }, window),
            configurable: true,
            enumerable: true,
          });

          Object.defineProperty(tProto, "changedTouches", {
            get: exportFunction(function () {
              const raw = xChangedGet.call(this);
              try {
                if (enabled && isGameLookElement(this.target)) {
                  const allTouches = xTouchesGet.call(this);
                  if (allTouches && allTouches.length > 1) {
                    let hasUi = false;
                    const kept = [];
                    for (let i = 0; i < raw.length; i++) {
                      const t = raw[i];
                      if (t && isUiControlElement(t.target)) {
                        hasUi = true;
                      } else if (t) {
                        kept.push(t);
                      }
                    }
                    if (!hasUi) {
                      for (let i = 0; i < allTouches.length; i++) {
                        if (allTouches[i] && isUiControlElement(allTouches[i].target)) {
                          hasUi = true;
                          break;
                        }
                      }
                    }
                    if (hasUi) {
                      if (kept.length === 0 && this.type === "touchmove") {
                        const tgt = xTargetGet.call(this);
                        for (let i = 0; i < tgt.length; i++) {
                          if (tgt[i] && !isUiControlElement(tgt[i].target)) kept.push(tgt[i]);
                        }
                      }
                      if (kept.length > 0) {
                        const out = new pageWin.Array();
                        for (let i = 0; i < kept.length; i++) {
                          out.push(kept[i].wrappedJSObject || kept[i]);
                        }
                        out.item = exportFunction(function (idx) {
                          return out[idx] || null;
                        }, window);
                        return out;
                      }
                    }
                  }
                }
              } catch (_) {
              }
              return raw;
            }, window),
            configurable: true,
            enumerable: true,
          });
        }
      }

      if (pageWin.HTMLCanvasElement && window.HTMLElement) {
        const cProto = pageWin.HTMLCanvasElement.prototype;
        const xElProto = window.HTMLElement.prototype;
        const xCw = Object.getOwnPropertyDescriptor(xElProto, "clientWidth");
        const xCh = Object.getOwnPropertyDescriptor(xElProto, "clientHeight");
        const xCwGet = xCw && xCw.get;
        const xChGet = xCh && xCh.get;
        if (xCwGet && xChGet) {
          Object.defineProperty(cProto, "clientWidth", {
            get: exportFunction(function () {
              try {
                if (enabled && marked.has(this)) {
                  const d = canvasDims.get(this);
                  if (d && d[0] > 0) return d[0];
                  const w = xCwGet.call(this);
                  const h = xChGet.call(this);
                  if (w > 0 && h > 0) canvasDims.set(this, [w, h]);
                  return w;
                }
                return xCwGet.call(this);
              } catch (_) {
                return 0;
              }
            }, window),
            configurable: true,
            enumerable: true,
          });
          Object.defineProperty(cProto, "clientHeight", {
            get: exportFunction(function () {
              try {
                if (enabled && marked.has(this)) {
                  const d = canvasDims.get(this);
                  if (d && d[1] > 0) return d[1];
                  const w = xCwGet.call(this);
                  const h = xChGet.call(this);
                  if (w > 0 && h > 0) canvasDims.set(this, [w, h]);
                  return h;
                }
                return xChGet.call(this);
              } catch (_) {
                return 0;
              }
            }, window),
            configurable: true,
            enumerable: true,
          });
        }
      }
        const xRectDesc = Object.getOwnPropertyDescriptor(window.Element.prototype, "getBoundingClientRect");
        const xRectFn = xRectDesc && xRectDesc.value;
        if (typeof xRectFn === "function") {
          Object.defineProperty(cProto, "getBoundingClientRect", {
            value: exportFunction(function () {
              try {
                if (enabled && marked.has(this)) {
                  const hit = canvasRects.get(this);
                  const t = now();
                  if (hit && t - hit.t < 250) return hit.r;
                  const r = xRectFn.call(this);
                  canvasRects.set(this, { r, t });
                  return r;
                }
              } catch (_) {
              }
              return xRectFn.call(this);
            }, window),
            configurable: true,
            writable: true,
          });
        }


    }
  } catch (_) {
  }

  const marked = new WeakSet();
  let canvasDims = new WeakMap(); canvasRects = new WeakMap();
  let canvasRects = new WeakMap();
  const lastCamTouch = new Map();
  let lastCamDispatchAt = 0;
  let gameRoot = false;

  window.addEventListener(
    "touchstart",
    (e) => {
      try {
        if (!enabled || !e.changedTouches) return;
        for (let i = 0; i < e.changedTouches.length; i++) {
          const t = e.changedTouches[i];
          if (t && !isUiControlElement(t.target)) {
            lastCamTouch.set(t.identifier, [t.clientX, t.clientY]);
          }
        }
      } catch (_) {
      }
    },
    opts
  );

  window.addEventListener(
    "touchmove",
    (e) => {
      try {
        if (!enabled || !isGameLookElement(e.target)) return;
        const ch = e.changedTouches;
        if (!ch || ch.length === 0) return;
        const tNow = now();
        let camMoved = false;
        for (let i = 0; i < ch.length; i++) {
          const t = ch[i];
          if (!t || isUiControlElement(t.target)) continue;
          const prev = lastCamTouch.get(t.identifier);
          const dx = prev ? Math.abs(t.clientX - prev[0]) : 99;
          const dy = prev ? Math.abs(t.clientY - prev[1]) : 99;
          if (dx < 0.22 && dy < 0.22) continue;
          // Blink VSync camera coalescing: max 1 camera update per ~7.2ms frame window unless delta is large
          if (prev && tNow - lastCamDispatchAt < 7.2 && dx < 6 && dy < 6) continue;
          lastCamTouch.set(t.identifier, [t.clientX, t.clientY]);
          lastCamDispatchAt = tNow;
          camMoved = true;
        }
        if (!camMoved) {
          e.stopImmediatePropagation();
        }
      } catch (_) {
      }
    },
    opts
  );

  // Zero-FPS-Drop Click Engine: suppress synthetic zero-delta mousemove hover raycasts during touch clicks
  window.addEventListener(
    "mousemove",
    (e) => {
      try {
        if (!enabled || !touchDerived() || !isGameLookElement(e.target)) return;
        const m = lookup(e);
        if (m && m[0] === 0 && m[1] === 0 && now() - lastTouchAt < 90) {
          e.stopImmediatePropagation();
        }
      } catch (_) {
      }
    },
    opts
  );

  window.addEventListener(
    "touchend",
    (e) => {
      try {
        if (!e.changedTouches) return;
        for (let i = 0; i < e.changedTouches.length; i++) {
          const t = e.changedTouches[i];
          if (t) lastCamTouch.delete(t.identifier);
        }
      } catch (_) {
      }
    },
    opts
  );
  let styleInjected = false;

  function injectGamePerfCss() {
    if (styleInjected) return;
    try {
      const parent = document.head || document.documentElement;
      if (!parent) return;
      const st = document.createElement("style");
      st.setAttribute("data-fable-game-perf", "1");
      st.textContent =
        "*,*::before,*::after{-webkit-tap-highlight-color:transparent!important}#noa-container,.WholeAppWrapper,.JoystickZone,.TouchMovementWrapper,.TouchComponentsButtons{contain:layout style!important;touch-action:none!important;-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}" +
        ".Joystick,.JoystickKnob{will-change:transform!important;backface-visibility:hidden!important}";
      parent.appendChild(st);
      styleInjected = true;
    } catch (_) {
    }
  }

  function lockRoot() {
    if (gameRoot) return;
    try {
      const de = document.documentElement;
      const body = document.body;
      const scrollable = de.scrollHeight > window.innerHeight + 8 || de.scrollWidth > window.innerWidth + 8;
      if (scrollable) return;
      for (const el of [de, body]) {
        if (!el) continue;
        el.style.setProperty("touch-action", "none", "important");
        el.style.setProperty("overscroll-behavior", "none", "important");
        el.style.setProperty("-webkit-user-select", "none", "important");
        el.style.setProperty("user-select", "none", "important");
        el.style.setProperty("-webkit-touch-callout", "none", "important");
      }
      injectGamePerfCss();
      gameRoot = true;
    } catch (_) {
    }
  }

  function markGameCanvases() {
    if (!enabled) return;
    try {
      canvasDims = new WeakMap(); canvasRects = new WeakMap();
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      if (!vw || !vh) return;
      const list = document.getElementsByTagName("canvas");
      for (let i = 0; i < list.length; i++) {
        const c = list[i];
        const r = c.getBoundingClientRect();
        const big = r.width >= vw * 0.7 && r.height >= vh * 0.6;
        if (!big) continue;
        if (!marked.has(c)) {
          c.style.setProperty("touch-action", "none", "important");
          c.style.setProperty("-webkit-user-select", "none", "important");
          c.style.setProperty("user-select", "none", "important");
          c.style.setProperty("-webkit-touch-callout", "none", "important");
          marked.add(c);
        }
        if (r.width >= vw * 0.9 && r.height >= vh * 0.85) lockRoot();
      }
    } catch (_) {
    }
  }

  function inGame(target) {
    try {
      if (document.fullscreenElement && document.fullscreenElement.tagName !== "VIDEO") return true;
      if (gameRoot) return true;
      return !!(target && marked.has(target));
    } catch (_) {
      return false;
    }
  }

  window.addEventListener(
    "contextmenu",
    (e) => {
      try {
        if (!enabled) return;
        if (activeTouches > 0 || uiPointerIds.size > 0 || now() - lastTouchAt < 800) {
          if (inGame(e.target)) e.preventDefault();
        }
      } catch (_) {
      }
    },
    true
  );

  window.addEventListener(
    "selectstart",
    (e) => {
      try {
        if (enabled && (activeTouches > 1 || uiPointerIds.size > 0) && inGame(e.target)) e.preventDefault();
      } catch (_) {
      }
    },
    true
  );

  let fsEl = null;
  function onFullscreen() {
    try {
      if (fsEl) {
        fsEl.style.removeProperty("touch-action");
        fsEl.style.removeProperty("user-select");
        fsEl.style.removeProperty("-webkit-user-select");
        fsEl = null;
      }
      const el = document.fullscreenElement;
      if (el && enabled && el.tagName !== "VIDEO") {
        el.style.setProperty("touch-action", "none", "important");
        el.style.setProperty("user-select", "none", "important");
        el.style.setProperty("-webkit-user-select", "none", "important");
        fsEl = el;
      }
      markGameCanvases();
    } catch (_) {
    }
  }

  document.addEventListener("fullscreenchange", onFullscreen, true);
  window.addEventListener("resize", markGameCanvases, opts);
  document.addEventListener("DOMContentLoaded", markGameCanvases, true);
  window.addEventListener(
    "load",
    () => {
      markGameCanvases();
      let n = 0;
      const timer = setInterval(() => {
        markGameCanvases();
        if (++n >= 15) clearInterval(timer);
      }, 2000);
    },
    true
  );

  try {
    browser.runtime
      .sendMessage({ type: "fable-get-config" })
      .then((cfg) => {
        if (cfg && cfg.gameTouchFix === false) enabled = false;
      })
      .catch(() => {});
  } catch (_) {
  }
})();
