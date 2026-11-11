# Fable Browser v14 Status & Continuation Guide

## Version summary

- Update version: `v14`
- Application version: `1.14.0` (`versionCode = 10`)
- Engine: GeckoView `144.0.20251027123126`
- Builds on v13 (Gradle fix, Xray-safe fingerprint shield, Gecko-only super identities, tablet layouts, password manager, encrypted backups, ad blocking, weak-device optimizer)

## Done in v14

1. Downloads
   - Files are saved straight into the public `Download` folder (MediaStore on Android 10+, public Downloads dir below)
   - Toasts for start, saved location and failure, so a download is never silent even when notifications are denied
   - Notification permission is requested on the first download (Android 13+)
   - Response without a body falls back to a direct fetch of the URL
   - New "Download link" action in the long-press menu
   - FileProvider paths fixed for the private fallback folder (`files/Download`), old records still open
   - Opening a download whose file was deleted shows "That file cannot be opened" instead of doing nothing
2. Strong password suggestion
   - Closing the dialog, or tapping "Type my own", hides the generated password for that site for the rest of the app session, so the field can be typed in
   - Dismissing the saved-login picker snoozes it for that site for 3 minutes in that tab
   - "Never suggest strong passwords" button in the dialog, and a "Suggest strong passwords" switch in Settings > Passwords
3. JavaScript in incognito and super incognito
   - Preference key reset to `incognito_javascript_v2` (default on), so everyone starts with JavaScript on after updating
   - The switch is now "Allow JavaScript" with a clear On/Off description and applies immediately to every open incognito tab (tabs reload)
   - Session builders use the setting value directly and `javascript.enabled` is forced on in the engine
4. Open local HTML files
   - Fable appears in the "Open with" list for `.html`, `.htm`, `.xhtml`, `.mht` files from file managers
   - The file is copied to `cache/opened_html` (last 20 kept) and opened in a normal tab
5. CI emulator hook
   - New checks: JavaScript detection page in incognito and super incognito, Cover Your Tracks in super incognito, and a real download that must appear in `/sdcard/Download`

## Remaining requests (next versions)

1. "Download full page code" button: zip of every loaded resource (HTML, JS, WASM, CSS, images, workers)
2. "Download everything loaded in memory" snapshot, readable and exact
3. Game camera jumping in bloxd.io (pointer lock / game_input.js)
4. Lag while assets load in games (Babylon.js), and with screen recording active
5. Fingerprinting protection result on Cover Your Tracks in super incognito
