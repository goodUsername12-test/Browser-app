# Fable Browser v41 (1.41.0, versionCode 37)

## Summary of Fixes in v41
1. **Fixed Lag Spikes in v40 (`SettingsStore.kt`, `FableBatteryGovernor.kt`, `HybridEngineBridge.kt`, `MainActivity.kt`, `GeckoViewHost.kt`)**:
   - Fixed the #1 lag-spike cause: `batteryIdleGameFps` previously defaulted to `30` (after 8s) and missed `touchmove` joystick events, slamming Bloxd into 30 FPS `setTimeout` jitter after 8 seconds of holding the joystick. Default is now `0` (Off), `touchmove`/`touchend`/`mousemove`/`wheel` all reset idle time, and `setTimeout` was replaced with VSync-aligned `origRaf` skipping when an explicit FPS cap is set.
   - Replaced synchronous `registerReceiver` IPC in `FableBatteryGovernor.evaluatePlan()` with an async background `HandlerThread` receiver (0 ns UI-thread IPC).
   - Restored `.distinctUntilChanged()` on `MainActivity` refresh observer and full C++ game boost across all non-benchmark Hybrid tabs.
2. **Fixed PC / Desktop Mode in Blink x Gecko Mode (`GeckoViewHost.kt`, `TabManager.kt`, `HybridEngineBridge.kt`)**:
   - Sends Desktop Client Hints headers (`Sec-CH-UA-Mobile: ?0`, `Sec-CH-UA-Platform: "Windows"`), sets Windows Chrome Desktop UA on both initial creation and toggle, overrides `navigator.userAgentData` (`mobile: false`) + `1920x1080` screen metrics, and rewrites `<meta name="viewport">` to `width=1280` (with `<head>` MutationObserver) so modern websites render true PC Desktop layouts instead of 360px phone viewports.
3. **Fixed Page Zoom in Blink x Gecko Mode (`TabManager.kt`, `GeckoViewHost.kt`, `HybridEngineBridge.kt`)**:
   - Connected `settings.textZoomPercent` changes in `TabManager` directly to `HybridBlinkController.setTextZoom(percent)` (previously uncalled), implemented live Chromium full-page CSS `zoom` (`window.__fableSetPageZoom`) scaling text, layout, images, and UI from 50% to 200%, and enabled two-finger pinch-to-zoom (`setSupportZoom(true)`).
4. **Fixed Bloxd Always Creating a New Account in Blink x Gecko Mode (`GeckoViewHost.kt`, `TabManager.kt`, `HybridEngineBridge.kt`, `UrlUtils.kt`)**:
   - Enabled `CookieManager.setAcceptThirdPartyCookies(wv, !tab.isIncognito)` (`true` on normal tabs) so Bloxd cross-origin `SameSite=None` auth cookies, Firebase/Auth0 session tokens, and Cloudflare `cf_clearance` cookies are saved permanently instead of rejected.
   - Removed HTTP redirect hijacking (`needDntHeader`) in `shouldOverrideUrlLoading` so 301/302 auth & OAuth redirects preserve `Set-Cookie` and `Referer`.
   - Added native OAuth Sign-In Popup support (`onCreateWindow` / `WebViewTransport` keeping `window.opener` connected) so Google, Discord, Microsoft, and Apple sign-in inside Bloxd works natively.
   - Added a **Persistent Game Account Storage Vault (`fable_game_account_vault`)** that mirrors and synchronously restores `bloxd.io` / `staging.bloxd.io` `localStorage` account tokens before the game bundle runs on normal tabs, plus exempted auth domains (`googleapis.com`, `accounts.google.com`, `firebaseapp.com`, `discord.com`, `challenges.cloudflare.com`, `auth0.com`, `microsoftonline.com`) from adblock interception.
