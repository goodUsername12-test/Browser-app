# WIP-v25 Checkpoint Continuation Guide

## Completed in v25 (90/120 FPS Unthrottled Fullscreen, On-Demand ZIP/Memory Cache Hydration, Instant Font Rendering & Anti-CAPTCHA Hardening)
1. **Unthrottled 90Hz/120Hz Fullscreen Game Performance (`MainActivity.kt`, `EngineManager.kt`)**:
   - Removed `window.setSustainedPerformanceMode(true)` in `MainActivity.kt` which caused Android's Power HAL to thermally downclock CPU and GPU frequencies by 30-50% as soon as fullscreen was entered.
   - Activated `NativeFastEngine.setGameBoost(true)` on fullscreen entry and set `gfx.display.max-frame-rate = 120` when high refresh rate is enabled so 90Hz and 120Hz phones run at full display refresh rate.
2. **Zero-Overhead Live Gameplay + On-Demand ZIP/Memory Cache Hydration (`blocker.js`)**:
   - Disabled `filterResponseData` on subresources during live gameplay/browsing so 100% of game/page assets stream directly in C++ Necko with zero extension IPC overhead and zero retained buffer bloat during gameplay.
   - Added an 8-way parallel `cache: "force-cache"` hydration pool in `fable-net-flush` that populates `network-stream/*` and `devtools/network.har` bodies on demand when the user selects "Download page code (.zip)" or "Download memory snapshot (.zip)", then immediately frees the buffers after export.
3. **Instant Font & Text Rendering (`EngineManager.kt`, `cosmetic.js`)**:
   - Set `gfx.downloadable_fonts.fallback_delay = 0` and `gfx.downloadable_fonts.fallback_delay_short = 0` so Gecko renders readable fallback text immediately (0 ms FOIT wait) and swaps in web fonts asynchronously.
   - Added speculative preconnect warmup to `fonts.gstatic.com` and `fonts.googleapis.com` and automatic `font-display: swap` injection for `@font-face` rules.
4. **Local IP Hiding Preserved + Much Lower CAPTCHA/Block Rate in Incognito & Super Incognito (`FingerprintShield.kt`, `SuperIdentity.kt`, `fingerprint_shield.js`)**:
   - Kept WebRTC local-IP hiding (`media.peerconnection.ice.no_host`, `default_address_only`, `obfuscate_host_addresses`) enabled across all modes.
   - Updated `FingerprintShield.kt` to use genuine Gecko/Firefox 144.0 User-Agents matching GeckoView's TLS/HTTP2 fingerprint.
   - Replaced the `"Mozilla" / "Mozilla"` WebGL renderer in Incognito with realistic mobile GPU pairs, set `navigator.webdriver = false` in all private modes, disabled `rectNoise` by default, and bypassed noisy canvas/audio/font/date hooks inside verification challenge hosts (`challenges.cloudflare.com`, `recaptcha.net`, `hcaptcha.com`, `arkoselabs.com`).
