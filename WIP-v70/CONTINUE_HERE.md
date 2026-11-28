# Fable Browser v70 HYPERION (`v70-ce-hyperion-2`, v1.70.1 / versionCode 63)

## Fixes verified in `v70-ce-hyperion-2` (from `v70-ce-hyperion-1` emulator report)
1. **Real Production Engine-Switch Recomposition Bug (`GeckoViewHost.kt`)**: Fixed short-circuiting `if (sessionState !== session && hybridBlinkState != ...)` that skipped Compose subscription to `hybridBlinkState`, `mirrorInputState`, and `currentUrlState` on the same tab. Toggling GeckoView <-> Blink on an existing tab now immediately recomposes and attaches `hybridController`.
2. **Scanner Self-Capture Bug (`WebsiteMemoryEngine.kt` + synced `memory_scanner.js`)**: Guarded `JSON.parse` hook with `internalOpDepth === 0` and switched internal command parsing to `origJsonParse`, eliminating `js:closure#0.val1` self-hits.
3. **Scaled/Truncated Fallback Trigger (`WebsiteMemoryEngine.kt`)**: Triggers `scanBufferFallback` (`~x1000`, `~x100`, `~x10`, `~f64rnd`, `~rnd`) whenever zero binary (`wasm`/`ab`/`ta`) hits exist, fixing `x1000Ok` and `f64RndOk` even when JS/DOM also holds the value.
4. **Real-Browser `window` Key Cap in `handleScanPhysics`**: Iterates all `keys.length` at `depth === 0` (real GeckoView & Blink have ~950 built-in `window` keys before game objects!), fixing `physScanOk`, `physProbeRealOk`, and real CrazyGames physics discovery.
5. **Upgraded CrazyGames 96-Game Matrix**: Replaced redirected tag URLs with direct playable CrazyGames game titles, enhanced `detectEngines()` and `extractVisibleNumericHints()`, and added `iframeHits` (`ifH`) cross-origin penetration metrics.
