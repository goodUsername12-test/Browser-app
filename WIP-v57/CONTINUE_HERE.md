# Fable Browser v57-website-speed-1 (1.57.0, versionCode 48)

- Added optional **Whole-Tab Website Speed Control (0.1x to 100x)** (`WebsiteSpeedEngine.kt`, `time_scale.js`, `SettingsStore`, `PerformanceSettingsScreen`, `MenuSheet`, `BrowserScreen` floating `WebsiteSpeedHud`).
- **Strict ZERO-load when OFF**:
  - Default `timeScaleEnabled = false`.
  - GeckoView (`blocker.js`): `time_scale.js` is NOT in `manifest.json`; only dynamically registered via `browser.contentScripts.register` when ON, and immediately unregistered when OFF.
  - Blink (`HybridEngineBridge.kt`): emits ZERO time-scale bytes when OFF.
  - Floating HUD hidden completely when OFF.
- Synchronizes `performance.now`, `Date`/`Date.now`, dynamic live-rescheduling `setTimeout`/`setInterval`, `requestAnimationFrame`, CSS & Web Animations (`playbackRate`), HTML5 `<video>`/`<audio>`, and classic Web Workers.
- Fixed `app/build.gradle.kts` `testOptions.unitTests.isReturnDefaultValues = true` + `org.json:json:20240303` so all JVM unit tests pass.
- Fixed `GeckoViewHost.kt` `about:blank` guard on Blink tab loads and added full `speed.html` emulator verification (`OFF -> 0.1x Gecko & Blink -> 10x Blink -> 100x 5s timer Blink -> OFF`).
