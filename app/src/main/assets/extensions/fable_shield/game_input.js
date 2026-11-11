(() => {
  "use strict";
  if (globalThis.__fableGameInput) return;
  globalThis.__fableGameInput = true;

  let enabled = true;
  const last = new Map();
  const before = new WeakMap();
  const moves = new WeakMap();
  const byKey = new Map();
  let activeTouches = 0;
  let lastTouchAt = 0;
  let lastRealMouseAt = 0;
  let mouseLast = null;

  function now() {
    return Date.now();
  }

  function keyOf(e) {
    return e.type + "|" + (e.pointerId === undefined ? "m" : e.pointerId) + "|" + e.timeStamp;
  }

  function limit() {
    let w = 400;
    let h = 800;
    try {
      w = screen.width || w;
      h = screen.height || h;
    } catch (_) {
    }
    return Math.max(90, Math.min(w, h) * 0.22);
  }

  const speed = new Map();

  function clampMove(dx, dy, id) {
    if (!isFinite(dx) || !isFinite(dy)) return [0, 0];
    const max = limit();
    if (Math.abs(dx) > max || Math.abs(dy) > max) return [0, 0];
    const mag = Math.abs(dx) + Math.abs(dy);
    if (id !== undefined) {
      const avg = speed.get(id);
      if (avg !== undefined && mag > 64 && mag > avg * 8 + 40) {
        speed.set(id, avg * 0.8 + 24 * 0.2);
        return [0, 0];
      }
      speed.set(id, avg === undefined ? Math.min(mag, 24) : avg * 0.8 + mag * 0.2);
    }
    return [dx, dy];
  }

  const lastDelta = new Map();

  function remember(e, m, x, y) {
    moves.set(e, m);
    try {
      byKey.set(keyOf(e), [m[0], m[1], x, y]);
      if (byKey.size > 400) {
        let n = byKey.size - 300;
        for (const k of byKey.keys()) {
          byKey.delete(k);
          if (--n <= 0) break;
        }
      }
    } catch (_) {
    }
  }

  function lookup(ev) {
    const m = moves.get(ev);
    if (m) return m;
    try {
      const entry = byKey.get(keyOf(ev));
      if (entry && entry[2] === ev.screenX && entry[3] === ev.screenY) return entry;
    } catch (_) {
    }
    return null;
  }

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
      if (e.type === "pointermove") {
        const p = last.get(id);
        before.set(e, p || [x, y]);
        const m = p ? clampMove(x - p[0], y - p[1], id) : [0, 0];
        remember(e, m, x, y);
        last.set(id, [x, y]);
        lastDelta.set(id, [m[0], m[1], x, y, false]);
        lastTouchAt = now();
      } else if (e.type === "pointerdown") {
        last.set(id, [x, y]);
        speed.delete(id);
        lastDelta.delete(id);
        remember(e, [0, 0], x, y);
        activeTouches = last.size;
        lastTouchAt = now();
      } else {
        last.delete(id);
        speed.delete(id);
        lastDelta.delete(id);
        remember(e, [0, 0], x, y);
        activeTouches = last.size;
        lastTouchAt = now();
      }
    } catch (_) {
    }
  }

  function touchDerived() {
    if (now() - lastRealMouseAt < 400) return false;
    return activeTouches > 0 || now() - lastTouchAt < 1200;
  }

  function trackMouse(e) {
    try {
      if (!touchDerived()) {
        mouseLast = null;
        return;
      }
      const x = e.screenX;
      const y = e.screenY;
      if (e.type === "mousemove") {
        let match = null;
        for (const d of lastDelta.values()) {
          if (Math.abs(d[2] - x) <= 2 && Math.abs(d[3] - y) <= 2) {
            match = d;
            break;
          }
        }
        if (match) {
          remember(e, match[4] ? [0, 0] : [match[0], match[1]], x, y);
          match[4] = true;
        } else if (lastDelta.size === 0 && mouseLast) {
          remember(e, clampMove(x - mouseLast[0], y - mouseLast[1], "mouse"), x, y);
        } else {
          remember(e, [0, 0], x, y);
        }
        mouseLast = [x, y];
      } else if (e.type === "mousedown") {
        mouseLast = [x, y];
        remember(e, [0, 0], x, y);
      } else {
        mouseLast = null;
        remember(e, [0, 0], x, y);
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
    }
  } catch (_) {
  }

  const marked = new WeakSet();
  let gameRoot = false;

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
      gameRoot = true;
    } catch (_) {
    }
  }

  function markGameCanvases() {
    if (!enabled) return;
    try {
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
        if (activeTouches > 0 || now() - lastTouchAt < 800) {
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
        if (enabled && activeTouches > 1 && inGame(e.target)) e.preventDefault();
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
