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
    if (h.name && h.name.toLowerCase() === "user-agent") return superByUa.get(h.value) || null;
  }
  return null;
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
  blockedSubstrings: [],
  exceptionDomains: new Set(),
  exceptionSubstrings: [],
};

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
    setTimeout(connectNative, 2000);
  });
  port.postMessage({ type: "hello" });
}

function onNativeMessage(msg) {
  if (!msg || typeof msg !== "object") return;
  if (msg.type === "config") {
    config = Object.assign(config, msg);
    rebuildSupers();
    registerListeners();
    applyContentScripts(false);
  } else if (msg.type === "rules") {
    rules = {
      blockedDomains: new Set(msg.blockedDomains || []),
      blockedSubstrings: msg.blockedSubstrings || [],
      exceptionDomains: new Set(msg.exceptionDomains || []),
      exceptionSubstrings: msg.exceptionSubstrings || [],
    };
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
  for (const s of rules.exceptionSubstrings) {
    if (url.includes(s)) return false;
  }

  const docHost = documentUrl ? hostOf(documentUrl) : null;
  if (matchesDomain(host, rules.blockedDomains)) {
    if (isSameSite(host, docHost)) return false;
    return true;
  }
  for (const s of rules.blockedSubstrings) {
    if (url.includes(s)) return true;
  }
  return false;
}

function onBeforeRequest(details) {
  const on = details.incognito ? config.adBlockIncognito !== false : config.adBlockEnabled !== false;
  if (!on) return {};
  if (details.type === "main_frame") return {};
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

    if (CLIENT_HINT_HEADERS.has(name)) continue;
    if (sup && name.startsWith("sec-ch-ua")) continue;

    if (sup && name === "accept-language") {
      headers.push({ name: header.name, value: sup.acceptLanguage || header.value });
      continue;
    }

    if (name === "referer" && !isSameSite(target, docHost)) {
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
      if (sup && !sup.doNotTrack) continue;
    }
    headers.push(header);
  }

  if (sup) {
    if (sup.doNotTrack && !sawDnt) headers.push({ name: "DNT", value: "1" });
  } else if (shield && !sawDnt) {
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
      try {
        proxyEvent.addListener(onProxyRequest, { urls: ["<all_urls>"] }, ["requestHeaders"]);
        proxyHeadersSupported = true;
      } catch (e) {
        proxyHeadersSupported = false;
        try {
          proxyEvent.addListener(onProxyRequest, { urls: ["<all_urls>"] });
        } catch (x) {
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
