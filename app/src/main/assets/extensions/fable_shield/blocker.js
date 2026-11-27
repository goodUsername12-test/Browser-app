const NATIVE_APP = "fable";

let config = {
  proxyEnabled: false,
  proxyHost: "",
  proxyPort: 9050,
  proxyType: "socks5",
  basicProxyEnabled: false,
  basicProxyType: "socks5",
  basicProxyHost: "",
  basicProxyPort: 1080,
  basicProxyUser: "",
  basicProxyPass: "",
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
  archiveIncludeCookies: false,
  archiveContinuousCapture: true,
  proxyStripLeakHeaders: true,
  stealthLowDetectability: true,
  etpEnabled: true,
  timeScaleEnabled: false,
  timeScaleRate: 1.0,
  timeScaleCss: true,
  timeScaleMedia: true,
  timeScaleWorkers: true,
  memoryEngineEnabled: false,
};

let superByUa = new Map();
let registered = null;
let registerSeq = 0;
let lastContentConfig = "";
let timeScaleRegistered = null;
let timeScaleSeq = 0;
let lastTimeScaleConfig = "";
let memScanRegistered = null;
let memScanSeq = 0;
const memScanChannel = "_mev_" + Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 8);
const timeScaleChannel = "_ev_" + Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 8);

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

function timeScaleConfig() {
  return {
    enabled: config.timeScaleEnabled === true,
    rate: Math.max(0.1, Math.min(300.0, Number(config.timeScaleRate) || 1.0)),
    css: config.timeScaleCss !== false,
    media: config.timeScaleMedia !== false,
    workers: config.timeScaleWorkers !== false,
    ch: timeScaleChannel,
  };
}

async function applyTimeScaleScript(force) {
  const cfgObj = timeScaleConfig();
  if (!cfgObj.enabled) {
    lastTimeScaleConfig = "";
    ++timeScaleSeq;
    const old = timeScaleRegistered;
    timeScaleRegistered = null;
    if (old) {
      try { await old.unregister(); } catch (_) {}
    }
    return;
  }
  const cfgStr = JSON.stringify(cfgObj);
  if (!force && cfgStr === lastTimeScaleConfig && timeScaleRegistered) return;
  lastTimeScaleConfig = cfgStr;
  const seq = ++timeScaleSeq;
  let next = null;
  try {
    if (browser.contentScripts && browser.contentScripts.register) {
      next = await browser.contentScripts.register({
        matches: ["<all_urls>"],
        js: [{ code: "var __TS_CFG = " + cfgStr + ";" }, { file: "time_scale.js" }],
        runAt: "document_start",
        allFrames: true,
        matchAboutBlank: true,
      });
    }
  } catch (e) {
    postNative({ type: "diag", text: "time_scale register failed: " + e });
  }
  if (seq !== timeScaleSeq) {
    if (next) next.unregister().catch(() => {});
    return;
  }
  const old = timeScaleRegistered;
  timeScaleRegistered = next;
  if (old) {
    try { await old.unregister(); } catch (_) {}
  }
}

async function broadcastTimeScaleLive(msg) {
  const enabled = msg.enabled === true;
  const cfgObj = {
    enabled,
    rate: Math.max(0.1, Math.min(300.0, Number(msg.rate) || 1.0)),
    css: msg.css !== false,
    media: msg.media !== false,
    workers: msg.workers !== false,
    ch: timeScaleChannel,
  };
  config.timeScaleEnabled = enabled;
  config.timeScaleRate = cfgObj.rate;
  config.timeScaleCss = cfgObj.css;
  config.timeScaleMedia = cfgObj.media;
  config.timeScaleWorkers = cfgObj.workers;
  await applyTimeScaleScript(false);
    applyMemoryScannerScript();
  try {
    const tabs = await browser.tabs.query({});
    for (const tab of tabs) {
      if (!tab || tab.id === undefined) continue;
      let delivered = false;
      try {
        await browser.tabs.sendMessage(tab.id, Object.assign({ type: "fable-time-scale-update" }, cfgObj));
        delivered = true;
      } catch (_) {}
      if (!delivered && enabled && browser.tabs.executeScript) {
        try {
          await browser.tabs.executeScript(tab.id, { code: "var __TS_CFG = " + JSON.stringify(cfgObj) + ";", allFrames: true });
          await browser.tabs.executeScript(tab.id, { file: "time_scale.js", allFrames: true });
        } catch (_) {}
      }
    }
  } catch (_) {}
}


async function applyMemoryScannerScript() {
  const on = config.memoryEngineEnabled === true;
  if (!on) {
    ++memScanSeq;
    const old = memScanRegistered;
    memScanRegistered = null;
    if (old) { try { await old.unregister(); } catch (_) {} }
    try {
      const tabs = await browser.tabs.query({});
      for (const tab of tabs) {
        if (tab && tab.id !== undefined) {
          browser.tabs.sendMessage(tab.id, { type: "fable-mem-reset" }).catch(() => {});
        }
      }
    } catch (_) {}
    return;
  }
  if (memScanRegistered) return;
  const seq = ++memScanSeq;
  const cfgStr = JSON.stringify({ enabled: true, ch: memScanChannel });
  let next = null;
  try {
    if (browser.contentScripts && browser.contentScripts.register) {
      next = await browser.contentScripts.register({
        matches: ["<all_urls>"],
        js: [{ code: "var __MEM_CFG = " + cfgStr + ";" }, { file: "memory_scanner.js" }],
        runAt: "document_start",
        allFrames: true,
        matchAboutBlank: true,
      });
    }
  } catch (e) {
    postNative({ type: "diag", text: "memory_scanner register failed: " + e });
  }
  if (seq !== memScanSeq) {
    if (next) next.unregister().catch(() => {});
    return;
  }
  const old = memScanRegistered;
  memScanRegistered = next;
  if (old) { try { await old.unregister(); } catch (_) {} }
  // Immediately hook any already-open tabs/frames so no manual Hook Reload is needed
  try {
    if (browser.tabs && browser.tabs.query && browser.tabs.executeScript) {
      const tabs = await browser.tabs.query({});
      for (const tab of tabs) {
        if (tab && tab.id !== undefined) {
          ensureMemHookOnTab(tab.id).catch(() => {});
        }
      }
    }
  } catch (_) {}
}

async function ensureMemHookOnTab(tabId) {
  if (!config.memoryEngineEnabled || !browser.tabs || !browser.tabs.executeScript) return;
  try {
    const check = await browser.tabs.executeScript(tabId, {
      code: "typeof globalThis.__fableRunFrameMemCmd === 'function'",
      allFrames: true
    });
    const needsInject = !Array.isArray(check) || check.length === 0 || check.some((v) => v !== true);
    if (needsInject) {
      const cfgStr = JSON.stringify({ enabled: true, ch: memScanChannel });
      await browser.tabs.executeScript(tabId, { code: "var __MEM_CFG = " + cfgStr + ";", allFrames: true });
      await browser.tabs.executeScript(tabId, { file: "memory_scanner.js", allFrames: true });
    }
  } catch (_) {}
}



function mergeFrameMemPayloads(rawList, op, maxReturn) {
  const parsed = [];
  for (const r of (rawList || [])) {
    if (typeof r !== "string" || !r) continue;
    try {
      const o = JSON.parse(r);
      if (o && !o.skippedFrame) parsed.push(o);
    } catch (_) {}
  }
  if (!parsed.length) return JSON.stringify({ ok: false, error: "Page did not respond (tap Hook Reload or use Fable Process)" });
  if (parsed.length === 1) return JSON.stringify(parsed[0]);
  if (op === "readBatch") {
    const vals = {};
    const freezeStats = {};
    const traces = {};
    for (const o of parsed) {
      if (o.values && typeof o.values === "object") Object.assign(vals, o.values);
      if (o.freezeStats && typeof o.freezeStats === "object") Object.assign(freezeStats, o.freezeStats);
      if (o.traces && typeof o.traces === "object") Object.assign(traces, o.traces);
    }
    let docGen = "", hookedEarly = false;
    for (const o of parsed) {
      if (!docGen && o.docGen) docGen = String(o.docGen);
      if (o.hookedEarly) hookedEarly = true;
    }
    return JSON.stringify({ ok: true, docGen, hookedEarly, values: vals, freezeStats, traces });
  }
  if (op === "ping") {
    let wasmCount = 0, bufCount = 0, workerCount = 0, fc = 0, hookedEarly = false, freezeStrength = 1;
    let docGen = "";
    const engSet = new Set();
    for (const o of parsed) {
      if (!o.ok) continue;
      fc++;
      if (!docGen && o.docGen) docGen = String(o.docGen);
      if (o.hookedEarly) hookedEarly = true;
      if (typeof o.freezeStrength === "number") freezeStrength = o.freezeStrength;
      wasmCount += Number(o.wasmCount || 0);
      bufCount += Number(o.bufCount || 0);
      workerCount += Number(o.workerCount || 0);
      for (const e of (o.engines || [])) { if (e) engSet.add(e); }
    }
    return JSON.stringify({ ok: fc > 0, docGen, hookedEarly, freezeStrength, wasmCount, bufCount, workerCount, frameCount: fc, engines: Array.from(engSet) });
  }
  if (op === "getTraceLogs") {
    const traces = {};
    for (const o of parsed) {
      if (o.traces && typeof o.traces === "object") Object.assign(traces, o.traces);
    }
    return JSON.stringify({ ok: true, traces });
  }
  if (op === "writeBatch") {
    let wrote = 0;
    const vals = {};
    for (const o of parsed) {
      if (!o.ok) continue;
      wrote += Number(o.wrote || 0);
      if (o.values && typeof o.values === "object") Object.assign(vals, o.values);
    }
    return JSON.stringify({ ok: true, wrote, values: vals });
  }
  if (op === "freezeBatch") {
    let locked = 0;
    const lockedIds = [];
    const failedIds = [];
    const freezeStats = {};
    for (const o of parsed) {
      locked += Number(o.locked) || 0;
      if (Array.isArray(o.lockedIds)) for (const id of o.lockedIds) lockedIds.push(id);
      if (Array.isArray(o.failedIds)) for (const id of o.failedIds) failedIds.push(id);
      if (o.freezeStats) Object.assign(freezeStats, o.freezeStats);
    }
    return JSON.stringify({ ok: locked > 0, locked, lockedIds, failedIds, freezeStats });
  }
  if (op === "unfreeze" || op === "unfreezeBatch" || op === "unfreezeAll" || op === "clearTrace" || op === "setFreezeStrength") {
    return JSON.stringify({ ok: true });
  }
  if (op === "write" || op === "hexDump" || op === "writeHex" || op === "setFreeze" || op === "peekStruct" || op === "pointerScan" || op === "setTrace" || op === "reattach" || op === "findMirrors" || op === "captureIdentity" || op === "reattachIdentity" || op === "testAuthority" || op === "setSafeMax" || op === "takeSnapshot") {
    const hit = parsed.find((o) => o.ok) || parsed[0];
    return JSON.stringify(hit);
  }
  if (op === "regions") {
    let totalKb = 0, fc = 0, workerCount = 0;
    const regs = [];
    const engSet = new Set();
    for (const o of parsed) {
      if (!o.ok) continue;
      fc++;
      totalKb += Number(o.totalKb || 0);
      workerCount += Number(o.workerCount || 0);
      for (const e of (o.engines || [])) { if (e) engSet.add(e); }
      for (const r of (o.regions || [])) {
        if (regs.length < 160) regs.push(r);
      }
    }
    return JSON.stringify({ ok: fc > 0, count: regs.length, totalKb, workerCount, frameCount: fc, engines: Array.from(engSet), regions: regs });
  }
  let anyOk = false, total = 0, canUndo = false, undoDepth = 0, wasmCount = 0, bufCount = 0, workerCount = 0, skippedXOrigin = 0, uiHiddenCount = 0;
  let maxElapsedMs = 0, anyTimedOut = false, anyFallback = false, anySnapshot = false, hookedEarly = false;
  let docGen = "";
  let wrote = 0, hasWrote = false, fc = 0;
  let firstErr = null;
  const engSet = new Set();
  const allItems = [];
  for (const o of parsed) {
    if (!o.ok) { if (!firstErr && o.error) firstErr = o.error; continue; }
    anyOk = true; fc++;
    if (!docGen && o.docGen) docGen = String(o.docGen);
    if (o.hookedEarly) hookedEarly = true;
    total += Number(o.total || 0);
    uiHiddenCount += Number(o.uiHiddenCount || 0);
    if (o.canUndo) canUndo = true;
    if (Number(o.undoDepth || 0) > undoDepth) undoDepth = Number(o.undoDepth || 0);
    wasmCount += Number(o.wasmCount || 0);
    bufCount += Number(o.bufCount || 0);
    workerCount += Number(o.workerCount || 0);
    skippedXOrigin += Number(o.skippedXOrigin || 0);
    if (Number(o.elapsedMs || 0) > maxElapsedMs) maxElapsedMs = Number(o.elapsedMs || 0);
    if (o.timedOut) anyTimedOut = true;
    if (o.fallbackUsed) anyFallback = true;
    if (o.snapshotActive) anySnapshot = true;
    for (const e of (o.engines || [])) { if (e) engSet.add(e); }
    if (typeof o.wrote === "number") { hasWrote = true; wrote += o.wrote; }
    for (const it of (o.items || [])) allItems.push(it);
  }
  if (!anyOk) return JSON.stringify({ ok: false, error: firstErr || "Scan failed" });
  const defSc = (x) => typeof x.score === "number" ? x.score : (x.kind === "wasm" ? 100 : (x.kind === "ab" || x.kind === "ta" ? 92 : 50));
  allItems.sort((a, b) => defSc(b) - defSc(a));
  const lim = maxReturn || 300;
  const items = allItems.slice(0, lim);
  const res = {
    ok: true, docGen, hookedEarly, total, uiHiddenCount, canUndo, undoDepth, wasmCount, bufCount, workerCount, skippedXOrigin,
    frameCount: fc, elapsedMs: maxElapsedMs, timedOut: anyTimedOut, complete: !anyTimedOut,
    fallbackUsed: anyFallback, snapshotActive: anySnapshot, engines: Array.from(engSet), items
  };
  if (hasWrote) res.wrote = wrote;
  return JSON.stringify(res);
}

async function handleMemCommand(msg) {
  const reqId = String(msg.reqId || "");
  const cmd = msg.cmd || { op: "ping" };
  try {
    const activeTabs = await browser.tabs.query({ active: true });
    let tab = activeTabs && activeTabs.length ? activeTabs[0] : null;
    if (!tab) {
      const all = await browser.tabs.query({});
      tab = all && all.length ? all[all.length - 1] : null;
    }
    if (!tab || tab.id === undefined) {
      postNative({ type: "mem-res", reqId, payload: JSON.stringify({ ok: false, error: "No active GeckoView tab" }) });
      return;
    }
    const execCode = "typeof globalThis.__fableRunFrameMemCmd === 'function' ? globalThis.__fableRunFrameMemCmd(" + JSON.stringify(cmd) + ") : null";
    let results = null;
    if (browser.tabs.executeScript) {
      try {
        results = await browser.tabs.executeScript(tab.id, { code: execCode, allFrames: true });
      } catch (_) {}
      const validCount = Array.isArray(results) ? results.filter((x) => typeof x === "string" && x.length > 0).length : 0;
      const missingAny = !Array.isArray(results) || validCount === 0 || results.some((x) => x === null);
      if (missingAny) {
        try {
          const cfgStr = JSON.stringify({ enabled: true, ch: memScanChannel });
          await browser.tabs.executeScript(tab.id, { code: "var __MEM_CFG = " + cfgStr + ";", allFrames: true });
          await browser.tabs.executeScript(tab.id, { file: "memory_scanner.js", allFrames: true });
          results = await browser.tabs.executeScript(tab.id, { code: execCode, allFrames: true });
        } catch (_) {}
      }
    }
    let payload = null;
    if (Array.isArray(results) && results.some((x) => typeof x === "string" && x.length > 0)) {
      payload = mergeFrameMemPayloads(results, cmd.op, cmd.maxReturn || 300);
    } else {
      let resp = null;
      try { resp = await browser.tabs.sendMessage(tab.id, { type: "fable-mem-cmd", cmd }); } catch (_) {}
      payload = (resp && typeof resp.payload === "string")
        ? resp.payload
        : JSON.stringify({ ok: false, error: "Page did not respond (tap Hook Reload or use Fable Process)" });
    }
    postNative({ type: "mem-res", reqId, payload });
  } catch (e) {
    postNative({ type: "mem-res", reqId, payload: JSON.stringify({ ok: false, error: String(e) }) });
  }
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

function pack4Js(s) {
  return (((s.charCodeAt(0) & 0xff) << 24) |
    ((s.charCodeAt(1) & 0xff) << 16) |
    ((s.charCodeAt(2) & 0xff) << 8) |
    (s.charCodeAt(3) & 0xff)) >>> 0;
}

function indexSubstrings(list) {
  const all = Array.isArray(list) ? list.filter((s) => typeof s === "string" && s.length > 0) : [];
  if (all.length === 0) return { all, map: null, short: [] };
  const map = new Map();
  const short = [];
  for (const s of all) {
    if (s.length < 4) {
      short.push(s);
      continue;
    }
    const key = pack4Js(s);
    const bucket = map.get(key);
    if (bucket) bucket.push(s);
    else map.set(key, [s]);
  }
  return { all, map, short };
}

function containsAny(url, index) {
  const short = index.short;
  for (let i = 0; i < short.length; i++) {
    if (url.indexOf(short[i]) !== -1) return true;
  }
  const map = index.map;
  if (!map || map.size === 0) return false;
  const len = url.length;
  if (len < 4) return false;
  let w = (((url.charCodeAt(0) & 0xff) << 16) |
    ((url.charCodeAt(1) & 0xff) << 8) |
    (url.charCodeAt(2) & 0xff)) >>> 0;
  for (let i = 3; i < len; i++) {
    w = (((w << 8) | (url.charCodeAt(i) & 0xff)) >>> 0);
    const bucket = map.get(w);
    if (!bucket) continue;
    const start = i - 3;
    for (let j = 0; j < bucket.length; j++) {
      if (url.startsWith(bucket[j], start)) return true;
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


const syncingFromBlink = new Map();

function markSyncFromBlink(host, name, value) {
  const now = Date.now();
  const cleanHost = String(host || "").toLowerCase().replace(/^\.+/, "");
  syncingFromBlink.set(cleanHost + "|" + name, { value: String(value || ""), expiresAt: now + 4000 });
  if (syncingFromBlink.size > 512) {
    for (const [k, v] of syncingFromBlink.entries()) {
      if (!v || v.expiresAt < now) syncingFromBlink.delete(k);
    }
  }
}

function isEchoFromBlink(cookie) {
  if (!cookie) return false;
  const now = Date.now();
  const name = String(cookie.name || "");
  const cleanHost = String(cookie.domain || "").toLowerCase().replace(/^\.+/, "");
  const direct = syncingFromBlink.get(cleanHost + "|" + name);
  if (direct && direct.expiresAt >= now && direct.value === String(cookie.value || "")) return true;
  const parts = cleanHost.split(".");
  for (let i = 1; i < parts.length - 1; i++) {
    const parent = parts.slice(i).join(".");
    const hit = syncingFromBlink.get(parent + "|" + name);
    if (hit && hit.expiresAt >= now && hit.value === String(cookie.value || "")) return true;
  }
  return false;
}

function cookieToBlinkPayload(c, removed) {
  if (!c || !c.name) return null;
  const rawDomain = String(c.domain || "");
  const cleanHost = rawDomain.replace(/^\.+/, "");
  if (!cleanHost) return null;
  const scheme = c.secure ? "https://" : "http://";
  const path = c.path || "/";
  const url = scheme + cleanHost + path;
  let sc = c.name + "=" + (removed ? "" : String(c.value || "")) + "; Path=" + path;
  if (!c.hostOnly && rawDomain) {
    sc += "; Domain=" + rawDomain;
  }
  if (removed) {
    sc += "; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT";
  } else if (typeof c.expirationDate === "number" && c.expirationDate > 0) {
    const maxAge = Math.max(1, Math.floor(c.expirationDate - Date.now() / 1000));
    sc += "; Max-Age=" + maxAge;
  }
  if (c.secure) sc += "; Secure";
  if (c.httpOnly) sc += "; HttpOnly";
  if (!removed) {
    if (c.sameSite === "strict") sc += "; SameSite=Strict";
    else if (c.sameSite === "lax") sc += "; SameSite=Lax";
    else if (c.sameSite === "no_restriction" && c.secure) sc += "; SameSite=None";
  }
  return { url, setCookie: sc };
}

async function syncCookiesToGecko(url, cookieHeader) {
  if (typeof browser === "undefined" || !browser.cookies || !url || !cookieHeader) return;
  let parsed;
  try {
    parsed = new URL(url);
  } catch (e) {
    return;
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return;
  const pairs = String(cookieHeader).split(";").map((s) => s.trim()).filter(Boolean);
  if (!pairs.length) return;
  let existing = [];
  try {
    existing = await browser.cookies.getAll({ url: parsed.href, storeId: "firefox-default" });
  } catch (e) {
    existing = [];
  }
  const byName = new Map();
  for (const c of existing) {
    if (c && c.name && !byName.has(c.name)) byName.set(c.name, c);
  }
  for (const pair of pairs) {
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    const name = pair.slice(0, eq).trim();
    const value = pair.slice(eq + 1);
    if (!name) continue;
    const cur = byName.get(name);
    if (cur && cur.value === value) continue;
    markSyncFromBlink(cur && cur.domain ? cur.domain : parsed.hostname, name, value);
    try {
      const details = {
        url: parsed.href,
        name,
        value,
        path: (cur && cur.path) ? cur.path : "/",
        secure: cur ? !!cur.secure : (parsed.protocol === "https:"),
        httpOnly: cur ? !!cur.httpOnly : false,
        storeId: "firefox-default",
      };
      if (cur && !cur.hostOnly && cur.domain) {
        details.domain = cur.domain;
      }
      if (cur && typeof cur.expirationDate === "number" && cur.expirationDate > 0) {
        details.expirationDate = cur.expirationDate;
      }
      await browser.cookies.set(details);
    } catch (e) {
    }
  }
}

async function pullGeckoCookies(url) {
  if (typeof browser === "undefined" || !browser.cookies || !url) return;
  let parsed;
  try {
    parsed = new URL(url);
  } catch (e) {
    return;
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return;
  try {
    const list = await browser.cookies.getAll({ url: parsed.href, storeId: "firefox-default" });
    const out = [];
    for (const c of list) {
      const item = cookieToBlinkPayload(c, false);
      if (item) out.push(item);
    }
    if (out.length > 0) {
      postNative({ type: "gecko-cookies-batch", url: parsed.href, cookies: out });
    }
  } catch (e) {
  }
}

if (typeof browser !== "undefined" && browser.cookies && browser.cookies.onChanged) {
  browser.cookies.onChanged.addListener((info) => {
    if (!info || !info.cookie) return;
    if (info.cookie.storeId !== "firefox-default") return;
    if (info.removed && info.cause === "overwrite") return;
    if (!info.removed && isEchoFromBlink(info.cookie)) return;
    const payload = cookieToBlinkPayload(info.cookie, !!info.removed);
    if (payload) {
      postNative({ type: "gecko-cookie-changed", url: payload.url, setCookie: payload.setCookie });
    }
  });
}

function onNativeMessage(msg) {
  if (!msg || typeof msg !== "object") return;
  if (msg.type === "config") {
    config = Object.assign(config, msg);
    allowSites = new Set((Array.isArray(config.adBlockAllow) ? config.adBlockAllow : []).map((h) => String(h).toLowerCase().replace(/^www\./, "")));
    rebuildSupers();
    registerListeners();
    applyContentScripts(false);
    applyTimeScaleScript(false);
    applyMemoryScannerScript();
  } else if (msg.type === "memory-engine-toggle") {
      config.memoryEngineEnabled = msg.enabled === true;
      applyMemoryScannerScript();
      return;
    }
    if (msg.type === "mem-cmd") {
      handleMemCommand(msg);
      return;
    }
    if (msg.type === "time-scale-update") {
    broadcastTimeScaleLive(msg);
  } else if (msg.type === "sync-cookies-to-gecko") {
    syncCookiesToGecko(msg.url, msg.cookieHeader);
  } else if (msg.type === "pull-gecko-cookies") {
    pullGeckoCookies(msg.url);
  } else if (msg.type === "backup-export-cookies") {
    handleBackupExportCookies(msg.reqId);
  } else if (msg.type === "backup-restore-cookies") {
    handleBackupRestoreCookies(msg.reqId, msg.cookies);
  } else if (msg.type === "backup-export-storage") {
    handleBackupExportStorage(msg.reqId);
  } else if (msg.type === "backup-set-pending-storage") {
    handleBackupSetPendingStorage(msg.origins);
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
    const cid = String(msg.id || "");
    const curl = String(msg.url || "");
    const cmode = String(msg.mode || "all");
    const cont = msg.continuous === true;
    if (cont) {
      liveCapture = { active: true, paused: false, id: cid, url: curl, mode: cmode, count: 0 };
    }
    triggerSavePage(cid, curl, cmode, cont);
  } else if (msg.type === "capture-control") {
    const act = String(msg.action || "");
    const cid = String(msg.id || "");
    if (act === "pause" && (!cid || liveCapture.id === cid)) {
      liveCapture.paused = true;
    } else if (act === "resume" && (!cid || liveCapture.id === cid)) {
      liveCapture.paused = false;
    } else if (act === "cancel") {
      liveCapture = { active: false, paused: false, id: "", url: "", mode: "", count: 0 };
    } else if (act === "stop") {
      const targetId = cid || liveCapture.id;
      const targetUrl = liveCapture.url;
      liveCapture = { active: false, paused: false, id: "", url: "", mode: "", count: 0 };
      if (targetId) {
        flushLiveSessionEnd(targetId, targetUrl);
      }
    }
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
    includeCookies: config.archiveIncludeCookies === true,
    continuousCapture: config.archiveContinuousCapture !== false,
  };
}

const tabNetStore = new Map();
const reqMeta = new Map();
let liveCapture = { active: false, paused: false, id: "", url: "", mode: "", count: 0 };
const SENSITIVE_HEADERS = new Set(["cookie", "set-cookie", "authorization", "proxy-authorization"]);
const CAPTURE_STREAM_TYPES = new Set([
  "main_frame", "sub_frame", "script", "stylesheet", "font", "image",
  "media", "xmlhttprequest", "fetch", "object", "other", "imageset"
]);

function sanitizeHeadersForArchive(list) {
  if (!Array.isArray(list)) return [];
  const keepSecrets = config.archiveIncludeCookies === true;
  return list.slice(0, 80).map((h) => {
    const nm = String((h && h.name) || "");
    const val = String((h && h.value) || "");
    if (!keepSecrets && SENSITIVE_HEADERS.has(nm.toLowerCase())) {
      return { name: nm, value: "[REDACTED]" };
    }
    return { name: nm, value: val };
  });
}

function streamLiveEntryToNative(id, entry, incognito) {
  if (!id || !entry || !entry.bytes || entry.bytes.length === 0) return;
  let p = "network-stream/live-" + (++liveCapture.count);
  try {
    const parsed = new URL(entry.url);
    const cleanHost = parsed.hostname.replace(/[^a-z0-9.-]/gi, "_");
    const cleanName = (parsed.pathname.split("/").filter(Boolean).slice(-2).join("_") || "index")
      .replace(/[^a-z0-9._-]/gi, "_").slice(0, 100);
    p = "network-stream/" + cleanHost + "/" + liveCapture.count + "_" + cleanName;
  } catch (_) {}
  const chunk = 512 * 1024;
  let seq = 0;
  for (let off = 0; off < entry.bytes.length; off += chunk) {
    const end = Math.min(entry.bytes.length, off + chunk);
    postNative({
      type: "save-page-data",
      id,
      op: "file",
      path: p,
      url: entry.url,
      mime: entry.mime || "",
      seq: seq++,
      last: end >= entry.bytes.length,
      data: b64Bytes(entry.bytes.subarray(off, end)),
      incognito: !!incognito,
    });
  }
}

async function flushLiveSessionEnd(id, url) {
  try {
    if (config.archiveIncludeCookies === true && url && /^https?:/i.test(url) && browser.cookies) {
      const cks = await browser.cookies.getAll({ url }).catch(() => []);
      if (cks && cks.length > 0) {
        const bytes = new TextEncoder().encode(JSON.stringify(cks, null, 2));
        postNative({
          type: "save-page-data",
          id,
          op: "file",
          path: "memory/cookies.json",
          seq: 0,
          last: true,
          data: b64Bytes(bytes),
          incognito: false,
        });
      }
    }
  } catch (_) {}
}

function getTabStore(tabId) {
  let s = tabNetStore.get(tabId);
  if (!s) {
    s = { bytes: 0, entries: new Map() };
    tabNetStore.set(tabId, s);
  }
  return s;
}

function isRealtimePingUrl(url, details) {
  if (details.type === "websocket" || details.type === "beacon" || details.type === "ping") return true;
  if (details.method && details.method !== "GET" && details.method !== "HEAD" && url.includes("/keep-alive")) return true;
  return false;
}

function materializeEntryBytes(entry) {
  if (!entry) return null;
  if (entry.bytes) return entry.bytes;
  if (!entry.chunks || entry.chunks.length === 0) return null;
  if (entry.chunks.length === 1) {
    entry.bytes = entry.chunks[0];
    entry.chunks = null;
    return entry.bytes;
  }
  const merged = new Uint8Array(entry.size);
  let off = 0;
  for (let i = 0; i < entry.chunks.length; i++) {
    const c = entry.chunks[i];
    merged.set(c, off);
    off += c.byteLength;
  }
  entry.bytes = merged;
  entry.chunks = null;
  return merged;
}

function captureStreamIfEnabled(details) {
  try {
    if (config.archiveNetworkStream === false) return;
    const tabId = details.tabId;
    if (typeof tabId !== "number" || tabId < 0) return;
    const url = String(details.url || "").split("#")[0];
    if (!/^https?:/i.test(url)) return;
    if (details.type === "main_frame") {
      tabNetStore.set(tabId, { bytes: 0, entries: new Map() });
    }
    const startedAt = Date.now();
    const resType = details.type || "other";
    // Browser network-layer filterResponseData NEVER modifies page source code!
    // Stream ALL loaded resource types (HTML, JS, CSS, WASM, fonts, images, media, JSON/API/XHR/fetch) transparently.
    const shouldStreamBody = !isRealtimePingUrl(url, details) &&
      (CAPTURE_STREAM_TYPES.has(resType) || (liveCapture.active && !liveCapture.paused));
    reqMeta.set(details.requestId, { tabId, url, method: details.method || "GET", resType, status: 200, mime: "", startedAt, streamed: shouldStreamBody });
    if (!shouldStreamBody) return;
    const wr = browser.webRequest;
    if (!wr || typeof wr.filterResponseData !== "function") return;
    const maxFileBytes = Math.min(64, Math.max(10, Number(config.archiveMaxFileMb) || 120)) * 1024 * 1024;
    const maxTabBytes = Math.max(16, Number(config.archiveStreamBufferMb) || 160) * 1024 * 1024;
    const filter = wr.filterResponseData(details.requestId);
    const chunks = [];
    let size = 0;
    let overflow = false;

    filter.ondata = (event) => {
      try {
        filter.write(event.data);
      } catch (e) {
      }
      if (!overflow && event.data && event.data.byteLength) {
        if (size + event.data.byteLength <= maxFileBytes) {
          chunks.push(new Uint8Array(event.data));
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
      const rec = {
        url,
        method: meta.method,
        resType: meta.resType,
        status: meta.status || 200,
        statusLine: meta.statusLine || "",
        mime: meta.mime || "",
        reqHeaders: sanitizeHeadersForArchive(meta.reqHeaders || []),
        resHeaders: sanitizeHeadersForArchive(meta.resHeaders || []),
        size,
        timeMs: Date.now() - startedAt,
        startedIso: new Date(startedAt).toISOString(),
        bytes: chunks.length === 1 ? chunks[0] : null,
        chunks: chunks.length > 1 ? chunks : null,
      };
      if (liveCapture.active && !liveCapture.paused && liveCapture.id) {
        materializeEntryBytes(rec);
        streamLiveEntryToNative(liveCapture.id, rec, !!details.incognito);
        // Release body from JS RAM after flushing to disk staging
        if (meta.resType !== "main_frame") {
          rec.bytes = null;
          rec.chunks = null;
        }
        store.entries.set(url, rec);
      } else {
        store.entries.set(url, rec);
        store.bytes += size;
      }
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
  let contentLen = 0;
  if (Array.isArray(details.responseHeaders)) {
    m.resHeaders = details.responseHeaders.slice(0, 80).map((h) => ({
      name: String((h && h.name) || ""),
      value: String((h && h.value) || ""),
    }));
    for (const h of details.responseHeaders) {
      if (!h || !h.name) continue;
      const lower = h.name.toLowerCase();
      if (lower === "content-type") m.mime = String(h.value || "");
      else if (lower === "content-length") contentLen = Number(h.value) || 0;
    }
  }
  if (!m.streamed) {
    reqMeta.delete(details.requestId);
    const store = getTabStore(m.tabId);
    if (store.entries.size < 4000 && !store.entries.has(m.url)) {
      store.entries.set(m.url, {
        url: m.url,
        method: m.method,
        resType: m.resType,
        status: m.status,
        statusLine: m.statusLine,
        mime: m.mime,
        reqHeaders: m.reqHeaders || [],
        resHeaders: m.resHeaders || [],
        size: contentLen,
        timeMs: Math.max(1, Date.now() - m.startedAt),
        startedIso: new Date(m.startedAt).toISOString(),
        bytes: null,
      });
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

async function triggerSavePage(id, url, mode, continuous) {
  const tabs = await candidateTabs(url);
  const msg = { type: "fable-save-page", id, url, mode: mode || "all", continuous: !!continuous, cfg: archiveCfg(), force: true };
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
  if (!url || typeof url !== "string") return null;
  const schemeIdx = url.indexOf("://");
  if (schemeIdx > 0) {
    let start = schemeIdx + 3;
    let end = start;
    const len = url.length;
    while (end < len) {
      const c = url.charCodeAt(end);
      if (c === 47 || c === 58 || c === 63 || c === 35) break;
      end++;
    }
    if (end > start) {
      const raw = url.slice(start, end);
      if (raw.indexOf("@") === -1 && raw.indexOf("[") === -1) {
        const lo = raw.toLowerCase();
        return lo.startsWith("www.") ? lo.slice(4) : lo;
      }
    }
  }
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

function isChallengeOrTurnstileHost(h) {
  return !!h && /(?:^|\.)(challenges\.cloudflare\.com|cloudflare\.com|recaptcha\.net|hcaptcha\.com|arkoselabs\.com|funcaptcha\.com|workos\.com|auth0\.com)$/i.test(h);
}

function shouldBlock(url, documentUrl) {
  const host = hostOf(url);
  if (!host) return false;
  if (isChallengeOrTurnstileHost(host)) return false;
  const docHost = documentUrl ? hostOf(documentUrl) : null;
  if (docHost && isSameSite(host, docHost)) return false;

  const blockedCandidate = matchesDomain(host, rules.blockedDomains) || containsAny(url, rules.blockedSubstrings);
  if (!blockedCandidate) return false;

  if (matchesDomain(host, rules.exceptionDomains)) return false;
  if (containsAny(url, rules.exceptionSubstrings)) return false;
  return true;
}

function onBeforeRequest(details) {
  if (!adBlockOn(details.incognito)) {
    captureStreamIfEnabled(details);
    return {};
  }
  if (details.type === "main_frame" || details.type === "websocket") {
    captureStreamIfEnabled(details);
    return {};
  }
  if (details.method && details.method !== "GET" && details.method !== "HEAD") {
    const docUrl = details.documentUrl || details.originUrl;
    if (docUrl && isSameSite(hostOf(details.url), hostOf(docUrl))) {
      captureStreamIfEnabled(details);
      return {};
    }
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
]);

const PROXY_LEAK_HEADERS = new Set([
  "x-forwarded-for",
  "x-forwarded-host",
  "x-forwarded-proto",
  "x-real-ip",
  "client-ip",
  "via",
  "forwarded",
]);

function onBeforeSendHeaders(details) {
  const sup = details.incognito ? superFor(details) : null;
  const saver = config.dataSaver === true;
  const shield = config.normalTabShield === true || details.incognito;
  const stripProxyLeaks = config.proxyStripLeakHeaders !== false && (basicProxyOn() || superProxyOn() || (details.incognito && config.proxyEnabled === true));
  if (!sup && !saver && !shield && !stripProxyLeaks) return {};
  const headers = [];
  const docHost = hostOf(details.documentUrl || details.originUrl || details.url);
  const target = hostOf(details.url);
  if (isChallengeOrTurnstileHost(target) || isChallengeOrTurnstileHost(docHost)) return {};
  let sawDnt = false;
  let sawSaver = false;

  for (const header of details.requestHeaders) {
    const name = header.name.toLowerCase();

    if (name === "save-data") sawSaver = true;

    if (stripProxyLeaks && PROXY_LEAK_HEADERS.has(name)) continue;

    if (!shield && !sup) {
      headers.push(header);
      continue;
    }

    if (config.proxyStripLeakHeaders !== false && PROXY_LEAK_HEADERS.has(name)) continue;
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

function basicProxyOn() {
  return config.basicProxyEnabled === true && !!config.basicProxyHost;
}

function basicProxyInfo() {
  const port = Number(config.basicProxyPort) || 1080;
  const user = config.basicProxyUser || "";
  const pass = config.basicProxyPass || "";
  if (config.basicProxyType === "http" || config.basicProxyType === "https") {
    const p = { type: config.basicProxyType, host: config.basicProxyHost, port };
    if (user || pass) {
      try {
        p.proxyAuthorizationHeader = "Basic " + btoa(unescape(encodeURIComponent(user + ":" + pass)));
      } catch (e) {}
    }
    return p;
  }
  const p = { type: "socks", host: config.basicProxyHost, port, proxyDNS: true };
  if (user) p.username = user;
  if (pass) p.password = pass;
  return p;
}

function onProxyRequest(details) {
  const reqHost = hostOf(details && details.url);
  if (reqHost === "local.fable.app" || reqHost === "localhost" || reqHost === "127.0.0.1") {
    return { type: "direct" };
  }
  if (details.incognito) {
    if (superProxyOn()) {
      if (!details.requestHeaders) return superProxyInfo();
      if (superFor(details)) return superProxyInfo();
    }
    if (config.proxyEnabled && config.proxyHost) return incognitoProxyInfo();
  }
  if (basicProxyOn()) {
    return basicProxyInfo();
  }
  return { type: "direct" };
}

const authTried = new Map();

function onAuthRequired(details) {
  if (!details.isProxy || (!superProxyOn() && !basicProxyOn())) return {};
  const useSuper = details.incognito && superProxyOn();
  const user = useSuper ? (config.superProxyUser || "") : (config.basicProxyUser || "");
  const pass = useSuper ? (config.superProxyPass || "") : (config.basicProxyPass || "");
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
  const headersAll = config.normalTabShield === true || config.dataSaver === true || (config.proxyStripLeakHeaders !== false && (basicProxyOn() || superProxyOn()));
  const incognitoProxyOn = config.proxyEnabled === true && !!config.proxyHost;
  const normalProxyOn = basicProxyOn();
  const proxyOn = incognitoProxyOn || superProxyOn() || normalProxyOn;
  const state = [blockNormal, blockPrivate, headersAll, proxyOn, superProxyOn(), normalProxyOn, config.basicProxyHost, config.basicProxyPort].join(",");
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
      if (superProxyOn() || normalProxyOn) wr.onAuthRequired.addListener(onAuthRequired, { urls: ["<all_urls>"] }, ["blocking"]);
    } catch (e) {
    }
  }

  if (browser.proxy && browser.proxy.onRequest) {
    const proxyEvent = browser.proxy.onRequest;
    if (proxyEvent.hasListener(onProxyRequest)) proxyEvent.removeListener(onProxyRequest);
    if (proxyOn) {
      const attempts = normalProxyOn
        ? [
            [{ urls: ["<all_urls>"] }, ["requestHeaders"]],
            [{ urls: ["<all_urls>"] }, null],
          ]
        : [
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
applyTimeScaleScript(true);

browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "fable-icon") {
    const tab = sender && sender.tab;
    if (tab && !tab.incognito && (sender.frameId === 0 || sender.frameId === undefined)) {
      postNative({ type: "icon", page: String(msg.page || ""), icons: Array.isArray(msg.icons) ? msg.icons.slice(0, 3) : [] });
    }
    return undefined;
  }
  if (msg && msg.type === "fable-net-flush") {
    return (async () => {
      const tab = sender && sender.tab;
      const tabId = tab ? tab.id : -1;
      const id = String(msg.id || "");
      const incognito = !!(tab && tab.incognito);
      const includeBodies = msg.includeBodies !== false;
      const already = new Set(Array.isArray(msg.seenUrls) ? msg.seenUrls : []);
      const store = tabNetStore.get(tabId);
      const flushed = [];
      if (store && store.entries.size > 0) {
        if (includeBodies) {
          const toHydrate = [];
          for (const [u, entry] of store.entries.entries()) {
            if (!entry.bytes && !entry.chunks && entry.method === "GET" && /^https?:/i.test(u) && !isRealtimePingUrl(u, { type: entry.resType, method: entry.method })) {
              toHydrate.push(entry);
            }
          }
          let cursor = 0;
          const maxHydrateBytes = Math.max(16, Number(config.archiveStreamBufferMb) || 160) * 1024 * 1024;
          let hydratedBytes = 0;
          const workers = [];
          for (let w = 0; w < Math.min(8, toHydrate.length); w++) {
            workers.push(
              (async () => {
                while (cursor < toHydrate.length && hydratedBytes < maxHydrateBytes) {
                  const e = toHydrate[cursor++];
                  if (!e) break;
                  try {
                    const ctrl = typeof AbortController !== "undefined" ? new AbortController() : null;
                    const t = ctrl ? setTimeout(() => ctrl.abort(), 3500) : null;
                    let res = await fetch(e.url, { cache: "force-cache", credentials: "omit", redirect: "follow", signal: ctrl ? ctrl.signal : undefined }).catch(() => null);
                    if (!res || !res.ok) {
                      res = await fetch(e.url, { cache: "default", credentials: "omit", redirect: "follow", signal: ctrl ? ctrl.signal : undefined }).catch(() => null);
                    }
                    if (t) clearTimeout(t);
                    if (res && res.ok) {
                      const buf = new Uint8Array(await res.arrayBuffer());
                      if (buf.byteLength > 0 && hydratedBytes + buf.byteLength <= maxHydrateBytes) {
                        e.bytes = buf;
                        e.size = buf.byteLength;
                        hydratedBytes += buf.byteLength;
                      }
                    }
                  } catch (_) {
                  }
                }
              })()
            );
          }
          await Promise.all(workers);
        }
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
        materializeEntryBytes(entry);
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
            headers: sanitizeHeadersForArchive(entry.reqHeaders || []),
            queryString: qList,
            cookies: [],
            headersSize: -1,
            bodySize: 0,
          },
          response: {
            status: entry.status,
            statusText: entry.statusLine || "OK",
            httpVersion: "HTTP/2.0",
            headers: sanitizeHeadersForArchive(entry.resHeaders || []),
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
      const harBytes = new TextEncoder().encode(JSON.stringify({ log: { version: "1.2", creator: { name: "Fable Browser", version: "1.56" }, entries: harEntries } }, null, 2));
      sendNativeChunked("devtools/network.har", harBytes);
      flushed.push({ path: "devtools/network.har", url: "devtools://network.har", kind: "devtools:har", type: "application/json", size: harBytes.length });
      if (config.archiveIncludeCookies === true && !incognito && tab && tab.url && /^https?:/i.test(tab.url) && browser.cookies) {
        try {
          const cks = await browser.cookies.getAll({ url: tab.url }).catch(() => []);
          if (cks && cks.length > 0) {
            const ckBytes = new TextEncoder().encode(JSON.stringify(cks, null, 2));
            sendNativeChunked("memory/cookies.json", ckBytes);
            flushed.push({ path: "memory/cookies.json", url: tab.url, kind: "memory:cookies", type: "application/json", size: ckBytes.length });
          }
        } catch (_) {}
      }
      for (const entry of store.entries.values()) {
        if (entry.resType !== "main_frame") {
          entry.bytes = null;
          entry.chunks = null;
        }
      }
      store.bytes = 0;
    }
    return { flushed };
    })();
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


const pendingBackupSiteStorage = Object.create(null);
let backupStorageListenerAttached = false;

async function handleBackupExportCookies(reqId) {
  try {
    if (!browser.cookies || !browser.cookies.getAll) {
      postNative({ type: "mem-res", reqId: String(reqId || ""), payload: "[]" });
      return;
    }
    const list = await browser.cookies.getAll({});
    const out = [];
    for (const c of (list || [])) {
      if (!c || !c.name || !c.domain) continue;
      out.push({
        name: c.name,
        value: c.value || "",
        domain: c.domain,
        path: c.path || "/",
        secure: c.secure === true,
        httpOnly: c.httpOnly === true,
        hostOnly: c.hostOnly === true,
        sameSite: c.sameSite || "no_restriction",
        expirationDate: typeof c.expirationDate === "number" ? c.expirationDate : null
      });
    }
    postNative({ type: "mem-res", reqId: String(reqId || ""), payload: JSON.stringify(out) });
  } catch (_) {
    postNative({ type: "mem-res", reqId: String(reqId || ""), payload: "[]" });
  }
}

async function handleBackupRestoreCookies(reqId, cookies) {
  let restored = 0;
  try {
    if (browser.cookies && browser.cookies.set && Array.isArray(cookies)) {
      for (const c of cookies) {
        if (!c || !c.name || !c.domain) continue;
        const cleanDomain = String(c.domain).replace(/^\.+/, "");
        if (!cleanDomain) continue;
        const path = c.path || "/";
        const scheme = c.secure ? "https://" : "http://";
        const details = {
          url: scheme + cleanDomain + path,
          name: String(c.name),
          value: String(c.value || ""),
          path: path,
          secure: c.secure === true,
          httpOnly: c.httpOnly === true
        };
        if (c.hostOnly !== true) details.domain = String(c.domain);
        if (typeof c.expirationDate === "number" && c.expirationDate > Date.now() / 1000) {
          details.expirationDate = c.expirationDate;
        }
        try {
          await browser.cookies.set(details);
          restored++;
        } catch (_) {
          try {
            delete details.domain;
            await browser.cookies.set(details);
            restored++;
          } catch (_) {}
        }
      }
    }
  } catch (_) {}
  postNative({ type: "mem-res", reqId: String(reqId || ""), payload: String(restored) });
}

async function handleBackupExportStorage(reqId) {
  const result = Object.create(null);
  try {
    if (browser.tabs && browser.tabs.query && browser.tabs.executeScript) {
      const tabs = await browser.tabs.query({});
      const code = `(() => {
        try {
          const orig = location.origin || "";
          if (!orig || orig === "null") return null;
          const ls = {}, ss = {};
          let c1 = 0, c2 = 0;
          for (let i = 0; i < localStorage.length && c1 < 200; i++) {
            const k = localStorage.key(i);
            if (!k || k.indexOf("__fable_") === 0) continue;
            const v = localStorage.getItem(k);
            if (typeof v === "string" && v.length <= 32768) { ls[k] = v; c1++; }
          }
          for (let j = 0; j < sessionStorage.length && c2 < 100; j++) {
            const k2 = sessionStorage.key(j);
            if (!k2 || k2.indexOf("__fable_") === 0) continue;
            const v2 = sessionStorage.getItem(k2);
            if (typeof v2 === "string" && v2.length <= 32768) { ss[k2] = v2; c2++; }
          }
          if (c1 === 0 && c2 === 0) return null;
          return { origin: orig, ls, ss };
        } catch (_) { return null; }
      })()`;
      for (const tab of (tabs || [])) {
        if (!tab || tab.id === undefined || tab.incognito || !tab.url || !/^https?:\/\//i.test(tab.url)) continue;
        try {
          const arr = await browser.tabs.executeScript(tab.id, { code });
          const item = Array.isArray(arr) && arr.length ? arr[0] : null;
          if (item && item.origin) result[item.origin] = { ls: item.ls || {}, ss: item.ss || {} };
        } catch (_) {}
      }
    }
  } catch (_) {}
  postNative({ type: "mem-res", reqId: String(reqId || ""), payload: JSON.stringify(result) });
}

async function injectBackupStorageIntoTab(tabId, url) {
  try {
    if (!url || !/^https?:\/\//i.test(url)) return;
    const orig = new URL(url).origin;
    const data = pendingBackupSiteStorage[orig];
    if (!data) return;
    const payloadStr = JSON.stringify(data);
    const code = `(() => {
      try {
        const d = ${payloadStr};
        if (d.ls) { for (const [k, v] of Object.entries(d.ls)) { if (typeof v === "string") localStorage.setItem(k, v); } }
        if (d.ss) { for (const [k, v] of Object.entries(d.ss)) { if (typeof v === "string") sessionStorage.setItem(k, v); } }
        return true;
      } catch (_) { return false; }
    })()`;
    const res = await browser.tabs.executeScript(tabId, { code, runAt: "document_start" });
    if (Array.isArray(res) && res[0] === true) {
      delete pendingBackupSiteStorage[orig];
    }
  } catch (_) {}
}

function handleBackupSetPendingStorage(origins) {
  if (!origins || typeof origins !== "object") return;
  for (const [k, v] of Object.entries(origins)) {
    if (k && v && typeof v === "object") pendingBackupSiteStorage[k] = v;
  }
  if (!backupStorageListenerAttached && browser.tabs && browser.tabs.onUpdated) {
    backupStorageListenerAttached = true;
    browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
      if (tab && tab.incognito) return;
      const u = (changeInfo && changeInfo.url) || (tab && tab.url) || "";
      if (u && (changeInfo.status === "loading" || changeInfo.status === "complete")) {
        injectBackupStorageIntoTab(tabId, u);
      }
    });
  }
  if (browser.tabs && browser.tabs.query) {
    browser.tabs.query({}).then((tabs) => {
      for (const tab of (tabs || [])) {
        if (tab && !tab.incognito && tab.id !== undefined && tab.url) {
          injectBackupStorageIntoTab(tab.id, tab.url);
        }
      }
    }).catch(() => {});
  }
}
