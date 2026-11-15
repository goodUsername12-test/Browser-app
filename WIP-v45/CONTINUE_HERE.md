# Fable Browser v45 (`1.45.0`, `versionCode = 41`) Continuation Guide

## Summary of v45 Upgrades
1. **Embedded Native `llama.cpp` GGUF Runtime (`cpp_llama/fable_llama_jni.cpp`, `cpp_llama/CMakeLists.txt`, `FableLocalLlmRuntime.kt`, Gradle `BuildLlamaJniTask`)**:
   - Compiles official `ggml-org/llama.cpp` (`arm64-v8a` ARMv8.2+FP16+DotProd & `x86_64`, 16KB page-aligned) into `libfable_llama.so`.
   - Wired directly to the repository's `Ai_models` GitHub Release (`Qwen3.5-0.8B-Q4_K_M.gguf` 508 MB Primary + `Qwen3-0.6B-Q4_K_M.gguf` 378 MB Fast) with SHA-256 verification and HuggingFace mirror fallback.
   - Supports `/no_think` rapid JSON generation, KV-cache system prefix reuse on pure GQA transformers (`Qwen3-0.6B`), safe full-sequence resets on hybrid DeltaNet+Attention models (`Qwen3.5-0.8B`), instant atomic generation cancellation, and automatic RAM unloading before 50-100ms Gaming loops.
2. **Owner-Only Research Mode · Beta (`FableOwnerAuthManager.kt`)**:
   - User-created password only (zero hardcoded/default password), derived via PBKDF2-HMAC-SHA256 (120,000 rounds + 16B salt) and encrypted under Android Keystore AES-256-GCM, with optional strong Biometric unlock, lockout protection, and configurable auto-lock timer.
   - Non-bypassable `ReadOnlyPageGuard` guarantees 0% JS/CSS/DOM page injection, keeps Gaming Mode strictly screen-only, preserves Emergency Stop, and enforces confirmation on irreversible actions.
3. **YouTube + Web Fast Strategy & Method Researcher (`FableStrategyResearcher.kt`)**:
   - Parallel YouTube search + `captionTracks` transcript extraction + top web guide synthesis in ~1.2-3.0s, compiling actionable playbooks and executable `LearnedVideoSkill`s.
4. **Natural OS Text Typing, Smooth Camera, 4-Pointer Button Holds & Predictive V2 Rust Steering (`FableScreenInputController.kt`, `RustFastCore.kt`, `lib.rs`)**:
   - `typeTextOnScreen` types via `KeyCharacterMap` + `InputConnection` + `KEYCODE_ENTER` with zero JS/DOM injection.
   - `moveCameraSmooth` turns/tilts camera smoothly with mid-turn re-anchoring so 180°/360° turns never hit screen edges.
   - `stepGamingControl` supports simultaneous Joystick + Camera + Holdable Primary/Mine/Attack button + Jump/Secondary button + 80ms watchdog release.
   - `nativeComputeControlVectorV2` adds predictive velocity lead (`vx`, `vy`) across `APPROACH`, `FOLLOW`, `COMBAT`, and `MINE_HARVEST`.
5. **LoRA Specialization Kit (`training/`)**:
   - Includes `generate_fable_dataset.py` and `train_fable_lora.py` for optional GPU LoRA fine-tuning on Safetensors weights (`Qwen/Qwen3.5-0.8B` & `Qwen/Qwen3-0.6B`) and Q4_K_M GGUF export.
