# WIP-v26 Checkpoint Continuation Guide

## Completed in v26 (Fix Xray Compartment Exceptions on Auth/CAPTCHA Pages)
1. **Eliminated `uncaught exception: undefined` on `setTimeout` (`game_input.js`)**:
   - Removed the cross-compartment `pageWin.setTimeout` wrapper that triggered `uncaught exception: undefined` when pages passed waived function references into `window.setTimeout`.
2. **Eliminated `OffscreenCanvas.convertToBlob` TypeError & `Symbol.iterator` Permission Denied on Incognito/Super Sign-In (`fingerprint_shield.js`)**:
   - Removed the cross-compartment `OffscreenCanvas.prototype.convertToBlob` wrapper that caused `TypeError: 'convertToBlob' called on an object that does not implement interface OffscreenCanvas` during WorkOS/Cloudflare anti-bot checks.
   - Replaced `Array.from(locales)` in `plainLocales` with an indexed loop over `waived(locales)` so `Symbol.iterator` is never accessed across Xray compartment boundaries when `Intl.DateTimeFormat` or `toLocaleString` is called.
