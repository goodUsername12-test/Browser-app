# Fable Browser v68 APEX (`v68-ce-apex-1`) Checkpoint

## Completed in Slice 1 (`v68-ce-apex-1`)
1. **JS/WASM/DOM/Worker Core Discovery Engine (`WebsiteMemoryEngine.kt` + synced `memory_scanner.js`)**:
   - Added full `u8`, `u16`, `u64` support alongside `i8`/`i16`/`i32`/`u32`/`i64`/`f32`/`f64`.
   - Fixed `auto` exact scan to discover unsigned 32-bit (`u32`: `2,147,483,648..4,294,967,295`) and unsigned 16-bit (`u16`: `32,768..65,535`) values instead of missing or reading them as negative numbers.
   - Added **Overflow Write-Guard** (`clampIntRange` / `parseBigIntExact`): writing an out-of-range number no longer silently wraps around into negative numbers; returns `overflowWarn` explaining the safe clamp and wider types.
   - Added **`diagnoseBinaryBytes` + `inspectTypes` + `retype`** commands and live **`typeHints`** in `readBatch` to diagnose signed `i32`/`i16`/`i8` overflow or float-bits-as-int and switch types in place without losing the address.
   - Expanded `scanBufferFallback` with **`~x1000`** fixed-point scaling and **`~f64rnd`** (truncated 64-bit double) discovery.
   - Added **`scanDomUiState`** (`kind: "dom"`) for `<input>`, `<textarea>`, `[contenteditable]`, `data-*` game attributes, and HUD counters, with full read, native-event write, and freeze support.
   - Upgraded **`testAuthority`** to perform **causal mirror-propagation testing**, cleanly distinguishing **`ui-authoritative`** and **`bidirectional-authoritative`** values from **`display-only-ui`** and **`externally-restored`** values, always restoring the original value.
   - Expanded **`findMirrors`** to search storage, DOM/UI state, and fallback scaled/truncated WASM copies.
   - Expanded **Worker/SharedArrayBuffer/Engine hooks** (`MessageChannel`, `BroadcastChannel`, `TypedArray.prototype.subarray`, Unity `Module.wasmMemory`, Godot `engine`, TurboWarp `vm`, CrazyGames/Poki SDK, Redux/store roots).
   - Verified byte-for-byte parity between `WebsiteMemoryEngine.kt` and `memory_scanner.js` and passed all 8 local Node VM stress suites (`JS_V68_APEX_ALL_8_SUITES_PASSED=1`).

## Next in Slice 2 (`v68-ce-apex-2`)
1. Upgrade `fable_memscan.cpp` + `NativeMemoryScanner.kt` with native overflow write-guard, `u32` in `auto` scan, and `nativeInspectTypes`.
2. Wire `MemoryEngineManager.kt`, `MemoryEnginePanel.kt`, `MemoryEngineTabs.kt`, and `BrowserScreen.kt` for:
   - Inline overflow/type-mismatch warning pill + 1-tap **Fix Type** & **Types** raw-byte multi-type inspector on Watch cards.
   - `Incl. UI` toggle and `+W` on UI copies in `CeFindSamePanel`.
   - Green/cyan styling for `ui-authoritative` & `bidirectional-authoritative` verdicts.
3. Add `ce-apex-lab.html` (testing u32 overflow, overflow guard, `inspectTypes`/`retype`, `~x1000`, `~f64rnd`, UI-authoritative `<input>` & `data-*`, display-only UI vs bidirectional, Worker SAB, zero-leak stealth on GeckoView & Blink) AND expand live CrazyGames testing to 7 real titles + `veck.io` in `ci/hook.sh` + `MainActivity.kt` + `MemoryEngineTest.kt`.
4. Run full local C++20/Node/Kotlin checks + GitHub Actions Build APK & Emulator verification until 100% green.
