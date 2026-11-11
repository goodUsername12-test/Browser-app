# Fable Browser v18 Checkpoint & Continuation Guide

## Checkpoint Summary
- **Version / Checkpoint**: `v18` (10-minute checkpoint)
- **Application version**: `1.18.0` (`versionCode = 14`), Fable Shield extension `1.9.0`
- **Engine**: GeckoView `144.0.20251027123126`
- **Previous version verification**: `v17` passed CI Debug build, Unit tests, Release build, and Emulator verification (`WebGL Aquarium-code.zip` saved with 133 files, 14,096 KB).

## New Features & Fixes in v18

1. **Passive Read-Only Gecko Network Stream Collector (`blocker.js`)**:
   - Uses Gecko's native `browser.webRequest.filterResponseData(details.requestId)` + `onHeadersReceived` in the background script to passively copy loaded network response bytes (scripts, WASM, JSON, XHR/fetch responses, 3D models, textures, shaders, audio, workers) as the page loads, forwarding the exact bytes untouched via `filter.write(event.data)`.
   - Zero modification to the website's code (`archiveReadOnly = true` by default).
   - Automatically exports `devtools/network.har` (HTTP Archive log) and flushes any passively captured network responses (`network-stream/...`) into the ZIP even if they were one-time XHR/fetch streams no longer present in the DOM.

2. **Standalone "Download memory snapshot (.zip)" Menu Item + Full Memory & Storage Dump (`MenuSheet.kt`, `page_tools.js`)**:
   - Added **"Download memory snapshot (.zip)"** right below **"Download page code (.zip)"** in the browser menu.
   - Exports:
     - `page/rendered-dom.html`
     - `memory/canvas-*.png` (2D & WebGL framebuffers after `requestAnimationFrame`)
     - `memory/local-storage.json` & `memory/session-storage.json` (full key/value contents)
     - `memory/indexeddb/*.json` (reads IndexedDB databases & object store records)
     - `memory/cache-storage/*` (reads Service Worker Cache Storage responses)
     - `memory/service-workers.json`
     - `memory/computed-stylesheets.css` (live CSSOM rules)
     - `memory/runtime-state.json`
     - `devtools/performance.json`, `devtools/network.har`, `devtools/errors.json`
     - `devtools/console.jsonl` & `memory/blobs/*` (when Deep Page Hooks are enabled)

3. **Full Archive & Snapshot Settings UI (`SettingsStore.kt`, `SettingsViewModel.kt`, `DownloadSettingsScreen.kt`)**:
   - Under **Settings -> Downloads & Page Archive**, the user can now toggle and adjust:
     - Strict read-only mode (Zero page edits) (default: ON)
     - Passive network stream capture (default: ON)
     - Include memory snapshot in Page Code (.zip) (default: ON)
     - Capture canvases & WebGL frames (default: ON)
     - Capture IndexedDB, Cache Storage & Web Storage (default: ON)
     - Deep-scan scripts & CSS for game assets (default: ON)
     - Deep page hooks (Console, WebSockets, revoked Blobs) (default: OFF; requires turning off Strict read-only mode)
     - Sliders for Max files per ZIP (`500..10000`), Max total ZIP size (`100..2000 MB`), Max single file size (`20..500 MB`), and Live network buffer per tab (`32..512 MB`).

4. **Fixed `uncaught exception: undefined` in `game_input.js`**:
   - Fixed cross-compartment getter/method invocation on `MouseEvent.prototype.movementX/Y` and `PointerEvent.prototype.getCoalescedEvents` by using the Xray prototype descriptors first and guarding with `try/catch`.
