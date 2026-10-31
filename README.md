# Fable Browser

A privacy-first Android web browser built with Kotlin, Jetpack Compose (Material 3) and
**Mozilla GeckoView**, with an incognito mode that is deliberately stronger than Chrome's.

- **Builds automatically on GitHub Actions** (see *Get the APK*), 64-bit only
- **Language / build**: Kotlin, JDK 17, Gradle Kotlin DSL, Kotlin 2.2.21, AGP 8.13.2, Gradle 8.14.3
- **minSdk 26 · targetSdk 35 · compileSdk 36**
- **Engine**: GeckoView (`org.mozilla.geckoview:geckoview`) — chosen over `WebView`
  because it supports true per-session isolation (`contextId` partitions), real
  Enhanced Tracking Protection, a blocking `webRequest` API for a filter-list ad
  blocker, and it does not phone home to Google.
- **Storage**: Room (bookmarks, history, downloads, site permissions) + DataStore (settings)
- **Async**: Coroutines + Flow · **DI**: Hilt · **Architecture**: MVVM, single Activity

## Zero telemetry — explicitly

Fable Browser contains **no analytics SDK, no crash-reporting SDK, and no telemetry**.
GeckoView's crash reporter and telemetry are opt-in and are never enabled
(`EngineManager.createRuntime()`). The app makes **no network request the user did not
initiate**, with exactly two exceptions, both user-triggered and both off by default:
downloading an updated filter list (Settings → Privacy, "Update filter lists" button)
and a user-configured DNS-over-HTTPS resolver.

---

## Architectural decisions (made in favour of privacy)

1. **GeckoView, not WebView.** Larger APK, but Android `WebView` cannot give per-tab
   cookie partitioning, cannot cancel requests at the network layer for subresources
   reliably, and ships as a Google-updated component.
2. **Per-incognito-tab container isolation defaults to ON.** Each incognito tab gets its
   own `contextId`, i.e. its own cookie jar and storage partition, so two incognito tabs
   cannot be correlated across sites. Chrome shares one profile across all its incognito tabs.
3. **HTTPS-only mode defaults to ON**, with an interstitial rather than a silent downgrade.
4. **DNS-over-HTTPS defaults to ON** (Cloudflare), because plaintext DNS leaks every
   hostname to the local network.
5. **Incognito tabs are never persisted**, not even their thumbnails, and never restored
   after an app kill — restoring them would mean writing them somewhere.
6. **Incognito screens set `FLAG_SECURE`**, so screenshots, screen recording, and the
   Android Recents thumbnail are all blocked.

---

## Get the APK (no Android Studio needed)

Every push to `main` (or `master`) runs the **Build APK** workflow in
`.github/workflows/build-apk.yml` on GitHub Actions. It builds the debug APK, runs the
unit tests, tries an optimized release build, and uploads the results as artifacts.

1. Open the repository on github.com, tap **Actions**, then the newest **Build APK** run.
2. When it shows a green check, scroll to **Artifacts** and download
   **fable-browser-debug-apk** (or **fable-browser-release-apk**, which is smaller and
   faster because it is minified with R8).
3. The download is a `.zip`. Open it on the phone and tap
   `app-arm64-v8a-debug.apk` (or `app-arm64-v8a-release.apk`).
4. If Android asks, allow your browser/file manager under **Settings → Apps → Special app
   access → Install unknown apps**, then tap **Install**.

You can start a build by hand from **Actions → Build APK → Run workflow**.

Only a **64-bit (`arm64-v8a`) APK** is produced. There is no 32-bit or universal APK,
which keeps the download and install size down.

Both debug and release builds are signed with `app/debug.keystore`, a deliberately
public key committed to the repository. Because every CI run uses the same key, a new
APK installs over the previous one without losing your bookmarks, history or settings.
The debug build has the package name `com.fable.browser.debug`, so it can sit next to a
release build. If you ever want to publish the app, use a private key instead (see
*Release signing* below).

---

### How updates reach this repository

Two workflows work together. `build-apk.yml` builds and tests the app and posts a short
result (APK sizes or the exact compiler errors) as a comment on issue #2.
`apply-update.yml` lets the repository owner deliver a whole new project version through
issue #2: base64 comments marked `FABLE-DATA <version>` are reassembled, checked against the
SHA-256 in a `FABLE-APPLY <version> <sha256>` comment, committed to the default branch, and
then the build is started. Comments from anyone other than the repository owner are ignored,
and workflow files are never changed this way.

---

## Speed, RAM and privacy profiles

**Settings → Performance** lets you trade privacy for speed with one tap.

| Profile | What it does | Good for |
| --- | --- | --- |
| **Maximum privacy** | Fingerprint shield and header cleaning in every tab, strict tracking protection, filter-list ad/tracker blocking | Private everyday browsing |
| **Balanced** (default) | Filter-list blocking with standard tracking protection; games keep full WebGL, gamepad and timer access | Most people |
| **Maximum speed** | Only GeckoView's built-in tracking protection in normal tabs, no extra work per request | HTML5/WebGL games, heavy web apps |
| **Custom** | Selected automatically when you flip one of the switches yourself | |

Incognito tabs always keep the blocker and the fingerprint shield, whatever the profile.

**Full screen and layout.** The ⛶ button in the toolbar puts any page (games, videos) in full
screen: the address bar, status bar and the phone's navigation bar are hidden and the screen
stays on. Swipe in from the edge and go back to leave. Pages that ask for full screen
themselves get the same treatment. The address bar sits at the top by default; *Settings →
General → Address bar at the top* moves it to the bottom. *Hide toolbar while scrolling* is off
by default so the page never changes size in the middle of a touch gesture, which keeps
two-finger game controls (move + camera) working normally.

**Smart tab unloading.** Under *Background tabs* you choose how many recent tabs stay in
RAM (*Recent tabs kept loaded*, 1 to 8, default 3) and the mode: *Smart* (default),
*Only current tab* (lowest memory) or *Keep all loaded*. Unloaded tabs keep their saved
state and come back when you open them. The current tab, tabs playing audio or video and
tabs marked **Keep this tab loaded** (in the menu) are never unloaded. When Android reports
memory pressure, background tabs are unloaded early.

Other memory savers that are always on: no pre-launched content processes, a single cached
back/forward page, a capped in-memory cache, low-resolution `RGB_565` tab thumbnails and
lazy session restore (only the active tab loads at startup). Fullscreen games run in an
immersive window that keeps the screen awake and honours the page's orientation lock.

---

## Project layout

```
settings.gradle.kts          root Gradle settings
build.gradle.kts             root build file
gradle.properties
gradlew / gradlew.bat
gradle/
  libs.versions.toml         version catalog
  wrapper/
.github/workflows/
  build-apk.yml              GitHub Actions: builds and uploads the APKs
app/                         the Android application module
  build.gradle.kts           SDK levels, 64-bit ABI split, signing, R8
  debug.keystore             public shared debug key (see above)
  proguard-rules.pro
  src/
    main/
      AndroidManifest.xml
      java/com/fable/browser/{ui,engine,data,di,util}/
      assets/extensions/fable_shield/   WebExtension: ad blocker, fingerprint shield
      res/                   themes, strings, purple fox-flame launcher icon
    test/                    JVM unit tests
    androidTest/             instrumented tests (google.com smoke test)
```

---

## Build on a computer (optional)

### Android Studio

1. Install Android Studio (Narwhal 2025.1.3 or newer) with the Android 16 (API 36) SDK platform
   and Build-Tools, plus JDK 17 (bundled).
2. `File → Open` → select the project root (the folder containing `settings.gradle.kts`).
   Gradle pulls GeckoView from `https://maven.mozilla.org/maven2/`.
3. Enable Developer options + USB debugging on the phone, plug it in and press **Run ▶**.

### Command line

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

### Release signing

For a private release key, generate a keystore once:

```bash
keytool -genkeypair -v -keystore fable-release.keystore -alias fable \
  -keyalg RSA -keysize 4096 -validity 10000
```

*Locally:* put it in the project root and create a git-ignored `signing.properties`:

```properties
storeFile=fable-release.keystore
storePassword=your-store-password
keyAlias=fable
keyPassword=your-key-password
```

*On GitHub Actions:* add these repository secrets (Settings → Secrets and variables →
Actions): `FABLE_KEYSTORE_BASE64` (output of `base64 -w0 fable-release.keystore`),
`FABLE_KEYSTORE_PASSWORD`, `FABLE_KEY_ALIAS`, `FABLE_KEY_PASSWORD`.

Note that an APK signed with a new key cannot be installed over one signed with the
public debug key; uninstall the old one first.

Release builds use R8 (`isMinifyEnabled` and `isShrinkResources`). The rules in
`app/proguard-rules.pro` keep `org.mozilla.geckoview.**` / `org.mozilla.gecko.**`
(GeckoView is JNI-heavy), Room's generated code, and Hilt/Dagger.

### Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Could not find org.mozilla.geckoview:geckoview` | The Mozilla Maven repo is missing/blocked. Confirm `maven("https://maven.mozilla.org/maven2/")` is in `settings.gradle.kts` and that your network allows it. |
| `Unsupported class file major version` / `Unsupported Java` | You are not on JDK 17. Android Studio: *Settings → Build Tools → Gradle → Gradle JDK → 17*. CLI: `export JAVA_HOME=/path/to/jdk-17`. |
| `SDK location not found` | Create `local.properties` with `sdk.dir=/absolute/path/to/Android/sdk`, or open once in Android Studio. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | GeckoView is big (about 80 MB installed). Free some space. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signed with a different key than the installed copy: `adb uninstall com.fable.browser` first. |
| "App not installed" / not compatible | The APK is 64-bit only (`arm64-v8a`). Very old 32-bit phones are not supported. |
| NDK/CMake errors | This project has no native code of its own; if the NDK is referenced, it's a stale Gradle cache — `./gradlew --stop && ./gradlew clean`. |
| Compose compiler version mismatch | Kotlin and the Compose plugin are aligned via `gradle/libs.versions.toml`; change both together, never one alone. |

### Tests

```bash
./gradlew testDebugUnitTest          # URL/search detection, tracking-param stripping, filter engine
./gradlew connectedDebugAndroidTest  # smoke test: loads https://www.google.com in a private session
```

---

## Permissions, and why each exists

| Permission | Why |
| --- | --- |
| `INTERNET` | Loading web pages. Without it the browser cannot work. |
| `ACCESS_NETWORK_STATE` | Offline interstitial and download resumption. |
| `POST_NOTIFICATIONS` | Download progress/complete notifications (runtime-requested). |
| `CAMERA`, `RECORD_AUDIO` | Only forwarded when a website asks (`getUserMedia`); runtime-requested, per-site, revocable in Settings → Site settings. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Only for the Geolocation API when a site asks; same per-site model. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keeps downloads alive when the app is backgrounded. |
| `USE_BIOMETRIC` | Optional biometric/PIN lock on incognito. |

No `READ/WRITE_EXTERNAL_STORAGE`: downloads use scoped storage (`Downloads/Fable`).

---

## Incognito: honest disclosure

Shown in-app on first incognito launch (`ui/browser/IncognitoSurfaces.kt`).

**It protects you from**: local traces on this device (no history, cache, cookies,
storage, or thumbnails hit the disk), cross-site tracking (per-tab cookie/storage
partitioning, third-party cookies blocked, trackers blocked at the network layer),
fingerprinting (spoofed UA/platform/timezone/screen, canvas + WebGL + audio noise,
WebRTC IP leak blocked, reduced timer precision, blocked sensor/battery APIs), and
over-the-shoulder or screenshot capture (`FLAG_SECURE`).

**It does not protect you from**: your ISP or mobile carrier seeing which sites you
connect to, your employer's or school's network and its certificates, any site you
voluntarily log into, malware or a compromised device, or a government-level adversary.
It is not Tor. If you need network-level anonymity, point the incognito proxy setting at
a Tor SOCKS5 port (127.0.0.1:9050) with Orbot running.

---

## Requirement → implementation checklist

### §1 Tech stack
- Kotlin/JDK 17/Gradle KDSL/AGP: `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`
- Compose + Material 3: `ui/theme/Theme.kt`, all `ui/**`
- SDK levels: `app/build.gradle.kts`
- GeckoView engine: `engine/EngineManager.kt`, `engine/SessionFactory.kt`, `ui/browser/GeckoViewHost.kt`
- Room: `data/db/FableDatabase.kt`, `data/db/Daos.kt`, `data/model/Models.kt`
- Coroutines/Flow, Hilt: `di/AppModule.kt`, `FableApplication.kt`, all `*ViewModel.kt`
- MVVM + repositories: `data/repo/Repositories.kt`, `ui/**/*ViewModel.kt`, single activity `MainActivity.kt`

### §2 Core browser features
1. Omnibox, URL-vs-search detection, engine switching, `https://` upgrade + "Not secure" chip, suggestions from bookmarks/history (normal mode only) — `ui/browser/Omnibox.kt`, `util/UrlUtils.kt`, `ui/browser/BrowserViewModel.kt`
2. Full page rendering (SPAs, video, forms, uploads/downloads) — `ui/browser/GeckoViewHost.kt`, `engine/SessionDelegates.kt`, `engine/FablePromptDelegate.kt`
3. Tabs, grid switcher with thumbnails, separate normal/incognito trays, swipe-to-close, close-all — `engine/TabManager.kt`, `engine/BrowserTab.kt`, `ui/tabs/TabsScreen.kt`
4. Back/forward/reload/stop, hardware back, pull-to-refresh, edge-swipe back — `ui/browser/BottomToolbar.kt`, `ui/browser/BrowserScreen.kt`, `MainActivity.kt`
5. Bookmarks with folders, search, HTML import/export — `ui/bookmarks/*`, `util/BookmarkHtml.kt`
6. History with search, single delete, clear range — `ui/history/*`, `data/db/Daos.kt`
7. Downloads manager, progress, open file, `Downloads/Fable`, incognito confirmation — `engine/Downloader.kt`, `engine/DownloadService.kt`, `engine/DownloadNotifier.kt`, `ui/downloads/*`
8. Find in page, desktop site, text/page zoom, reader mode, share, copy link, add to home screen, print/save-as-PDF — `ui/browser/MenuSheet.kt`, `ui/browser/BrowserViewModel.kt`, `assets/extensions/fable_shield/reader.css`
9. Long-press context menus for links/images — `ui/browser/ContextMenuSheet.kt`, `engine/ContentMenuAction.kt`
10. Runtime + per-site permissions, revocable — `engine/FablePromptDelegate.kt`, `ui/browser/PromptHost.kt`, `ui/settings/SiteSettingsScreen.kt`
11. Bottom collapsible toolbar, light/dark/system + forced-dark web, immersive video, rotation, edge-to-edge, tab restore (normal only) — `ui/browser/BottomToolbar.kt`, `ui/theme/Theme.kt`, `ui/browser/BrowserScreen.kt`, `engine/TabManager.kt`, `MainActivity.kt`

### §3 Privacy & blocking
- EasyList + EasyPrivacy filter engine with network-layer blocking, updatable from URL — `engine/ContentBlocker.kt`, `assets/easylist.txt`, `assets/easyprivacy.txt`, `assets/extensions/fable_shield/blocker.js`, `assets/extensions/fable_shield/manifest.json`
- Third-party cookies blocked by default, cookie purging, strict ETP — `engine/EngineManager.kt`
- `Sec-GPC: 1` + `DNT: 1` — `engine/EngineManager.kt` (`globalPrivacyControlEnabled`), `assets/extensions/fable_shield/blocker.js`
- HTTPS-only mode + interstitial — `ui/browser/Interstitials.kt`, `ui/browser/BrowserViewModel.kt`
- Tracking-param stripping (`utm_*`, `fbclid`, `gclid`, `igshid`, `mc_eid`, …) — `util/UrlUtils.kt`
- DNS-over-HTTPS (Cloudflare/Quad9/custom, on by default) — `engine/EngineManager.kt`, `data/model/Models.kt` (`DohProvider`), `ui/settings/PrivacySettingsScreen.kt`
- Zero telemetry — `engine/EngineManager.kt`, `ui/settings/AboutScreen.kt`, this README

### §4 Incognito
- **A. Nothing touches disk** — `engine/SessionFactory.kt` (`usePrivateMode(true)`), `engine/TabManager.kt` (in-memory thumbnails, never persisted)
- **B. Per-tab container isolation + third-party partitioning** — `engine/SessionFactory.kt` (`contextId`), `engine/EngineManager.kt` (`clearContainer`), toggle in `ui/settings/IncognitoSettingsScreen.kt`
- **C. Fingerprinting resistance** (UA pool, platform, language, timezone/UTC, screen letterboxing, canvas/WebGL/audio noise, WebRTC leak block, `performance.now()` precision, battery/gamepad/sensor block, frozen font list) — `engine/FingerprintShield.kt`, `assets/extensions/fable_shield/fingerprint_shield.js`; "Test my fingerprint" → `ui/settings/IncognitoSettingsScreen.kt`
- **D. OS-level shield**: `FLAG_SECURE`, hidden Recents titles/URLs, biometric/PIN lock + auto-lock, panic button, auto-wipe on exit + inactivity timer, incognito download confirmation, per-mode proxy/Tor — `MainActivity.kt`, `util/BiometricGate.kt`, `util/ActivityBridge.kt`, `ui/browser/IncognitoSurfaces.kt`, `engine/TabManager.kt`, `engine/ProxyConfig.kt`
- **E. Honest disclosure card** — `ui/browser/IncognitoSurfaces.kt`

### §5 Settings
`ui/settings/SettingsScreen.kt` (hub) · `GeneralSettingsScreen.kt` · `PrivacySettingsScreen.kt` ·
`IncognitoSettingsScreen.kt` · `AppearanceSettingsScreen.kt` · `SiteSettingsScreen.kt` ·
`DownloadSettingsScreen.kt` · `AboutScreen.kt` · state in `SettingsViewModel.kt` / `data/prefs/SettingsStore.kt`

### §6 Quality
- Package structure as specified — `app/src/main/java/com/fable/browser/{ui,engine,data,di,util}`
- Session release / no leaks — `engine/TabManager.kt`, `ui/browser/BrowserViewModel.kt` (`onCleared`), `MainActivity.kt`
- Error handling: offline, SSL interstitial, 404, malformed URLs, `intent://`, `mailto:`, `tel:`, `market://`, custom schemes, PDF, low-memory eviction — `ui/browser/Interstitials.kt`, `engine/SessionDelegates.kt`, `util/UrlUtils.kt`, `engine/TabManager.kt`
- Unit tests — `app/src/test/java/com/fable/browser/util/UrlUtilsTest.kt`, `app/src/test/java/com/fable/browser/engine/ContentBlockerTest.kt`
- Instrumented smoke test loading google.com — `app/src/androidTest/java/com/fable/browser/GoogleLoadSmokeTest.kt`, `HiltTestRunner.kt`
- Manifest + justified permissions — `app/src/main/AndroidManifest.xml`, table above
- Adaptive launcher icon + Material Symbols — `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/mipmap-*/`, `material-icons-extended`

### §7 APK build
This README §"Build & install" 1–6, plus `app/build.gradle.kts` (splits, signing, R8) and
`app/proguard-rules.pro`.
