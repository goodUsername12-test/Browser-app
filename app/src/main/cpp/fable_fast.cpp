#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>

#if __has_include(<jni.h>)
#include <jni.h>
#define FABLE_HAS_JNI 1
#else
#define FABLE_HAS_JNI 0
#endif

namespace fable_fast {

// 256-entry lookup table for single-pass Base64 decoding:
//  0..63  : valid Base64 sextet
//  0xFE   : whitespace (\r, \n, space, \t) -> skipped
//  0xFD   : padding ('=')
//  0xFF   : invalid character
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

size_t decode_base64(const uint8_t* src, size_t len, uint8_t* dst) noexcept {
    if (!src || !dst || len == 0) return 0;
    size_t out = 0;
    size_t i = 0;

    // Fast 4-byte unrolled loop for contiguous non-whitespace Base64
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

    // Tail & whitespace-tolerant state machine
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

// Fast 64-bit hash (8-byte unrolled FNV-1a / Murmur-style avalanche) for asset deduplication
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

// Fast domain boundary suffix match: returns true if host == domain or host ends with "." + domain
bool match_domain_suffix(const char* host, size_t host_len, const char* domain, size_t dom_len) noexcept {
    if (!host || !domain || dom_len == 0 || host_len < dom_len) return false;
    if (host_len == dom_len) {
        return std::memcmp(host, domain, dom_len) == 0;
    }
    const size_t prefix = host_len - dom_len;
    if (host[prefix - 1] != '.') return false;
    return std::memcmp(host + prefix, domain, dom_len) == 0;
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

} // extern "C"
#endif
