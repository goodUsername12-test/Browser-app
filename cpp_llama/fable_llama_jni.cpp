#include <jni.h>
#include <android/log.h>
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#define FABLE_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "FableLlama", __VA_ARGS__)
#define FABLE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FableLlama", __VA_ARGS__)

namespace {

std::mutex              g_mutex;
std::atomic<bool>       g_backend_ready{false};
std::atomic<bool>       g_abort_requested{false};

llama_model           * g_model   = nullptr;
llama_context         * g_ctx     = nullptr;
llama_batch_ext       * g_batch   = nullptr;
std::string             g_loaded_path;
std::string             g_cached_sys_block;
int32_t                 g_cached_sys_tokens = 0;

void llama_android_logger(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    if (level >= GGML_LOG_LEVEL_ERROR) {
        FABLE_LOGE("%s", text);
    }
}

void ensure_backend_locked() {
    if (!g_backend_ready.load()) {
        llama_log_set(llama_android_logger, nullptr);
        llama_backend_init();
        g_backend_ready.store(true);
    }
}

void unload_locked() {
    if (g_batch) {
        llama_batch_ext_free(g_batch);
        g_batch = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_loaded_path.clear();
    g_cached_sys_block.clear();
    g_cached_sys_tokens = 0;
}

std::vector<llama_token> tokenize_string(const llama_vocab * vocab, const std::string & text, bool add_special) {
    if (text.empty()) return {};
    const int n_est = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    if (n_est <= 0) return {};
    std::vector<llama_token> out(static_cast<size_t>(n_est));
    const int n_tok = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), static_cast<int32_t>(out.size()), add_special, true);
    if (n_tok < 0) return {};
    out.resize(static_cast<size_t>(n_tok));
    return out;
}

bool decode_chunked(
    llama_context * ctx,
    llama_batch_ext * batch,
    const llama_token * tokens,
    int32_t n_tokens,
    llama_pos start_pos,
    int32_t batch_cap,
    bool compute_final_logits
) {
    int32_t offset = 0;
    while (offset < n_tokens) {
        if (g_abort_requested.load(std::memory_order_relaxed)) {
            return false;
        }
        const int32_t chunk = std::min(batch_cap, n_tokens - offset);
        llama_batch_ext_clear(batch);
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[offset + i]);
            if (idx < 0) return false;
            const llama_pos p = start_pos + offset + i;
            llama_batch_ext_set_pos(batch, idx, &p);
            const bool is_very_last = compute_final_logits && (offset + i == n_tokens - 1);
            if (is_very_last) {
                llama_batch_ext_set_output_logits(batch, idx, true);
            }
        }
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            return false;
        }
        offset += chunk;
    }
    return true;
}

bool is_valid_utf8(const std::string & s) {
    const auto * bytes = reinterpret_cast<const unsigned char *>(s.data());
    size_t i = 0;
    const size_t len = s.size();
    while (i < len) {
        int extra = 0;
        const unsigned char c = bytes[i];
        if ((c & 0x80u) == 0x00u) {
            extra = 0;
        } else if ((c & 0xE0u) == 0xC0u) {
            extra = 1;
        } else if ((c & 0xF0u) == 0xE0u) {
            extra = 2;
        } else if ((c & 0xF8u) == 0xF0u) {
            extra = 3;
        } else {
            return false;
        }
        if (i + static_cast<size_t>(extra) >= len) return false;
        for (int k = 1; k <= extra; ++k) {
            if ((bytes[i + static_cast<size_t>(k)] & 0xC0u) != 0x80u) return false;
        }
        i += static_cast<size_t>(extra + 1);
    }
    return true;
}

std::string json_escape(const std::string & in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:   out += c; break;
        }
    }
    return out;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_fable_browser_agent_FableLocalLlmRuntime_nativeInitBackend(JNIEnv * env, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend_locked();
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "llama.cpp ready");
}

extern "C" JNIEXPORT void JNICALL
Java_com_fable_browser_agent_FableLocalLlmRuntime_nativeAbortGeneration(JNIEnv * /*env*/, jclass /*clazz*/) {
    g_abort_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fable_browser_agent_FableLocalLlmRuntime_nativeUnloadModel(JNIEnv * /*env*/, jclass /*clazz*/) {
    g_abort_requested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
    FABLE_LOGI("Unloaded active GGUF model and reclaimed RAM.");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fable_browser_agent_FableLocalLlmRuntime_nativeLoadModel(
    JNIEnv * env,
    jclass /*clazz*/,
    jstring jModelPath,
    jint nCtx,
    jint nThreads
) {
    if (!jModelPath) return env->NewStringUTF("{\"ok\":false,\"error\":\"null_path\"}");
    const char * raw_path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string path(raw_path ? raw_path : "");
    if (raw_path) env->ReleaseStringUTFChars(jModelPath, raw_path);
    if (path.empty()) return env->NewStringUTF("{\"ok\":false,\"error\":\"empty_path\"}");

    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend_locked();

    const int32_t target_ctx = std::max<int32_t>(512, std::min<int32_t>(nCtx, 8192));
    if (g_model && g_ctx && g_loaded_path == path && static_cast<int32_t>(llama_n_ctx(g_ctx)) == target_ctx) {
        char desc_buf[128] = {0};
        llama_model_desc(g_model, desc_buf, sizeof(desc_buf));
        const uint64_t size_mb = llama_model_size(g_model) / (1024ULL * 1024ULL);
        const double params_b = static_cast<double>(llama_model_n_params(g_model)) / 1e9;
        char resp[384];
        std::snprintf(resp, sizeof(resp),
            "{\"ok\":true,\"reused\":true,\"desc\":\"%s\",\"sizeMb\":%llu,\"paramsB\":%.2f,\"nCtx\":%d}",
            json_escape(desc_buf).c_str(),
            static_cast<unsigned long long>(size_mb),
            params_b,
            target_ctx);
        return env->NewStringUTF(resp);
    }

    unload_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        FABLE_LOGE("Failed to load GGUF model: %s", path.c_str());
        return env->NewStringUTF("{\"ok\":false,\"error\":\"model_load_failed\"}");
    }

    const int hw_cores = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int auto_threads = std::max(2, std::min(6, hw_cores - 2));
    const int threads = (nThreads > 0) ? std::max(1, std::min(8, static_cast<int>(nThreads))) : auto_threads;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(target_ctx);
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        FABLE_LOGE("Failed to create llama_context for: %s", path.c_str());
        return env->NewStringUTF("{\"ok\":false,\"error\":\"context_init_failed\"}");
    }

    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    if (!batch) {
        llama_free(ctx);
        llama_model_free(model);
        return env->NewStringUTF("{\"ok\":false,\"error\":\"batch_init_failed\"}");
    }

    g_model = model;
    g_ctx = ctx;
    g_batch = batch;
    g_loaded_path = path;
    g_cached_sys_block.clear();
    g_cached_sys_tokens = 0;

    char desc_buf[128] = {0};
    llama_model_desc(g_model, desc_buf, sizeof(desc_buf));
    const uint64_t size_mb = llama_model_size(g_model) / (1024ULL * 1024ULL);
    const double params_b = static_cast<double>(llama_model_n_params(g_model)) / 1e9;
    const bool hybrid = llama_model_is_hybrid(g_model) || llama_model_is_recurrent(g_model);

    FABLE_LOGI("Loaded GGUF '%s' (%llu MB, %.2fB, ctx=%d, threads=%d, hybrid=%d)",
               desc_buf, static_cast<unsigned long long>(size_mb), params_b, target_ctx, threads, hybrid ? 1 : 0);

    char resp[384];
    std::snprintf(resp, sizeof(resp),
        "{\"ok\":true,\"reused\":false,\"desc\":\"%s\",\"sizeMb\":%llu,\"paramsB\":%.2f,\"nCtx\":%d,\"hybrid\":%s}",
        json_escape(desc_buf).c_str(),
        static_cast<unsigned long long>(size_mb),
        params_b,
        target_ctx,
        hybrid ? "true" : "false");
    return env->NewStringUTF(resp);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fable_browser_agent_FableLocalLlmRuntime_nativeGenerate(
    JNIEnv * env,
    jclass /*clazz*/,
    jstring jSystemPrompt,
    jstring jUserPrompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking
) {
    const char * raw_sys = jSystemPrompt ? env->GetStringUTFChars(jSystemPrompt, nullptr) : "";
    const char * raw_usr = jUserPrompt ? env->GetStringUTFChars(jUserPrompt, nullptr) : "";
    std::string sys_prompt(raw_sys ? raw_sys : "");
    std::string usr_prompt(raw_usr ? raw_usr : "");
    if (jSystemPrompt && raw_sys) env->ReleaseStringUTFChars(jSystemPrompt, raw_sys);
    if (jUserPrompt && raw_usr) env->ReleaseStringUTFChars(jUserPrompt, raw_usr);

    g_abort_requested.store(false, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx || !g_batch) {
        return env->NewStringUTF("");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t ctx_cap = static_cast<int32_t>(llama_n_ctx(g_ctx));
    const int32_t max_gen = std::max<int32_t>(16, std::min<int32_t>(maxTokens, 1024));
    const bool is_hybrid_or_recurrent = llama_model_is_hybrid(g_model) || llama_model_is_recurrent(g_model);

    // Format with Qwen3 / Qwen3.5 ChatML structure; prefill empty <think> block when fast non-thinking is requested
    std::string sys_block = "<|im_start|>system\n" + sys_prompt + "<|im_end|>\n";
    std::string turn_block = "<|im_start|>user\n" + usr_prompt;
    if (!enableThinking) {
        turn_block += " /no_think";
    }
    turn_block += "<|im_end|>\n<|im_start|>assistant\n";
    if (!enableThinking) {
        turn_block += "<think>\n\n</think>\n\n";
    }

    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_pos cur_pos = 0;

    bool reused_sys = false;
    if (!is_hybrid_or_recurrent && !g_cached_sys_block.empty() && g_cached_sys_block == sys_block && g_cached_sys_tokens > 0) {
        if (llama_memory_seq_rm(mem, 0, g_cached_sys_tokens, -1)) {
            cur_pos = g_cached_sys_tokens;
            reused_sys = true;
        }
    }

    if (!reused_sys) {
        llama_memory_clear(mem, false);
        g_cached_sys_block.clear();
        g_cached_sys_tokens = 0;

        if (!is_hybrid_or_recurrent) {
            std::vector<llama_token> sys_toks = tokenize_string(vocab, sys_block, true);
            if (!sys_toks.empty() && static_cast<int32_t>(sys_toks.size()) < ctx_cap - max_gen - 64) {
                if (!decode_chunked(g_ctx, g_batch, sys_toks.data(), static_cast<int32_t>(sys_toks.size()), 0, 512, false)) {
                    llama_memory_clear(mem, false);
                    return env->NewStringUTF("");
                }
                g_cached_sys_block = sys_block;
                g_cached_sys_tokens = static_cast<int32_t>(sys_toks.size());
                cur_pos = g_cached_sys_tokens;
                reused_sys = true;
            }
        }
    }

    const std::string to_eval = reused_sys ? turn_block : (sys_block + turn_block);
    std::vector<llama_token> turn_toks = tokenize_string(vocab, to_eval, !reused_sys);
    if (turn_toks.empty()) {
        return env->NewStringUTF("");
    }

    const int32_t max_prompt_toks = std::max<int32_t>(64, ctx_cap - cur_pos - max_gen - 8);
    if (static_cast<int32_t>(turn_toks.size()) > max_prompt_toks) {
        // Keep tail of user context so latest instruction & JSON cue stay intact
        turn_toks.erase(turn_toks.begin(), turn_toks.end() - max_prompt_toks);
    }

    if (!decode_chunked(g_ctx, g_batch, turn_toks.data(), static_cast<int32_t>(turn_toks.size()), cur_pos, 512, true)) {
        return env->NewStringUTF("");
    }
    cur_pos += static_cast<int32_t>(turn_toks.size());

    // Non-greedy Qwen3 / Qwen3.5 recommended sampler chain
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const int32_t eff_top_k = (topK > 0) ? topK : 20;
    const float   eff_top_p = (topP > 0.05f && topP <= 1.0f) ? topP : 0.85f;
    const float   eff_temp  = std::max(0.15f, std::min(1.2f, static_cast<float>(temperature)));

    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(eff_top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(eff_top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.02f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(eff_temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    std::string utf8_pending;
    output.reserve(512);

    for (int32_t step = 0; step < max_gen; ++step) {
        if (g_abort_requested.load(std::memory_order_relaxed) || cur_pos >= ctx_cap - 2) {
            break;
        }

        const llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, tok);

        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }

        char piece_buf[256];
        const int n_bytes = llama_token_to_piece(vocab, tok, piece_buf, sizeof(piece_buf), 0, false);
        if (n_bytes > 0) {
            utf8_pending.append(piece_buf, static_cast<size_t>(n_bytes));
            if (is_valid_utf8(utf8_pending)) {
                output += utf8_pending;
                utf8_pending.clear();
                if (output.find("<|im_end|>") != std::string::npos ||
                    output.find("<|endoftext|>") != std::string::npos) {
                    break;
                }
            }
        }

        if (!decode_chunked(g_ctx, g_batch, &tok, 1, cur_pos, 1, true)) {
            break;
        }
        cur_pos += 1;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(output.c_str());
}
