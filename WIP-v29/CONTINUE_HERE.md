# WIP-v29 Checkpoint Continuation Guide

## Completed in v29 (Zero-Copy Direct Hardware Rendering & Smart Unified Engine Routing)
1. **Eliminated Offscreen FBO Layer Copy (`GeckoViewHost.kt`, `BlinkGamingActivity.kt`)**:
   - Removed `setLayerType(View.LAYER_TYPE_HARDWARE, null)` from `WebView` so Chromium Blink composites directly into the hardware-accelerated window surface without allocating an extra offscreen hardware bitmap layer or stalling `HardwareRenderer.nSyncAndDrawFrame`.
2. **Smart Per-URL Unified Engine Routing (`TabManager.kt`, `BrowserTab.kt`, `HybridEngineBridge.kt`)**:
   - Added `manualHybridOverride` on `BrowserTab` so manual toggles in the menu persist for that tab, while automatic Unified Gecko+Blink routing seamlessly activates Blink 3D rendering on 3D game URLs (`bloxd.io`, `poki.com`, `crazygames.com`, `krunker.io`, `?hybrid=1`, etc.) and returns to GeckoView on normal non-game URLs (`dust.tt`, `wikipedia.org`, etc.), while keeping the Native C++ `fable_fast` touch/camera/click pipeline active everywhere.
