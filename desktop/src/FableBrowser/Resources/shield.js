(function (cfg) {
  "use strict";
  if (!cfg) return;
  var host = "";
  try { host = String(location.hostname || "").toLowerCase(); } catch (e) { host = ""; }
  var trusted = cfg.trusted || [];
  for (var t = 0; t < trusted.length; t++) {
    if (host === trusted[t] || host.slice(-(trusted[t].length + 1)) === "." + trusted[t]) return;
  }

  function hash32(text, seed) {
    var h = (seed >>> 0) ^ 0x811c9dc5;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h >>> 0;
  }

  function prng(seed) {
    var a = seed >>> 0;
    return function () {
      a = (a + 0x6d2b79f5) >>> 0;
      var r = Math.imul(a ^ (a >>> 15), 1 | a);
      r = (r + Math.imul(r ^ (r >>> 7), 61 | r)) ^ r;
      return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
    };
  }

  var baseSeed = hash32(host, cfg.seed >>> 0);

  function getter(proto, name, value) {
    if (!proto) return;
    var d = Object.getOwnPropertyDescriptor(proto, name);
    if (!d || typeof d.get !== "function") return;
    Object.defineProperty(proto, name, {
      get: function () { return value; },
      set: undefined,
      enumerable: d.enumerable,
      configurable: true
    });
  }

  if (cfg.canvas > 0) {
    var level = cfg.canvas;
    var noisify = function (data, w, h) {
      if (!data || !w || !h) return;
      var count = Math.min(64, Math.max(4, Math.floor((w * h) / 4096))) * level;
      var r = prng(baseSeed ^ Math.imul(w, 73856093) ^ Math.imul(h, 19349663));
      for (var k = 0; k < count; k++) {
        var p = Math.floor(r() * w * h) * 4 + Math.floor(r() * 3);
        if (p < data.length && data[p + (3 - (p % 4))] !== 0) data[p] = data[p] ^ 1;
      }
    };
    var ctx2d = window.CanvasRenderingContext2D && window.CanvasRenderingContext2D.prototype;
    var origGetImageData = ctx2d && ctx2d.getImageData;
    if (origGetImageData) {
      ctx2d.getImageData = function () {
        var img = origGetImageData.apply(this, arguments);
        try { noisify(img.data, img.width, img.height); } catch (e) {}
        return img;
      };
    }
    var off2d = window.OffscreenCanvasRenderingContext2D && window.OffscreenCanvasRenderingContext2D.prototype;
    var origOffGetImageData = off2d && off2d.getImageData;
    if (origOffGetImageData) {
      off2d.getImageData = function () {
        var img = origOffGetImageData.apply(this, arguments);
        try { noisify(img.data, img.width, img.height); } catch (e) {}
        return img;
      };
    }
    var canvasProto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
    var noisyCopy = function (canvas) {
      var w = canvas.width, h = canvas.height;
      if (!w || !h || w * h > 16777216 || !origGetImageData) return null;
      var copy = document.createElement("canvas");
      copy.width = w;
      copy.height = h;
      var c = copy.getContext("2d");
      if (!c) return null;
      c.drawImage(canvas, 0, 0);
      var img = origGetImageData.call(c, 0, 0, w, h);
      noisify(img.data, w, h);
      c.putImageData(img, 0, 0);
      return copy;
    };
    if (canvasProto && canvasProto.toDataURL) {
      var origToDataURL = canvasProto.toDataURL;
      canvasProto.toDataURL = function () {
        var copy = null;
        try { copy = noisyCopy(this); } catch (e) { copy = null; }
        return origToDataURL.apply(copy || this, arguments);
      };
    }
    if (canvasProto && canvasProto.toBlob) {
      var origToBlob = canvasProto.toBlob;
      canvasProto.toBlob = function () {
        var copy = null;
        try { copy = noisyCopy(this); } catch (e) { copy = null; }
        return origToBlob.apply(copy || this, arguments);
      };
    }
  }

  if (cfg.audio > 0) {
    var audioLevel = cfg.audio;
    var touched = new WeakSet();
    var bufferProto = window.AudioBuffer && window.AudioBuffer.prototype;
    if (bufferProto && bufferProto.getChannelData) {
      var origGetChannelData = bufferProto.getChannelData;
      bufferProto.getChannelData = function () {
        var data = origGetChannelData.apply(this, arguments);
        try {
          if (data && !touched.has(data)) {
            touched.add(data);
            var r = prng(baseSeed ^ data.length);
            for (var i = 0; i < data.length; i += 97) data[i] = data[i] + (r() - 0.5) * 1e-7 * audioLevel;
          }
        } catch (e) {}
        return data;
      };
    }
    var analyserProto = window.AnalyserNode && window.AnalyserNode.prototype;
    if (analyserProto && analyserProto.getFloatFrequencyData) {
      var origFreq = analyserProto.getFloatFrequencyData;
      analyserProto.getFloatFrequencyData = function (array) {
        var result = origFreq.apply(this, arguments);
        try {
          var r = prng(baseSeed ^ 0x9e3779b9);
          for (var i = 0; i < array.length; i += 13) array[i] = array[i] + (r() - 0.5) * 1e-4 * audioLevel;
        } catch (e) {}
        return result;
      };
    }
  }

  if (cfg.glVendor && cfg.glRenderer) {
    ["WebGLRenderingContext", "WebGL2RenderingContext"].forEach(function (name) {
      var proto = window[name] && window[name].prototype;
      if (!proto || !proto.getParameter) return;
      var origGetParameter = proto.getParameter;
      proto.getParameter = function (p) {
        if (p === 0x9245) return cfg.glVendor;
        if (p === 0x9246) return cfg.glRenderer;
        return origGetParameter.apply(this, arguments);
      };
    });
  }

  if (cfg.screen) {
    var s = cfg.screen;
    var screenProto = window.Screen && window.Screen.prototype;
    getter(screenProto, "width", s.width);
    getter(screenProto, "height", s.height);
    getter(screenProto, "availWidth", s.width);
    getter(screenProto, "availHeight", s.availHeight);
    getter(screenProto, "colorDepth", 24);
    getter(screenProto, "pixelDepth", 24);
  }

  if (cfg.cores > 0) {
    var navProto = window.Navigator && window.Navigator.prototype;
    getter(navProto, "hardwareConcurrency", cfg.cores);
    if (cfg.memory > 0) getter(navProto, "deviceMemory", cfg.memory);
  }

  if (cfg.webrtc) {
    ["RTCPeerConnection", "webkitRTCPeerConnection", "RTCDataChannel", "RTCSessionDescription", "RTCIceCandidate"].forEach(function (name) {
      try { delete window[name]; } catch (e) {}
    });
  }
})(__FABLE_CONFIG__);
