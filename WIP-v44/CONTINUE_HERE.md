# WIP-v44 Agent Foundation Checkpoint

## Current state
The agent implementation is partially integrated and has not yet been compiled or sent to CI.

## Implemented
- `FableAgentModels.kt`: Basic Browsing, Gaming Screen-Only, Long Task modes, autonomy policies, risk zones, confirmations, logs, learned skills.
- `FableScreenInputController.kt`: Android touch/joystick/camera multi-pointer input, tap/swipe, 80 ms watchdog, guaranteed release.
- `FableVisionAndOcr.kt`: PixelCopy/view capture, ML Kit OCR, visual stability/load waiting, fast frame-diff heuristic.
- `FableSearchAndReader.kt`: search adapter, HTML page text extraction, page understanding.
- `FableVideoSkillLearner.kt`: video sampling, OCR, motion/camera/button heuristics, saved skill JSON, model storage/free-space discovery.
- Rust `nativeComputeControlVector` and Kotlin fallback in `RustFastCore.kt`.
- ML Kit dependency.
- Agent and Risk Zone settings fields/setters in `SettingsStore.kt` and `SettingsViewModel.kt`.
- Input controller attached to browser container lifecycle in `GeckoViewHost.kt`.

## Not yet completed
- Compile and fix Kotlin/Rust/Gradle errors.
- Add Agent UI and route.
- Add orchestrator/state machine for all three modes.
- Connect OCR/vision/search/video skills to orchestrator.
- Add BlinkGamingActivity integration.
- Add unit tests.
- Run local validation and GitHub CI.
- Do not claim v44 complete until debug, tests, release, and emulator checks pass.

## Critical constraints
- Gaming Mode is strict screen-only: no DOM, JS, console, DevTools, network state, or game memory.
- LLM never runs inside the 50-100 ms input loop.
- Every held input has watchdog release.
- Visually wait for loading and state changes.
- Enforce risk zones outside the model.
- Apply the 10-minute save rule continuously.
