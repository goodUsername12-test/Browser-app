# Fable Browser v60-ce-backup-dl-2 Checkpoint

## Completed
- Checkpoint 1 (`v60-ce-backup-dl-1`, verified all-green in Build #100):
  - Fixed Android 16 / release APK "Cannot open self process memory" error in `fable_memscan.cpp` with crash-proof kernel `pipe2` fallback and native `DT_AUTO`.
  - Upgraded stealth `WebsiteMemoryEngine.kt` & `memory_scanner.js` with `ArrayBuffer`/`SharedArrayBuffer` passive `WeakRef` tracking, deep JS/`Map`/`Set`/same-origin `iframe`/framework fiber scan, noisy window-key filtering, `prev` value tracking, `wasmCount`/`bufCount`, and `refreshHits`.
- Checkpoint 2 (`v60-ce-backup-dl-2`):
  - Fixed Mobile CE UI pressability (`MemoryEnginePanel.kt`, `MemoryEngineManager.kt`, `BrowserScreen.kt`): replaced tiny horizontal-scroll chips with big thumb-friendly 34dp+ buttons in 2/3-column grids, added 1-tap quick mode bar, live Refresh, direct inline Edit on scan results, `prev -> val` change highlighting, and WASM reload hint.
  - Implemented unified 1-notification download progress (`DownloadNotifier.kt`, `Downloader.kt`, `DownloadNotifierTest.kt`): single notification in-place on `FOREGROUND_ID` showing downloaded MB / total MB, remaining MB left, rolling Wi-Fi/network speed, and ETA seconds, updated every 450ms for both standard and parallel segmented downloads.

## Remaining for Checkpoint 3
- Complete Backup & Restore fix (`BackupManager.kt`, `BackupScreen.kt`, `PasswordStore.kt`, `ProxyConfig.kt`, `blocker.js`, `HybridEngineBridge.kt`, `GeckoViewHost.kt`, `TabManager.kt`, `BrowserTab.kt`): collect & restore ALL website cookies (Gecko + Blink), `localStorage` & `sessionStorage` with automatic paste-back when opening sites, synchronous password restore + Blink password autofill, clear validation/status UI, and unit tests.
