# Fable Browser v19 Checkpoint & Continuation Guide

## Checkpoint Summary
- **Version / Checkpoint**: `v19` (10-minute checkpoint #2 of current session)
- **Application version**: `1.19.0` (`versionCode = 15`), Fable Shield extension `1.9.0`
- **Engine**: GeckoView `144.0.20251027123126`

## Changes in v19 (building on v18)
1. **Game Asset Loading Speed & Content Process Stability (`MemoryPolicy.kt`, `EngineManager.kt`)**:
   - Disabled `network.http.rcwn.enabled` (Race Cache With Network) to eliminate disk/network socket contention when games load hundreds of assets simultaneously.
   - Increased `browser.cache.disk.max_entry_size` to `51200` KB (50 MB) so large `.wasm`, `.glb`, `.babylon`, and `.data` game files are cached on disk instead of re-downloading.
   - Increased network stream buffers (`network.buffer.cache.size = 65536`, `network.buffer.cache.count = 48`) and texture decode chunk size (`131072` bytes in game mode).
   - Capped `dom.ipc.processCount.webIsolated` (`2` on low-RAM devices, `4` otherwise) and raised `MemoryMode.LOW` `keepLoaded` to `2` with a `32 MB` memory cache and `180s` idle threshold to prevent premature `:tabXX` process kills.
2. **JavaScript & Cover Your Tracks Diagnostics (`page_tools.js`, `ci/hook.sh`)**:
   - Added debug diagnostic reporting for `whatismybrowser.com` and `coveryourtracks.eff.org` so CI can verify JavaScript enabled status and Cover Your Tracks protection results even when GeckoView web text is not in `uiautomator dump`.
   - Updated `ci/hook.sh` ZIP summary to explicitly count `memory/`, `devtools/`, and `network-stream/` entries inside the downloaded archive.
