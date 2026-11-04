(function () {
  "use strict";
  var params = new URLSearchParams(location.search);
  var mode = params.get("mode") || "normal";
  if (mode !== "incognito" && mode !== "super") mode = "normal";
  document.body.className = mode;
  var notes = {
    normal: "",
    incognito: "Incognito. Nothing from this tab is written to disk, and it is wiped when the tab closes.",
    super: "Super incognito. This tab has its own made-up device and its own cookies and storage."
  };
  document.getElementById("note").textContent = notes[mode];
  if (mode === "incognito") document.getElementById("title").textContent = "Incognito";
  if (mode === "super") document.getElementById("title").textContent = "Super incognito";
  var bridge = window.chrome && window.chrome.webview;
  var palette = ["#6d36c2", "#ff2e7e", "#ff7417", "#2e9bff", "#1fb57a", "#c2368f", "#3b4bd6", "#d6823b"];

  function colorFor(text) {
    var h = 0;
    for (var i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0;
    return palette[h % palette.length];
  }

  function render(items) {
    var root = document.getElementById("tiles");
    root.textContent = "";
    items.slice(0, 12).forEach(function (item) {
      if (!item || typeof item.url !== "string" || !/^https?:/i.test(item.url)) return;
      var a = document.createElement("a");
      a.className = "tile";
      a.href = item.url;
      a.title = item.title || item.url;
      var label = String(item.label || item.title || item.url);
      var badge = document.createElement("span");
      badge.className = "badge";
      badge.style.background = colorFor(label);
      badge.textContent = label.charAt(0).toUpperCase();
      var text = document.createElement("span");
      text.className = "label";
      text.textContent = label;
      a.appendChild(badge);
      a.appendChild(text);
      root.appendChild(a);
    });
  }

  document.getElementById("search").addEventListener("submit", function (e) {
    e.preventDefault();
    var text = document.getElementById("query").value.trim();
    if (text && bridge) bridge.postMessage({ type: "open", text: text });
  });

  if (bridge) {
    bridge.addEventListener("message", function (e) {
      var data = e.data;
      if (data && data.type === "tiles" && Array.isArray(data.items)) render(data.items);
    });
    bridge.postMessage({ type: "ready", mode: mode });
  }
})();
