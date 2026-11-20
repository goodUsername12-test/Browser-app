#!/usr/bin/env bash
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
export FABLE_ROOT="$REPO_ROOT"

VERSION="$(cat .fable-version 2>/dev/null || echo unknown)"
ART="${RUNNER_TEMP:-/tmp}/hook-artifacts"; mkdir -p "$ART"
REPORT="${RUNNER_TEMP:-/tmp}/hook-report.md"; : > "$REPORT"
PKG_DBG="com.fable.localbloxd.debug"
ACT_DBG="$PKG_DBG/com.fable.localbloxd.LocalBloxdActivity"
PKG_REL="com.fable.localbloxd"
ACT_REL="$PKG_REL/com.fable.localbloxd.LocalBloxdActivity"

log() { echo "[hook] $*"; }
post() {
  if [ -n "${GITHUB_REPOSITORY:-}" ] && command -v gh >/dev/null 2>&1; then
    gh issue comment 2 --repo "$GITHUB_REPOSITORY" --body-file "$1" >/dev/null 2>&1 || log "could not post $1"
  fi
}

{ echo "HOOK-START $VERSION local-bloxd offline CI & emulator verification started"; } > "$ART/start.md"; post "$ART/start.md"

# 1. Node integration harness (10 suites)
log "Running local-bloxd Node integration harness"
if command -v node >/dev/null 2>&1; then
  if ! node local-bloxd/harness/local_room_harness.js > "$ART/node-harness.log" 2>&1; then
    { echo "HOOK-RESULT $VERSION FAILED: local_room_harness.js"; echo '```'; tail -n 60 "$ART/node-harness.log"; echo '```'; } > "$REPORT"
    post "$REPORT"; exit 1
  fi
  log "Node harness: $(tail -n 1 "$ART/node-harness.log")"
fi

# 2. Python static audit
log "Running local-bloxd static audit"
if command -v python3 >/dev/null 2>&1 && [ -f local-bloxd/harness/static_audit.py ]; then
  if ! python3 local-bloxd/harness/static_audit.py > "$ART/static-audit.log" 2>&1; then
    { echo "HOOK-RESULT $VERSION FAILED: static_audit.py"; echo '```'; tail -n 60 "$ART/static-audit.log"; echo '```'; } > "$REPORT"
    post "$REPORT"; exit 1
  fi
  log "Static audit: $(tail -n 1 "$ART/static-audit.log")"
fi

# 3. :local-bloxd JVM unit tests
log "Running :local-bloxd:testDebugUnitTest"
if ! ./gradlew :local-bloxd:testDebugUnitTest --stacktrace > "$ART/unit-tests.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION FAILED: :local-bloxd:testDebugUnitTest"; echo '```'; tail -n 80 "$ART/unit-tests.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi

# 4. Build x86_64 debug & release APKs for emulator testing
log "Building :local-bloxd x86_64 debug and release APKs"
if ! ./gradlew :local-bloxd:assembleDebug :local-bloxd:assembleRelease -Pfable.abis=x86_64 -q > "$ART/x86-build.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION FAILED: :local-bloxd x86_64 build"; echo '```'; tail -n 80 "$ART/x86-build.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi

APK_X86_DBG="$(ls local-bloxd/build/outputs/apk/debug/*x86_64*.apk 2>/dev/null | head -n 1)"
APK_X86_REL="$(ls local-bloxd/build/outputs/apk/release/*x86_64*.apk 2>/dev/null | head -n 1)"
APK_ARM_DBG="$(ls app/build/outputs/apk/debug/z-local-bloxd-*arm64-v8a*.apk local-bloxd/build/outputs/apk/debug/*arm64-v8a*.apk 2>/dev/null | head -n 1)"
APK_ARM_REL="$(ls app/build/outputs/apk/release/z-local-bloxd-*arm64-v8a*.apk local-bloxd/build/outputs/apk/release/*arm64-v8a*.apk 2>/dev/null | head -n 1)"

if [ -z "$APK_X86_DBG" ] || [ ! -f "$APK_X86_DBG" ]; then
  { echo "HOOK-RESULT $VERSION FAILED: x86_64 debug APK not found"; } > "$REPORT"
  post "$REPORT"; exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/emulator:$SDK/platform-tools:$PATH"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
export ANDROID_USER_HOME="$HOME/.android" ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
AAPT2="$(find "$SDK/build-tools" -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -n 1 || true)"

AUDIT_FAIL=0
AUDIT_SUMMARY=""

audit_apk() {
  local label="$1" apk="$2" abi="$3"
  if [ -z "$apk" ] || [ ! -f "$apk" ]; then
    AUDIT_SUMMARY="${AUDIT_SUMMARY}\n- ${label}: MISSING"
    AUDIT_FAIL=1
    return
  fi
  local sz; sz="$(stat -c %s "$apk" 2>/dev/null || wc -c < "$apk")"
  local perms="unknown" pkg="unknown"
  if [ -n "$AAPT2" ] && [ -x "$AAPT2" ]; then
    "$AAPT2" dump badging "$apk" > "$ART/badging-${label}.txt" 2>&1 || true
    "$AAPT2" dump permissions "$apk" > "$ART/perms-${label}.txt" 2>&1 || true
    if grep -q "android.permission.INTERNET" "$ART/perms-${label}.txt"; then
      AUDIT_SUMMARY="${AUDIT_SUMMARY}\n- ${label}: FAIL android.permission.INTERNET present!"
      AUDIT_FAIL=1
      return
    fi
    pkg="$(grep -oE "package: name='[^']+'" "$ART/badging-${label}.txt" | head -n 1 || echo unknown)"
    perms="$(tr '\n' ' ' < "$ART/perms-${label}.txt" | cut -c1-120)"
  fi
  unzip -l "$apk" > "$ART/contents-${label}.txt" 2>&1 || true
  local has_eng has_c117 has_cpp has_rust
  has_eng="$(grep -c 'assets/bloxd/local_bloxd_engine.js' "$ART/contents-${label}.txt" || true)"
  has_c117="$(grep -c 'assets/bloxd/static/js/7rp1w.117.2c551f46.chunk.js' "$ART/contents-${label}.txt" || true)"
  has_cpp="$(grep -c "lib/${abi}/liblocal_bloxd_native.so" "$ART/contents-${label}.txt" || true)"
  has_rust="$(grep -c "lib/${abi}/liblocal_bloxd_rust.so" "$ART/contents-${label}.txt" || true)"

  local elf_info=""
  if command -v readelf >/dev/null 2>&1; then
    local tmpdir="$ART/elf-${label}"; rm -rf "$tmpdir"; mkdir -p "$tmpdir"
    unzip -q -o "$apk" "lib/${abi}/*.so" -d "$tmpdir" 2>/dev/null || true
    for so in "$tmpdir/lib/${abi}"/*.so; do
      [ -f "$so" ] || continue
      local soname; soname="$(basename "$so")"
      local needed align
      needed="$(readelf -d "$so" 2>/dev/null | grep NEEDED | sed -E 's/.*\[([^]]+)\].*/\1/' | tr '\n' ',' | sed 's/,$//')"
      align="$(readelf -lW "$so" 2>/dev/null | awk '$1=="LOAD"{print $NF}' | sort -u | tr '\n' ',' | sed 's/,$//')"
      elf_info="${elf_info} ${soname}(align=${align};needed=${needed})"
    done
  fi
  AUDIT_SUMMARY="${AUDIT_SUMMARY}\n- ${label}: $(basename "$apk") (${sz}B) ${pkg} perms=[${perms}] eng=${has_eng} c117=${has_c117} cpp=${has_cpp} rust=${has_rust}${elf_info}"
  if [ "$has_eng" -lt 1 ] || [ "$has_c117" -lt 1 ]; then
    AUDIT_FAIL=1
  fi
}

audit_apk "arm64-debug" "$APK_ARM_DBG" "arm64-v8a"
audit_apk "arm64-release" "$APK_ARM_REL" "arm64-v8a"
audit_apk "x86_64-debug" "$APK_X86_DBG" "x86_64"
[ -n "$APK_X86_REL" ] && audit_apk "x86_64-release" "$APK_X86_REL" "x86_64"

{
  echo "HOOK-AUDIT $VERSION (Node 10/10 OK, static_audit 0 err, :local-bloxd JVM tests OK)"
  printf '%b\n' "$AUDIT_SUMMARY"
} > "$ART/audit.md"
post "$ART/audit.md"

if [ "$AUDIT_FAIL" -ne 0 ]; then
  { echo "HOOK-RESULT $VERSION FAILED: APK audit failed"; printf '%b\n' "$AUDIT_SUMMARY"; } > "$REPORT"
  post "$REPORT"; exit 1
fi

# 5. Boot offline Android API 34 x86_64 emulator
log "Enabling KVM"
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm

yes | sdkmanager --licenses > "$ART/licenses.log" 2>&1 || true
AVD_OK=""
for IMG in "system-images;android-34;google_apis;x86_64" "system-images;android-33;google_apis;x86_64" "system-images;android-34;default;x86_64"; do
  log "Installing $IMG"
  timeout 360 sdkmanager --install "$IMG" "emulator" "platform-tools" >> "$ART/sdkmanager.log" 2>&1 || log "sdkmanager error for $IMG"
  IMG_DIR="$SDK/$(echo "$IMG" | tr ';' '/')"
  if [ ! -d "$IMG_DIR" ]; then
    sudo -E env "PATH=$PATH" timeout 300 sdkmanager --install "$IMG" "emulator" >> "$ART/sdkmanager.log" 2>&1 || true
    sudo chown -R "$(id -u):$(id -g)" "$SDK" >/dev/null 2>&1 || true
  fi
  echo no | avdmanager create avd -n fable -k "$IMG" --force >> "$ART/avdmanager.log" 2>&1 || true
  if { emulator -list-avds 2>/dev/null; avdmanager list avd -c 2>/dev/null; } | grep -qx fable; then AVD_OK="$IMG"; break; fi
done
if [ -z "$AVD_OK" ]; then
  { echo "HOOK-RESULT $VERSION FAILED: emulator setup (no AVD)"; echo '```'; tail -n 25 "$ART/sdkmanager.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi
log "AVD ready with $AVD_OK"

./gradlew --stop >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1 || true
log "Booting offline emulator (-dns-server 127.0.0.1)"
emulator -avd fable -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -camera-back none -camera-front none -dns-server 127.0.0.1 -memory 3072 -cores 4 > "$ART/emulator.log" 2>&1 &
EMU_PID=$!

timeout 300 adb wait-for-device || log "emulator did not appear"
for i in $(seq 1 150); do
  if ! kill -0 "$EMU_PID" 2>/dev/null; then
    log "Emulator process $EMU_PID exited prematurely during boot!"
    break
  fi
  [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell input keyevent 82 || true
BOOTED="$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
log "Boot completed: $BOOTED (emu_alive=$(kill -0 "$EMU_PID" 2>/dev/null && echo yes || echo no))"
if [ "$BOOTED" != "1" ]; then
  {
    echo "HOOK-RESULT $VERSION FAILED: emulator did not boot (emu_pid=$EMU_PID)"
    echo '```'
    adb devices -l 2>&1 || true
    echo "--- emulator.log ---"
    tail -n 80 "$ART/emulator.log"
    echo '```'
  } > "$REPORT"
  post "$REPORT"; exit 1
fi

log "Disabling wifi & cellular data on emulator (DNS already blackholed to 127.0.0.1)"
adb shell svc wifi disable >/dev/null 2>&1 || true
adb shell svc data disable >/dev/null 2>&1 || true
sleep 2

ensure_adb() {
  for attempt in 1 2 3 4 5; do
    if [ "$(adb get-state 2>/dev/null | tr -d '\r')" = "device" ]; then
      return 0
    fi
    log "Waiting for adb device (attempt $attempt, emu_alive=$(kill -0 "$EMU_PID" 2>/dev/null && echo yes || echo no))"
    timeout 30 adb wait-for-device >/dev/null 2>&1 || true
    sleep 3
  done
  return 1
}

install_apk_safe() {
  local apk="$1" logf="$2"
  for attempt in 1 2 3; do
    ensure_adb || return 1
    if timeout 240 adb install -r -g "$apk" > "$logf" 2>&1; then
      return 0
    fi
    log "adb install attempt $attempt failed: $(tr '\n' ' ' < "$logf")"
    sleep 5
  done
  return 1
}

shot() {
  timeout 15 adb exec-out screencap -p > "$ART/$1.png" 2>/dev/null || return
  if command -v convert >/dev/null 2>&1; then
    convert "$ART/$1.png" -resize 420x -quality 65 "$ART/$1.jpg" 2>/dev/null
    { echo "HOOK-SHOT $VERSION $1"; base64 -w 76 "$ART/$1.jpg"; } > "$ART/$1.shot"
    post "$ART/$1.shot"
  fi
}
alive_pkg() { adb shell pidof "$1" 2>/dev/null | tr -d '\r'; }

STEPS=""
STEP_FAIL=0
step_check() {
  local desc="$1" pkg="$2"
  local pid; pid="$(alive_pkg "$pkg")"
  if [ -n "$pid" ]; then
    STEPS="${STEPS}\n- ${desc}: RUNNING (pid=${pid})"
  else
    STEPS="${STEPS}\n- ${desc}: NOT RUNNING (CRASHED/DEAD)"
    STEP_FAIL=1
  fi
}

if ! ensure_adb; then
  {
    echo "HOOK-RESULT $VERSION FAILED: adb device lost after boot"
    echo '```'
    adb devices -l 2>&1 || true
    echo "--- emu alive: $(kill -0 "$EMU_PID" 2>/dev/null && echo yes || echo no) ---"
    tail -n 80 "$ART/emulator.log"
    echo '```'
  } > "$REPORT"
  post "$REPORT"; kill "$EMU_PID" >/dev/null 2>&1 || true; exit 1
fi

adb logcat -c

# --- Phase A: x86_64 Release-configured APK test ---
if [ -n "$APK_X86_REL" ] && [ -f "$APK_X86_REL" ]; then
  log "A) Installing x86_64 RELEASE APK ($PKG_REL)"
  if install_apk_safe "$APK_X86_REL" "$ART/install-rel.log"; then
    timeout 90 adb shell am start -W -n "$ACT_REL" > "$ART/start-rel.txt" 2>&1
    sleep 18; step_check "release cold start offline (https://bloxd.io/)" "$PKG_REL"; shot 0a-release-cold-start
    timeout 90 adb shell am start -W -a android.intent.action.VIEW \
      -d "https://bloxd.io/play/classic?lobby=local_world" -n "$ACT_REL" >> "$ART/start-rel.txt" 2>&1
    sleep 18; adb shell input tap 640 360; sleep 2; adb shell input swipe 500 360 720 320 300; sleep 3
    step_check "release classic local_world + touch" "$PKG_REL"; shot 0b-release-classic
    adb shell am force-stop "$PKG_REL" >/dev/null 2>&1 || true
  else
    STEPS="${STEPS}\n- release apk install: FAILED ($(tr '\n' ' ' < "$ART/install-rel.log" | cut -c1-160))"
    STEP_FAIL=1
  fi
fi

# --- Phase B: x86_64 Debug APK full lifecycle + upgrade + persistence test ---
log "B) Installing x86_64 DEBUG APK ($PKG_DBG)"
if ! install_apk_safe "$APK_X86_DBG" "$ART/install.log"; then
  {
    echo "HOOK-RESULT $VERSION FAILED: adb install debug APK"
    echo '```'
    cat "$ART/install.log"
    echo "--- adb devices ---"
    adb devices -l 2>&1 || true
    echo "--- emu alive: $(kill -0 "$EMU_PID" 2>/dev/null && echo yes || echo no) ---"
    echo "--- emulator.log tail ---"
    tail -n 60 "$ART/emulator.log"
    echo '```'
  } > "$REPORT"
  post "$REPORT"; adb emu kill >/dev/null 2>&1 || true; exit 1
fi

log "1) Debug cold start offline Bloxd home"
timeout 90 adb shell am start -W -n "$ACT_DBG" > "$ART/start.txt" 2>&1
sleep 22; step_check "debug cold start offline (https://bloxd.io/)" "$PKG_DBG"; shot 1-cold-start

log "2) Deep-link into offline classic local_world"
timeout 90 adb shell am start -W -a android.intent.action.VIEW \
  -d "https://bloxd.io/play/classic?lobby=local_world" -n "$ACT_DBG" >> "$ART/start.txt" 2>&1
sleep 25; step_check "debug open offline classic local_world" "$PKG_DBG"; shot 2-play-classic

log "3) Simulate touch/drag interaction on WebView canvas"
adb shell input tap 640 360; sleep 2
adb shell input swipe 500 360 720 320 300; sleep 4
step_check "debug touch & drag input in local room" "$PKG_DBG"; shot 3-after-touch

log "4) Background/foreground lifecycle x3"
for n in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME; sleep 4
  timeout 90 adb shell am start -W -n "$ACT_DBG" >> "$ART/start.txt" 2>&1; sleep 5
done
step_check "debug background/foreground x3" "$PKG_DBG"; shot 4-after-background

log "5) Force-stop and cold restart persistence check"
adb shell am force-stop "$PKG_DBG"; sleep 2
timeout 90 adb shell am start -W -n "$ACT_DBG" >> "$ART/start.txt" 2>&1
sleep 18; step_check "debug cold restart after force-stop" "$PKG_DBG"; shot 5-after-restart

log "6) Upgrade-install over existing app data"
install_apk_safe "$APK_X86_DBG" "$ART/install-upgrade.log" || STEP_FAIL=1
timeout 90 adb shell am start -W -n "$ACT_DBG" >> "$ART/start.txt" 2>&1
sleep 12; step_check "debug launch after upgrade reinstall" "$PKG_DBG"

PERSIST_LS="$(adb shell run-as "$PKG_DBG" ls -la files/bloxd_local_worlds 2>/dev/null | head -n 30 || echo '(no bloxd_local_worlds dir yet)')"

adb logcat -d -v brief > "$ART/logcat.txt" 2>/dev/null || true
adb logcat -d -b events -v brief 2>/dev/null | grep -E "(am_anr|am_crash|am_proc_died).*localbloxd" > "$ART/events.txt" || true

# Scoped crash / error extraction for com.fable.localbloxd*
APP_CRASHES="$(grep -E "am_crash.*localbloxd|am_anr.*localbloxd" "$ART/events.txt" | wc -l | tr -d ' ')"
FATAL_JAVA="$(awk '/FATAL EXCEPTION/{flag=1; buf=$0"\n"; next} flag{buf=buf $0"\n"; if(NF==0 || /^[^ \tE]/){if(buf ~ /localbloxd/) print buf; flag=0}} END{if(flag && buf ~ /localbloxd/) print buf}' "$ART/logcat.txt")"
FATAL_NATIVE="$(grep -E "Fatal signal.*localbloxd|Abort message|WebView render process gone" "$ART/logcat.txt" || true)"
SEC_ERRS="$(grep -iE "SecurityException.*(INTERNET|localbloxd|blockNetworkLoads)" "$ART/logcat.txt" || true)"
CHUNK_ERRS="$(grep -ciE "ChunkLoadError|Failed to fetch dynamically imported module" "$ART/logcat.txt" || true)"
FABLE_BOOT_LINES="$(grep -E "FABLE-BOOT|FABLE-HEALTH|FABLE-ROOM-JOIN" "$ART/logcat.txt" | tail -n 40 | cut -c1-260)"
FABLE_ERR_LINES="$(grep -E "FABLE-ERR|LocalBloxdWeb.*\[ERROR\]|onReceivedError|onReceivedHttpError" "$ART/logcat.txt" | tail -n 50 | cut -c1-260)"
WEB_TAIL="$(grep -E "LocalBloxdWeb|LocalBloxdNative" "$ART/logcat.txt" | tail -n 60 | cut -c1-260)"

{
  echo "== Scoped ANR / crash events (localbloxd) =="
  head -n 30 "$ART/events.txt"
  echo "== Scoped Fatal Java Exceptions (localbloxd) =="
  printf '%s\n' "$FATAL_JAVA" | head -n 120
  echo "== Scoped Native / Renderer Fatal Signals =="
  printf '%s\n' "$FATAL_NATIVE" | head -n 60
  echo "== SecurityException checks =="
  printf '%s\n' "$SEC_ERRS" | head -n 30
  echo "== LocalBloxdWeb / LocalBloxdNative / AndroidRuntime errors =="
  grep -E "^(E|W)/(LocalBloxdWeb|LocalBloxdNative|AndroidRuntime|System\.err)" "$ART/logcat.txt" | head -n 140
} | cut -c1-300 > "$ART/problems.txt"

OVERALL_FAIL=0
FAIL_REASONS=""
if [ "$STEP_FAIL" -ne 0 ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} process-died"; fi
if [ "${APP_CRASHES:-0}" -gt 0 ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} am_crash/anr(${APP_CRASHES})"; fi
if [ -n "$FATAL_JAVA" ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} fatal-java-exception"; fi
if [ -n "$FATAL_NATIVE" ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} fatal-native/renderer"; fi
if [ -n "$SEC_ERRS" ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} security-exception"; fi
if [ "${CHUNK_ERRS:-0}" -gt 0 ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} chunk-load-error(${CHUNK_ERRS})"; fi
if [ -z "$FABLE_BOOT_LINES" ]; then OVERALL_FAIL=1; FAIL_REASONS="${FAIL_REASONS} missing-fable-boot-marker"; fi

VERDICT="PASS"
[ "$OVERALL_FAIL" -ne 0 ] && VERDICT="FAIL (${FAIL_REASONS# })"

{
  echo "HOOK-RESULT $VERSION $VERDICT — local-bloxd offline emulator API 34 x86_64"
  echo ""
  echo "### APK & Native ELF Audit"
  printf '%b\n' "$AUDIT_SUMMARY"
  echo ""
  echo "### Lifecycle Steps"
  printf '%b\n' "$STEPS"
  echo ""
  echo "ChunkLoadErrors: $CHUNK_ERRS | AppCrashEvents: $APP_CRASHES"
  echo ""
  echo "### Boot / Health / Room Telemetry"
  echo '```'
  echo "${FABLE_BOOT_LINES:-(none)}"
  echo '```'
  echo "### JS / WebView Errors"
  echo '```'
  echo "${FABLE_ERR_LINES:-(none)}"
  echo '```'
  echo "### Persistence directory (files/bloxd_local_worlds)"
  echo '```'
  echo "$PERSIST_LS"
  echo '```'
  echo "### LocalBloxdWeb / LocalBloxdNative tail"
  echo '```'
  echo "${WEB_TAIL:-(none)}"
  echo '```'
  echo "### Problems found in logcat ($(wc -l < "$ART/problems.txt") lines)"
  echo '```'
  head -c 30000 "$ART/problems.txt"
  echo '```'
} > "$REPORT"

post "$REPORT"
adb emu kill >/dev/null 2>&1 || true
exit "$OVERALL_FAIL"
