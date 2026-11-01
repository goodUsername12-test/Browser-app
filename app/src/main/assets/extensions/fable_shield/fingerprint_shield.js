(() => {
  if (globalThis.__fableShieldRan) return;
  globalThis.__fableShieldRan = true;

  const CFG = typeof FABLE_CFG === "object" && FABLE_CFG ? FABLE_CFG : null;
  if (!CFG) return;

  let PRIVATE = false;
  try {
    PRIVATE = browser.extension.inIncognitoContext === true;
  } catch (e) {
  }

  let UA = "";
  try {
    UA = navigator.userAgent;
  } catch (e) {
  }

  const SUPER = PRIVATE ? (CFG.supers || []).find((s) => s && s.ua === UA) || null : null;
  const BASIC = !SUPER && CFG.shield === true && (PRIVATE || CFG.normal === true);
  if (!SUPER && !BASIC) return;

  function hash32(str, seed) {
    let h = (seed ^ 0x9e3779b9) >>> 0;
    const s = String(str);
    for (let i = 0; i < s.length; i++) {
      h = Math.imul(h ^ s.charCodeAt(i), 0x85ebca6b);
      h ^= h >>> 13;
    }
    h = Math.imul(h ^ (h >>> 16), 0xc2b2ae35);
    return (h ^ (h >>> 16)) >>> 0;
  }

  function mix(a, b) {
    let h = Math.imul((a ^ Math.imul(b, 0x9e3779b1)) >>> 0, 0x27d4eb2d);
    h ^= h >>> 15;
    h = Math.imul(h, 0x165667b1);
    return (h ^ (h >>> 13)) >>> 0;
  }

  let HOST = "";
  try {
    HOST = location.hostname || location.protocol || "local";
  } catch (e) {
  }

  const BASE_SEED = SUPER ? Number(SUPER.seed) >>> 0 : Number(CFG.sessionSeed || 1) >>> 0;
  const SEED = hash32(HOST, BASE_SEED) || 1;
  const CANVAS_LEVEL = SUPER ? Math.max(1, Math.min(3, SUPER.canvasNoise | 0)) : 2;
  const AUDIO_LEVEL = SUPER ? Math.max(1, Math.min(3, SUPER.audioNoise | 0)) : 2;
  const TIME_ZONE = SUPER ? SUPER.timeZone || "" : CFG.utc ? "UTC" : "";
  const GENERIC = new Set([
    "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "ui-serif",
    "ui-sans-serif", "ui-monospace", "ui-rounded", "emoji", "math", "fangsong", "inherit", "initial",
  ]);
  const FONTS = SUPER && Array.isArray(SUPER.fonts) ? new Set(SUPER.fonts.map((f) => String(f).toLowerCase())) : null;

  function firstFamily(list) {
    if (!list) return "";
    const first = String(list).split(",")[0] || "";
    return first.trim().replace(/^["']|["']$/g, "").trim().toLowerCase();
  }

  function familiesOf(fontShorthand) {
    const s = String(fontShorthand || "");
    const m = s.match(/(?:^|\s)[\d.]+(?:px|pt|em|rem|%|ex|ch|vw|vh|q|mm|cm|in|pc)(?:\s*\/\s*\S+)?\s+(.+)$/i);
    const fam = m ? m[1] : s;
    return fam
      .split(",")
      .map((f) => f.trim().replace(/^["']|["']$/g, "").trim().toLowerCase())
      .filter(Boolean);
  }

  function claimedFont(families) {
    if (!FONTS) return "";
    for (const f of families) {
      if (GENERIC.has(f)) continue;
      if (FONTS.has(f)) return f;
      return "";
    }
    return "";
  }

  const done = new WeakSet();

  function install(win) {
    let W;
    try {
      W = win.wrappedJSObject || win;
      if (!W || done.has(W)) return;
      done.add(W);
      if (!W.document) return;
    } catch (e) {
      return;
    }

    const exp = (fn) => exportFunction(fn, win);

    function desc(obj, prop) {
      try {
        return obj ? Object.getOwnPropertyDescriptor(obj, prop) : undefined;
      } catch (e) {
        return undefined;
      }
    }

    function defGet(obj, prop, fn) {
      const d = desc(obj, prop);
      if (!d) return null;
      try {
        Object.defineProperty(obj, prop, {
          get: exp(fn),
          set: d.set,
          enumerable: d.enumerable,
          configurable: true,
        });
      } catch (e) {
      }
      return d.get || null;
    }

    function defValue(obj, prop, fn) {
      const d = desc(obj, prop);
      if (!d || typeof d.value !== "function") return null;
      try {
        Object.defineProperty(obj, prop, {
          value: exp(fn),
          writable: d.writable !== false,
          enumerable: d.enumerable,
          configurable: true,
        });
      } catch (e) {
      }
      return d.value;
    }

    function constant(obj, prop, value) {
      if (obj === W && !desc(W, prop)) {
        defGet(proto("Window"), prop, () => value);
        return;
      }
      defGet(obj, prop, () => value);
    }

    function pageProxy(target, handler) {
      return new W.Proxy(target, cloneInto(handler, win, { cloneFunctions: true }));
    }

    function waived(x) {
      try {
        return (x && x.wrappedJSObject) || x;
      } catch (e) {
        return x;
      }
    }

    function pageArray(list) {
      return cloneInto(list, win);
    }

    function proto(name) {
      try {
        return W[name] && W[name].prototype;
      } catch (e) {
        return null;
      }
    }

    function noiseBytes(data, length, salt) {
      const mod = [0, 101, 53, 29][CANVAS_LEVEL];
      const pixels = Math.floor(length / 4);
      if (pixels <= 0) return;
      const step = pixels > 1200000 ? 7 : 1;
      for (let p = salt % step; p < pixels; p += step) {
        const h = mix(SEED, p + salt);
        if (h % mod !== 0) continue;
        const i = p * 4;
        if (data[i + 3] === 0) continue;
        const c = i + ((h >>> 8) % 3);
        const v = data[c];
        data[c] = v >= 255 ? 254 : v <= 0 ? 1 : v + ((h >>> 12) & 1 ? 1 : -1);
      }
    }

    function shieldCanvas() {
      const C2D = proto("CanvasRenderingContext2D");
      const realGetImageData = C2D && desc(C2D, "getImageData") ? desc(C2D, "getImageData").value : null;
      const realPutImageData = C2D && desc(C2D, "putImageData") ? desc(C2D, "putImageData").value : null;

      function noisyCopy(canvas) {
        const w = canvas.width;
        const h = canvas.height;
        if (!w || !h || w * h > 4000000 || !realGetImageData) return null;
        const doc = win.document;
        const copy = doc.createElement("canvas");
        copy.width = w;
        copy.height = h;
        const ctx = copy.getContext("2d");
        if (!ctx) return null;
        ctx.drawImage(canvas, 0, 0);
        const img = realGetImageData.call(ctx, 0, 0, w, h);
        noiseBytes(img.data, img.data.length, 0);
        realPutImageData.call(ctx, img, 0, 0);
        return copy;
      }

      const HC = proto("HTMLCanvasElement");
      if (HC) {
        const origToDataURL = desc(HC, "toDataURL") && desc(HC, "toDataURL").value;
        const origToBlob = desc(HC, "toBlob") && desc(HC, "toBlob").value;
        if (origToDataURL) {
          defValue(HC, "toDataURL", function () {
            let target = this;
            try {
              target = noisyCopy(this) || this;
            } catch (e) {
              target = this;
            }
            return origToDataURL.apply(target, arguments);
          });
        }
        if (origToBlob) {
          defValue(HC, "toBlob", function () {
            let target = this;
            try {
              target = noisyCopy(this) || this;
            } catch (e) {
              target = this;
            }
            return origToBlob.apply(target, arguments);
          });
        }
      }

      const OC = proto("OffscreenCanvas");
      const O2D = proto("OffscreenCanvasRenderingContext2D");
      const realOffGet = O2D && desc(O2D, "getImageData") ? desc(O2D, "getImageData").value : null;
      const realOffPut = O2D && desc(O2D, "putImageData") ? desc(O2D, "putImageData").value : null;
      if (OC && W.OffscreenCanvas) {
        const origConvert = desc(OC, "convertToBlob") && desc(OC, "convertToBlob").value;
        if (origConvert) {
          defValue(OC, "convertToBlob", function () {
            let target = this;
            try {
              const w = this.width;
              const h = this.height;
              if (w && h && w * h <= 4000000 && realOffGet && realOffPut) {
                const copy = new W.OffscreenCanvas(w, h);
                const ctx = copy.getContext("2d");
                ctx.drawImage(this, 0, 0);
                const img = realOffGet.call(ctx, 0, 0, w, h);
                noiseBytes(img.data, img.data.length, 0);
                realOffPut.call(ctx, img, 0, 0);
                target = copy;
              }
            } catch (e) {
              target = this;
            }
            return origConvert.apply(target, arguments);
          });
        }
      }

      for (const name of ["CanvasRenderingContext2D", "OffscreenCanvasRenderingContext2D"]) {
        const P = proto(name);
        if (!P) continue;
        const orig = desc(P, "getImageData") && desc(P, "getImageData").value;
        if (!orig) continue;
        defValue(P, "getImageData", function (sx, sy, sw, sh, settings) {
          const img = orig.apply(this, arguments);
          try {
            const salt = ((Number(sx) | 0) * 7919 + (Number(sy) | 0)) >>> 0;
            noiseBytes(img.data, img.data.length, salt);
          } catch (e) {
          }
          return img;
        });
      }

      if (C2D) {
        const origMeasure = desc(C2D, "measureText") && desc(C2D, "measureText").value;
        if (origMeasure) {
          defValue(C2D, "measureText", function (text) {
            const m = origMeasure.apply(this, arguments);
            try {
              const font = this.font;
              const fam = claimedFont(familiesOf(font));
              let delta = 0;
              if (fam) delta += ((hash32(fam, SEED) % 7) + 1) * 0.0137;
              delta += (hash32(String(text).length + "|" + font, SEED) % 97) * 0.0000013;
              const width = m.width + delta;
              Object.defineProperty(m.wrappedJSObject || m, "width", {
                value: width,
                enumerable: true,
                configurable: true,
              });
            } catch (e) {
            }
            return m;
          });
        }
      }
    }

    function shieldWebGl(vendor, renderer, limits) {
      const caps = {
        0x0d33: "maxTexture",
        0x84e8: "maxRenderbuffer",
        0x8dfb: "maxVertexUniforms",
        0x8dfd: "maxFragmentUniforms",
        0x8dfc: "maxVaryings",
        0x8869: "maxAttribs",
        0x8872: "maxTextureUnits",
      };
      for (const name of ["WebGLRenderingContext", "WebGL2RenderingContext"]) {
        const P = proto(name);
        if (!P) continue;
        const orig = desc(P, "getParameter") && desc(P, "getParameter").value;
        if (orig) {
          defValue(P, "getParameter", function (pname) {
            const value = orig.apply(this, arguments);
            try {
              if (pname === 0x9245) return vendor;
              if (pname === 0x9246) return renderer;
              if (limits) {
                const key = caps[pname];
                if (key && typeof value === "number" && limits[key]) return Math.min(value, limits[key]);
                if (pname === 0x0d3a && value && limits.maxViewport) {
                  value[0] = Math.min(value[0], limits.maxViewport);
                  value[1] = Math.min(value[1], limits.maxViewport);
                }
              }
            } catch (e) {
            }
            return value;
          });
        }
        const origRead = desc(P, "readPixels") && desc(P, "readPixels").value;
        if (origRead) {
          defValue(P, "readPixels", function () {
            const r = origRead.apply(this, arguments);
            try {
              const out = arguments[6];
              if (out && typeof out === "object" && typeof out.length === "number" && out.length >= 4) {
                const n = Math.min(64 * CANVAS_LEVEL, Math.floor(out.length / 4));
                for (let k = 0; k < n; k++) {
                  const h = mix(SEED, k + 31);
                  const i = (h % Math.floor(out.length / 4)) * 4 + ((h >>> 9) % 3);
                  const v = out[i];
                  if (typeof v === "number" && v > 0 && v < 255) out[i] = v + ((h >>> 14) & 1 ? 1 : -1);
                }
              }
            } catch (e) {
            }
            return r;
          });
        }
      }
    }

    function shieldAudio() {
      const amp = 1e-7 * AUDIO_LEVEL;
      const seen = new WeakSet();
      function noiseFloats(arr, salt) {
        const len = arr.length;
        if (!len) return;
        const n = Math.min(len, 400 * AUDIO_LEVEL);
        for (let k = 0; k < n; k++) {
          const h = mix(SEED, k * 131 + salt);
          const i = h % len;
          arr[i] = arr[i] + (((h >>> 10) % 2001) / 1000 - 1) * amp;
        }
      }
      const AB = proto("AudioBuffer");
      if (AB) {
        const origGet = desc(AB, "getChannelData") && desc(AB, "getChannelData").value;
        if (origGet) {
          defValue(AB, "getChannelData", function (channel) {
            const data = origGet.apply(this, arguments);
            try {
              if (!seen.has(data)) {
                seen.add(data);
                noiseFloats(data, (Number(channel) | 0) + 7);
              }
            } catch (e) {
            }
            return data;
          });
        }
        const origCopy = desc(AB, "copyFromChannel") && desc(AB, "copyFromChannel").value;
        if (origCopy) {
          defValue(AB, "copyFromChannel", function (dest, channel) {
            const r = origCopy.apply(this, arguments);
            try {
              noiseFloats(dest, (Number(channel) | 0) + 11);
            } catch (e) {
            }
            return r;
          });
        }
      }
      const AN = proto("AnalyserNode");
      if (AN) {
        const origFreq = desc(AN, "getFloatFrequencyData") && desc(AN, "getFloatFrequencyData").value;
        if (origFreq) {
          defValue(AN, "getFloatFrequencyData", function (arr) {
            const r = origFreq.apply(this, arguments);
            try {
              const len = arr.length;
              for (let k = 0; k < Math.min(len, 64); k++) {
                const h = mix(SEED, k + 977);
                const i = h % len;
                arr[i] = arr[i] + (((h >>> 10) % 2001) / 1000 - 1) * 1e-4 * AUDIO_LEVEL;
              }
            } catch (e) {
            }
            return r;
          });
        }
      }
    }

    function shieldTimeZone(tz) {
      let fmt;
      let nameFmt;
      try {
        fmt = new Intl.DateTimeFormat("en-US", {
          timeZone: tz,
          hourCycle: "h23",
          year: "numeric",
          month: "numeric",
          day: "numeric",
          hour: "numeric",
          minute: "numeric",
          second: "numeric",
          era: "short",
        });
        nameFmt = new Intl.DateTimeFormat("en-US", { timeZone: tz, timeZoneName: "long" });
      } catch (e) {
        return;
      }
      const DP = W.Date && W.Date.prototype;
      if (!DP) return;
      const realGetTime = desc(DP, "getTime").value;
      const realSetTime = desc(DP, "setTime").value;
      const cache = new Map();

      function offsetMin(t) {
        if (!isFinite(t)) return 0;
        const key = Math.floor(t / 900000);
        const hit = cache.get(key);
        if (hit !== undefined) return hit;
        const p = {};
        for (const part of fmt.formatToParts(new Date(t))) p[part.type] = part.value;
        let year = Number(p.year);
        if (p.era && /^B/i.test(p.era)) year = 1 - year;
        const asUtc = Date.UTC(year, Number(p.month) - 1, Number(p.day), Number(p.hour) % 24, Number(p.minute), Number(p.second));
        const v = Math.round((asUtc - Math.floor(t / 1000) * 1000) / 60000);
        if (cache.size > 4000) cache.clear();
        cache.set(key, v);
        return v;
      }

      const tv = (d) => realGetTime.call(d);
      const shifted = (t) => new Date(t + offsetMin(t) * 60000);
      const toUtc = (localMs) => {
        let u = localMs - offsetMin(localMs) * 60000;
        u = localMs - offsetMin(u) * 60000;
        return u;
      };

      const getters = {
        getFullYear: "getUTCFullYear",
        getMonth: "getUTCMonth",
        getDate: "getUTCDate",
        getDay: "getUTCDay",
        getHours: "getUTCHours",
        getMinutes: "getUTCMinutes",
        getSeconds: "getUTCSeconds",
        getMilliseconds: "getUTCMilliseconds",
      };
      for (const name of Object.keys(getters)) {
        const utc = getters[name];
        defValue(DP, name, function () {
          const t = tv(this);
          if (isNaN(t)) return NaN;
          return shifted(t)[utc]();
        });
      }
      defValue(DP, "getYear", function () {
        const t = tv(this);
        if (isNaN(t)) return NaN;
        return shifted(t).getUTCFullYear() - 1900;
      });
      defValue(DP, "getTimezoneOffset", function () {
        const t = tv(this);
        if (isNaN(t)) return NaN;
        return -offsetMin(t);
      });

      const setters = {
        setFullYear: "setUTCFullYear",
        setMonth: "setUTCMonth",
        setDate: "setUTCDate",
        setHours: "setUTCHours",
        setMinutes: "setUTCMinutes",
        setSeconds: "setUTCSeconds",
        setMilliseconds: "setUTCMilliseconds",
      };
      for (const name of Object.keys(setters)) {
        const utc = setters[name];
        defValue(DP, name, function () {
          let t = tv(this);
          if (isNaN(t)) {
            if (name !== "setFullYear") return NaN;
            t = 0;
          }
          const local = shifted(t);
          const list = [];
          for (let i = 0; i < arguments.length; i++) list.push(Number(arguments[i]));
          local[utc].apply(local, list);
          const lm = local.getTime();
          if (isNaN(lm)) return realSetTime.call(this, NaN);
          return realSetTime.call(this, toUtc(lm));
        });
      }

      const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
      const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
      const pad = (n, w) => String(n).padStart(w || 2, "0");
      const yearStr = (y) => (y < 0 ? "-" + pad(-y, 6) : pad(y, 4));
      const offStr = (m) => "GMT" + (m >= 0 ? "+" : "-") + pad(Math.floor(Math.abs(m) / 60)) + pad(Math.abs(m) % 60);
      const tzName = (t) => {
        try {
          const part = nameFmt.formatToParts(new Date(t)).find((x) => x.type === "timeZoneName");
          return part ? part.value : tz;
        } catch (e) {
          return tz;
        }
      };
      const dateStr = (l) =>
        DAYS[l.getUTCDay()] + " " + MONTHS[l.getUTCMonth()] + " " + pad(l.getUTCDate()) + " " + yearStr(l.getUTCFullYear());
      const timeStr = (t, l) =>
        pad(l.getUTCHours()) + ":" + pad(l.getUTCMinutes()) + ":" + pad(l.getUTCSeconds()) + " " + offStr(offsetMin(t)) + " (" + tzName(t) + ")";
      const fullStr = (t) => {
        if (isNaN(t)) return "Invalid Date";
        const l = shifted(t);
        return dateStr(l) + " " + timeStr(t, l);
      };

      defValue(DP, "toString", function () {
        return fullStr(tv(this));
      });
      defValue(DP, "toDateString", function () {
        const t = tv(this);
        return isNaN(t) ? "Invalid Date" : dateStr(shifted(t));
      });
      defValue(DP, "toTimeString", function () {
        const t = tv(this);
        return isNaN(t) ? "Invalid Date" : timeStr(t, shifted(t));
      });

      function plainOptions(options) {
        const o = {};
        try {
          if (options && typeof options === "object") {
            for (const k of Object.keys(options)) o[k] = options[k];
          }
        } catch (e) {
        }
        return o;
      }

      function plainLocales(locales) {
        try {
          if (locales && typeof locales === "object") return Array.from(locales, (x) => String(x));
        } catch (e) {
        }
        return locales;
      }

      for (const name of ["toLocaleString", "toLocaleDateString", "toLocaleTimeString"]) {
        const realFn = Date.prototype[name];
        defValue(DP, name, function (locales, options) {
          const t = tv(this);
          if (isNaN(t)) return "Invalid Date";
          const o = plainOptions(options);
          if (o.timeZone === undefined) o.timeZone = tz;
          try {
            return realFn.call(new Date(t), plainLocales(locales), o);
          } catch (e) {
            return realFn.call(new Date(t));
          }
        });
      }

      try {
        const RealDTF = W.Intl.DateTimeFormat;
        const fix = (args) => {
          const locales = args.length > 0 ? plainLocales(args[0]) : undefined;
          const o = plainOptions(args.length > 1 ? args[1] : undefined);
          if (o.timeZone === undefined) o.timeZone = tz;
          return cloneInto([locales, o], win);
        };
        W.Intl.DateTimeFormat = pageProxy(RealDTF, {
          construct(target, args, newTarget) {
            return Reflect.construct(RealDTF, fix(args), waived(newTarget) || RealDTF);
          },
          apply(target, thisArg, args) {
            return Reflect.apply(RealDTF, waived(thisArg), fix(args));
          },
        });
        try {
          const probe = waived(waived(new W.Intl.DateTimeFormat()).resolvedOptions()).timeZone;
          if (probe !== tz) W.Intl.DateTimeFormat = RealDTF;
        } catch (e) {
          W.Intl.DateTimeFormat = RealDTF;
        }
      } catch (e) {
      }

      function parseLocal(str) {
        const s = String(str).trim();
        if (/^\d{4}(-\d{2}(-\d{2})?)?$/.test(s)) return null;
        if (/(Z|z|[+-]\d{2}:?\d{2}|GMT|UTC|UT)\b/.test(s) || /[+-]\d{2}:?\d{2}$/.test(s)) return null;
        const real = Date.parse(s);
        if (isNaN(real)) return null;
        const realOff = new Date(real).getTimezoneOffset();
        return toUtc(real - realOff * 60000);
      }

      try {
        const RealDate = W.Date;
        const realParse = desc(RealDate, "parse").value;
        defValue(RealDate, "parse", function (str) {
          const v = parseLocal(str);
          return v === null ? realParse.call(RealDate, str) : v;
        });
        W.Date = pageProxy(RealDate, {
          construct(target, args, newTarget) {
            try {
              return makeDate(args, newTarget);
            } catch (e) {
              return Reflect.construct(RealDate, waived(args), RealDate);
            }
          },
          apply() {
            return fullStr(Date.now());
          },
        });
        function makeDate(args, newTarget) {
          {
            if (args.length >= 2) {
              const n = [];
              for (let i = 0; i < args.length && i < 7; i++) n.push(Number(args[i]));
              while (n.length < 7) n.push(n.length === 2 ? 1 : 0);
              const lm = Date.UTC(n[0], n[1], n[2], n[3], n[4], n[5], n[6]);
              const ms = isNaN(lm) ? NaN : toUtc(lm);
              return Reflect.construct(RealDate, [ms], waived(newTarget) || RealDate);
            }
            if (args.length === 1 && typeof args[0] === "string") {
              const v = parseLocal(args[0]);
              if (v !== null) return Reflect.construct(RealDate, [v], waived(newTarget) || RealDate);
            }
            return Reflect.construct(RealDate, waived(args), waived(newTarget) || RealDate);
          }
        }
        try {
          const a = waived(new W.Date());
          const b = waived(new W.Date(2020, 0, 2, 3, 4, 5));
          const ok =
            typeof a.getTime() === "number" &&
            b.getFullYear() === 2020 &&
            b.getMonth() === 0 &&
            b.getDate() === 2 &&
            b.getHours() === 3 &&
            b.getMinutes() === 4 &&
            typeof W.Date.now() === "number";
          if (!ok) W.Date = RealDate;
        } catch (e) {
          W.Date = RealDate;
        }
      } catch (e) {
      }
    }

    function shieldFonts() {
      if (!FONTS) return;
      const HE = proto("HTMLElement");
      const fontDelta = (el, axis) => {
        try {
          if (el.firstElementChild) return 0;
          const st = el.style;
          if (!st) return 0;
          const fam = st.fontFamily;
          if (!fam) return 0;
          const first = firstFamily(fam);
          if (!first || GENERIC.has(first) || !FONTS.has(first)) return 0;
          return (hash32(first, SEED + axis) % 3) + 1;
        } catch (e) {
          return 0;
        }
      };
      const ow = desc(HE, "offsetWidth");
      const oh = desc(HE, "offsetHeight");
      if (ow && ow.get) {
        const g = ow.get;
        defGet(HE, "offsetWidth", function () {
          return g.call(this) + fontDelta(this, 0);
        });
      }
      if (oh && oh.get) {
        const g = oh.get;
        defGet(HE, "offsetHeight", function () {
          return g.call(this) + fontDelta(this, 1);
        });
      }
      const FS = proto("FontFaceSet");
      const origCheck = FS && desc(FS, "check") && desc(FS, "check").value;
      if (origCheck) {
        defValue(FS, "check", function (font) {
          try {
            if (claimedFont(familiesOf(font))) return true;
          } catch (e) {
          }
          return origCheck.apply(this, arguments);
        });
      }
    }

    function shieldRects() {
      const tweak = (v, salt) => {
        if (typeof v !== "number" || !isFinite(v) || Math.floor(v) === v) return v;
        return v + ((mix(SEED, salt) % 9) + 1) * 1e-6;
      };
      for (const name of ["Element", "Range"]) {
        const P = proto(name);
        const orig = P && desc(P, "getBoundingClientRect") && desc(P, "getBoundingClientRect").value;
        if (!orig) continue;
        defValue(P, "getBoundingClientRect", function () {
          const r = orig.apply(this, arguments);
          try {
            const w = r.width;
            const h = r.height;
            const w2 = tweak(w, 1 + Math.round(w * 10));
            const h2 = tweak(h, 2 + Math.round(h * 10));
            if (w2 === w && h2 === h) return r;
            return new W.DOMRect(r.x, r.y, w2, h2);
          } catch (e) {
            return r;
          }
        });
      }
    }

    function shieldVoices(share) {
      const SS = proto("SpeechSynthesis");
      const orig = SS && desc(SS, "getVoices") && desc(SS, "getVoices").value;
      if (!orig) return;
      defValue(SS, "getVoices", function () {
        const list = orig.apply(this, arguments);
        try {
          if (!list || list.length <= 1 || share >= 100) return list;
          const out = new W.Array();
          for (let i = 0; i < list.length; i++) {
            const v = list[i];
            if (i === 0 || hash32(v.name, SEED) % 100 < share) out.push(v);
          }
          return out;
        } catch (e) {
          return list;
        }
      });
    }

    function shieldWebRtc() {
      for (const name of ["RTCPeerConnection", "webkitRTCPeerConnection", "mozRTCPeerConnection"]) {
        try {
          const Real = W[name];
          if (typeof Real !== "function") continue;
          W[name] = pageProxy(Real, {
            construct(target, args, newTarget) {
              const o = {};
              try {
                const cfg = args.length > 0 ? args[0] : undefined;
                if (cfg && typeof cfg === "object") {
                  for (const k of Object.keys(cfg)) o[k] = cfg[k];
                }
              } catch (e) {
              }
              o.iceTransportPolicy = "relay";
              const rest = [];
              for (let i = 1; i < args.length; i++) rest.push(args[i]);
              return Reflect.construct(Real, cloneInto([o].concat(rest), win), waived(newTarget) || Real);
            },
          });
        } catch (e) {
        }
      }
    }

    function shieldFrames() {
      const IF = proto("HTMLIFrameElement");
      const cw = desc(IF, "contentWindow");
      const cd = desc(IF, "contentDocument");
      if (cw && cw.get) {
        const g = cw.get;
        defGet(IF, "contentWindow", function () {
          const w = g.call(this);
          try {
            if (w) install(w);
          } catch (e) {
          }
          return w;
        });
      }
      if (cd && cd.get) {
        const g = cd.get;
        defGet(IF, "contentDocument", function () {
          const d = g.call(this);
          try {
            if (d && d.defaultView) install(d.defaultView);
          } catch (e) {
          }
          return d;
        });
      }
    }

    try {
      const NP = proto("Navigator");
      const SP = proto("Screen");

      if (SUPER) {
        const S = SUPER;
        const langs = Array.isArray(S.languages) && S.languages.length ? S.languages : ["en-US", "en"];
        const frozen = W.Object.freeze(pageArray(langs));
        constant(NP, "userAgent", S.ua);
        constant(NP, "appVersion", S.appVersion);
        constant(NP, "platform", S.platform);
        if (S.oscpu) constant(NP, "oscpu", S.oscpu);
        else defGet(NP, "oscpu", () => undefined);
        constant(NP, "vendor", S.vendor || "");
        constant(NP, "hardwareConcurrency", S.cores);
        constant(NP, "maxTouchPoints", S.touchPoints);
        constant(NP, "language", langs[0]);
        defGet(NP, "languages", () => frozen);
        constant(NP, "doNotTrack", S.doNotTrack ? "1" : "unspecified");
        constant(NP, "pdfViewerEnabled", !!S.pdfViewer);
        constant(NP, "webdriver", false);
        const width = S.screenWidth;
        const height = S.screenHeight;
        constant(SP, "width", width);
        constant(SP, "height", height);
        constant(SP, "availWidth", width);
        constant(SP, "availHeight", S.availHeight || height);
        constant(SP, "availTop", S.availTop || 0);
        constant(SP, "availLeft", 0);
        constant(SP, "left", 0);
        constant(SP, "top", 0);
        constant(SP, "colorDepth", S.colorDepth);
        constant(SP, "pixelDepth", S.colorDepth);
        constant(W, "devicePixelRatio", S.pixelRatio);
        shieldCanvas();
        shieldWebGl(S.glVendor, S.glRenderer, S.glLimits || null);
        shieldAudio();
        shieldFonts();
        if (S.rectNoise) shieldRects();
        shieldVoices(Number(S.voiceShare) || 100);
        shieldWebRtc();
        if (TIME_ZONE) shieldTimeZone(TIME_ZONE);
      } else {
        const bucket = (v) => Math.max(100, Math.round(v / 100) * 100);
        let iw = 1280;
        let ih = 720;
        try {
          iw = win.innerWidth || 1280;
          ih = win.innerHeight || 720;
        } catch (e) {
        }
        const frozen = W.Object.freeze(pageArray(["en-US", "en"]));
        constant(SP, "width", bucket(iw));
        constant(SP, "height", bucket(ih));
        constant(SP, "availWidth", bucket(iw));
        constant(SP, "availHeight", bucket(ih));
        constant(SP, "colorDepth", 24);
        constant(SP, "pixelDepth", 24);
        constant(NP, "hardwareConcurrency", 8);
        constant(NP, "maxTouchPoints", 5);
        constant(NP, "doNotTrack", "1");
        constant(NP, "language", "en-US");
        defGet(NP, "languages", () => frozen);
        shieldCanvas();
        shieldWebGl("Mozilla", "Mozilla", null);
        shieldAudio();
        shieldWebRtc();
        if (TIME_ZONE) shieldTimeZone(TIME_ZONE);
      }
      shieldFrames();
    } catch (e) {
    }
  }

  try {
    install(window);
  } catch (e) {
  }

  if (SUPER && CFG.diag && window.top === window) {
    const report = () => {
      try {
        const W = window.wrappedJSObject;
        const n = W.navigator;
        const s = W.screen;
        let tz = "";
        try {
          const f = new W.Intl.DateTimeFormat();
          const r = (f.wrappedJSObject || f).resolvedOptions();
          tz = (r.wrappedJSObject || r).timeZone;
        } catch (e) {
          tz = "err";
        }
        let gl = "";
        try {
          const c = document.createElement("canvas");
          const g = c.getContext("webgl");
          if (g) gl = (g.wrappedJSObject || g).getParameter(0x9246);
        } catch (e) {
          gl = "err";
        }
        let off = "";
        try {
          const d = new W.Date(2026, 0, 15, 12, 0, 0);
          off = String((d.wrappedJSObject || d).getTimezoneOffset()) + "/" + String((d.wrappedJSObject || d).getHours());
        } catch (e) {
          off = "err";
        }
        let fc = "";
        try {
          const list = SUPER.fonts || [];
          const fam = list[list.length - 1];
          fc = fam + ":" + W.document.fonts.check("16px '" + fam + "'");
        } catch (e) {
          fc = "err";
        }
        let cv = "";
        try {
          const el = document.createElement("canvas");
          el.width = 60;
          el.height = 20;
          const cx = el.getContext("2d");
          cx.fillStyle = "#f60";
          cx.fillRect(0, 0, 60, 20);
          cx.fillStyle = "#069";
          cx.fillText("Fable", 2, 14);
          cv = String(hash32((el.wrappedJSObject || el).toDataURL(), 7));
        } catch (e) {
          cv = "err";
        }
        const text =
          "super page " + location.host +
          " fonts=" + fc +
          " canvas=" + cv +
          " expected=" + SUPER.label +
          " platform=" + n.platform +
          " cores=" + n.hardwareConcurrency +
          " touch=" + n.maxTouchPoints +
          " screen=" + s.width + "x" + s.height +
          " dpr=" + W.devicePixelRatio +
          " lang=" + n.languages.join("/") +
          " tz=" + tz +
          " offset=" + off +
          " gl=" + gl +
          " ua=" + String(n.userAgent).slice(0, 60);
        browser.runtime.sendMessage({ type: "fable-diag", text }).catch(() => {});
      } catch (e) {
        try {
          browser.runtime.sendMessage({ type: "fable-diag", text: "super diag error " + e }).catch(() => {});
        } catch (x) {
        }
      }
    };
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", report, { once: true });
    } else {
      report();
    }
  }
})();
