# Fable Browser v60-ce-backup-dl-1 Checkpoint

## Completed in Checkpoint 1
- Fixed Android 16 / non-debuggable release APK "Cannot open self process memory" error in `app/src/main/cpp/fable_memscan.cpp`:
  - Added crash-proof `SafeMemIo` with automatic Linux kernel `pipe2` `copy_from_user`/`copy_to_user` fallback when `/proc/self/mem` returns `EACCES` (`PR_GET_DUMPABLE == 0` on release APKs).
  - Added native `DT_AUTO` (`i32`/`f32`/`f64`), per-hit type tracking, `prev` value reporting, `nativeRefreshHits`, and 128 MB region support.
  - Verified with `g++ -std=c++20 -O3 -Wall -Wextra -Werror -fno-exceptions -fno-rtti` under `prctl(PR_SET_DUMPABLE, 0)`.
- Upgraded stealth webpage & WebAssembly scanner in `WebsiteMemoryEngine.kt` and `app/src/main/assets/extensions/fable_shield/memory_scanner.js`:
  - Zero-load when OFF, zero page source changes, zero leaky globals, native `Function.prototype.toString` masking on all hooks.
  - Added passive `WeakRef` tracking of `ArrayBuffer` (>= 2KB) and `SharedArrayBuffer` (catching closure-hidden game memory) alongside `WebAssembly.Memory`/`Instance`/`instantiate`/`instantiateStreaming`.
  - Deepened JS graph scanning (depth 7, 12k nodes), added ES6 `Map`/`Set` read/write support, same-origin `iframe` traversal, `#root`/`#app`/`#game`/`canvas` framework fiber discovery, noisy browser-property filtering, `prev` values, `wasmCount`/`bufCount`, and `refreshHits`.
  - Verified in Node VM (`V60_STEALTH_DEEP_SCAN_OK=1`).

## Remaining for Checkpoint 2
- `MemoryEnginePanel.kt` & `MemoryEngineManager.kt`: big thumb-friendly grid buttons for Data Type and Scan Mode (replacing small horizontal scroll chips), direct 1-tap Edit on scan results, live Refresh, `prev -> val` display, and WASM reload hint.
- `DownloadNotifier.kt`, `DownloadService.kt`, `Downloader.kt`: 1 unified live-updating notification showing downloaded MB, total file size MB, remaining MB left, rolling transfer speed, and ETA in seconds.
- `BackupManager.kt`, `BackupScreen.kt`, `PasswordStore.kt`, `HybridEngineBridge.kt`, `GeckoViewHost.kt`, `TabManager.kt`: fix broken backup/restore so it collects and restores Gecko + Blink cookies, `localStorage`/`sessionStorage` (with automatic paste-back on website load), synchronous passwords + Blink autofill, bookmarks, history, tabs, settings, and clear progress/error UI feedback.
