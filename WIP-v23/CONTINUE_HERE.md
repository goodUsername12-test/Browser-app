# WIP-v23 Checkpoint Continuation Guide

## Completed in v23 (Bloxd.io Walking Shutter Fix, Real Hardware Data, Max Upload & Meshing Speed)
1. **Multi-Touch Walking Joystick & Camera Look Isolation (`game_input.js`)**:
   - Identified root cause of screen shutter while walking in Bloxd Peaceful Survival (`7rp1w.180.672193ff.chunk.js`): `noa-engine` reads `TouchEvent.touches` and `TouchEvent.changedTouches` instead of `targetTouches`. When walking (Touch ID 0 on `.JoystickZone`) and looking around (Touch ID 1 on `#noa-container`), `changedTouches[0]` resolved to the walking joystick finger, causing camera jumps and `console.error("Did not find touch id")` spam on every `touchmove`.
   - Filtered UI/Joystick touches (`isUiControlElement`) out of `TouchEvent.prototype.touches` and `TouchEvent.prototype.changedTouches` when dispatched to game look targets (`#noa-container` / `<canvas>`), while preserving 100% of touches on `.JoystickZone` and UI buttons.
   - Removed the false-positive `mag > 64 && mag > avg * 8 + 40` frame drop in `clampMove` and injected CSS `contain: layout style` + `will-change: transform` on `#noa-container` and `.JoystickZone` to prevent synchronous DOM reflow thrashing during simultaneous walking and camera movement.
2. **Real Hardware Data & Full WebAssembly Meshing Workers (`EngineManager.kt`)**:
   - Gated `fingerprintOverrides` on `d.normalTabShield && !d.gameMode && !d.superGameMode` so normal/game tabs expose real CPU core counts (`navigator.hardwareConcurrency`), real GPU renderer strings (instead of `"Mali-G51, or similar"`), real screen dimensions, and native WebAudio sample rates without FPP resampling overhead on footstep sounds (`stepSoundOnWalk`).
   - Exposing real `navigator.hardwareConcurrency` allows Bloxd's `meshingWorkerCount` (`33692`) to spawn 2-3 WebAssembly chunk meshing workers instead of 1 when walking into new chunks.
3. **Maximum Upload & Network Buffer Speed (`EngineManager.kt`, `blocker.js`)**:
   - Increased `network.buffer.cache.size` to 256 KB (`262144`), `network.buffer.cache.count` to `128`, `network.http.send_window_size` to 1 MB (`1048576`), `network.websocket.max-connections` to `200`, and `dom.workers.maxPerDomain` to `64`.
   - Fast-pathed WebSocket connections and same-site POST/PUT/PATCH upload requests in `blocker.js`.
