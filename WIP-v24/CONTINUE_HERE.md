# WIP-v24 Checkpoint Continuation Guide

## Completed in v24 (Zero-Copy Full Memory Stream Retention + Zero-Reflow Canvas & Walking Raycast Optimization)
1. **Full In-Memory Subresource Retention with Zero Gameplay Copy Overhead (`blocker.js`)**:
   - Restored full in-memory subresource streaming (`shouldStreamBody = config.archiveIncludeMemory !== false && !isRealtimePingUrl(url, details)`) so `tabNetStore` retains 100% of game/page subresources in memory (`network-stream/*` and `devtools/network.har`) just like `v21`.
   - Eliminated `event.data.slice(0)` in `filter.ondata` and eliminated synchronous `new Uint8Array(size)` chunk merging in `filter.onstop`, deferring multi-chunk concatenation to `materializeEntryBytes(entry)` at archive export time (`fable-net-flush`).
2. **Zero-Reflow Canvas Size Caching & Multi-Touch Raycast Deduplication (`game_input.js`)**:
   - Cached `HTMLCanvasElement.prototype.clientWidth` and `clientHeight` on game canvases (invalidated on `resize`/`fullscreenchange`) so reading canvas dimensions inside `touchmove` handlers while `.JoystickKnob` updates `style.transform` never triggers a synchronous DOM layout flush.
   - Deduplicated zero-delta `touchmove` events on `#noa-container` during multi-touch walking so moving only the `.JoystickZone` thumb does not trigger redundant 3D voxel + entity raycasts (`createPickingRay` + `FF.hN` + `FF.akF`) on `#noa-container`.
   - Suppressed `noa-engine`'s redundant post-render `setTimeout(ip, 0, FF, true)` mid-frame timer so physics ticks stay phase-locked to `requestAnimationFrame` for smooth walking interpolation (`vkF`/`qfF`).
