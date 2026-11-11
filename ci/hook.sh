#!/usr/bin/env bash
set -uo pipefail

VERSION="$(cat .fable-version 2>/dev/null || echo unknown)"
ART="$RUNNER_TEMP/hook-artifacts"; mkdir -p "$ART"
REPORT="$RUNNER_TEMP/hook-report.md"; : > "$REPORT"
PKG="com.fable.browser.debug"
ACT="$PKG/com.fable.browser.MainActivity"
log() { echo "[hook] $*"; }
post() { gh issue comment 2 --repo "$GITHUB_REPOSITORY" --body-file "$1" >/dev/null 2>&1 || log "could not post $1"; }

{ echo "HOOK-START $VERSION emulator test started"; } > "$ART/start.md"; post "$ART/start.md"
log "Building x86_64 debug APK for the emulator"
if ! ./gradlew assembleDebug -Pfable.abis=x86_64 -q > "$ART/x86-build.log" 2>&1; then
  { echo "HOOK-RESULT $VERSION x86_64 build failed"; echo '```'; tail -n 60 "$ART/x86-build.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi
APK="$(ls app/build/outputs/apk/debug/*x86_64*.apk | head -n 1)"
log "APK: $APK"

log "Enabling KVM"
echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/emulator:$SDK/platform-tools:$PATH"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
export ANDROID_USER_HOME="$HOME/.android" ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
log "SDK: $SDK (writable: $( [ -w "$SDK" ] && echo yes || echo no ))"
yes | sdkmanager --licenses > "$ART/licenses.log" 2>&1 || true
AVD_OK=""
for IMG in "system-images;android-34;google_apis;x86_64" "system-images;android-33;google_apis;x86_64" "system-images;android-34;default;x86_64"; do
  log "Installing $IMG"
  timeout 900 sdkmanager --install "$IMG" "emulator" "platform-tools" >> "$ART/sdkmanager.log" 2>&1 || log "sdkmanager error for $IMG"
  IMG_DIR="$SDK/$(echo "$IMG" | tr ';' '/')"
  if [ ! -d "$IMG_DIR" ]; then
    log "Image dir missing, retrying with sudo"
    sudo -E env "PATH=$PATH" sdkmanager --install "$IMG" "emulator" >> "$ART/sdkmanager.log" 2>&1 || true
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
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82 || true
BOOTED="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
log "Boot completed: $BOOTED"
if [ "$BOOTED" != "1" ]; then
  { echo "HOOK-RESULT $VERSION emulator did not boot"; echo '```'; tail -n 60 "$ART/emulator.log"; echo '```'; } > "$REPORT"
  post "$REPORT"; exit 1
fi

timeout 240 adb install -r -g "$APK" > "$ART/install.log" 2>&1 || log "install failed: $(cat "$ART/install.log")"
adb logcat -c

shot() {
  adb exec-out screencap -p > "$ART/$1.png" 2>/dev/null || return
  if command -v convert >/dev/null; then
    convert "$ART/$1.png" -resize 300x -quality 55 "$ART/$1.jpg" 2>/dev/null
    { echo "HOOK-SHOT $VERSION $1"; base64 -w 76 "$ART/$1.jpg"; } > "$ART/$1.shot"
    post "$ART/$1.shot"
  fi
}
alive() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

STEPS=""
step() { STEPS="$STEPS"$'\n'"- $1: process $( [ -n "$(alive)" ] && echo running || echo NOT RUNNING )"; }

log "Cold start"
timeout 90 adb shell am start -W -n "$ACT" > "$ART/start.txt" 2>&1
sleep 20; step "cold start (new tab page)"; shot 1-start

tap_desc() {
  adb shell uiautomator dump /sdcard/fable-ui.xml > "$ART/uidump.txt" 2>&1
  adb pull /sdcard/fable-ui.xml "$ART/ui.xml" >/dev/null 2>&1 || return 1
  local b
  b="$(python3 - "$1" "$ART/ui.xml" <<'PY'
import re, sys
desc, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8", errors="ignore").read()
for node in re.findall(r"<node [^>]*>", xml):
    if 'content-desc="%s"' % desc in node:
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)"
  [ -n "$b" ] || return 1
  adb shell input tap $b
}

for url in https://example.com https://en.m.wikipedia.org/wiki/Firefox https://webglsamples.org/aquarium/aquarium.html; do
  log "Opening $url"
  timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "$url" -n "$ACT" >> "$ART/start.txt" 2>&1
  sleep 25
  name="$(echo "$url" | sed -E 's#https?://##; s#[^a-zA-Z0-9]+#-#g' | cut -c1-30)"
  step "open $url"; shot "2-$name"
done

log "Full screen button, then the WebGL page while in full screen"
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://example.com" -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 12
if tap_desc "Full screen"; then
  sleep 6; step "full screen button (static page)"; shot 4-fullscreen
  adb shell input swipe 300 700 300 600 300; sleep 2; step "touch while in full screen"
  adb shell input keyevent KEYCODE_BACK; sleep 4; step "leave full screen with Back"
  tap_desc "Full screen" && { sleep 4; step "full screen again"; }
  timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://webglsamples.org/aquarium/aquarium.html" -n "$ACT" >> "$ART/start.txt" 2>&1
  sleep 20; step "WebGL page loaded in full screen"; shot 4b-webgl-fullscreen
else
  STEPS="$STEPS"$'\n'"- full screen button: not found ($(head -c 200 "$ART/uidump.txt" 2>/dev/null | tr '\n' ' '))"
fi

log "Background/foreground while the WebGL page runs (full screen)"
for n in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME; sleep 6
  timeout 90 adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 6
  adb shell input tap 540 1200; sleep 2
done
step "back from background x3"; shot 5-after-background
adb shell input keyevent KEYCODE_BACK; sleep 3; step "leave full screen after background"
adb shell am force-stop "$PKG"; sleep 2
timeout 90 adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 20; step "restart after force-stop (session restore)"; shot 3-restore

tap_text() {
  adb shell uiautomator dump /sdcard/fable-ui.xml > "$ART/uidump.txt" 2>&1
  adb pull /sdcard/fable-ui.xml "$ART/ui.xml" >/dev/null 2>&1 || return 1
  local b
  b="$(python3 - "$1" "$ART/ui.xml" <<'PY'
import re, sys
want, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8", errors="ignore").read()
for node in re.findall(r"<node [^>]*>", xml):
    m = re.search(r' text="([^"]*)"', node)
    if m and want in m.group(1):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)"
  [ -n "$b" ] || return 1
  adb shell input tap $b
}

log "Typing in the address bar, then switching apps with the keyboard open"
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://example.com" -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 12
if tap_text "example.com"; then
  sleep 2
  for w in fable browser typing speed test; do
    adb shell input text "$w"; adb shell input keyevent KEYCODE_SPACE
  done
  sleep 2; step "typing in the address bar"; shot 6-typing
  for n in 1 2 3; do
    adb shell input keyevent KEYCODE_HOME; sleep 4
    timeout 90 adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 4
    adb shell input text "x"
  done
  step "switch apps x3 while typing"; shot 7-after-typing-switch
  adb shell input keyevent KEYCODE_BACK; sleep 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
  step "close keyboard and address bar"
else
  STEPS="$STEPS"$'\n'"- typing test: address bar not found"
fi

log "Super incognito check"
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://example.com/?fable=super" --ez fable_super true -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 20; step "super incognito tab (example.com)"; shot 8-super
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://www.wikipedia.org/" --ez fable_super true -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 20; step "second super incognito tab (wikipedia)"; shot 9-super-second
adb logcat -d -v brief -s FableShield:* > "$ART/super.txt" 2>/dev/null || true

log "dust.tt sign-in check in normal, incognito and super incognito"
DUST=""
adb logcat -d -v brief > "$ART/logcat-before-dust.txt" 2>/dev/null || true
for mode in normal incognito super; do
  extra=""
  [ "$mode" = "incognito" ] && extra="--ez fable_incognito true"
  [ "$mode" = "super" ] && extra="--ez fable_super true"
  adb logcat -c
  timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://dust.tt/" $extra -n "$ACT" >> "$ART/start.txt" 2>&1
  sleep 30; shot "10-dust-$mode"
  tapped="no"
  for label in "Sign in" "Log in" "Try for free"; do
    if tap_text "$label"; then tapped="$label"; break; fi
  done
  sleep 15; shot "11-dust-$mode-after-tap"
  cp "$ART/ui.xml" "$ART/dust-$mode-ui.xml" 2>/dev/null || true
  adb logcat -d -v brief > "$ART/dust-$mode.txt" 2>/dev/null || true
  nav="$(grep -E "FableNav" "$ART/dust-$mode.txt" | sed -E 's/^.*FableNav[^:]*: //' | cut -c1-160 | tail -n 6 | tr '\n' ' ')"
  errs="$(grep -E "GeckoConsole|JavaScript (Error|error)|JavaScript Warning: \"(TypeError|ReferenceError)" "$ART/dust-$mode.txt" | grep -viE "CSS|downloadable font|Content-Security" | sed -E 's/^[^:]*: //' | cut -c1-220 | sort -u | head -n 8)"
  webnodes="$(grep -o 'text="[^"]*"' "$ART/dust-$mode-ui.xml" 2>/dev/null | grep -ciE 'sign in|try for free|log in')"
  step "dust.tt $mode: tapped=$tapped, web text nodes=$webnodes"
  DUST="$DUST"$'\n'"[$mode] tapped: $tapped | nav: $nav"$'\n'"$errs"
done
log "JavaScript check in incognito and super incognito"
JSCHECK=""
for mode in incognito super; do
  extra="--ez fable_incognito true"
  [ "$mode" = "super" ] && extra="--ez fable_super true"
  timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://www.whatismybrowser.com/detect/is-javascript-enabled" $extra -n "$ACT" >> "$ART/start.txt" 2>&1
  sleep 25; shot "14-js-$mode"
  adb shell uiautomator dump /sdcard/fable-ui.xml > /dev/null 2>&1
  adb pull /sdcard/fable-ui.xml "$ART/js-$mode-ui.xml" >/dev/null 2>&1 || true
  jsyes="$(grep -o 'text="[^"]*"' "$ART/js-$mode-ui.xml" 2>/dev/null | grep -ciE 'javascript is enabled|yes.{0,20}javascript')"
  jsno="$(grep -o 'text="[^"]*"' "$ART/js-$mode-ui.xml" 2>/dev/null | grep -ciE 'javascript is disabled|javascript is not enabled|no.{0,20}javascript')"
  jstext="$(grep -o 'text="[^"]*"' "$ART/js-$mode-ui.xml" 2>/dev/null | grep -iE 'javascript' | head -n 3 | cut -c1-120 | tr '\n' ' ')"
  step "javascript $mode: enabled-text=$jsyes disabled-text=$jsno"
  JSCHECK="$JSCHECK"$'\n'"[$mode] enabled=$jsyes disabled=$jsno text: $jstext"
done
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://coveryourtracks.eff.org/kcarter?aat=1" --ez fable_super true -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 45; shot "15-eff-super"

log "Download check"
adb shell ls /sdcard/Download > "$ART/dl-before.txt" 2>/dev/null || true
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://codeload.github.com/goodUsername12-test/Browser-app/zip/refs/heads/main" -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 40; shot "16-download"
adb shell ls /sdcard/Download > "$ART/dl-after.txt" 2>/dev/null || true
DLNEW="$(comm -13 <(sort "$ART/dl-before.txt") <(sort "$ART/dl-after.txt") | tr -d '\r' | tr '\n' ' ')"
step "download to Download folder: new files=[${DLNEW}]"

log "Download page code check"
PAGECODE="not run"
adb shell ls /sdcard/Download > "$ART/pc-before.txt" 2>/dev/null || true
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://webglsamples.org/aquarium/aquarium.html" -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 25
adb logcat -d -v brief > "$ART/dust-zz-before-page-code.txt" 2>/dev/null || true
adb logcat -c
if tap_desc "Menu"; then
  sleep 2
  if ! tap_text "Download page code"; then
    adb shell input swipe 540 1800 540 700 300; sleep 2
    tap_text "Download page code" || PAGECODE="menu item not found"
  fi
  sleep 90; shot "17-page-code"
  adb shell ls /sdcard/Download > "$ART/pc-after.txt" 2>/dev/null || true
  PCNEW="$(comm -13 <(sort "$ART/pc-before.txt") <(sort "$ART/pc-after.txt") | tr -d '\r' | grep -i '\.zip$' | head -n 1)"
  if [ -n "$PCNEW" ]; then
    adb pull "/sdcard/Download/$PCNEW" "$ART/page-code.zip" >/dev/null 2>&1 || true
    PAGECODE="saved $PCNEW: $(python3 - "$ART/page-code.zip" <<'PY2'
import sys, zipfile, collections
try:
    z = zipfile.ZipFile(sys.argv[1])
    names = z.namelist()
    ext = collections.Counter((n.rsplit(".", 1)[-1].lower() if "." in n.rsplit("/", 1)[-1] else "none") for n in names)
    size = sum(i.file_size for i in z.infolist())
    print("%d files, %d KB, types %s" % (len(names), size // 1024, dict(ext.most_common(10))))
except Exception as e:
    print("unreadable zip: %s" % e)
PY2
)"
  else
    [ "$PAGECODE" = "not run" ] && PAGECODE="no zip appeared"
  fi
else
  PAGECODE="menu button not found"
fi
adb logcat -d -v brief -s PageArchiver:* FableShield:* > "$ART/page-code-log.txt" 2>/dev/null || true
step "download page code: $PAGECODE"

log "Close a tab and undo"
timeout 90 adb shell am start -W -a android.intent.action.VIEW -d "https://example.com/?undo=1" -n "$ACT" >> "$ART/start.txt" 2>&1
sleep 12
UNDO="tabs button not found"
if tap_desc "Show tabs"; then
  sleep 3
  if tap_desc "Close tab"; then
    sleep 1; shot 12-tab-closed
    if tap_text "Undo"; then UNDO="undo bar shown and tapped"; else UNDO="undo bar not found"; fi
    sleep 2; shot 13-after-undo
  else
    UNDO="close button not found"
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi
step "close tab and undo: $UNDO"
log "Fast app switching x5"
for n in 1 2 3 4 5; do
  adb shell input keyevent KEYCODE_HOME; sleep 1
  timeout 90 adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 1
done
sleep 5; step "fast app switching x5"
FREEZE="$(adb shell run-as "$PKG" cat files/freeze_report.txt 2>/dev/null | head -c 6000)"

{ cat "$ART/logcat-before-dust.txt" "$ART"/dust-*.txt 2>/dev/null; adb logcat -d -v brief 2>/dev/null; } > "$ART/logcat.txt"
SUPERDIAG="$( { cat "$ART/super.txt" 2>/dev/null; grep -E "FableShield" "$ART/logcat.txt"; } | grep -E "FableShield" | sort -u | cut -c1-900 | head -n 24)"
adb logcat -d -b events -v brief 2>/dev/null | grep -E "am_anr|am_crash|am_proc_died.*fable" > "$ART/events.txt" || true
{
  echo "== ANR / crash events =="
  head -n 20 "$ART/events.txt"
  echo "== Fatal errors =="
  grep -E "FATAL EXCEPTION|Fatal signal|ANR in|Abort message|backtrace:" -A30 "$ART/logcat.txt" | grep -v "^--$" | head -n 160
  echo "== Process deaths =="
  grep -E "fable.*(has died|died|Killing)|(has died|Killing).*fable" "$ART/logcat.txt" | head -n 20
  echo "== Engine and app errors =="
  grep -E "^(E|W)/(Gecko[A-Za-z]*|Fable[A-Za-z]*|AndroidRuntime|System.err)" "$ART/logcat.txt" | grep -v "GeckoConsole.*CSS" | head -n 120
} | cut -c1-300 > "$ART/problems.txt"

{
  echo "HOOK-RESULT $VERSION emulator API 34 x86_64"
  echo "$STEPS"
  echo ""
  echo "am start output:"
  echo '```'
  grep -E "Status|LaunchState|TotalTime|Error" "$ART/start.txt" | head -n 20
  echo '```'
  echo "Freeze detector:"
  echo '```'
  if [ -n "$FREEZE" ]; then echo "$FREEZE"; else echo "no freeze recorded"; fi
  echo '```'
  echo "dust.tt check:"
  echo '```'
  echo "$DUST" | cut -c1-400 | head -n 60
  echo '```'
  echo "JavaScript check:"
  echo '```'
  echo "$JSCHECK"
  echo '```'
  echo "Super incognito diagnostics:"
  echo '```'
  if [ -n "$SUPERDIAG" ]; then echo "$SUPERDIAG"; else echo "no FableShield lines"; fi
  echo '```'
  echo "Problems found in logcat ($(wc -l < "$ART/problems.txt") lines):"
  echo '```'
  head -c 40000 "$ART/problems.txt"
  echo '```'
} > "$REPORT"
post "$REPORT"
adb emu kill >/dev/null 2>&1 || true
exit 0
