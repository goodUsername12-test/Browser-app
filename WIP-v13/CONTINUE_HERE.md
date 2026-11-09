# Fable Browser v13 Status & Continuation Guide

## Version summary

- Update version: `v13`
- Application version: `1.13.0` (`versionCode = 9`)
- Engine: GeckoView `144.0.20251027123126`
- Target / Compile SDK: `targetSdk = 35`, `compileSdk = 36`, `minSdk = 26`
- Release ABI: `arm64-v8a`

## Completed and verified in code for v13

1. **Gradle build fix (`app/build.gradle.kts`)**:
   - Fixed the v12 script compilation failure (`Unresolved reference: net` at line 148) caused by Gradle's `Project.java` extension shadowing the root `java` package.
   - Imported `java.net.URI` and `java.net.HttpURLConnection` at the top of `app/build.gradle.kts` and bumped the app version to `1.13.0` (`versionCode = 9`).

2. **Incognito & Super-Incognito `dust.tt` and WebApp compatibility (`fingerprint_shield.js`, `SuperIdentity.kt`)**:
   - Replaced Xray-waived prototype method lookups with Xray-safe `wrapOrig` / `methodOf` wrappers in `fingerprint_shield.js` so `HTMLCanvasElement.toDataURL`, `CanvasRenderingContext2D.getImageData` / `measureText`, `WebGLRenderingContext.getParameter` / `readPixels`, `Element.getBoundingClientRect`, `AudioBuffer.getChannelData`, and `FontFaceSet.check` never throw cross-compartment `Permission denied` errors on modern apps like `dust.tt`.
   - Updated `SuperDeviceRange.ANY` in `SuperIdentity.kt` to select from genuine Firefox/Gecko device profiles (`PHONE`, `TABLET`, `WINDOWS`, `MAC`, `LINUX`) so default super-incognito sessions always match the Gecko engine, while keeping `SuperDeviceRange.IPHONES` available when explicitly chosen.

3. **Tablet Layouts (`BrowserScreen.kt`, `BottomToolbar.kt`, `NewTabPage.kt`, `SettingsScreen.kt`, `TabsScreen.kt`)**:
   - Added `TabletTabBar` (horizontal scrollable tab strip with active tab state, close with Undo,new tab, and super-incognito tab actions) and single-row `TabletToolbar` (combining navigation controls, expanding center `Omnibox`, and action buttons) when `screenWidthDp >= 600`.
   - Added responsive tablet sizing and 6-column top-sites rows in `NewTabPage.kt`, centered wide-screen layout in `SettingsPage` (`SettingsScreen.kt`), and adaptive grid columns in `TabsScreen.kt`.
   - Phone layout (`screenWidthDp < 600`) remains unchanged.

4. **All core v12/v13 features included**:
   - Encrypted password manager (`PasswordStore`, `PasswordManager`, `FablePromptDelegate`, `PromptHost`, `PasswordsScreen`) with login/signup detection, save/update/select prompts, biometric unlock, and sensitive clipboard handling.
   - Encrypted `.fable` backup export and restore (`BackupManager`, `BackupScreen`) using AES-256-GCM + PBKDF2-HMAC-SHA256 (150,000 iterations) covering settings, passwords, bookmarks, history, open tabs, site permissions, download records, and cookies/site data.
   - Strong ad & tracker blocking (`ContentBlocker`, `blocker.js`, `cosmetic.js`) with EasyList, EasyPrivacy, AdGuard Mobile, AdGuard Tracking, and Peter Lowe's list, fast chunked cosmetic selector validation, per-site allowlist, and automatic 4-day background updates.
   - Weak-device optimizer (`MemoryPolicy`, `EngineManager`, `PerformanceSettingsScreen`) enabled by default.
   - Persistent downloads (`Downloader`, `DownloadService`) and Chrome-style `Tab closed · Undo` bar (`UndoCloseBar`, `TabManager`).
