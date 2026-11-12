(() => {
  if (globalThis.__fableCosmetic) return;
  globalThis.__fableCosmetic = true;
  let host = "";
  try {
    host = location.hostname;
  } catch (e) {
    return;
  }
  if (!host || !/^https?:$/.test(location.protocol)) return;

  let styleEl = null;
  function attach(css) {
    if (!css) return;
    const put = () => {
      const root = document.documentElement;
      if (!root) return false;
      if (!styleEl) {
        styleEl = document.createElement("style");
        styleEl.setAttribute("data-fable", "c");
        styleEl.textContent = css;
      }
      const parent = document.head || root;
      if (styleEl.parentNode !== parent) parent.appendChild(styleEl);
      return true;
    };
    if (!put()) {
      const obs = new MutationObserver(() => {
        if (put()) obs.disconnect();
      });
      obs.observe(document, { childList: true, subtree: true });
    }
    const keep = new MutationObserver(() => {
      if (styleEl && !styleEl.isConnected) put();
    });
    const watch = () => {
      if (document.head) keep.observe(document.head, { childList: true });
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", watch, { once: true });
    else watch();
  }

  function optimizeFonts() {
    try {
      const sheets = document.styleSheets;
      if (!sheets) return;
      for (let i = 0; i < sheets.length; i++) {
        let rules;
        try {
          rules = sheets[i].cssRules;
        } catch (_) {
          continue;
        }
        if (!rules) continue;
        for (let j = 0; j < rules.length; j++) {
          const r = rules[j];
          if (r && r.type === 5 && r.style && !r.style.fontDisplay) {
            try {
              r.style.fontDisplay = "swap";
            } catch (_) {
            }
          }
        }
      }
    } catch (_) {
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", optimizeFonts, { once: true, passive: true });
  } else {
    optimizeFonts();
  }

  if (window === window.top) {
    try {
      browser.runtime
        .sendMessage({ type: "fable-cosmetic", host, top: true })
        .then((res) => {
          if (res && typeof res.css === "string") attach(res.css);
        })
        .catch(() => {});
    } catch (e) {
    }
  }
})();
