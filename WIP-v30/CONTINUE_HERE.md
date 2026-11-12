# WIP-v30 Checkpoint Continuation Guide

## Completed in v30 (Hoist Game Input WeakMap/WeakSet State Declarations)
1. **Fixed `canvasRects` TDZ Initialization Order (`game_input.js`)**:
   - Hoisted `marked`, `canvasDims`, `canvasRects`, `lastCamTouch`, `lastCamDispatchAt`, and `gameRoot` to the top of `game_input.js` and removed the duplicate `let canvasRects` declaration so `game_input.js` initializes cleanly on all pages without a temporal dead zone `ReferenceError`.
