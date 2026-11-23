#include <jni.h>
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

namespace {

// Data types matching Cheat Engine
constexpr int DT_INT8   = 0;
constexpr int DT_INT16  = 1;
constexpr int DT_INT32  = 2;
constexpr int DT_INT64  = 3;
constexpr int DT_FLOAT  = 4;
constexpr int DT_DOUBLE = 5;
constexpr int DT_STRING = 6;
constexpr int DT_AOB    = 7;

// Scan comparison types matching Cheat Engine
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

constexpr std::size_t MAX_RESULTS_CAP = 200000;
constexpr std::size_t MAX_UNKNOWN_CAP = 120000;
constexpr std::size_t CHUNK_BYTES     = 256 * 1024;
constexpr std::size_t MAX_REGION_BYTES = 64 * 1024 * 1024; // skip giant 64MB+ mmap pools for speed

struct MemRegion {
    std::uint64_t start = 0;
    std::uint64_t end = 0;
    char perms[8] = {0};
    std::string label;
};

struct ScanHit {
    std::uint64_t addr = 0;
    std::uint64_t prev_raw = 0; // stores raw bytes (up to 8 bytes) for Next Scan delta comparisons
};

struct AobPattern {
    std::vector<std::uint8_t> bytes;
    std::vector<std::uint8_t> mask; // 1 = match byte, 0 = wildcard ??
};

std::mutex g_scan_mu;
std::vector<ScanHit> g_hits;
std::vector<ScanHit> g_prev_hits;
int g_last_type = DT_INT32;
std::size_t g_last_str_len = 0;

bool is_safe_private_rw_region(const MemRegion& r) {
    // Strictly require readable, writable, non-executable, private mapping
    if (r.perms[0] != 'r' || r.perms[1] != 'w' || r.perms[2] != '-' || r.perms[3] != 'p') {
        return false;
    }
    if (r.end <= r.start) return false;
    if ((r.end - r.start) > MAX_REGION_BYTES) return false;
    // Skip system/device/graphics/linker mappings
    const std::string_view lbl(r.label);
    if (!lbl.empty()) {
        if (lbl.starts_with("/dev/") || lbl.starts_with("/system/") ||
            lbl.starts_with("/apex/") || lbl.starts_with("/vendor/") ||
            lbl.starts_with("/product/") || lbl.starts_with("/odm/") ||
            lbl == "[vdso]" || lbl == "[vvar]" || lbl == "[vsyscall]" ||
            lbl.find("kgsl") != std::string_view::npos ||
            lbl.find("mali") != std::string_view::npos ||
            lbl.find("dma_buf") != std::string_view::npos ||
            lbl.find("dalvik-zygote") != std::string_view::npos) {
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

bool is_inside_scanner_internal(std::uint64_t addr, std::size_t size,
                                std::uint64_t buf_start, std::uint64_t buf_end) {
    if (buf_start != 0 && addr < buf_end && (addr + size) > buf_start) return true;
    auto overlaps_vec = [&](const std::vector<ScanHit>& v) -> bool {
        if (v.empty() && v.capacity() == 0) return false;
        const std::uint64_t vs = reinterpret_cast<std::uint64_t>(v.data());
        const std::uint64_t ve = vs + v.capacity() * sizeof(ScanHit);
        return vs != 0 && addr < ve && (addr + size) > vs;
    };
    return overlaps_vec(g_hits) || overlaps_vec(g_prev_hits);
}

std::size_t type_size(int dtype, std::size_t str_len) {
    switch (dtype) {
        case DT_INT8:   return 1;
        case DT_INT16:  return 2;
        case DT_INT32:  return 4;
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
        case DT_INT64:  return 8;
        case DT_FLOAT:  return 4;
        case DT_DOUBLE: return 8;
        default:        return 1;
    }
}

bool parse_double_strict(const char* s, double& out) {
    if (!s || !*s) return false;
    char* end = nullptr;
    out = std::strtod(s, &end);
    if (end == s || !std::isfinite(out)) return false;
    return true;
}

bool parse_i64_strict(const char* s, std::int64_t& out) {
    if (!s || !*s) return false;
    char* end = nullptr;
    const int base = (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) ? 16 : 10;
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

std::uint64_t load_raw_u64(const std::uint8_t* ptr, int dtype) {
    std::uint64_t raw = 0;
    switch (dtype) {
        case DT_INT8:   std::memcpy(&raw, ptr, 1); break;
        case DT_INT16:  std::memcpy(&raw, ptr, 2); break;
        case DT_INT32:  std::memcpy(&raw, ptr, 4); break;
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
            std::int8_t v = 0;
            std::memcpy(&v, &raw, 1);
            return static_cast<double>(v);
        }
        case DT_INT16: {
            std::int16_t v = 0;
            std::memcpy(&v, &raw, 2);
            return static_cast<double>(v);
        }
        case DT_INT32: {
            std::int32_t v = 0;
            std::memcpy(&v, &raw, 4);
            return static_cast<double>(v);
        }
        case DT_INT64: {
            std::int64_t v = 0;
            std::memcpy(&v, &raw, 8);
            return static_cast<double>(v);
        }
        case DT_FLOAT: {
            float v = 0.0f;
            std::memcpy(&v, &raw, 4);
            return static_cast<double>(v);
        }
        case DT_DOUBLE: {
            double v = 0.0;
            std::memcpy(&v, &raw, 8);
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

std::string build_results_json(int fd, int dtype, std::size_t item_len, std::size_t max_return) {
    std::string json;
    json.reserve(4096);
    json += "{\"ok\":true,\"total\":";
    json += std::to_string(g_hits.size());
    json += ",\"canUndo\":";
    json += (g_prev_hits.empty() ? "false" : "true");
    json += ",\"items\":[";
    const std::size_t limit = std::min(g_hits.size(), max_return);
    std::uint8_t tmp[64];
    const std::size_t rd_len = std::min<std::size_t>(item_len, sizeof(tmp));
    for (std::size_t i = 0; i < limit; ++i) {
        if (i > 0) json += ",";
        const auto& h = g_hits[i];
        std::memset(tmp, 0, sizeof(tmp));
        bool ok_rd = false;
        if (fd >= 0 && rd_len > 0) {
            const ssize_t got = ::pread(fd, tmp, rd_len, static_cast<off_t>(h.addr));
            ok_rd = (got == static_cast<ssize_t>(rd_len));
        }
        char addr_hex[32];
        std::snprintf(addr_hex, sizeof(addr_hex), "0x%llX", static_cast<unsigned long long>(h.addr));
        const std::string val_str = ok_rd ? format_value(tmp, rd_len, dtype) : "<unreadable>";
        json += "{\"addr\":\"";
        json += addr_hex;
        json += "\",\"val\":\"";
        json += json_escape(val_str);
        json += "\"}";
    }
    json += "]}";
    return json;
}

std::string mem_first_scan_impl(int dtype, int scan_type, const std::string& s1, const std::string& s2, std::size_t max_return) {
    std::lock_guard<std::mutex> lk(g_scan_mu);
    g_prev_hits.clear();
    g_hits.clear();
    g_last_type = dtype;

    double v1 = 0.0, v2 = 0.0;
    std::int64_t iv1 = 0, iv2 = 0;
    AobPattern aob;
    if (dtype == DT_STRING) {
        if (s1.empty()) return "{\"ok\":false,\"error\":\"Empty string query\"}";
        g_last_str_len = std::min<std::size_t>(s1.size(), 64);
    } else if (dtype == DT_AOB) {
        aob = parse_aob(s1);
        if (aob.bytes.empty()) return "{\"ok\":false,\"error\":\"Invalid hex byte pattern\"}";
        g_last_str_len = aob.bytes.size();
    } else {
        g_last_str_len = 0;
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

    const int fd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "{\"ok\":false,\"error\":\"Cannot open self process memory\"}";

    const auto regions = parse_self_maps();
    std::vector<std::uint8_t> chunk(CHUNK_BYTES);
    const std::uint64_t chunk_start = reinterpret_cast<std::uint64_t>(chunk.data());
    const std::uint64_t chunk_end = chunk_start + chunk.size();
    const std::size_t item_sz = type_size(dtype, g_last_str_len);
    const std::size_t step = type_align(dtype);
    const std::size_t cap = (scan_type == SC_UNKNOWN) ? MAX_UNKNOWN_CAP : MAX_RESULTS_CAP;

    // Exclude our query strings themselves from matching
    const std::uint64_t s1_start = reinterpret_cast<std::uint64_t>(s1.data());
    const std::uint64_t s1_end = s1_start + s1.capacity() + 1;

    for (const auto& reg : regions) {
        if (g_hits.size() >= cap) break;
        std::uint64_t cursor = reg.start;
        while (cursor < reg.end && g_hits.size() < cap) {
            const std::size_t to_read = static_cast<std::size_t>(std::min<std::uint64_t>(CHUNK_BYTES, reg.end - cursor));
            if (to_read < item_sz) break;
            const ssize_t got = ::pread(fd, chunk.data(), to_read, static_cast<off_t>(cursor));
            if (got < static_cast<ssize_t>(item_sz)) {
                cursor += to_read;
                continue;
            }
            const std::size_t valid = static_cast<std::size_t>(got);
            const std::size_t last_off = valid - item_sz;

            if (dtype == DT_STRING) {
                const auto* needle = reinterpret_cast<const std::uint8_t*>(s1.data());
                for (std::size_t off = 0; off <= last_off && g_hits.size() < cap; ++off) {
                    if (chunk[off] == needle[0] && std::memcmp(chunk.data() + off, needle, item_sz) == 0) {
                        const std::uint64_t addr = cursor + off;
                        if (addr >= s1_start && addr < s1_end) continue;
                        if (is_inside_scanner_internal(addr, item_sz, chunk_start, chunk_end)) continue;
                        g_hits.push_back(ScanHit{addr, 0});
                    }
                }
            } else if (dtype == DT_AOB) {
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
                        g_hits.push_back(ScanHit{addr, 0});
                    }
                }
            } else {
                for (std::size_t off = 0; off <= last_off && g_hits.size() < cap; off += step) {
                    const std::uint64_t raw = load_raw_u64(chunk.data() + off, dtype);
                    if (match_numeric(raw, 0, false, dtype, scan_type, v1, v2, iv1, iv2)) {
                        const std::uint64_t addr = cursor + off;
                        if (is_inside_scanner_internal(addr, item_sz, chunk_start, chunk_end)) continue;
                        g_hits.push_back(ScanHit{addr, raw});
                    }
                }
            }
            const std::size_t advance = (valid > item_sz && step == 1) ? (valid - item_sz + 1) : valid;
            cursor += advance;
        }
    }

    const std::string res = build_results_json(fd, dtype, item_sz, max_return);
    ::close(fd);
    return res;
}

std::string mem_next_scan_impl(int scan_type, const std::string& s1, const std::string& s2, std::size_t max_return) {
    std::lock_guard<std::mutex> lk(g_scan_mu);
    if (g_hits.empty()) {
        return "{\"ok\":false,\"error\":\"Run First Scan before Next Scan\"}";
    }
    const int dtype = g_last_type;
    double v1 = 0.0, v2 = 0.0;
    std::int64_t iv1 = 0, iv2 = 0;
    AobPattern aob;
    const bool needs_v1 = (scan_type == SC_EXACT || scan_type == SC_BIGGER || scan_type == SC_SMALLER ||
                           scan_type == SC_BETWEEN || scan_type == SC_INCREASED_BY || scan_type == SC_DECREASED_BY);
    if (dtype == DT_STRING) {
        if (s1.empty()) return "{\"ok\":false,\"error\":\"Empty string query\"}";
    } else if (dtype == DT_AOB) {
        aob = parse_aob(s1);
        if (aob.bytes.empty()) return "{\"ok\":false,\"error\":\"Invalid hex byte pattern\"}";
    } else if (needs_v1) {
        if (!parse_double_strict(s1.c_str(), v1)) return "{\"ok\":false,\"error\":\"Invalid numeric value\"}";
        parse_i64_strict(s1.c_str(), iv1);
        if (scan_type == SC_BETWEEN) {
            if (!parse_double_strict(s2.c_str(), v2)) return "{\"ok\":false,\"error\":\"Invalid max value\"}";
            parse_i64_strict(s2.c_str(), iv2);
            if (v2 < v1) { std::swap(v1, v2); std::swap(iv1, iv2); }
        }
    }

    const int fd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "{\"ok\":false,\"error\":\"Cannot open self process memory\"}";

    g_prev_hits = g_hits;
    std::vector<ScanHit> next;
    next.reserve(std::min<std::size_t>(g_hits.size(), 4096));
    const std::size_t item_sz = type_size(dtype, g_last_str_len);
    std::uint8_t buf[64];
    const std::size_t rd_sz = std::min<std::size_t>(item_sz, sizeof(buf));

    for (const auto& h : g_hits) {
        const ssize_t got = ::pread(fd, buf, rd_sz, static_cast<off_t>(h.addr));
        if (got != static_cast<ssize_t>(rd_sz)) continue;
        if (dtype == DT_STRING) {
            const std::size_t cmp_len = std::min(rd_sz, s1.size());
            if (std::memcmp(buf, s1.data(), cmp_len) == 0) {
                next.push_back(h);
            }
        } else if (dtype == DT_AOB) {
            bool ok = true;
            for (std::size_t k = 0; k < aob.bytes.size() && k < rd_sz; ++k) {
                if (aob.mask[k] && buf[k] != aob.bytes[k]) { ok = false; break; }
            }
            if (ok) next.push_back(h);
        } else {
            const std::uint64_t raw = load_raw_u64(buf, dtype);
            if (match_numeric(raw, h.prev_raw, true, dtype, scan_type, v1, v2, iv1, iv2)) {
                next.push_back(ScanHit{h.addr, raw});
            }
        }
    }
    g_hits.swap(next);
    const std::string res = build_results_json(fd, dtype, item_sz, max_return);
    ::close(fd);
    return res;
}

std::string mem_undo_impl(std::size_t max_return) {
    std::lock_guard<std::mutex> lk(g_scan_mu);
    if (g_prev_hits.empty()) {
        return "{\"ok\":false,\"error\":\"Nothing to undo\"}";
    }
    g_hits.swap(g_prev_hits);
    g_prev_hits.clear();
    const int fd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    const std::string res = build_results_json(fd, g_last_type, type_size(g_last_type, g_last_str_len), max_return);
    if (fd >= 0) ::close(fd);
    return res;
}

std::string mem_read_value_impl(std::uint64_t addr, int dtype, std::size_t str_len) {
    const std::size_t sz = std::min<std::size_t>(type_size(dtype, str_len > 0 ? str_len : 16), 64);
    const int fd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "<err>";
    std::uint8_t buf[64] = {0};
    const ssize_t got = ::pread(fd, buf, sz, static_cast<off_t>(addr));
    ::close(fd);
    if (got != static_cast<ssize_t>(sz)) return "<unreadable>";
    return format_value(buf, sz, dtype);
}

bool mem_write_value_impl(std::uint64_t addr, int dtype, const std::string& val_str) {
    std::uint8_t buf[64] = {0};
    std::size_t sz = 0;
    if (dtype == DT_STRING) {
        if (val_str.empty()) return false;
        sz = std::min<std::size_t>(val_str.size(), sizeof(buf));
        std::memcpy(buf, val_str.data(), sz);
    } else if (dtype == DT_AOB) {
        const auto pat = parse_aob(val_str);
        if (pat.bytes.empty()) return false;
        sz = std::min<std::size_t>(pat.bytes.size(), sizeof(buf));
        std::memcpy(buf, pat.bytes.data(), sz);
    } else {
        double dv = 0.0;
        std::int64_t iv = 0;
        if (!parse_double_strict(val_str.c_str(), dv)) return false;
        parse_i64_strict(val_str.c_str(), iv);
        switch (dtype) {
            case DT_INT8:   { std::int8_t v = static_cast<std::int8_t>(iv); std::memcpy(buf, &v, 1); sz = 1; break; }
            case DT_INT16:  { std::int16_t v = static_cast<std::int16_t>(iv); std::memcpy(buf, &v, 2); sz = 2; break; }
            case DT_INT32:  { std::int32_t v = static_cast<std::int32_t>(iv); std::memcpy(buf, &v, 4); sz = 4; break; }
            case DT_INT64:  { std::memcpy(buf, &iv, 8); sz = 8; break; }
            case DT_FLOAT:  { float v = static_cast<float>(dv); std::memcpy(buf, &v, 4); sz = 4; break; }
            case DT_DOUBLE: { std::memcpy(buf, &dv, 8); sz = 8; break; }
            default: return false;
        }
    }
    // Safety gate: ONLY allow writes inside Fable's own verified rw-p private heap mappings!
    if (!is_addr_in_safe_rw_region(addr, sz)) return false;
    const int fd = ::open("/proc/self/mem", O_RDWR | O_CLOEXEC);
    if (fd < 0) return false;
    const ssize_t wrote = ::pwrite(fd, buf, sz, static_cast<off_t>(addr));
    ::close(fd);
    return wrote == static_cast<ssize_t>(sz);
}

std::string mem_hex_dump_impl(std::uint64_t addr, std::size_t len) {
    const std::size_t clamped = std::min<std::size_t>(std::max<std::size_t>(len, 16), 256);
    const int fd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "{\"ok\":false,\"error\":\"Cannot open self mem\"}";
    std::uint8_t buf[256] = {0};
    const ssize_t got = ::pread(fd, buf, clamped, static_cast<off_t>(addr));
    ::close(fd);
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

std::string jstr_to_std(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    if (!c) return {};
    std::string s(c, static_cast<std::size_t>(env->GetStringUTFLength(js)));
    env->ReleaseStringUTFChars(js, c);
    return s;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeFirstScan(
    JNIEnv* env, jclass, jint dtype, jint scan_type, jstring val1, jstring val2, jint max_return
) {
    const std::string res = mem_first_scan_impl(
        static_cast<int>(dtype),
        static_cast<int>(scan_type),
        jstr_to_std(env, val1),
        jstr_to_std(env, val2),
        static_cast<std::size_t>(max_return > 0 ? max_return : 150)
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeNextScan(
    JNIEnv* env, jclass, jint scan_type, jstring val1, jstring val2, jint max_return
) {
    const std::string res = mem_next_scan_impl(
        static_cast<int>(scan_type),
        jstr_to_std(env, val1),
        jstr_to_std(env, val2),
        static_cast<std::size_t>(max_return > 0 ? max_return : 150)
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeUndoScan(
    JNIEnv* env, jclass, jint max_return
) {
    const std::string res = mem_undo_impl(static_cast<std::size_t>(max_return > 0 ? max_return : 150));
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeResetScan(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_scan_mu);
    std::vector<ScanHit>().swap(g_hits);
    std::vector<ScanHit>().swap(g_prev_hits);
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeReadValue(
    JNIEnv* env, jclass, jlong addr, jint dtype, jint str_len
) {
    const std::string res = mem_read_value_impl(
        static_cast<std::uint64_t>(addr),
        static_cast<int>(dtype),
        static_cast<std::size_t>(str_len > 0 ? str_len : 16)
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeWriteValue(
    JNIEnv* env, jclass, jlong addr, jint dtype, jstring val_str
) {
    const bool ok = mem_write_value_impl(
        static_cast<std::uint64_t>(addr),
        static_cast<int>(dtype),
        jstr_to_std(env, val_str)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeHexDump(
    JNIEnv* env, jclass, jlong addr, jint len
) {
    const std::string res = mem_hex_dump_impl(
        static_cast<std::uint64_t>(addr),
        static_cast<std::size_t>(len > 0 ? len : 128)
    );
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fable_browser_engine_NativeMemoryScanner_nativeListRegions(JNIEnv* env, jclass) {
    const std::string res = mem_regions_json_impl();
    return env->NewStringUTF(res.c_str());
}

} // extern "C"
