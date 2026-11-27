# Fable Browser v66 Continuation (`v66-ce-titan-6`, v1.66.0 / versionCode 58)

## Completed in `v66-ce-titan-6`
1. **Instant Auto-Hook on Page Load & Refresh (No Manual "Hook Reload" Needed)**:
   - Fixed `blocker.js` `config` handler missing `applyMemoryScannerScript()`, added immediate injection into open tabs on enable, and added lightweight `browser.tabs.onUpdated` fallback (strictly zero-load when OFF).
   - Fixed `FableWebGpuManager.installDocumentStartBootstrap` script-cache check so `onPageStarted` never tears down & races identical `DocumentStartJavaScript` scripts on Blink child iframes.
   - Added seamless auto-inject retry in `GeckoViewHost.runMemoryCommand` when a Blink page reports `notHooked: true`.
   - Added `docGen` + `hookedEarly` telemetry on `ping`/`readBatch`/scans and wired `BrowserScreen` tab `(url, loading)` into `MemoryEngineManager.onActiveTabPageStateChanged`, automatically verifying auto-hook (`Auto✓` header badge) and auto-reattaching + re-arming frozen Watch entries after page reload.
2. **TITANIUM MAX Freeze Engine (3 Selectable Tiers: `Normal`, `Strong`, `MAX`)**:
   - Native C++20 (`fable_memscan.cpp` + `NativeMemoryScanner.kt`): `nativeSetFreezeStrength(0/1/2)` with adaptive cadence up to `400us` (~2500 Hz) fighting + read-after-write verification in `MAX` mode, live `rate` (`writesPerSec`), raised scan limits (`400k` results, `96MB` unknown snapshot, 8-step undo), plus `nativeFindMirrors`.
   - Page/WASM (`memory_scanner.js` + `WebsiteMemoryEngine.kt`): hooked `WebAssembly.Memory.prototype.grow` for zero-drop freeze survival across WASM heap growth; hardened `Map` freezes (`set`, `delete`, AND `clear`); `MAX` mode bounded `MessageChannel` micro-bursts (~1000 Hz fighting with <1ms latency) + automatic JS object path rebind on GC/level reload; `findMirrors` op + 1-tap `Lock All Mirrors`.
3. **Expanded Memory Discovery + Authority Classification (Solves `_setString` UI-Label Confusion)**:
   - Weakly captures closure-private game objects via `Map.prototype.set`, `Set.prototype.add`, `WeakMap.prototype.set`, `ArrayBuffer.prototype.slice`, `Response.prototype.arrayBuffer/json` (`WeakRef` ring buffers, `[native code]` masked).
   - Expanded engine roots (Cocos Creator 2/3, SystemJS, Webpack chunks, Laya, Egret, CreateJS, Three.js, Spine, ImpactJS, Crafty, MelonJS) + deeper traversal (`110k` nodes, depth `18`, 4-level prototype accessor chain up to depth `14`, non-enumerable props up to depth `8`, Symbols up to depth `5`, JS `BigInt`).
   - Smart Authority Classifier (`wasm`/`buf`/`game`/`store`/`ui` + score) sorts gameplay hits ABOVE UI display labels (`_node._parent._string`, `Label`, `text`), adds `HideUI` toggle chip, 8-step live Value Timeline on Watch entries, and automatic amber UI-copy warning with 1-tap `Find Real` when a watched property or Write-Watcher caller (`_setString`, `setText`, `updateLabel`) is a display copy.
4. **Honest Safety & Scope**:
   - Local client-side browser JS/WASM/storage and Fable own-process memory only; zero multiplayer/server tampering, zero WebSocket/fetch mutation, zero other-app memory access, zero-load when OFF, `:local-bloxd` preserved byte-for-byte.

## Local Verification Passed
- C++20 `-std=c++20 -O3 -Wall -Wextra -Werror`: `NATIVE_V66_TITAN_TEST_PASS=1`
- Node VM Blink + GeckoWebIDL Suite: `JS_V66_BLINK_PASS=1`, `JS_V66_GECKO_WEBIDL_PASS=1`, `JS_V66_ALL_TESTS_PASS=1`
- Byte-for-byte parity between `WebsiteMemoryEngine.kt` and `memory_scanner.js`: `V66_PARITY_CHECK_PASS=1`
- `node --check` on `memory_scanner.js` and `blocker.js`, `bash -n ci/hook.sh`.

## Next Step
- Monitor GitHub Actions `Build APK` for `v66-ce-titan-6` (Debug APK, unit tests, Release APK, Android API 34 x86_64 Emulator verification) and address any CI findings if any appear.

## v66-ce-titan-6 Fixes
- Fixed 3 Kotlin string escape typos reported by Build #115 (`MemoryEngineManager.kt:649`, `WebsiteMemoryEngine.kt:358`, `GeckoViewHost.kt:1212`).

## v66-ce-titan-6 Fix + New Rule
- Split `MemoryEnginePanel.kt` into modular `@Composable` functions (`CeWatchTabContent`, `CeHexTabContent`, `CeMapTabContent`) to eliminate JVM 64KB `MethodTooLargeException` on `MemoryEnginePanel$lambda$115`.
- Added rule: Always wait until the triggered GitHub Actions build + unit tests + release + emulator verification is 100% finished with zero errors before finishing.

## v66-ce-titan-6 Fixes (From Build #117 Emulator Telemetry)
- Restored `500Hz`/`500Hz-XOR` mode label for default `Strong` native freeze (`2500Hz` for `MAX`) and added high-entropy word filter in `SC_XOR` auto-key discovery.
- Added `internalOpDepth` guard in `memory_scanner.js` / `WebsiteMemoryEngine.kt` so scanner internal `watchRefs.set` and `Set` operations never self-pollute `closureMapRefs`/`closureObjRefs`, and synced `lastKnownDocGen` on scan responses.

## v66-ce-titan-6 Fixes
- Fixed `MainActivity.kt` native XOR self-check symmetry overwrite (testing offset 32 no longer corrupts `xKey` at offset 32 before testing offset 36).
- Restored fast `cur.depth < 12` traversal bound in `collectJsAndTypedArrays` and gave `ultra_gecko` 11s in `ci/hook.sh` so `ultra_blink` never navigates the tab mid-test.
