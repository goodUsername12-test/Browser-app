# Fable Browser v64-ce-power-1 Checkpoint (10-Minute Checkpoint 1)

## Completed & Locally Verified
1. Native C++20 Freeze & Struct Engine (`app/src/main/cpp/fable_memscan.cpp`, `NativeMemoryScanner.kt`):
   - Added freeze rules: Exact (`=`), Min Floor (`>=`), Max Cap (`<=`).
   - Fixed `mem_set_freeze_impl` false-success bug: now verifies `pthread_create` and removes entry + returns `false` if worker cannot start.
   - Added canonical readback bytes (zero false `blocked_writes` on Float32/wrapped values) + per-second write rate tracking.
   - Added `nativePeekStruct` (`±64B..±256B` nearby struct field dissector).
   - Verified locally with `g++ -std=c++20 -O3 -Wall -Wextra -Werror` test binary (`NATIVE_V64_FREEZE_AND_STRUCT_TEST_OK=1`).
2. Stealth JS/WASM Instant-Replace Freeze & ES6 Prototype Accessor Engine (`WebsiteMemoryEngine.kt`, synced `memory_scanner.js`, `blocker.js`, `GeckoViewHost.kt`):
   - Fixed v63 accessor-destruction bug: own AND prototype `get`/`set` accessors (ES6 classes / TypeScript games) now use pass-through instant-replace traps calling `origSet.call(this, frozenVal)` immediately + background sync.
   - Added 1-level ES6 prototype getter/setter discovery during scan.
   - Fixed v63 Float32/scaled false-blocking loop using canonical raw-byte `targetBytes` comparison.
   - Captured native unpatched `setInterval`/`clearInterval`/`requestAnimationFrame`/`Date.now` at bootstrap + self-healing watchdog so `WebsiteSpeedEngine` or game `clearInterval` sweeps cannot slow down or kill Freeze.
   - Added conditional freeze rules (`eq`, `min`, `max`), honest `freezeBatch` (`ok: locked > 0`, `lockedIds`, `failedIds`), and `peekStruct` nearby struct dissector.
   - Raised cross-origin iframe `discoveryMs` in `GeckoViewHost.kt` so heavy Unity/CrazyGames iframes never drop `setFreeze`/`readBatch` at 110ms.
   - Added panel size bounds (`50%..160%` scale, `55%..150%` height).
   - Verified BOTH Blink and GeckoView engines locally in Node VM (`BLINK_V64_PASS=1`, `GECKO_V64_PASS=1`).

## Next Steps (Checkpoint 2 — continuing immediately)
1. Upgrade `MemoryEngineManager.kt` with confirmed (non-optimistic) freeze state, freeze rule cycling (`=`, `>=`, `<=`), quick math (`+100`, `+1K`, `x2`, `999999`), rename watch labels, live change highlight timestamps, automatic lost-freeze re-arm / honest `FAILED`/`DETACHED` status, and `peekNearbyStruct`.
2. Add `memoryEnginePanelHeightPct` to `SettingsStore.kt`, `SettingsViewModel.kt`, `BrowserScreen.kt`, and `MemoryEnginePanel.kt`.
3. Upgrade `PerformanceSettingsScreen.kt` (always visible even before toggling CE ON) with full CE Size Scale (`50%..160%`) + Height (`55%..150%`) sliders, presets, interactive live actual-size phone preview (Scan / Watch / Mini Pill), and explicit `Apply Size` / `Revert` / `Reset` controls showing exactly how it will look after applying.
4. Upgrade `MemoryEnginePanel.kt` with freeze rules (`=`, `>=`, `<=`), verified badges, quick math, Hex live multi-type decoder + `Nearby ±64B` struct finder, and live change highlights.
5. Upgrade `MemoryEngineTest.kt`, `MainActivity.kt`, and `ci/hook.sh` to strictly assert `blockedSum > 0`, ES6 accessor backing lock, Float32 zero-false-block, Min `>=` lock, and struct peek on Android API 34 x86_64 emulator across GeckoView, Blink x Gecko, and Native C++.
