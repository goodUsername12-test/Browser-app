# WIP-v43 Checkpoint Continuation Guide (10-Minute Save Rule)

## Completed in Cycle 1 (`v43-wip`)
1. **`DefaultBrowserEngine` Model & Two-Way Settings Sync (`DefaultBrowserEngine.kt`, `SettingsStore.kt`, `SettingsViewModel.kt`)**:
   - Added `DefaultBrowserEngine` (`BLINK_GECKO_AUTO`, `BLINK_GECKO_ALL`, `GECKO_ONLY`) + `archiveSplitPartMb` (`0 = Single full .zip`, `45 = Split <= 45 MB parts`, `95 = Split <= 95 MB parts`).
   - Wired `Settings.defaultEngine`, `setDefaultEngine(...)`, and `setArchiveSplitPartMb(...)` in `SettingsStore` and `SettingsViewModel`.
2. **Blink-Aware Native Downloader (`Downloader.kt`)**:
   - Upgraded `enqueueUrl(...)` with `mimeTypeHint`, `userAgentHint`, and `preferBlinkNetwork` so Blink × Gecko HTTP/HTTPS downloads carry `CookieManager` cookies + `User-Agent` across both 6x Parallel Segmented `Range` downloads and direct HTTP streams.
   - Upgraded `decodeDataUrl` to use fast C++20 `NativeFastEngine.decodeBase64` (`comma >= 5`) and `saveLocalFile` to preserve `.zip` extensions.

## Remaining for Cycle 2 (`v43` Final — Start Immediately Next)
1. **`PageArchiver.kt`**: Add 12-stream native Kotlin parallel `fetch_batch` downloader (zero CORS blocks, zero JS Base64 overhead, C++20 CRC32 `STORED` entries), `capture-manifest.json` completeness report, and optional `archiveSplitPartMb` multipart `.partXofN.zip` splitting.
2. **`HybridEngineBridge.kt`**:
   - Fix blank Bloxd `FPS` counter (`FpsCanvas` 48x16) by removing `{ desynchronized: true }` on 2D canvases (preserving `willReadFrequently`) and fixing per-element `HTMLCanvasElement` rect caching.
   - Fix all `blob:` & `data:` & detached `a.click()` downloads with short-lived `URL.createObjectURL` retention + chunked binary bridge (`beginJsDownload` / `appendJsDownloadChunk` / `finishJsDownload`).
   - Add `<video>` & `WebCodecs` `VideoDecoder` hardware-accelerated high-FPS video frame fast path.
   - Upgrade `buildBlinkArchiveScript` with full `IndexedDB`, `CacheStorage`, `js-heap-and-webgl.json`, `<iframe>` DOMs, `requestAnimationFrame` WebGL canvas capture, HAR 1.2 (`devtools/network.har`), deep CSS/JS asset scanner, and `fetch_batch`.
3. **`GeckoViewHost.kt`, `TabManager.kt`, `BrowserTab.kt`, `BlinkGamingActivity.kt`, `BrowserScreen.kt`**:
   - Wire `data:` + `blob:` + chunked JS download handlers in `TabManager`, `GeckoViewHost`, and `BlinkGamingActivity`.
   - Add 1-tap translucent **Exit Fullscreen** floating pill in both `BlinkGamingActivity` and `BrowserScreen` fullscreen + `document.exitFullscreen()` support.
4. **UI Screens (`GeneralSettingsScreen.kt`, `HybridBetaSettingsScreen.kt`, `DownloadSettingsScreen.kt`, `EngineManager.kt`) + Unit Tests (`UnifiedEngineTest.kt`)**:
   - Wire Default Engine radio selector + ZIP part split selector + hardware media decoding prefs, bump to `v43` (`1.43.0`, `versionCode = 39`), push, and verify full CI!
