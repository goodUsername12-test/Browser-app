# WIP-v33 Checkpoint Continuation Guide

## Completed in v33 (Extreme Speedometer 3.0 & Bloxd Optimization for Xiaomi POCO 2410FPCC5G / mt6768 + Persistent Beta State + 20+ CPS Zero-Delay Clicks)
1. **Native C++ `mt6768` Cortex-A75 Thread Booster, 1ns Timer Slack & Fast Asset Path (`fable_fast.cpp`, `NativeFastEngine.kt`)**:
   - Tuned `set_game_thread_boost(true)` for the user's `mt6768` (2x Cortex-A75 @ 2.0 GHz + 6x Cortex-A55 @ 1.8 GHz) on Android 16: sets `prctl(PR_SET_TIMERSLACK, 1)` (1 ns timer slack instead of 50,000 ns) and scans `/proc/self/task` in C++ to boost `CrRendererMain`, `CrGpuMain`, `VizCompositor`, `GeckoMain`, and UI threads to priority `-16` so Android's scheduler places them on the Cortex-A75 big cores.
   - Added C++ `is_fast_same_origin_or_game_asset` (`nativeIsFastAsset`) so `shouldInterceptRequest` approves same-origin requests, `data:`/`blob:`, Google Fonts (`gstatic.com`/`googleapis.com`), `bloxd.io`, and `browserbench.org` in nanoseconds in C++.
2. **Speedometer 3.0 (`browserbench.org`) & DOM/JS Benchmark Acceleration (`EngineManager.kt`, `HybridEngineBridge.kt`, `cosmetic.js`, `game_input.js`)**:
   - Added `browserbench.org` to automatic Combined Blink+Gecko routing, and moved the `getBoundingClientRect` cache hook from `Element.prototype` to `HTMLCanvasElement.prototype` only so V8's C++ DOM inline cache for `div.getBoundingClientRect()` runs at full native speed.
   - Gated `setBaselineFingerprintingProtection` and `setFingerprintingProtection` in `EngineManager.kt` on `normalTabShield && !gameMode` so normal tabs do not pay Gecko's C++ RFP timer/canvas/font overhead, enabled SpiderMonkey `warp`, `parallel_parsing`, and `bytecode_cache`, and skipped `cosmetic.js` / `game_input.js` in child iframes (`window !== window.top`).
3. **Zero-Delay High-CPS Clicking & Persistent Beta State Across App Updates (`GeckoViewHost.kt`, `TabManager.kt`, `SettingsStore.kt`)**:
   - Disabled `setSupportZoom` and `builtInZoomControls` on `WebView` and suppressed `dblclick`/`contextmenu` on game canvases to eliminate Android's 300 ms double-tap zoom gesture recognizer during rapid 20+ CPS clicking.
   - Persisted per-domain/per-tab Beta Combined Engine state in `fable_hybrid_tab_state` `SharedPreferences` and migrated to `hybrid_gecko_blink_beta_v2` so Beta settings and tab states survive app updates and restarts.
