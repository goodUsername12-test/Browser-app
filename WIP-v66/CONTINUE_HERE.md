# Fable Browser v66 Continuation (`v66-ce-titan-1`, v1.66.0 / versionCode 58)

## Completed in `v66-ce-titan-1`
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
- Monitor GitHub Actions `Build APK` for `v66-ce-titan-1` (Debug APK, unit tests, Release APK, Android API 34 x86_64 Emulator verification) and address any CI findings if any appear.
