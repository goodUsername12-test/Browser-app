# WIP-v28 Checkpoint Continuation Guide

## Completed in v28 (Unified GeckoView + Blink Single-Pipeline Engine, Blink Camera Coalescer & Zero-FPS-Drop Click Engine)
1. **Native C++ Blink-Algorithm Touch/Camera Coalescer (`fable_fast.cpp`, `NativeFastEngine.kt`, `GeckoViewHost.kt`)**:
   - Added lock-free C++ pointer state tracking (`reset_touch_pointer`, `filter_camera_move`) in `libfable_fast.so` and wired `CombinedEngineContainer.dispatchTouchEvent` so both GeckoView and Blink share the same native C++ touch/camera/click pipeline.
   - Disabled Android UI-thread click sound effects (`isSoundEffectsEnabled = false`) and haptic IPC (`isHapticFeedbackEnabled = false`) on `CombinedEngineContainer`, `GeckoView`, and `WebView` so clicks never block the UI thread on AudioService/Vibrator Binder IPC.
2. **Blink VSync Camera Coalescer & Zero-FPS-Drop Click Engine (`game_input.js`, `HybridEngineBridge.kt`)**:
   - Coalesces high-frequency single-finger and multi-finger `touchmove` camera updates to at most 1 update per VSync window (`~7.2 ms` for 90-120 Hz) while preserving full accumulated delta so camera movement never triggers multiple 3D raycasts in the same frame.
   - Added `getBoundingClientRect()` caching on game canvases (`250 ms` TTL, invalidated on resize) so clicks (`pointerdown` / `mousedown` / `click`) and touch drags never trigger synchronous DOM layout reflows.
   - Suppressed synthetic zero-delta `mousemove` hover events during touch taps (`< 90 ms` after touch) and disabled `-webkit-tap-highlight-color` repaints so clicking in Bloxd eats zero FPS.
3. **Default Unified GeckoView + Blink Mode for 3D Games (`SettingsStore.kt`)**:
   - Enabled `hybridGeckoBlinkBeta = true` and `hybridAutoForGames = true` by default so 3D games automatically run on the Unified GeckoView (Privacy Core) + Blink (3D/Camera/Click Engine) pipeline out of the box.
