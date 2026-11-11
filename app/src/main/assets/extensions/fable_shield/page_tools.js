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

  const retainedBlobs = new Map();
  let retainedBlobBytes = 0;
  const consoleLogs = [];
  const runtimeErrors = [];
  const MAX_RETAINED_BLOBS = 80;
  const MAX_RETAINED_BLOB_BYTES = 96 * 1024 * 1024;

  window.addEventListener("error", (ev) => {
    try {
      if (runtimeErrors.length < 300) {
        runtimeErrors.push({ time: Date.now(), message: String(ev.message || ""), file: String(ev.filename || ""), line: ev.lineno || 0, col: ev.colno || 0 });
      }
    } catch (e) {}
  }, true);

  function installDeepHooksIfEnabled() {
    try {
      const W = window.wrappedJSObject;
      if (!W || typeof exportFunction !== "function") return;
      if (window.URL && typeof window.URL.createObjectURL === "function" && W.URL) {
        const xCreate = window.URL.createObjectURL.bind(window.URL);
        W.URL.createObjectURL = exportFunction(function (obj) {
          let u = "";
          try {
            u = xCreate(obj);
            if (u && obj && typeof obj.size === "number" && typeof obj.arrayBuffer === "function") {
              if (obj.size > 0 && obj.size <= 32 * 1024 * 1024 && retainedBlobs.size < MAX_RETAINED_BLOBS && retainedBlobBytes + obj.size <= MAX_RETAINED_BLOB_BYTES) {
                retainedBlobs.set(String(u), { blob: obj, size: obj.size, type: String(obj.type || "") });
                retainedBlobBytes += obj.size;
              }
            }
          } catch (e) {}
          return u;
        }, window);
      }
      if (W.console) {
        for (const level of ["log", "info", "warn", "error"]) {
          const xFn = console[level] ? console[level].bind(console) : null;
          if (!xFn) continue;
          W.console[level] = exportFunction(function () {
            try {
              if (consoleLogs.length < 500) {
                const parts = [];
                for (let i = 0; i < arguments.length && i < 6; i++) parts.push(String(arguments[i]).slice(0, 400));
                consoleLogs.push({ time: Date.now(), level, text: parts.join(" ") });
              }
            } catch (e) {}
            try { return xFn.apply(console, arguments); } catch (e) { return undefined; }
          }, window);
        }
      }
    } catch (e) {}
  }

  try {
    browser.runtime.sendMessage({ type: "fable-get-config" }).then((cfg) => {
      if (cfg && cfg.archive && cfg.archive.readOnly === false && cfg.archive.deepHooks === true) {
        installDeepHooksIfEnabled();
      }
    }).catch(() => {});
  } catch (e) {}

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
    if (!msg) return undefined;
    if (msg.type === "reader-toggle") {
      toggleReader();
      return Promise.resolve({ ok: true });
    }
    if (msg.type === "fable-blocked") {
      blockedCount++;
      return undefined;
    }
    if (msg.type === "fable-save-page") {
      if (window.top !== window) return undefined;
      const want = stripHash(msg.url || "").replace(/\/+$/, "");
      const here = stripHash(location.href).replace(/\/+$/, "");
      if (!msg.force && want && here !== want) return undefined;
      savePageCode(msg);
      return Promise.resolve({ started: true, url: location.href });
    }
    return undefined;
  });

  const SAVE_MAX_FILES = 2500;
  const SAVE_MAX_TOTAL = 400 * 1024 * 1024;
  const SAVE_MAX_FILE = 80 * 1024 * 1024;
  const SAVE_CHUNK = 384 * 1024;
  const ASSET_EXT = "wasm|js|mjs|cjs|json|css|html|htm|png|jpe?g|webp|avif|gif|svg|ico|bmp|glb|gltf|bin|obj|mtl|fbx|stl|babylon|env|dds|ktx2?|basis|hdr|exr|mp3|ogg|oga|wav|m4a|aac|flac|mp4|webm|woff2?|ttf|otf|eot|data|mem|wat|map|txt|xml|csv|atlas|fnt|frag|vert|glsl|wgsl|zip|pck";
  const ASSET_LITERAL = new RegExp("[\"'`]((?:https?:)?[\\w./%~@:+-]{1,300}\\.(?:" + ASSET_EXT + "))(?:[?#][^\"'`\\s]{0,200})?[\"'`]", "gi");
  let saving = false;

  function stripHash(u) {
    const i = String(u).indexOf("#");
    return i < 0 ? String(u) : String(u).slice(0, i);
  }

  function absolute(u, base) {
    try {
      const url = new URL(u, base || location.href);
      if (!/^(https?:|data:|blob:)$/.test(url.protocol)) return null;
      return stripHash(url.href);
    } catch (e) {
      return null;
    }
  }

  function b64(bytes) {
    let out = "";
    const step = 0x8000;
    for (let i = 0; i < bytes.length; i += step) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(bytes.length, i + step)));
    }
    return btoa(out);
  }

  function safePart(s) {
    return String(s).replace(/[\\:*?"<>|\x00-\x1f]/g, "_").replace(/\.\.+/g, ".").slice(0, 120) || "_";
  }

  function pathFor(url, type, used) {
    let path;
    try {
      if (url.startsWith("data:")) {
        const ext = (/^data:[^/]+\/([a-z0-9.+-]+)/i.exec(url) || [0, "bin"])[1].replace("svg+xml", "svg").replace(/[^a-z0-9]/gi, "");
        path = "inline/data-" + (hashStr(url) >>> 0).toString(16) + "." + (ext || "bin");
      } else if (url.startsWith("blob:")) {
        path = "inline/blob-" + (hashStr(url) >>> 0).toString(16) + extFromType(type, ".bin");
      } else {
        const u = new URL(url);
        const segs = u.pathname.split("/").filter(Boolean).map((x) => {
          try {
            return safePart(decodeURIComponent(x));
          } catch (e) {
            return safePart(x);
          }
        });
        if (segs.length === 0 || u.pathname.endsWith("/")) segs.push("index" + extFromType(type, ".html"));
        let last = segs[segs.length - 1];
        if (u.search) {
          const dot = last.lastIndexOf(".");
          const tag = "_q" + (hashStr(u.search) >>> 0).toString(16);
          last = dot > 0 ? last.slice(0, dot) + tag + last.slice(dot) : last + tag;
        }
        if (last.indexOf(".") < 0) last += extFromType(type, "");
        segs[segs.length - 1] = last;
        path = "files/" + safePart(u.host) + "/" + segs.join("/");
      }
    } catch (e) {
      path = "files/other/" + (hashStr(url) >>> 0).toString(16);
    }
    let candidate = path;
    let n = 1;
    while (used.has(candidate)) {
      const dot = path.lastIndexOf(".");
      candidate = dot > path.lastIndexOf("/") ? path.slice(0, dot) + "_" + n + path.slice(dot) : path + "_" + n;
      n++;
    }
    used.add(candidate);
    return candidate;
  }

  function hashStr(s) {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h;
  }

  function extFromType(type, fallback) {
    const t = String(type || "").toLowerCase();
    if (t.includes("html")) return ".html";
    if (t.includes("css")) return ".css";
    if (t.includes("wasm")) return ".wasm";
    if (t.includes("javascript") || t.includes("ecmascript")) return ".js";
    if (t.includes("json")) return ".json";
    if (t.includes("svg")) return ".svg";
    if (t.includes("png")) return ".png";
    if (t.includes("jpeg")) return ".jpg";
    if (t.includes("webp")) return ".webp";
    if (t.includes("gif")) return ".gif";
    if (t.includes("woff2")) return ".woff2";
    if (t.includes("woff")) return ".woff";
    if (t.includes("font")) return ".font";
    if (t.includes("audio")) return ".audio";
    if (t.includes("video")) return ".video";
    if (t.includes("xml")) return ".xml";
    if (t.includes("text/plain")) return ".txt";
    return fallback;
  }

  function isTextual(type, url) {
    const t = String(type || "").toLowerCase();
    if (/css|javascript|ecmascript|html|json|xml|text\//.test(t)) return true;
    return /\.(css|m?js|html?|json|svg)(\?|$)/i.test(url);
  }

  function cssUrls(text, base, add) {
    const re = /url\(\s*(["']?)([^"')]+)\1\s*\)|@import\s+(["'])([^"']+)\3/gi;
    let m;
    while ((m = re.exec(text))) add(m[2] || m[4], base, "css");
  }

  function scriptUrls(text, base, add) {
    let m;
    let n = 0;
    ASSET_LITERAL.lastIndex = 0;
    while ((m = ASSET_LITERAL.exec(text)) && n < 400) {
      const raw = m[1];
      if (raw.length < 4 || /^[.\/]*$/.test(raw)) continue;
      add(raw, base, "guess");
      if (base !== location.href && !/^(https?:)?\/\//i.test(raw)) add(raw, location.href, "guess");
      n++;
    }
  }

  function domUrls(add) {
    const attrs = [
      ["script[src]", "src"],
      ["link[href]", "href"],
      ["img[src]", "src"],
      ["source[src]", "src"],
      ["video[src]", "src"],
      ["video[poster]", "poster"],
      ["audio[src]", "src"],
      ["track[src]", "src"],
      ["iframe[src]", "src"],
      ["embed[src]", "src"],
      ["object[data]", "data"],
      ["input[src]", "src"],
      ["image[href]", "href"],
      ["use[href]", "href"],
    ];
    for (const [sel, attr] of attrs) {
      for (const el of document.querySelectorAll(sel)) {
        const v = el.getAttribute(attr);
        if (v) add(v, location.href, "dom");
      }
    }
    for (const el of document.querySelectorAll("img[srcset], source[srcset]")) {
      for (const part of String(el.getAttribute("srcset") || "").split(",")) {
        const u = part.trim().split(/\s+/)[0];
        if (u) add(u, location.href, "dom");
      }
    }
    for (const el of document.querySelectorAll("[style*='url(']")) cssUrls(el.getAttribute("style") || "", location.href, add);
    for (const el of document.querySelectorAll("style")) cssUrls(el.textContent || "", location.href, add);
    for (const el of document.querySelectorAll("script:not([src])")) scriptUrls(el.textContent || "", location.href, add);
    for (const el of document.querySelectorAll("img, video, audio")) {
      try {
        if (el.currentSrc) add(el.currentSrc, location.href, "dom");
      } catch (e) {
      }
    }
    try {
      for (const sheet of document.styleSheets) {
        try {
          if (sheet.href) add(sheet.href, location.href, "dom");
          const rules = sheet.cssRules;
          const base = sheet.href || location.href;
          for (let i = 0; i < rules.length && i < 20000; i++) cssUrls(rules[i].cssText || "", base, add);
        } catch (e) {
        }
      }
    } catch (e) {
    }
  }

  function perfUrls(add) {
    try {
      for (const e of performance.getEntriesByType("resource")) add(e.name, location.href, "loaded:" + (e.initiatorType || ""));
    } catch (e) {
    }
    try {
      for (const e of performance.getEntriesByType("navigation")) add(e.name, location.href, "page");
    } catch (e) {
    }
  }

  async function savePageCode(msg) {
    if (window.top !== window) return;
    if (saving) return;
    saving = true;
    const id = String(msg.id || "");
    const mode = msg.mode === "memory" ? "memory" : "all";
    const cfg = Object.assign({
      readOnly: true,
      networkStream: true,
      includeMemory: true,
      includeCanvas: true,
      includeStorage: true,
      guessAssets: true,
      deepHooks: false,
      maxFiles: 4000,
      maxTotalMb: 600,
      maxFileMb: 120,
    }, msg.cfg || {});
    const maxFiles = Math.max(100, Number(cfg.maxFiles) || 4000);
    const maxTotalBytes = Math.max(50, Number(cfg.maxTotalMb) || 600) * 1024 * 1024;
    const maxFileBytes = Math.max(10, Number(cfg.maxFileMb) || 120) * 1024 * 1024;
    const send = (m) => browser.runtime.sendMessage(Object.assign({ type: "fable-save", id, mode }, m)).catch(() => {});
    try {
      await send({ op: "begin", page: location.href, title: document.title || "", mode });
      const used = new Set();
      const seen = new Map();
      const queue = [];
      const index = [];
      let total = 0;
      let sent = 0;

      const add = (raw, base, why) => {
        const abs = absolute(raw, base);
        if (!abs || seen.has(abs) || seen.size >= maxFiles) return;
        if (abs.startsWith("blob:") && why === "guess") return;
        seen.set(abs, why);
        queue.push(abs);
      };

      const sendBytes = async (path, bytes) => {
        if (!bytes.length) {
          await send({ op: "file", path, seq: 0, last: true, data: "" });
          return;
        }
        let seq = 0;
        for (let off = 0; off < bytes.length; off += SAVE_CHUNK) {
          const end = Math.min(bytes.length, off + SAVE_CHUNK);
          await send({ op: "file", path, seq: seq++, last: end >= bytes.length, data: b64(bytes.subarray(off, end)) });
          sent += end - off;
          if (sent > 4 * 1024 * 1024) {
            sent = 0;
            await new Promise((r) => setTimeout(r, 40));
          }
        }
      };
      const enc = new TextEncoder();

      const rendered = "<!DOCTYPE html>\n" + document.documentElement.outerHTML;
      await sendBytes("page/rendered-dom.html", enc.encode(rendered));
      index.push({ path: "page/rendered-dom.html", url: location.href, kind: "current DOM" });

      try {
        await new Promise((resolve) => {
          let doneRaf = false;
          const finish = () => { if (!doneRaf) { doneRaf = true; resolve(); } };
          setTimeout(finish, 120);
          try { requestAnimationFrame(finish); } catch (e) { finish(); }
        });
        const canvases = Array.from(document.querySelectorAll("canvas")).slice(0, 16);
        let ci = 0;
        for (const c of canvases) {
          ci++;
          try {
            if (!c.width || !c.height) continue;
            const dataUrl = c.toDataURL("image/png");
            const comma = dataUrl.indexOf(",");
            if (comma > 0) {
              const raw = atob(dataUrl.slice(comma + 1));
              const bytes = new Uint8Array(raw.length);
              for (let k = 0; k < raw.length; k++) bytes[k] = raw.charCodeAt(k);
              const cpath = "memory/canvas-" + ci + "-" + c.width + "x" + c.height + ".png";
              total += bytes.length;
              await sendBytes(cpath, bytes);
              index.push({ path: cpath, url: "memory://canvas/" + ci, kind: "memory:canvas", type: "image/png", size: bytes.length });
            }
          } catch (e) {
          }
        }
        const snap = {
          url: location.href,
          title: document.title || "",
          viewport: { width: window.innerWidth, height: window.innerHeight, dpr: window.devicePixelRatio },
          canvasCount: canvases.length,
          localStorageKeys: (() => { try { return Object.keys(localStorage).slice(0, 200); } catch (e) { return []; } })(),
          sessionStorageKeys: (() => { try { return Object.keys(sessionStorage).slice(0, 200); } catch (e) { return []; } })(),
        };
        const snapBytes = enc.encode(JSON.stringify(snap, null, 2));
        await sendBytes("memory/runtime-state.json", snapBytes);
        index.push({ path: "memory/runtime-state.json", url: "memory://runtime-state", kind: "memory:state", type: "application/json", size: snapBytes.length });

        let cssDump = "";
        try {
          let si = 0;
          for (const sheet of document.styleSheets) {
            si++;
            try {
              const rules = sheet.cssRules;
              if (!rules || !rules.length) continue;
              cssDump += "/* Stylesheet #" + si + " " + (sheet.href || "inline/cssom") + " */\n";
              for (let ri = 0; ri < rules.length && ri < 10000; ri++) {
                cssDump += (rules[ri].cssText || "") + "\n";
              }
            } catch (e) {
            }
          }
        } catch (e) {
        }
        if (cssDump) {
          const cssBytes = enc.encode(cssDump);
          total += cssBytes.length;
          await sendBytes("memory/computed-stylesheets.css", cssBytes);
          index.push({ path: "memory/computed-stylesheets.css", url: "memory://stylesheets", kind: "memory:cssom", type: "text/css", size: cssBytes.length });
        }

        let bi = 0;
        for (const [burl, info] of retainedBlobs.entries()) {
          bi++;
          if (total >= maxTotalBytes) break;
          try {
            const ab = await info.blob.arrayBuffer();
            const bbytes = new Uint8Array(ab);
            if (bbytes.length > 0 && bbytes.length <= maxFileBytes) {
              const bpath = "memory/blobs/blob-" + bi + extFromType(info.type, ".bin");
              total += bbytes.length;
              await sendBytes(bpath, bbytes);
              seen.set(burl, "memory:blob");
              index.push({ path: bpath, url: burl, kind: "memory:blob", type: info.type, size: bbytes.length });
            }
          } catch (e) {
          }
        }
      } catch (e) {
      }

      if (mode === "memory" || cfg.includeStorage !== false) {
        try {
          const dumpStorage = (st) => {
            const out = {};
            try {
              for (let i = 0; i < st.length && i < 1000; i++) {
                const k = st.key(i);
                if (k !== null) out[k] = String(st.getItem(k) || "").slice(0, 65536);
              }
            } catch (e) {}
            return out;
          };
          const lsBytes = enc.encode(JSON.stringify(dumpStorage(localStorage), null, 2));
          await sendBytes("memory/local-storage.json", lsBytes);
          index.push({ path: "memory/local-storage.json", url: "memory://local-storage", kind: "memory:storage", type: "application/json", size: lsBytes.length });

          const ssBytes = enc.encode(JSON.stringify(dumpStorage(sessionStorage), null, 2));
          await sendBytes("memory/session-storage.json", ssBytes);
          index.push({ path: "memory/session-storage.json", url: "memory://session-storage", kind: "memory:storage", type: "application/json", size: ssBytes.length });
        } catch (e) {}

        try {
          if (window.indexedDB && typeof window.indexedDB.databases === "function") {
            const dbs = await window.indexedDB.databases();
            for (const dbInfo of (dbs || []).slice(0, 12)) {
              if (!dbInfo || !dbInfo.name) continue;
              const dbDump = await new Promise((resolve) => {
                const t = setTimeout(() => resolve(null), 1500);
                try {
                  const req = window.indexedDB.open(dbInfo.name);
                  req.onerror = () => { clearTimeout(t); resolve(null); };
                  req.onsuccess = () => {
                    const db = req.result;
                    const names = Array.from(db.objectStoreNames || []).slice(0, 20);
                    if (!names.length) { db.close(); clearTimeout(t); resolve({ name: dbInfo.name, version: db.version, stores: {} }); return; }
                    const stores = {};
                    let pending = names.length;
                    const tx = db.transaction(names, "readonly");
                    for (const sname of names) {
                      try {
                        const getReq = tx.objectStore(sname).getAll(null, 250);
                        getReq.onsuccess = () => { stores[sname] = getReq.result || []; if (--pending <= 0) { db.close(); clearTimeout(t); resolve({ name: dbInfo.name, version: db.version, stores }); } };
                        getReq.onerror = () => { if (--pending <= 0) { db.close(); clearTimeout(t); resolve({ name: dbInfo.name, version: db.version, stores }); } };
                      } catch (e) {
                        if (--pending <= 0) { db.close(); clearTimeout(t); resolve({ name: dbInfo.name, version: db.version, stores }); }
                      }
                    }
                  };
                } catch (e) { clearTimeout(t); resolve(null); }
              });
              if (dbDump) {
                const idbBytes = enc.encode(JSON.stringify(dbDump, null, 2));
                const idbPath = "memory/indexeddb/" + safePart(dbInfo.name) + ".json";
                total += idbBytes.length;
                await sendBytes(idbPath, idbBytes);
                index.push({ path: idbPath, url: "indexeddb://" + dbInfo.name, kind: "memory:indexeddb", type: "application/json", size: idbBytes.length });
              }
            }
          }
        } catch (e) {}

        try {
          if (window.caches && typeof window.caches.keys === "function") {
            const ckeys = await window.caches.keys();
            let cCount = 0;
            for (const ck of (ckeys || []).slice(0, 8)) {
              const cache = await window.caches.open(ck);
              const reqs = await cache.keys();
              for (const rq of (reqs || []).slice(0, 80)) {
                if (total >= maxTotalBytes || ++cCount > 150) break;
                const resp = await cache.match(rq);
                if (!resp) continue;
                const ctype = resp.headers.get("content-type") || "";
                const cbuf = new Uint8Array(await resp.arrayBuffer());
                if (cbuf.length > 0 && cbuf.length <= maxFileBytes) {
                  const cpath = "memory/cache-storage/" + safePart(ck) + "/" + pathFor(rq.url, ctype, used);
                  total += cbuf.length;
                  await sendBytes(cpath, cbuf);
                  seen.set(stripHash(rq.url), "memory:cache-storage");
                  index.push({ path: cpath, url: rq.url, kind: "memory:cache-storage", type: ctype, size: cbuf.length });
                }
              }
            }
          }
        } catch (e) {}

        try {
          if (navigator.serviceWorker && typeof navigator.serviceWorker.getRegistrations === "function") {
            const regs = await navigator.serviceWorker.getRegistrations();
            const swList = (regs || []).map((r) => ({
              scope: r.scope,
              active: r.active ? r.active.scriptURL : null,
              waiting: r.waiting ? r.waiting.scriptURL : null,
            }));
            const swBytes = enc.encode(JSON.stringify(swList, null, 2));
            await sendBytes("memory/service-workers.json", swBytes);
            index.push({ path: "memory/service-workers.json", url: "memory://service-workers", kind: "memory:service-workers", type: "application/json", size: swBytes.length });
          }
        } catch (e) {}

        try {
          const perfDump = {
            navigation: performance.getEntriesByType("navigation"),
            resources: performance.getEntriesByType("resource").slice(0, 1500),
          };
          const perfBytes = enc.encode(JSON.stringify(perfDump, null, 2));
          await sendBytes("devtools/performance.json", perfBytes);
          index.push({ path: "devtools/performance.json", url: "devtools://performance", kind: "devtools:performance", type: "application/json", size: perfBytes.length });
        } catch (e) {}

        if (consoleLogs.length > 0) {
          const clBytes = enc.encode(consoleLogs.map((x) => JSON.stringify(x)).join("\n") + "\n");
          await sendBytes("devtools/console.jsonl", clBytes);
          index.push({ path: "devtools/console.jsonl", url: "devtools://console", kind: "devtools:console", type: "application/x-ndjson", size: clBytes.length });
        }
        if (runtimeErrors.length > 0) {
          const erBytes = enc.encode(JSON.stringify(runtimeErrors, null, 2));
          await sendBytes("devtools/errors.json", erBytes);
          index.push({ path: "devtools/errors.json", url: "devtools://errors", kind: "devtools:errors", type: "application/json", size: erBytes.length });
        }
      }

      if (mode !== "memory") {
        perfUrls(add);
        domUrls(add);
        add(location.href, location.href, "page");
      }

      let done = 0;
      while (queue.length) {
        const url = queue.shift();
        const why = seen.get(url) || "";
        done++;
        if (done % 10 === 0) await send({ op: "progress", done, total: done + queue.length });
        if (total >= maxTotalBytes) {
          index.push({ url, kind: why, skipped: "size limit" });
          continue;
        }
        let res;
        try {
          res = await fetch(url, { cache: "force-cache", credentials: "include", redirect: "follow" });
        } catch (e) {
          try {
            res = await fetch(url, { credentials: "omit" });
          } catch (x) {
            if (why !== "guess") index.push({ url, kind: why, error: String(x && x.message ? x.message : x) });
            continue;
          }
        }
        if (!res.ok && res.type !== "opaque") {
          if (why !== "guess") index.push({ url, kind: why, status: res.status });
          continue;
        }
        const type = res.headers.get("content-type") || "";
        if (why === "guess" && /text\/html/i.test(type) && !/\.html?(\?|$)/i.test(url)) continue;
        let buf;
        try {
          buf = new Uint8Array(await res.arrayBuffer());
        } catch (e) {
          index.push({ url, kind: why, error: "read failed" });
          continue;
        }
        if (buf.length > maxFileBytes) {
          index.push({ url, kind: why, skipped: "file too large", size: buf.length });
          continue;
        }
        const path = url === stripHash(location.href) ? "page/original.html" : pathFor(url, type, used);
        total += buf.length;
        await sendBytes(path, buf);
        index.push({ path, url, kind: why, type, size: buf.length });
        if (isTextual(type, url) && buf.length < 12 * 1024 * 1024) {
          let text = "";
          try {
            text = new TextDecoder().decode(buf);
          } catch (e) {
          }
          if (text) {
            if (/css/i.test(type) || /\.css(\?|$)/i.test(url)) cssUrls(text, url, add);
            else if (/html/i.test(type) && why === "dom") {
              const re = /(?:src|href)\s*=\s*["']([^"']+)["']/gi;
              let m;
              while ((m = re.exec(text))) add(m[1], url, "frame");
            } else if (/javascript|ecmascript|json/i.test(type) || /\.m?js(\?|$)/i.test(url)) scriptUrls(text, url, add);
          }
        }
      }

      try {
        const netRes = await browser.runtime.sendMessage({
          type: "fable-net-flush",
          id,
          includeBodies: mode !== "memory" && cfg.networkStream !== false,
          seenUrls: Array.from(seen.keys()),
        }).catch(() => null);
        if (netRes && Array.isArray(netRes.flushed)) {
          for (const f of netRes.flushed) {
            total += Number(f.size) || 0;
            index.push(f);
          }
        }
      } catch (e) {}

      const info = {
        page: location.href,
        title: document.title || "",
        mode,
        readOnlyMode: cfg.readOnly !== false,
        settings: cfg,
        savedAt: new Date().toISOString(),
        userAgent: navigator.userAgent,
        files: index,
      };
      await sendBytes("index.json", enc.encode(JSON.stringify(info, null, 2)));
      await send({ op: "end", count: index.filter((x) => x.path).length, bytes: total });
    } catch (e) {
      await send({ op: "error", message: String(e && e.message ? e.message : e) });
    } finally {
      saving = false;
    }
  }


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

  function reportPageDiag() {
    try {
      if (window.top !== window) return;
      const h = location.hostname.toLowerCase();
      if (h.includes("whatismybrowser.com")) {
        const bodyText = String((document.body && document.body.innerText) || "").replace(/\s+/g, " ").slice(0, 300);
        browser.runtime.sendMessage({ type: "fable-diag", text: "js-page " + location.href + " text=" + bodyText }).catch(() => {});
      } else if (h.includes("coveryourtracks.eff.org")) {
        setTimeout(() => {
          try {
            const statusEl = document.getElementById("fp_status") || document.body;
            const st = String((statusEl && statusEl.innerText) || "").replace(/\s+/g, " ").slice(0, 350);
            browser.runtime.sendMessage({ type: "fable-diag", text: "eff-page status=" + st }).catch(() => {});
          } catch (e) {}
        }, 6000);
      }
    } catch (e) {}
  }

  if (document.readyState === "complete") {
    setTimeout(reportIcon, 600);
    setTimeout(reportPageDiag, 1200);
  } else {
    window.addEventListener("load", () => {
      setTimeout(reportIcon, 600);
      setTimeout(reportPageDiag, 1200);
    }, { once: true });
  }
})();
