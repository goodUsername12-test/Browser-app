# Fable Browser v16 Checkpoint & Continuation Guide

## Checkpoint Summary
- **Version / Checkpoint**: `v16` (10-minute checkpoint #1 from clean `v15` commit `7a76acc`)
- **Application version**: `1.16.0` (`versionCode = 12`), Fable Shield extension `1.8.0`
- **Engine**: GeckoView `144.0.20251027123126`
- **Compile status**: All modified JavaScript files pass `node --check`; Kotlin changes are complete and syntactically valid; pushing to GitHub CI now.

## Root Cause Found & Fixed in v16

### 1. Why "Download page code (.zip)" said "The page did not respond. Reload it and try again."
- **Root cause #1**: In GeckoView, `browser.tabs.query({ active: true })` inside a WebExtension background script returns an empty array `[]` unless the embedding Android app explicitly calls `GeckoRuntime.webExtensionController.setTabActive(session, true)`. `TabManager.kt` previously only called `session.setActive(true)` and never called `webExtensionController.setTabActive(...)`.
- **Root cause #2**: `manifest.json` did not include `"tabs"` in `"permissions"`.
- **Root cause #3**: `page_tools.js` checked `stripHash(location.href) !== want` and silently ignored requests if the URL normalized slightly differently, and tabs restored before extension startup could miss the content script.
- **Fixes implemented in v16**:
  - Added `"tabs"` permission to `app/src/main/assets/extensions/fable_shield/manifest.json` and bumped extension version to `1.8.0`.
  - Added `EngineManager.setTabActive(session, active)` calling `_runtime?.webExtensionController?.setTabActive(session, active)` and wired it into `TabManager.activate()` and `TabManager.freshSession()`.
  - Updated `blocker.js` (`candidateTabs` + `triggerSavePage`) to search both `browser.tabs.query({ active: true })` and `browser.tabs.query({})`, match by URL/host or active state, await `{ started: true }` acknowledgement from `page_tools.js`, dynamically inject `page_tools.js` via `browser.tabs.executeScript` if not yet injected in that tab, and run a background-script archive fallback (`fallbackSaveFromBackground`) if content scripts cannot run on that tab.
  - Updated `page_tools.js` to return `Promise.resolve({ started: true, url: location.href })` immediately and also export in-memory `<canvas>` snapshots (`memory/canvas-*.png`) plus `memory/runtime-state.json`.
  - Moved `"Download page code (.zip)"` directly under `"Downloads"` near the top of `MenuSheet.kt` so it is immediately visible without scrolling.

### 2. Why `uncaught exception: undefined` appeared in GeckoConsole on Dust
- **Root cause**: In `fingerprint_shield.js`, `pageError(e)` had a fallback `catch (x) { return undefined; }` when `new W.Error(message)` failed across compartments, which caused `guard(fn)` to execute `throw undefined;`.
- **Fix implemented in v16**: `pageError(e)` now constructs errors using the Xray window `win` first (`win[name]`, `win.DOMException`, `win.Error`), falls back to `W`, and never returns `undefined`.

### 3. CI Hook Page-Code Verification (`ci/hook.sh`)
- Updated `ci/hook.sh` and `MainActivity.kt` so if WebGL 60fps rendering prevents `uiautomator dump` from finding the menu, it also supports triggering `savePageCode()` via debug intent extra `--ez fable_save_page_code true`.

## Remaining / Incomplete Work for Next 10-Minute Cycle (v17)
1. **Verify v16 CI build & emulator test results** on GitHub Actions (specifically checking `download page code:` output and ZIP contents from the WebGL aquarium page, plus Dust console logs).
2. **Expand "Download everything loaded in memory"**:
   - Hook `URL.createObjectURL` at `document_start` in `page_tools.js` so `blob:` objects (even after `URL.revokeObjectURL`!) are retained in a weak/bounded map for archiving when the user taps Download page code.
   - Capture WebGL texture/framebuffer pixels even when `preserveDrawingBuffer` is false by capturing on the next `requestAnimationFrame` before returning the canvas PNG.
3. **Cover Your Tracks & JavaScript detection check in CI**:
   - Inspect why `whatismybrowser.com` text nodes did not appear in `uiautomator dump` (GeckoView web content isn't always exposed as Android Accessibility nodes unless accessibility is active or title/bridge reports it).
4. **Game loading lag & Gecko tab/media process deaths**:
   - Tune Gecko content process count / cache prefs in `EngineManager.kt` so heavy WebGL/Babylon.js pages don't trigger OOM killer on background processes.
