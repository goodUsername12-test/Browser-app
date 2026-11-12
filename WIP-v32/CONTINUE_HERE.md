# WIP-v32 Checkpoint Continuation Guide

## Completed in v32 (Harden CI Emulator Hook Against ADB/SDKManager Hangs)
1. **Added Explicit Timeouts to `sdkmanager`, `adb shell getprop`, `screencap`, and `uiautomator dump` (`ci/hook.sh`)**:
   - Prevented `ci/hook.sh` from hanging for 40 minutes when a GitHub Actions runner experiences a stalled `adb` transport or `sdkmanager` download during AVD boot.
   - Includes all v31 fixes: Undo Close Tab disappearing loop fix (`TabsScreen.kt`, `TabManager.kt`), Combined Blink x Gecko camera anti-shutter (`HybridEngineBridge.kt`, `game_input.js`, `fable_fast.cpp`), and mid-frame physics lag spike elimination.
