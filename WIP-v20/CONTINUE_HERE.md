# WIP-v20 Checkpoint Continuation Guide

## Completed in v20
1. **Native C++20 Engine (`app/src/main/cpp/fable_fast.cpp`, `CMakeLists.txt`, `NativeFastEngine.kt`)**:
   - Added high-speed C++20 JNI native library `libfable_fast.so` compiled with `-O3 -Wall -Wextra`.
   - Implemented 4-byte unrolled 256-entry lookup table Base64 decoder (`nativeDecodeBase64`), 8-byte unrolled 64-bit hash (`nativeHash64`), and fast domain suffix boundary matcher (`nativeMatchDomain`).
   - Wired `NativeFastEngine` into `PageArchiver.kt` with 512 KB buffered output streams and raised `Downloader.kt` copy buffer to 256 KB.
   - Tested `fable_fast.cpp` with `g++ -std=c++20 -O3 -Wall -Wextra -Werror -pedantic` (zero warnings, zero errors).
2. **Maximum HAR 1.2 & Memory Snapshot Collection (`blocker.js`, `page_tools.js`)**:
   - Fixed `onBeforeRequest` in `blocker.js` so `captureStreamIfEnabled(details)` captures ALL non-blocked requests (`script`, `image`, `fetch`, `xmlhttprequest`, `font`, `media`, `wasm`, `stylesheet`, `other`), not just `main_frame`.
   - Upgraded `devtools/network.har` to full HAR 1.2 with request/response headers, query strings, timings, and embedded response bodies (`content.text` + `base64` or UTF-8).
   - Enabled full resource & network stream body inclusion in `mode === "memory"` ("Download memory snapshot (.zip)") plus `memory/js-heap-and-webgl.json` (custom window globals + WebGL context/renderer/extension state).
   - Accelerated transfer speed with 512 KB chunks and `TextDecoder("latin1")` bulk Base64 encoding.
