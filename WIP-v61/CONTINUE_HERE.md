# Fable Browser v61-ce-bughunt-1 Checkpoint

## Completed in v61-ce-bughunt-1
1. Fixed Subview Buffer Cutoff Bug (`WebsiteMemoryEngine.kt`, `memory_scanner.js`):
   - Previously, encountering a small `TypedArray` subview (such as a 16-byte `Float32Array(pool, off, 4)` matrix view) scanned only that slice and marked the entire underlying `ArrayBuffer` as scanned, skipping the rest of the multi-megabyte game buffer.
   - Now `registerAndScanFullBuffer` always scans the full underlying `ArrayBuffer` (`0..byteLength`) once.
2. Fixed Small Closure Buffer Cutoff (`WebsiteMemoryEngine.kt`, `memory_scanner.js`):
   - Lowered `trackRawBuf` threshold from `2048` bytes to `16` bytes and increased `MAX_TRACKED_BUFS` from `180` to `2500`, plus hooked all `TypedArray` and `DataView` constructors so small closure-hidden stats/player arrays are captured.
3. Fixed `Auto` Mode Duplicate Results (`WebsiteMemoryEngine.kt`, `memory_scanner.js`, `fable_memscan.cpp`):
   - Deduplicated `Auto` mode per 4-byte offset so each matching address emits a single canonical type (`i32`, `f32`, `f64`, or `i16`) instead of duplicating `:i32` + `:i64` at the same offset.
4. Eliminated Self-App & Read-Only Noise (`WebsiteMemoryEngine.kt`, `memory_scanner.js`, `fable_memscan.cpp`):
   - Filtered out non-writable JS properties (`!desc.writable`), 3D geometry/material/shader/DOM subtrees, and `__fable*`/`Fable*` internal keys at all depths; excluded ART/DEX/JIT metadata mappings in `fable_memscan.cpp`; expanded numeric `Array` scanning up to 4,096 slots and `Object.getOwnPropertyNames` discovery.
5. Enabled `allFrames: true` in GeckoView (`blocker.js`) & Live Bootstrap Injection on Toggle ON (`GeckoViewHost.kt`).
6. Improved Mobile CE Workflow (`MemoryEngineManager.kt`, `MemoryEnginePanel.kt`, `BrowserScreen.kt`):
   - Auto-transitions `Unknown Initial` -> `Changed` after First Scan, auto-switches to `Exact` when a number is typed for Next Scan, and adds `Hook Reload` and `Write All` buttons directly in the panel.
