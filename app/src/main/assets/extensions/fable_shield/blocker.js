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
  }
}

async function broadcastToActiveTab(message) {
  try {
    const tabs = await browser.tabs.query({ active: true });
    for (const tab of tabs) {
      browser.tabs.sendMessage(tab.id, message).catch(() => {});
    }
  } catch (e) {
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
  if (!adBlockOn(details.incognito)) return {};
  if (details.type === "main_frame") return {};
  if (allowSites.size > 0) {
    const top = details.frameAncestors && details.frameAncestors.length
      ? details.frameAncestors[details.frameAncestors.length - 1].url
      : details.documentUrl || details.originUrl;
    if (siteAllowed(hostOf(top))) return {};
  }
  if (shouldBlock(details.url, details.documentUrl || details.originUrl)) {
    if (details.tabId >= 0) {
      browser.tabs
        .sendMessage(details.tabId, { type: "fable-blocked", url: details.url })
        .catch(() => {});
    }
    return { cancel: true };
  }
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
  if (blockNormal || blockPrivate) {
    addListenerSafe(wr.onBeforeRequest, onBeforeRequest, filterFor(blockAll), ["blocking"]);
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
    });
  }
  return undefined;
});

connectNative();
