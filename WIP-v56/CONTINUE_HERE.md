# Fable Browser v56-complete-ci-4 (1.56.0, versionCode 47)

- Timestamp: 2026-11-21T09:04Z (Deep Pre-CI Bug Audit & 15 Fixes)
- `:local-bloxd` verified 100% byte-for-byte identical to `main`.

## Bugs Caught & Fixed in `v56-complete-ci-4`
1. `PageArchiver.kt` L384: Fixed literal unescaped newline in `appendJournalLocked` JSONL string.
2. `PageArchiver.kt` L930: Fixed unescaped quote and regex escape inside `archiveBasePrefix`.
3. `PageArchiver.kt` L944: Fixed unescaped backslash char literal in `cleanPath`.
4. `BrowserScreen.kt` L845: Fixed literal unescaped newline in `PerfOverlayHud` text string.
5. `PerformanceTelemetry.kt`: Added safe JVM fallbacks for `SystemClock.elapsedRealtime()` and `Log.i(...)` + expanded unit tests.
6. `GeckoViewHost.kt` L738: Fixed inverted `stripOn` boolean (`!decision.blinkPrivacyParity` was stripping tracking params when parity was OFF).
7. `TabManager.kt` L504: Wired `s.blinkPrivacyParity` and `FablePrivacyPolicy.isAdBlockAllowedSite` into `hybridBlockChecker`.
8. `TabManager.kt` L932+: Applied Blink `httpsOnly` and `stripTrackingParams` on initial `load()` (since `WebView.loadUrl` does not invoke `shouldOverrideUrlLoading`).
9. `SessionDelegates.kt` L133 & `UrlUtils.isLoopback`: Exempted loopback (`127.0.0.1`, `localhost`, `::1`) from HTTPS-Only interstitial in both GeckoView and Blink.
10. `AndroidManifest.xml` + `network_security_config.xml`: Permitted cleartext HTTP for local/user-allowed sites while keeping strict system TLS trust anchors.
11. `blocker.js`: Added missing `includeCookies` & `continuousCapture` in `archiveCfg()` and missing config defaults.
12. `blocker.js`: Captured opt-in `memory/cookies.json` during `fable-net-flush` before `stopAndSave()` closes staging.
13. `page_tools.js`: Awaited `sendBytes("memory/fonts.json", ...)` and indexed it in `index.json`.
14. `PageArchiver.kt`: Deduplicated `sourceUrl` in `capturedUrls` inside `stageCompleteBytesLocked`.
15. `ci/hook.sh`: Added strict PASS/FAIL assertions for cookie persistence across force-stop and Blink Privacy ON vs OFF verification.

## Blocker Requiring Repository Owner Action
Issue #2 reached GitHub's hard 2,500-comment limit, and `.github/workflows/apply-update.yml` on `main` still filters `github.event.issue.number == 2`.
Once `.github/workflows/apply-update.yml` and `.github/workflows/build-apk.yml` are updated to accept Issue #4, post `FABLE-APPLY-APP v56-complete-ci-4 <sha256>` on Issue #4 to apply, build APKs, run unit tests, and run the full API 34 x86_64 emulator verification suite.
