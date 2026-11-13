# WIP-v36 Checkpoint Continuation Guide

## Completed in v36 (Full Hybrid GeckoView + Blink Privacy Shield Parity, Real HTTP DNT/Sec-GPC Headers, WASM/WebGPU/WebGL/Teleport Acceleration & Screen-Recorder Pacing)
1. **Real HTTP `DNT: 1`, `Sec-GPC: 1`, and `Accept-Language` Headers in Blink Hybrid Mode (`BrowserTab.kt`, `TabManager.kt`, `GeckoViewHost.kt`, `BlinkGamingActivity.kt`)**:
   - Added `hybridCustomUserAgent` and `hybridExtraHeadersProvider` callbacks on `BrowserTab` and wired them in `TabManager.wireHybridCallbacks`.
   - `GeckoViewHost` and `BlinkGamingActivity` now attach real HTTP `DNT: 1`, `Sec-GPC: 1`, and normalized `Accept-Language` headers on initial `loadUrl`, `reload`, and main-frame `GET` link navigations via `shouldOverrideUrlLoading`, ensuring EFF Cover Your Tracks (`coveryourtracks.eff.org`) sees `DNT Header Enabled? True`.
   - Sanitized the WebView User-Agent (stripping `; wv)` and `Version/4.0 `) and applied the normalized Fable privacy User-Agent or active Super-Incognito User-Agent whenever privacy shielding is enabled.
2. **Full Gecko-Grade Anti-Fingerprinting in Blink Hybrid Mode (`HybridEngineBridge.kt`)**:
   - Enabled Blink fingerprint shield whenever `normalTabShield`, `incognitoFingerprintProtection`, or `Super-Incognito` is active on non-game pages.
   - Masked `navigator.userAgentData` (Client Hints), `navigator.language`/`languages` (`en-US`), `navigator.platform` (`Linux armv81`), `navigator.hardwareConcurrency` (`8`), `navigator.deviceMemory` (`8`), and `navigator.maxTouchPoints` (`5`).
   - Normalized `Screen` dimensions (`360x800x24`), `Date.prototype.getTimezoneOffset` (`0`), and `Intl.DateTimeFormat.prototype.resolvedOptions().timeZone` (`UTC` or Super-Incognito timezone).
   - Masked WebGL `UNMASKED_VENDOR_WEBGL` (`37445`) and `UNMASKED_RENDERER_WEBGL` (`37446`) so device-specific GPUs (`ARM~Mali-G52 MC2`) are not exposed to trackers, while adding deterministic session noise to Canvas 2D `getImageData`/`toDataURL`, `AudioBuffer.prototype.getChannelData`, and `speechSynthesis.getVoices()`.
3. **WASM Module Cache & Streaming Fallback, WebGPU Shader Cache, WebGL State Deduplication & Teleport/Chunk-Worker C++ 8-Core Burst (`HybridEngineBridge.kt`, `fable_fast.cpp`, `NativeFastEngine.kt`, `MainActivity.kt`, `BlinkGamingActivity.kt`)**:
   - Added in-memory `WebAssembly.Module` FNV-1a cache + `compileStreaming`/`instantiateStreaming` fallback + automatic C++ 8-core burst during `.wasm` compilation.
   - Added WebGPU `powerPreference: "high-performance"` adapter promoter, `device.createShaderModule` WGSL deduplication cache, and `device.lost` burst recovery.
   - Added WebGL/WebGL2 redundant state-call deduplication (`useProgram`, `depthFunc`, `viewport`), `desynchronized: true` 3D/2D game canvas acceleration, async `HTMLImageElement` player-skin decoding, and `Worker.prototype.postMessage` (`> 64 KB`) + `requestAnimationFrame` (`> 28 ms`) C++ multi-core burst triggers.
   - Added Android `DisplayManager` screen-recorder detection (`MainActivity.kt` -> `NativeFastEngine.setScreenRecording` -> `fable_fast.cpp`) to reserve a little core for hardware `MediaCodec` video encoding during gameplay recording.
