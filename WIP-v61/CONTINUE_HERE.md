# Fable Browser v61-ce-bughunt-2 Checkpoint

## Fixed
- Fixed CrazyGames/Page & WASM scan abort: `Permission denied to access property "constructor" on cross-origin object`.
- Added guarded `safeInstanceOf` / `safeIsView` and fail-closed cross-origin skipping around every constructor, prototype, `instanceof`, `ArrayBuffer.isView`, typed-array region, Map/Set, DOM and WebAssembly object inspection.
- Cross-origin objects are silently skipped while accessible page JS, WebAssembly and buffer candidates continue scanning.
- Reproduced the exact denial with a hostile cross-origin proxy and verified scanning continues to `js:game.coins = 4976216` (`CROSS_ORIGIN_SKIP_TEST_PASS=1`).
