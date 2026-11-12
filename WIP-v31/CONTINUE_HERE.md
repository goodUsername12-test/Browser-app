# WIP-v31 Checkpoint Continuation Guide

## Completed in v31 (Fix Camera Shutter in Blink x Gecko, Fix Undo Close Tab Disappearing Bug, Eliminate Mid-Frame Lag Spikes)
1. **Fixed Undo Close Tab Instantly Disappearing Bug (`TabsScreen.kt`, `TabManager.kt`)**:
   - Replaced the `LaunchedEffect(dismissState.currentValue)` close trigger in `TabCard` with `confirmValueChange` returning `false` after invoking `onClose()` plus a `LaunchedEffect(tab.id)` reset to `SwipeToDismissBoxValue.Settled`. Previously, `rememberSwipeToDismissBoxState()` saved `EndToStart`/`StartToEnd` in `SaveableStateHolder` keyed by `tab.id`, so pressing Undo restored the tab with a dismissed state and immediately re-closed it in an infinite loop.
2. **Fixed Game Camera Shutter in Combined Blink x Gecko & GeckoView (`HybridEngineBridge.kt`, `game_input.js`, `fable_fast.cpp`)**:
   - Removed the `< 7.2 ms` / `< 6 ms` time-window `touchmove` drop that starved Bloxd's `NF(FF, pF)` camera delta accumulator (`FF.gfF.dx += DF.clientX - AF`) on alternating frames (which caused `+0 px` freeze frames followed by `+2x px` jump frames).
   - Added persistent per-finger touch ID locking (`uiTouchIds` vs `camTouchIds` from `touchstart` to `touchend`) and stationary-touch fallback in `TouchEvent.prototype.changedTouches` so joystick movements (`Touch 0`) never leak into `changedTouches[0]` even when `kept.length === 0` or when the thumb drags outside `.JoystickZone`.
   - Blocked synthetic touchscreen `mousemove` events on `#noa-container` when not in pointer-lock so Bloxd's `mousemove` listener (`NF`) never double-adds camera movement or triggers redundant hover raycasts.
3. **Eliminated Mid-Frame Physics Lag Spikes (`HybridEngineBridge.kt`)**:
   - Suppressed Bloxd's redundant post-render `setTimeout(ip, 0, FF, true)` call in Blink so physics ticks (`noa.CF`) run strictly inside `requestAnimationFrame` (`frameCB`), and ensured `navigator.hardwareConcurrency >= 8` on game domains so Bloxd spawns all 3 WebAssembly chunk-meshing workers.
