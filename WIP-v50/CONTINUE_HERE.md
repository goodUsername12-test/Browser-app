# Fable Browser v50 (1.50.0, versionCode 46)

## Completed in v50
1. **Fixed Freeze #1 (`BLASTBufferQueue.nativeDestroy` / `ViewRootImpl.destroySurface` 2.9s Main-Thread Stall)**:
   - Removed the redundant `frameVoter: SurfaceView` on Android 14+ (API 34-36) where `View.setRequestedFrameRate` votes directly on Views without creating a second `SurfaceControl` + `BLASTBufferQueue`.
   - Changed hybrid Gecko hibernation in `GeckoViewHost.kt` to use `View.INVISIBLE` + `alpha = 0f` after `setActive(false)` and `releaseSession()` instead of synchronous `View.GONE` during layout traversal, preventing `ViewRootImpl.relayoutWindow` from blocking in `BLASTBufferQueue.nativeDestroy()` while `GeckoThread` is active.
   - Deduplicated `applyToViewTree` and `NativeFastEngine.setGameBoost` in `GeckoViewHost.kt` and moved `/proc/self/task` scanning off the UI main thread onto a single background worker thread (`FableBoostWorker`) in `NativeFastEngine.kt` + removed `/proc/self/task` scans from `nativeRecordFrameDeltaNs` in `fable_fast.cpp`.
   - Updated `fable_fast.cpp` so the Android UI Main Thread (`getpid()`) and `RenderThread` always remain responsive on `all_mask` (`-10`), while background `GeckoMain` / `WRRender` threads are kept on `little_mask` at priority `+2` during Blink hybrid mode so `Thread Gecko (RUNNABLE)` never starves the UI thread.
2. **Fixed Crash #2 (`java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints`)**:
   - Replaced the nested `LazyColumn` inside `McpSettingsScreen.kt` (which is already wrapped by `SettingsPage`'s `Column(Modifier.verticalScroll(rememberScrollState()))`) with a regular `Column`, eliminating unbounded vertical constraint crashes when opening `Settings -> MCP server & live control`.
3. **Full Live MCP Browser Control & In-App Live HUD (`McpSessionManager.kt`, `McpSettingsScreen.kt`, `BrowserScreen.kt`)**:
   - Added `Mcp-Session-Id` header persistence for Streamable HTTP MCP servers, custom headers / Bearer token support, and a 1-tap `local://fable-mcp` local session mode.
   - Added 8 live browser control tools (`browser_navigate`, `browser_read_page`, `browser_click`, `browser_type`, `browser_scroll`, `browser_tabs`, `browser_eval_js`, `browser_game_input`) wired to the active `BrowserScreen` tab.
   - Added `McpLiveBrowserHud` on `BrowserScreen.kt` showing live AI status, current action, expandable live log feed, visual green click marker at `(x, y)`, Pause/Resume AI, and Stop MCP controls directly over the browser.
