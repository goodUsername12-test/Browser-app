# Fable Browser v68 APEX (`v68-ce-apex-2`, v1.68.0 / versionCode 60)

## Completed in `v68-ce-apex-2`
1. **JS/WASM/DOM/Worker Core Discovery Engine (`WebsiteMemoryEngine.kt` + synced `memory_scanner.js`)**:
   - Full `u8`, `u16`, `u64` + `i8`/`i16`/`i32`/`u32`/`i64`/`f32`/`f64` read/write support.
   - `auto` exact scan now discovers unsigned `u32` (`2,147,483,648..4,294,967,295`) and `u16` (`32,768..65,535`).
   - Safe Overflow Write-Guard (`clampIntRange` / `parseBigIntExact`): prevents silent negative wraparound when writing out-of-range numbers.
   - `diagnoseBinaryBytes` + `inspectTypes` + `retype` + live `typeHints` in `readBatch` to auto-diagnose signed `i32`/`i16`/`i8` overflow or float-bits-as-int and switch types in place without losing the address.
   - Expanded `scanBufferFallback` with `~x1000` fixed-point scaling and `~f64rnd` truncated double discovery.
   - Interactive DOM/UI State Scanner (`scanDomUiState`, `kind: "dom"`) for `<input>`, `<textarea>`, `[contenteditable]`, `data-*` game attributes, and HUD counters, with read, native-event write, and freeze support.
   - Causal `testAuthority`: snapshots matching mirrors before probing `+1` and checks propagation, accurately classifying `ui-authoritative`, `bidirectional-authoritative`, `ui-state-writable`, `verified-writable-game`, `display-only-ui`, `externally-restored`, and `read-only` while always restoring the original value.
   - Expanded `findMirrors` (`Find Same`) across WASM, JS, storage, DOM/UI state, and fallback scaled/truncated WASM copies.
   - Expanded Worker/SharedArrayBuffer/Engine hooks (`MessageChannel`, `BroadcastChannel`, `TypedArray.prototype.subarray`, Unity `Module.wasmMemory`, Godot, TurboWarp, CrazyGames/Poki SDK, Redux/store).
2. **Native C++20 Engine (`fable_memscan.cpp` + `NativeMemoryScanner.kt`)**:
   - Safe overflow clamping in `encode_value_bytes` & `encode_numeric_val_bytes`.
   - New `mem_inspect_types_impl` + JNI `nativeInspectTypes` returning `hex`, `i8..u64`, `f32`, `f64`, `/10`, `/100`, `/1000`, `diagnosis`, and `suggestedType`.
3. **Kotlin Manager & Mobile UI (`MemoryEngineManager.kt`, `MemoryEngineTabs.kt`, `MemoryEnginePanel.kt`, `BrowserScreen.kt`)**:
   - Added 1-tap `Types` button on every Watch card + inline `CeTypeInspectCard` showing all live raw-byte interpretations with 1-tap type switching.
   - Added inline overflow/type-mismatch warning pill with 1-tap `Fix->u32/f32/...` on Watch cards.
   - Added `Incl. UI` toggle and `+W` on UI copies inside `CeFindSamePanel` for UI-authoritative games.
   - Updated authority verdict display so `ui-authoritative` & `bidirectional-authoritative` render positively instead of flagging as display-only warnings.
4. **Expanded Emulator Verification & CrazyGames Suite (`ci/hook.sh`, `MainActivity.kt`, `MemoryEngineTest.kt`)**:
   - Added `ce-apex-lab.html` verified on BOTH GeckoView and Blink x Gecko (`u32`, overflow guard, `inspectTypes`/`retype`, `~x1000`, `~f64rnd`, UI-authoritative `<input>` vs display-only HUD, zero-leak stealth).
   - Expanded live CrazyGames emulator battery to 7 titles (`turbo-trail-txl`, `ragdoll-archers`, `space-waves`, `smash-karts`, `crazy-flips-3d`, `capybara-clicker`, `shellshockersio`) plus `veck.io`.

## Verification Status
- Local Node VM 8-suite stress test: `JS_V68_APEX_ALL_8_SUITES_PASSED=1`
- Local C++20 `g++ -std=c++20 -O3 -Wall -Wextra -Werror`: `NATIVE_V68_APEX_TEST_PASS=1`
- Local parity/syntax (`node --check`, `bash -n`, embedded Python compile, Kotlin brace/paren audit): PASSED
- Next: Monitor GitHub Actions Build APK & Android API 34 Emulator `HOOK-RESULT` / `CI-RESULT` for `v68-ce-apex-2` and fix any issue until 100% green.
