# Fable Browser Dual-Model LoRA Specialization & GGUF Export Kit

This folder provides the reproducible dataset generator and LoRA fine-tuning pipeline to further specialize **Qwen/Qwen3.5-0.8B** (Primary Brain) and **Qwen/Qwen3-0.6B** (Fast Command Router) on original Safetensors weights (`FP16`/`BF16`) and export mobile `Q4_K_M.gguf` artifacts for Fable's local `llama.cpp` vault.

## Architecture
- **Runtime specialization (already active in Fable v45 APK)**:
  - Native `libfable_llama.so` (`llama.cpp` JNI) loads `Qwen3.5-0.8B-Q4_K_M.gguf` and `Qwen3-0.6B-Q4_K_M.gguf` from `Ai_models` GitHub Release.
  - Supplies Fable's Live Context JSON packet, mode-isolated tool schemas, `/no_think` fast routing, KV-cache system prefix reuse, and non-bypassable Screen-Only / Read-Only guards.
- **Optional GPU LoRA fine-tuning (Colab / PC GPU)**:
  1. `python3 generate_fable_dataset.py` -> builds `fable_train.jsonl` covering Basic Browsing, Strict Screen-Only Gaming, Long Task monitoring, YouTube method synthesis, natural typing, camera/button holds, typos, and Owner Research Beta read-only conversations.
  2. `python3 train_fable_lora.py --model Qwen/Qwen3.5-0.8B --out fable-qwen35-0.8b` (or `Qwen/Qwen3-0.6B`) -> trains a rank-16 LoRA adapter and exports a `Q4_K_M.gguf` ready to upload to GitHub Releases or import in `Fable -> Settings -> AI Agent`.
