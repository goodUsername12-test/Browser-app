(() => {
  "use strict";

  let enabled = true;
  const last = new Map();
  const moves = new WeakMap();

  function track(e) {
    try {
      if (e.pointerType !== "touch") return;
      const id = e.pointerId;
      const x = e.screenX;
      const y = e.screenY;
      if (e.type === "pointermove") {
        const p = last.get(id);
        moves.set(e, p ? [x - p[0], y - p[1]] : [0, 0]);
        last.set(id, [x, y]);
      } else if (e.type === "pointerdown") {
        last.set(id, [x, y]);
        moves.set(e, [0, 0]);
      } else {
        last.delete(id);
        moves.set(e, [0, 0]);
      }
    } catch (_) {
    }
  }

  const opts = { capture: true, passive: true };
  for (const t of ["pointerdown", "pointermove", "pointerup", "pointercancel"]) {
    try {
      window.addEventListener(t, track, opts);
    } catch (_) {}
  }

  try {
    const pageWin = window.wrappedJSObject;
    if (pageWin && typeof exportFunction === "function" && pageWin.PointerEvent && pageWin.MouseEvent) {
      const mouseProto = pageWin.MouseEvent.prototype;
      const ptrProto = pageWin.PointerEvent.prototype;
      const make = (prop, idx) => {
        const desc = Object.getOwnPropertyDescriptor(mouseProto, prop);
        if (!desc || typeof desc.get !== "function") return;
        const orig = desc.get;
        const getter = exportFunction(function () {
          if (enabled) {
            const m = moves.get(this);
            if (m) return m[idx];
          }
          return orig.call(this);
        }, window);
        Object.defineProperty(ptrProto, prop, {
          get: getter,
          configurable: true,
          enumerable: true,
        });
      };
      make("movementX", 0);
      make("movementY", 1);
    }
  } catch (_) {
  }

  const marked = new WeakSet();

  function markGameCanvases() {
    if (!enabled) return;
    try {
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      if (!vw || !vh) return;
      const list = document.getElementsByTagName("canvas");
      for (let i = 0; i < list.length; i++) {
        const c = list[i];
        if (marked.has(c)) continue;
        const r = c.getBoundingClientRect();
        if (r.width >= vw * 0.7 && r.height >= vh * 0.6) {
          c.style.setProperty("touch-action", "none", "important");
          marked.add(c);
        }
      }
    } catch (_) {}
  }

  let fsEl = null;
  function onFullscreen() {
    try {
      if (fsEl) {
        fsEl.style.removeProperty("touch-action");
        fsEl = null;
      }
      const el = document.fullscreenElement;
      if (el && enabled && el.tagName !== "VIDEO") {
        el.style.setProperty("touch-action", "none", "important");
        fsEl = el;
      }
      markGameCanvases();
    } catch (_) {}
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
  } catch (_) {}
})();
