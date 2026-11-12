const NATIVE_APP = "fable";

let config = {
  proxyEnabled: false,
  proxyHost: "",
  proxyPort: 9050,
  proxyType: "socks5",
  adBlockEnabled: true,
  adBlockIncognito: true,
  gameTouchFix: true,
  fingerprintShield: true,
  normalTabShield: false,
  forceUtc: true,
  isolateIncognitoTabs: true,
  sessionSeed: 1,
  supers: [],
  incognitoUa: "",
  trimReferrer: true,
  stripClientHints: true,
  sendDnt: true,
  adBlockCosmetic: true,
  adBlockAllow: [],
  superProxyEnabled: false,
  superProxyType: "socks5",
  superProxyHost: "",
  superProxyPort: 1080,
  superProxyUser: "",
  superProxyPass: "",
  dataSaver: false,
  diag: false,
  archiveReadOnly: true,
  archiveNetworkStream: true,
  archiveIncludeMemory: true,
  archiveIncludeCanvas: true,
  archiveIncludeStorage: true,
  archiveGuessAssets: true,
  archiveDeepHooks: false,
  archiveMaxFiles: 4000,
  archiveMaxTotalMb: 600,
  archiveMaxFileMb: 120,
  archiveStreamBufferMb: 160,
};

let superByUa = new Map();
let registered = null;
let registerSeq = 0;
let lastContentConfig = "";

function postNative(message) {
  try {
    if (port) port.postMessage(message);
  } catch (e) {
  }
}

function contentConfig() {
  return {
    shield: config.fingerprintShield !== false,
    normal: config.normalTabShield === true,
    utc: config.forceUtc !== false,
    sessionSeed: Number(config.sessionSeed) || 1,
    supers: Array.isArray(config.supers) ? config.supers : [],
    incognitoUa: String(config.incognitoUa || ""),
    dnt: config.sendDnt !== false,
    diag: config.diag === true,
  };
}

function superIds() {
  return (Array.isArray(config.supers) ? config.supers : []).map((s) => s.id);
}

async function applyContentScripts(force) {
  const cfg = JSON.stringify(contentConfig());
  if (!force && cfg === lastContentConfig && registered) {
    postNative({ type: "applied", superIds: superIds(), ok: true });
    return;
  }
  lastContentConfig = cfg;
  const seq = ++registerSeq;
  let next = null;
  let ok = false;
  try {
    if (browser.contentScripts && browser.contentScripts.register) {
      next = await browser.contentScripts.register({
        matches: ["<all_urls>"],
        js: [{ code: "var FABLE_CFG = " + cfg + ";" }, { file: "fingerprint_shield.js" }],
        runAt: "document_start",
        allFrames: true,
        matchAboutBlank: true,
      });
      ok = true;
    }
  } catch (e) {
    postNative({ type: "diag", text: "content script register failed: " + e });
  }
  if (seq !== registerSeq) {
    if (next) next.unregister().catch(() => {});
    return;
  }
  const old = registered;
  registered = next;
  if (old) {
    try {
      await old.unregister();
    } catch (e) {
    }
  }
  postNative({ type: "applied", superIds: superIds(), ok });
}

function rebuildSupers() {
  const map = new Map();
  for (const s of Array.isArray(config.supers) ? config.supers : []) {
    if (s && s.ua) map.set(s.ua, s);
  }
  superByUa = map;
}

function superFor(details) {
  if (superByUa.size === 0) return null;
  const headers = details.requestHeaders;
  if (!headers) return null;
  for (const h of headers) {
    if (h.name && h.name.toLowerCase() === "user-agent") {
      if (config.incognitoUa && h.value === config.incognitoUa) return null;
      return superByUa.get(h.value) || null;
    }
  }
  return null;
}

function hides(sup, key) {
  return !!sup && (!sup.hide || sup.hide[key] !== false);
}

function superProxyOn() {
  return config.superProxyEnabled === true && !!config.superProxyHost;
}

function superProxyInfo() {
  const port = Number(config.superProxyPort) || 1080;
  const user = config.superProxyUser || "";
  const pass = config.superProxyPass || "";
  if (config.superProxyType === "http" || config.superProxyType === "https") {
    const p = { type: config.superProxyType, host: config.superProxyHost, port };
    if (user || pass) {
      try {
        p.proxyAuthorizationHeader = "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
      } catch (e) {
      }
    }
    return p;
  }
  const p = { type: "socks", host: config.superProxyHost, port, proxyDNS: true };
  if (user) p.username = user;
  if (pass) p.password = pass;
  return p;
}

let rules = {
  blockedDomains: new Set(),
  blockedSubstrings: indexSubstrings([]),
  exceptionDomains: new Set(),
  exceptionSubstrings: indexSubstrings([]),
};

function indexSubstrings(list) {
  const all = Array.isArray(list) ? list.filter((s) => typeof s === "string" && s.length > 0) : [];
  if (all.length < 64) return { all, map: null, short: all };
  const map = new Map();
  const short = [];
  for (const s of all) {
    if (s.length < 2) {
      short.push(s);
      continue;
    }
    const key = s.slice(0, 2);
    const bucket = map.get(key);
    if (bucket) bucket.push(s);
    else map.set(key, [s]);
  }
  return { all, map, short };
}

function containsAny(url, index) {
  for (const s of index.short) {
    if (url.includes(s)) return true;
  }
  const map = index.map;
  if (!map) return false;
  for (let i = 0; i < url.length - 1; i++) {
    const bucket = map.get(url.slice(i, i + 2));
    if (!bucket) continue;
    for (const s of bucket) {
      if (url.startsWith(s, i)) return true;
    }
  }
  return false;
}

let cosmetic = {
  generic: [],
  genericCss: "",
  domains: new Map(),
  exceptions: new Map(),
  genericHide: new Set(),
};
let allowSites = new Set();

const frag = document.createDocumentFragment();

function validSelector(sel) {
  try {
    frag.querySelector(sel);
    return true;
  } catch (e) {
    return false;
  }
}

function filterValidSelectors(list) {
  const out = [];
  const step = 100;
  for (let i = 0; i < list.length; i += step) {
    const chunk = list.slice(i, i + step).filter((s) => typeof s === "string" && s.length > 0);
    if (!chunk.length) continue;
    try {
      frag.querySelector(chunk.join(","));
      for (const s of chunk) out.push(s);
    } catch (e) {
      for (const s of chunk) {
        if (validSelector(s)) out.push(s);
      }
    }
  }
  return out;
}

function cssFor(selectors) {
  const out = [];
  for (let i = 0; i < selectors.length; i += 400) {
    out.push(selectors.slice(i, i + 400).join(",\n") + "{display:none!important}");
  }
  return out.join("\n");
}

function buildCosmetic(msg) {
  const generic = filterValidSelectors(Array.isArray(msg.cosmeticGeneric) ? msg.cosmeticGeneric : []);
  const domains = new Map();
  const rawDomains = msg.cosmeticDomains && typeof msg.cosmeticDomains === "object" ? msg.cosmeticDomains : {};
  for (const d of Object.keys(rawDomains)) {
    const list = Array.isArray(rawDomains[d]) ? rawDomains[d] : [];
    if (list.length) domains.set(d, list);
  }
  const exceptions = new Map();
  const rawExc = msg.cosmeticExceptions && typeof msg.cosmeticExceptions === "object" ? msg.cosmeticExceptions : {};
  for (const d of Object.keys(rawExc)) {
    if (Array.isArray(rawExc[d])) exceptions.set(d, new Set(rawExc[d]));
  }
  cosmetic = {
    generic,
    genericCss: cssFor(generic),
    domains,
    exceptions,
    genericHide: new Set(Array.isArray(msg.genericHideDomains) ? msg.genericHideDomains : []),
  };
}

function hostChain(host) {
  const out = [];
  let h = host;
  while (h && h.indexOf(".") !== -1) {
    out.push(h);
    h = h.substring(h.indexOf(".") + 1);
  }
  return out;
}

function adBlockOn(incognito) {
  return incognito ? config.adBlockIncognito !== false : config.adBlockEnabled !== false;
}

function siteAllowed(host) {
  return !!host && allowSites.size > 0 && matchesDomain(host, allowSites);
}

function cosmeticCss(host, incognito, top) {
  if (!host || config.adBlockCosmetic === false || !adBlockOn(incognito) || siteAllowed(host)) return "";
  const chain = hostChain(host);
  const excluded = new Set();
  const specific = [];
  for (const h of chain) {
    const exc = cosmetic.exceptions.get(h);
    if (exc) for (const x of exc) excluded.add(x);
  }
  for (const h of chain) {
    const list = cosmetic.domains.get(h);
    if (!list) continue;
    for (const sel of list) {
      if (!excluded.has(sel) && validSelector(sel)) specific.push(sel);
    }
  }
  let css = specific.length ? cssFor(specific) : "";
  if (top !== false && !matchesDomain(host, cosmetic.genericHide)) {
    if (excluded.size === 0) css = css ? cosmetic.genericCss + "\n" + css : cosmetic.genericCss;
    else {
      const generic = cssFor(cosmetic.generic.filter((sel) => !excluded.has(sel)));
      css = css ? generic + "\n" + css : generic;
    }
  }
  return css;
}

let port = null;

function connectNative() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    return;
  }
  port.onMessage.addListener(onNativeMessage);
  port.onDisconnect.addListener(() => {
    port = null;
    setTimeout(connectNative, 1000);
  });
  port.postMessage({ type: "hello" });
}

function onNativeMessage(msg) {
  if (!msg || typeof msg !== "object") return;
  if (msg.type === "config") {
    config = Object.assign(config, msg);
    allowSites = new Set((Array.isArray(config.adBlockAllow) ? config.adBlockAllow : []).map((h) => String(h).toLowerCase().replace(/^www\./, "")));
    rebuildSupers();
    registerListeners();
    applyContentScripts(false);
  } else if (msg.type === "rules") {
    rules = {
      blockedDomains: new Set(msg.blockedDomains || []),
      blockedSubstrings: indexSubstrings(msg.blockedSubstrings),
      exceptionDomains: new Set(msg.exceptionDomains || []),
      exceptionSubstrings: indexSubstrings(msg.exceptionSubstrings),
    };
    try {
      buildCosmetic(msg);
    } catch (e) {
      postNative({ type: "diag", text: "cosmetic build failed: " + e });
    }
  } else if (msg.type === "reader-toggle") {
    broadcastToActiveTab({ type: "reader-toggle" });
  } else if (msg.type === "save-page") {
    triggerSavePage(String(msg.id || ""), String(msg.url || ""), String(msg.mode || "all"));
  }
}

function normUrl(u) {
  const s = String(u || "");
  const i = s.indexOf("#");
  return (i < 0 ? s : s.slice(0, i)).replace(/\/+$/, "");
}

async function candidateTabs(wantUrl) {
  const seen = new Set();
  const out = [];
  const push = (t) => {
    if (t && t.id !== undefined && !seen.has(t.id)) {
      seen.add(t.id);
      out.push(t);
    }
  };
  let active = [];
  let all = [];
  try {
    active = await browser.tabs.query({ active: true });
  } catch (e) {
  }
  try {
    all = await browser.tabs.query({});
  } catch (e) {
  }
  const want = normUrl(wantUrl);
  if (want) {
    for (const t of active) if (normUrl(t.url) === want) push(t);
    for (const t of all) if (normUrl(t.url) === want) push(t);
    const wantHost = hostOf(wantUrl);
    if (wantHost) {
      for (const t of active) if (hostOf(t.url) === wantHost) push(t);
      for (const t of all) if (hostOf(t.url) === wantHost) push(t);
    }
  }
  for (const t of active) push(t);
  for (let i = all.length - 1; i >= 0; i--) push(all[i]);
  return out;
}

async function broadcastToActiveTab(message) {
  try {
    const tabs = await candidateTabs(message.url || "");
    for (const tab of tabs) {
      const ok = await browser.tabs.sendMessage(tab.id, message).then(() => true).catch(() => false);
      if (ok) return;
    }
  } catch (e) {
  }
}

const latin1Decoder = (() => {
  try {
    return new TextDecoder("latin1");
  } catch (e) {
    return null;
  }
})();

function b64Bytes(bytes) {
  if (!bytes || !bytes.length) return "";
  if (latin1Decoder && bytes.length <= 2 * 1024 * 1024) {
    try {
      return btoa(latin1Decoder.decode(bytes));
    } catch (e) {
    }
  }
  let out = "";
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    out += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(bytes.length, i + step)));
  }
  return btoa(out);
}

async function fallbackSaveFromBackground(id, url) {
  const send = (m) => postNative(Object.assign({ type: "save-page-data", id, incognito: false }, m));
  try {
    send({ op: "begin", page: url, title: hostOf(url) || "page" });
    const enc = new TextEncoder();
    const sendFile = (path, bytes) => {
      const chunk = 384 * 1024;
      if (!bytes.length) {
        send({ op: "file", path, seq: 0, last: true, data: "" });
        return;
      }
      let seq = 0;
      for (let off = 0; off < bytes.length; off += chunk) {
        const end = Math.min(bytes.length, off + chunk);
        send({ op: "file", path, seq: seq++, last: end >= bytes.length, data: b64Bytes(bytes.subarray(off, end)) });
      }
    };
    const index = [];
    const seen = new Set([url]);
    const queue = [{ u: url, kind: "page" }];
    let total = 0;
    while (queue.length && index.length < 300 && total < 120 * 1024 * 1024) {
      const item = queue.shift();
      let res;
      try {
        res = await fetch(item.u, { redirect: "follow" });
      } catch (e) {
        index.push({ url: item.u, kind: item.kind, error: String(e) });
        continue;
      }
      if (!res.ok) continue;
      const type = res.headers.get("content-type") || "";
      const buf = new Uint8Array(await res.arrayBuffer());
      total += buf.length;
      let path = item.kind === "page" ? "page/original.html" : "files/" + (new URL(item.u).hostname.replace(/[^a-z0-9.-]/gi, "_")) + "/" + (new URL(item.u).pathname.split("/").filter(Boolean).slice(-2).join("_") || "asset");
      sendFile(path, buf);
      index.push({ path, url: item.u, kind: item.kind, type, size: buf.length });
      if ((/html|css|javascript/i.test(type) || item.kind === "page") && buf.length < 4 * 1024 * 1024) {
        const text = new TextDecoder().decode(buf);
        const re = /(?:src|href|url\()\s*["']?([^"'\)\s>]+)["']?/gi;
        let m;
        while ((m = re.exec(text)) && seen.size < 300) {
          try {
            const abs = new URL(m[1], item.u).href.split("#")[0];
            if (/^https?:/i.test(abs) && !seen.has(abs)) {
              seen.add(abs);
              queue.push({ u: abs, kind: "linked" });
            }
          } catch (e) {
          }
        }
      }
    }
    sendFile("index.json", enc.encode(JSON.stringify({ page: url, savedAt: new Date().toISOString(), fallback: true, files: index }, null, 2)));
    send({ op: "end", count: index.filter((x) => x.path).length, bytes: total });
  } catch (e) {
    send({ op: "error", message: String(e) });
  }
}


function archiveCfg() {
  return {
    readOnly: config.archiveReadOnly !== false,
    networkStream: config.archiveNetworkStream !== false,
    includeMemory: config.archiveIncludeMemory !== false,
    includeCanvas: config.archiveIncludeCanvas !== false,
    includeStorage: config.archiveIncludeStorage !== false,
    guessAssets: config.archiveGuessAssets !== false,
    deepHooks: config.archiveReadOnly === false && config.archiveDeepHooks === true,
    maxFiles: Math.max(100, Number(config.archiveMaxFiles) || 4000),
    maxTotalMb: Math.max(50, Number(config.archiveMaxTotalMb) || 600),
    maxFileMb: Math.max(10, Number(config.archiveMaxFileMb) || 120),
    streamBufferMb: Math.max(16, Number(config.archiveStreamBufferMb) || 160),
  };
}

const tabNetStore = new Map();
const reqMeta = new Map();

function getTabStore(tabId) {
  let s = tabNetStore.get(tabId);
  if (!s) {
    s = { bytes: 0, entries: new Map() };
    tabNetStore.set(tabId, s);
  }
  return s;
}

function captureStreamIfEnabled(details) {
  try {
    if (config.archiveNetworkStream === false) return;
    const tabId = details.tabId;
    if (typeof tabId !== "number" || tabId < 0) return;
    const url = String(details.url || "").split("#")[0];
    if (!/^https?:/i.test(url)) return;
    const wr = browser.webRequest;
    if (!wr || typeof wr.filterResponseData !== "function") return;
    if (details.type === "main_frame") {
      tabNetStore.set(tabId, { bytes: 0, entries: new Map() });
    }
    const maxFileBytes = Math.min(64, Math.max(10, Number(config.archiveMaxFileMb) || 120)) * 1024 * 1024;
    const maxTabBytes = Math.max(16, Number(config.archiveStreamBufferMb) || 160) * 1024 * 1024;
    const filter = wr.filterResponseData(details.requestId);
    const chunks = [];
    let size = 0;
    let overflow = false;
    const startedAt = Date.now();
    reqMeta.set(details.requestId, { tabId, url, method: details.method || "GET", resType: details.type || "other", status: 200, mime: "", startedAt });
    filter.ondata = (event) => {
      try {
        filter.write(event.data);
      } catch (e) {
      }
      if (!overflow && event.data && event.data.byteLength) {
        if (size + event.data.byteLength <= maxFileBytes) {
          chunks.push(new Uint8Array(event.data.slice(0)));
          size += event.data.byteLength;
        } else {
          overflow = true;
          chunks.length = 0;
        }
      }
    };
    filter.onstop = () => {
      try {
        filter.close();
      } catch (e) {
      }
      const meta = reqMeta.get(details.requestId) || { tabId, url, method: "GET", resType: details.type || "other", status: 200, mime: "", startedAt };
      reqMeta.delete(details.requestId);
      if (overflow || size === 0) return;
      const store = getTabStore(tabId);
      const old = store.entries.get(url);
      if (old) store.bytes -= old.size;
      while (store.bytes + size > maxTabBytes && store.entries.size > 0) {
        const oldestKey = store.entries.keys().next().value;
        const oldest = store.entries.get(oldestKey);
        if (oldest) store.bytes -= oldest.size;
        store.entries.delete(oldestKey);
      }
      if (store.bytes + size > maxTabBytes) return;
      const merged = new Uint8Array(size);
      let off = 0;
      for (const c of chunks) {
        merged.set(c, off);
        off += c.byteLength;
      }
      store.entries.set(url, {
        url,
        method: meta.method,
        resType: meta.resType,
        status: meta.status || 200,
        statusLine: meta.statusLine || "",
        mime: meta.mime || "",
        reqHeaders: meta.reqHeaders || [],
        resHeaders: meta.resHeaders || [],
        size,
        timeMs: Date.now() - startedAt,
        startedIso: new Date(startedAt).toISOString(),
        bytes: merged,
      });
      store.bytes += size;
    };
    filter.onerror = () => {
      reqMeta.delete(details.requestId);
      try {
        filter.disconnect();
      } catch (e) {
      }
    };
  } catch (e) {
  }
}

function onHeadersReceived(details) {
  const m = reqMeta.get(details.requestId);
  if (!m) return;
  m.status = details.statusCode || 200;
  m.statusLine = String(details.statusLine || "");
  if (Array.isArray(details.responseHeaders)) {
    m.resHeaders = details.responseHeaders.slice(0, 80).map((h) => ({
      name: String((h && h.name) || ""),
      value: String((h && h.value) || ""),
    }));
    for (const h of details.responseHeaders) {
      if (h && h.name && h.name.toLowerCase() === "content-type") {
        m.mime = String(h.value || "");
        break;
      }
    }
  }
}

try {
  if (browser.tabs && browser.tabs.onRemoved) {
    browser.tabs.onRemoved.addListener((tabId) => {
      tabNetStore.delete(tabId);
    });
  }
} catch (e) {
}

async function triggerSavePage(id, url, mode) {
  const tabs = await candidateTabs(url);
  const msg = { type: "fable-save-page", id, url, mode: mode || "all", cfg: archiveCfg(), force: true };
  for (const tab of tabs) {
    try {
      const res = await browser.tabs.sendMessage(tab.id, msg);
      if (res && res.started) return;
    } catch (e) {
    }
    try {
      if (browser.tabs.executeScript) {
        await browser.tabs.executeScript(tab.id, { file: "page_tools.js" });
        const res2 = await browser.tabs.sendMessage(tab.id, msg);
        if (res2 && res2.started) return;
      }
    } catch (e) {
    }
  }
  if (url && /^https?:/i.test(url)) {
    await fallbackSaveFromBackground(id, url);
  }
}

function hostOf(url) {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^www\./, "");
  } catch (e) {
    return null;
  }
}

function matchesDomain(host, set) {
  if (!host || set.size === 0) return false;
  let h = host;
  while (h) {
    if (set.has(h)) return true;
    const dot = h.indexOf(".");
    h = dot === -1 ? null : h.substring(dot + 1);
  }
  return false;
}

function isSameSite(a, b) {
  if (!a || !b) return false;
  return a === b || a.endsWith("." + b) || b.endsWith("." + a);
}

function shouldBlock(url, documentUrl) {
  const host = hostOf(url);
  if (!host) return false;

  if (matchesDomain(host, rules.exceptionDomains)) return false;
  if (containsAny(url, rules.exceptionSubstrings)) return false;

  const docHost = documentUrl ? hostOf(documentUrl) : null;
  if (matchesDomain(host, rules.blockedDomains)) {
    if (isSameSite(host, docHost)) return false;
    return true;
  }
  return containsAny(url, rules.blockedSubstrings);
}

function onBeforeRequest(details) {
  if (!adBlockOn(details.incognito)) {
    captureStreamIfEnabled(details);
    return {};
  }
  if (details.type === "main_frame") {
    captureStreamIfEnabled(details);
    return {};
  }
  if (allowSites.size > 0) {
    const top = details.frameAncestors && details.frameAncestors.length
      ? details.frameAncestors[details.frameAncestors.length - 1].url
      : details.documentUrl || details.originUrl;
    if (siteAllowed(hostOf(top))) {
      captureStreamIfEnabled(details);
      return {};
    }
  }
  if (shouldBlock(details.url, details.documentUrl || details.originUrl)) {
    if (details.tabId >= 0) {
      browser.tabs
        .sendMessage(details.tabId, { type: "fable-blocked", url: details.url })
        .catch(() => {});
    }
    return { cancel: true };
  }
  captureStreamIfEnabled(details);
  return {};
}

const CLIENT_HINT_HEADERS = new Set([
  "sec-ch-ua-full-version",
  "sec-ch-ua-full-version-list",
  "sec-ch-ua-model",
  "sec-ch-ua-arch",
  "sec-ch-ua-bitness",
  "sec-ch-ua-platform-version",
  "x-forwarded-for",
]);

function onBeforeSendHeaders(details) {
  const sup = details.incognito ? superFor(details) : null;
  const saver = config.dataSaver === true;
  const shield = config.normalTabShield === true || details.incognito;
  if (!sup && !saver && !shield) return {};
  const headers = [];
  const docHost = hostOf(details.documentUrl || details.originUrl || details.url);
  const target = hostOf(details.url);
  let sawDnt = false;
  let sawSaver = false;

  for (const header of details.requestHeaders) {
    const name = header.name.toLowerCase();

    if (name === "save-data") sawSaver = true;

    if (!shield && !sup) {
      headers.push(header);
      continue;
    }

    if (CLIENT_HINT_HEADERS.has(name) && (sup ? hides(sup, "hints") : config.stripClientHints !== false)) continue;
    if (hides(sup, "browser") && name.startsWith("sec-ch-ua")) continue;

    if (hides(sup, "languages") && name === "accept-language") {
      headers.push({ name: header.name, value: sup.acceptLanguage || header.value });
      continue;
    }

    if (name === "referer" && (sup ? hides(sup, "referrer") : config.trimReferrer !== false) && !isSameSite(target, docHost)) {
      const origin = (() => {
        try {
          return new URL(header.value).origin + "/";
        } catch (e) {
          return null;
        }
      })();
      if (origin) headers.push({ name: header.name, value: origin });
      continue;
    }

    if (name === "dnt") {
      sawDnt = true;
      if (hides(sup, "dnt") && !sup.doNotTrack) continue;
    }
    headers.push(header);
  }

  if (sup) {
    if (hides(sup, "dnt") && sup.doNotTrack && !sawDnt) headers.push({ name: "DNT", value: "1" });
  } else if (shield && !sawDnt && config.sendDnt !== false) {
    headers.push({ name: "DNT", value: "1" });
  }
  if (saver && !sawSaver) headers.push({ name: "Save-Data", value: "on" });

  return { requestHeaders: headers };
}

function incognitoProxyInfo() {
  const type = config.proxyType === "http" ? "http" : "socks";
  const proxy = {
    type: type,
    host: config.proxyHost,
    port: Number(config.proxyPort) || 9050,
  };
  if (type === "socks") {
    proxy.proxyDNS = true;
  }
  return proxy;
}

function onProxyRequest(details) {
  if (details.incognito) {
    if (superProxyOn()) {
      if (!details.requestHeaders) return superProxyInfo();
      if (superFor(details)) return superProxyInfo();
    }
    if (config.proxyEnabled && config.proxyHost) return incognitoProxyInfo();
  }
  return { type: "direct" };
}

const authTried = new Map();

function onAuthRequired(details) {
  if (!details.isProxy || !superProxyOn()) return {};
  const user = config.superProxyUser || "";
  const pass = config.superProxyPass || "";
  if (!user && !pass) return {};
  const tries = authTried.get(details.requestId) || 0;
  if (tries >= 1) return { cancel: true };
  authTried.set(details.requestId, tries + 1);
  if (authTried.size > 500) authTried.clear();
  return { authCredentials: { username: user, password: pass } };
}

function addListenerSafe(event, listener, filter, extra) {
  try {
    if (extra) {
      event.addListener(listener, filter, extra);
    } else {
      event.addListener(listener, filter);
    }
  } catch (e) {
    const fallback = { urls: filter.urls };
    if (extra) {
      event.addListener(listener, fallback, extra);
    } else {
      event.addListener(listener, fallback);
    }
  }
}

function replaceListener(event, listener, filter, extra) {
  if (event.hasListener(listener)) event.removeListener(listener);
  addListenerSafe(event, listener, filter, extra);
}

function filterFor(allTabs) {
  return allTabs ? { urls: ["<all_urls>"] } : { urls: ["<all_urls>"], incognito: true };
}

let listenerState = "";
let proxyHeadersSupported = true;

function registerListeners() {
  const blockNormal = config.adBlockEnabled !== false;
  const blockPrivate = config.adBlockIncognito !== false;
  const blockAll = blockNormal;
  const headersAll = config.normalTabShield === true || config.dataSaver === true;
  const incognitoProxyOn = config.proxyEnabled === true && !!config.proxyHost;
  const proxyOn = incognitoProxyOn || superProxyOn();
  const state = [blockNormal, blockPrivate, headersAll, proxyOn, superProxyOn()].join(",");
  if (state === listenerState) return;
  listenerState = state;

  const wr = browser.webRequest;
  if (wr.onBeforeRequest.hasListener(onBeforeRequest)) wr.onBeforeRequest.removeListener(onBeforeRequest);
  addListenerSafe(wr.onBeforeRequest, onBeforeRequest, { urls: ["<all_urls>"] }, ["blocking"]);
  if (wr.onHeadersReceived) {
    if (wr.onHeadersReceived.hasListener(onHeadersReceived)) wr.onHeadersReceived.removeListener(onHeadersReceived);
    addListenerSafe(wr.onHeadersReceived, onHeadersReceived, { urls: ["<all_urls>"] }, ["responseHeaders"]);
  }
  replaceListener(
    wr.onBeforeSendHeaders,
    onBeforeSendHeaders,
    filterFor(headersAll),
    ["blocking", "requestHeaders"]
  );

  if (wr.onAuthRequired) {
    try {
      if (wr.onAuthRequired.hasListener(onAuthRequired)) wr.onAuthRequired.removeListener(onAuthRequired);
      if (superProxyOn()) wr.onAuthRequired.addListener(onAuthRequired, { urls: ["<all_urls>"] }, ["blocking"]);
    } catch (e) {
    }
  }

  if (browser.proxy && browser.proxy.onRequest) {
    const proxyEvent = browser.proxy.onRequest;
    if (proxyEvent.hasListener(onProxyRequest)) proxyEvent.removeListener(onProxyRequest);
    if (proxyOn) {
      const attempts = [
        [{ urls: ["<all_urls>"], incognito: true }, ["requestHeaders"]],
        [{ urls: ["<all_urls>"] }, ["requestHeaders"]],
        [{ urls: ["<all_urls>"] }, null],
      ];
      for (const [filter, extra] of attempts) {
        try {
          if (proxyEvent.hasListener(onProxyRequest)) proxyEvent.removeListener(onProxyRequest);
          if (extra) {
            proxyEvent.addListener(onProxyRequest, filter, extra);
          } else {
            proxyEvent.addListener(onProxyRequest, filter);
          }
          proxyHeadersSupported = !!extra;
          break;
        } catch (e) {
        }
      }
    }
  }
}

registerListeners();
applyContentScripts(true);

browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "fable-icon") {
    const tab = sender && sender.tab;
    if (tab && !tab.incognito && (sender.frameId === 0 || sender.frameId === undefined)) {
      postNative({ type: "icon", page: String(msg.page || ""), icons: Array.isArray(msg.icons) ? msg.icons.slice(0, 3) : [] });
    }
    return undefined;
  }
  if (msg && msg.type === "fable-net-flush") {
    const tab = sender && sender.tab;
    const tabId = tab ? tab.id : -1;
    const id = String(msg.id || "");
    const incognito = !!(tab && tab.incognito);
    const includeBodies = msg.includeBodies !== false;
    const already = new Set(Array.isArray(msg.seenUrls) ? msg.seenUrls : []);
    const store = tabNetStore.get(tabId);
    const flushed = [];
    if (store && store.entries.size > 0) {
      const sendNativeChunked = (path, bytes) => {
        const chunk = 512 * 1024;
        if (!bytes.length) {
          postNative({ type: "save-page-data", id, op: "file", path, seq: 0, last: true, data: "", incognito });
          return;
        }
        let seq = 0;
        for (let off = 0; off < bytes.length; off += chunk) {
          const end = Math.min(bytes.length, off + chunk);
          postNative({ type: "save-page-data", id, op: "file", path, seq: seq++, last: end >= bytes.length, data: b64Bytes(bytes.subarray(off, end)), incognito });
        }
      };
      const harEntries = [];
      let harBodyBytes = 0;
      const maxHarBodyBytes = 48 * 1024 * 1024;
      const utf8Dec = new TextDecoder("utf-8", { fatal: false });
      let idx = 0;
      for (const [u, entry] of store.entries.entries()) {
        idx++;
        const qList = [];
        try {
          const parsedQ = new URL(entry.url);
          parsedQ.searchParams.forEach((v, k) => qList.push({ name: k, value: v }));
        } catch (e) {}
        const contentObj = { size: entry.size, mimeType: entry.mime || "application/octet-stream" };
        if (entry.bytes && entry.bytes.length > 0 && entry.bytes.length <= 4 * 1024 * 1024 && harBodyBytes + entry.bytes.length <= maxHarBodyBytes) {
          harBodyBytes += entry.bytes.length;
          if (/^(text\/|application\/(javascript|ecmascript|json|xml|xhtml|manifest|ld\+json)|image\/svg)/i.test(entry.mime || "")) {
            contentObj.text = utf8Dec.decode(entry.bytes);
          } else {
            contentObj.text = b64Bytes(entry.bytes);
            contentObj.encoding = "base64";
          }
        }
        harEntries.push({
          startedDateTime: entry.startedIso,
          time: entry.timeMs,
          _resourceType: entry.resType,
          request: {
            method: entry.method,
            url: entry.url,
            httpVersion: "HTTP/2.0",
            headers: entry.reqHeaders || [],
            queryString: qList,
            cookies: [],
            headersSize: -1,
            bodySize: 0,
          },
          response: {
            status: entry.status,
            statusText: entry.statusLine || "OK",
            httpVersion: "HTTP/2.0",
            headers: entry.resHeaders || [],
            cookies: [],
            content: contentObj,
            redirectURL: "",
            headersSize: -1,
            bodySize: entry.size,
          },
          cache: {},
          timings: { blocked: 0, dns: -1, connect: -1, send: 0, wait: Math.max(0, Math.round(entry.timeMs * 0.4)), receive: Math.max(0, Math.round(entry.timeMs * 0.6)), ssl: -1 },
        });
        if (includeBodies && !already.has(u) && entry.bytes && entry.bytes.length > 0) {
          let p = "network-stream/item-" + idx;
          try {
            const parsed = new URL(u);
            const cleanHost = parsed.hostname.replace(/[^a-z0-9.-]/gi, "_");
            const cleanName = (parsed.pathname.split("/").filter(Boolean).slice(-2).join("_") || "index").replace(/[^a-z0-9._-]/gi, "_").slice(0, 100);
            p = "network-stream/" + cleanHost + "/" + idx + "_" + cleanName;
          } catch (e) {
          }
          sendNativeChunked(p, entry.bytes);
          flushed.push({ path: p, url: u, kind: "network-stream:" + entry.resType, type: entry.mime, size: entry.size });
        }
      }
      const harBytes = new TextEncoder().encode(JSON.stringify({ log: { version: "1.2", creator: { name: "Fable Browser", version: "1.18" }, entries: harEntries } }, null, 2));
      sendNativeChunked("devtools/network.har", harBytes);
      flushed.push({ path: "devtools/network.har", url: "devtools://network.har", kind: "devtools:har", type: "application/json", size: harBytes.length });
    }
    return Promise.resolve({ flushed });
  }
  if (msg && msg.type === "fable-save") {
    const tab = sender && sender.tab;
    if (sender.frameId === 0 || sender.frameId === undefined) {
      postNative({
        mode: String(msg.mode || "code"),
        type: "save-page-data",
        id: String(msg.id || ""),
        op: String(msg.op || ""),
        path: msg.path === undefined ? "" : String(msg.path),
        seq: Number(msg.seq) || 0,
        last: msg.last === true,
        data: typeof msg.data === "string" ? msg.data : "",
        page: String(msg.page || ""),
        title: String(msg.title || ""),
        done: Number(msg.done) || 0,
        total: Number(msg.total) || 0,
        count: Number(msg.count) || 0,
        bytes: Number(msg.bytes) || 0,
        message: String(msg.message || ""),
        incognito: !!(tab && tab.incognito),
      });
    }
    return Promise.resolve(true);
  }
  if (msg && msg.type === "fable-diag") {
    if (config.diag) postNative({ type: "diag", text: String(msg.text || "").slice(0, 600) });
    return undefined;
  }
  if (msg && msg.type === "fable-cosmetic") {
    const tab = sender && sender.tab;
    const host = String(msg.host || "").toLowerCase().replace(/^www\./, "");
    let top = null;
    try {
      top = tab && tab.url ? hostOf(tab.url) : null;
    } catch (e) {
    }
    if (top && siteAllowed(top)) return Promise.resolve({ css: "" });
    return Promise.resolve({ css: cosmeticCss(host, !!(tab && tab.incognito), msg.top !== false) });
  }
  if (msg && msg.type === "fable-get-config") {
    return Promise.resolve({
      fingerprintShield: config.fingerprintShield,
      normalTabShield: config.normalTabShield,
      forceUtc: config.forceUtc,
      gameTouchFix: config.gameTouchFix !== false,
      archive: archiveCfg(),
    });
  }
  return undefined;
});

connectNative();
