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

  try {
    browser.runtime
      .sendMessage({ type: "fable-cosmetic", host, top: window === window.top })
      .then((res) => {
        if (res && typeof res.css === "string") attach(res.css);
      })
      .catch(() => {});
  } catch (e) {
  }
})();
