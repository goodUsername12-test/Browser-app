# Fable Browser v38 (1.38.0, versionCode 34)

## Summary of Changes in v38
1. **Custom Fable Unified Engine Layer (`FableUnifiedEngine.kt`, `EngineManager.kt`, `TabManager.kt`, `BrowserTab.kt`, `GeckoViewHost.kt`)**:
   - Coordinates GeckoView (`GeckoRuntime` / `GeckoSession`) and Blink (`WebView` / Chromium V8 / WebGPU / WebGL2) as a unified browser engine.
   - Asynchronous startup pre-warming (`FableUnifiedEngine.prewarmAsync`) initializes C++ CPU topology (`NativeFastEngine.prewarm()`), pre-fetches default Blink User-Agent off the UI thread, and pre-warms `CookieManager`.
   - Unified Blink downloads (`setDownloadListener` -> `Downloader.enqueueUrl`), unified scroll toolbar auto-hide (`onScrollChanged` -> `tab.hybridScrollHandler`), unified long-press Link/Image Context Menu (`FableUnifiedEngine.extractContextMenuTarget` -> `ContextMenuSheet`) while preserving native Android long-press Paste/Select All on `<input>`, `<textarea>`, and `[contenteditable]`, and automatic Blink cookie flushing on app backgrounding.
2. **Blink 3D WebGL2 / WebGPU / OffscreenCanvas / PointerLock Hyper-Optimizations (`HybridEngineBridge.kt`)**:
   - Extended WebGL1/WebGL2 command-buffer state deduplicator (`useProgram`, `deleteProgram`, `activeTexture`, `bindTexture`, `deleteTexture`, `depthFunc`, `depthMask`, `cullFace`, `frontFace`, `blendFunc`, `blendFuncSeparate`, `enable`, `disable`, `viewport`, `scissor`) to eliminate redundant JS-to-GPU IPC calls in draw-call-heavy 3D games like Bloxd.io.
   - Pre-warms high-speed WebGL extensions (`OES_vertex_array_object`, `ANGLE_instanced_arrays`, `OES_texture_float_linear`, `OES_texture_half_float_linear`, `EXT_color_buffer_float`, `WEBGL_compressed_texture_astc`, `WEBGL_compressed_texture_etc`) and hooks `webglcontextlost` to trigger immediate C++ 8-core burst recovery.
   - Extends `powerPreference: "high-performance"` and `desynchronized: true` to `OffscreenCanvas.prototype.getContext` for Worker-rendered 3D scenes, plus adds a mobile `Element.prototype.requestPointerLock` compatibility shim.
3. **C++20 / ARM NEON Native Engine Upgrades (`fable_fast.cpp`, `NativeFastEngine.kt`)**:
   - Fixed JNI signatures for `nativeHash64(jbyteArray)` and `nativeMatchDomain(jstring, jstring)` so `NativeFastEngine.hash64` and `NativeFastEngine.matchDomain` execute directly in C++20 without JVM fallback.
   - Added ARM NEON SIMD (`#if defined(__ARM_NEON)`) 16-byte vectorized FNV-1a 64-bit hashing, `nativePrewarm()`, and `nativeRecordFrameDeltaNs(deltaNs, nowNs)` for lock-free 120 Hz EMA frame-rate tracking and automatic micro-stutter thread priority boosting (`-20` nice on `CrRendererMain`/`CrGpuMain`, plus `Maglev`/`TurboFan`/`Liftoff` worker boost).
4. **Automated Unit Tests (`UnifiedEngineTest.kt`)**:
   - Added unit tests covering 3D game URL detection, unified engine routing rules, first-party `staging.bloxd.io` chunk protection, and deterministic hashing/domain matching.
