# Fable Browser v62-multiframe-unity-wasm-1 Checkpoint

## Completed
1. True Cross-Origin Game `<iframe>` Multi-Frame Memory CE (BOTH GeckoView & Blink x Gecko):
   - CrazyGames Unity 6 games (including Turbo Trail `turbo-trail-txl`) execute inside cross-origin `games.crazygames.com` iframes.
   - GeckoView (`blocker.js` + `memory_scanner.js`): executes commands across ALL frames via `browser.tabs.executeScript({ allFrames: true })`, assigns per-frame IDs (`@f_...|`), merges all frame hits with WASM prioritized first, and routes writes/hex/watch to the exact owning iframe.
   - Blink x Gecko (`WebsiteMemoryEngine.kt` + `GeckoViewHost.kt`): relays commands recursively to child/nested cross-origin iframes via stealth private `postMessage` (`__mch === CH`), collects iframe responses via `FableHybridJsBridge.onFrameMemResult`, and merges results with `WebsiteMemoryEngine.mergeMultiFrameResults`.
2. Unity 6 512 MB Fast-Path WebAssembly Scanner (`WebsiteMemoryEngine.kt` + `memory_scanner.js`):
   - Raised scan cap from 32 MB to 512 MB so Unity 6 C#/IL2CPP managed heaps above 32 MB are scanned completely.
   - Replaced per-byte `DataView` scans with JIT-vectorized `Int32Array`/`Float32Array`/`Float64Array` fast paths (48 MB scanned in ~140ms).
   - Fixed `WeakRef` index shifting and `memory.grow()` detachment by storing direct `memRef`/`abRef` references on hits and scanning both `imports` and `exports` of `WebAssembly.instantiate`/`instantiateStreaming`/`Instance`.
   - Added automatic comma/space normalization (`4,976,216` -> `4976216`) across Kotlin and JS.
3. Expanded Automated Emulator Lab (`ci/hook.sh`, `MainActivity.kt`, `MemoryEngineTest.kt`):
   - Added a true cross-origin second HTTP server (port 18081 embedded inside port 18080) allocating a 48 MB Unity-style `WebAssembly.Memory` with `4,976,216` at offset 40 MB (`0x2800000`), verifying scan, write (`9999999`), HexDump, and `stealth=true` on BOTH GeckoView and Blink x Gecko.
   - Added live diagnostic probes of `https://veck.io` and `https://www.crazygames.com/game/turbo-trail-txl` on the emulator.
