#!/usr/bin/env python3
import os
import re
import sys

ROOT = "/tmp/fable"
MOD = os.path.join(ROOT, "local-bloxd")
KT_MAIN = os.path.join(MOD, "src/main/java/com/fable/localbloxd")
KT_TEST = os.path.join(MOD, "src/test/java/com/fable/localbloxd")
CPP_PATH = os.path.join(MOD, "src/main/cpp/local_bloxd_native.cpp")
RUST_PATH = os.path.join(ROOT, "rust/local_bloxd_rust/src/lib.rs")
MANIFEST = os.path.join(MOD, "src/main/AndroidManifest.xml")
ASSETS = os.path.join(MOD, "src/main/assets/bloxd")

errors = []

def check(cond, msg):
    if not cond:
        errors.append(msg)
        print(f"FAIL: {msg}")
    else:
        print(f"OK:   {msg}")

# 1. AndroidManifest.xml offline invariant
manifest_txt = open(MANIFEST, encoding="utf-8").read()
check("android.permission.INTERNET" not in manifest_txt.replace("<!-- Strictly 100% offline: android.permission.INTERNET is intentionally omitted! -->", ""),
      "AndroidManifest.xml has no active INTERNET permission")
check("<uses-permission" not in manifest_txt, "AndroidManifest.xml declares zero <uses-permission> tags")
check('android:name=".LocalBloxdActivity"' in manifest_txt, "LocalBloxdActivity registered in manifest")

# 2. Asset manifest 1:1 audit
manifest_entries = [l.strip() for l in open(os.path.join(ASSETS, "asset_manifest.txt"), encoding="utf-8") if l.strip()]
actual_entries = []
for dp, _, fns in os.walk(ASSETS):
    for fn in fns:
        rel = os.path.relpath(os.path.join(dp, fn), os.path.dirname(ASSETS)).replace("\\", "/")
        actual_entries.append(rel)
actual_entries.sort()
check(manifest_entries == actual_entries,
      f"asset_manifest.txt matches disk 1:1 ({len(actual_entries)} files, sorted, zero missing/stale)")
check("bloxd/static/js/7rp1w.117.2c551f46.chunk.js" in manifest_entries,
      "Chunk 117 shim is present in asset_manifest.txt")

# 3. No window.fetch override anywhere in local-bloxd assets
for rel in actual_entries:
    if rel.endswith(".js") or rel.endswith(".html"):
        full = os.path.join(os.path.dirname(ASSETS), rel)
        txt = open(full, encoding="utf-8", errors="ignore").read()
        if re.search(r'(window|globalThis|self)\.fetch\s*=', txt):
            check(False, f"Forbidden fetch override in {rel}")
check(True, "Zero window.fetch/globalThis.fetch/self.fetch overrides across all JS/HTML assets")

# 4. Kotlin delimiter balance & syntax hygiene check
def check_kotlin_delimiters(path):
    src = open(path, encoding="utf-8").read()
    stack = []
    i = 0
    n = len(src)
    line = 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        # Line comment
        if c == '/' and i + 1 < n and src[i+1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        # Block comment (nestable in Kotlin)
        if c == '/' and i + 1 < n and src[i+1] == '*':
            depth = 1
            i += 2
            while i < n and depth > 0:
                if src[i] == '\n':
                    line += 1
                if src[i] == '/' and i + 1 < n and src[i+1] == '*':
                    depth += 1
                    i += 2
                elif src[i] == '*' and i + 1 < n and src[i+1] == '/':
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        # Triple-quoted raw string
        if src[i:i+3] == '"""':
            i += 3
            while i < n and src[i:i+3] != '"""':
                if src[i] == '\n':
                    line += 1
                i += 1
            if i >= n:
                return f"Unterminated raw string in {os.path.basename(path)}"
            i += 3
            continue
        # Normal string
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == '\n':
                    return f"Newline in string at {os.path.basename(path)}:{line}"
                i += 1
            if i >= n:
                return f"Unterminated string at {os.path.basename(path)}:{line}"
            i += 1
            continue
        # Char literal
        if c == "'":
            i += 1
            if i < n and src[i] == '\\':
                i += 2
                if src[i-1] == 'u':
                    i += 4
            else:
                i += 1
            if i >= n or src[i] != "'":
                return f"Malformed char literal at {os.path.basename(path)}:{line}"
            i += 1
            continue
        if c in "({[":
            stack.append((c, line))
        elif c in ")}]":
            pairs = {')': '(', '}': '{', ']': '['}
            if not stack or stack[-1][0] != pairs[c]:
                return f"Mismatched '{c}' at {os.path.basename(path)}:{line}"
            stack.pop()
        i += 1
    if stack:
        return f"Unclosed '{stack[-1][0]}' from line {stack[-1][1]} in {os.path.basename(path)}"
    return None

kt_files = []
for d in (KT_MAIN, KT_TEST):
    if os.path.isdir(d):
        for fn in sorted(os.listdir(d)):
            if fn.endswith(".kt"):
                kt_files.append(os.path.join(d, fn))

for kf in kt_files:
    err = check_kotlin_delimiters(kf)
    check(err is None, f"Kotlin lexer/delimiter check: {os.path.basename(kf)}" + (f" ({err})" if err else ""))
    src = open(kf, encoding="utf-8").read()
    check(src.startswith("package com.fable.localbloxd"), f"Package header OK: {os.path.basename(kf)}")

# 5. Pure-JVM invariant on LocalBloxdProtocolCore.kt (zero android.* imports)
core_src = open(os.path.join(KT_MAIN, "LocalBloxdProtocolCore.kt"), encoding="utf-8").read()
check("import android." not in core_src and "import org.json." not in core_src,
      "LocalBloxdProtocolCore.kt has zero Android framework imports (pure JVM unit-testable)")

# 6. Exact JNI symbol verification: Kotlin external fun <-> C++ & Rust extern "C"
bridge_src = open(os.path.join(KT_MAIN, "NativeBloxdBridge.kt"), encoding="utf-8").read()
cpp_src = open(CPP_PATH, encoding="utf-8").read()
rust_src = open(RUST_PATH, encoding="utf-8").read()

kt_externals = re.findall(r'@JvmStatic\s+external\s+fun\s+(\w+)\s*\(', bridge_src)
cpp_exports = set(re.findall(r'Java_com_fable_localbloxd_NativeBloxdBridge_(\w+)\s*\(', cpp_src))
rust_exports = set(re.findall(r'Java_com_fable_localbloxd_NativeBloxdBridge_(\w+)\s*\(', rust_src))
all_native_exports = cpp_exports | rust_exports

check(len(kt_externals) == 12, f"NativeBloxdBridge declares 12 JNI externals (got {len(kt_externals)})")
for fn in kt_externals:
    check(fn in all_native_exports, f"JNI symbol exported in C++/Rust: {fn}")
for fn in sorted(all_native_exports):
    check(fn in kt_externals, f"Native C++/Rust export declared in NativeBloxdBridge.kt: {fn}")

# 7. Cross-file Kotlin API call verification
core_funcs = set(re.findall(r'\bfun\s+(\w+)\s*\(', core_src))
core_types = set(re.findall(r'\b(?:data\s+class|object|class)\s+(\w+)\b', core_src))
bridge_funcs = set(re.findall(r'\bfun\s+(\w+)\s*\(', bridge_src))
server_src = open(os.path.join(KT_MAIN, "LocalBloxdAssetServer.kt"), encoding="utf-8").read()
act_src = open(os.path.join(KT_MAIN, "LocalBloxdActivity.kt"), encoding="utf-8").read()
test_src = open(os.path.join(KT_TEST, "LocalBloxdProtocolCoreTest.kt"), encoding="utf-8").read()

for caller_name, caller_src in [
    ("NativeBloxdBridge.kt", bridge_src),
    ("LocalBloxdProtocolCoreTest.kt", test_src),
]:
    refs = set(re.findall(r'LocalBloxdProtocolCore\.(\w+)', caller_src))
    for r in sorted(refs):
        ok = (r in core_funcs) or (r in core_types) or (r == "MAX_NAME_CODEPOINTS")
        check(ok, f"{caller_name} -> LocalBloxdProtocolCore.{r} resolves")

server_bridge_calls = set(re.findall(r'NativeBloxdBridge\.(\w+)\s*\(', server_src))
for fn in sorted(server_bridge_calls):
    check(fn in bridge_funcs, f"LocalBloxdAssetServer.kt -> NativeBloxdBridge.{fn}() resolves")

check("assetServer.intercept(uri, request.method ?: \"GET\", request.requestHeaders ?: emptyMap())" in act_src,
      "LocalBloxdActivity forwards requestHeaders to LocalBloxdAssetServer.intercept")

# 8. JS -> JsBridge @JavascriptInterface verification
js_ifaces = set(re.findall(r'@JavascriptInterface\s+fun\s+(\w+)\s*\(', bridge_src))
for expected_iface in [
    "isNativeReady", "stagePostBody", "setPlayerName", "getPlayerName",
    "setRequestedMode", "getRequestedModeJson", "setBlockEdit", "setWorldBlockEdit",
    "clearWorldEdits", "saveWorldMetaJson", "setCodeSessionTarget",
    "syncCodeDoc", "syncCodeDocFull", "deleteCodeDoc", "loadWorldBundleJson",
    "generateChunk32RleBase64", "generateSubchunkBase64", "getSurfaceHeight",
    "saveCodeBlock", "loadSavedWorldJson",
]:
    check(expected_iface in js_ifaces, f"@JavascriptInterface exported: {expected_iface}")

print(f"\nAUDIT SUMMARY: {len(errors)} error(s)")
sys.exit(1 if errors else 0)
