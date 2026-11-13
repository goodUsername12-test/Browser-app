# Fable Browser v37 (1.37.0, versionCode 33)

## Summary of Changes in v37
1. **120 Hz / 120 FPS Hardware Enforcement (`HighRefreshRateEnforcer.kt`, `MainActivity.kt`, `BlinkGamingActivity.kt`, `GeckoViewHost.kt`, `fable_fast.cpp`)**:
   - Enforces 120 Hz on Xiaomi HyperOS / MIUI and Android 11-16 (`SDK 30-36`) via `Window.preferredDisplayModeId`, `Window.preferredRefreshRate = 120f`, `Window.setFrameRatePowerSavingsBalanced(false)` (disabling Android 15/16 Adaptive Refresh Rate 60 Hz power throttling), `View.setRequestedFrameRate(120f)`, and a `1x1` hardware `SurfaceView` voter calling `Surface.setFrameRate(120f, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)`.
   - Added `testufo.com`, `ufo.test`, `vsynctester.com`, and `displayhz.com` to `GAME_HOSTS` and `hybridCustomDomains`.
2. **Fixed Missing/Blank In-Game FPS Counter in Bloxd.io (`HybridEngineBridge.kt`, `SettingsStore.kt`)**:
   - Removed the `setTimeout(fn, 0, arg1, true)` blocker (`arg1.lpF` / `arg1.fY`) that prevented Bloxd's post-render callback from updating its in-game FPS counter, and changed `hybridSuppressPostRenderTimer` default to `false`.
3. **Fixed Page Reloading When Opening Tabs Screen and Returning (`BrowserTab.kt`, `GeckoViewHost.kt`, `TabManager.kt`)**:
   - Retained `retainedBlinkView: WebView?` wrapped in `MutableContextWrapper` on `BrowserTab`.
   - `GeckoViewHost.onRelease` detaches the `WebView` (`container.detachBlinkView()`) instead of destroying it when navigating to `TabsScreen` or `SettingsScreen`.
   - Added `doUpdateVisitedHistory` tracking and prevented `GeckoViewHost.update` from re-calling `loadUrl` on an already loaded `WebView`.
4. **Fixed Long-Press Paste & `staging.bloxd.io` Paste `ChunkLoadError` (`HybridEngineBridge.kt`, `GeckoViewHost.kt`, `ContentBlocker.kt`)**:
   - Exempted `<input>`, `<textarea>`, `<select>`, and `[contenteditable]` from `contextmenu`/`dblclick` suppression and `user-select: none` so Android's native long-press Paste / Select All popup works in Blink Hybrid mode.
   - Bridged `navigator.clipboard.readText()` and `writeText()` to Android `ClipboardManager` via `FableHybridBridge.getClipboardText()` / `setClipboardText()`, and granted `clipboard-read` / `clipboard-write` in `navigator.permissions.query`.
   - Fixed `ContentBlocker.shouldBlock` and `GeckoViewHost.shouldInterceptRequest` so same-site first-party JS chunks are never blocked by substring rules, plus added automatic dynamic `<script>` chunk retry.
5. **Built-In Zero-Lag Screen Recorder with Xiaomi/MIUI Settings (`ScreenRecorderManager.kt`, `ScreenRecorderSettingsScreen.kt`, `BrowserScreen.kt`, `MenuSheet.kt`, `fable_fast.cpp`)**:
   - Off-main-thread `PixelCopy` + `Surface.lockHardwareCanvas()` + hardware `MediaCodec` H.264 encoder pinned to little Cortex-A55 cores (`0..1`) while `CrRendererMain`/`CrGpuMain` stay pinned to big Cortex-A75 cores (`6..7`).
   - Includes all Xiaomi/MIUI settings: Resolution (`1640*720`, `800*480`), Video quality (`50Mbps`..`1Mbps`), Orientation (`Auto`, `Portrait`, `Landscape`), Sound source (`System sounds`, `Microphone`, `System + Mic`, `No sound`), Frame rate (`15fps`..`120fps`), `Zero-Lag Blink Surface Mode`, `End recording when screen is locked`, `Show touches`, and `Show button presses`.
