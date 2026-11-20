#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <mutex>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <unistd.h>
#include <vector>

namespace {

struct CpuTopology {
    int safe_cpus = 8;
    std::uint32_t all_mask = 0xFFu;
    std::uint32_t big_mask = 0xC0u;
};

CpuTopology g_topo;
std::once_flag g_topo_once;

void detect_topology() noexcept {
    const int num_cpus = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    const int safe_cpus = (num_cpus > 0 && num_cpus <= 16) ? num_cpus : 8;
    std::uint32_t all_mask = 0;
    std::array<long, 16> max_freq{};
    long peak_freq = 0;

    for (int i = 0; i < safe_cpus; ++i) {
        all_mask |= (1u << i);
        char path[96];
        std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE* f = std::fopen(path, "re");
        if (f) {
            char buf[32]{};
            if (std::fread(buf, 1, sizeof(buf) - 1, f) > 0) {
                max_freq[static_cast<std::size_t>(i)] = std::atol(buf);
                if (max_freq[static_cast<std::size_t>(i)] > peak_freq) {
                    peak_freq = max_freq[static_cast<std::size_t>(i)];
                }
            }
            std::fclose(f);
        }
    }

    std::uint32_t big_mask = 0;
    if (peak_freq > 0) {
        // Select performance + prime cores (>= 75% of peak frequency)
        const long threshold = (peak_freq * 3L) / 4L;
        for (int i = 0; i < safe_cpus; ++i) {
            if (max_freq[static_cast<std::size_t>(i)] >= threshold) {
                big_mask |= (1u << i);
            }
        }
    }
    if (big_mask == 0 || big_mask == all_mask) {
        big_mask = (safe_cpus >= 8) ? (all_mask & ~0x0Fu) : all_mask;
        if (big_mask == 0) big_mask = all_mask;
    }

    g_topo.safe_cpus = safe_cpus;
    g_topo.all_mask = all_mask;
    g_topo.big_mask = big_mask;
}

inline std::uint32_t hash2d_u32(std::uint32_t x, std::uint32_t z, std::uint32_t seed) noexcept {
    std::uint32_t h = seed ^ (x * 374761393u) ^ (z * 668265263u);
    h = (h ^ (h >> 13u)) * 1274126177u;
    return h ^ (h >> 16u);
}

inline double value_noise_2d(double x, double z, std::uint32_t seed) noexcept {
    const double x_fl = std::floor(x);
    const double z_fl = std::floor(z);
    const std::int64_t xi = static_cast<std::int64_t>(x_fl);
    const std::int64_t zi = static_cast<std::int64_t>(z_fl);
    const std::uint32_t xi0 = static_cast<std::uint32_t>(xi);
    const std::uint32_t zi0 = static_cast<std::uint32_t>(zi);
    const std::uint32_t xi1 = static_cast<std::uint32_t>(xi + 1);
    const std::uint32_t zi1 = static_cast<std::uint32_t>(zi + 1);
    const double xf = x - x_fl;
    const double zf = z - z_fl;
    const double u = xf * xf * (3.0 - 2.0 * xf);
    const double v = zf * zf * (3.0 - 2.0 * zf);

    const double n00 = static_cast<double>(hash2d_u32(xi0, zi0, seed) & 0xFFFFu) / 65535.0;
    const double n10 = static_cast<double>(hash2d_u32(xi1, zi0, seed) & 0xFFFFu) / 65535.0;
    const double n01 = static_cast<double>(hash2d_u32(xi0, zi1, seed) & 0xFFFFu) / 65535.0;
    const double n11 = static_cast<double>(hash2d_u32(xi1, zi1, seed) & 0xFFFFu) / 65535.0;

    const double nx0 = n00 + u * (n10 - n00);
    const double nx1 = n01 + u * (n11 - n01);
    return nx0 + v * (nx1 - nx0);
}

inline int js_math_round(double x) noexcept {
    const double fl = std::floor(x);
    return static_cast<int>((x - fl >= 0.5) ? (fl + 1.0) : fl);
}

// Exact match to local_bloxd_engine.js surfaceHeightAt(wx, wz, seedU32):
// Spawn zone around [16, 16] stands cleanly at y = -4 so [16.5, -2, 16.5] is above ground.
inline int terrain_height(std::int64_t wx, std::int64_t wz, std::uint32_t seed, int mode_id) noexcept {
    if (mode_id == 2) {
        return (wx == 16 && wz == 16) ? -4 : -100;
    }
    if (mode_id == 3) {
        return -4;
    }
    const std::int64_t dx = wx - 16;
    const std::int64_t dz = wz - 16;
    const bool near_spawn = (dx >= -10 && dx <= 10 && dz >= -10 && dz <= 10);
    const std::int64_t dist_sq = near_spawn ? (dx * dx + dz * dz) : 100;
    if (dist_sq <= 25) {
        return -4;
    }
    const double f1 = value_noise_2d(static_cast<double>(wx) * 0.025, static_cast<double>(wz) * 0.025, seed);
    const double f2 = value_noise_2d(static_cast<double>(wx) * 0.075, static_cast<double>(wz) * 0.075, seed + 101u);
    const int raw = static_cast<int>(std::floor(-8.0 + f1 * 9.0 + f2 * 3.0));
    if (dist_sq < 100) {
        const double t = static_cast<double>(dist_sq - 25) / 75.0;
        return js_math_round(-4.0 * (1.0 - t) + static_cast<double>(raw) * t);
    }
    return raw;
}

// Exact Bloxd world_meta block IDs matching local_bloxd_engine.js baseBlockAt:
// 0 Air, 2 Dirt, 4 Grass Block, 5 Sand, 28 Stone, 40 Coal Ore, 41 Iron Ore,
// 42 Gold Ore, 45 Diamond Ore, 147 Bedrock, 465 Moonstone Ore.
inline std::uint16_t base_block_with_surf(std::int64_t wx, std::int64_t wy, std::int64_t wz, int surf, std::uint32_t seed) noexcept {
    if (wy <= -32) return 147u;
    if (wy > surf) return 0u;
    if (wy == surf) return (surf <= -7) ? 5u : 4u;
    if (wy >= surf - 3) return 2u;
    const std::uint32_t hx = static_cast<std::uint32_t>(wx) + static_cast<std::uint32_t>(wy) * 31u;
    const std::uint32_t hz = static_cast<std::uint32_t>(wz) - static_cast<std::uint32_t>(wy) * 17u;
    const std::uint32_t r = hash2d_u32(hx, hz, seed + 911u) & 0x3FFu;
    if (wy < -24 && r < 4u) return 45u;
    if (wy < -20 && r >= 4u && r < 7u) return 465u;
    if (wy < -18 && r >= 7u && r < 14u) return 42u;
    if (r >= 14u && r < 30u) return 41u;
    if (r >= 30u && r < 54u) return 40u;
    return 28u;
}

inline void append_uvarint(std::vector<std::uint8_t>& out, std::uint32_t val) {
    while (val >= 128u) {
        out.push_back(static_cast<std::uint8_t>(0x80u | (val & 0x7Fu)));
        val >>= 7u;
    }
    out.push_back(static_cast<std::uint8_t>(val));
}

void generate_chunk32_rle(
    int cx,
    int cy,
    int cz,
    std::uint32_t seed,
    int mode_id,
    std::vector<std::uint8_t>& out_rle
) {
    out_rle.clear();
    const std::int64_t base_x = static_cast<std::int64_t>(cx) * 32;
    const std::int64_t base_y = static_cast<std::int64_t>(cy) * 32;
    const std::int64_t base_z = static_cast<std::int64_t>(cz) * 32;

    if (base_y > 16) {
        out_rle.reserve(4);
        append_uvarint(out_rle, 32768u);
        append_uvarint(out_rle, 0u);
        return;
    }

    int heights[32][32];
    int max_h = -1024;
    for (int lx = 0; lx < 32; ++lx) {
        const std::int64_t wx = base_x + lx;
        for (int lz = 0; lz < 32; ++lz) {
            const int h = terrain_height(wx, base_z + lz, seed, mode_id);
            heights[lx][lz] = h;
            if (h > max_h) max_h = h;
        }
    }

    if (base_y > max_h && base_y > -32) {
        out_rle.reserve(4);
        append_uvarint(out_rle, 32768u);
        append_uvarint(out_rle, 0u);
        return;
    }

    out_rle.reserve(2048);
    bool has_run = false;
    std::uint16_t cur_val = 0;
    std::uint32_t cur_count = 0;

    // Exact chunkIndex(x, y, z) = (x << 10) + (y << 5) + z traversal order
    for (int lx = 0; lx < 32; ++lx) {
        const std::int64_t wx = base_x + lx;
        for (int ly = 0; ly < 32; ++ly) {
            const std::int64_t wy = base_y + ly;
            for (int lz = 0; lz < 32; ++lz) {
                const std::int64_t wz = base_z + lz;
                std::uint16_t block = 0u;
                if (mode_id == 2) {
                    block = (wx == 16 && wy == -4 && wz == 16) ? 4u : 0u;
                } else {
                    block = base_block_with_surf(wx, wy, wz, heights[lx][lz], seed);
                }
                if (!has_run) {
                    cur_val = block;
                    cur_count = 1u;
                    has_run = true;
                } else if (block == cur_val) {
                    ++cur_count;
                } else {
                    append_uvarint(out_rle, cur_count);
                    append_uvarint(out_rle, static_cast<std::uint32_t>(cur_val));
                    cur_val = block;
                    cur_count = 1u;
                }
            }
        }
    }
    if (has_run) {
        append_uvarint(out_rle, cur_count);
        append_uvarint(out_rle, static_cast<std::uint32_t>(cur_val));
    }
}

void generate_subchunk_16(
    int cx,
    int cy,
    int cz,
    std::uint32_t seed,
    int mode_id,
    std::uint8_t* out_voxels
) noexcept {
    std::memset(out_voxels, 0, 4096);
    const std::int64_t base_x = static_cast<std::int64_t>(cx) * 16;
    const std::int64_t base_y = static_cast<std::int64_t>(cy) * 16;
    const std::int64_t base_z = static_cast<std::int64_t>(cz) * 16;

    if (base_y > 16) {
        return;
    }

    if (mode_id == 2) {
        if (base_x <= 16 && 16 < base_x + 16 && base_y <= -4 && -4 < base_y + 16 && base_z <= 16 && 16 < base_z + 16) {
            const int lx = static_cast<int>(16 - base_x);
            const int ly = static_cast<int>(-4 - base_y);
            const int lz = static_cast<int>(16 - base_z);
            out_voxels[(ly << 8) | (lz << 4) | lx] = 4u;
        }
        return;
    }

    int heights[16][16];
    int max_h = -1024;
    for (int lz = 0; lz < 16; ++lz) {
        for (int lx = 0; lx < 16; ++lx) {
            const int h = terrain_height(base_x + lx, base_z + lz, seed, mode_id);
            heights[lz][lx] = h;
            if (h > max_h) max_h = h;
        }
    }

    if (base_y > max_h && base_y > -32) {
        return;
    }

    for (int lz = 0; lz < 16; ++lz) {
        const std::int64_t wz = base_z + lz;
        for (int lx = 0; lx < 16; ++lx) {
            const std::int64_t wx = base_x + lx;
            const int surface_y = heights[lz][lx];
            for (int ly = 0; ly < 16; ++ly) {
                const std::int64_t wy = base_y + ly;
                const std::uint16_t b16 = base_block_with_surf(wx, wy, wz, surface_y, seed);
                out_voxels[(ly << 8) | (lz << 4) | lx] = static_cast<std::uint8_t>(b16 <= 255u ? b16 : 28u);
            }
        }
    }
}

void apply_thread_policy(bool boost) noexcept {
    std::call_once(g_topo_once, detect_topology);
    const pid_t main_pid = getpid();
    DIR* dir = opendir("/proc/self/task");
    if (!dir) return;

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] < '1' || entry->d_name[0] > '9') continue;
        const int tid = std::atoi(entry->d_name);
        if (tid <= 0) continue;

        char comm_path[64];
        std::snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
        FILE* f = std::fopen(comm_path, "re");
        if (!f) continue;
        char buf[32]{};
        const std::size_t n = std::fread(buf, 1, sizeof(buf) - 1, f);
        std::fclose(f);
        if (n == 0) continue;

        const bool is_hot =
            std::strstr(buf, "CrRendererMain") != nullptr ||
            std::strstr(buf, "CrGpuMain") != nullptr ||
            std::strstr(buf, "VizCompositor") != nullptr;
        const bool is_crit = is_hot || (tid == main_pid) || std::strstr(buf, "RenderThread") != nullptr;

        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        const std::uint32_t target = (boost && is_hot) ? g_topo.big_mask : g_topo.all_mask;
        for (int i = 0; i < g_topo.safe_cpus; ++i) {
            if ((target & (1u << i)) != 0) CPU_SET(i, &cpuset);
        }
        sched_setaffinity(static_cast<pid_t>(tid), sizeof(cpu_set_t), &cpuset);
        if (is_crit) {
            setpriority(PRIO_PROCESS, static_cast<id_t>(tid), boost ? -12 : 0);
        }
    }
    closedir(dir);
}

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_fable_localbloxd_NativeBloxdBridge_nativeGenerateChunk32Rle(
    JNIEnv* env,
    jclass,
    jint cx,
    jint cy,
    jint cz,
    jint seed,
    jint mode_id
) {
    if (env == nullptr) return nullptr;
    try {
        std::vector<std::uint8_t> rle;
        generate_chunk32_rle(
            static_cast<int>(cx),
            static_cast<int>(cy),
            static_cast<int>(cz),
            static_cast<std::uint32_t>(seed),
            static_cast<int>(mode_id),
            rle
        );
        const jsize len = static_cast<jsize>(rle.size());
        jbyteArray arr = env->NewByteArray(len);
        if (arr != nullptr && len > 0) {
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(rle.data()));
        }
        return arr;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_fable_localbloxd_NativeBloxdBridge_nativeGenerateSubchunk(
    JNIEnv* env,
    jclass,
    jint cx,
    jint cy,
    jint cz,
    jint seed,
    jint mode_id
) {
    if (env == nullptr) return nullptr;
    try {
        std::array<std::uint8_t, 4096> voxels{};
        generate_subchunk_16(
            static_cast<int>(cx),
            static_cast<int>(cy),
            static_cast<int>(cz),
            static_cast<std::uint32_t>(seed),
            static_cast<int>(mode_id),
            voxels.data()
        );
        jbyteArray arr = env->NewByteArray(4096);
        if (arr != nullptr) {
            env->SetByteArrayRegion(arr, 0, 4096, reinterpret_cast<const jbyte*>(voxels.data()));
        }
        return arr;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_fable_localbloxd_NativeBloxdBridge_nativeGetSurfaceHeight(
    JNIEnv*,
    jclass,
    jint wx,
    jint wz,
    jint seed,
    jint mode_id
) {
    try {
        return static_cast<jint>(terrain_height(
            static_cast<std::int64_t>(wx),
            static_cast<std::int64_t>(wz),
            static_cast<std::uint32_t>(seed),
            static_cast<int>(mode_id)
        ));
    } catch (...) {
        return -4;
    }
}

JNIEXPORT void JNICALL
Java_com_fable_localbloxd_NativeBloxdBridge_nativeBoostThreads(
    JNIEnv*,
    jclass
) {
    prctl(PR_SET_TIMERSLACK, 1UL, 0UL, 0UL, 0UL);
    apply_thread_policy(true);
}

JNIEXPORT void JNICALL
Java_com_fable_localbloxd_NativeBloxdBridge_nativeRelaxThreads(
    JNIEnv*,
    jclass
) {
    prctl(PR_SET_TIMERSLACK, 50000UL, 0UL, 0UL, 0UL);
    apply_thread_policy(false);
}

} // extern "C"
