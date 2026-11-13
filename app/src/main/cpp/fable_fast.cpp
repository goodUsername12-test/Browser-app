#include <jni.h>
#include <string>
#include <string_view>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>
#include <dirent.h>
#include <pthread.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <unistd.h>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace {

inline constexpr char to_lower_ascii(char c) noexcept {
    return (c >= 'A' && c <= 'Z') ? static_cast<char>(c + ('a' - 'A')) : c;
}

inline bool ends_with_ci(std::string_view str, std::string_view suffix) noexcept {
    if (suffix.size() > str.size()) return false;
    const std::size_t offset = str.size() - suffix.size();
    for (std::size_t i = 0; i < suffix.size(); ++i) {
        if (to_lower_ascii(str[offset + i]) != to_lower_ascii(suffix[i])) return false;
    }
    return true;
}

inline bool starts_with_ci(std::string_view str, std::string_view prefix) noexcept {
    if (prefix.size() > str.size()) return false;
    for (std::size_t i = 0; i < prefix.size(); ++i) {
        if (to_lower_ascii(str[i]) != to_lower_ascii(prefix[i])) return false;
    }
    return true;
}

inline bool contains_ci(std::string_view haystack, std::string_view needle) noexcept {
    if (needle.empty() || needle.size() > haystack.size()) return false;
    const char first_lo = to_lower_ascii(needle[0]);
    const std::size_t limit = haystack.size() - needle.size();
    for (std::size_t i = 0; i <= limit; ++i) {
        if (to_lower_ascii(haystack[i]) != first_lo) continue;
        bool match = true;
        for (std::size_t j = 1; j < needle.size(); ++j) {
            if (to_lower_ascii(haystack[i + j]) != to_lower_ascii(needle[j])) {
                match = false;
                break;
            }
        }
        if (match) return true;
    }
    return false;
}

constexpr std::array<std::int8_t, 256> make_b64_table() noexcept {
    std::array<std::int8_t, 256> t{};
    for (int i = 0; i < 256; ++i) t[static_cast<std::size_t>(i)] = -1;
    for (int i = 'A'; i <= 'Z'; ++i) t[static_cast<std::size_t>(i)] = static_cast<std::int8_t>(i - 'A');
    for (int i = 'a'; i <= 'z'; ++i) t[static_cast<std::size_t>(i)] = static_cast<std::int8_t>(i - 'a' + 26);
    for (int i = '0'; i <= '9'; ++i) t[static_cast<std::size_t>(i)] = static_cast<std::int8_t>(i - '0' + 52);
    t[static_cast<std::size_t>('+')] = 62;
    t[static_cast<std::size_t>('/')] = 63;
    t[static_cast<std::size_t>('-')] = 62;
    t[static_cast<std::size_t>('_')] = 63;
    return t;
}

constexpr auto B64_TABLE = make_b64_table();

constexpr std::array<std::uint32_t, 256> make_crc32_table() noexcept {
    std::array<std::uint32_t, 256> table{};
    for (std::uint32_t i = 0; i < 256; ++i) {
        std::uint32_t c = i;
        for (int k = 0; k < 8; ++k) {
            c = (c & 1u) ? (0xEDB88320u ^ (c >> 1u)) : (c >> 1u);
        }
        table[i] = c;
    }
    return table;
}

constexpr auto CRC32_TABLE = make_crc32_table();

constexpr std::array<std::string_view, 32> BLOCKED_DOMAINS = {
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "google-analytics.com",
    "googletagmanager.com",
    "googletagservices.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "facebook.net",
    "connect.facebook.net",
    "graph.facebook.com",
    "analytics.tiktok.com",
    "ads-twitter.com",
    "static.ads-twitter.com",
    "amazon-adsystem.com",
    "adnxs.com",
    "rubiconproject.com",
    "pubmatic.com",
    "openx.net",
    "criteo.com",
    "criteo.net",
    "taboola.com",
    "outbrain.com",
    "scorecardresearch.com",
    "quantserve.com",
    "hotjar.com",
    "mouseflow.com",
    "clarity.ms",
    "mixpanel.com",
    "amplitude.com",
    "segment.io",
    "sentry.io"
};

constexpr std::array<std::string_view, 8> BLOCKED_PATH_TOKENS = {
    "/pagead/",
    "/adserver",
    "/ads.js",
    "/analytics.js",
    "/gtm.js",
    "/fbevents.js",
    "/collect?v=",
    "/pixel/"
};

constexpr std::array<std::string_view, 16> FAST_SAFE_CDNS = {
    "fonts.gstatic.com",
    "fonts.googleapis.com",
    "cdn.jsdelivr.net",
    "cdnjs.cloudflare.com",
    "unpkg.com",
    "ajax.googleapis.com",
    "assets.babylonjs.com",
    "cdn.babylonjs.com",
    "threejs.org",
    "playcanvas.com",
    "poki-gdn.com",
    "crazygames.com",
    "bloxd.io",
    "staging.bloxd.io",
    "krunker.io",
    "1v1.lol"
};

constexpr std::array<std::string_view, 24> FAST_GAME_EXTENSIONS = {
    ".wasm",
    ".wat",
    ".unityweb",
    ".pck",
    ".mem",
    ".woff2",
    ".woff",
    ".ttf",
    ".otf",
    ".glb",
    ".gltf",
    ".babylon",
    ".ktx2",
    ".dds",
    ".basis",
    ".bin",
    ".atlas",
    ".webp",
    ".avif",
    ".ogg",
    ".mp3",
    ".wgsl",
    ".vert",
    ".frag"
};

std::string_view extract_host(std::string_view url) noexcept {
    const std::size_t scheme_pos = url.find("://");
    const std::size_t start = (scheme_pos == std::string_view::npos) ? 0 : scheme_pos + 3;
    if (start >= url.size()) return {};
    std::size_t end = start;
    while (end < url.size() && url[end] != '/' && url[end] != ':' && url[end] != '?' && url[end] != '#') {
        ++end;
    }
    return url.substr(start, end - start);
}

std::string_view extract_path_no_query(std::string_view url) noexcept {
    const std::size_t scheme_pos = url.find("://");
    const std::size_t start = (scheme_pos == std::string_view::npos) ? 0 : scheme_pos + 3;
    const std::size_t slash = url.find('/', start);
    if (slash == std::string_view::npos) return {};
    std::size_t end = slash;
    while (end < url.size() && url[end] != '?' && url[end] != '#') {
        ++end;
    }
    return url.substr(slash, end - slash);
}

bool is_same_or_subdomain(std::string_view host, std::string_view domain) noexcept {
    if (host.empty() || domain.empty() || host.size() < domain.size()) return false;
    if (!ends_with_ci(host, domain)) return false;
    if (host.size() == domain.size()) return true;
    return host[host.size() - domain.size() - 1] == '.';
}

bool is_fast_same_origin_or_game_asset(std::string_view url, std::string_view page_host) noexcept {
    if (url.empty()) return true;
    if (starts_with_ci(url, "data:") || starts_with_ci(url, "blob:") || starts_with_ci(url, "about:")) {
        return true;
    }
    const std::string_view host = extract_host(url);
    if (host.empty()) return true;
    if (!page_host.empty() && (is_same_or_subdomain(host, page_host) || is_same_or_subdomain(page_host, host))) {
        const std::string_view path = extract_path_no_query(url);
        for (const auto& token : BLOCKED_PATH_TOKENS) {
            if (contains_ci(path, token)) return false;
        }
        return true;
    }
    for (const auto& cdn : FAST_SAFE_CDNS) {
        if (is_same_or_subdomain(host, cdn)) return true;
    }
    const std::string_view path = extract_path_no_query(url);
    if (!path.empty()) {
        for (const auto& ext : FAST_GAME_EXTENSIONS) {
            if (ends_with_ci(path, ext)) {
                for (const auto& domain : BLOCKED_DOMAINS) {
                    if (is_same_or_subdomain(host, domain)) return false;
                }
                return true;
            }
        }
    }
    return false;
}

struct PointerState {
    float x = -10000.0f;
    float y = -10000.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    std::int64_t ts = 0;
};

constexpr std::size_t MAX_POINTER_SLOTS = 16;
std::array<PointerState, MAX_POINTER_SLOTS> g_pointers{};
std::atomic<bool> g_game_boost_enabled{false};
std::atomic<bool> g_screen_recording{false};
std::atomic<std::uint32_t> g_big_core_mask{0};
std::atomic<std::uint32_t> g_little_core_mask{0};
std::atomic<std::uint32_t> g_all_core_mask{0};
std::atomic<std::int64_t> g_last_burst_ns{0};
std::atomic<std::int64_t> g_ema_frame_ns{8333333};

void detect_cpu_topology() noexcept {
    if (g_all_core_mask.load(std::memory_order_relaxed) != 0) return;
    int num_cpus = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    if (num_cpus <= 0 || num_cpus > 16) num_cpus = 8;

    std::array<long, 16> max_freqs{};
    long highest_freq = 0;
    std::uint32_t all_mask = 0;

    for (int i = 0; i < num_cpus; ++i) {
        all_mask |= (1u << i);
        char path[96];
        std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE* f = std::fopen(path, "re");
        if (f) {
            long freq = 0;
            if (std::fscanf(f, "%ld", &freq) == 1 && freq > 0) {
                max_freqs[static_cast<std::size_t>(i)] = freq;
                if (freq > highest_freq) highest_freq = freq;
            }
            std::fclose(f);
        }
    }

    std::uint32_t big_mask = 0;
    if (highest_freq > 0) {
        const long threshold = (highest_freq * 85) / 100;
        for (int i = 0; i < num_cpus; ++i) {
            if (max_freqs[static_cast<std::size_t>(i)] >= threshold) {
                big_mask |= (1u << i);
            }
        }
    }
    if (big_mask == 0) {
        if (num_cpus >= 8) {
            big_mask = (1u << 6) | (1u << 7);
        } else {
            big_mask = all_mask;
        }
    }
    std::uint32_t little_mask = all_mask & ~big_mask;
    if (little_mask == 0) {
        little_mask = all_mask;
    }
    g_big_core_mask.store(big_mask, std::memory_order_relaxed);
    g_little_core_mask.store(little_mask, std::memory_order_relaxed);
    g_all_core_mask.store(all_mask, std::memory_order_relaxed);
}

void pin_tid_to_mask(pid_t tid, std::uint32_t mask) noexcept {
    if (mask == 0) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int i = 0; i < 16; ++i) {
        if ((mask & (1u << i)) != 0) {
            CPU_SET(i, &cpuset);
        }
    }
    sched_setaffinity(tid, sizeof(cpu_set_t), &cpuset);
}

void boost_critical_engine_threads(bool enabled, bool heavy_burst = false) noexcept {
    detect_cpu_topology();
    const std::uint32_t big_mask = g_big_core_mask.load(std::memory_order_relaxed);
    const std::uint32_t raw_little_mask = g_little_core_mask.load(std::memory_order_relaxed);
    const std::uint32_t all_mask = g_all_core_mask.load(std::memory_order_relaxed);
    const bool rec = g_screen_recording.load(std::memory_order_relaxed);
    const std::uint32_t rec_core_mask = (raw_little_mask & 3u) != 0 ? (raw_little_mask & 3u) : raw_little_mask;
    const std::uint32_t little_mask = (rec && (raw_little_mask & ~3u) != 0) ? (raw_little_mask & ~3u) : raw_little_mask;
    const std::uint32_t burst_worker_mask = (rec && (all_mask & ~3u) != 0) ? (all_mask & ~3u) : all_mask;

    DIR* dir = opendir("/proc/self/task");
    if (!dir) return;

    constexpr std::array<std::string_view, 3> BIG_CORE_MAIN_PREFIXES = {
        "CrRendererMain",
        "GeckoMain",
        "WRRender"
    };

    constexpr std::array<std::string_view, 10> COMPOSITOR_GPU_PREFIXES = {
        "CrGpuMain",
        "Chrome_InProcGp",
        "VizCompositor",
        "Compositor",
        "RenderThread",
        "Renderer",
        "WRSceneBuilder",
        "Swappy",
        "Chrome_ChildIO",
        "GpuMemory"
    };

    constexpr std::array<std::string_view, 7> RECORDER_MEDIA_PREFIXES = {
        "FableRec",
        "MediaCodec",
        "CodecLooper",
        "AudioRecord",
        "AudioOutputDev",
        "AAudio",
        "AudioTrack"
    };

    constexpr std::array<std::string_view, 12> WORKER_BOOST_PREFIXES = {
        "ThreadPoolFore",
        "DedicatedWorker",
        "DOM Worker",
        "WebGPU",
        "TileWorker",
        "V8 DefaultWork",
        "Wasm",
        "Liftoff",
        "TurboFan",
        "Maglev",
        "IonCompile",
        "JS Helper"
    };

    constexpr std::array<std::string_view, 9> BACKGROUND_PREFIXES = {
        "ThreadPoolBack",
        "BgIOThreadPool",
        "Cache2 I/O",
        "Cookie",
        "SaveScripts",
        "Telemetry",
        "DiskCache",
        "NetworkCache",
        "IndexedDB"
    };

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] < '1' || entry->d_name[0] > '9') continue;
        const int tid = std::atoi(entry->d_name);
        if (tid <= 0) continue;

        char comm_path[64];
        std::snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
        FILE* f = std::fopen(comm_path, "re");
        if (!f) continue;
        char name_buf[32]{};
        const std::size_t n = std::fread(name_buf, 1, sizeof(name_buf) - 1, f);
        std::fclose(f);
        if (n == 0) continue;

        std::string_view comm(name_buf, n);
        while (!comm.empty() && (comm.back() == '\n' || comm.back() == '\r' || comm.back() == '\0')) {
            comm.remove_suffix(1);
        }

        if (!enabled) {
            pin_tid_to_mask(static_cast<pid_t>(tid), all_mask);
            continue;
        }

        bool matched_main = false;
        for (const auto& prefix : BIG_CORE_MAIN_PREFIXES) {
            if (starts_with_ci(comm, prefix) || contains_ci(comm, prefix)) {
                setpriority(PRIO_PROCESS, static_cast<id_t>(tid), -20);
                pin_tid_to_mask(static_cast<pid_t>(tid), big_mask);
                matched_main = true;
                break;
            }
        }
        if (matched_main) continue;

        bool matched_compositor = false;
        for (const auto& prefix : COMPOSITOR_GPU_PREFIXES) {
            if (starts_with_ci(comm, prefix) || contains_ci(comm, prefix)) {
                setpriority(PRIO_PROCESS, static_cast<id_t>(tid), heavy_burst ? -20 : -19);
                pin_tid_to_mask(static_cast<pid_t>(tid), rec ? (all_mask & ~rec_core_mask) : all_mask);
                matched_compositor = true;
                break;
            }
        }
        if (matched_compositor) continue;

        bool matched_rec = false;
        for (const auto& prefix : RECORDER_MEDIA_PREFIXES) {
            if (starts_with_ci(comm, prefix) || contains_ci(comm, prefix)) {
                setpriority(PRIO_PROCESS, static_cast<id_t>(tid), -14);
                pin_tid_to_mask(static_cast<pid_t>(tid), rec_core_mask);
                matched_rec = true;
                break;
            }
        }
        if (matched_rec) continue;

        bool matched_worker = false;
        for (const auto& prefix : WORKER_BOOST_PREFIXES) {
            if (starts_with_ci(comm, prefix) || contains_ci(comm, prefix)) {
                setpriority(PRIO_PROCESS, static_cast<id_t>(tid), heavy_burst ? -16 : -10);
                pin_tid_to_mask(static_cast<pid_t>(tid), heavy_burst ? burst_worker_mask : little_mask);
                matched_worker = true;
                break;
            }
        }
        if (matched_worker) continue;

        for (const auto& prefix : BACKGROUND_PREFIXES) {
            if (starts_with_ci(comm, prefix) || contains_ci(comm, prefix)) {
                setpriority(PRIO_PROCESS, static_cast<id_t>(tid), heavy_burst ? 12 : 8);
                pin_tid_to_mask(static_cast<pid_t>(tid), little_mask);
                break;
            }
        }
    }
    closedir(dir);
}

bool filter_camera_move(int pointer_id, float x, float y, std::int64_t event_time_ms) noexcept {
    const std::size_t slot = static_cast<std::size_t>(pointer_id & 0x0F);
    PointerState& st = g_pointers[slot];
    const float dx = std::fabs(x - st.x);
    const float dy = std::fabs(y - st.y);
    const std::int64_t dt = event_time_ms - st.ts;

    if (dt >= 0 && dt < 24 && dx < 0.028f && dy < 0.028f) {
        return false;
    }
    if (dt > 0 && dt < 100) {
        const float inv_dt = 1.0f / static_cast<float>(dt);
        st.vx = 0.65f * st.vx + 0.35f * ((x - st.x) * inv_dt);
        st.vy = 0.65f * st.vy + 0.35f * ((y - st.y) * inv_dt);
    }
    st.x = x;
    st.y = y;
    st.ts = event_time_ms;
    return true;
}

bool apply_thread_boost(bool enabled) noexcept {
    g_game_boost_enabled.store(enabled, std::memory_order_relaxed);
    detect_cpu_topology();
    const pid_t self_tid = gettid();
    if (enabled) {
        prctl(PR_SET_TIMERSLACK, 1UL, 0UL, 0UL, 0UL);
    } else {
        prctl(PR_SET_TIMERSLACK, 50000UL, 0UL, 0UL, 0UL);
    }
    setpriority(PRIO_PROCESS, static_cast<id_t>(self_tid), enabled ? -19 : 0);
    pin_tid_to_mask(self_tid, g_all_core_mask.load(std::memory_order_relaxed));
    boost_critical_engine_threads(enabled, false);
    return true;
}

bool notify_heavy_load_burst() noexcept {
    detect_cpu_topology();
    const pid_t self_tid = gettid();
    prctl(PR_SET_TIMERSLACK, 1UL, 0UL, 0UL, 0UL);
    setpriority(PRIO_PROCESS, static_cast<id_t>(self_tid), -20);
    pin_tid_to_mask(self_tid, g_all_core_mask.load(std::memory_order_relaxed));
    boost_critical_engine_threads(true, true);
    return true;
}

std::uint64_t compute_fnv1a_64(const unsigned char* data, std::size_t len) noexcept {
    std::uint64_t h = 0xcbf29ce484222325ULL ^ (static_cast<std::uint64_t>(len) * 0x9e3779b97f4a7c15ULL);
    std::size_t i = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    for (; i + 16 <= len; i += 16) {
        uint8x16_t v = vld1q_u8(data + i);
        uint64x2_t pairs = vreinterpretq_u64_u8(v);
        h ^= vgetq_lane_u64(pairs, 0);
        h *= 0x100000001b3ULL;
        h ^= vgetq_lane_u64(pairs, 1);
        h *= 0x100000001b3ULL;
    }
#endif
    for (; i < len; ++i) {
        h = (h ^ static_cast<std::uint64_t>(data[i])) * 0x100000001b3ULL;
    }
    h ^= (h >> 29u);
    h *= 0xff51afd7ed558ccdULL;
    return h ^ (h >> 32u);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativePrewarm(
    JNIEnv*,
    jclass
) {
    detect_cpu_topology();
    prctl(PR_SET_TIMERSLACK, 1UL, 0UL, 0UL, 0UL);
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeDecodeBase64(
    JNIEnv* env,
    jclass,
    jstring j_b64
) {
    if (j_b64 == nullptr) return nullptr;
    const char* src = env->GetStringUTFChars(j_b64, nullptr);
    if (src == nullptr) return nullptr;
    const std::size_t len = static_cast<std::size_t>(env->GetStringUTFLength(j_b64));
    std::vector<jbyte> out;
    out.reserve((len * 3) / 4 + 4);

    std::uint32_t val = 0;
    int valb = -8;
    for (std::size_t i = 0; i < len; ++i) {
        const unsigned char c = static_cast<unsigned char>(src[i]);
        if (c == '=') break;
        const std::int8_t d = B64_TABLE[c];
        if (d < 0) continue;
        val = (val << 6u) + static_cast<std::uint32_t>(d);
        valb += 6;
        if (valb >= 0) {
            out.push_back(static_cast<jbyte>((val >> static_cast<unsigned>(valb)) & 0xFFu));
            valb -= 8;
        }
    }
    env->ReleaseStringUTFChars(j_b64, src);

    const jsize out_len = static_cast<jsize>(out.size());
    jbyteArray arr = env->NewByteArray(out_len);
    if (arr != nullptr && out_len > 0) {
        env->SetByteArrayRegion(arr, 0, out_len, out.data());
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeCrc32(
    JNIEnv* env,
    jclass,
    jbyteArray j_bytes
) {
    if (j_bytes == nullptr) return 0;
    const jsize len = env->GetArrayLength(j_bytes);
    if (len <= 0) return 0;
    jbyte* buf = env->GetByteArrayElements(j_bytes, nullptr);
    if (buf == nullptr) return 0;

    std::uint32_t crc = 0xFFFFFFFFu;
    const auto* u = reinterpret_cast<const unsigned char*>(buf);
    for (jsize i = 0; i < len; ++i) {
        crc = CRC32_TABLE[(crc ^ u[i]) & 0xFFu] ^ (crc >> 8u);
    }
    env->ReleaseByteArrayElements(j_bytes, buf, JNI_ABORT);
    return static_cast<jlong>(crc ^ 0xFFFFFFFFu);
}

JNIEXPORT jlong JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeHash64(
    JNIEnv* env,
    jclass,
    jbyteArray j_bytes
) {
    if (j_bytes == nullptr) return 0;
    const jsize len = env->GetArrayLength(j_bytes);
    if (len <= 0) return 0;
    jbyte* buf = env->GetByteArrayElements(j_bytes, nullptr);
    if (buf == nullptr) return 0;
    const auto* u = reinterpret_cast<const unsigned char*>(buf);
    const std::uint64_t h = compute_fnv1a_64(u, static_cast<std::size_t>(len));
    env->ReleaseByteArrayElements(j_bytes, buf, JNI_ABORT);
    return static_cast<jlong>(h);
}

JNIEXPORT jlong JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeFastHash64(
    JNIEnv* env,
    jclass,
    jstring j_str
) {
    if (j_str == nullptr) return 0;
    const char* src = env->GetStringUTFChars(j_str, nullptr);
    if (src == nullptr) return 0;
    const std::size_t len = static_cast<std::size_t>(env->GetStringUTFLength(j_str));
    const std::uint64_t h = compute_fnv1a_64(reinterpret_cast<const unsigned char*>(src), len);
    env->ReleaseStringUTFChars(j_str, src);
    return static_cast<jlong>(h);
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeMatchDomain(
    JNIEnv* env,
    jclass,
    jstring j_host,
    jstring j_domain
) {
    if (j_host == nullptr || j_domain == nullptr) return JNI_FALSE;
    const char* host_src = env->GetStringUTFChars(j_host, nullptr);
    if (host_src == nullptr) return JNI_FALSE;
    const char* dom_src = env->GetStringUTFChars(j_domain, nullptr);
    if (dom_src == nullptr) {
        env->ReleaseStringUTFChars(j_host, host_src);
        return JNI_FALSE;
    }
    const std::size_t host_len = static_cast<std::size_t>(env->GetStringUTFLength(j_host));
    const std::size_t dom_len = static_cast<std::size_t>(env->GetStringUTFLength(j_domain));
    const bool matched = is_same_or_subdomain(
        std::string_view(host_src, host_len),
        std::string_view(dom_src, dom_len)
    );
    env->ReleaseStringUTFChars(j_domain, dom_src);
    env->ReleaseStringUTFChars(j_host, host_src);
    return matched ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeIsBlockedDomain(
    JNIEnv* env,
    jclass,
    jstring j_host
) {
    if (j_host == nullptr) return JNI_FALSE;
    const char* src = env->GetStringUTFChars(j_host, nullptr);
    if (src == nullptr) return JNI_FALSE;
    const std::size_t len = static_cast<std::size_t>(env->GetStringUTFLength(j_host));
    std::string_view host(src, len);

    bool blocked = false;
    for (const auto& domain : BLOCKED_DOMAINS) {
        if (is_same_or_subdomain(host, domain)) {
            blocked = true;
            break;
        }
    }
    env->ReleaseStringUTFChars(j_host, src);
    return blocked ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeSetGameBoost(
    JNIEnv*,
    jclass,
    jboolean j_enabled
) {
    return apply_thread_boost(j_enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeNotifyHeavyLoadBurst(
    JNIEnv*,
    jclass
) {
    return notify_heavy_load_burst() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeRecordFrameDeltaNs(
    JNIEnv*,
    jclass,
    jlong j_delta_ns,
    jlong j_now_ns
) {
    if (j_delta_ns <= 0 || j_delta_ns > 500000000LL) return 120;
    const std::int64_t prev_ema = g_ema_frame_ns.load(std::memory_order_relaxed);
    const std::int64_t next_ema = (prev_ema * 7 + j_delta_ns) / 8;
    g_ema_frame_ns.store(next_ema, std::memory_order_relaxed);

    if (j_delta_ns > 14500000LL && g_game_boost_enabled.load(std::memory_order_relaxed)) {
        const std::int64_t last_b = g_last_burst_ns.load(std::memory_order_relaxed);
        if (j_now_ns - last_b > 350000000LL) {
            g_last_burst_ns.store(j_now_ns, std::memory_order_relaxed);
            boost_critical_engine_threads(true, true);
        }
    }
    const std::int64_t safe_ema = next_ema > 1000000LL ? next_ema : 8333333LL;
    const int est_fps = static_cast<int>(1000000000LL / safe_ema);
    return est_fps > 120 ? 120 : (est_fps < 1 ? 1 : est_fps);
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeSetScreenRecording(
    JNIEnv*,
    jclass,
    jboolean j_recording
) {
    const bool rec = (j_recording == JNI_TRUE);
    g_screen_recording.store(rec, std::memory_order_relaxed);
    if (g_game_boost_enabled.load(std::memory_order_relaxed)) {
        boost_critical_engine_threads(true, false);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeResetTouchPointer(
    JNIEnv*,
    jclass,
    jint j_pointer_id
) {
    const std::size_t slot = static_cast<std::size_t>(j_pointer_id & 0x0F);
    g_pointers[slot].x = -10000.0f;
    g_pointers[slot].y = -10000.0f;
    g_pointers[slot].vx = 0.0f;
    g_pointers[slot].vy = 0.0f;
    g_pointers[slot].ts = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeFilterCameraMove(
    JNIEnv*,
    jclass,
    jint j_pointer_id,
    jfloat j_x,
    jfloat j_y,
    jlong j_event_time_ms
) {
    const bool keep = filter_camera_move(
        static_cast<int>(j_pointer_id),
        static_cast<float>(j_x),
        static_cast<float>(j_y),
        static_cast<std::int64_t>(j_event_time_ms)
    );
    return keep ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeIsFastAsset(
    JNIEnv* env,
    jclass,
    jstring j_url,
    jstring j_page_host
) {
    if (j_url == nullptr) return JNI_TRUE;
    const char* url_chars = env->GetStringUTFChars(j_url, nullptr);
    if (url_chars == nullptr) return JNI_FALSE;
    const std::size_t url_len = static_cast<std::size_t>(env->GetStringUTFLength(j_url));

    const char* host_chars = nullptr;
    std::size_t host_len = 0;
    if (j_page_host != nullptr) {
        host_chars = env->GetStringUTFChars(j_page_host, nullptr);
        if (host_chars != nullptr) {
            host_len = static_cast<std::size_t>(env->GetStringUTFLength(j_page_host));
        }
    }

    const bool fast = is_fast_same_origin_or_game_asset(
        std::string_view(url_chars, url_len),
        host_chars != nullptr ? std::string_view(host_chars, host_len) : std::string_view{}
    );

    if (host_chars != nullptr) {
        env->ReleaseStringUTFChars(j_page_host, host_chars);
    }
    env->ReleaseStringUTFChars(j_url, url_chars);
    return fast ? JNI_TRUE : JNI_FALSE;
}

}
