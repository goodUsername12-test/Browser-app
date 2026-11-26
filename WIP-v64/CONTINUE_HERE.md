# Fable Browser v64-ce-power-2 Checkpoint (1.64.0, versionCode 56)

## Completed & Locally Verified
1. Fixed ALL v63 Freeze Bugs & Upgraded Freeze to Zero-Slip Instant-Replace + Conditional Rules (`=`, `>=`, `<=`):
   - **JS Accessor & Prototype Trap Fix (`WebsiteMemoryEngine.kt`, `memory_scanner.js`)**: Own and prototype `get`/`set` accessors (ES6 classes / TypeScript games like Phaser, PlayCanvas, Babylon, Unity JS interop) now use pass-through instant-replace traps that intercept bad writes AND immediately write the frozen value through `origSet.call(this, frozenVal)`, plus background 250 Hz sync when backing fields are mutated directly. Cleanly restores descriptors on unfreeze.
   - **ES6 Class Prototype Accessor Discovery**: `collectJsAndTypedArrays` now inspects 1-level class prototype getter/setter pairs (`Object.getPrototypeOf(obj)`).
   - **Zero False Blocked Counts**: Binary WASM / ArrayBuffer / TypedArray freeze now stores canonical raw bytes (`captureTargetBytes` + `binaryBytesDiffer`) immediately after write so `f32`/`f64`/`i16`/`~x10` values never trigger fake 250 Hz self-blocking loops.
   - **Native Timer Immunity**: Captures unpatched `setInterval`/`clearInterval`/`requestAnimationFrame`/`Date.now` at bootstrap plus a self-healing `ensureFreezeLoopAlive()` watchdog so `WebsiteSpeedEngine` (0.1x) or game `clearInterval` sweeps cannot slow down or kill Freeze.
   - **Cross-Origin Iframe Timeout Fix (`GeckoViewHost.kt`)**: Raised child-frame `discoveryMs` (up to `600ms` for targeted `setFreeze`/`write`/`peekStruct`, `380ms` for batch ops) so heavy Unity/CrazyGames iframes do not drop freeze commands at 110ms.
   - **Confirmed (Non-Optimistic) Freeze State (`MemoryEngineManager.kt`, `fable_memscan.cpp`, `blocker.js`)**:
     - `fable_memscan.cpp` verifies `pthread_create`, removes entry & returns `false` if worker cannot start, supports Exact (`=`), Min Floor (`>=`), and Max Cap (`<=`) rules, and tracks accurate per-second `rate` and `blocked_writes`.
     - `freezeBatch` returns `ok: locked > 0` with `lockedIds` & `failedIds`.
     - `MemoryEngineManager` confirms every single and batch freeze against backend responses, shows `Arming...` -> `Instant Trap` / `Accessor Trap` / `250Hz Lock` / `500Hz Native`, and honestly reverts + displays `FREEZE FAILED` / `DETACHED` if not installed. Automatically re-arms lost freezes on active pages.
2. Made CE Much More Powerful (`MemoryEngineManager.kt`, `MemoryEnginePanel.kt`, `WebsiteMemoryEngine.kt`, `fable_memscan.cpp`):
   - **Freeze Rules (`=`, `>=`, `<=`)**: 1-tap rule cycler chip on every Watch row (`=` Exact Lock, `>=` Min Floor allowing increases, `<=` Max Cap allowing decreases).
   - **Quick Math & Custom Labels**: Inline `+100`, `+1000`, `x2`, `x10`, `999999`, `0` buttons and custom Watch label renaming.
   - **Hex Live Multi-Type Decoder + Nearby `±64B` Struct Dissector (`Struct` button in Hex tab)**: Decodes `i16`/`i32`/`f32`/`f64` live at the inspected address and lists nearby non-zero struct fields (`-0x40..+0x40`) with 1-tap `+Watch` across both Native C++ and Page/WASM memory.
3. CE Panel Size in Settings + Interactive Live Preview Before Applying (`SettingsStore.kt`, `SettingsViewModel.kt`, `PerformanceSettingsScreen.kt`, `SettingsScreen.kt`, `MemoryEnginePanel.kt`, `BrowserScreen.kt`):
   - Always visible under **Settings -> Performance -> Memory Engine (Mobile Cheat Engine)** even when CE is OFF (and surfaced in main Settings subtitle).
   - Added **Scale (`50%..160%`)** + **List Height (`55%..150%`)** sliders + 5 quick presets (`Mini`, `Compact`, `Normal`, `Large`, `XL`).
   - Added **Live Actual-Size Preview** switching between **Scan**, **Watch/Freeze**, and **Mini pill** with explicit `"Saved -> Preview after Apply"` indicator and **`Apply Size` / `Revert` / `Reset`** buttons.
4. Honest Automated Verification (`MemoryEngineTest.kt`, `MainActivity.kt`, `ci/hook.sh`):
   - Emulator `multi_freeze` now hammers Phaser's real backing `_pCoins` accessor variable (`accLocked=true`), requires `blockedSum > 0`, and verifies zero failed locks across GeckoView and Blink x Gecko.
   - Native emulator check verifies `blocked > 0`, `MIN (>=)` lock behavior, and `peekStruct`.

## Verification Status
- Local C++20 `g++ -std=c++20 -O3 -Wall -Wextra -Werror` test binary: PASS (`NATIVE_V64_FREEZE_AND_STRUCT_TEST_OK=1`).
- Local Node VM Blink & GeckoView test suite: PASS (`BLINK_V64_PASS=1`, `GECKO_V64_PASS=1`).
- Kotlin delimiter audit (11 modified files), JS syntax check, `ci/hook.sh` bash & Python AST checks, `local-bloxd` SHA-256 intact: PASS.
- GitHub Actions APK Build, Unit Tests, and Android API 34 x86_64 Emulator verification: pending CI run for `v64-ce-power-2`.
