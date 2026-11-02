# Fable Browser

A privacy focused Android browser built on GeckoView (Firefox's engine), Kotlin and Jetpack Compose.

I wanted a browser that blocks ads out of the box, has a real incognito mode and doesn't phone home. That's what this is. There's no telemetry and no analytics SDK, and crash reports stay on the device unless you choose to send one.

## Features

- Tabs that sleep in the background and restore, desktop mode, find in page, text zoom
- Built in ad and tracker blocking (EasyList style rules, updatable from settings)
- Incognito tabs that never touch the disk, with an optional PIN/biometric lock
- Super incognito: every session gets its own fake device (browser, screen, GPU, fonts, time zone...) and you can switch each part on or off
- Game mode for super incognito, so WebGL and canvas games still work
- Optional SOCKS/HTTP proxy for incognito and super incognito, with no direct fallback if the proxy is down
- DNS over HTTPS, HTTPS only mode, tracking parameter stripping
- Automatic cache cleanup (daily, or when the cache gets over 300 MB)
- Downloads to `Downloads/Fable` with a foreground service (no storage permission on Android 10 and up)

## Getting the APK

Every push to `main` builds the app on GitHub Actions.

1. Open the **Actions** tab and pick the latest green **Build APK** run.
2. Download **fable-browser-release-apk** from the Artifacts section.
3. Unzip and install `app-arm64-v8a-release.apk`.

The APK is 64-bit only (arm64-v8a), Android 8.0 and up.

Release builds are signed with my own key. If you still have an old build that was signed with the debug key, Android will say the package conflicts. Uninstall it once and install the new one. Updates install normally after that.

## Building

You need JDK 17 and the Android 16 (API 36) SDK. Android Studio Narwhal or newer works fine.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

GeckoView comes from `https://maven.mozilla.org/maven2/`, which is already set up in `settings.gradle.kts`.

### Signing

Without a key, builds fall back to `app/debug.keystore`, which is public. Don't ship those.

On GitHub the release key comes from four repository secrets: `FABLE_KEYSTORE_BASE64`, `FABLE_KEYSTORE_PASSWORD`, `FABLE_KEY_ALIAS` and `FABLE_KEY_PASSWORD`. Locally you can drop a git ignored `signing.properties` next to the keystore:

```properties
storeFile=fable-release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Release builds run R8. `app/proguard-rules.pro` keeps GeckoView, Room and Hilt classes.

## CI

- `build-apk.yml` builds debug and release, runs the unit tests and then a short emulator test (cold start, a few sites, incognito and super incognito tabs, memory and freeze checks). Results get posted to issue #2.
- `apply-update.yml` lets me push a whole project snapshot from my phone: the archive is posted to issue #2 as base64 comments, checked against its SHA-256, committed and built. Only comments from the repo owner are accepted.

## Project layout

```
app/src/main/java/com/fable/browser/
  engine/     GeckoRuntime, tabs, sessions, proxy, super incognito, downloads
  ui/         Compose screens (browser, tabs, settings, history, bookmarks)
  data/       DataStore settings, Room database, favicon cache
  di/         Hilt modules
  util/       crash reporter, freeze watchdog, URL helpers
app/src/main/assets/extensions/fable_shield/
  blocker.js            ad blocking, header cleanup, proxy routing
  fingerprint_shield.js the fingerprint protection for incognito and super incognito
  page_tools.js         favicons and small page helpers
ci/hook.sh              the emulator test
```

## Permissions

| Permission | Used for |
| --- | --- |
| `INTERNET` | Loading pages |
| `ACCESS_NETWORK_STATE` | Offline page, data saver on mobile data |
| `POST_NOTIFICATIONS` | Download notifications |
| `CAMERA`, `RECORD_AUDIO` | Only when a site asks, per site |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Only when a site asks, per site |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keeping downloads alive in the background |
| `USE_BIOMETRIC` | Optional incognito lock |
| `VIBRATE` | Vibration API for games, if you turn it on |
| `WRITE_EXTERNAL_STORAGE` | Downloads on Android 8 and 9 only |

## About incognito

Incognito keeps things off your device and makes tracking and fingerprinting a lot harder. It doesn't hide which sites you visit from your ISP or network, and it can't help once you log into something. It is not Tor. If you need that, point the proxy setting at Orbot (`127.0.0.1:9050`, SOCKS5).

## Troubleshooting

| Problem | Fix |
| --- | --- |
| "App not installed, conflicts with another package" | The installed copy has a different signature. Uninstall it first. |
| `Could not find org.mozilla.geckoview:geckoview` | The Mozilla Maven repo is blocked on your network. |
| `Unsupported class file major version` | Use JDK 17. |
| `SDK location not found` | Add `sdk.dir=...` to `local.properties` or open the project in Android Studio once. |
