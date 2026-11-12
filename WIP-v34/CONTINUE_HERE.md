# WIP-v34 Checkpoint Continuation Guide

## Completed in v34 (Full Editable Settings Screen for Beta GeckoView + Blink Combined Engine)
1. **Dedicated `HybridBetaSettingsScreen.kt` (`Routes.SETTINGS_HYBRID_BETA`)**:
   - Added a full settings page accessible from **Settings -> Beta: Gecko + Blink Engine** and **Settings -> Performance -> Edit all Beta Gecko + Blink settings**.
   - Exposed all 17 configurable Beta Combined Engine controls:
     - Master toggle (`Enable GeckoView + Blink Combined Engine (Beta)`).
     - Routing mode (`Auto-combine only on 3D games & benchmarks` vs. all websites).
     - Persistent per-tab/per-site state across app updates + one-tap `Reset saved per-site Beta overrides`.
     - Editable comma-separated domain list (`hybridCustomDomains`) for automatic Combined Engine activation.
     - Native C++ Big-Core (`Cortex-A75`) Thread Boost toggle (`hybridNativeCpuBoost`).
     - 1 ns Kernel Timer Slack (`PR_SET_TIMERSLACK`) toggle (`hybridZeroTimerSlack`).
     - Reported CPU core count slider (`2..16 cores`, `hybridReportedCores`) for WebAssembly chunk-meshing workers.
     - Offscreen tile pre-rasterization toggle (`hybridOffscreenPreRaster`).
     - Native C++ same-origin & font CDN fast-path toggle (`hybridCppFastAssetBypass`).
     - High-Performance WebGL/WebGPU promoter toggle (`hybridHighPerfWebGl`).
     - Camera anti-shutter & multi-touch ID lock toggle (`hybridCameraAntiShutter`).
     - Ultra-fast 20+ CPS multi-click toggle (`hybridZeroDelayClicks`) + Canvas layout cache duration slider (`0..1000 ms`, `hybridCanvasRectCacheMs`).
     - Suppress redundant post-render physics timer toggle (`hybridSuppressPostRenderTimer`).
     - Instant font swap (`font-display: swap`) & CDN preconnect toggle (`hybridFastFontSwap`).
     - GeckoView filter-list tracker blocking in Blink (`hybridGeckoAdBlock`) and WebRTC local-IP hiding in Blink (`hybridHideLocalIp`).
