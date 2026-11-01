(() => {
  if (globalThis.__fablePageTools) return;
  globalThis.__fablePageTools = true;

  const IS_PRIVATE = (() => {
    try {
      return browser.extension.inIncognitoContext === true;
    } catch (e) {
      return false;
    }
  })();

  let blockedCount = 0;

  let readerActive = false;
  let savedBodyHtml = null;

  function scoreNode(node) {
    const text = node.textContent || "";
    const links = node.querySelectorAll("a").length;
    const paragraphs = node.querySelectorAll("p").length;
    return text.length + paragraphs * 120 - links * 60;
  }

  function extractArticle() {
    const candidates = Array.from(
      document.querySelectorAll("article, main, [role=main], .post, .content, #content, body div")
    ).filter((n) => (n.textContent || "").length > 400);
    if (candidates.length === 0) return null;
    return candidates.reduce((best, n) => (scoreNode(n) > scoreNode(best) ? n : best));
  }

  function toggleReader() {
    if (readerActive) {
      if (savedBodyHtml !== null) document.body.innerHTML = savedBodyHtml;
      document.documentElement.removeAttribute("data-fable-reader");
      readerActive = false;
      return;
    }
    const article = extractArticle();
    if (!article) return;

    savedBodyHtml = document.body.innerHTML;
    const title = document.title || "";
    const html = article.innerHTML;

    document.body.innerHTML =
      '<div id="fable-reader">' +
      (title ? "<h1>" + title.replace(/[<>]/g, "") + "</h1>" : "") +
      html +
      "</div>";

    const style = document.createElement("style");
    style.textContent =
      "#fable-reader{max-width:42rem;margin:0 auto;padding:2rem 1.25rem 6rem;" +
      "font:1.125rem/1.7 -apple-system,Segoe UI,Roboto,sans-serif;}" +
      "#fable-reader h1{font-size:2rem;line-height:1.2;margin:0 0 1.5rem;}" +
      "#fable-reader img,#fable-reader video{max-width:100%;height:auto;border-radius:.5rem;}" +
      "#fable-reader pre{overflow-x:auto;padding:1rem;border-radius:.5rem;}" +
      "#fable-reader aside,#fable-reader nav,#fable-reader footer,#fable-reader iframe{display:none!important;}";
    document.body.appendChild(style);

    document.documentElement.setAttribute("data-fable-reader", "true");
    readerActive = true;
  }

  browser.runtime.onMessage.addListener((msg) => {
    if (!msg) return;
    if (msg.type === "reader-toggle") {
      toggleReader();
    } else if (msg.type === "fable-blocked") {
      blockedCount++;
    }
  });


  function iconCandidates() {
    const out = [];
    const links = document.querySelectorAll("link[rel][href]");
    for (const link of links) {
      const rel = (link.getAttribute("rel") || "").toLowerCase();
      if (!/(^|\s)(icon|apple-touch-icon|apple-touch-icon-precomposed|shortcut)(\s|$)/.test(rel)) continue;
      if (rel.includes("mask-icon")) continue;
      const href = link.href;
      if (!href) continue;
      const type = (link.getAttribute("type") || "").toLowerCase();
      if (type.includes("svg") || /\.svg(\?|#|$)/i.test(href)) continue;
      if (href.startsWith("data:") && href.length > 150000) continue;
      if (!/^(https?:|data:image\/)/i.test(href)) continue;
      let size = 0;
      const sizes = (link.getAttribute("sizes") || "").toLowerCase();
      const m = sizes.match(/(\d+)x(\d+)/);
      if (m) size = parseInt(m[1], 10);
      let score = size || 32;
      if (rel.includes("apple-touch-icon")) score = Math.max(score, 180) + 20;
      if (score > 260) score = 260 - (score - 260) / 10;
      out.push({ href, score });
    }
    out.sort((a, b) => b.score - a.score);
    return out.map((c) => c.href);
  }

  function reportIcon() {
    if (IS_PRIVATE) return;
    if (window.top !== window) return;
    if (!/^https?:$/.test(location.protocol)) return;
    const icons = iconCandidates();
    try {
      browser.runtime
        .sendMessage({
          type: "fable-icon",
          page: location.href,
          icons: icons.slice(0, 3),
        })
        .catch(() => {});
    } catch (e) {
    }
  }

  if (document.readyState === "complete") {
    setTimeout(reportIcon, 600);
  } else {
    window.addEventListener("load", () => setTimeout(reportIcon, 600), { once: true });
  }
})();
