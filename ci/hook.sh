#!/usr/bin/env bash
set -uo pipefail

VERSION="$(cat .fable-version 2>/dev/null || echo unknown)"
ART="${RUNNER_TEMP:-/tmp}/hook-artifacts"; mkdir -p "$ART"
REPORT="${RUNNER_TEMP:-/tmp}/hook-report.md"; : > "$REPORT"
PKG="com.fable.localbloxd.debug"
ACT="$PKG/com.fable.localbloxd.LocalBloxdActivity"
log() { echo "[hook] $*"; }
post() {
  if [ -n "${GITHUB_REPOSITORY:-}" ] && command -v gh >/dev/null 2>&1; then
    gh issue comment 2 --repo "$GITHUB_REPOSITORY" --body-file "$1" >/dev/null 2>&1 || log "could not post $1"
  fi
}

{ echo "HOOK-START $VERSION local-bloxd offline emulator test started"; } > "$ART/start.md"; post "$ART/start.md"

log "Running local-bloxd Node integration harness and static audit"
if command -v node >/dev/null 2>&1; then
  if ! node local-bloxd/harness/local_room_harness.js > "$ART/node-harness.log" 2>&1; then
    { echo "HOOK-RESULT $VERSION local_room_harness.js failed"; echo '```'; tail -n 60 "$ART/node-harness.log"; echo '```'; } > "$REPORT"
    post "$REPORT"; exit 1
  fi
fi
if command -v python3 >/dev/null 2>&1 && [ -f local-bloxd/harness/static_audit.py ]; then
  if ! python3 local-bloxd/harness/static_audit.py > "$ART/static-audit.log" 2>&1; then
    { echo "HOOK-RESULT $VERSION static_audit.py failed"; echo '```'; tail -n 60 "$ART/static-audit.log"; echo '```'; } > "$REPORT"
    post "$REPORT"; exit 1
  fi
fi

log "Running :local-bloxd JVM unit tests"
if ! ./gradlew :local-bloxd:testDebugUnitTest --stacktrace > "$ART/unit-tests.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION :local-bloxd:testDebugUnitTest failed"; echo '```'; tail -n 80 "$ART/unit-tests.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi

log "Building :local-bloxd x86_64 debug APK for the emulator"
if ! ./gradlew :local-bloxd:assembleDebug -Pfable.abis=x86_64 -q > "$ART/x86-build.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION :local-bloxd x86_64 build failed"; echo '```'; tail -n 80 "$ART/x86-build.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi

APK="$(ls local-bloxd/build/outputs/apk/debug/*x86_64*.apk 2>/dev/null | head -n 1)"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  { echo "HOOK-RESULT $VERSION x86_64 APK not found under local-bloxd/build/outputs/apk/debug/"; } > "$REPORT"
  post "$REPORT"; exit 1
fi
log "APK: $APK ($(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK") bytes)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/emulator:$SDK/platform-tools:$PATH"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
export ANDROID_USER_HOME="$HOME/.android" ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"

# Inspect APK manifest permissions, assets, and native libraries
AAPT2="$(find "$SDK/build-tools" -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
APK_AUDIT=""
if [ -n "$AAPT2" ] && [ -x "$AAPT2" ]; then
  "$AAPT2" dump badging "$APK" > "$ART/apk-badging.txt" 2>&1 || true
  "$AAPT2" dump permissions "$APK" > "$ART/apk-permissions.txt" 2>&1 || true
  if grep -q "android.permission.INTERNET" "$ART/apk-permissions.txt"; then
    { echo "HOOK-RESULT $VERSION FAILED: android.permission.INTERNET present in $APK"; echo '```'; cat "$ART/apk-permissions.txt"; echo '```'; } > "$REPORT"
    post "$REPORT"; exit 1
  fi
  APK_PKG="$(grep -oE "package: name='[^']+'" "$ART/apk-badging.txt" | head -n 1 || echo unknown)"
  APK_PERMS="$(tr '\n' ' ' < "$ART/apk-permissions.txt" | cut -c1-200)"
  APK_AUDIT="badging=${APK_PKG} perms=[${APK_PERMS}]"
fi

unzip -l "$APK" > "$ART/apk-contents.txt" 2>&1 || true
HAS_ENGINE="$(grep -c 'assets/bloxd/local_bloxd_engine.js' "$ART/apk-contents.txt" || true)"
HAS_C117="$(grep -c 'assets/bloxd/static/js/7rp1w.117.2c551f46.chunk.js' "$ART/apk-contents.txt" || true)"
HAS_CPP_SO="$(grep -c 'lib/x86_64/liblocal_bloxd_native.so' "$ART/apk-contents.txt" || true)"
HAS_RUST_SO="$(grep -c 'lib/x86_64/liblocal_bloxd_rust.so' "$ART/apk-contents.txt" || true)"
APK_AUDIT="$APK_AUDIT engine=$HAS_ENGINE chunk117=$HAS_C117 cpp_so=$HAS_CPP_SO rust_so=$HAS_RUST_SO"
log "APK audit: $APK_AUDIT"
if [ "$HAS_ENGINE" -lt 1 ] || [ "$HAS_C117" -lt 1 ]; then
  { echo "HOOK-RESULT $VERSION FAILED: required offline assets missing ($APK_AUDIT)"; } > "$REPORT"
  post "$REPORT"; exit 1
fi

log "Enabling KVM"
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm

log "SDK: $SDK (writable: $( [ -w "$SDK" ] && echo yes || echo no ))"
yes | sdkmanager --licenses > "$ART/licenses.log" 2>&1 || true
AVD_OK=""
for IMG in "system-images;android-34;google_apis;x86_64" "system-images;android-33;google_apis;x86_64" "system-images;android-34;default;x86_64"; do
  log "Installing $IMG"
  timeout 360 sdkmanager --install "$IMG" "emulator" "platform-tools" >> "$ART/sdkmanager.log" 2>&1 || log "sdkmanager error for $IMG"
  IMG_DIR="$SDK/$(echo "$IMG" | tr ';' '/')"
  if [ ! -d "$IMG_DIR" ]; then
    log "Image dir missing, retrying with sudo"
    sudo -E env "PATH=$PATH" timeout 300 sdkmanager --install "$IMG" "emulator" >> "$ART/sdkmanager.log" 2>&1 || true
    sudo chown -R "$(id -u):$(id -g)" "$SDK" >/dev/null 2>&1 || true
  fi
  echo no | avdmanager create avd -n fable -k "$IMG" --force >> "$ART/avdmanager.log" 2>&1 || true
  if { emulator -list-avds 2>/dev/null; avdmanager list avd -c 2>/dev/null; } | grep -qx fable; then AVD_OK="$IMG"; break; fi
  log "AVD creation failed for $IMG"
done
if [ -z "$AVD_OK" ]; then
  { echo "HOOK-RESULT $VERSION emulator setup failed (no AVD)"; echo '```'; tail -n 25 "$ART/sdkmanager.log"; echo "--- avdmanager"; tail -n 25 "$ART/avdmanager.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi
log "AVD ready with $AVD_OK"

log "Stopping Gradle daemons to free memory"
./gradlew --stop >/dev/null 2>&1 || true
log "Booting emulator"
emulator -avd fable -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -camera-back none -camera-front none -memory 4096 -cores 4 > "$ART/emulator.log" 2>&1 &
timeout 300 adb wait-for-device || log "emulator did not appear"
for i in $(seq 1 150); do
  [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82 || true
BOOTED="$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
log "Boot completed: $BOOTED"
if [ "$BOOTED" != "1" ]; then
  { echo "HOOK-RESULT $VERSION emulator did not boot"; echo '```'; tail -n 60 "$ART/emulator.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi

log "Enforcing strict offline mode on emulator (airplane mode + wifi/data off)"
adb shell svc wifi disable >/dev/null 2>&1 || true
adb shell svc data disable >/dev/null 2>&1 || true
adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || adb shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true

timeout 240 adb install -r -g "$APK" > "$ART/install.log" 2>&1 || {
  { echo "HOOK-RESULT $VERSION adb install failed"; echo '```'; cat "$ART/install.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; adb emu kill >/dev/null 2>&1 || true; exit 1
}
adb logcat -c

shot() {
  timeout 15 adb exec-out screencap -p > "$ART/$1.png" 2>/dev/null || return
  if command -v convert >/dev/null 2>&1; then
    convert "$ART/$1.png" -resize 300x -quality 55 "$ART/$1.jpg" 2>/dev/null
    { echo "HOOK-SHOT $VERSION $1"; base64 -w 76 "$ART/$1.jpg"; } > "$ART/$1.shot"
    post "$ART/$1.shot"
  fi
}
alive() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

STEPS=""
step() { STEPS="$STEPS"$'\n'"- $1: process $( [ -n "$(alive)" ] && echo running || echo NOT RUNNING )"; }

log "1) Cold start offline Bloxd home"
timeout 90 adb shell am start -W -n "$ACT" > "$ART/start.txt" 2>&1
sleep 25; step "cold start offline (https://bloxd.io/)"; shot 1-cold-start

log "2) Deep-link into offline classic local_world"
timeout 90 adb shell am start -W -a android.intent.action.VIEW \
  -d "https://bloxd.io/play/classic?lobby=local_world" -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 25; step "open offline classic local_world"; shot 2-play-classic

log "3) Simulate touch/drag interaction on WebView canvas"
adb shell input tap 640 360; sleep 2
adb shell input swipe 500 360 720 320 300; sleep 4
step "touch & drag input in local room"; shot 3-after-touch

log "4) Background/foreground lifecycle x3 (flushNow + boostAsync/relaxAsync)"
for n in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME; sleep 4
  timeout 90 adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 5
done
step "background/foreground x3"; shot 4-after-background

log "5) Force-stop and cold restart persistence check"
adb shell am force-stop "$PKG"; sleep 2
timeout 90 adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 20; step "cold restart after force-stop"; shot 5-after-restart

PERSIST_LS="$(adb shell run-as "$PKG" ls -la files/bloxd_local_worlds 2>/dev/null | head -n 30 || echo '(no bloxd_local_worlds dir yet)')"

adb logcat -d -v brief > "$ART/logcat.txt" 2>/dev/null || true
adb logcat -d -b events -v brief 2>/dev/null | grep -E "am_anr|am_crash|am_proc_died.*localbloxd" > "$ART/events.txt" || true

{
  echo "== ANR / crash events =="
  head -n 20 "$ART/events.txt"
  echo "== Fatal errors =="
  grep -E "FATAL EXCEPTION|Fatal signal|ANR in|Abort message|backtrace:" -A30 "$ART/logcat.txt" | grep -v "^--$" | head -n 160
  echo "== Process deaths =="
  grep -E "localbloxd.*(has died|died|Killing)|(has died|Killing).*localbloxd" "$ART/logcat.txt" | head -n 20
  echo "== LocalBloxdWeb / LocalBloxdNative errors & warnings =="
  grep -E "^(E|W)/(LocalBloxdWeb|LocalBloxdNative|AndroidRuntime|System\.err|chromium)" "$ART/logcat.txt" | head -n 140
} | cut -c1-300 > "$ART/problems.txt"

WEB_INFO="$(grep -E "LocalBloxdWeb|LocalBloxdNative" "$ART/logcat.txt" | tail -n 50 | cut -c1-260)"
CHUNK_ERRS="$(grep -ciE "ChunkLoadError|Failed to fetch dynamically imported module" "$ART/logcat.txt" || true)"

{
  echo "HOOK-RESULT $VERSION local-bloxd offline emulator API 34 x86_64"
  echo "APK audit: $APK_AUDIT"
  echo "ChunkLoadErrors in logcat: $CHUNK_ERRS"
  echo "$STEPS"
  echo ""
  echo "am start output:"
  echo '```'
  grep -E "Status|LaunchState|TotalTime|Error" "$ART/start.txt" | head -n 20
  echo '```'
  echo "Persistence directory (files/bloxd_local_worlds):"
  echo '```'
  echo "$PERSIST_LS"
  echo '```'
  echo "LocalBloxdWeb / LocalBloxdNative log tail:"
  echo '```'
  echo "$WEB_INFO"
  echo '```'
  echo "Problems found in logcat ($(wc -l < "$ART/problems.txt") lines):"
  echo '```'
  head -c 40000 "$ART/problems.txt"
  echo '```'
} > "$REPORT"
post "$REPORT"
adb emu kill >/dev/null 2>&1 || true
exit 0
