#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <pthread.h>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr int DT_INT8   = 0;
constexpr int DT_INT16  = 1;
constexpr int DT_INT32  = 2;
constexpr int DT_INT64  = 3;
constexpr int DT_FLOAT  = 4;
constexpr int DT_DOUBLE = 5;
constexpr int DT_STRING = 6;
constexpr int DT_AOB    = 7;
constexpr int DT_AUTO   = 8;
constexpr int DT_UINT32 = 9;

constexpr int SC_EXACT        = 0;
constexpr int SC_BIGGER       = 1;
constexpr int SC_SMALLER      = 2;
constexpr int SC_BETWEEN      = 3;
constexpr int SC_UNKNOWN      = 4;
constexpr int SC_INCREASED    = 5;
constexpr int SC_DECREASED    = 6;
constexpr int SC_INCREASED_BY = 7;
constexpr int SC_DECREASED_BY = 8;
constexpr int SC_CHANGED      = 9;
constexpr int SC_UNCHANGED    = 10;
constexpr int SC_GROUP        = 11;
constexpr int SC_XOR          = 12;

constexpr int FR_EXACT          = 0;
constexpr int FR_MIN            = 1;
constexpr int FR_MAX            = 2;
constexpr int FR_RANGE          = 3;
constexpr int FR_STEP           = 4;
constexpr int FR_ON_CHANGE_SET  = 5;
constexpr int FR_ON_DEC_RESTORE = 6;
constexpr int FR_ON_CHANGE_ADD  = 7;
constexpr int FR_ON_CHANGE_MUL  = 8;
constexpr int FR_UNLIMITED_MAX  = 9;

constexpr std::size_t MAX_RESULTS_CAP        = 400000;
constexpr std::size_t MAX_UNKNOWN_SNAP_BYTES = 96 * 1024 * 1024;
constexpr std::size_t MAX_UNDO_DEPTH         = 8;
constexpr std::size_t CHUNK_BYTES            = 256 * 1024;
constexpr std::size_t PIPE_STEP_BYTES        = 4096;
constexpr std::size_t MAX_REGION_BYTES       = 128 * 1024 * 1024;

struct MemRegion {
    std::uint64_t start = 0;
    std::uint64_t end = 0;
    char perms[8] = {0};
    std::string label;
};

struct ScanHit {
    std::uint64_t addr = 0;
    std::uint64_t prev_raw = 0;
    std::uint8_t hit_type = DT_INT32;
    std::uint8_t xor_mode = 0; // 0=none, 1=explicit const key, 2=neighbor key addr
    std::uint32_t xor_key = 0;
    std::uint64_t xor_key_addr = 0;
    std::uint8_t prev_bytes[16] = {0};
    std::uint8_t prev_bytes_len = 0;
};

struct UnknownRegionSnap {
    std::uint64_t start = 0;
    std::vector<std::uint8_t> bytes;
};

struct ScanGeneration {
    std::vector<ScanHit> hits;
    std::vector<UnknownRegionSnap> snaps;
    int last_type = DT_INT32;
    std::size_t last_str_len = 0;
    bool snap_active = false;
    bool snap_truncated = false;
    std::size_t snap_bytes = 0;
};

struct AobPattern {
    std::vector<std::uint8_t> bytes;
    std::vector<std::uint8_t> mask;
};

struct GroupTerm {
    int dtype = DT_AUTO; // DT_AUTO or explicit DT_INT8..DT_UINT32
    double dval = 0.0;
    std::int64_t ival = 0;
    bool is_int = false;
};

struct GroupQuery {
    std::vector<GroupTerm> terms;
    std::size_t range_bytes = 64;
    bool ordered = false;
};

class SafeMemIo {
public:
    explicit SafeMemIo(bool writable) : writable_(writable) {
        mem_fd_ = ::open("/proc/self/mem", (writable ? O_RDWR : O_RDONLY) | O_CLOEXEC);
        if (mem_fd_ < 0) {
            if (::pipe2(pipe_fd_, O_CLOEXEC | O_NONBLOCK) == 0) {
#ifdef F_SETPIPE_SZ
                ::fcntl(pipe_fd_[1], F_SETPIPE_SZ, 65536);
#endif
                use_pipe_ = true;
            }
        }
    }

    ~SafeMemIo() {
        if (mem_fd_ >= 0) ::close(mem_fd_);
        if (pipe_fd_[0] >= 0) ::close(pipe_fd_[0]);
        if (pipe_fd_[1] >= 0) ::close(pipe_fd_[1]);
    }

    SafeMemIo(const SafeMemIo&) = delete;
    SafeMemIo& operator=(const SafeMemIo&) = delete;

    bool valid() const { return mem_fd_ >= 0 || use_pipe_; }
    bool using_pipe() const { return use_pipe_; }

    ssize_t read_at(std::uint64_t addr, void* dst, std::size_t len) {
        if (len == 0) return 0;
        if (mem_fd_ >= 0) {
            return ::pread(mem_fd_, dst, len, static_cast<off_t>(addr));
        }
        if (!use_pipe_) return -1;
        auto* out = static_cast<std::uint8_t*>(dst);
        std::size_t total = 0;
        while (total < len) {
            const std::size_t step = std::min<std::size_t>(PIPE_STEP_BYTES, len - total);
            const auto* src_ptr = reinterpret_cast<const void*>(static_cast<std::uintptr_t>(addr + total));
            const ssize_t w = ::write(pipe_fd_[1], src_ptr, step);
            if (w <= 0) {
                drain_pipe();
                break;
            }
            std::size_t got = 0;
            while (got < static_cast<std::size_t>(w)) {
                const ssize_t r = ::read(pipe_fd_[0], out + total + got, static_cast<std::size_t>(w) - got);
                if (r <= 0) {
                    drain_pipe();
                    return total > 0 ? static_cast<ssize_t>(total) : -1;
                }
                got += static_cast<std::size_t>(r);
            }
            total += got;
            if (static_cast<std::size_t>(w) < step) break;
        }
        return total > 0 ? static_cast<ssize_t>(total) : -1;
    }

    ssize_t write_at(std::uint64_t addr, const void* src, std::size_t len) {
        if (!writable_ || len == 0 || len > PIPE_STEP_BYTES) return -1;
        if (mem_fd_ >= 0) {
            return ::pwrite(mem_fd_, src, len, static_cast<off_t>(addr));
        }
        if (!use_pipe_) return -1;
        drain_pipe();
        const ssize_t w = ::write(pipe_fd_[1], src, len);
        if (w != static_cast<ssize_t>(len)) {
            drain_pipe();
            return -1;
        }
        auto* dst_ptr = reinterpret_cast<void*>(static_cast<std::uintptr_t>(addr));
        const ssize_t r = ::read(pipe_fd_[0], dst_ptr, len);
        if (r != static_cast<ssize_t>(len)) {
            drain_pipe();
            return -1;
        }
        return r;
    }

private:
    void drain_pipe() {
        if (pipe_fd_[0] < 0) return;
        std::uint8_t sink[512];
        while (::read(pipe_fd_[0], sink, sizeof(sink)) > 0) {}
    }

    bool writable_ = false;
    bool use_pipe_ = false;
    int mem_fd_ = -1;
    int pipe_fd_[2] = {-1, -1};
};

std::mutex g_scan_mu;
std::atomic<bool> g_cancel_scan{false};
std::atomic<std::uint64_t> g_freeze_buf_start{0};
std::atomic<std::uint64_t> g_freeze_buf_end{0};

std::vector<ScanHit> g_hits;
std::vector<UnknownRegionSnap> g_unknown_snaps;
bool g_snap_active = false;
bool g_snap_truncated = false;
std::size_t g_snap_bytes = 0;
int g_last_type = DT_INT32;
std::size_t g_last_str_len = 0;
std::deque<ScanGeneration> g_undo_stack;

bool is_safe_private_rw_region(const MemRegion& r) {
    if (r.perms[0] != 'r' || r.perms[1] != 'w' || r.perms[2] != '-' || r.perms[3] != 'p') {
        return false;
    }
    if (r.end <= r.start) return false;
    if ((r.end - r.start) > MAX_REGION_BYTES) return false;
    const std::string_view lbl(r.label);
    if (!lbl.empty()) {
        if (lbl.starts_with("/dev/") || lbl.starts_with("/system/") ||
            lbl.starts_with("/apex/") || lbl.starts_with("/vendor/") ||
            lbl.starts_with("/product/") || lbl.starts_with("/odm/") ||
            lbl == "[vdso]" || lbl == "[vvar]" || lbl == "[vsyscall]" ||
            lbl.find("kgsl") != std::string_view::npos ||
            lbl.find("mali") != std::string_view::npos ||
            lbl.find("dma_buf") != std::string_view::npos ||
            lbl.find("dalvik-zygote") != std::string_view::npos ||
            lbl.find("dalvik-LinearAlloc") != std::string_view::npos ||
            lbl.find("dalvik-CompilerMetadata") != std::string_view::npos ||
            lbl.find("dalvik-jit-code-cache") != std::string_view::npos ||
            lbl.ends_with(".dex") || lbl.ends_with(".vdex") ||
            lbl.ends_with(".art") || lbl.ends_with(".oat")) {
            return false;
        }
    }
    return true;
}

std::vector<MemRegion> parse_self_maps() {
    std::vector<MemRegion> out;
    FILE* fp = std::fopen("/proc/self/maps", "r");
    if (!fp) return out;
    char line[1024];
    while (std::fgets(line, sizeof(line), fp)) {
        unsigned long long s = 0, e = 0, off = 0, inode = 0;
        char perms[8] = {0};
        char dev[32] = {0};
        int consumed = 0;
        if (std::sscanf(line, "%llx-%llx %7s %llx %31s %llu%n",
                        &s, &e, perms, &off, dev, &inode, &consumed) >= 6) {
            MemRegion r;
            r.start = static_cast<std::uint64_t>(s);
            r.end = static_cast<std::uint64_t>(e);
            std::memcpy(r.perms, perms, sizeof(r.perms));
            r.perms[sizeof(r.perms) - 1] = '\0';
            const char* p = line + consumed;
            while (*p == ' ' || *p == '\t') ++p;
            std::size_t len = std::strlen(p);
            while (len > 0 && (p[len - 1] == '\n' || p[len - 1] == '\r')) --len;
            r.label.assign(p, len);
            if (is_safe_private_rw_region(r)) {
                out.push_back(std::move(r));
            }
        }
    }
    std::fclose(fp);
    return out;
}

bool is_addr_in_safe_rw_region(std::uint64_t addr, std::size_t len) {
    if (addr == 0 || len == 0 || addr > (UINT64_MAX - len)) return false;
    const auto regions = parse_self_maps();
    for (const auto& r : regions) {
        if (addr >= r.start && (addr + len) <= r.end) {
            return true;
        }
    }
    return false;
}

bool range_overlaps(std::uint64_t addr, std::size_t size, std::uint64_t lo, std::uint64_t hi) {
    return hi > lo && addr < hi && (addr + size) > lo;
}

bool is_inside_scanner_internal(std::uint64_t addr, std::size_t size,
                                std::uint64_t buf_start, std::uint64_t buf_end) {
    if (range_overlaps(addr, size, buf_start, buf_end)) return true;
    auto overlaps_hits = [&](const std::vector<ScanHit>& v) -> bool {
        if (v.capacity() == 0) return false;
        const std::uint64_t vs = reinterpret_cast<std::uint64_t>(v.data());
        return range_overlaps(addr, size, vs, vs + v.capacity() * sizeof(ScanHit));
    };
    auto overlaps_snaps = [&](const std::vector<UnknownRegionSnap>& sn) -> bool {
        for (const auto& s : sn) {
            if (s.bytes.capacity() == 0) continue;
            const std::uint64_t bs = reinterpret_cast<std::uint64_t>(s.bytes.data());
            if (range_overlaps(addr, size, bs, bs + s.bytes.capacity())) return true;
        }
        return false;
    };
    const std::uint64_t f_lo = g_freeze_buf_start.load(std::memory_order_relaxed);
    const std::uint64_t f_hi = g_freeze_buf_end.load(std::memory_order_relaxed);
    if (range_overlaps(addr, size, f_lo, f_hi)) return true;
    if (overlaps_hits(g_hits) || overlaps_snaps(g_unknown_snaps)) return true;
    for (const auto& gen : g_undo_stack) {
        if (overlaps_hits(gen.hits) || overlaps_snaps(gen.snaps)) return true;
    }
    return false;
}

std::size_t type_size(int dtype, std::size_t str_len) {
    switch (dtype) {
        case DT_INT8:   return 1;
        case DT_INT16:  return 2;
        case DT_INT32:  return 4;
        case DT_UINT32: return 4;
        case DT_INT64:  return 8;
        case DT_FLOAT:  return 4;
        case DT_DOUBLE: return 8;
        case DT_STRING: return str_len > 0 ? str_len : 1;
        case DT_AOB:    return str_len > 0 ? str_len : 1;
        default:        return 4;
    }
}

std::size_t type_align(int dtype) {
    switch (dtype) {
        case DT_INT8:   return 1;
        case DT_INT16:  return 2;
        case DT_INT32:  return 4;
        case DT_UINT32: return 4;
        case DT_INT64:  return 8;
        case DT_FLOAT:  return 4;
        case DT_DOUBLE: return 8;
        default:        return 1;
    }
}

const char* type_code_str(int dtype) {
    switch (dtype) {
        case DT_INT8:   return "i8";
        case DT_INT16:  return "i16";
        case DT_INT32:  return "i32";
        case DT_UINT32: return "u32";
        case DT_INT64:  return "i64";
        case DT_FLOAT:  return "f32";
        case DT_DOUBLE: return "f64";
        case DT_STRING: return "str";
        case DT_AOB:    return "aob";
        default:        return "i32";
    }
}

bool parse_double_strict(const char* s, double& out) {
    if (!s || !*s) return false;
    while (*s == ' ' || *s == '\t') ++s;
    if (!*s) return false;
    char* end = nullptr;
    out = std::strtod(s, &end);
    if (end == s || !std::isfinite(out)) return false;
    return true;
}

bool parse_i64_strict(const char* s, std::int64_t& out) {
    if (!s || !*s) return false;
    while (*s == ' ' || *s == '\t') ++s;
    if (!*s) return false;
    char* end = nullptr;
    const bool neg = (*s == '-');
    const char* p = neg ? (s + 1) : s;
    const int base = (p[0] == '0' && (p[1] == 'x' || p[1] == 'X')) ? 16 : 10;
    if (base == 16 && !neg) {
        unsigned long long uv = std::strtoull(s, &end, 16);
        if (end != s && (*end == '\0' || *end == ' ')) {
            out = static_cast<std::int64_t>(uv);
            return true;
        }
    }
    long long v = std::strtoll(s, &end, base);
    if (end != s && (*end == '\0' || *end == ' ')) {
        out = static_cast<std::int64_t>(v);
        return true;
    }
    double d = 0.0;
    if (parse_double_strict(s, d)) {
        out = static_cast<std::int64_t>(std::llround(d));
        return true;
    }
    return false;
}

AobPattern parse_aob(std::string_view text) {
    AobPattern pat;
    std::size_t i = 0;
    const std::size_t n = text.size();
    while (i < n) {
        while (i < n && (text[i] == ' ' || text[i] == '\t' || text[i] == ',' || text[i] == '-')) ++i;
        if (i >= n) break;
        std::size_t j = i;
        while (j < n && text[j] != ' ' && text[j] != '\t' && text[j] != ',' && text[j] != '-') ++j;
        std::string_view tok = text.substr(i, j - i);
        if (tok == "?" || tok == "??" || tok == "*" || tok == "**") {
            pat.bytes.push_back(0);
            pat.mask.push_back(0);
        } else if (tok.size() <= 2) {
            char tmp[4] = {0};
            std::memcpy(tmp, tok.data(), tok.size());
            char* end = nullptr;
            unsigned long v = std::strtoul(tmp, &end, 16);
            if (end != tmp && v <= 0xFF) {
                pat.bytes.push_back(static_cast<std::uint8_t>(v));
                pat.mask.push_back(1);
            }
        }
        i = j;
    }
    return pat;
}

bool parse_range_pair(const std::string& s, double& lo, double& hi) {
    std::size_t pos = s.find("..");
    std::size_t sep_len = 2;
    if (pos == std::string::npos) {
        pos = s.find(',');
        sep_len = 1;
    }
    if (pos == std::string::npos) return false;
    std::string a = s.substr(0, pos);
    std::string b = s.substr(pos + sep_len);
    if (!parse_double_strict(a.c_str(), lo) || !parse_double_strict(b.c_str(), hi)) return false;
    if (hi < lo) std::swap(lo, hi);
    return true;
}

bool parse_group_query(const std::string& raw, GroupQuery& out) {
    out = GroupQuery{};
    std::string body = raw;
    // Check :: (unordered) or : (ordered) range suffix
    std::size_t dbl = raw.rfind("::");
    if (dbl != std::string::npos) {
        out.ordered = false;
        body = raw.substr(0, dbl);
        std::int64_t rng = 64;
        if (parse_i64_strict(raw.substr(dbl + 2).c_str(), rng)) {
            out.range_bytes = static_cast<std::size_t>(std::clamp<std::int64_t>(rng, 4, 4096));
        }
    } else {
        std::size_t sgl = raw.rfind(':');
        if (sgl != std::string::npos) {
            out.ordered = true;
            body = raw.substr(0, sgl);
            std::int64_t rng = 64;
            if (parse_i64_strict(raw.substr(sgl + 1).c_str(), rng)) {
                out.range_bytes = static_cast<std::size_t>(std::clamp<std::int64_t>(rng, 4, 4096));
            }
        }
    }
    std::size_t start = 0;
    while (start <= body.size()) {
        std::size_t sep = body.find(';', start);
        std::string tok = (sep == std::string::npos) ? body.substr(start) : body.substr(start, sep - start);
        while (!tok.empty() && (tok.front() == ' ' || tok.front() == '\t')) tok.erase(tok.begin());
        while (!tok.empty() && (tok.back() == ' ' || tok.back() == '\t')) tok.pop_back();
        if (!tok.empty()) {
            GroupTerm term;
            char last = tok.back();
            bool has_suffix = false;
            if (tok.size() >= 2 && !(tok.size() >= 2 && tok[0] == '0' && (tok[1] == 'x' || tok[1] == 'X'))) {
                switch (last) {
                    case 'b': case 'B': term.dtype = DT_INT8;   has_suffix = true; break;
                    case 's': case 'S': term.dtype = DT_INT16;  has_suffix = true; break;
                    case 'i': case 'I': term.dtype = DT_INT32;  has_suffix = true; break;
                    case 'u': case 'U': term.dtype = DT_UINT32; has_suffix = true; break;
                    case 'q': case 'Q': term.dtype = DT_INT64;  has_suffix = true; break;
                    case 'f': case 'F': term.dtype = DT_FLOAT;  has_suffix = true; break;
                    case 'd': case 'D': term.dtype = DT_DOUBLE; has_suffix = true; break;
                    default: break;
                }
            }
            if (has_suffix) tok.pop_back();
            std::string clean;
            clean.reserve(tok.size());
            for (char c : tok) {
                if (c != ',' && c != '_' && c != ' ') clean.push_back(c);
            }
            if (!parse_double_strict(clean.c_str(), term.dval)) {
                if (!parse_i64_strict(clean.c_str(), term.ival)) return false;
                term.dval = static_cast<double>(term.ival);
                term.is_int = true;
            } else {
                parse_i64_strict(clean.c_str(), term.ival);
                term.is_int = (std::floor(term.dval) == term.dval && term.dval >= -9e15 && term.dval <= 9e15);
            }
            out.terms.push_back(term);
        }
        if (sep == std::string::npos) break;
        start = sep + 1;
    }
    return out.terms.size() >= 2 && out.terms.size() <= 12;
}

std::uint64_t load_raw_u64(const std::uint8_t* ptr, int dtype) {
    std::uint64_t raw = 0;
    switch (dtype) {
        case DT_INT8:   std::memcpy(&raw, ptr, 1); break;
        case DT_INT16:  std::memcpy(&raw, ptr, 2); break;
        case DT_INT32:  std::memcpy(&raw, ptr, 4); break;
        case DT_UINT32: std::memcpy(&raw, ptr, 4); break;
        case DT_INT64:  std::memcpy(&raw, ptr, 8); break;
        case DT_FLOAT:  std::memcpy(&raw, ptr, 4); break;
        case DT_DOUBLE: std::memcpy(&raw, ptr, 8); break;
        default: break;
    }
    return raw;
}

double decode_numeric(std::uint64_t raw, int dtype) {
    switch (dtype) {
        case DT_INT8: {
            std::int8_t v = 0; std::memcpy(&v, &raw, 1);
            return static_cast<double>(v);
        }
        case DT_INT16: {
            std::int16_t v = 0; std::memcpy(&v, &raw, 2);
            return static_cast<double>(v);
        }
        case DT_INT32: {
            std::int32_t v = 0; std::memcpy(&v, &raw, 4);
            return static_cast<double>(v);
        }
        case DT_UINT32: {
            std::uint32_t v = 0; std::memcpy(&v, &raw, 4);
            return static_cast<double>(v);
        }
        case DT_INT64: {
            std::int64_t v = 0; std::memcpy(&v, &raw, 8);
            return static_cast<double>(v);
        }
        case DT_FLOAT: {
            float v = 0.0f; std::memcpy(&v, &raw, 4);
            return static_cast<double>(v);
        }
        case DT_DOUBLE: {
            double v = 0.0; std::memcpy(&v, &raw, 8);
            return v;
        }
        default:
            return 0.0;
    }
}

bool match_numeric(std::uint64_t cur_raw, std::uint64_t prev_raw, bool has_prev,
                   int dtype, int scan_type, double v1, double v2, std::int64_t iv1, std::int64_t iv2) {
    const double eps = (dtype == DT_FLOAT) ? 0.01 : (dtype == DT_DOUBLE ? 0.001 : 0.0);
    if (dtype == DT_INT64) {
        std::int64_t c = 0, p = 0;
        std::memcpy(&c, &cur_raw, 8);
        std::memcpy(&p, &prev_raw, 8);
        switch (scan_type) {
            case SC_EXACT:        return c == iv1;
            case SC_BIGGER:       return c > iv1;
            case SC_SMALLER:      return c < iv1;
            case SC_BETWEEN:      return c >= iv1 && c <= iv2;
            case SC_UNKNOWN:      return true;
            case SC_INCREASED:    return has_prev && c > p;
            case SC_DECREASED:    return has_prev && c < p;
            case SC_INCREASED_BY: return has_prev && (c - p) == iv1;
            case SC_DECREASED_BY: return has_prev && (p - c) == iv1;
            case SC_CHANGED:      return has_prev && c != p;
            case SC_UNCHANGED:    return has_prev && c == p;
            default:              return false;
        }
    }
    const double c = decode_numeric(cur_raw, dtype);
    if (!std::isfinite(c)) return false;
    const double p = has_prev ? decode_numeric(prev_raw, dtype) : 0.0;
    if (has_prev && !std::isfinite(p)) return false;
    switch (scan_type) {
        case SC_EXACT:        return std::fabs(c - v1) <= eps;
        case SC_BIGGER:       return c > v1;
        case SC_SMALLER:      return c < v1;
        case SC_BETWEEN:      return c >= v1 && c <= v2;
        case SC_UNKNOWN:      return true;
        case SC_INCREASED:    return has_prev && (c > p + eps);
        case SC_DECREASED:    return has_prev && (c < p - eps);
        case SC_INCREASED_BY: return has_prev && std::fabs((c - p) - v1) <= (eps > 0.0 ? eps : 1e-6);
        case SC_DECREASED_BY: return has_prev && std::fabs((p - c) - v1) <= (eps > 0.0 ? eps : 1e-6);
        case SC_CHANGED:      return has_prev && std::fabs(c - p) > eps;
        case SC_UNCHANGED:    return has_prev && std::fabs(c - p) <= eps;
        default:              return false;
    }
}

std::string format_value(const std::uint8_t* bytes, std::size_t len, int dtype) {
    char buf[96] = {0};
    switch (dtype) {
        case DT_INT8: {
            if (len < 1) return "?";
            std::int8_t v = 0; std::memcpy(&v, bytes, 1);
            std::snprintf(buf, sizeof(buf), "%d", static_cast<int>(v));
            return buf;
        }
        case DT_INT16: {
            if (len < 2) return "?";
            std::int16_t v = 0; std::memcpy(&v, bytes, 2);
            std::snprintf(buf, sizeof(buf), "%d", static_cast<int>(v));
            return buf;
        }
        case DT_INT32: {
            if (len < 4) return "?";
            std::int32_t v = 0; std::memcpy(&v, bytes, 4);
            std::snprintf(buf, sizeof(buf), "%d", v);
            return buf;
        }
        case DT_UINT32: {
            if (len < 4) return "?";
            std::uint32_t v = 0; std::memcpy(&v, bytes, 4);
            std::snprintf(buf, sizeof(buf), "%u", v);
            return buf;
        }
        case DT_INT64: {
            if (len < 8) return "?";
            std::int64_t v = 0; std::memcpy(&v, bytes, 8);
            std::snprintf(buf, sizeof(buf), "%lld", static_cast<long long>(v));
            return buf;
        }
        case DT_FLOAT: {
            if (len < 4) return "?";
            float v = 0.0f; std::memcpy(&v, bytes, 4);
            if (!std::isfinite(v)) return "NaN";
            std::snprintf(buf, sizeof(buf), "%.4g", static_cast<double>(v));
            return buf;
        }
        case DT_DOUBLE: {
            if (len < 8) return "?";
            double v = 0.0; std::memcpy(&v, bytes, 8);
            if (!std::isfinite(v)) return "NaN";
            std::snprintf(buf, sizeof(buf), "%.6g", v);
            return buf;
        }
        case DT_STRING: {
            std::string out;
            out.reserve(len);
            for (std::size_t i = 0; i < len && i < 64; ++i) {
                const unsigned char ch = bytes[i];
                if (ch == 0) break;
                if (ch >= 32 && ch < 127) out.push_back(static_cast<char>(ch));
                else out.push_back('.');
            }
            return out;
        }
        case DT_AOB: {
            std::string out;
            for (std::size_t i = 0; i < len && i < 24; ++i) {
                char hx[4];
                std::snprintf(hx, sizeof(hx), i == 0 ? "%02X" : " %02X", bytes[i]);
                out.append(hx);
            }
            return out;
        }
        default:
            return "?";
    }
}

bool read_live_xor_key(SafeMemIo& io, int xor_mode, std::uint32_t const_key, std::uint64_t key_addr, std::uint32_t& out_key) {
    if (xor_mode == 1) {
        out_key = const_key;
        return true;
    }
    if (xor_mode == 2 && key_addr != 0) {
        std::uint32_t k = 0;
        if (io.read_at(key_addr, &k, 4) != 4) return false;
        out_key = k;
        return true;
    }
    out_key = 0;
    return true;
}

std::string json_escape(std::string_view s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c >= 32 && c < 127) out.push_back(static_cast<char>(c));
                break;
        }
    }
    return out;
}

void push_undo_generation_locked() {
    if (g_hits.empty() && !g_snap_active) return;
    if (g_undo_stack.size() >= MAX_UNDO_DEPTH) {
        g_undo_stack.pop_front();
    }
    ScanGeneration gen;
    gen.hits = g_hits;
    if (g_snap_active) {
        // Move snapshots rather than duplicating 48MB when leaving snapshot mode
        gen.snaps = std::move(g_unknown_snaps);
        g_unknown_snaps.clear();
    }
    gen.last_type = g_last_type;
    gen.last_str_len = g_last_str_len;
    gen.snap_active = g_snap_active;
    gen.snap_truncated = g_snap_truncated;
    gen.snap_bytes = g_snap_bytes;
    g_undo_stack.push_back(std::move(gen));
}

std::string build_results_json(SafeMemIo& io, std::size_t max_return, std::int64_t elapsed_ms = 0,
                               bool timed_out = false, bool cancelled = false) {
    std::string json;
    json.reserve(4096);
    json += "{\"ok\":true,\"mode\":\"";
    json += (io.using_pipe() ? "pipe" : "proc");
    json += "\",\"total\":";
    const std::size_t total_count = g_snap_active ? (g_hits.size() + (g_snap_bytes / 4)) : g_hits.size();
    json += std::to_string(total_count);
    json += ",\"snapshotActive\":";
    json += (g_snap_active ? "true" : "false");
    json += ",\"snapTruncated\":";
    json += (g_snap_truncated ? "true" : "false");
    json += ",\"canUndo\":";
    json += (g_undo_stack.empty() ? "false" : "true");
    json += ",\"undoDepth\":";
    json += std::to_string(g_undo_stack.size());
    json += ",\"elapsedMs\":";
    json += std::to_string(elapsed_ms);
    json += ",\"timedOut\":";
    json += (timed_out ? "true" : "false");
    json += ",\"cancelled\":";
    json += (cancelled ? "true" : "false");
    json += ",\"complete\":";
    json += ((timed_out || cancelled) ? "false" : "true");
    json += ",\"items\":[";
    const std::size_t limit = std::min(g_hits.size(), max_return);
    std::uint8_t tmp[64];
    for (std::size_t i = 0; i < limit; ++i) {
        if (i > 0) json += ",";
        const auto& h = g_hits[i];
        const int htype = static_cast<int>(h.hit_type);
        const std::size_t rd_len = std::min<std::size_t>(type_size(htype, g_last_str_len), sizeof(tmp));
        std::memset(tmp, 0, sizeof(tmp));
        bool ok_rd = false;
        if (io.valid() && rd_len > 0) {
            const ssize_t got = io.read_at(h.addr, tmp, rd_len);
            ok_rd = (got == static_cast<ssize_t>(rd_len));
            if (ok_rd && h.xor_mode != 0 && rd_len == 4) {
                std::uint32_t k = 0;
                if (read_live_xor_key(io, h.xor_mode, h.xor_key, h.xor_key_addr, k)) {
                    std::uint32_t raw32 = 0;
                    std::memcpy(&raw32, tmp, 4);
                    raw32 ^= k;
                    std::memcpy(tmp, &raw32, 4);
                } else {
                    ok_rd = false;
                }
            }
        }
        char addr_hex[32];
        std::snprintf(addr_hex, sizeof(addr_hex), "0x%llX", static_cast<unsigned long long>(h.addr));
        const std::string val_str = ok_rd ? format_value(tmp, rd_len, htype) : "<unreadable>";
        std::string prev_str;
        if (htype == DT_STRING || htype == DT_AOB) {
            prev_str = h.prev_bytes_len > 0 ? format_value(h.prev_bytes, h.prev_bytes_len, htype) : val_str;
        } else {
            std::uint8_t prev_bytes[8] = {0};
            std::memcpy(prev_bytes, &h.prev_raw, 8);
            prev_str = format_value(prev_bytes, std::min<std::size_t>(rd_len, 8), htype);
        }
        json += "{\"addr\":\"";
        json += addr_hex;
        json += "\",\"val\":\"";
        json += json_escape(val_str);
        json += "\",\"prev\":\"";
        json += json_escape(prev_str);
        json += "\",\"type\":\"";
        json += type_code_str(htype);
        json += "\",\"kind\":\"";
        json += (h.xor_mode != 0 ? "native_xor" : "native");
        json += "\"";
        if (h.xor_mode != 0) {
            char kaddr_hex[32];
            std::snprintf(kaddr_hex, sizeof(kaddr_hex), "0x%llX", static_cast<unsigned long long>(h.xor_key_addr));
            json += ",\"xorMode\":";
            json += std::to_string(static_cast<int>(h.xor_mode));
            json += ",\"xorKey\":";
            json += std::to_string(static_cast<unsigned long long>(h.xor_key));
            json += ",\"xorKeyAddr\":\"";
            json += kaddr_hex;
            json += "\"";
        }
        json += "}";
    }
    json += "]}";
    return json;
}

bool match_group_term_at(const std::uint8_t* data, std::size_t valid, std::size_t off,
                         const GroupTerm& term, int& matched_dt, std::uint64_t& matched_raw) {
    if (term.dtype != DT_AUTO) {
        const std::size_t sz = type_size(term.dtype, 0);
        if (off + sz > valid) return false;
        const std::uint64_t raw = load_raw_u64(data + off, term.dtype);
        if (match_numeric(raw, 0, false, term.dtype, SC_EXACT, term.dval, 0.0, term.ival, 0)) {
            matched_dt = term.dtype;
            matched_raw = raw;
            return true;
        }
        return false;
    }
    // Auto term: i32 -> u32 -> f32 -> f64
    if (off + 4 <= valid) {
        const std::uint64_t raw32 = load_raw_u64(data + off, DT_INT32);
        if (term.is_int && term.ival >= -2147483648LL && term.ival <= 2147483647LL &&
            match_numeric(raw32, 0, false, DT_INT32, SC_EXACT, term.dval, 0.0, term.ival, 0)) {
            matched_dt = DT_INT32;
            matched_raw = raw32;
            return true;
        }
        if (term.is_int && term.ival > 2147483647LL && term.ival <= 4294967295LL &&
            match_numeric(raw32, 0, false, DT_UINT32, SC_EXACT, term.dval, 0.0, term.ival, 0)) {
            matched_dt = DT_UINT32;
            matched_raw = raw32;
            return true;
        }
        const double f32v = decode_numeric(raw32, DT_FLOAT);
        if (std::isfinite(f32v) && (term.dval == 0.0 || std::fabs(f32v) >= 1e-6) &&
            match_numeric(raw32, 0, false, DT_FLOAT, SC_EXACT, term.dval, 0.0, term.ival, 0)) {
            matched_dt = DT_FLOAT;
            matched_raw = raw32;
            return true;
        }
    }
    if (off + 8 <= valid) {
        const std::uint64_t raw64 = load_raw_u64(data + off, DT_DOUBLE);
        const double f64v = decode_numeric(raw64, DT_DOUBLE);
        if (std::isfinite(f64v) && (term.dval == 0.0 || std::fabs(f64v) >= 1e-6) &&
            match_numeric(raw64, 0, false, DT_DOUBLE, SC_EXACT, term.dval, 0.0, term.ival, 0)) {
            matched_dt = DT_DOUBLE;
            matched_raw = raw64;
            return true;
        }
    }
    return false;
}

void diff_unknown_snapshots_locked(const std::vector<UnknownRegionSnap>& snaps, int dtype,
                                   int scan_type, double v1, double v2, std::int64_t iv1, std::int64_t iv2,
                                   SafeMemIo& io, std::vector<ScanHit>& out,
                                   std::chrono::steady_clock::time_point deadline,
                                   bool& timed_out, bool& cancelled) {
    std::vector<std::uint8_t> live(CHUNK_BYTES);
    for (const auto& s : snaps) {
        if (out.size() >= MAX_RESULTS_CAP) break;
        if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
        if (std::chrono::steady_clock::now() >= deadline) { timed_out = true; break; }
        const std::size_t total_len = s.bytes.size();
        std::size_t pos = 0;
        while (pos < total_len && out.size() < MAX_RESULTS_CAP) {
            if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
            if (std::chrono::steady_clock::now() >= deadline) { timed_out = true; break; }
            const std::size_t to_read = std::min<std::size_t>(CHUNK_BYTES, total_len - pos);
            const ssize_t got = io.read_at(s.start + pos, live.data(), to_read);
            if (got <= 0) {
                pos += std::min<std::size_t>(PIPE_STEP_BYTES, to_read);
                continue;
            }
            const std::size_t valid = static_cast<std::size_t>(got);
            const std::uint8_t* old_ptr = s.bytes.data() + pos;
            const std::uint8_t* new_ptr = live.data();

            if (dtype == DT_AUTO) {
                if (valid >= 4) {
                    const std::size_t last_off = valid - 4;
                    for (std::size_t off = 0; off <= last_off && out.size() < MAX_RESULTS_CAP; off += 4) {
                        const std::uint64_t old32 = load_raw_u64(old_ptr + off, DT_INT32);
                        const std::uint64_t new32 = load_raw_u64(new_ptr + off, DT_INT32);
                        if (old32 == new32 && (scan_type == SC_CHANGED || scan_type == SC_INCREASED ||
                            scan_type == SC_DECREASED || (scan_type == SC_INCREASED_BY && v1 > 0.0) ||
                            (scan_type == SC_DECREASED_BY && v1 > 0.0))) {
                            continue;
                        }
                        const double of = decode_numeric(old32, DT_FLOAT);
                        const double nf = decode_numeric(new32, DT_FLOAT);
                        std::int32_t oi = 0, ni = 0;
                        std::memcpy(&oi, &old32, 4);
                        std::memcpy(&ni, &new32, 4);
                        const bool looks_f = std::isfinite(of) && std::isfinite(nf) &&
                            ((std::fabs(of) >= 0.001 && std::fabs(of) <= 1e8) || (std::fabs(nf) >= 0.001 && std::fabs(nf) <= 1e8)) &&
                            (std::abs(oi) > 100000000 || std::abs(ni) > 100000000);
                        if (looks_f) {
                            if (match_numeric(new32, old32, true, DT_FLOAT, scan_type, v1, v2, iv1, iv2)) {
                                ScanHit h;
                                h.addr = s.start + pos + off;
                                h.prev_raw = new32;
                                h.hit_type = DT_FLOAT;
                                out.push_back(h);
                            }
                        } else if (match_numeric(new32, old32, true, DT_INT32, scan_type, v1, v2, iv1, iv2)) {
                            ScanHit h;
                            h.addr = s.start + pos + off;
                            h.prev_raw = new32;
                            h.hit_type = DT_INT32;
                            out.push_back(h);
                        }
                    }
                }
            } else {
                const std::size_t item_sz = type_size(dtype, 0);
                const std::size_t step = type_align(dtype);
                if (valid >= item_sz) {
                    const std::size_t last_off = valid - item_sz;
                    for (std::size_t off = 0; off <= last_off && out.size() < MAX_RESULTS_CAP; off += step) {
                        const std::uint64_t old_raw = load_raw_u64(old_ptr + off, dtype);
                        const std::uint64_t new_raw = load_raw_u64(new_ptr + off, dtype);
                        if (old_raw == new_raw && (scan_type == SC_CHANGED || scan_type == SC_INCREASED ||
                            scan_type == SC_DECREASED || (scan_type == SC_INCREASED_BY && v1 > 0.0) ||
                            (scan_type == SC_DECREASED_BY && v1 > 0.0))) {
                            continue;
                        }
                        if (match_numeric(new_raw, old_raw, true, dtype, scan_type, v1, v2, iv1, iv2)) {
                            ScanHit h;
                            h.addr = s.start + pos + off;
                            h.prev_raw = new_raw;
                            h.hit_type = static_cast<std::uint8_t>(dtype);
                            out.push_back(h);
                        }
                    }
                }
            }
            pos += valid;
        }
    }
}

static bool is_all_zero_block(const std::uint8_t* ptr, std::size_t len) {
    const std::size_t words = len / 8;
    const auto* w = reinterpret_cast<const std::uint64_t*>(ptr);
    for (std::size_t i = 0; i < words; ++i) {
        if (w[i] != 0) return false;
    }
    for (std::size_t i = words * 8; i < len; ++i) {
        if (ptr[i] != 0) return false;
    }
    return true;
}

std::string mem_first_scan_impl(int dtype, int scan_type, const std::string& s1, const std::string& s2,
                                std::size_t max_return, int timeout_ms = 15000) {
    g_cancel_scan.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lk(g_scan_mu);
    const auto t_start = std::chrono::steady_clock::now();
    const int clamped_timeout = std::clamp(timeout_ms, 1000, 80000);
    const auto t_deadline = t_start + std::chrono::milliseconds(clamped_timeout);
    bool timed_out = false;
    bool cancelled = false;

    g_undo_stack.clear();
    g_hits.clear();
    g_unknown_snaps.clear();
    g_snap_active = false;
    g_snap_truncated = false;
    g_snap_bytes = 0;
    g_last_type = dtype;
    g_last_str_len = 0;

    double v1 = 0.0, v2 = 0.0;
    std::int64_t iv1 = 0, iv2 = 0;
    AobPattern aob;
    GroupQuery gq;
    bool xor_has_explicit_key = false;
    std::uint32_t xor_explicit_key = 0;

    if (scan_type == SC_GROUP) {
        if (!parse_group_query(s1, gq)) {
            return "{\"ok\":false,\"error\":\"Invalid group syntax (use e.g. 100;200;50::64 or 100;200:32)\"}";
        }
        g_last_type = DT_AUTO;
    } else if (scan_type == SC_XOR) {
        if (!parse_i64_strict(s1.c_str(), iv1)) {
            return "{\"ok\":false,\"error\":\"Enter target integer value for XOR scan\"}";
        }
        if (!s2.empty()) {
            std::int64_t kv = 0;
            if (!parse_i64_strict(s2.c_str(), kv)) {
                return "{\"ok\":false,\"error\":\"Invalid XOR key (use number or 0xHEX)\"}";
            }
            xor_has_explicit_key = true;
            xor_explicit_key = static_cast<std::uint32_t>(kv);
        }
        g_last_type = DT_INT32;
    } else if (dtype == DT_STRING) {
        if (s1.empty()) return "{\"ok\":false,\"error\":\"Empty string query\"}";
        g_last_str_len = std::min<std::size_t>(s1.size(), 64);
    } else if (dtype == DT_AOB) {
        aob = parse_aob(s1);
        if (aob.bytes.empty()) return "{\"ok\":false,\"error\":\"Invalid hex byte pattern\"}";
        g_last_str_len = aob.bytes.size();
    } else {
        if (scan_type != SC_UNKNOWN) {
            if (!parse_double_strict(s1.c_str(), v1)) return "{\"ok\":false,\"error\":\"Invalid numeric value\"}";
            parse_i64_strict(s1.c_str(), iv1);
            if (scan_type == SC_BETWEEN) {
                if (!parse_double_strict(s2.c_str(), v2)) return "{\"ok\":false,\"error\":\"Invalid max value\"}";
                parse_i64_strict(s2.c_str(), iv2);
                if (v2 < v1) { std::swap(v1, v2); std::swap(iv1, iv2); }
            }
        }
    }

    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self process memory\"}";

    const auto regions = parse_self_maps();

    // Canonical byte snapshot for Unknown Initial (zero-inclusive, all numeric types!)
    if (scan_type == SC_UNKNOWN && dtype != DT_STRING && dtype != DT_AOB) {
        std::size_t used = 0;
        for (const auto& reg : regions) {
            if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
            if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
            if (used >= MAX_UNKNOWN_SNAP_BYTES) { g_snap_truncated = true; break; }
            const std::uint64_t rlen = reg.end - reg.start;
            const std::size_t take = static_cast<std::size_t>(std::min<std::uint64_t>(rlen, MAX_UNKNOWN_SNAP_BYTES - used));
            if (take < 4) continue;
            UnknownRegionSnap snap;
            snap.start = reg.start;
            snap.bytes.resize(take);
            std::size_t filled = 0;
            while (filled < take) {
                if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
                if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
                const std::size_t step = std::min<std::size_t>(CHUNK_BYTES, take - filled);
                const ssize_t got = io.read_at(reg.start + filled, snap.bytes.data() + filled, step);
                if (got <= 0) break;
                filled += static_cast<std::size_t>(got);
            }
            if (filled >= 4) {
                snap.bytes.resize(filled);
                used += filled;
                g_unknown_snaps.push_back(std::move(snap));
            }
            if (take < rlen) g_snap_truncated = true;
            if (cancelled || timed_out) break;
        }
        g_snap_active = !g_unknown_snaps.empty();
        g_snap_bytes = used;
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
        return build_results_json(io, max_return, elapsed_ms, timed_out, cancelled);
    }

    std::vector<std::uint8_t> chunk(CHUNK_BYTES);
    const std::uint64_t chunk_start = reinterpret_cast<std::uint64_t>(chunk.data());
    const std::uint64_t chunk_end = chunk_start + chunk.size();
    const std::size_t cap = MAX_RESULTS_CAP;
    const std::uint64_t s1_start = reinterpret_cast<std::uint64_t>(s1.data());
    const std::uint64_t s1_end = s1_start + s1.capacity() + 1;

    for (const auto& reg : regions) {
        if (g_hits.size() >= cap) break;
        if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
        if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
        std::uint64_t cursor = reg.start;
        while (cursor < reg.end && g_hits.size() < cap) {
            if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
            if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
            const std::size_t to_read = static_cast<std::size_t>(std::min<std::uint64_t>(CHUNK_BYTES, reg.end - cursor));
            if (to_read < 1) break;
            const ssize_t got = io.read_at(cursor, chunk.data(), to_read);
            if (got <= 0) {
                cursor += std::min<std::size_t>(PIPE_STEP_BYTES, to_read);
                continue;
            }
            const std::size_t valid = static_cast<std::size_t>(got);
            if (scan_type == SC_EXACT && dtype != DT_STRING && dtype != DT_AOB && std::fabs(v1) > 1e-6 && is_all_zero_block(chunk.data(), valid)) {
                cursor += valid;
                continue;
            }

            if (scan_type == SC_GROUP) {
                const auto& t0 = gq.terms[0];
                const std::size_t step0 = (t0.dtype == DT_INT8) ? 1 : (t0.dtype == DT_INT16 ? 2 : 4);
                if (valid >= 4) {
                    const std::size_t last0 = valid - std::min<std::size_t>(4, valid);
                    for (std::size_t off0 = 0; off0 <= last0 && g_hits.size() < cap; off0 += step0) {
                        int dt0 = DT_INT32;
                        std::uint64_t raw0 = 0;
                        if (!match_group_term_at(chunk.data(), valid, off0, t0, dt0, raw0)) continue;
                        const std::uint64_t a0 = cursor + off0;
                        if (is_inside_scanner_internal(a0, 8, chunk_start, chunk_end)) continue;

                        std::vector<ScanHit> group_found;
                        group_found.reserve(gq.terms.size());
                        {
                            ScanHit h0; h0.addr = a0; h0.prev_raw = raw0; h0.hit_type = static_cast<std::uint8_t>(dt0);
                            group_found.push_back(h0);
                        }
                        std::vector<std::size_t> used_offs;
                        used_offs.push_back(off0);
                        bool all_matched = true;
                        std::size_t prev_off = off0;

                        for (std::size_t ti = 1; ti < gq.terms.size(); ++ti) {
                            const auto& tk = gq.terms[ti];
                            const std::size_t stepk = (tk.dtype == DT_INT8) ? 1 : (tk.dtype == DT_INT16 ? 2 : 4);
                            const std::size_t win_lo = gq.ordered
                                ? (prev_off + 1)
                                : (off0 > gq.range_bytes ? (off0 - gq.range_bytes) : 0);
                            const std::size_t win_hi = std::min<std::size_t>(valid, off0 + gq.range_bytes + 1);
                            std::size_t aligned_lo = (win_lo + stepk - 1) / stepk * stepk;
                            bool matched_k = false;
                            for (std::size_t ok_off = aligned_lo; ok_off < win_hi; ok_off += stepk) {
                                bool dup = false;
                                for (std::size_t uo : used_offs) { if (uo == ok_off) { dup = true; break; } }
                                if (dup) continue;
                                int dtk = DT_INT32;
                                std::uint64_t rawk = 0;
                                if (match_group_term_at(chunk.data(), valid, ok_off, tk, dtk, rawk)) {
                                    ScanHit hk;
                                    hk.addr = cursor + ok_off;
                                    hk.prev_raw = rawk;
                                    hk.hit_type = static_cast<std::uint8_t>(dtk);
                                    group_found.push_back(hk);
                                    used_offs.push_back(ok_off);
                                    prev_off = ok_off;
                                    matched_k = true;
                                    break;
                                }
                            }
                            if (!matched_k) { all_matched = false; break; }
                        }
                        if (all_matched) {
                            for (const auto& gh : group_found) {
                                if (g_hits.size() < cap) g_hits.push_back(gh);
                            }
                        }
                    }
                }
                const std::size_t overlap = std::min<std::size_t>(gq.range_bytes, valid > 64 ? valid / 2 : 0);
                cursor += (valid > overlap && cursor + valid < reg.end) ? (valid - overlap) : valid;
            } else if (scan_type == SC_XOR) {
                const std::uint32_t target_u32 = static_cast<std::uint32_t>(static_cast<std::int32_t>(iv1));
                if (valid >= 4) {
                    const std::size_t slots = valid / 4;
                    if (xor_has_explicit_key) {
                        const std::uint32_t enc = target_u32 ^ xor_explicit_key;
                        for (std::size_t si = 0; si < slots && g_hits.size() < cap; ++si) {
                            std::uint32_t w = 0;
                            std::memcpy(&w, chunk.data() + si * 4, 4);
                            if (w == enc) {
                                const std::uint64_t addr = cursor + si * 4;
                                if (is_inside_scanner_internal(addr, 4, chunk_start, chunk_end)) continue;
                                ScanHit h;
                                h.addr = addr;
                                h.prev_raw = target_u32;
                                h.hit_type = DT_INT32;
                                h.xor_mode = 1;
                                h.xor_key = xor_explicit_key;
                                g_hits.push_back(h);
                            }
                        }
                    } else {
                        // Auto-Key discovery: check neighbor keys at +-4, +-8, +-12, +-16 bytes
                        // Require non-trivial 32-bit key entropy (both 16-bit halves non-zero/non-FFFF) to avoid heap pointer/zero noise
                        auto is_plausible_xor = [](std::uint32_t v) -> bool {
                            const std::uint32_t hi = v >> 16;
                            const std::uint32_t lo = v & 0xFFFFu;
                            return hi != 0u && hi != 0xFFFFu && lo != 0u && lo != 0xFFFFu && hi != lo;
                        };
                        static constexpr int kDeltas[8] = { -4, 4, -8, 8, -12, 12, -16, 16 };
                        for (std::size_t si = 0; si < slots && g_hits.size() < cap; ++si) {
                            std::uint32_t w = 0;
                            std::memcpy(&w, chunk.data() + si * 4, 4);
                            if (w == 0 || w == 0xFFFFFFFFu || w == target_u32 || !is_plausible_xor(w)) continue;
                            const std::uint32_t needed_key = w ^ target_u32;
                            if (needed_key == 0 || needed_key == 0xFFFFFFFFu || needed_key == w || !is_plausible_xor(needed_key)) continue;
                            for (int d : kDeltas) {
                                const std::int64_t nslot = static_cast<std::int64_t>(si) + (d / 4);
                                if (nslot < 0 || static_cast<std::size_t>(nslot) >= slots) continue;
                                std::uint32_t cand_k = 0;
                                std::memcpy(&cand_k, chunk.data() + static_cast<std::size_t>(nslot) * 4, 4);
                                if (cand_k == needed_key) {
                                    const std::uint64_t addr = cursor + si * 4;
                                    if (is_inside_scanner_internal(addr, 4, chunk_start, chunk_end)) break;
                                    ScanHit h;
                                    h.addr = addr;
                                    h.prev_raw = target_u32;
                                    h.hit_type = DT_INT32;
                                    h.xor_mode = 2;
                                    h.xor_key = cand_k;
                                    h.xor_key_addr = cursor + static_cast<std::size_t>(nslot) * 4;
                                    g_hits.push_back(h);
                                    break;
                                }
                            }
                        }
                    }
                }
                cursor += valid;
            } else if (dtype == DT_STRING) {
                const std::size_t item_sz = type_size(DT_STRING, g_last_str_len);
                if (valid >= item_sz) {
                    const std::size_t last_off = valid - item_sz;
                    const auto* needle = reinterpret_cast<const std::uint8_t*>(s1.data());
                    for (std::size_t off = 0; off <= last_off && g_hits.size() < cap; ++off) {
                        if (chunk[off] == needle[0] && std::memcmp(chunk.data() + off, needle, item_sz) == 0) {
                            const std::uint64_t addr = cursor + off;
                            if (addr >= s1_start && addr < s1_end) continue;
                            if (is_inside_scanner_internal(addr, item_sz, chunk_start, chunk_end)) continue;
                            ScanHit h;
                            h.addr = addr;
                            h.hit_type = DT_STRING;
                            h.prev_bytes_len = static_cast<std::uint8_t>(std::min<std::size_t>(item_sz, sizeof(h.prev_bytes)));
                            std::memcpy(h.prev_bytes, chunk.data() + off, h.prev_bytes_len);
                            g_hits.push_back(h);
                        }
                    }
                }
                const std::size_t advance = (valid > item_sz) ? (valid - item_sz + 1) : valid;
                cursor += advance;
            } else if (dtype == DT_AOB) {
                const std::size_t item_sz = type_size(DT_AOB, g_last_str_len);
                if (valid >= item_sz) {
                    const std::size_t last_off = valid - item_sz;
                    for (std::size_t off = 0; off <= last_off && g_hits.size() < cap; ++off) {
                        bool ok = true;
                        for (std::size_t k = 0; k < aob.bytes.size(); ++k) {
                            if (aob.mask[k] && chunk[off + k] != aob.bytes[k]) {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) {
                            const std::uint64_t addr = cursor + off;
                            if (is_inside_scanner_internal(addr, item_sz, chunk_start, chunk_end)) continue;
                            ScanHit h;
                            h.addr = addr;
                            h.hit_type = DT_AOB;
                            h.prev_bytes_len = static_cast<std::uint8_t>(std::min<std::size_t>(item_sz, sizeof(h.prev_bytes)));
                            std::memcpy(h.prev_bytes, chunk.data() + off, h.prev_bytes_len);
                            g_hits.push_back(h);
                        }
                    }
                }
                const std::size_t advance = (valid > item_sz) ? (valid - item_sz + 1) : valid;
                cursor += advance;
            } else if (dtype == DT_AUTO) {
                if (valid >= 4) {
                    const std::size_t last_off = valid - 4;
                    for (std::size_t off = 0; off <= last_off && g_hits.size() < cap; off += 4) {
                        const std::uint64_t addr = cursor + off;
                        if (is_inside_scanner_internal(addr, 8, chunk_start, chunk_end)) continue;
                        const std::uint64_t raw32 = load_raw_u64(chunk.data() + off, DT_INT32);
                        if (match_numeric(raw32, 0, false, DT_INT32, scan_type, v1, v2, iv1, iv2)) {
                            ScanHit h; h.addr = addr; h.prev_raw = raw32; h.hit_type = DT_INT32;
                            g_hits.push_back(h);
                            continue;
                        }
                        if (v1 > 2147483647.0 && v1 <= 4294967295.0 &&
                            match_numeric(raw32, 0, false, DT_UINT32, scan_type, v1, v2, iv1, iv2)) {
                            ScanHit h; h.addr = addr; h.prev_raw = raw32; h.hit_type = DT_UINT32;
                            g_hits.push_back(h);
                            continue;
                        }
                        const double f32v = decode_numeric(raw32, DT_FLOAT);
                        if (std::isfinite(f32v) && (v1 == 0.0 || std::fabs(f32v) >= 1e-6) &&
                            match_numeric(raw32, 0, false, DT_FLOAT, scan_type, v1, v2, iv1, iv2)) {
                            ScanHit h; h.addr = addr; h.prev_raw = raw32; h.hit_type = DT_FLOAT;
                            g_hits.push_back(h);
                            continue;
                        }
                        if ((off + 8) <= valid) {
                            const std::uint64_t raw64 = load_raw_u64(chunk.data() + off, DT_DOUBLE);
                            if (scan_type == SC_EXACT && (iv1 < -2147483648LL || iv1 > 2147483647LL) &&
                                match_numeric(raw64, 0, false, DT_INT64, scan_type, v1, v2, iv1, iv2)) {
                                ScanHit h; h.addr = addr; h.prev_raw = raw64; h.hit_type = DT_INT64;
                                g_hits.push_back(h);
                                continue;
                            }
                            const double f64v = decode_numeric(raw64, DT_DOUBLE);
                            if (std::isfinite(f64v) && (v1 == 0.0 || std::fabs(f64v) >= 1e-6) &&
                                match_numeric(raw64, 0, false, DT_DOUBLE, scan_type, v1, v2, iv1, iv2)) {
                                ScanHit h; h.addr = addr; h.prev_raw = raw64; h.hit_type = DT_DOUBLE;
                                g_hits.push_back(h);
                                continue;
                            }
                        }
                        if (scan_type == SC_EXACT && iv1 >= -32768 && iv1 <= 32767 && iv1 != 0 && std::floor(v1) == v1) {
                            const std::uint64_t s16a = load_raw_u64(chunk.data() + off, DT_INT16);
                            if (match_numeric(s16a, 0, false, DT_INT16, scan_type, v1, v2, iv1, iv2)) {
                                ScanHit h; h.addr = addr; h.prev_raw = s16a; h.hit_type = DT_INT16;
                                g_hits.push_back(h);
                            }
                            const std::uint64_t s16b = load_raw_u64(chunk.data() + off + 2, DT_INT16);
                            if (match_numeric(s16b, 0, false, DT_INT16, scan_type, v1, v2, iv1, iv2)) {
                                ScanHit h; h.addr = addr + 2; h.prev_raw = s16b; h.hit_type = DT_INT16;
                                g_hits.push_back(h);
                            }
                        }
                    }
                }
                cursor += valid;
            } else {
                const std::size_t item_sz = type_size(dtype, 0);
                const std::size_t step = type_align(dtype);
                if (valid >= item_sz) {
                    const std::size_t last_off = valid - item_sz;
                    for (std::size_t off = 0; off <= last_off && g_hits.size() < cap; off += step) {
                        const std::uint64_t raw = load_raw_u64(chunk.data() + off, dtype);
                        if (match_numeric(raw, 0, false, dtype, scan_type, v1, v2, iv1, iv2)) {
                            const std::uint64_t addr = cursor + off;
                            if (is_inside_scanner_internal(addr, item_sz, chunk_start, chunk_end)) continue;
                            ScanHit h; h.addr = addr; h.prev_raw = raw; h.hit_type = static_cast<std::uint8_t>(dtype);
                            g_hits.push_back(h);
                        }
                    }
                }
                cursor += valid;
            }
        }
        if (cancelled || timed_out) break;
    }

    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t_start).count();
    return build_results_json(io, max_return, elapsed_ms, timed_out, cancelled);
}

std::string mem_next_scan_impl(int scan_type, const std::string& s1, const std::string& s2,
                               std::size_t max_return, int timeout_ms = 15000) {
    g_cancel_scan.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lk(g_scan_mu);
    const auto t_start = std::chrono::steady_clock::now();
    const auto t_deadline = t_start + std::chrono::milliseconds(std::clamp(timeout_ms, 1000, 80000));
    bool timed_out = false;
    bool cancelled = false;

    if (g_hits.empty() && !g_snap_active) {
        return "{\"ok\":false,\"error\":\"Run First Scan before Next Scan\"}";
    }
    const int dtype = g_last_type;
    if (scan_type == SC_UNKNOWN) scan_type = SC_CHANGED;
    if (scan_type == SC_XOR) scan_type = SC_EXACT;
    if (scan_type == SC_GROUP) scan_type = SC_CHANGED;

    double v1 = 0.0, v2 = 0.0;
    std::int64_t iv1 = 0, iv2 = 0;
    AobPattern aob;
    const bool needs_v1 = (scan_type == SC_EXACT || scan_type == SC_BIGGER || scan_type == SC_SMALLER ||
                           scan_type == SC_BETWEEN || scan_type == SC_INCREASED_BY || scan_type == SC_DECREASED_BY);
    if (dtype == DT_STRING) {
        if (needs_v1 && s1.empty()) return "{\"ok\":false,\"error\":\"Empty string query\"}";
    } else if (dtype == DT_AOB) {
        if (needs_v1) {
            aob = parse_aob(s1);
            if (aob.bytes.empty()) return "{\"ok\":false,\"error\":\"Invalid hex byte pattern\"}";
        }
    } else if (needs_v1) {
        if (!parse_double_strict(s1.c_str(), v1)) return "{\"ok\":false,\"error\":\"Invalid numeric value\"}";
        parse_i64_strict(s1.c_str(), iv1);
        if (scan_type == SC_BETWEEN) {
            if (!parse_double_strict(s2.c_str(), v2)) return "{\"ok\":false,\"error\":\"Invalid max value\"}";
            parse_i64_strict(s2.c_str(), iv2);
            if (v2 < v1) { std::swap(v1, v2); std::swap(iv1, iv2); }
        }
    }

    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self process memory\"}";

    // Push current generation onto undo stack BEFORE mutating
    push_undo_generation_locked();
    std::vector<ScanHit> next;

    if (g_snap_active) {
        const auto& saved_snaps = g_undo_stack.back().snaps;
        g_snap_active = false;
        g_snap_truncated = false;
        g_snap_bytes = 0;
        next.reserve(4096);
        diff_unknown_snapshots_locked(saved_snaps, dtype, scan_type, v1, v2, iv1, iv2, io, next, t_deadline, timed_out, cancelled);
        g_hits.swap(next);
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
        return build_results_json(io, max_return, elapsed_ms, timed_out, cancelled);
    }

    next.reserve(std::min<std::size_t>(g_hits.size(), 4096));
    std::uint8_t buf[64];
    std::size_t checked = 0;
    for (const auto& h : g_hits) {
        if ((++checked & 1023U) == 0) {
            if (g_cancel_scan.load(std::memory_order_acquire)) { cancelled = true; break; }
            if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
        }
        const int htype = static_cast<int>(h.hit_type);
        const std::size_t rd_sz = std::min<std::size_t>(type_size(htype, g_last_str_len), sizeof(buf));
        const ssize_t got = io.read_at(h.addr, buf, rd_sz);
        if (got != static_cast<ssize_t>(rd_sz)) continue;

        if (htype == DT_STRING) {
            const std::size_t blen = std::min<std::size_t>(rd_sz, sizeof(h.prev_bytes));
            bool ok = false;
            if (scan_type == SC_CHANGED) {
                ok = (h.prev_bytes_len > 0 && std::memcmp(buf, h.prev_bytes, std::min<std::size_t>(blen, h.prev_bytes_len)) != 0);
            } else if (scan_type == SC_UNCHANGED) {
                ok = (h.prev_bytes_len > 0 && std::memcmp(buf, h.prev_bytes, std::min<std::size_t>(blen, h.prev_bytes_len)) == 0);
            } else {
                const std::size_t cmp_len = std::min(rd_sz, s1.size());
                ok = (cmp_len > 0 && std::memcmp(buf, s1.data(), cmp_len) == 0);
            }
            if (ok) {
                ScanHit nh = h;
                nh.prev_bytes_len = static_cast<std::uint8_t>(blen);
                std::memcpy(nh.prev_bytes, buf, blen);
                next.push_back(nh);
            }
        } else if (htype == DT_AOB) {
            const std::size_t blen = std::min<std::size_t>(rd_sz, sizeof(h.prev_bytes));
            bool ok = false;
            if (scan_type == SC_CHANGED) {
                ok = (h.prev_bytes_len > 0 && std::memcmp(buf, h.prev_bytes, std::min<std::size_t>(blen, h.prev_bytes_len)) != 0);
            } else if (scan_type == SC_UNCHANGED) {
                ok = (h.prev_bytes_len > 0 && std::memcmp(buf, h.prev_bytes, std::min<std::size_t>(blen, h.prev_bytes_len)) == 0);
            } else {
                ok = true;
                for (std::size_t k = 0; k < aob.bytes.size() && k < rd_sz; ++k) {
                    if (aob.mask[k] && buf[k] != aob.bytes[k]) { ok = false; break; }
                }
            }
            if (ok) {
                ScanHit nh = h;
                nh.prev_bytes_len = static_cast<std::uint8_t>(blen);
                std::memcpy(nh.prev_bytes, buf, blen);
                next.push_back(nh);
            }
        } else {
            if (h.xor_mode != 0 && rd_sz == 4) {
                std::uint32_t k = 0;
                if (!read_live_xor_key(io, h.xor_mode, h.xor_key, h.xor_key_addr, k)) continue;
                std::uint32_t raw32 = 0;
                std::memcpy(&raw32, buf, 4);
                raw32 ^= k;
                const std::uint64_t plain_raw = static_cast<std::uint64_t>(raw32);
                if (match_numeric(plain_raw, h.prev_raw, true, htype, scan_type, v1, v2, iv1, iv2)) {
                    ScanHit nh = h;
                    nh.prev_raw = plain_raw;
                    nh.xor_key = k;
                    next.push_back(nh);
                }
            } else {
                const std::uint64_t raw = load_raw_u64(buf, htype);
                if (match_numeric(raw, h.prev_raw, true, htype, scan_type, v1, v2, iv1, iv2)) {
                    ScanHit nh = h;
                    nh.prev_raw = raw;
                    next.push_back(nh);
                }
            }
        }
    }
    g_hits.swap(next);
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t_start).count();
    return build_results_json(io, max_return, elapsed_ms, timed_out, cancelled);
}

std::string mem_refresh_hits_impl(std::size_t max_return) {
    std::lock_guard<std::mutex> lk(g_scan_mu);
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self process memory\"}";
    return build_results_json(io, max_return);
}

std::string mem_undo_impl(std::size_t max_return) {
    std::lock_guard<std::mutex> lk(g_scan_mu);
    if (g_undo_stack.empty()) {
        return "{\"ok\":false,\"error\":\"Nothing to undo\"}";
    }
    ScanGeneration gen = std::move(g_undo_stack.back());
    g_undo_stack.pop_back();
    g_hits = std::move(gen.hits);
    g_unknown_snaps = std::move(gen.snaps);
    g_last_type = gen.last_type;
    g_last_str_len = gen.last_str_len;
    g_snap_active = gen.snap_active;
    g_snap_truncated = gen.snap_truncated;
    g_snap_bytes = gen.snap_bytes;
    SafeMemIo io(false);
    return build_results_json(io, max_return);
}

std::string mem_read_value_ex_impl(std::uint64_t addr, int dtype, std::size_t str_len,
                                   int xor_mode, std::uint32_t xor_key, std::uint64_t xor_key_addr) {
    const std::size_t sz = std::min<std::size_t>(type_size(dtype, str_len > 0 ? str_len : 16), 64);
    SafeMemIo io(false);
    if (!io.valid()) return "<err>";
    std::uint8_t buf[64] = {0};
    const ssize_t got = io.read_at(addr, buf, sz);
    if (got != static_cast<ssize_t>(sz)) return "<unreadable>";
    if (xor_mode != 0 && sz == 4) {
        std::uint32_t k = 0;
        if (!read_live_xor_key(io, xor_mode, xor_key, xor_key_addr, k)) return "<key err>";
        std::uint32_t raw32 = 0;
        std::memcpy(&raw32, buf, 4);
        raw32 ^= k;
        std::memcpy(buf, &raw32, 4);
    }
    return format_value(buf, sz, dtype);
}

bool encode_value_bytes(int dtype, const std::string& val_str, std::uint8_t out_buf[64],
                        std::size_t& out_sz, double& out_num, bool& out_is_num) {
    out_sz = 0;
    out_num = 0.0;
    out_is_num = false;
    std::memset(out_buf, 0, 64);
    if (dtype == DT_STRING) {
        if (val_str.empty()) return false;
        out_sz = std::min<std::size_t>(val_str.size(), 64);
        std::memcpy(out_buf, val_str.data(), out_sz);
        return true;
    }
    if (dtype == DT_AOB) {
        const auto pat = parse_aob(val_str);
        if (pat.bytes.empty()) return false;
        out_sz = std::min<std::size_t>(pat.bytes.size(), 64);
        std::memcpy(out_buf, pat.bytes.data(), out_sz);
        return true;
    }
    double dv = 0.0;
    std::int64_t iv = 0;
    if (!parse_double_strict(val_str.c_str(), dv)) {
        if (!parse_i64_strict(val_str.c_str(), iv)) return false;
        dv = static_cast<double>(iv);
    } else {
        parse_i64_strict(val_str.c_str(), iv);
    }
    out_is_num = true;
    switch (dtype) {
        case DT_INT8:   { std::int8_t v = static_cast<std::int8_t>(std::clamp<std::int64_t>(iv, -128LL, 127LL)); std::memcpy(out_buf, &v, 1); out_sz = 1; out_num = static_cast<double>(v); return true; }
        case DT_INT16:  { std::int16_t v = static_cast<std::int16_t>(std::clamp<std::int64_t>(iv, -32768LL, 32767LL)); std::memcpy(out_buf, &v, 2); out_sz = 2; out_num = static_cast<double>(v); return true; }
        case DT_INT32:  { std::int32_t v = static_cast<std::int32_t>(std::clamp<std::int64_t>(iv, -2147483648LL, 2147483647LL)); std::memcpy(out_buf, &v, 4); out_sz = 4; out_num = static_cast<double>(v); return true; }
        case DT_UINT32: { std::uint32_t v = static_cast<std::uint32_t>(std::clamp<std::int64_t>(iv, 0LL, 4294967295LL)); std::memcpy(out_buf, &v, 4); out_sz = 4; out_num = static_cast<double>(v); return true; }
        case DT_INT64:  { std::memcpy(out_buf, &iv, 8); out_sz = 8; out_num = static_cast<double>(iv); return true; }
        case DT_FLOAT:  { float v = static_cast<float>(dv); std::memcpy(out_buf, &v, 4); out_sz = 4; out_num = static_cast<double>(v); return true; }
        case DT_DOUBLE: { std::memcpy(out_buf, &dv, 8); out_sz = 8; out_num = dv; return true; }
        default: return false;
    }
}

bool encode_numeric_val_bytes(int dtype, double val, std::uint8_t out_buf[64], std::size_t& out_sz) {
    std::memset(out_buf, 0, 64);
    switch (dtype) {
        case DT_INT8:   { std::int8_t v = static_cast<std::int8_t>(std::clamp<double>(std::round(val), -128.0, 127.0)); std::memcpy(out_buf, &v, 1); out_sz = 1; return true; }
        case DT_INT16:  { std::int16_t v = static_cast<std::int16_t>(std::clamp<double>(std::round(val), -32768.0, 32767.0)); std::memcpy(out_buf, &v, 2); out_sz = 2; return true; }
        case DT_INT32:  { std::int32_t v = static_cast<std::int32_t>(std::clamp<double>(std::round(val), -2147483648.0, 2147483647.0)); std::memcpy(out_buf, &v, 4); out_sz = 4; return true; }
        case DT_UINT32: { std::uint32_t v = static_cast<std::uint32_t>(std::clamp<double>(std::round(val), 0.0, 4294967295.0)); std::memcpy(out_buf, &v, 4); out_sz = 4; return true; }
        case DT_INT64:  { std::int64_t v = static_cast<std::int64_t>(std::clamp<double>(std::round(val), -9007199254740991.0, 9007199254740991.0)); std::memcpy(out_buf, &v, 8); out_sz = 8; return true; }
        case DT_FLOAT:  { float v = static_cast<float>(val); std::memcpy(out_buf, &v, 4); out_sz = 4; return true; }
        case DT_DOUBLE: { std::memcpy(out_buf, &val, 8); out_sz = 8; return true; }
        default: return false;
    }
}

bool mem_write_value_ex_impl(std::uint64_t addr, int dtype, const std::string& val_str,
                             int xor_mode, std::uint32_t xor_key, std::uint64_t xor_key_addr) {
    std::uint8_t buf[64] = {0};
    std::size_t sz = 0;
    double dnum = 0.0;
    bool is_num = false;
    if (!encode_value_bytes(dtype, val_str, buf, sz, dnum, is_num)) return false;
    if (!is_addr_in_safe_rw_region(addr, sz)) return false;
    SafeMemIo io(true);
    if (!io.valid()) return false;
    if (xor_mode != 0 && sz == 4) {
        std::uint32_t k = 0;
        if (!read_live_xor_key(io, xor_mode, xor_key, xor_key_addr, k)) return false;
        std::uint32_t raw32 = 0;
        std::memcpy(&raw32, buf, 4);
        raw32 ^= k;
        std::memcpy(buf, &raw32, 4);
    }
    const ssize_t wrote = io.write_at(addr, buf, sz);
    return wrote == static_cast<ssize_t>(sz);
}

std::string mem_write_hex_impl(std::uint64_t addr, const std::string& hex_str) {
    const auto pat = parse_aob(hex_str);
    if (pat.bytes.empty() || pat.bytes.size() > 64) {
        return "{\"ok\":false,\"error\":\"Enter 1..64 hex bytes (e.g. 90 90 ?? 01)\"}";
    }
    const std::size_t sz = pat.bytes.size();
    if (!is_addr_in_safe_rw_region(addr, sz)) {
        return "{\"ok\":false,\"error\":\"Address not in writable region\"}";
    }
    SafeMemIo io(true);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot open self mem\"}";
    std::uint8_t buf[64] = {0};
    if (io.read_at(addr, buf, sz) != static_cast<ssize_t>(sz)) {
        return "{\"ok\":false,\"error\":\"Target memory unreadable\"}";
    }
    std::size_t patched = 0;
    for (std::size_t i = 0; i < sz; ++i) {
        if (pat.mask[i]) {
            buf[i] = pat.bytes[i];
            patched++;
        }
    }
    if (io.write_at(addr, buf, sz) != static_cast<ssize_t>(sz)) {
        return "{\"ok\":false,\"error\":\"Write failed\"}";
    }
    std::uint8_t rb[64] = {0};
    const ssize_t got = io.read_at(addr, rb, sz);
    std::string hex;
    for (ssize_t i = 0; i < got; ++i) {
        char h[4];
        std::snprintf(h, sizeof(h), "%02X", rb[i]);
        hex += h;
    }
    std::string out = "{\"ok\":true,\"wrote\":";
    out += std::to_string(patched);
    out += ",\"hex\":\"";
    out += hex;
    out += "\"}";
    return out;
}

std::string mem_hex_dump_impl(std::uint64_t addr, std::size_t len) {
    const std::size_t clamped = std::min<std::size_t>(std::max<std::size_t>(len, 16), 256);
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self mem\"}";
    std::uint8_t buf[256] = {0};
    const ssize_t got = io.read_at(addr, buf, clamped);
    if (got <= 0) return "{\"ok\":false,\"error\":\"Address unreadable\"}";
    char addr_hex[32];
    std::snprintf(addr_hex, sizeof(addr_hex), "0x%llX", static_cast<unsigned long long>(addr));
    std::string hex;
    hex.reserve(static_cast<std::size_t>(got) * 2);
    for (ssize_t i = 0; i < got; ++i) {
        char h[4];
        std::snprintf(h, sizeof(h), "%02X", buf[i]);
        hex += h;
    }
    std::string out = "{\"ok\":true,\"addr\":\"";
    out += addr_hex;
    out += "\",\"len\":";
    out += std::to_string(got);
    out += ",\"hex\":\"";
    out += hex;
    out += "\"}";
    return out;
}

std::string mem_pointer_scan_impl(std::uint64_t target_addr, std::size_t max_offset, std::size_t max_return, int timeout_ms) {
    if (target_addr == 0) return "{\"ok\":false,\"error\":\"Invalid target address\"}";
    g_cancel_scan.store(false, std::memory_order_release);
    const auto t_start = std::chrono::steady_clock::now();
    const auto t_deadline = t_start + std::chrono::milliseconds(std::clamp(timeout_ms, 1000, 30000));
    const std::size_t clamped_off = std::min<std::size_t>(max_offset, 65536);
    const std::uint64_t lo_target = target_addr >= clamped_off ? (target_addr - clamped_off) : 0;
    const std::size_t cap = std::clamp<std::size_t>(max_return, 1, 200);

    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self process memory\"}";

    const auto regions = parse_self_maps();
    std::vector<std::uint8_t> chunk(CHUNK_BYTES);
    const std::uint64_t chunk_start = reinterpret_cast<std::uint64_t>(chunk.data());
    const std::uint64_t chunk_end = chunk_start + chunk.size();

    struct PtrHit {
        std::uint64_t ptr_addr;
        std::uint64_t points_to;
        std::uint64_t offset;
        std::string region;
    };
    std::vector<PtrHit> found;
    found.reserve(64);
    bool timed_out = false;

    constexpr std::size_t ptr_sz = sizeof(std::uintptr_t);
    for (const auto& reg : regions) {
        if (found.size() >= cap) break;
        if (g_cancel_scan.load(std::memory_order_acquire)) break;
        if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
        std::uint64_t cursor = reg.start;
        while (cursor < reg.end && found.size() < cap) {
            if (g_cancel_scan.load(std::memory_order_acquire)) break;
            if (std::chrono::steady_clock::now() >= t_deadline) { timed_out = true; break; }
            const std::size_t to_read = static_cast<std::size_t>(std::min<std::uint64_t>(CHUNK_BYTES, reg.end - cursor));
            if (to_read < ptr_sz) break;
            const ssize_t got = io.read_at(cursor, chunk.data(), to_read);
            if (got <= 0) {
                cursor += std::min<std::size_t>(PIPE_STEP_BYTES, to_read);
                continue;
            }
            const std::size_t valid = static_cast<std::size_t>(got);
            const std::size_t slots = valid / ptr_sz;
            for (std::size_t i = 0; i < slots && found.size() < cap; ++i) {
                std::uintptr_t pval = 0;
                std::memcpy(&pval, chunk.data() + i * ptr_sz, ptr_sz);
                const std::uint64_t p64 = static_cast<std::uint64_t>(pval);
                if (p64 >= lo_target && p64 <= target_addr) {
                    const std::uint64_t paddr = cursor + i * ptr_sz;
                    if (is_inside_scanner_internal(paddr, ptr_sz, chunk_start, chunk_end)) continue;
                    found.push_back(PtrHit{paddr, p64, target_addr - p64, reg.label.empty() ? "[anon]" : reg.label});
                }
            }
            cursor += valid;
        }
    }

    std::string out = "{\"ok\":true,\"count\":";
    out += std::to_string(found.size());
    out += ",\"timedOut\":";
    out += (timed_out ? "true" : "false");
    out += ",\"items\":[";
    for (std::size_t i = 0; i < found.size(); ++i) {
        if (i > 0) out += ",";
        char pa[32], pt[32], offh[24];
        std::snprintf(pa, sizeof(pa), "0x%llX", static_cast<unsigned long long>(found[i].ptr_addr));
        std::snprintf(pt, sizeof(pt), "0x%llX", static_cast<unsigned long long>(found[i].points_to));
        std::snprintf(offh, sizeof(offh), "+0x%llX", static_cast<unsigned long long>(found[i].offset));
        out += "{\"ptrAddr\":\"";
        out += pa;
        out += "\",\"pointsTo\":\"";
        out += pt;
        out += "\",\"offset\":\"";
        out += offh;
        out += "\",\"region\":\"";
        out += json_escape(found[i].region);
        out += "\"}";
    }
    out += "]}";
    return out;
}

std::string mem_regions_json_impl() {
    const auto regions = parse_self_maps();
    std::string out;
    out.reserve(4096);
    std::uint64_t total_kb = 0;
    for (const auto& r : regions) total_kb += (r.end - r.start) / 1024;
    out += "{\"ok\":true,\"count\":";
    out += std::to_string(regions.size());
    out += ",\"totalKb\":";
    out += std::to_string(total_kb);
    out += ",\"regions\":[";
    const std::size_t limit = std::min<std::size_t>(regions.size(), 80);
    for (std::size_t i = 0; i < limit; ++i) {
        if (i > 0) out += ",";
        const auto& r = regions[i];
        char sbuf[32], ebuf[32];
        std::snprintf(sbuf, sizeof(sbuf), "0x%llX", static_cast<unsigned long long>(r.start));
        std::snprintf(ebuf, sizeof(ebuf), "0x%llX", static_cast<unsigned long long>(r.end));
        out += "{\"start\":\"";
        out += sbuf;
        out += "\",\"end\":\"";
        out += ebuf;
        out += "\",\"kb\":";
        out += std::to_string((r.end - r.start) / 1024);
        out += ",\"perms\":\"";
        out += r.perms;
        out += "\",\"label\":\"";
        out += json_escape(r.label.empty() ? "[anon heap]" : r.label);
        out += "\"}";
    }
    out += "]}";
    return out;
}

struct NativeFreezeEntry {
    std::uint64_t addr = 0;
    int dtype = DT_INT32;
    int rule = FR_EXACT;
    std::uint8_t bytes[64] = {0};
    std::uint8_t bytes_hi[64] = {0};
    std::size_t len = 0;
    double target_num = 0.0;
    double target_max = 0.0;
    double step_delta = 0.0;
    double last_seen_num = 0.0;
    bool has_last_seen = false;
    std::int64_t last_step_ms = 0;
    bool is_numeric = false;
    int xor_mode = 0;
    std::uint32_t xor_key = 0;
    std::uint64_t xor_key_addr = 0;
    std::uint64_t blocked_writes = 0;
    std::uint64_t recent_blocked = 0;
    std::uint64_t rate_per_sec = 0;
    std::int64_t rate_window_ms = 0;
    bool detached = false;
};

std::mutex g_freeze_mu;
std::vector<NativeFreezeEntry> g_freezes;
std::atomic<bool> g_freeze_running{false};
std::atomic<int> g_freeze_strength{1}; // 0=Normal, 1=Strong, 2=Max (Titan)
pthread_t g_freeze_thread{};
bool g_freeze_thread_valid = false;

std::int64_t steady_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

void* native_freeze_worker(void*) {
    SafeMemIo io(true);
    std::uint8_t cur[64];
    std::uint8_t wr[64];
    while (g_freeze_running.load(std::memory_order_acquire)) {
        const std::int64_t now_ms = steady_now_ms();
        bool any_active_fight = false;
        {
            std::lock_guard<std::mutex> lk(g_freeze_mu);
            if (g_freezes.empty()) {
                g_freeze_running.store(false, std::memory_order_release);
                break;
            }
            if (io.valid()) {
                for (auto& f : g_freezes) {
                    if (now_ms - f.rate_window_ms >= 1000) {
                        f.rate_per_sec = f.recent_blocked;
                        f.recent_blocked = 0;
                        f.rate_window_ms = now_ms;
                    }
                    if (f.len == 0 || f.len > sizeof(cur)) continue;
                    const ssize_t got = io.read_at(f.addr, cur, f.len);
                    if (got != static_cast<ssize_t>(f.len)) {
                        f.detached = true;
                        continue;
                    }
                    std::uint32_t live_k = 0;
                    if (f.xor_mode != 0 && f.len == 4) {
                        if (!read_live_xor_key(io, f.xor_mode, f.xor_key, f.xor_key_addr, live_k)) {
                            f.detached = true;
                            continue;
                        }
                        std::uint32_t r32 = 0;
                        std::memcpy(&r32, cur, 4);
                        r32 ^= live_k;
                        std::memcpy(cur, &r32, 4);
                    }
                    f.detached = false;
                    bool should_write = false;
                    std::size_t write_len = f.len;

                    if (f.rule == FR_STEP && f.is_numeric) {
                        if (now_ms - f.last_step_ms >= 1000) {
                            f.last_step_ms = now_ms;
                            const double cur_v = decode_numeric(load_raw_u64(cur, f.dtype), f.dtype);
                            if (std::isfinite(cur_v)) {
                                const double next_v = cur_v + f.step_delta;
                                if (encode_numeric_val_bytes(f.dtype, next_v, wr, write_len)) {
                                    should_write = true;
                                }
                            }
                        }
                    } else if (f.rule == FR_EXACT || !f.is_numeric) {
                        if (std::memcmp(cur, f.bytes, f.len) != 0) {
                            std::memcpy(wr, f.bytes, f.len);
                            should_write = true;
                        }
                    } else {
                        const double cur_v = decode_numeric(load_raw_u64(cur, f.dtype), f.dtype);
                        if (std::isfinite(cur_v)) {
                            if (f.rule == FR_MIN && cur_v < f.target_num) {
                                std::memcpy(wr, f.bytes, f.len);
                                should_write = true;
                            } else if (f.rule == FR_MAX && cur_v > f.target_num) {
                                std::memcpy(wr, f.bytes, f.len);
                                should_write = true;
                            } else if (f.rule == FR_RANGE) {
                                if (cur_v < f.target_num) {
                                    std::memcpy(wr, f.bytes, f.len);
                                    should_write = true;
                                } else if (cur_v > f.target_max) {
                                    std::memcpy(wr, f.bytes_hi, f.len);
                                    should_write = true;
                                }
                            } else if (f.rule == FR_ON_CHANGE_SET) {
                                if (!f.has_last_seen) {
                                    f.last_seen_num = cur_v;
                                    f.has_last_seen = true;
                                } else if (std::fabs(cur_v - f.last_seen_num) > 1e-6 && std::fabs(cur_v - f.target_num) > 1e-6) {
                                    std::memcpy(wr, f.bytes, f.len);
                                    f.last_seen_num = f.target_num;
                                    should_write = true;
                                }
                            } else if (f.rule == FR_ON_DEC_RESTORE) {
                                if (!f.has_last_seen) {
                                    f.last_seen_num = cur_v;
                                    f.has_last_seen = true;
                                } else if (cur_v < f.last_seen_num - 1e-6) {
                                    if (encode_numeric_val_bytes(f.dtype, f.last_seen_num, wr, write_len)) {
                                        should_write = true;
                                    }
                                } else if (cur_v > f.last_seen_num) {
                                    f.last_seen_num = cur_v;
                                }
                            } else if (f.rule == FR_ON_CHANGE_ADD) {
                                if (!f.has_last_seen) {
                                    f.last_seen_num = cur_v;
                                    f.has_last_seen = true;
                                } else if (std::fabs(cur_v - f.last_seen_num) > 1e-6) {
                                    const double nv = cur_v + f.step_delta;
                                    if (encode_numeric_val_bytes(f.dtype, nv, wr, write_len)) {
                                        f.last_seen_num = nv;
                                        should_write = true;
                                    }
                                }
                            } else if (f.rule == FR_ON_CHANGE_MUL) {
                                if (!f.has_last_seen) {
                                    f.last_seen_num = cur_v;
                                    f.has_last_seen = true;
                                } else if (std::fabs(cur_v - f.last_seen_num) > 1e-6) {
                                    const double nv = cur_v * f.step_delta;
                                    if (encode_numeric_val_bytes(f.dtype, nv, wr, write_len)) {
                                        f.last_seen_num = nv;
                                        should_write = true;
                                    }
                                }
                            } else if (f.rule == FR_UNLIMITED_MAX) {
                                if (cur_v < f.target_num) {
                                    std::memcpy(wr, f.bytes, f.len);
                                    should_write = true;
                                }
                            }
                        }
                    }

                    if (should_write) {
                        if (f.xor_mode != 0 && write_len == 4) {
                            std::uint32_t w32 = 0;
                            std::memcpy(&w32, wr, 4);
                            w32 ^= live_k;
                            std::memcpy(wr, &w32, 4);
                        }
                        const ssize_t wrote = io.write_at(f.addr, wr, write_len);
                        if (wrote == static_cast<ssize_t>(write_len)) {
                            f.blocked_writes++;
                            f.recent_blocked++;
                            if (f.rule != FR_STEP) any_active_fight = true;
                            if (g_freeze_strength.load(std::memory_order_relaxed) >= 2 && f.rule == FR_EXACT) {
                                std::uint8_t verify_buf[64];
                                const ssize_t vr = io.read_at(f.addr, verify_buf, write_len);
                                if (vr == static_cast<ssize_t>(write_len) && std::memcmp(verify_buf, wr, write_len) != 0) {
                                    io.write_at(f.addr, wr, write_len);
                                    f.blocked_writes++;
                                    f.recent_blocked++;
                                }
                            }
                        } else {
                            f.detached = true;
                        }
                    }
                    if (f.rate_per_sec >= 4) any_active_fight = true;
                }
            }
        }
        const int st_lvl = g_freeze_strength.load(std::memory_order_relaxed);
        const useconds_t sleep_us = (st_lvl >= 2)
            ? (any_active_fight ? 400 : 1800)
            : (st_lvl == 0 ? (any_active_fight ? 4000 : 8000)
                           : (any_active_fight ? 1500 : 4000));
        ::usleep(sleep_us);
    }
    return nullptr;
}

void stop_freeze_thread_locked(std::unique_lock<std::mutex>& lk) {
    g_freeze_running.store(false, std::memory_order_release);
    if (g_freeze_thread_valid) {
        pthread_t th = g_freeze_thread;
        g_freeze_thread_valid = false;
        lk.unlock();
        ::pthread_join(th, nullptr);
        lk.lock();
    }
}

bool mem_set_freeze_ex_impl(std::uint64_t addr, int dtype, const std::string& val_str, int rule,
                            int xor_mode, std::uint32_t xor_key, std::uint64_t xor_key_addr) {
    std::uint8_t buf[64] = {0};
    std::uint8_t buf_hi[64] = {0};
    std::size_t sz = 0;
    double target_num = 0.0;
    double target_max = 0.0;
    double step_delta = 0.0;
    bool is_num = false;

    const int safe_rule = (rule >= FR_EXACT && rule <= FR_UNLIMITED_MAX) ? rule : FR_EXACT;
    if (safe_rule == FR_RANGE) {
        double lo = 0.0, hi = 0.0;
        if (!parse_range_pair(val_str, lo, hi)) {
            if (!parse_double_strict(val_str.c_str(), lo)) return false;
            hi = lo;
        }
        if (!encode_numeric_val_bytes(dtype, lo, buf, sz)) return false;
        std::size_t sz2 = 0;
        if (!encode_numeric_val_bytes(dtype, hi, buf_hi, sz2) || sz2 != sz) return false;
        target_num = lo;
        target_max = hi;
        is_num = true;
    } else if (safe_rule == FR_STEP || safe_rule == FR_ON_CHANGE_ADD || safe_rule == FR_ON_CHANGE_MUL) {
        if (!parse_double_strict(val_str.c_str(), step_delta)) {
            if (safe_rule == FR_STEP) return false;
            step_delta = (safe_rule == FR_ON_CHANGE_MUL ? 2.0 : 10.0);
        }
        sz = type_size(dtype, 0);
        if (sz == 0 || sz > 8 || dtype == DT_STRING || dtype == DT_AOB) return false;
        is_num = true;
    } else if (safe_rule == FR_UNLIMITED_MAX) {
        target_num = (dtype == DT_INT8) ? 127.0
            : (dtype == DT_INT16 ? 32767.0
            : (dtype == DT_INT32 ? 2147483647.0
            : (dtype == DT_UINT32 ? 4294967295.0
            : (dtype == DT_INT64 ? 9007199254740991.0
            : (dtype == DT_FLOAT ? 1.0e30 : 9007199254740991.0)))));
        if (!encode_numeric_val_bytes(dtype, target_num, buf, sz)) return false;
        is_num = true;
    } else if (safe_rule == FR_ON_DEC_RESTORE) {
        sz = type_size(dtype, 0);
        if (sz == 0 || sz > 8 || dtype == DT_STRING || dtype == DT_AOB) return false;
        parse_double_strict(val_str.c_str(), target_num);
        is_num = true;
    } else {
        if (!encode_value_bytes(dtype, val_str, buf, sz, target_num, is_num)) return false;
    }

    if (!is_addr_in_safe_rw_region(addr, sz)) return false;
    {
        SafeMemIo io(true);
        if (!io.valid()) return false;
        std::uint32_t live_k = 0;
        if (xor_mode != 0 && sz == 4) {
            if (!read_live_xor_key(io, xor_mode, xor_key, xor_key_addr, live_k)) return false;
        }
        if (safe_rule == FR_EXACT || safe_rule == FR_UNLIMITED_MAX || !is_num) {
            std::uint8_t wbuf[64];
            std::memcpy(wbuf, buf, sz);
            if (xor_mode != 0 && sz == 4) {
                std::uint32_t w32 = 0; std::memcpy(&w32, wbuf, 4); w32 ^= live_k; std::memcpy(wbuf, &w32, 4);
            }
            if (io.write_at(addr, wbuf, sz) != static_cast<ssize_t>(sz)) return false;
            std::uint8_t rb[64] = {0};
            if (io.read_at(addr, rb, sz) == static_cast<ssize_t>(sz)) {
                if (xor_mode != 0 && sz == 4) {
                    std::uint32_t r32 = 0; std::memcpy(&r32, rb, 4); r32 ^= live_k; std::memcpy(rb, &r32, 4);
                }
                std::memcpy(buf, rb, sz);
            }
        } else if (safe_rule != FR_STEP) {
            std::uint8_t cur[64] = {0};
            if (io.read_at(addr, cur, sz) != static_cast<ssize_t>(sz)) return false;
            if (xor_mode != 0 && sz == 4) {
                std::uint32_t r32 = 0; std::memcpy(&r32, cur, 4); r32 ^= live_k; std::memcpy(cur, &r32, 4);
            }
            const double cur_v = decode_numeric(load_raw_u64(cur, dtype), dtype);
            std::uint8_t* chosen = nullptr;
            if (safe_rule == FR_MIN && cur_v < target_num) chosen = buf;
            else if (safe_rule == FR_MAX && cur_v > target_num) chosen = buf;
            else if (safe_rule == FR_RANGE && cur_v < target_num) chosen = buf;
            else if (safe_rule == FR_RANGE && cur_v > target_max) chosen = buf_hi;
            if (chosen) {
                std::uint8_t wbuf[64];
                std::memcpy(wbuf, chosen, sz);
                if (xor_mode != 0 && sz == 4) {
                    std::uint32_t w32 = 0; std::memcpy(&w32, wbuf, 4); w32 ^= live_k; std::memcpy(wbuf, &w32, 4);
                }
                if (io.write_at(addr, wbuf, sz) != static_cast<ssize_t>(sz)) return false;
            }
        } else {
            // FR_STEP: verify target is readable now
            std::uint8_t cur[64] = {0};
            if (io.read_at(addr, cur, sz) != static_cast<ssize_t>(sz)) return false;
        }
    }

    const std::int64_t now_ms = steady_now_ms();
    std::unique_lock<std::mutex> lk(g_freeze_mu);
    bool updated = false;
    for (auto& f : g_freezes) {
        if (f.addr == addr) {
            f.dtype = dtype;
            f.rule = safe_rule;
            std::memcpy(f.bytes, buf, sz);
            std::memcpy(f.bytes_hi, buf_hi, sz);
            f.len = sz;
            f.target_num = target_num;
            f.target_max = target_max;
            f.step_delta = step_delta;
            f.has_last_seen = false;
            f.last_step_ms = now_ms;
            f.is_numeric = is_num;
            f.xor_mode = xor_mode;
            f.xor_key = xor_key;
            f.xor_key_addr = xor_key_addr;
            f.detached = false;
            updated = true;
            break;
        }
    }
    if (!updated) {
        NativeFreezeEntry e;
        e.addr = addr;
        e.dtype = dtype;
        e.rule = safe_rule;
        std::memcpy(e.bytes, buf, sz);
        std::memcpy(e.bytes_hi, buf_hi, sz);
        e.len = sz;
        e.target_num = target_num;
        e.target_max = target_max;
        e.step_delta = step_delta;
        e.last_step_ms = now_ms;
        e.is_numeric = is_num;
        e.xor_mode = xor_mode;
        e.xor_key = xor_key;
        e.xor_key_addr = xor_key_addr;
        e.rate_window_ms = now_ms;
        g_freezes.push_back(e);
        const std::uint64_t fl = reinterpret_cast<std::uint64_t>(g_freezes.data());
        g_freeze_buf_start.store(fl, std::memory_order_relaxed);
        g_freeze_buf_end.store(fl + g_freezes.capacity() * sizeof(NativeFreezeEntry), std::memory_order_relaxed);
    }
    if (!g_freeze_running.load(std::memory_order_acquire)) {
        if (g_freeze_thread_valid) {
            pthread_t old_th = g_freeze_thread;
            g_freeze_thread_valid = false;
            lk.unlock();
            ::pthread_join(old_th, nullptr);
            lk.lock();
        }
        if (!g_freezes.empty()) {
            g_freeze_running.store(true, std::memory_order_release);
            if (::pthread_create(&g_freeze_thread, nullptr, native_freeze_worker, nullptr) == 0) {
                g_freeze_thread_valid = true;
            } else {
                g_freeze_running.store(false, std::memory_order_release);
                g_freezes.erase(
                    std::remove_if(g_freezes.begin(), g_freezes.end(),
                                   [addr](const NativeFreezeEntry& e) { return e.addr == addr; }),
                    g_freezes.end()
                );
                return false;
            }
        }
    }
    return g_freeze_running.load(std::memory_order_acquire);
}

void mem_unfreeze_impl(std::uint64_t addr) {
    std::unique_lock<std::mutex> lk(g_freeze_mu);
    g_freezes.erase(
        std::remove_if(g_freezes.begin(), g_freezes.end(),
                       [addr](const NativeFreezeEntry& e) { return e.addr == addr; }),
        g_freezes.end()
    );
    if (g_freezes.empty()) {
        stop_freeze_thread_locked(lk);
    }
}

void mem_unfreeze_all_impl() {
    std::unique_lock<std::mutex> lk(g_freeze_mu);
    g_freezes.clear();
    stop_freeze_thread_locked(lk);
}

std::string mem_freeze_stats_json_impl() {
    std::lock_guard<std::mutex> lk(g_freeze_mu);
    std::string out = "{\"ok\":true,\"alive\":";
    out += (g_freeze_running.load(std::memory_order_acquire) ? "true" : "false");
    out += ",\"stats\":{";
    bool first = true;
    for (const auto& f : g_freezes) {
        if (!first) out += ",";
        first = false;
        char addr_hex[32];
        std::snprintf(addr_hex, sizeof(addr_hex), "0x%llX", static_cast<unsigned long long>(f.addr));
        const char* st = f.detached ? "detached"
            : (f.rate_per_sec >= 10 ? "fighting"
            : (f.blocked_writes > 0 ? "enforcing" : "locked"));
        const char* rstr = f.rule == FR_MIN ? "min"
            : (f.rule == FR_MAX ? "max"
            : (f.rule == FR_RANGE ? "range"
            : (f.rule == FR_STEP ? "step"
            : (f.rule == FR_ON_CHANGE_SET ? "on_change_set"
            : (f.rule == FR_ON_DEC_RESTORE ? "on_dec_restore"
            : (f.rule == FR_ON_CHANGE_ADD ? "on_change_add"
            : (f.rule == FR_ON_CHANGE_MUL ? "on_change_mul"
            : (f.rule == FR_UNLIMITED_MAX ? "unlimited_max" : "eq"))))))));
        out += "\"";
        out += addr_hex;
        out += "\":{\"mode\":\"";
        const int st_cur = g_freeze_strength.load(std::memory_order_relaxed);
        const char* hz_tag = (st_cur >= 2) ? (f.xor_mode != 0 ? "MAX-XOR" : "2500Hz")
            : (st_cur == 0 ? (f.xor_mode != 0 ? "250Hz-XOR" : "250Hz")
                           : (f.xor_mode != 0 ? "500Hz-XOR" : "500Hz"));
        out += hz_tag;
        out += "\",\"rule\":\"";
        out += rstr;
        out += "\",\"blocked\":";
        out += std::to_string(f.blocked_writes);
        out += ",\"rate\":";
        out += std::to_string(f.rate_per_sec);
        out += ",\"status\":\"";
        out += st;
        out += "\"}";
    }
    out += "}}";
    return out;
}

std::string mem_peek_struct_impl(std::uint64_t base_addr, std::size_t radius) {
    const std::size_t rad = std::min<std::size_t>(std::max<std::size_t>(radius, 32), 256) & ~std::size_t(3);
    const std::uint64_t start = base_addr >= rad ? ((base_addr - rad) & ~std::uint64_t(3)) : 0;
    const std::size_t total_len = static_cast<std::size_t>((base_addr - start) + rad);
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self mem\"}";
    std::vector<std::uint8_t> buf(total_len, 0);
    const ssize_t got = io.read_at(start, buf.data(), total_len);
    if (got < 4) return "{\"ok\":false,\"error\":\"Struct memory unreadable\"}";
    std::string out = "{\"ok\":true,\"fields\":[";
    bool first = true;
    const std::size_t slots = static_cast<std::size_t>(got) / 4;
    for (std::size_t i = 0; i < slots; ++i) {
        const std::uint64_t a = start + i * 4;
        const std::int64_t delta = static_cast<std::int64_t>(a) - static_cast<std::int64_t>(base_addr);
        std::int32_t iv = 0;
        std::uint32_t uv = 0;
        float fv = 0.0f;
        std::memcpy(&iv, buf.data() + i * 4, 4);
        std::memcpy(&uv, buf.data() + i * 4, 4);
        std::memcpy(&fv, buf.data() + i * 4, 4);
        const bool is_center = (delta == 0);
        const bool looks_f = std::isfinite(fv) && std::fabs(fv) >= 0.001f && std::fabs(fv) <= 1e7f && (std::abs(iv) > 100000000);
        if (!is_center && iv == 0) continue;
        if (!first) out += ",";
        first = false;
        char addr_hex[32];
        std::snprintf(addr_hex, sizeof(addr_hex), "0x%llX", static_cast<unsigned long long>(a));
        char delta_str[24];
        std::snprintf(delta_str, sizeof(delta_str), "%s0x%X", delta < 0 ? "-" : "+", static_cast<unsigned>(delta < 0 ? -delta : delta));
        char f_str[48];
        if (std::isfinite(fv) && std::fabs(fv) <= 1e9f) std::snprintf(f_str, sizeof(f_str), "%.3f", fv);
        else std::snprintf(f_str, sizeof(f_str), "NaN");
        out += "{\"addr\":\"";
        out += addr_hex;
        out += "\",\"delta\":\"";
        out += delta_str;
        out += "\",\"type\":\"";
        out += (looks_f ? "f32" : "i32");
        out += "\",\"val\":\"";
        out += (looks_f ? f_str : std::to_string(iv));
        out += "\",\"i32\":\"";
        out += std::to_string(iv);
        out += "\",\"u32\":\"";
        out += std::to_string(uv);
        out += "\",\"f32\":\"";
        out += f_str;
        out += "\"}";
    }
    out += "]}";
    return out;
}

std::string mem_find_mirrors_impl(std::uint64_t target_addr, int dtype, std::size_t max_ret, int timeout_ms) {
    const std::size_t sz = type_size(dtype, 0);
    if (sz == 0 || sz > 8) return "{\"ok\":false,\"error\":\"Mirrors supports numeric types\"}";
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self mem\"}";
    std::uint8_t want[8] = {0};
    if (io.read_at(target_addr, want, sz) != static_cast<ssize_t>(sz)) {
        return "{\"ok\":false,\"error\":\"Target address unreadable\"}";
    }
    const auto regions = parse_self_maps();
    const std::int64_t deadline = steady_now_ms() + std::clamp<int>(timeout_ms, 1000, 20000);
    std::vector<std::uint8_t> chunk(CHUNK_BYTES);
    const std::uint64_t chunk_lo = reinterpret_cast<std::uint64_t>(chunk.data());
    const std::uint64_t chunk_hi = chunk_lo + chunk.capacity();
    const std::uint64_t want_lo = reinterpret_cast<std::uint64_t>(want);
    const std::uint64_t want_hi = want_lo + sizeof(want);
    std::string out = "{\"ok\":true,\"items\":[";
    std::size_t found = 0;
    const std::string cur_val_str = format_value(want, sz, dtype);
    for (const auto& reg : regions) {
        if (found >= max_ret || steady_now_ms() >= deadline) break;
        const std::uint64_t reg_len = reg.end - reg.start;
        if (reg_len < sz || reg_len > MAX_REGION_BYTES) continue;
        std::uint64_t off = 0;
        while (off + sz <= reg_len && found < max_ret && steady_now_ms() < deadline) {
            const std::size_t to_read = static_cast<std::size_t>(std::min<std::uint64_t>(CHUNK_BYTES, reg_len - off));
            const ssize_t got = io.read_at(reg.start + off, chunk.data(), to_read);
            if (got >= static_cast<ssize_t>(sz)) {
                const std::size_t step = (sz >= 4) ? 4 : sz;
                for (std::size_t i = 0; i + sz <= static_cast<std::size_t>(got) && found < max_ret; i += step) {
                    const std::uint64_t a = reg.start + off + i;
                    if (a == target_addr) continue;
                    if (range_overlaps(a, sz, want_lo, want_hi)) continue;
                    if (is_inside_scanner_internal(a, sz, chunk_lo, chunk_hi)) continue;
                    if (std::memcmp(chunk.data() + i, want, sz) == 0) {
                        if (found > 0) out += ",";
                        found++;
                        char hx[32];
                        std::snprintf(hx, sizeof(hx), "0x%llX", static_cast<unsigned long long>(a));
                        out += "{\"addr\":\"";
                        out += hx;
                        out += "\",\"val\":\"";
                        out += cur_val_str;
                        out += "\",\"type\":\"";
                        out += type_code_str(dtype);
                        out += "\",\"kind\":\"native\",\"role\":\"game\"}";
                    }
                }
            }
            off += to_read;
        }
    }
    out += "],\"count\":";
    out += std::to_string(found);
    out += "}";
    return out;
}

std::string mem_make_signature_impl(std::uint64_t target_addr) {
    if (target_addr < 16) return "{\"ok\":false,\"error\":\"Invalid address\"}";
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self mem\"}";
    std::uint8_t buf[32] = {0};
    const std::uint64_t base = target_addr - 12;
    if (io.read_at(base, buf, 32) != 32) {
        return "{\"ok\":false,\"error\":\"Signature bytes unreadable\"}";
    }
    std::string sig;
    sig.reserve(96);
    int fixed_cnt = 0;
    for (int i = 0; i < 32; ++i) {
        if (i > 0) sig.push_back(' ');
        if (i >= 12 && i < 16) {
            sig += "??";
        } else {
            char hx[4];
            std::snprintf(hx, sizeof(hx), "%02X", static_cast<unsigned>(buf[i]));
            sig += hx;
            fixed_cnt++;
        }
    }
    std::string out = "{\"ok\":true,\"sig\":\"";
    out += sig;
    out += "\",\"valueOffset\":12,\"fixedBytes\":";
    out += std::to_string(fixed_cnt);
    out += "}";
    return out;
}

std::string mem_resolve_signature_impl(const std::string& sig_hex, int value_offset, int timeout_ms) {
    AobPattern pat = parse_aob(sig_hex);
    if (pat.bytes.size() < 8) {
        return "{\"ok\":false,\"error\":\"Invalid AOB signature\"}";
    }
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self mem\"}";
    const auto regions = parse_self_maps();
    const std::int64_t deadline = steady_now_ms() + std::clamp<int>(timeout_ms, 1000, 15000);
    std::vector<std::uint8_t> chunk(CHUNK_BYTES);
    const std::uint64_t chunk_lo = reinterpret_cast<std::uint64_t>(chunk.data());
    const std::uint64_t chunk_hi = chunk_lo + chunk.capacity();
    const std::size_t plen = pat.bytes.size();
    std::vector<std::uint64_t> found;
    found.reserve(16);
    for (const auto& reg : regions) {
        if (found.size() >= 16 || steady_now_ms() >= deadline) break;
        const std::uint64_t reg_len = reg.end - reg.start;
        if (reg_len < plen || reg_len > MAX_REGION_BYTES) continue;
        std::uint64_t off = 0;
        while (off + plen <= reg_len && found.size() < 16 && steady_now_ms() < deadline) {
            const std::size_t to_read = static_cast<std::size_t>(std::min<std::uint64_t>(CHUNK_BYTES, reg_len - off));
            const ssize_t got = io.read_at(reg.start + off, chunk.data(), to_read);
            if (got >= static_cast<ssize_t>(plen)) {
                const std::size_t last = static_cast<std::size_t>(got) - plen;
                for (std::size_t i = 0; i <= last && found.size() < 16; ++i) {
                    bool ok = true;
                    for (std::size_t k = 0; k < plen; ++k) {
                        if (pat.mask[k] && chunk[i + k] != pat.bytes[k]) { ok = false; break; }
                    }
                    if (ok) {
                        const std::uint64_t a = reg.start + off + i + static_cast<std::uint64_t>(std::max(0, value_offset));
                        if (!is_inside_scanner_internal(a, 4, chunk_lo, chunk_hi)) {
                            found.push_back(a);
                        }
                    }
                }
            }
            const std::size_t step = (to_read > plen) ? (to_read - plen + 1) : to_read;
            off += step;
        }
    }
    std::string out = "{\"ok\":";
    out += (found.empty() ? "false" : "true");
    out += ",\"count\":";
    out += std::to_string(found.size());
    out += ",\"addrs\":[";
    for (std::size_t i = 0; i < found.size(); ++i) {
        if (i > 0) out += ",";
        char hx[32];
        std::snprintf(hx, sizeof(hx), "\"0x%llX\"", static_cast<unsigned long long>(found[i]));
        out += hx;
    }
    out += "]}";
    return out;
}

std::string mem_test_authority_impl(std::uint64_t addr, int dtype) {
    const std::size_t sz = type_size(dtype, 0);
    if (sz == 0 || sz > 8) return "{\"ok\":false,\"error\":\"Authority test requires numeric type\"}";
    SafeMemIo io(true);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self mem\"}";
    std::uint8_t orig[8] = {0};
    if (io.read_at(addr, orig, sz) != static_cast<ssize_t>(sz)) {
        return "{\"ok\":false,\"error\":\"Address unreadable\"}";
    }
    const double cur_v = decode_numeric(load_raw_u64(orig, dtype), dtype);
    if (!std::isfinite(cur_v)) return "{\"ok\":false,\"error\":\"Non-finite value\"}";
    std::uint8_t probe[64] = {0};
    std::size_t psz = sz;
    const double test_v = cur_v + 1.0;
    if (!encode_numeric_val_bytes(dtype, test_v, probe, psz)) {
        return "{\"ok\":false,\"error\":\"Cannot encode probe\"}";
    }
    const bool writable = (io.write_at(addr, probe, sz) == static_cast<ssize_t>(sz));
    std::uint8_t rb[8] = {0};
    bool accepted = false;
    bool overwritten = false;
    if (writable) {
        ::usleep(12000);
        if (io.read_at(addr, rb, sz) == static_cast<ssize_t>(sz)) {
            accepted = (std::memcmp(rb, probe, sz) == 0);
            overwritten = (std::memcmp(rb, orig, sz) == 0);
        }
        // Always restore original bytes cleanly!
        io.write_at(addr, orig, sz);
    }
    const int score = !writable ? 5 : (accepted ? 92 : (overwritten ? 55 : 70));
    const char* verdict = !writable ? "read-only" : (accepted ? "writable-stable" : (overwritten ? "externally-restored" : "active-dynamic"));
    std::string out = "{\"ok\":true,\"writable\":";
    out += (writable ? "true" : "false");
    out += ",\"accepted\":";
    out += (accepted ? "true" : "false");
    out += ",\"externallyRestored\":";
    out += (overwritten ? "true" : "false");
    out += ",\"score\":";
    out += std::to_string(score);
    out += ",\"verdict\":\"";
    out += verdict;
    out += "\"}";
    return out;
}

std::string jstr_to_std(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    if (!c) return {};
    std::string s(c, static_cast<std::size_t>(env->GetStringUTFLength(js)));
    env->ReleaseStringUTFChars(js, c);
    return s;
}

} // namespace


std::string mem_inspect_types_impl(std::uint64_t addr, int cur_dtype) {
    SafeMemIo io(false);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot access self process memory\"}";
    std::uint8_t b[8] = {0};
    const ssize_t got = io.read_at(addr, b, 8);
    if (got < 1) return "{\"ok\":false,\"error\":\"Address unreadable\"}";
    char hex_buf[32] = {0};
    std::string hex_str;
    for (ssize_t i = 0; i < got; ++i) {
        if (i > 0) hex_str.push_back(' ');
        std::snprintf(hex_buf, sizeof(hex_buf), "%02X", static_cast<unsigned>(b[i]));
        hex_str += hex_buf;
    }
    std::int8_t v_i8 = static_cast<std::int8_t>(b[0]);
    std::uint8_t v_u8 = b[0];
    std::int16_t v_i16 = 0; std::uint16_t v_u16 = 0;
    std::int32_t v_i32 = 0; std::uint32_t v_u32 = 0;
    std::int64_t v_i64 = 0; std::uint64_t v_u64 = 0;
    float v_f32 = 0.0f; double v_f64 = 0.0;
    if (got >= 2) { std::memcpy(&v_i16, b, 2); std::memcpy(&v_u16, b, 2); }
    if (got >= 4) { std::memcpy(&v_i32, b, 4); std::memcpy(&v_u32, b, 4); std::memcpy(&v_f32, b, 4); }
    if (got >= 8) { std::memcpy(&v_i64, b, 8); std::memcpy(&v_u64, b, 8); std::memcpy(&v_f64, b, 8); }
    char f32s[48] = {0}, f64s[48] = {0}, x10s[48] = {0}, x100s[48] = {0}, x1000s[48] = {0};
    if (got >= 4) {
        if (std::isfinite(v_f32)) std::snprintf(f32s, sizeof(f32s), "%.6g", static_cast<double>(v_f32));
        else std::snprintf(f32s, sizeof(f32s), "NaN");
        std::snprintf(x10s, sizeof(x10s), "%.6g", static_cast<double>(v_i32) / 10.0);
        std::snprintf(x100s, sizeof(x100s), "%.6g", static_cast<double>(v_i32) / 100.0);
        std::snprintf(x1000s, sizeof(x1000s), "%.6g", static_cast<double>(v_i32) / 1000.0);
    }
    if (got >= 8) {
        if (std::isfinite(v_f64)) std::snprintf(f64s, sizeof(f64s), "%.6g", v_f64);
        else std::snprintf(f64s, sizeof(f64s), "NaN");
    }
    std::string diag, sug;
    if (cur_dtype == DT_INT32 && got >= 4 && v_i32 < -1000000) {
        diag = "Signed i32 overflow! Unsigned u32 = " + std::to_string(v_u32);
        sug = "u32";
    } else if (cur_dtype == DT_INT32 && got >= 4 && std::abs(v_i32) > 100000000 && std::isfinite(v_f32) && std::fabs(v_f32) >= 0.001f && std::fabs(v_f32) <= 1e7f) {
        diag = std::string("Looks like Float (f32 = ") + f32s + ") read as i32";
        sug = "f32";
    }
    std::string out = "{\"ok\":true,\"hex\":\"" + hex_str + "\"";
    out += ",\"i8\":\"" + std::to_string(static_cast<int>(v_i8)) + "\"";
    out += ",\"u8\":\"" + std::to_string(static_cast<unsigned>(v_u8)) + "\"";
    out += ",\"i16\":\"" + (got >= 2 ? std::to_string(v_i16) : std::string()) + "\"";
    out += ",\"u16\":\"" + (got >= 2 ? std::to_string(v_u16) : std::string()) + "\"";
    out += ",\"i32\":\"" + (got >= 4 ? std::to_string(v_i32) : std::string()) + "\"";
    out += ",\"u32\":\"" + (got >= 4 ? std::to_string(v_u32) : std::string()) + "\"";
    out += ",\"i64\":\"" + (got >= 8 ? std::to_string(v_i64) : std::string()) + "\"";
    out += ",\"u64\":\"" + (got >= 8 ? std::to_string(v_u64) : std::string()) + "\"";
    out += ",\"f32\":\"" + std::string(f32s) + "\"";
    out += ",\"f64\":\"" + std::string(f64s) + "\"";
    out += ",\"x10\":\"" + std::string(x10s) + "\"";
    out += ",\"x100\":\"" + std::string(x100s) + "\"";
    out += ",\"x1000\":\"" + std::string(x1000s) + "\"";
    std::string split64_str;
    if (got >= 8) {
        std::uint32_t lo32 = 0, hi32 = 0;
        std::memcpy(&lo32, b, 4);
        std::memcpy(&hi32, b + 4, 4);
        split64_str = "hi=" + std::to_string(hi32) + " | lo=" + std::to_string(lo32);
        if (diag.empty() && (cur_dtype == DT_INT32 || cur_dtype == DT_UINT32) && hi32 > 0 && hi32 < 65536) {
            diag = "Possible 64-bit integer (u64 = " + std::to_string(v_u64) + ", hi32=" + std::to_string(hi32) + ")";
            sug = "i64";
        }
    }
    out += ",\"split64\":\"" + split64_str + "\"";
    out += ",\"diagnosis\":\"" + diag + "\",\"suggestedType\":\"" + sug + "\"}";
    return out;
}

std::string mem_analyze_protection_impl(std::uint64_t addr, int dtype) {
    const std::size_t sz = type_size(dtype, 0);
    if (sz == 0 || sz > 8) return "{\"ok\":false,\"error\":\"Protection analyzer requires numeric type\"}";
    std::string perms = "----";
    bool in_maps = false;
    {
        FILE* fp = std::fopen("/proc/self/maps", "r");
        if (fp) {
            char line[512];
            while (std::fgets(line, sizeof(line), fp)) {
                unsigned long long a = 0, b = 0;
                char pbuf[8] = {0};
                if (std::sscanf(line, "%llx-%llx %4s", &a, &b, pbuf) == 3) {
                    if (addr >= static_cast<std::uint64_t>(a) && addr + sz <= static_cast<std::uint64_t>(b)) {
                        in_maps = true;
                        perms = pbuf;
                        break;
                    }
                }
            }
            std::fclose(fp);
        }
    }
    const bool perm_w = perms.size() >= 2 && perms[1] == 'w';
    const bool perm_r = perms.size() >= 1 && perms[0] == 'r';
    if (!in_maps || !perm_r) {
        return "{\"ok\":true,\"perms\":\"" + perms + "\",\"writable\":false,\"readOnly\":true,\"restoredImmediately\":false,\"restoredDelayed\":false,\"nearbyMirrors\":0,\"verdict\":\"unmapped-or-protected\",\"explanation\":\"Address is outside readable process mappings\"}";
    }
    SafeMemIo io(perm_w);
    if (!io.valid()) return "{\"ok\":false,\"error\":\"Cannot open self memory\"}";
    std::uint8_t orig[8] = {0};
    if (io.read_at(addr, orig, sz) != static_cast<ssize_t>(sz)) {
        return "{\"ok\":true,\"perms\":\"" + perms + "\",\"writable\":false,\"readOnly\":true,\"restoredImmediately\":false,\"restoredDelayed\":false,\"nearbyMirrors\":0,\"verdict\":\"unreadable\",\"explanation\":\"Memory page refused read\"}";
    }
    int nearby_mirrors = 0;
    const std::uint64_t win_start = (addr >= 128) ? (addr - 128) : 0;
    std::uint8_t win_buf[264] = {0};
    const ssize_t wgot = io.read_at(win_start, win_buf, 256);
    if (wgot >= static_cast<ssize_t>(sz)) {
        const std::size_t step = (sz >= 4) ? 4 : sz;
        for (std::size_t i = 0; i + sz <= static_cast<std::size_t>(wgot); i += step) {
            if (win_start + i == addr) continue;
            if (std::memcmp(win_buf + i, orig, sz) == 0) nearby_mirrors++;
        }
    }
    if (!perm_w) {
        return "{\"ok\":true,\"perms\":\"" + perms + "\",\"writable\":false,\"readOnly\":true,\"restoredImmediately\":false,\"restoredDelayed\":false,\"nearbyMirrors\":" + std::to_string(nearby_mirrors) + ",\"verdict\":\"read-only\",\"explanation\":\"Mapping is read-only (" + perms + "); Fable respects OS memory permissions\"}";
    }
    const double cur_v = decode_numeric(load_raw_u64(orig, dtype), dtype);
    std::uint8_t probe[64] = {0};
    std::size_t psz = sz;
    const bool encoded = std::isfinite(cur_v) && encode_numeric_val_bytes(dtype, cur_v + 1.0, probe, psz);
    bool writable = false;
    bool restored_imm = false;
    bool restored_del = false;
    if (encoded) {
        writable = (io.write_at(addr, probe, sz) == static_cast<ssize_t>(sz));
        if (writable) {
            std::uint8_t rb1[8] = {0}, rb2[8] = {0};
            if (io.read_at(addr, rb1, sz) == static_cast<ssize_t>(sz) && std::memcmp(rb1, orig, sz) == 0) {
                restored_imm = true;
            }
            ::usleep(25000);
            if (!restored_imm && io.read_at(addr, rb2, sz) == static_cast<ssize_t>(sz) && std::memcmp(rb2, orig, sz) == 0) {
                restored_del = true;
            }
            io.write_at(addr, orig, sz);
        }
    }
    std::string verdict = "writable-stable";
    std::string expl = "Writable private memory; value stayed modified cleanly";
    if (!writable) {
        verdict = "read-only";
        expl = "OS refused write to this mapping";
    } else if (restored_imm) {
        verdict = "rewritten-immediately";
        expl = "Another thread or loop overwrote the value immediately";
    } else if (restored_del) {
        verdict = "rewritten-by-local-loop";
        expl = "Value reverted within 25ms — local update loop or mirror sync suspected";
    } else if (nearby_mirrors > 0) {
        verdict = "linked-copies-suspected";
        expl = "Writable, with " + std::to_string(nearby_mirrors) + " nearby identical copy/copies in +-128B struct";
    }
    std::string out = "{\"ok\":true,\"perms\":\"" + perms + "\"";
    out += ",\"writable\":" + std::string(writable ? "true" : "false");
    out += ",\"readOnly\":" + std::string((!writable) ? "true" : "false");
    out += ",\"restoredImmediately\":" + std::string(restored_imm ? "true" : "false");
    out += ",\"restoredDelayed\":" + std::string(restored_del ? "true" : "false");
    out += ",\"nearbyMirrors\":" + std::to_string(nearby_mirrors);
    out += ",\"verdict\":\"" + verdict + "\"";
    out += ",\"explanation\":\"" + expl + "\"}";
    return out;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeFirstScan(
        JNIEnv* env, jclass,
        jint dtype, jint scanType, jstring val1, jstring val2, jint maxReturn, jint timeoutMs) {
    const std::string s1 = jstr_to_std(env, val1);
    const std::string s2 = jstr_to_std(env, val2);
    const std::size_t max_ret = static_cast<std::size_t>(std::clamp<int>(maxReturn, 1, 500));
    const std::string res = mem_first_scan_impl(dtype, scanType, s1, s2, max_ret, timeoutMs);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeNextScan(
        JNIEnv* env, jclass,
        jint scanType, jstring val1, jstring val2, jint maxReturn, jint timeoutMs) {
    const std::string s1 = jstr_to_std(env, val1);
    const std::string s2 = jstr_to_std(env, val2);
    const std::size_t max_ret = static_cast<std::size_t>(std::clamp<int>(maxReturn, 1, 500));
    const std::string res = mem_next_scan_impl(scanType, s1, s2, max_ret, timeoutMs);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeCancelScan(JNIEnv*, jclass) {
    g_cancel_scan.store(true, std::memory_order_release);
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeRefreshHits(
        JNIEnv* env, jclass, jint maxReturn) {
    const std::size_t max_ret = static_cast<std::size_t>(std::clamp<int>(maxReturn, 1, 500));
    const std::string res = mem_refresh_hits_impl(max_ret);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeUndoScan(
        JNIEnv* env, jclass, jint maxReturn) {
    const std::size_t max_ret = static_cast<std::size_t>(std::clamp<int>(maxReturn, 1, 500));
    const std::string res = mem_undo_impl(max_ret);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeResetScan(JNIEnv*, jclass) {
    g_cancel_scan.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lk(g_scan_mu);
    g_hits.clear();
    g_unknown_snaps.clear();
    g_undo_stack.clear();
    g_snap_active = false;
    g_snap_truncated = false;
    g_snap_bytes = 0;
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeReadValue(
        JNIEnv* env, jclass, jlong addr, jint dtype, jint strLen) {
    const std::string res = mem_read_value_ex_impl(
        static_cast<std::uint64_t>(addr),
        dtype,
        static_cast<std::size_t>(std::max<int>(strLen, 1)),
        0, 0, 0
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeReadValueEx(
        JNIEnv* env, jclass, jlong addr, jint dtype, jint strLen,
        jint xorMode, jlong xorKey, jlong xorKeyAddr) {
    const std::string res = mem_read_value_ex_impl(
        static_cast<std::uint64_t>(addr),
        dtype,
        static_cast<std::size_t>(std::max<int>(strLen, 1)),
        xorMode,
        static_cast<std::uint32_t>(xorKey),
        static_cast<std::uint64_t>(xorKeyAddr)
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeWriteValue(
        JNIEnv* env, jclass, jlong addr, jint dtype, jstring valStr) {
    const std::string s = jstr_to_std(env, valStr);
    const bool ok = mem_write_value_ex_impl(static_cast<std::uint64_t>(addr), dtype, s, 0, 0, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeWriteValueEx(
        JNIEnv* env, jclass, jlong addr, jint dtype, jstring valStr,
        jint xorMode, jlong xorKey, jlong xorKeyAddr) {
    const std::string s = jstr_to_std(env, valStr);
    const bool ok = mem_write_value_ex_impl(
        static_cast<std::uint64_t>(addr),
        dtype,
        s,
        xorMode,
        static_cast<std::uint32_t>(xorKey),
        static_cast<std::uint64_t>(xorKeyAddr)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeWriteHex(
        JNIEnv* env, jclass, jlong addr, jstring hexStr) {
    const std::string s = jstr_to_std(env, hexStr);
    const std::string res = mem_write_hex_impl(static_cast<std::uint64_t>(addr), s);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeHexDump(
        JNIEnv* env, jclass, jlong addr, jint len) {
    const std::string res = mem_hex_dump_impl(
        static_cast<std::uint64_t>(addr),
        static_cast<std::size_t>(std::max<int>(len, 16))
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativePointerScan(
        JNIEnv* env, jclass, jlong targetAddr, jint maxOffset, jint maxReturn, jint timeoutMs) {
    const std::string res = mem_pointer_scan_impl(
        static_cast<std::uint64_t>(targetAddr),
        static_cast<std::size_t>(std::max<int>(maxOffset, 0)),
        static_cast<std::size_t>(std::clamp<int>(maxReturn, 1, 200)),
        timeoutMs
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeListRegions(JNIEnv* env, jclass) {
    const std::string res = mem_regions_json_impl();
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeSetFreeze(
        JNIEnv* env, jclass, jlong addr, jint dtype, jstring valStr) {
    const std::string s = jstr_to_std(env, valStr);
    const bool ok = mem_set_freeze_ex_impl(static_cast<std::uint64_t>(addr), dtype, s, FR_EXACT, 0, 0, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeSetFreezeRule(
        JNIEnv* env, jclass, jlong addr, jint dtype, jstring valStr, jint rule) {
    const std::string s = jstr_to_std(env, valStr);
    const bool ok = mem_set_freeze_ex_impl(static_cast<std::uint64_t>(addr), dtype, s, rule, 0, 0, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeSetFreezeEx(
        JNIEnv* env, jclass, jlong addr, jint dtype, jstring valStr, jint rule,
        jint xorMode, jlong xorKey, jlong xorKeyAddr) {
    const std::string s = jstr_to_std(env, valStr);
    const bool ok = mem_set_freeze_ex_impl(
        static_cast<std::uint64_t>(addr),
        dtype,
        s,
        rule,
        xorMode,
        static_cast<std::uint32_t>(xorKey),
        static_cast<std::uint64_t>(xorKeyAddr)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativePeekStruct(
        JNIEnv* env, jclass, jlong addr, jint radius) {
    const std::string res = mem_peek_struct_impl(
        static_cast<std::uint64_t>(addr),
        static_cast<std::size_t>(std::max<int>(radius, 32))
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeUnfreeze(JNIEnv*, jclass, jlong addr) {
    mem_unfreeze_impl(static_cast<std::uint64_t>(addr));
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeUnfreezeAll(JNIEnv*, jclass) {
    mem_unfreeze_all_impl();
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeGetFreezeStats(JNIEnv* env, jclass) {
    const std::string res = mem_freeze_stats_json_impl();
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeSetFreezeStrength(JNIEnv*, jclass, jint level) {
    g_freeze_strength.store(std::clamp<int>(level, 0, 2), std::memory_order_release);
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeFindMirrors(
        JNIEnv* env, jclass, jlong targetAddr, jint dtype, jint maxReturn, jint timeoutMs) {
    const std::string res = mem_find_mirrors_impl(
        static_cast<std::uint64_t>(targetAddr),
        dtype,
        static_cast<std::size_t>(std::clamp<int>(maxReturn, 1, 100)),
        timeoutMs
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeMakeSignature(JNIEnv* env, jclass, jlong addr) {
    const std::string res = mem_make_signature_impl(static_cast<std::uint64_t>(addr));
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeResolveSignature(
        JNIEnv* env, jclass, jstring sigHex, jint valueOffset, jint timeoutMs) {
    const std::string s = jstr_to_std(env, sigHex);
    const std::string res = mem_resolve_signature_impl(s, valueOffset, timeoutMs);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeTestAuthority(JNIEnv* env, jclass, jlong addr, jint dtype) {
    const std::string res = mem_test_authority_impl(static_cast<std::uint64_t>(addr), dtype);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeInspectTypes(JNIEnv* env, jclass, jlong addr, jint dtype) {
    const std::string res = mem_inspect_types_impl(static_cast<std::uint64_t>(addr), dtype);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeAnalyzeProtection(JNIEnv* env, jclass, jlong addr, jint dtype) {
    const std::string res = mem_analyze_protection_impl(static_cast<std::uint64_t>(addr), dtype);
    return env->NewStringUTF(res.c_str());
}

} // extern "C"
