#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>
#include <array>
#if defined(__unix__) || defined(__ANDROID__)
#include <sys/resource.h>
#endif

#if __has_include(<jni.h>)
#include <jni.h>
#define FABLE_HAS_JNI 1
#else
#define FABLE_HAS_JNI 0
#endif

namespace fable_fast {

alignas(64) static constexpr uint8_t kB64Table[256] = {
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFE,0xFE,0xFF,0xFF,0xFE,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFE,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,  62,0xFF,  62,0xFF,  63,
      52,  53,  54,  55,  56,  57,  58,  59,  60,  61,0xFF,0xFF,0xFF,0xFD,0xFF,0xFF,
    0xFF,   0,   1,   2,   3,   4,   5,   6,   7,   8,   9,  10,  11,  12,  13,  14,
      15,  16,  17,  18,  19,  20,  21,  22,  23,  24,  25,0xFF,0xFF,0xFF,0xFF,  63,
    0xFF,  26,  27,  28,  29,  30,  31,  32,  33,  34,  35,  36,  37,  38,  39,  40,
      41,  42,  43,  44,  45,  46,  47,  48,  49,  50,  51,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
    0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF
};

constexpr std::array<uint32_t, 256> make_crc32_table() noexcept {
    std::array<uint32_t, 256> table{};
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t c = i;
        for (int k = 0; k < 8; ++k) {
            c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        table[i] = c;
    }
    return table;
}

alignas(64) static constexpr std::array<uint32_t, 256> kCrc32Table = make_crc32_table();

size_t decode_base64(const uint8_t* src, size_t len, uint8_t* dst) noexcept {
    if (!src || !dst || len == 0) return 0;
    size_t out = 0;
    size_t i = 0;

    while (i + 4 <= len) {
        const uint8_t a = kB64Table[src[i]];
        const uint8_t b = kB64Table[src[i + 1]];
        const uint8_t c = kB64Table[src[i + 2]];
        const uint8_t d = kB64Table[src[i + 3]];
        if ((a | b | c | d) < 64) {
            const uint32_t triple = (static_cast<uint32_t>(a) << 18) |
                                    (static_cast<uint32_t>(b) << 12) |
                                    (static_cast<uint32_t>(c) << 6)  |
                                    static_cast<uint32_t>(d);
            dst[out]     = static_cast<uint8_t>(triple >> 16);
            dst[out + 1] = static_cast<uint8_t>(triple >> 8);
            dst[out + 2] = static_cast<uint8_t>(triple);
            out += 3;
            i += 4;
        } else {
            break;
        }
    }

    uint32_t buf = 0;
    int bits = 0;
    for (; i < len; ++i) {
        const uint8_t v = kB64Table[src[i]];
        if (v < 64) {
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                dst[out++] = static_cast<uint8_t>((buf >> bits) & 0xFFu);
            }
        } else if (v == 0xFD) {
            break;
        }
    }
    return out;
}

uint32_t crc32(const uint8_t* data, size_t len) noexcept {
    uint32_t c = 0xFFFFFFFFu;
    if (!data || len == 0) return 0u;
    size_t i = 0;
    while (i + 4 <= len) {
        c = kCrc32Table[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
        c = kCrc32Table[(c ^ data[i + 1]) & 0xFFu] ^ (c >> 8);
        c = kCrc32Table[(c ^ data[i + 2]) & 0xFFu] ^ (c >> 8);
        c = kCrc32Table[(c ^ data[i + 3]) & 0xFFu] ^ (c >> 8);
        i += 4;
    }
    for (; i < len; ++i) {
        c = kCrc32Table[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
    }
    return c ^ 0xFFFFFFFFu;
}

uint64_t hash64(const uint8_t* data, size_t len) noexcept {
    uint64_t h = 0xcbf29ce484222325ULL ^ (static_cast<uint64_t>(len) * 0x9e3779b97f4a7c15ULL);
    if (!data || len == 0) return h;
    size_t i = 0;
    while (i + 8 <= len) {
        uint64_t word = 0;
        std::memcpy(&word, data + i, sizeof(uint64_t));
        h ^= word;
        h *= 0x100000001b3ULL;
        h ^= (h >> 33);
        i += 8;
    }
    for (; i < len; ++i) {
        h ^= static_cast<uint64_t>(data[i]);
        h *= 0x100000001b3ULL;
    }
    h ^= (h >> 29);
    h *= 0xff51afd7ed558ccdULL;
    h ^= (h >> 32);
    return h;
}

bool match_domain_suffix(const char* host, size_t host_len, const char* domain, size_t dom_len) noexcept {
    if (!host || !domain || dom_len == 0 || host_len < dom_len) return false;
    if (host_len == dom_len) {
        return std::memcmp(host, domain, dom_len) == 0;
    }
    const size_t prefix = host_len - dom_len;
    if (host[prefix - 1] != '.') return false;
    return std::memcmp(host + prefix, domain, dom_len) == 0;
}

size_t extract_host(const char* url, size_t len, char* out_buf, size_t max_out) noexcept {
    if (!url || !out_buf || len == 0 || max_out == 0) return 0;
    size_t start = 0;
    for (size_t i = 0; i + 2 < len && i < 16; ++i) {
        if (url[i] == ':' && url[i + 1] == '/' && url[i + 2] == '/') {
            start = i + 3;
            break;
        }
    }
    if (start == 0) return 0;
    size_t end = start;
    while (end < len) {
        const char ch = url[end];
        if (ch == '/' || ch == '?' || ch == '#' || ch == ':') break;
        if (ch == '@') {
            start = end + 1;
        }
        ++end;
    }
    if (end <= start) return 0;
    if (end - start > 4 &&
        (url[start] == 'w' || url[start] == 'W') &&
        (url[start + 1] == 'w' || url[start + 1] == 'W') &&
        (url[start + 2] == 'w' || url[start + 2] == 'W') &&
        url[start + 3] == '.') {
        start += 4;
    }
    while (end > start && url[end - 1] == '.') {
        --end;
    }
    size_t out_len = end - start;
    if (out_len == 0 || out_len >= max_out) return 0;
    for (size_t i = 0; i < out_len; ++i) {
        char c = url[start + i];
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + ('a' - 'A'));
        out_buf[i] = c;
    }
    out_buf[out_len] = '\0';
    return out_len;
}

bool set_game_thread_boost(bool enable) noexcept {
#if defined(__unix__) || defined(__ANDROID__)
    const int prio = enable ? -8 : 0;
    return ::setpriority(PRIO_PROCESS, 0, prio) == 0;
#else
    (void)enable;
    return false;
#endif
}


struct TouchSlot {
    float last_x = 0.0f;
    float last_y = 0.0f;
    int64_t last_ms = 0;
    bool active = false;
};

alignas(64) static TouchSlot gTouchSlots[16];

void reset_touch_pointer(int pointer_id) noexcept {
    const int idx = pointer_id & 15;
    gTouchSlots[idx].active = false;
    gTouchSlots[idx].last_ms = 0;
}

bool filter_camera_move(int pointer_id, float x, float y, int64_t time_ms) noexcept {
    const int idx = pointer_id & 15;
    TouchSlot& s = gTouchSlots[idx];
    if (!s.active) {
        s.active = true;
        s.last_x = x;
        s.last_y = y;
        s.last_ms = time_ms;
        return true;
    }
    const float dx = x - s.last_x;
    const float dy = y - s.last_y;
    const float abs_dx = dx < 0.0f ? -dx : dx;
    const float abs_dy = dy < 0.0f ? -dy : dy;
    // Only drop exact duplicate zero-delta coordinates; never drop non-zero camera motion
    if (abs_dx < 0.04f && abs_dy < 0.04f) {
        return false;
    }
    s.last_x = x;
    s.last_y = y;
    s.last_ms = time_ms;
    return true;
}

} // namespace fable_fast

#if FABLE_HAS_JNI
extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeDecodeBase64(
    JNIEnv* env,
    jobject /* thiz */,
    jstring b64Str
) {
    if (!env || !b64Str) return nullptr;
    const jsize strLen = env->GetStringUTFLength(b64Str);
    if (strLen <= 0) {
        return env->NewByteArray(0);
    }
    const char* raw = env->GetStringUTFChars(b64Str, nullptr);
    if (!raw) return nullptr;

    const size_t maxOut = (static_cast<size_t>(strLen) * 3) / 4 + 4;
    std::vector<uint8_t> temp(maxOut);
    const size_t decodedLen = fable_fast::decode_base64(
        reinterpret_cast<const uint8_t*>(raw),
        static_cast<size_t>(strLen),
        temp.data()
    );
    env->ReleaseStringUTFChars(b64Str, raw);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(decodedLen));
    if (!result) return nullptr;
    if (decodedLen > 0) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(decodedLen),
            reinterpret_cast<const jbyte*>(temp.data())
        );
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeCrc32(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray bytes
) {
    if (!env || !bytes) return 0L;
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return 0L;
    jboolean isCopy = JNI_FALSE;
    jbyte* ptr = env->GetByteArrayElements(bytes, &isCopy);
    if (!ptr) return 0L;
    const uint32_t c = fable_fast::crc32(
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len)
    );
    env->ReleaseByteArrayElements(bytes, ptr, JNI_ABORT);
    return static_cast<jlong>(c) & 0xFFFFFFFFL;
}

JNIEXPORT jlong JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeHash64(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray bytes
) {
    if (!env || !bytes) return 0L;
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return 0L;
    jboolean isCopy = JNI_FALSE;
    jbyte* ptr = env->GetByteArrayElements(bytes, &isCopy);
    if (!ptr) return 0L;
    const uint64_t h = fable_fast::hash64(
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len)
    );
    env->ReleaseByteArrayElements(bytes, ptr, JNI_ABORT);
    return static_cast<jlong>(h);
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeMatchDomain(
    JNIEnv* env,
    jobject /* thiz */,
    jstring hostStr,
    jstring domainStr
) {
    if (!env || !hostStr || !domainStr) return JNI_FALSE;
    const jsize hostLen = env->GetStringUTFLength(hostStr);
    const jsize domLen = env->GetStringUTFLength(domainStr);
    if (hostLen <= 0 || domLen <= 0 || hostLen < domLen) return JNI_FALSE;
    const char* host = env->GetStringUTFChars(hostStr, nullptr);
    if (!host) return JNI_FALSE;
    const char* dom = env->GetStringUTFChars(domainStr, nullptr);
    if (!dom) {
        env->ReleaseStringUTFChars(hostStr, host);
        return JNI_FALSE;
    }
    const bool ok = fable_fast::match_domain_suffix(
        host, static_cast<size_t>(hostLen),
        dom, static_cast<size_t>(domLen)
    );
    env->ReleaseStringUTFChars(domainStr, dom);
    env->ReleaseStringUTFChars(hostStr, host);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeSetGameBoost(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jboolean enable
) {
    return fable_fast::set_game_thread_boost(enable == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeResetTouchPointer(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pointerId
) {
    fable_fast::reset_touch_pointer(static_cast<int>(pointerId));
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeFastEngine_nativeFilterCameraMove(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pointerId,
    jfloat x,
    jfloat y,
    jlong timeMs
) {
    return fable_fast::filter_camera_move(
        static_cast<int>(pointerId),
        static_cast<float>(x),
        static_cast<float>(y),
        static_cast<int64_t>(timeMs)
    ) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
#endif
