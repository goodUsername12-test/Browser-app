# Fable Browser v17 Checkpoint & Continuation Guide

## Checkpoint Summary
- **Version / Checkpoint**: `v17` (10-minute checkpoint #2)
- **Application version**: `1.17.0` (`versionCode = 13`), Fable Shield extension `1.8.0`
- **Engine**: GeckoView `144.0.20251027123126`

## Changes in v17 (building on v16)
1. **Fixed missing `GeckoSession` import in `EngineManager.kt`**:
   - Added `import org.mozilla.geckoview.GeckoSession` in `EngineManager.kt` for `fun setTabActive(session: GeckoSession, active: Boolean)`.
2. **"Download everything loaded in memory" (`page_tools.js`)**:
   - Hooked `URL.createObjectURL` at `document_start` on `window.wrappedJSObject.URL` so `Blob` objects created by games/apps (models, textures, audio, worker scripts) are retained in memory (bounded to 80 blobs / 96 MB) even if the page immediately calls `URL.revokeObjectURL()`. All retained blobs are written to `memory/blobs/` in the downloaded ZIP.
   - Added a `requestAnimationFrame` synchronization step before `canvas.toDataURL("image/png")` so WebGL canvases with `preserveDrawingBuffer: false` are captured right after the frame's render callback finishes drawing.
   - Added `memory/computed-stylesheets.css` exporting live CSSOM rules (`sheet.cssRules`) so dynamically inserted `insertRule` styles (CSS-in-JS) are saved even when `<style>` tags have empty textContent.
3. **Cover Your Tracks Fingerprint Protection (`fingerprint_shield.js`)**:
   - Analyzed EFF Cover Your Tracks `fetch_whorls.js`: it only awards the "randomized / strong defense" status (`fp_status_str['almost_yes']`) when `randomized_results >= 4` across 5 first-party-isolation checks (`audio`, `canvas_hash_v2`, `webgl_hash_v2`, `plugins`, `hardware_concurrency`).
   - Previously, `hardwareConcurrency` was static across domains, capping `randomized_results` at 3. `hardwareConcurrency` now varies deterministically per first-party host (`SEED`), and `noisyCopy` unwaives the source canvas (`ctx.drawImage(unwaive(canvas), 0, 0)`) so both 2D and WebGL canvas `toDataURL()` calls are reliably perturbed per domain.

## Next Steps (v18)
1. Monitor GitHub Actions build & emulator test for `v17` (`HOOK-RESULT v17` and `CI-RESULT v17`).
2. Inspect the emulator test results for:
   - `download page code:` ZIP file count, size, and `memory/` entries on the WebGL aquarium page.
   - Cover Your Tracks (`15-eff-super`) screenshot and result.
   - Dust sign-in console logs (`dust-normal`, `dust-incognito`, `dust-super`).
