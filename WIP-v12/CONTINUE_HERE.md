# Fable Browser v12 work-in-progress snapshot

This folder preserves the complete repository state that was prepared for v12, including work that is not finished or not yet verified. The buildable project is also present at the repository root. The `repository/` subfolder is a complete file snapshot for reference, and `current-working-tree.patch` records the changes from the v11 base commit.

## Current state

- Base repository commit before this work: `a5ee9d6` (`base`)
- Target update version: `v12`
- Application version prepared in Gradle: `1.12.0`, version code `8`
- Work is intentionally preserved as a work in progress. It has not been declared release-ready.
- The archive includes `desktop/`, `.fable-version`, CI scripts, source files, assets, tests, and the `.github` directory. The GitHub apply workflow preserves the repository's existing workflow files when applying an update.

## Implemented or substantially implemented

- Password manager storage, login and signup detection, save/select prompts, password management UI, biometric unlock path, never-saved sites, and sensitive clipboard handling.
- Encrypted backup and restore for application data and selected Gecko profile/site data, using an encrypted archive and staged restore.
- Incognito and super-incognito privacy controls, including referrer, client hints, DNT, rotating user agent, timezone, hints, and referrer hiding controls.
- Cosmetic and network ad blocking with EasyList, EasyPrivacy, AdGuard mobile/tracking filters, Peter Lowe's list, allow-listed sites, cosmetic hiding, and update scheduling.
- Weak-device optimizer settings and engine tuning for memory-constrained devices.
- Download handling changes and tab-close undo behavior with an undo bar.
- Adaptive tab grid work and settings navigation for password and backup screens.
- CI filter-list asset generation and v12 application version metadata.

## Not finished or not verified

- Tablet-specific layouts beyond the adaptive tab grid still need review and implementation.
- The Android project has not yet completed a clean compile and test pass after these changes.
- GeckoView autocomplete API compatibility may need fixes, especially `LoginEntry.Builder`, autocomplete hints, and the storage delegate wiring.
- Dependency-injection cycles or construction issues may remain around `EngineManager`, `PasswordManager`, and `BackupManager`.
- Gradle generated-assets task API compatibility needs verification.
- Android API compatibility needs verification for sensitive clipboard metadata and biometric paths.
- Material icon names and imports need a compile check.
- `dust.tt` behavior in normal, incognito, and super-incognito modes must be confirmed by the emulator hook.
- Download persistence, password prompts, backup restore, ad-block cosmetic rules, optimizer defaults, and tab undo must be exercised on-device.
- Release APK generation and the single arm64-v8a APK artifact still need confirmation.

## Where to continue

1. Run `./gradlew testDebugUnitTest assembleDebug` and fix the first compiler or test failure.
2. Run the release build and inspect the arm64-v8a output.
3. Run `ci/hook.sh` through GitHub Actions, then inspect `HOOK-RESULT`, `HOOK-SHOT`, `CI-RESULT`, and the `dust.tt` checks on issue #2.
4. Fix failures one at a time, preserving unrelated behavior.
5. Review tablet layouts and finish any missing adaptive UI work.
6. Repeat the build and emulator checks until the APK and hook are successful.

## Important files changed

See `changed-files.txt` for the complete list. The main areas are:

- `app/src/main/java/com/fable/browser/data/backup/BackupManager.kt`
- `app/src/main/java/com/fable/browser/data/repo/PasswordStore.kt`
- `app/src/main/java/com/fable/browser/engine/PasswordManager.kt`
- `app/src/main/java/com/fable/browser/ui/settings/PasswordsScreen.kt`
- `app/src/main/java/com/fable/browser/ui/settings/BackupScreen.kt`
- `app/src/main/java/com/fable/browser/engine/ContentBlocker.kt`
- `app/src/main/assets/extensions/fable_shield/blocker.js`
- `app/src/main/assets/extensions/fable_shield/cosmetic.js`
- `app/src/main/java/com/fable/browser/engine/MemoryPolicy.kt`
- `app/src/main/java/com/fable/browser/engine/TabManager.kt`
- `app/src/main/java/com/fable/browser/engine/Downloader.kt`
- `app/src/main/java/com/fable/browser/engine/DownloadService.kt`

## Preservation note

This snapshot is intentionally not cleaned up. Do not delete unfinished files merely because they are not yet compiled. Use the root project for the next build and use this folder as the preserved checkpoint and recovery reference.
