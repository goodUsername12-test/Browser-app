# Fable Browser v40 (1.40.0, versionCode 36)

## Summary of Changes in v40
1. **Added Native Rust Core (`rust/fable_rust_core` -> `libfable_rust.so`, `RustFastCore.kt`, `.github/workflows/build-apk.yml`)**:
   - Added zero-dependency native Rust power & thermal governor crate compiled with `opt-level = 3`, fat `lto = true`, `codegen-units = 1`, `panic = "abort"`, and 16KB page alignment (`-Wl,-z,max-page-size=16384`) for `arm64-v8a` and `x86_64`.
   - Computes target display refresh rate, target in-game FPS cap, 3D render scale %, CPU boost mode, and Choreographer state in nanoseconds, with an identical Kotlin fallback for JVM unit tests.
2. **Full Granular Battery & Eco Gaming Settings (`BatterySaverSettingsScreen.kt`, `FableBatteryGovernor.kt`, `SettingsStore.kt`)**:
   - Added 12 user-configurable battery controls (accessible from Settings > Battery & Eco Gaming and Performance):
     - Master Battery Saver + Auto-enable threshold (Off, 15%, 20%, 30%, 50%)
     - Android ADPF Thermal Overheat Guard (`PowerManager.OnThermalStatusChangedListener`)
     - In-Game Max FPS & Refresh Cap (`120`, `90`, `60`, `45`, `30` FPS)
     - In-Game 3D Render Resolution Scale (`100%`, `85%`, `75%`, `60%` DPR scaling to cut Mali/Adreno GPU fill-rate power by up to 64%)
     - Eco CPU Core Scheduling in Games
     - AFK Untouched In-Game FPS Drop (`60`, `30`, `15` FPS after `4..30s`) with instant first-touch wake
     - Extra OLED Hardware Scrim Dimmer (`0%`, `10%`, `20%`, `35%`)
     - Dynamic 60/120 Hz Website Refresh, Background Tab Deep-Freeze, and `prefers-reduced-motion` animation stopper
     - 1-Tap Eco Gaming Preset and Competitive 120 Hz Preset, applied live to open game tabs via `window.__fableSetPowerPlan(...)` without reloading.
3. **Speedometer & Normal-Website Zero-Overhead Fast Path (`HybridEngineBridge.kt`, `GeckoViewHost.kt`)**:
   - Separated benchmark hosts (`browserbench.org`, `web.basemark.com`, `html5test.com`) from `isPure3dGameUrl` so DOM benchmarks and normal websites still get Blink Hybrid speed without `requestAnimationFrame` JNI burst calls, `Worker.postMessage` hooks, `HTMLImageElement.src` hooks, or continuous 120 Hz `Choreographer` callbacks.
