# Fable Browser v15 Status & Continuation Guide

## Version summary

- Update version: `v15`
- Application version: `1.15.0` (`versionCode = 11`), Fable Shield extension `1.7.0`
- Engine: GeckoView `144.0.20251027123126`
- Builds on v14 (verified: CI build, unit tests, release build and emulator run all passed, download landed in `/sdcard/Download`)

## Done in v15

1. Download page code (.zip)
   - Menu item "Download page code (.zip)"
   - `page_tools.js` collects the current DOM, the original HTML, everything in the resource timing list (scripts, WASM, CSS, images, fonts, worker scripts, fetch/XHR files), DOM references, CSS `url()`/`@import`, and asset paths found inside scripts (Babylon/Unity/Godot style files)
   - Files are streamed in base64 chunks through the background script to `PageArchiver`, which writes a real zip and saves it to `Download`
   - `index.json` lists every URL, its path in the zip, type, size and errors
   - Limits: 2500 files, 400 MB total, 80 MB per file
2. Fingerprint shield compatibility (incognito and super incognito)
   - Every patched function and proxy trap now throws errors created in the page and returns page-owned objects, fixing the "Permission denied to access property", "uncaught exception: undefined" and moz-extension TypeErrors seen on dust.tt
3. Game camera jumps (bloxd.io and similar)
   - Mouse events created from touches now take the movement of the finger they belong to, so switching between the joystick finger and the camera finger no longer jumps the camera
   - Lower per-event jump limit and a spike filter per finger
4. Extension version bumped so GeckoView reloads the built-in extension scripts
5. CI hook: page code zip test on the WebGL aquarium page, JavaScript check prints the page text

## Remaining requests (next versions)

1. "Download everything loaded in memory" snapshot (beyond network files: canvas images, blob URLs, decoded media)
2. Lag while assets load in games (Babylon.js), and with screen recording active
3. Fingerprinting result on Cover Your Tracks in super incognito
4. JavaScript detection test page result is inconclusive in CI (no matching text)
