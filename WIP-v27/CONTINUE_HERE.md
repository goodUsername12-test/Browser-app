# WIP-v27 Checkpoint Continuation Guide

## Completed in v27 (GeckoView + Blink Combined Hybrid Mode Beta & Blink Gaming Mode)
1. **GeckoView + Blink Combined Engine (Beta) (`HybridEngineBridge.kt`, `GeckoViewHost.kt`, `BrowserTab.kt`, `TabManager.kt`)**:
   - Added a true dual-engine hybrid mode where **GeckoView** acts as the Privacy & Security Core (ContentBlocker EasyList/EasyPrivacy + Rust `NativeFastEngine`, WebRTC local-IP hiding, GPC/DNT signals, tracking-param stripping, and `SuperIdentity` / Incognito synchronization) while **Chromium Blink (`WebView`)** simultaneously handles 3D WebGL/WebGPU rendering, WebAssembly execution, multi-touch joystick/camera isolation, and fast `font-display: swap` layout in the same tab.
   - Added `shouldInterceptRequest` filtering in `CombinedEngineContainer` so every Blink subresource request is filtered by Fable's `ContentBlocker` and increments the tab's blocked-tracker shield badge in real time.
   - Wired `FableHybridBridge` (`@JavascriptInterface`) directly to `PageArchiver` so **Download page code (.zip)** and **Download memory snapshot (.zip)** work on-demand inside Combined Gecko+Blink tabs (capturing HTML, assets, `memory/runtime-state.json`, `localStorage`, `sessionStorage`, `computed-stylesheets.css`, `performance-resources.json`, and WebGL/2D canvas PNGs).
2. **Beta Settings & Per-Tab Menu Controls (`SettingsStore.kt`, `PerformanceSettingsScreen.kt`, `MenuSheet.kt`, `BrowserScreen.kt`, `BlinkGamingActivity.kt`)**:
   - Added **Beta: GeckoView + Blink Combined Engine** toggles in **Settings -> Performance** (`GeckoView + Blink Combined Mode (Beta)` and `Auto-combine on 3D game sites`).
   - Added **Gecko + Blink Combined (Beta)** live switch and **Open in Blink Gaming Mode** launcher in the browser menu (`MenuSheet.kt`).
   - Added `fable_hybrid` debug intent extra in `MainActivity.kt` and emulator verification step in `ci/hook.sh`.
