# WIP-v21 Checkpoint Continuation Guide

## Completed in v21
1. **C++20 IEEE 802.3 CRC32 & Smart Zero-Recompression ZIP (`fable_fast.cpp`, `NativeFastEngine.kt`, `PageArchiver.kt`)**:
   - Added 4-byte unrolled C++20 `crc32` (`nativeCrc32`) and fast single-pass `extract_host` in `fable_fast.cpp`.
   - Updated `PageArchiver.kt` to write pre-compressed formats (`.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`, `.avif`, `.woff2`, `.mp3`, `.ogg`, `.mp4`, `.webm`, `.zip`, `.gz`, `.xz`, `.br`) using `ZipEntry.STORED` + `NativeFastEngine.crc32(decoded)` and `setLevel(4)` for compressible files.
2. **Inline WebAssembly (`WebAssembly.Instance`) Fast Binary Hasher + 6-Way Concurrent Fetch Pool (`page_tools.js`)**:
   - Embedded verified WebAssembly binary (`wasmHasher`) for native-speed FNV-1a 32-bit hashing of `Uint8Array` asset buffers.
   - Replaced sequential single-request fetch loop with a 6-worker concurrent fetch pool (`nWorkers = 6`) + serialized `sendChain` promise queue for 4x-6x faster page/memory archive creation.
   - Added accessible same-origin iframe DOM dumps (`memory/frames/frame-N.html`).
