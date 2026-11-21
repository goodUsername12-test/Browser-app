# Fable Browser v54-privacy-parity (WIP Checkpoint 2)

- Timestamp: 2026-11-21T06:09Z (10-minute checkpoint #2)
- `:local-bloxd` untouched and preserved.

## Completed in Checkpoint 2
1. Every privacy feature individually optional ON/OFF (`etpEnabled`, `cookiePurging`, `stealthLowDetectability`, `blinkPrivacyParity`, `isolateNativeBridge`, `proxyStripLeakHeaders`, plus existing adblock/cosmetic/query-strip/3p-cookies/https-only/GPC/WebRTC/fingerprint/DoH toggles).
2. Added `PrivacyPreset.STEALTH` and live Gecko × Blink Privacy Capability Matrix in `PrivacySettingsScreen.kt`.
3. Wired full Blink × Gecko privacy parity + per-tab `bridgeToken` isolation on `FableHybridJsBridge` + `PROXY_LEAK_HEADERS` stripping in `blocker.js`.
4. Wired Gecko & Blink navigation timing into `PerformanceTelemetry`.
