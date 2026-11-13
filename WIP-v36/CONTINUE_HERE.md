# WIP-v36 Checkpoint Continuation Guide

## Completed in v36 (Hybrid GeckoView + Blink Privacy Shield Parity, Real HTTP DNT/Sec-GPC Headers, WebGPU/3D/2D & Teleport Burst Acceleration)
1. **Real HTTP `DNT: 1`, `Sec-GPC: 1`, and `Accept-Language` Headers in Blink Hybrid Mode (`BrowserTab.kt`, `TabManager.kt`, `GeckoViewHost.kt`)**:
   - Added `hybridCustomUserAgent` and `hybridExtraHeadersProvider` callbacks on `BrowserTab`, wired in `TabManager.wireHybridCallbacks`.
   - `GeckoViewHost` now attaches `DNT: 1`, `Sec-GPC: 1`, and normalized `Accept-Language` headers on initial `loadUrl`, `reload`, and main-frame `GET` link navigations via `shouldOverrideUrlLoading`, ensuring EFF Cover Your Tracks (`coveryourtracks.eff.org`) sees `DNT Header Enabled? True`.
   - Sanitized WebView User-Agent (stripping `; wv)` and `Version/4.0 `) and applied Fable privacy User-Agent / Super-Incognito User-Agent when privacy shield is enabled.
2. **Full Gecko-Grade Anti-Fingerprinting in Blink Hybrid Mode (`HybridEngineBridge.kt`)**:
   - Enabled Blink fingerprint shield whenever `normalTabShield`, `incognitoFingerprintProtection`, or `Super-Incognito` is active on non-game pages.
   - Masked `navigator.userAgentData` (Client Hints), `navigator.language`/`languages` (`en-US`), `navigator.platform` (`Linux armv81`), `navigator.hardwareConcurrency` (`8`), `navigator.deviceMemory` (`8`), and `navigator.maxTouchPoints` (`5`).
   - Normalized `Screen` dimensions (`360x800x24`), `Date.prototype.getTimezoneOffset` (`0`), and `Intl.DateTimeFormat.prototype.resolvedOptions().timeZone` (`UTC` or Super-Incognito timezone).
   - Masked WebGL `UNMASKED_VENDOR_WEBGL` (`37445`) and `UNMASKED_RENDERER_WEBGL` (`37446`) so device-specific GPUs (`ARM~Mali-G52 MC2`) are not exposed to trackers, while adding subtle deterministic session noise to Canvas 2D `getImageData`/`toDataURL`, `AudioBuffer.prototype.getChannelData`, and `speechSynthesis.getVoices()`.
3. **WebGPU Boost, 3D/2D Canvas Desynchronized Acceleration, C++ Multi-Core Teleport Burst & Gecko Hibernation (`HybridEngineBridge.kt`, `fable_fast.cpp`, `NativeFastEngine.kt`, `EngineManager.kt`, `SettingsStore.kt`, `HybridBetaSettingsScreen.kt`)**:
   - Added 4 new editable toggles in `HybridBetaSettingsScreen.kt` (total 21 Beta Combined Engine controls): `hybridWebGpuBoost`, `hybrid3d2dAccel`, `hybridTeleportStabilizer`, and `hybridHibernateGecko`.
   - Added C++ `notifyHeavyLoadBurst` JNI entry point in `fable_fast.cpp` and `FableHybridBridge.onHeavyBurst()` JS bridge in `GeckoViewHost.kt`, triggered automatically when `requestAnimationFrame` detects a teleport/chunk-meshing frame spike (`> 28 ms`) or WebGPU device loss.
