(() => {
  "use strict";

  const IS_PRIVATE = (() => {
    try {
      return browser.extension.inIncognitoContext === true;
    } catch (e) {
      return false;
    }
  })();

  let blockedCount = 0;

  function hashString(str) {
    let h = 2166136261 >>> 0;
    for (let i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 16777619) >>> 0;
    }
    return h >>> 0;
  }

  const SEED = hashString(location.origin + "|fable-shield");

  function makeRng(seed) {
    let s = seed >>> 0;
    return function () {
      s ^= s << 13;
      s >>>= 0;
      s ^= s >> 17;
      s ^= s << 5;
      s >>>= 0;
      return s / 4294967296;
    };
  }

  const rng = makeRng(SEED);

  function patch(target, name, factory) {
    try {
      const original = target[name];
      if (typeof original !== "function") return;
      const replacement = factory(original);
      Object.defineProperty(replacement, "name", { value: name });
      Object.defineProperty(replacement, "toString", {
        value: () => "function " + name + "() { [native code] }",
        writable: true,
        configurable: true,
      });
      target[name] = replacement;
    } catch (e) {
    }
  }

  function defineValue(target, name, value) {
    try {
      Object.defineProperty(target, name, {
        get: () => value,
        configurable: true,
      });
    } catch (e) {
    }
  }

  function shieldCanvas() {
    const noise = () => Math.floor(rng() * 3) - 1;

    patch(HTMLCanvasElement.prototype, "toDataURL", (orig) =>
      function (...args) {
        perturb(this);
        return orig.apply(this, args);
      }
    );

    patch(HTMLCanvasElement.prototype, "toBlob", (orig) =>
      function (...args) {
        perturb(this);
        return orig.apply(this, args);
      }
    );

    patch(CanvasRenderingContext2D.prototype, "getImageData", (orig) =>
      function (...args) {
        const data = orig.apply(this, args);
        const px = data.data;
        for (let i = 0; i < px.length; i += 4) {
          if ((i >> 2) % 977 === 0) {
            px[i] = clamp(px[i] + noise());
            px[i + 1] = clamp(px[i + 1] + noise());
            px[i + 2] = clamp(px[i + 2] + noise());
          }
        }
        return data;
      }
    );

    function clamp(v) {
      return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    function perturb(canvas) {
      try {
        const ctx = canvas.getContext("2d");
        if (!ctx || canvas.width === 0 || canvas.height === 0) return;
        const x = Math.floor(rng() * canvas.width);
        const y = Math.floor(rng() * canvas.height);
        ctx.save();
        ctx.globalAlpha = 0.01;
        ctx.fillStyle = "rgb(" + Math.floor(rng() * 256) + ",0,0)";
        ctx.fillRect(x, y, 1, 1);
        ctx.restore();
      } catch (e) {
      }
    }
  }

  function shieldWebGl() {
    const SPOOF = {
      37445: "Mozilla",
      37446: "Mozilla",
    };

    const contexts = [
      typeof WebGLRenderingContext !== "undefined" ? WebGLRenderingContext : null,
      typeof WebGL2RenderingContext !== "undefined" ? WebGL2RenderingContext : null,
    ].filter(Boolean);

    for (const Ctx of contexts) {
      patch(Ctx.prototype, "getParameter", (orig) =>
        function (param) {
          if (Object.prototype.hasOwnProperty.call(SPOOF, param)) return SPOOF[param];
          return orig.call(this, param);
        }
      );

      patch(Ctx.prototype, "getSupportedExtensions", (orig) =>
        function () {
          const list = orig.call(this) || [];
          return list.filter((e) => e !== "WEBGL_debug_renderer_info");
        }
      );

      patch(Ctx.prototype, "readPixels", (orig) =>
        function (...args) {
          const result = orig.apply(this, args);
          const buf = args[6];
          if (buf && buf.length) {
            for (let i = 0; i < buf.length; i += 4093) {
              buf[i] = buf[i] ^ (rng() < 0.5 ? 0 : 1);
            }
          }
          return result;
        }
      );
    }
  }

  function shieldAudio() {
    if (typeof AnalyserNode !== "undefined") {
      patch(AnalyserNode.prototype, "getFloatFrequencyData", (orig) =>
        function (array) {
          orig.call(this, array);
          for (let i = 0; i < array.length; i += 101) {
            array[i] += (rng() - 0.5) * 0.0002;
          }
        }
      );
    }
    if (typeof AudioBuffer !== "undefined") {
      patch(AudioBuffer.prototype, "getChannelData", (orig) =>
        function (channel) {
          const data = orig.call(this, channel);
          for (let i = 0; i < data.length; i += 1009) {
            data[i] += (rng() - 0.5) * 1e-7;
          }
          return data;
        }
      );
    }
  }

  function shieldScreen() {
    const bucket = (v) => Math.max(100, Math.round(v / 100) * 100);
    const w = bucket(window.innerWidth || 1280);
    const h = bucket(window.innerHeight || 720);

    defineValue(screen, "width", w);
    defineValue(screen, "height", h);
    defineValue(screen, "availWidth", w);
    defineValue(screen, "availHeight", h);
    defineValue(screen, "colorDepth", 24);
    defineValue(screen, "pixelDepth", 24);
    defineValue(window, "devicePixelRatio", 2);

    defineValue(navigator, "hardwareConcurrency", 8);
    if ("deviceMemory" in navigator) defineValue(navigator, "deviceMemory", 8);
    defineValue(navigator, "maxTouchPoints", 5);
    defineValue(navigator, "doNotTrack", "1");
    defineValue(navigator, "globalPrivacyControl", true);
    defineValue(navigator, "languages", ["en-US", "en"]);
    defineValue(navigator, "language", "en-US");

    try {
      defineValue(navigator, "plugins", Object.freeze([]));
      defineValue(navigator, "mimeTypes", Object.freeze([]));
    } catch (e) {
    }

    if (navigator.getBattery) {
      patch(navigator, "getBattery", () =>
        function () {
          return Promise.resolve({
            charging: true,
            chargingTime: 0,
            dischargingTime: Infinity,
            level: 1,
            addEventListener() {},
            removeEventListener() {},
          });
        }
      );
    }
  }

  function shieldTimezone() {
    patch(Date.prototype, "getTimezoneOffset", () =>
      function () {
        return 0;
      }
    );

    if (typeof Intl !== "undefined" && Intl.DateTimeFormat) {
      const OrigDTF = Intl.DateTimeFormat;
      const Patched = function (locales, options) {
        const opts = Object.assign({}, options, { timeZone: "UTC" });
        return new OrigDTF(locales || "en-US", opts);
      };
      Patched.prototype = OrigDTF.prototype;
      Patched.supportedLocalesOf = OrigDTF.supportedLocalesOf.bind(OrigDTF);
      try {
        Intl.DateTimeFormat = Patched;
      } catch (e) {
      }
    }
  }

  function shieldTimers() {
    patch(Performance.prototype, "now", (orig) =>
      function () {
        return Math.floor(orig.call(this) * 10) / 10;
      }
    );
  }

  function shieldWebRtc() {
    const Ctors = ["RTCPeerConnection", "webkitRTCPeerConnection", "mozRTCPeerConnection"];
    for (const name of Ctors) {
      if (typeof window[name] !== "function") continue;
      const Orig = window[name];
      function Guarded(cfg, ...rest) {
        const safe = Object.assign({}, cfg, {
          iceServers: [],
          iceTransportPolicy: "relay",
        });
        const pc = new Orig(safe, ...rest);
        const origAdd = pc.addIceCandidate.bind(pc);
        pc.addIceCandidate = function (candidate, ...args) {
          const text = candidate && (candidate.candidate || "");
          if (/(\b10\.|\b192\.168\.|\b172\.(1[6-9]|2\d|3[01])\.|\.local)/.test(text)) {
            return Promise.resolve();
          }
          return origAdd(candidate, ...args);
        };
        return pc;
      }
      Guarded.prototype = Orig.prototype;
      try {
        window[name] = Guarded;
      } catch (e) {
      }
    }
  }

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

  browser.runtime
    .sendMessage({ type: "fable-get-config" })
    .then((cfg) => {
      if (!cfg) return;
      if (cfg.fingerprintShield && (IS_PRIVATE || cfg.normalTabShield)) {
        shieldCanvas();
        shieldWebGl();
        shieldAudio();
        shieldScreen();
        shieldTimers();
        shieldWebRtc();
        if (cfg.forceUtc) shieldTimezone();
      }
    })
    .catch(() => {});
})();
