# Fable Browser v70 HYPERION (`v70-ce-hyperion-1`, v1.70.0 / versionCode 62)

## Fixed from `v69-ce-titan-1` CI/Emulator Report
1. **Real Production Engine-Switch Bug (`GeckoViewHost.kt`)**: `CombinedEngineContainer.ensureBlinkView(tab)` previously returned a stale/destroyed `WebView` whose `tab.hybridController` had been cleared when toggling Blink OFF -> ON in the same tab. Now requires `tab.retainedBlinkView === currentAttached && tab.hybridController != null` and rebuilds cleanly.
2. **Tab Reuse Engine Override Bug (`MainActivity.kt`)**: Fixed `fable_reuse_tab` setting `cur.manualHybridOverride = wantHybrid` (was wrongly hardcoded `true`, breaking all GeckoView & Blink labs after tab reuse) and ensured `fable_test_crazygames_batch` waits for a live `hybridController != null` before running.
3. **Cold Start Boot Stabilization (`ci/hook.sh`)**: Waits for `pm path android` + 10s settle before first launch.

## Built in `v70-ce-hyperion-1`
1. **Full HTML5 Value Finding Engine (`WebsiteMemoryEngine.kt`, synced `memory_scanner.js`)**:
   - Passive read-only HTML5 Canvas2D text observer (`CanvasRenderingContext2D.prototype.fillText`/`strokeText`, masked `[native code]`) capturing live canvas-rendered HUD numbers without mutating drawing.
   - HTML5 Base64-encoded JSON storage scanner, re-encoding writer, and freezer (`lsb64` / `ssb64`).
   - Passive read-only IndexedDB (`IDBObjectStore.put`/`add`) save observer (`idb`).
   - Extended HTML5 DOM scanner covering open Shadow DOM (`shadowRoot`), `<svg><text>`/`<tspan>`, `<progress>`, `<meter>`, `[aria-valuenow]`, plus HTML5 framework/engine seeding (Construct 2/3, GDevelop, GameMaker, Impact, MelonJS, React Fiber, Vue).
2. **Physics UI-Pollution Fix**: Excludes website/wrapper chrome (`isWebsiteChromePath`) and separates `ui_clue` from `physics_candidate` so the Physics tab no longer shows website UI noise.
3. **Expanded Emulator Suite**:
   - Verified `.fable` E2E Backup & Restore (already confirmed PASS on API 34 emulator in v69: `bytes=1230`, `roundTripOk=true`, `emptyRej=true`, `badRej=true`, `wrongPwRej=true`).
   - New `ce-html5-lab.html` verified on GeckoView + Blink x Gecko.
   - Expanded live CrazyGames matrix to **96 real titles** (95 CrazyGames + `veck.io` = 96 titles across 8 batches of 12) measuring hits across WASM/JS/HTML5/Canvas2D/Base64/Physics, scan speed (`avg/p50/p95 ms`), non-destructive write/restore authority, engine breakdown, heap usage, and exact weaknesses.
