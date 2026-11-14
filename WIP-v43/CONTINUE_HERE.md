# Fable Browser v43 (1.43.0, versionCode 39)

## Summary of Fixes, Features & Optimizations in v43
1. **Fixed Blank `FPS` Counter in Bloxd (`HybridEngineBridge.kt`)**:
   - Removed `{ desynchronized: true }` override on 2D canvases (`FpsCanvas` `48x16` and `CoordsCanvas`), which caused Android WebView's Skia compositor to render 0 pixels on small HUD canvases (verified in user's archive: `canvas-0.png` was 137 bytes empty).
   - Fixed shared singleton `cachedRect`/`cachedCw`/`cachedCh` across multiple `<canvas>` elements by storing layout rect cache per-canvas (`this.__fableRect`, invalidated on resize/fullscreen/dimension change).
2. **Fixed All Downloads in Blink × Gecko Mode (`TabManager.kt`, `GeckoViewHost.kt`, `Downloader.kt`, `HybridEngineBridge.kt`, `BlinkGamingActivity.kt`)**:
   - Fixed `TabManager.hybridDownloadHandler` dropping `data:` and `blob:` downloads.
   - Added 25-second `URL.createObjectURL` `Blob` retention (`blobRegistry`), hooked detached `HTMLAnchorElement.prototype.click`, and added streaming native chunked binary download bridge (`beginJsDownload` / `appendJsDownloadChunk` / `finishJsDownload`) + Blink cookie/User-Agent forwarding for HTTP/HTTPS downloads.
3. **1-Tap Exit Fullscreen in Blink Gaming Mode & Browser Fullscreen (`BlinkGamingActivity.kt`, `BrowserScreen.kt`, `GeckoViewHost.kt`)**:
   - Added compact translucent `✕ Exit Fullscreen` pill at top-center in both `BlinkGamingActivity` and `BrowserScreen` fullscreen, plus `document.exitFullscreen()` + `onHideCustomView()` synchronization.
4. **Default Browser Engine Selector (`DefaultBrowserEngine.kt`, `GeneralSettingsScreen.kt`, `HybridBetaSettingsScreen.kt`, `TabManager.kt`)**:
   - Added selectable default engine (`Blink × Gecko Smart Auto (Beta)`, `Blink × Gecko Default (Beta)`, `GeckoView Classic`) with live tab updates and automatic GeckoView fallback for SOCKS5 Super/Incognito proxy tabs.
5. **10x Faster Forensic Page Code & Memory `.zip` Archiver + Multi-Part Splitting (`PageArchiver.kt`, `HybridEngineBridge.kt`, `DownloadSettingsScreen.kt`)**:
   - Added 12-stream native Kotlin parallel `fetch_batch` asset downloader (zero CORS blocks, zero JS Base64 overhead, C++20 CRC32 `ZipEntry.STORED` for binary media/textures/fonts/wasm).
   - Added full `IndexedDB` dump (`memory/indexeddb/*.json`), `CacheStorage`, `js-heap-and-webgl.json`, `<iframe>` DOMs, RAF-synced WebGL canvas capture, HAR 1.2 (`devtools/network.har`), `capture-manifest.json`, and optional `<= 45 MB` / `<= 95 MB` `.partXofN.zip` splitting.
6. **High-FPS Browser Video & Frame Playback (`HybridEngineBridge.kt`, `EngineManager.kt`)**:
   - Promoted WebCodecs `VideoDecoder.configure` to `hardwareAcceleration: "prefer-hardware"`, preserved `willReadFrequently` on 2D frame-grab canvases, boosted `<video>.play()` onto big cores, and enabled hardware H.264/VPX media decoding in GeckoView.
