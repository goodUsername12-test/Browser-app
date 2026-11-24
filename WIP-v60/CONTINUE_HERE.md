# Fable Browser v60-ce-backup-dl-3 Checkpoint

## Completed
1. Mobile Cheat Engine (Memory Engine):
   - Fixed Android 16 / non-debuggable release APK "Cannot open self process memory" error (`fable_memscan.cpp`, `NativeMemoryScanner.kt`) via crash-proof kernel `pipe2` fallback + native `DT_AUTO` (`i32`/`f32`/`f64`) + `nativeRefreshHits`.
   - Upgraded stealth Page & WASM scanner (`WebsiteMemoryEngine.kt`, `memory_scanner.js`) with zero-load when OFF, zero page source changes, native `Function.prototype.toString` masking, passive `WeakRef` `ArrayBuffer`/`SharedArrayBuffer` tracking, deep JS/`Map`/`Set`/same-origin `iframe`/framework fiber traversal, noisy window-property filtering, `prev` tracking, `wasmCount`/`bufCount`, and live `refreshHits`.
   - Fixed Mobile CE panel pressability (`MemoryEnginePanel.kt`, `MemoryEngineManager.kt`, `BrowserScreen.kt`) with big 34dp+ buttons in 2/3-column grids, 1-tap quick mode bar, direct inline Edit on scan results, live Refresh, and `prev -> val` change highlighting.
2. Unified 1-Notification Download Progress (`DownloadNotifier.kt`, `Downloader.kt`, `DownloadNotifierTest.kt`):
   - Single live-updating notification on `FOREGROUND_ID` displaying downloaded MB / total MB, remaining MB left, rolling Wi-Fi/network speed, and ETA seconds, updated every 450ms for standard and parallel segmented downloads.
3. Full Backup & Restore Fix (`BackupManager.kt`, `BackupScreen.kt`, `PasswordStore.kt`, `ProxyConfig.kt`, `blocker.js`, `HybridEngineBridge.kt`, `GeckoViewHost.kt`, `TabManager.kt`, `BrowserTab.kt`, `BackupManagerTest.kt`):
   - Synchronous password flush on import + Blink password autofill/save alongside GeckoView `Autocomplete.StorageDelegate`.
   - Captures and restores ALL GeckoView cookies (`browser.cookies.getAll`/`set`), Blink `CookieManager` cookies, live open-tab & cached `localStorage`/`sessionStorage` (with automatic paste-back into websites on open/reload for both Blink and Gecko), Gecko profile files, WebView profile files (`app_webview/Default`), bookmarks, history, tabs, permissions, downloads, hybrid per-site modes, and settings.
   - Lowered minimum backup password to 4 chars with explicit validation hints, highlighted status card, and toast feedback.

## Next Steps
- Verify GitHub Actions Build APK (Debug APK, Unit Tests, Release APK, and API 34 x86_64 Emulator checks) for `v60-ce-backup-dl-3`.
- Fix any CI or unit-test issues immediately if found.
- Physical Android 16 POCO device verification remains to be performed on real hardware.
