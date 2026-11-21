# Fable Browser v53-perf-foundations (WIP Checkpoint 1)

- Timestamp: 2026-11-21T05:44Z (10-minute checkpoint #1 from base a37fd81 / v52-bloxd-fix8)
- `:local-bloxd` untouched and preserved.

## Completed in Checkpoint 1
1. Added `PerformanceTelemetry.kt` — low-overhead startup, navigation, p50/p95/p99 frame time, jank %, display Hz, heap MB, and cookie flush tracker with `FABLE-PERF` structured logcat output.
2. Added `FablePrivacyPolicy.kt` — unified privacy policy evaluator and Gecko/Blink per-feature capability matrix foundation.
3. Startup & Launch Speed (`FableApplication.kt`, `MainActivity.kt`, `FableUnifiedEngine.kt`, `EngineManager.kt`):
   - Offloaded `FableProxyProfileManager.ensureLoaded()` from `MainActivity.onCreate()` main thread to `Dispatchers.IO`.
   - Wired `PerformanceTelemetry.markAppCreated()`, first-frame `Choreographer` `markFirstFrame()` + `reportFullyDrawn()`, and non-weak-device post-frame Blink warmup.
   - Enabled GeckoView content-process prelaunch (`dom.ipc.processPrelaunch.enabled`) on non-weak devices, `nglayout.initialpaint.delay=0`, `gfx.font_loader.delay=0`, zero-CPU JS bytecode cache (`browser.cache.jsbc_compression_level=0`), 4ms incremental GC slice, 512MB disk cache / 100MB entry cap, 4096 DNS entries, and WebRender shader precache.
4. Cookie Persistence (`FableApplication.kt`, `MainActivity.kt`, `FableUnifiedEngine.kt`):
   - Added debounced IO-thread `scheduleCookieFlush()` on `onPause()`, `onStop()`, and `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` so cookies survive background kills without main-thread jank.

## Next Checkpoints
- Checkpoint 2 (`v54-privacy-parity`): All privacy settings individually optional ON/OFF in SettingsStore & PrivacySettingsScreen + Low-Detectability Stealth mode + full Blink x Gecko privacy parity & native bridge isolation + proxy hardening.
- Checkpoint 3 (`v55-capture-snapshot`): Journaled incremental disk staging in `PageArchiver.kt`, full interactive Start/Pause/Resume/Stop&Save/Cancel controls + floating HUD, zero-mutation network-layer resource capture in Gecko (`filterResponseData`) and non-invasive observation + storage/cache/DOM/HAR export in Blink.
- Checkpoint 4 (`v56-tests-ci`): Unit tests + updated `ci/hook.sh` testing `com.fable.browser.debug` on API 34 x86_64 emulator across launch speed, loading speed, FPS/jank, privacy ON/OFF (Gecko + Blink), zero-mutation page ZIP capture, controllable snapshot pause/resume/stop, and cookie persistence.
