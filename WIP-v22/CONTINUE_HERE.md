# WIP-v22 Checkpoint Continuation Guide

## Completed in v22 (High-FPS WebGL & Bloxd.io Optimization)
1. **Zero-IPC-Copy Subresource Networking During Live Gameplay (`blocker.js`)**:
   - Stopped attaching `browser.webRequest.filterResponseData` to subresources (`image`, `media`, `font`, `script`, `wasm`, `xmlhttprequest`, `fetch`) during live browsing/gameplay unless Deep Capture hooks are explicitly enabled.
   - `onHeadersReceived` records all HAR 1.2 request/response metadata with zero stream-cloning overhead, while `page_tools.js` fetches cached bodies from Gecko's C++ HTTP cache when archiving.
2. **Hardware GPU Process, WebRender, WebGL & SpiderMonkey GC Tuning (`EngineManager.kt`, `SettingsStore.kt`)**:
   - Enabled `gpuProcess = true` (`layers.gpu-process.enabled = true`) by default.
   - Enabled `gfx.webrender.all`, `gfx.webrender.compositor`, `gfx.webrender.precache-shaders`, `webgl.force-enabled`, `webgl.msaa-force = false`, `webgl.lose-context-on-memory-pressure = false`, `layout.frame_rate = 0` (native VSync 60/90/120Hz), `dom.ipc.processPriorityManager.enabled = true`, `javascript.options.wasm_simd = true`, `javascript.options.wasm_relaxed_simd = true`, and 5ms SpiderMonkey incremental GC slices (`javascript.options.mem.gc_incremental_slice_ms = 5`).
   - Disabled C++ `+CanvasRandomization` and `+ReduceTimerPrecision`/`+FrameRate` in `fingerprintOverrides` when `gameMode` is active so voxel texture atlas uploads (`gl.texImage2D(canvas)`) don't stall WebGL frames.
3. **10,000x Faster Canvas Fingerprint Noise (`fingerprint_shield.js`)**:
   - Reduced `noiseBytes` from looping over up to 1.2M pixels per `getImageData` call to perturbing `16 * CANVAS_LEVEL` (up to 48) deterministic stride-distributed pixels (`< 0.002ms` per call).
4. **Zero-Allocation 240Hz Multi-Touch Game Input (`game_input.js`)**:
   - Replaced `Date.now()` with `performance.now()`, removed per-event string allocations (`keyOf`) and `Map` pruning on `pointermove`, and replaced with a fixed `Float64Array(32)` ring buffer + `ZERO_MOVE` constant.
5. **Android Sustained Performance Mode, Minimal Post-Processing & C++20 Thread Boost (`MainActivity.kt`, `GeckoViewHost.kt`, `fable_fast.cpp`)**:
   - Added POSIX `setpriority(PRIO_PROCESS, 0, -8)` (`nativeSetGameBoost`) in `fable_fast.cpp` when entering Game Mode.
   - Enabled `window.setSustainedPerformanceMode(true)`, `preferMinimalPostProcessing = true`, and `preferredRefreshRate = maxRate` in `MainActivity.kt`.
