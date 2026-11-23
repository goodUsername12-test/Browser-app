#!/usr/bin/env bash
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(cat .fable-version 2>/dev/null || echo unknown)"
REPORT_ISSUE="${FABLE_REPORT_ISSUE:-4}"
ART="${RUNNER_TEMP:-/tmp}/hook-artifacts"; mkdir -p "$ART"
REPORT="${RUNNER_TEMP:-/tmp}/hook-report.md"; : > "$REPORT"
PKG="com.fable.browser.debug"
ACT="$PKG/com.fable.browser.MainActivity"

log() { echo "[hook] $*"; }
post() {
  if [ -n "${GITHUB_REPOSITORY:-}" ] && command -v gh >/dev/null 2>&1; then
    gh issue comment "$REPORT_ISSUE" --repo "$GITHUB_REPOSITORY" --body-file "$1" >/dev/null 2>&1 || log "could not post $1 to #$REPORT_ISSUE"
  fi
}

{ echo "HOOK-START $VERSION Fable Browser emulator verification started (API 34 x86_64)"; } > "$ART/start.md"; post "$ART/start.md"

LOCAL_BLOXD_OK="yes"
[ -f local-bloxd/src/main/assets/bloxd/local_bloxd_engine.js ] || LOCAL_BLOXD_OK="MISSING"

APK_ARM_DBG="$(ls app/build/outputs/apk/debug/*arm64-v8a*.apk 2>/dev/null | head -n 1)"
APK_ARM_REL="$(ls app/build/outputs/apk/release/*arm64-v8a*.apk 2>/dev/null | head -n 1)"

log "Building :app x86_64 debug APK for Android emulator"
if ! ./gradlew :app:assembleDebug -Pfable.abis=x86_64 -q > "$ART/x86-build.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION FAILED: :app x86_64 build"; echo '```'; tail -n 80 "$ART/x86-build.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi
APK_X86_DBG="$(ls app/build/outputs/apk/debug/*x86_64*.apk 2>/dev/null | head -n 1)"
if [ -z "$APK_X86_DBG" ] || [ ! -f "$APK_X86_DBG" ]; then
  { echo "HOOK-RESULT $VERSION FAILED: x86_64 debug APK not found"; } > "$REPORT"
  post "$REPORT"; exit 1
fi

AUDIT_SUMMARY=""
for entry in "arm64-debug:$APK_ARM_DBG:arm64-v8a" "arm64-release:$APK_ARM_REL:arm64-v8a" "x86_64-debug:$APK_X86_DBG:x86_64"; do
  IFS=: read -r lbl path abi <<< "$entry"
  if [ -n "$path" ] && [ -f "$path" ]; then
    sz="$(stat -c %s "$path" 2>/dev/null || wc -c < "$path")"
    cpp="$(unzip -l "$path" 2>/dev/null | grep -c "lib/${abi}/libfable_fast.so" || true)"
    ext="$(unzip -l "$path" 2>/dev/null | grep -c "assets/extensions/fable_shield/blocker.js" || true)"
    AUDIT_SUMMARY="${AUDIT_SUMMARY}\n- ${lbl}: $(basename "$path") (${sz}B) cpp=${cpp} shield=${ext}"
  else
    AUDIT_SUMMARY="${AUDIT_SUMMARY}\n- ${lbl}: not present"
  fi
done
AUDIT_SUMMARY="${AUDIT_SUMMARY}\n- local-bloxd-intact: ${LOCAL_BLOXD_OK}"

cat <<'PYSRV' > "$ART/test_server.py"
import base64, http.server, json, socketserver, urllib.parse

MIN_PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
MIN_WASM = bytes([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00])
MIN_WOFF2 = b"wOF2" + b"\x00" * 44

INDEX_HTML = """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Fable Benchmark & Capture Test</title>
  <link rel="stylesheet" href="/style.css">
</head>
<body>
  <h1>Fable Browser Verification Page</h1>
  <img id="logo" src="/logo.png" width="32" height="32">
  <canvas id="c2d" width="128" height="128"></canvas>
  <pre id="status">loading</pre>
  <script src="/app.js"></script>
</body>
</html>"""

STYLE_CSS = """@font-face { font-family: 'FableTestFont'; src: url('/font.woff2') format('woff2'); font-display: swap; }
body { font-family: 'FableTestFont', sans-serif; background: #101820; color: #f1f5f9; }"""

APP_JS = """(async function() {
  window.__fableTestIntegrity = "UNTOUCHED_ORIGINAL_CODE";
  try {
    const c = document.getElementById("c2d");
    const ctx = c.getContext("2d");
    ctx.fillStyle = "#22c55e"; ctx.fillRect(0, 0, 128, 128);
    ctx.fillStyle = "#ffffff"; ctx.fillText("FABLE", 20, 64);
  } catch (e) {}
  try { localStorage.setItem("fable_ls_key", "ls_ok_v56"); sessionStorage.setItem("fable_ss_key", "ss_ok_v56"); } catch (e) {}
  try {
    const req = indexedDB.open("fable_test_db", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("kv");
    req.onsuccess = () => {
      const tx = req.result.transaction("kv", "readwrite");
      tx.objectStore("kv").put({ hello: "world", ver: 56 }, "k1");
    };
  } catch (e) {}
  const apiRes = await fetch("/api/data.json").then(r => r.json()).catch(() => ({}));
  const wasmBuf = await fetch("/module.wasm").then(r => r.arrayBuffer()).catch(() => new ArrayBuffer(0));
  let frames = 0;
  function tick() {
    frames++;
    const ctx = document.getElementById("c2d").getContext("2d");
    ctx.fillStyle = (frames % 2 === 0) ? "#22c55e" : "#16a34a";
    ctx.fillRect(0, 0, 16, 16);
    if (frames < 180) requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
  document.getElementById("status").textContent = JSON.stringify({
    integrity: window.__fableTestIntegrity,
    apiOk: apiRes.ok === true,
    wasmBytes: wasmBuf.byteLength
  });
  console.log("FABLE-PAGE-READY " + document.getElementById("status").textContent);
})();"""

PRIVACY_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Privacy Probe</title></head>
<body><pre id="out">probing</pre>
<script>
(function() {
  const unauthClip = (window.FableHybridBridge && window.FableHybridBridge.getClipboardText)
    ? String(window.FableHybridBridge.getClipboardText() || "") : "NO_BRIDGE";
  const res = {
    href: location.href,
    gpc: navigator.globalPrivacyControl === true,
    dnt: navigator.doNotTrack || "",
    hasHybridMarker: typeof window.__fableHybridEngine !== "undefined",
    unauthClipBlocked: unauthClip === "" || unauthClip === "NO_BRIDGE",
    hasBattery: typeof navigator.getBattery === "function"
  };
  document.getElementById("out").textContent = JSON.stringify(res);
  console.log("FABLE-PRIVACY-PROBE " + JSON.stringify(res));
})();
</script>
<script src="/dynamic/extra.json"></script>
</body></html>"""

MEM_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Fable Memory Test</title></head>
<body>
<pre id="out">ready</pre>
<script>
(function() {
  window.gameState = { gold: 133700, hp: 500 };
  window.testWasmMem = new WebAssembly.Memory({ initial: 1 });
  new Int32Array(window.testWasmMem.buffer)[16] = 133700;
  function checkStealth() {
    try {
      const noLeak = Object.getOwnPropertyNames(window).filter(k => /cheat|memscan|__fableMem|__fableTimeScale/i.test(k)).length === 0;
      const wasmNat = Function.prototype.toString.call(WebAssembly.Memory).indexOf("[native code]") !== -1;
      const instNat = Function.prototype.toString.call(WebAssembly.instantiate).indexOf("[native code]") !== -1;
      return noLeak && wasmNat && instNat;
    } catch (_) { return false; }
  }
  const qs = new URLSearchParams(location.search);
  const tag = qs.get("tag") || "mem";
  window.addEventListener("load", function() {
    setTimeout(function() {
      const wVal = new Int32Array(window.testWasmMem.buffer)[16];
      const gVal = window.gameState.gold;
      const st = checkStealth();
      fetch("/mem-probe?tag=" + encodeURIComponent(tag) + "&stealth=" + st + "&wasm=" + wVal + "&gold=" + gVal).catch(() => {});
    }, 3800);
  });
})();
</script></body></html>"""

SPEED_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Fable Speed Test</title>
<style>
@keyframes cubeMove { from { transform: translateX(0px); } to { transform: translateX(300px); } }
#cube { width: 48px; height: 48px; background: #38bdf8; animation: cubeMove 1s linear infinite; }
</style></head>
<body>
<div id="cube"></div>
<pre id="out">running</pre>
<script>
(function() {
  const qs = new URLSearchParams(location.search);
  const tag = qs.get("tag") || "run";
  const timerMs = Number(qs.get("ms") || "250");
  let animRate = 1.0;
  function readAnimRate() {
    try {
      const anims = document.getAnimations ? document.getAnimations() : [];
      if (anims.length > 0) return Number(anims[0].playbackRate.toFixed(2));
    } catch (_) {}
    return 1.0;
  }
  function checkStealth() {
    try {
      const noMarker = typeof window.__fableTimeScale === "undefined";
      const noOwnPerf = Object.getOwnPropertyDescriptor(performance, "now") === undefined;
      const stNat = Function.prototype.toString.call(setTimeout).indexOf("[native code]") !== -1;
      const dnNat = Function.prototype.toString.call(Date.now).indexOf("[native code]") !== -1;
      const pnNat = Function.prototype.toString.call(performance.now).indexOf("[native code]") !== -1;
      const rafNat = Function.prototype.toString.call(requestAnimationFrame).indexOf("[native code]") !== -1;
      const dateOk = (new Date() instanceof Date) && (new Date().constructor === Date);
      return noMarker && noOwnPerf && stNat && dnNat && pnNat && rafNat && dateOk;
    } catch (_) {
      return false;
    }
  }
  animRate = readAnimRate();
  const tStartPerf = performance.now();
  const tStartDate = Date.now();
  const afterOff = qs.get("after_off") === "1";
  function finish() {
    const vPerfDelta = Math.round(performance.now() - tStartPerf);
    const vDateDelta = Math.round(Date.now() - tStartDate);
    animRate = readAnimRate();
    const stealth = checkStealth();
    const res = { tag, stealth, animRate, timerMs, vPerfDelta, vDateDelta };
    document.getElementById("out").textContent = JSON.stringify(res);
    console.log("FABLE-SPEED-PROBE " + JSON.stringify(res));
    fetch("/speed-done?tag=" + encodeURIComponent(tag) + "&stealth=" + stealth + "&anim=" + animRate + "&ms=" + timerMs).catch(() => {});
  }
  if (afterOff) {
    // Wait until live injection turns speed ON without refreshing, then run a timer under the live engine
    let tries = 0;
    const iv = setInterval(function() {
      tries++;
      if (readAnimRate() !== 1.0 || tries >= 35) {
        clearInterval(iv);
        setTimeout(finish, timerMs);
      }
    }, 150);
  } else {
    setTimeout(finish, timerMs);
  }
})();
</script></body></html>"""

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass
    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        p = parsed.path
        if p == "/index.html" or p == "/":
            self._send(200, "text/html; charset=utf-8", INDEX_HTML.encode())
        elif p == "/style.css":
            self._send(200, "text/css; charset=utf-8", STYLE_CSS.encode())
        elif p == "/app.js":
            self._send(200, "application/javascript; charset=utf-8", APP_JS.encode())
        elif p == "/logo.png":
            self._send(200, "image/png", MIN_PNG)
        elif p == "/font.woff2":
            self._send(200, "font/woff2", MIN_WOFF2)
        elif p == "/module.wasm":
            self._send(200, "application/wasm", MIN_WASM)
        elif p == "/api/data.json":
            self._send(200, "application/json", json.dumps({"ok": True, "items": [1, 2, 3], "engine": "fable"}).encode())
        elif p == "/dynamic/extra.json":
            self._send(200, "application/json", json.dumps({"dynamic": True, "ts": 56}).encode())
        elif p == "/privacy.html":
            hdrs = {k.lower(): v for k, v in self.headers.items()}
            print(f"REQ_PRIVACY path={self.path} gpc={hdrs.get('sec-gpc','')} dnt={hdrs.get('dnt','')} xff={hdrs.get('x-forwarded-for','')}", flush=True)
            self._send(200, "text/html; charset=utf-8", PRIVACY_HTML.encode())
        elif p == "/cookie-set":
            print("REQ_COOKIE_SET", flush=True)
            body = b"<html><body>Cookie set</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Set-Cookie", "fable_persist=ok_v56; Path=/; Max-Age=86400; SameSite=Lax")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif p == "/speed.html":
            qs = urllib.parse.parse_qs(parsed.query)
            tag = qs.get("tag", ["run"])[0]
            import time
            print(f"SPEED_START tag={tag} ts={time.monotonic():.3f}", flush=True)
            self._send(200, "text/html; charset=utf-8", SPEED_HTML.encode())
        elif p == "/mem.html":
            self._send(200, "text/html; charset=utf-8", MEM_HTML.encode())
        elif p == "/mem-probe":
            print(f"MEM_PROBE q={parsed.query}", flush=True)
            self._send(200, "application/json", b'{"ok":true}')
        elif p == "/speed-done":
            qs = urllib.parse.parse_qs(parsed.query)
            tag = qs.get("tag", ["run"])[0]
            import time
            print(f"SPEED_DONE tag={tag} ts={time.monotonic():.3f} q={parsed.query}", flush=True)
            self._send(200, "application/json", b'{"ok":true}')
        elif p == "/cookie-check":
            ck = self.headers.get("Cookie", "")
            ok = "fable_persist=ok_v56" in ck
            print(f"REQ_COOKIE_CHECK ok={ok} cookie={ck}", flush=True)
            self._send(200, "text/html; charset=utf-8", f"<html><body>COOKIE_PERSIST={ok}</body></html>".encode())
        else:
            self._send(404, "text/plain", b"not found")
    def _send(self, code, ctype, data):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

with socketserver.TCPServer(("127.0.0.1", 18080), Handler) as httpd:
    print("TEST_SERVER_READY 18080", flush=True)
    httpd.serve_forever()
PYSRV

python3 "$ART/test_server.py" > "$ART/server.log" 2>&1 &
SRV_PID=$!
sleep 1

log "Enabling KVM"
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/emulator:$SDK/platform-tools:$PATH"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
export ANDROID_USER_HOME="$HOME/.android" ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"

yes | sdkmanager --licenses > "$ART/licenses.log" 2>&1 || true
AVD_OK=""
for IMG in "system-images;android-34;google_apis;x86_64" "system-images;android-33;google_apis;x86_64" "system-images;android-34;default;x86_64"; do
  log "Installing $IMG"
  timeout 360 sdkmanager --install "$IMG" "emulator" "platform-tools" >> "$ART/sdkmanager.log" 2>&1 || true
  IMG_DIR="$SDK/$(echo "$IMG" | tr ';' '/')"
  if [ ! -d "$IMG_DIR" ]; then
    sudo -E env "PATH=$PATH" timeout 300 sdkmanager --install "$IMG" "emulator" >> "$ART/sdkmanager.log" 2>&1 || true
    sudo chown -R "$(id -u):$(id -g)" "$SDK" >/dev/null 2>&1 || true
  fi
  echo no | avdmanager create avd -n fable -k "$IMG" --force >> "$ART/avdmanager.log" 2>&1 || true
  if { emulator -list-avds 2>/dev/null; avdmanager list avd -c 2>/dev/null; } | grep -qx fable; then AVD_OK="$IMG"; break; fi
done
if [ -z "$AVD_OK" ]; then
  { echo "HOOK-RESULT $VERSION FAILED: no AVD"; tail -n 30 "$ART/sdkmanager.log"; } > "$REPORT"
  post "$REPORT"; kill "$SRV_PID" >/dev/null 2>&1 || true; exit 1
fi

./gradlew --stop >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1 || true

BOOTED=""
EMU_PID=""
GPU_USED=""
for GPU_MODE in lavapipe swangle_indirect; do
  log "Booting emulator with -gpu $GPU_MODE"
  : > "$ART/emulator.log"
  emulator -avd fable -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$GPU_MODE" \
    -camera-back none -camera-front none -memory 4096 -cores 4 > "$ART/emulator.log" 2>&1 &
  EMU_PID=$!
  timeout 180 adb wait-for-device || true
  for i in $(seq 1 120); do
    kill -0 "$EMU_PID" 2>/dev/null || break
    if [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      BOOTED="1"; GPU_USED="$GPU_MODE"; break
    fi
    sleep 2
  done
  [ "$BOOTED" = "1" ] && kill -0 "$EMU_PID" 2>/dev/null && break
  kill "$EMU_PID" >/dev/null 2>&1 || true; sleep 2
done

if [ "$BOOTED" != "1" ]; then
  { echo "HOOK-RESULT $VERSION FAILED: emulator did not boot"; tail -n 60 "$ART/emulator.log"; } > "$REPORT"
  post "$REPORT"; kill "$SRV_PID" >/dev/null 2>&1 || true; exit 1
fi

adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell input keyevent 82 || true
adb reverse tcp:18080 tcp:18080 || true
sleep 6

if ! timeout 240 adb install -r -g "$APK_X86_DBG" > "$ART/install.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION FAILED: adb install"; cat "$ART/install.log"; } > "$REPORT"
  post "$REPORT"; kill "$EMU_PID" "$SRV_PID" >/dev/null 2>&1 || true; exit 1
fi

shot() {
  timeout 15 adb exec-out screencap -p > "$ART/$1.png" 2>/dev/null || return
  if command -v convert >/dev/null 2>&1; then
    convert "$ART/$1.png" -resize 360x -quality 60 "$ART/$1.jpg" 2>/dev/null
    { echo "HOOK-SHOT $VERSION $1"; base64 -w 76 "$ART/$1.jpg"; } > "$ART/$1.shot"
    post "$ART/$1.shot"
  fi
}
alive() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

STEPS=""
STEP_FAIL=0
step() {
  local pid; pid="$(alive)"
  if [ -n "$pid" ]; then
    STEPS="${STEPS}\n- $1: PASS (pid=${pid})"
  else
    STEPS="${STEPS}\n- $1: FAIL (NOT RUNNING)"
    STEP_FAIL=1
  fi
}

adb logcat -c

log "Phase 1: Cold & Warm Launch Benchmark"
timeout 90 adb shell am start -W -n "$ACT" > "$ART/cold-start-1.txt" 2>&1
sleep 14; step "cold start #1 (new tab)"; shot 1-cold-start
adb shell am start -n "$ACT" --ez fable_test_perf_hud true >/dev/null 2>&1 || true
sleep 2

log "Phase 2: Multi-Asset Page Load (GeckoView & Blink Combined)"
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/index.html" -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 14; step "load local multi-asset page (GeckoView)"; shot 2-gecko-page

timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/index.html" --ez fable_hybrid true -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 14; step "load local multi-asset page (Blink Combined)"; shot 3-blink-page
adb shell am start -n "$ACT" --ez fable_perf_dump true >/dev/null 2>&1 || true
sleep 2
adb shell dumpsys gfxinfo "$PKG" > "$ART/gfxinfo.txt" 2>/dev/null || true

log "Phase 3: Privacy ON vs OFF Verification"
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/privacy.html?utm_source=ci_on&fbclid=abc123&keep=1' -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 12; step "privacy ON probe (GeckoView)"
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/privacy.html?utm_source=ci_blink_on&fbclid=xyz&keep=2' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 12; step "privacy ON probe (Blink Combined)"; shot 4-privacy-on

adb shell am start -n "$ACT" --ez fable_test_adblock false >/dev/null 2>&1 || true
sleep 3
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/privacy.html?utm_source=ci_off&keep=3' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 12; step "privacy OFF probe (verifying optional toggles)"
adb shell am start -n "$ACT" --ez fable_test_adblock true >/dev/null 2>&1 || true
sleep 2

log "Phase 4: Zero-Mutation Full Page Code ZIP Collector"
adb shell ls /sdcard/Download > "$ART/dl-before-code.txt" 2>/dev/null || true
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/index.html" -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 12
adb shell am start -n "$ACT" --ez fable_save_page_code true >/dev/null 2>&1 || true
sleep 25; shot 5-after-page-code
adb shell ls /sdcard/Download > "$ART/dl-after-code.txt" 2>/dev/null || true
CODE_ZIP="$(comm -13 <(sort "$ART/dl-before-code.txt") <(sort "$ART/dl-after-code.txt") | tr -d '\r' | grep -i '\.zip$' | head -n 1)"
CODE_ZIP_REPORT="NO ZIP CREATED"
if [ -n "$CODE_ZIP" ]; then
  adb pull "/sdcard/Download/$CODE_ZIP" "$ART/page-code.zip" >/dev/null 2>&1 || true
  CODE_ZIP_REPORT="$(python3 - "$ART/page-code.zip" <<'PYZIP'
import json, sys, zipfile
try:
    z = zipfile.ZipFile(sys.argv[1])
    names = z.namelist()
    man = json.loads(z.read("capture-manifest.json").decode()) if "capture-manifest.json" in names else {}
    has_js = any(n.endswith(".js") for n in names)
    has_css = any(n.endswith(".css") for n in names)
    has_png = any(n.endswith(".png") for n in names)
    has_wasm = any(n.endswith(".wasm") for n in names)
    has_json = any("data.json" in n for n in names)
    has_har = any(n.endswith(".har") for n in names)
    has_ls = any("local-storage.json" in n for n in names)
    has_journal = "capture-journal.jsonl" in names
    tot = sum(i.file_size for i in z.infolist())
    print(f"OK {len(names)} files ({tot//1024}KB) schema={man.get('schema')} status={man.get('status')} zeroMut={man.get('zeroMutation')} cookies={man.get('containsSensitiveData')} js={has_js} css={has_css} png={has_png} wasm={has_wasm} api={has_json} har={has_har} ls={has_ls} journal={has_journal}")
except Exception as e:
    print(f"BAD ZIP: {e}")
PYZIP
)"
fi
step "zero-mutation full page ZIP ($CODE_ZIP_REPORT)"

log "Phase 5: Controllable Live Memory Snapshot (Start -> Pause -> Resume -> Stop & Save)"
adb shell ls /sdcard/Download > "$ART/dl-before-snap.txt" 2>/dev/null || true
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/index.html" --ez fable_hybrid true -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 10
adb shell am start -n "$ACT" --ez fable_start_snapshot true >/dev/null 2>&1 || true
sleep 6; shot 6-snap-capturing
adb shell am start -n "$ACT" --ez fable_pause_capture true >/dev/null 2>&1 || true
sleep 3; shot 7-snap-paused
adb shell am start -n "$ACT" --ez fable_resume_capture true >/dev/null 2>&1 || true
sleep 3
adb shell am start -n "$ACT" --ez fable_stop_capture true >/dev/null 2>&1 || true
sleep 14; shot 8-snap-saved
adb shell ls /sdcard/Download > "$ART/dl-after-snap.txt" 2>/dev/null || true
SNAP_ZIP="$(comm -13 <(sort "$ART/dl-before-snap.txt") <(sort "$ART/dl-after-snap.txt") | tr -d '\r' | grep -i '\.zip$' | head -n 1)"
SNAP_ZIP_REPORT="NO SNAPSHOT ZIP CREATED"
if [ -n "$SNAP_ZIP" ]; then
  adb pull "/sdcard/Download/$SNAP_ZIP" "$ART/snapshot.zip" >/dev/null 2>&1 || true
  SNAP_ZIP_REPORT="$(python3 - "$ART/snapshot.zip" <<'PYSNAP'
import json, sys, zipfile
try:
    z = zipfile.ZipFile(sys.argv[1])
    names = z.namelist()
    man = json.loads(z.read("capture-manifest.json").decode()) if "capture-manifest.json" in names else {}
    tot = sum(i.file_size for i in z.infolist())
    print(f"OK {len(names)} files ({tot//1024}KB) schema={man.get('schema')} status={man.get('status')} continuous={man.get('continuous')} zeroMut={man.get('zeroMutation')} journal={'capture-journal.jsonl' in names}")
except Exception as e:
    print(f"BAD SNAPSHOT ZIP: {e}")
PYSNAP
)"
fi
step "controllable live snapshot Pause/Resume/Stop ($SNAP_ZIP_REPORT)"

log "Phase 6: Website Speed Control v58 (OFF -> 0.1x -> 3.5x -> Live Blink no-refresh -> 300x -> OFF + stealth)"
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=off_blink&ms=300' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 5; step "speed OFF zero-load (Blink)"

# Start Blink page while speed is OFF, then turn ON 10x LIVE without refreshing!
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=off_to_live_blink&ms=2000&after_off=1' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 2
adb shell am start -n "$ACT" --ez fable_test_timescale true --ei fable_test_timescale_tenths 100 >/dev/null 2>&1 || true
sleep 4; step "speed OFF->10x LIVE no-refresh (Blink)"

adb shell am start -n "$ACT" --ez fable_test_timescale true --ei fable_test_timescale_tenths 1 >/dev/null 2>&1 || true
sleep 3
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=slow_gecko&ms=200' -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 7; step "speed 0.1x slow (GeckoView)"
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=slow_blink&ms=200' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 7; step "speed 0.1x slow (Blink)"; shot 8b-speed-01x

# Exact fractional 3.5x (35 tenths) in Blink Combined
adb shell am start -n "$ACT" --ez fable_test_timescale true --ei fable_test_timescale_tenths 35 >/dev/null 2>&1 || true
sleep 3
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=exact35_blink&ms=3500' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 5; step "speed 3.5x exact (Blink)"

# LIVE no-refresh test in Blink x Gecko: load 8000ms timer at 0.1x (would take 80s!), then switch LIVE to 300x WITHOUT reloading!
adb shell am start -n "$ACT" --ez fable_test_timescale true --ei fable_test_timescale_tenths 1 >/dev/null 2>&1 || true
sleep 2
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=live_blink_noreload&ms=8000' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 2
adb shell am start -n "$ACT" --ez fable_test_timescale true --ei fable_test_timescale_tenths 3000 >/dev/null 2>&1 || true
sleep 4; step "speed LIVE 0.1x->300x no-refresh (Blink)"

# Max 300x on fresh GeckoView & Blink pages
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=turbo300_gecko&ms=5000' -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 5; step "speed 300x max 5s timer (GeckoView)"
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=turbo300_blink&ms=5000' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 5; step "speed 300x max 5s timer (Blink)"

adb shell am start -n "$ACT" --ez fable_test_timescale false --ei fable_test_timescale_tenths 10 >/dev/null 2>&1 || true
sleep 3
timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/speed.html?tag=off_after_gecko&ms=200' -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 5; step "speed back OFF unloaded (GeckoView)"


log "Phase 6b: Memory Engine CE (Native C++ self-process + GeckoView WASM/JS + Blink x Gecko WASM/JS + Stealth)"
adb shell am start -n "$ACT" --ez fable_test_mem_toggle true >/dev/null 2>&1 || true
sleep 2
adb shell am start -n "$ACT" --es fable_test_mem_selfcheck native >/dev/null 2>&1 || true
sleep 3; step "memory engine native C++ self-scan & write"

timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/mem.html?tag=mem_gecko' -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 2
adb shell am start -n "$ACT" --es fable_test_mem_selfcheck page_gecko >/dev/null 2>&1 || true
sleep 5; step "memory engine GeckoView WASM+JS scan & write"

timeout 90 adb shell "am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18080/mem.html?tag=mem_blink' --ez fable_hybrid true -n '$ACT'" >> "$ART/nav.txt" 2>&1
sleep 2
adb shell am start -n "$ACT" --es fable_test_mem_selfcheck page_blink >/dev/null 2>&1 || true
sleep 5; step "memory engine Blink x Gecko WASM+JS scan & write"

adb shell am start -n "$ACT" --ez fable_test_mem_toggle false >/dev/null 2>&1 || true
sleep 2

log "Phase 7: Cookie Persistence across Force-Stop"
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/cookie-set" -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 8
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/cookie-set" --ez fable_hybrid true -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 10
adb shell input keyevent KEYCODE_HOME; sleep 4
adb shell am force-stop "$PKG"; sleep 2
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:18080/cookie-check" --ez fable_hybrid true -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 8; step "cookie persistence after force-stop"

log "Phase 7: Live Web Smoke + Background/Foreground x3"
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://example.com" -n "$ACT" >> "$ART/nav.txt" 2>&1
sleep 10
for n in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME; sleep 3
  timeout 90 adb shell am start -W -n "$ACT" >> "$ART/nav.txt" 2>&1; sleep 3
done
step "background/foreground lifecycle x3"; shot 9-final

adb logcat -d -v brief > "$ART/logcat.txt" 2>/dev/null || true
adb logcat -d -b events -v brief 2>/dev/null | grep -E "(am_anr|am_crash|am_proc_died).*fable" > "$ART/events.txt" || true
FREEZE="$(adb shell run-as "$PKG" cat files/freeze_report.txt 2>/dev/null | head -c 4000 || true)"

APP_CRASHES="$(grep -E "am_crash.*fable|am_anr.*fable" "$ART/events.txt" | wc -l | tr -d ' ')"
FATAL_JAVA="$(awk '/FATAL EXCEPTION/{flag=1; buf=$0"\n"; next} flag{buf=buf $0"\n"; if(NF==0 || /^[^ \tE]/){if(buf ~ /com\.fable\.browser/) print buf; flag=0}} END{if(flag && buf ~ /com\.fable\.browser/) print buf}' "$ART/logcat.txt")"
FATAL_NATIVE="$(grep -E "Fatal signal.*fable|Abort message.*fable" "$ART/logcat.txt" || true)"
PERF_LINES="$(grep -E "FABLE-PERF" "$ART/logcat.txt" | tail -n 25)"
CAPTURE_LINES="$(grep -E "FABLE-CAPTURE" "$ART/logcat.txt" | tail -n 20)"
PRIVACY_LINES="$(grep -E "FABLE-PRIVACY-PROBE|FABLE-PRIVACY-TEST|FABLE-PAGE-READY|FABLE-SPEED-PROBE|FABLE-SPEED-TEST|FABLE-INTENT" "$ART/logcat.txt" | tail -n 55)"
GFX_SUMMARY="$(grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" "$ART/gfxinfo.txt" | head -n 12)"

OVERALL_FAIL=0
FAIL_REASONS=""
[ "$STEP_FAIL" -ne 0 ] && OVERALL_FAIL=1 && FAIL_REASONS="${FAIL_REASONS} process-died"
[ "${APP_CRASHES:-0}" -gt 0 ] && OVERALL_FAIL=1 && FAIL_REASONS="${FAIL_REASONS} am_crash/anr(${APP_CRASHES})"
[ -n "$FATAL_JAVA" ] && OVERALL_FAIL=1 && FAIL_REASONS="${FAIL_REASONS} fatal-java"
[ -n "$FATAL_NATIVE" ] && OVERALL_FAIL=1 && FAIL_REASONS="${FAIL_REASONS} fatal-native"
[ "$LOCAL_BLOXD_OK" != "yes" ] && OVERALL_FAIL=1 && FAIL_REASONS="${FAIL_REASONS} local-bloxd-missing"
echo "$CODE_ZIP_REPORT" | grep -q "^OK " || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} page-code-zip"; }
echo "$SNAP_ZIP_REPORT" | grep -q "^OK " || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} snapshot-zip"; }
grep -q "REQ_COOKIE_CHECK ok=True" "$ART/server.log" || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} cookie-persist"; }
grep -q "REQ_PRIVACY path=/privacy.html?keep=2 gpc=1" "$ART/server.log" || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} blink-privacy-on-hdr"; }
grep -q 'FABLE-PRIVACY-PROBE.*"gpc":true.*"hasHybridMarker":false.*"hasBattery":false' "$ART/logcat.txt" || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} blink-privacy-on-js"; }
grep -q 'FABLE-PRIVACY-PROBE.*"gpc":false.*"hasHybridMarker":true.*"hasBattery":true' "$ART/logcat.txt" || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} blink-privacy-off-js"; }
SPEED_CHECK="$(python3 - "$ART/server.log" <<'PYSPEED'
import re, sys
txt = open(sys.argv[1]).read()
starts = {m.group(1): float(m.group(2)) for m in re.finditer(r'SPEED_START tag=(\S+) ts=([0-9.]+)', txt)}
dones = {m.group(1): (float(m.group(2)), m.group(3)) for m in re.finditer(r'SPEED_DONE tag=(\S+) ts=([0-9.]+) q=(\S+)', txt)}
errs = []
def check(tag, min_dt, max_dt, anim_sub=None, require_stealth=True):
    if tag not in dones or tag not in starts:
        errs.append(f"missing:{tag}")
        return
    dt = dones[tag][0] - starts[tag]
    q = dones[tag][1]
    if require_stealth and "stealth=true" not in q:
        errs.append(f"{tag}:stealth_failed:{q}")
    if anim_sub and anim_sub not in q:
        errs.append(f"{tag}:{anim_sub}_not_in_{q}")
    if not (min_dt <= dt <= max_dt):
        errs.append(f"{tag}:dt={dt:.2f}s_out_of_[{min_dt},{max_dt}]")
check("off_blink", 0.15, 3.0, "anim=1&")
check("off_to_live_blink", 0.5, 5.5, "anim=10&")
check("slow_gecko", 1.3, 6.5, "anim=0.1&")
check("slow_blink", 1.3, 6.5, "anim=0.1&")
check("exact35_blink", 0.5, 3.0, "anim=3.5&")
check("live_blink_noreload", 0.8, 6.0, "anim=300&")
check("turbo300_gecko", 0.005, 2.0, "anim=300&")
check("turbo300_blink", 0.005, 2.0, "anim=300&")
check("off_after_gecko", 0.05, 3.0, "anim=1&")
if errs:
    print("FAIL " + " ".join(errs))
else:
    keys = ["off_blink","off_to_live_blink","slow_gecko","slow_blink","exact35_blink","live_blink_noreload","turbo300_gecko","turbo300_blink","off_after_gecko"]
    parts = [f"{k}={dones[k][0]-starts[k]:.2f}s" for k in keys]
    print("OK " + " ".join(parts))
PYSPEED
)"
echo "$SPEED_CHECK" | grep -q "^OK " || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} speed-control(${SPEED_CHECK})"; }


MEM_NATIVE_LINE="$(grep 'FABLE-MEM-CHECK.*scope=native' "$ART/logcat.txt" | tail -n 1 || true)"
MEM_GECKO_LINE="$(grep 'FABLE-MEM-CHECK.*scope=page_gecko' "$ART/logcat.txt" | tail -n 1 || true)"
MEM_BLINK_LINE="$(grep 'FABLE-MEM-CHECK.*scope=page_blink' "$ART/logcat.txt" | tail -n 1 || true)"
echo "$MEM_NATIVE_LINE" | grep -q 'okReg=true.*writeOk=true readBack=987654321 hexOk=true' || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} mem-native(${MEM_NATIVE_LINE:-missing})"; }
echo "$MEM_GECKO_LINE" | grep -q 'engine=GeckoView total=[2-9].*hexOk=true' || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} mem-gecko(${MEM_GECKO_LINE:-missing})"; }
echo "$MEM_BLINK_LINE" | grep -q 'engine=Blink x Gecko total=[2-9].*hexOk=true' || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} mem-blink(${MEM_BLINK_LINE:-missing})"; }
grep -q 'MEM_PROBE q=tag=mem_gecko&stealth=true' "$ART/server.log" || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} mem-gecko-stealth"; }
grep -q 'MEM_PROBE q=tag=mem_blink&stealth=true' "$ART/server.log" || { OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} mem-blink-stealth"; }

VERDICT="PASS"
[ "$OVERALL_FAIL" -ne 0 ] && VERDICT="FAIL (${FAIL_REASONS# })"

{
  echo "HOOK-RESULT $VERSION $VERDICT — Fable Browser API 34 x86_64 Emulator (gpu=$GPU_USED)"
  echo ""
  echo "### APK Audit"
  printf '%b\n' "$AUDIT_SUMMARY"
  echo ""
  echo "### Verification Steps"
  printf '%b\n' "$STEPS"
  echo ""
  echo "### Cold Start (am start -W)"
  echo '```'
  grep -E "Status|LaunchState|TotalTime|WaitTime" "$ART/cold-start-1.txt" || true
  echo '```'
  echo "### Performance Telemetry (FABLE-PERF + gfxinfo)"
  echo '```'
  echo "${PERF_LINES:-(none)}"
  echo "--- gfxinfo ---"
  echo "${GFX_SUMMARY:-(none)}"
  echo '```'
  echo "### Memory Engine (Mobile CE) Verification"
  echo '```'
  echo "Native: ${MEM_NATIVE_LINE:-(none)}"
  echo "Gecko:  ${MEM_GECKO_LINE:-(none)}"
  echo "Blink:  ${MEM_BLINK_LINE:-(none)}"
  echo '```'
  echo "### Website Speed Control Verification"
  echo '```'
  echo "Speed Check: $SPEED_CHECK"
  echo '```'
  echo "### Privacy ON/OFF & Server Verification"
  echo '```'
  cat "$ART/server.log" | tail -n 25
  echo "--- JS probes ---"
  echo "${PRIVACY_LINES:-(none)}"
  echo '```'
  echo "### Capture & Controllable Snapshot Telemetry"
  echo '```'
  echo "Page Code ZIP: $CODE_ZIP_REPORT"
  echo "Snapshot ZIP:  $SNAP_ZIP_REPORT"
  echo "${CAPTURE_LINES:-(none)}"
  echo '```'
  echo "### Freeze / Crash Check"
  echo '```'
  echo "AppCrashEvents: $APP_CRASHES | FreezeReport: ${FREEZE:-none}"
  [ -s "$ART/events.txt" ] && head -n 20 "$ART/events.txt"
  [ -n "$FATAL_JAVA" ] && printf '%s\n' "$FATAL_JAVA" | head -n 60
  [ -n "$FATAL_NATIVE" ] && printf '%s\n' "$FATAL_NATIVE" | head -n 40
  echo '```'
} > "$REPORT"

post "$REPORT"
adb emu kill >/dev/null 2>&1 || true
kill "$EMU_PID" "$SRV_PID" >/dev/null 2>&1 || true
exit "$OVERALL_FAIL"
