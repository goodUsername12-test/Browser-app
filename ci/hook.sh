#!/usr/bin/env bash
# Automatic start-up test on an Android emulator. Called by .github/workflows/build-apk.yml
# after the APKs are built. It installs an x86_64 debug build, starts the browser, opens a
# few pages, and reports crashes, screenshots and engine errors to issue #2.
set -uo pipefail

VERSION="$(cat .fable-version 2>/dev/null || echo unknown)"
ART="$RUNNER_TEMP/hook-artifacts"; mkdir -p "$ART"
REPORT="$RUNNER_TEMP/hook-report.md"; : > "$REPORT"
PKG="com.fable.browser.debug"
ACT="$PKG/com.fable.browser.MainActivity"
log() { echo "[hook] $*"; }
post() { gh issue comment 2 --repo "$GITHUB_REPOSITORY" --body-file "$1" >/dev/null 2>&1 || log "could not post $1"; }

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
IMG="system-images;android-34;google_apis;x86_64"
log "Installing $IMG"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "$IMG" "emulator" "platform-tools" >/dev/null 2>&1 || log "sdkmanager returned an error"
echo no | avdmanager create avd -n fable -k "$IMG" -d pixel_6 --force >/dev/null

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
log "Boot completed: $(adb shell getprop sys.boot_completed | tr -d '\r')"

adb install -r -g "$APK" > "$ART/install.log" 2>&1 || log "install failed: $(cat "$ART/install.log")"
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
adb shell am start -W -n "$ACT" > "$ART/start.txt" 2>&1
sleep 20; step "cold start (new tab page)"; shot 1-start

for url in https://example.com https://en.m.wikipedia.org/wiki/Firefox https://webglsamples.org/aquarium/aquarium.html; do
  log "Opening $url"
  adb shell am start -W -a android.intent.action.VIEW -d "$url" -n "$ACT" >> "$ART/start.txt" 2>&1
  sleep 25
  name="$(echo "$url" | sed -E 's#https?://##; s#[^a-zA-Z0-9]+#-#g' | cut -c1-30)"
  step "open $url"; shot "2-$name"
done

log "Opening and closing tabs, background/foreground"
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 8; step "back from background"
adb shell am force-stop "$PKG"; sleep 2
adb shell am start -W -n "$ACT" >> "$ART/start.txt" 2>&1; sleep 20; step "restart after force-stop (session restore)"; shot 3-restore

adb logcat -d -v brief > "$ART/logcat.txt" 2>/dev/null
{
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
  echo "Problems found in logcat ($(wc -l < "$ART/problems.txt") lines):"
  echo '```'
  head -c 40000 "$ART/problems.txt"
  echo '```'
} > "$REPORT"
post "$REPORT"
adb emu kill >/dev/null 2>&1 || true
exit 0
