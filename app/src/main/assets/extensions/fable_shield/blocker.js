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
};

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
    registerListeners();
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
  if (!config.normalTabShield && !details.incognito) return {};
  const headers = [];
  const docHost = hostOf(details.documentUrl || details.originUrl || details.url);
  const target = hostOf(details.url);
  let sawDnt = false;

  for (const header of details.requestHeaders) {
    const name = header.name.toLowerCase();

    if (CLIENT_HINT_HEADERS.has(name)) continue;

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

    if (name === "dnt") sawDnt = true;
    headers.push(header);
  }

  if (!sawDnt) headers.push({ name: "DNT", value: "1" });

  return { requestHeaders: headers };
}

function onProxyRequest(details) {
  if (!config.proxyEnabled) return { type: "direct" };
  if (!config.proxyHost) return { type: "direct" };
  if (!details.incognito) return { type: "direct" };

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

function registerListeners() {
  const blockNormal = config.adBlockEnabled !== false;
  const blockPrivate = config.adBlockIncognito !== false;
  const blockAll = blockNormal;
  const headersAll = config.normalTabShield === true;
  const proxyOn = config.proxyEnabled === true && !!config.proxyHost;
  const state = [blockNormal, blockPrivate, headersAll, proxyOn].join(",");
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

  if (browser.proxy && browser.proxy.onRequest) {
    const proxyEvent = browser.proxy.onRequest;
    if (proxyEvent.hasListener(onProxyRequest)) proxyEvent.removeListener(onProxyRequest);
    if (proxyOn) addListenerSafe(proxyEvent, onProxyRequest, filterFor(false), null);
  }
}

registerListeners();

browser.runtime.onMessage.addListener((msg) => {
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
