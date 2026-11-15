# Fable Browser v44 (1.44.0, versionCode 40)

## Summary of Fable Multi-Mode Visual & Browser AI Agent in v44
1. **3 Operating Modes (`FableAgentEngine.kt`, `FableNeuralBrain.kt`, `FableAgentModels.kt`)**:
   - **Basic Browsing Mode**: Fast natural-language planning, live DuckDuckGo web search (`FableSearchAndReader.kt`), URL navigation with adaptive visual load waiting, full page text copy to Clipboard + `Downloads/<host>-text.txt`, and 12-stream `.zip` Page Code export.
   - **Gaming Mode (Strict Screen-Only · 50-100ms)**: 100% pixel & touch only (zero DOM/JS/console access). Captures rendered View pixels (`PixelCopy`/Canvas), runs sub-2ms fast target analysis + Rust PD steering (`nativeComputeControlVector` in `lib.rs` & `RustFastCore.kt`) + periodic ML Kit OCR nametag lock, and drives simultaneous multi-touch Joystick + Camera + Attack via `FableScreenInputController.kt` with an 80ms auto-release safety watchdog.
   - **Long Task Mode**: Multi-step state machine opening requested sections (`Projects`, `Analytics`, `Members`, `Upgrade`), waiting for visual screen stability, comparing visible OCR signatures across intervals, stopping early if unchanged, and gating sensitive taps.
2. **Deep Video Upload Skill Learning & 172 GB Model Vault (`FableVideoSkillLearner.kt`, `AgentSettingsScreen.kt`)**:
   - Upload any `.mp4`/`.webm` demonstration video -> hardware `MediaMetadataRetriever` frame sampling + optical motion flow + ML Kit OCR -> saves reusable `LearnedVideoSkill` JSON on local storage + 1-tap replay.
   - Built-in `<4 MB` offline NLU + ML Kit OCR + Rust Vision + 1-tap 6x parallel download of compact 4-bit `Qwen2.5-0.5B` (~395 MB) / `Llama-3.2-1B` (~770 MB) `.gguf` models + optional `llama.cpp`/Ollama/OpenAI endpoint.
3. **Full Autonomy, Emergency Stop HUD & 8 Adjustable Risk Zones (`FableAgentSheet.kt`, `AgentSettingsScreen.kt`, `BrowserScreen.kt`)**:
   - Configurable autonomy (`Auto Safe`, `Full Auto`, `Ask Important`, `Always Ask Everything`), all 8 Risk Zones (`Accessibility`, `Private Worlds`, `Single-Player`, `Game Testing`, `Allowed Automation`, `Approved Macros`, `Competitive Online`, `Important Actions`), live HUD pill, and `<10 ms` `■ STOP` button.
