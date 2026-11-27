package com.fable.browser.engine

enum class MemScope(val label: String) {
    PAGE_WASM("Page & WASM"),
    FABLE_NATIVE("Fable Process")
}

enum class MemDataType(val code: Int, val jsCode: String, val shortLabel: String) {
    AUTO(8, "auto", "Auto"),
    INT8(0, "i8", "1B (i8)"),
    INT16(1, "i16", "2B (i16)"),
    INT32(2, "i32", "4B (i32)"),
    UINT32(9, "u32", "4B (u32)"),
    INT64(3, "i64", "8B (i64)"),
    FLOAT(4, "f32", "Float"),
    DOUBLE(5, "f64", "Double"),
    STRING(6, "str", "String"),
    AOB(7, "aob", "Hex AOB")
}

enum class MemScanType(val code: Int, val jsCode: String, val label: String, val nextOnly: Boolean = false) {
    EXACT(0, "exact", "Exact Value"),
    UNKNOWN(4, "unknown", "Unknown Initial"),
    GROUP(11, "group", "Group Search"),
    XOR_ENCRYPTED(12, "xor", "XOR / Encrypted"),
    BIGGER(1, "bigger", "Bigger than"),
    SMALLER(2, "smaller", "Smaller than"),
    BETWEEN(3, "between", "Between"),
    INCREASED(5, "increased", "Increased", nextOnly = true),
    DECREASED(6, "decreased", "Decreased", nextOnly = true),
    INCREASED_BY(7, "increased_by", "Increased by", nextOnly = true),
    DECREASED_BY(8, "decreased_by", "Decreased by", nextOnly = true),
    CHANGED(9, "changed", "Changed", nextOnly = true),
    UNCHANGED(10, "unchanged", "Unchanged", nextOnly = true),
    ALWAYS_INC(13, "always_inc", "Always Inc (Snaps)", nextOnly = true),
    ALWAYS_DEC(14, "always_dec", "Always Dec (Snaps)", nextOnly = true),
    RETURNED_ORIG(15, "returned_orig", "Returned Orig", nextOnly = true)
}

object NativeMemoryScanner {
    val isAvailable: Boolean get() = NativeFastEngine.isNativeAvailable

    @JvmStatic
    private external fun nativeFirstScan(dtype: Int, scanType: Int, val1: String, val2: String, maxReturn: Int, timeoutMs: Int): String

    @JvmStatic
    private external fun nativeNextScan(scanType: Int, val1: String, val2: String, maxReturn: Int, timeoutMs: Int): String

    @JvmStatic
    private external fun nativeCancelScan()

    @JvmStatic
    private external fun nativeRefreshHits(maxReturn: Int): String

    @JvmStatic
    private external fun nativeUndoScan(maxReturn: Int): String

    @JvmStatic
    private external fun nativeResetScan()

    @JvmStatic
    private external fun nativeReadValue(addr: Long, dtype: Int, strLen: Int): String

    @JvmStatic
    private external fun nativeReadValueEx(addr: Long, dtype: Int, strLen: Int, xorMode: Int, xorKey: Long, xorKeyAddr: Long): String

    @JvmStatic
    private external fun nativeWriteValue(addr: Long, dtype: Int, valStr: String): Boolean

    @JvmStatic
    private external fun nativeWriteValueEx(addr: Long, dtype: Int, valStr: String, xorMode: Int, xorKey: Long, xorKeyAddr: Long): Boolean

    @JvmStatic
    private external fun nativeWriteHex(addr: Long, hexStr: String): String

    @JvmStatic
    private external fun nativeHexDump(addr: Long, len: Int): String

    @JvmStatic
    private external fun nativePointerScan(targetAddr: Long, maxOffset: Int, maxReturn: Int, timeoutMs: Int): String

    @JvmStatic
    private external fun nativeListRegions(): String

    @JvmStatic
    private external fun nativeSetFreeze(addr: Long, dtype: Int, valStr: String): Boolean

    @JvmStatic
    private external fun nativeSetFreezeRule(addr: Long, dtype: Int, valStr: String, rule: Int): Boolean

    @JvmStatic
    private external fun nativeSetFreezeEx(addr: Long, dtype: Int, valStr: String, rule: Int, xorMode: Int, xorKey: Long, xorKeyAddr: Long): Boolean

    @JvmStatic
    private external fun nativePeekStruct(addr: Long, radius: Int): String

    @JvmStatic
    private external fun nativeUnfreeze(addr: Long)

    @JvmStatic
    private external fun nativeUnfreezeAll()

    @JvmStatic
    private external fun nativeGetFreezeStats(): String

    @JvmStatic
    private external fun nativeSetFreezeStrength(level: Int)

    @JvmStatic
    private external fun nativeFindMirrors(targetAddr: Long, dtype: Int, maxReturn: Int, timeoutMs: Int): String

    @JvmStatic
    private external fun nativeMakeSignature(addr: Long): String

    @JvmStatic
    private external fun nativeResolveSignature(sigHex: String, valueOffset: Int, timeoutMs: Int): String

    @JvmStatic
    private external fun nativeTestAuthority(addr: Long, dtype: Int): String

    fun firstScan(
        dtype: MemDataType,
        scanType: MemScanType,
        val1: String,
        val2: String = "",
        maxReturn: Int = 300,
        timeoutMs: Int = 15_000
    ): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeFirstScan(dtype.code, scanType.code, val1, val2, maxReturn, timeoutMs.coerceIn(1_000, 80_000)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Native scan error\"}" }
    }

    fun nextScan(
        scanType: MemScanType,
        val1: String,
        val2: String = "",
        maxReturn: Int = 300,
        timeoutMs: Int = 15_000
    ): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeNextScan(scanType.code, val1, val2, maxReturn, timeoutMs.coerceIn(1_000, 80_000)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Native next scan error\"}" }
    }

    fun cancelScan() {
        if (isAvailable) runCatching { nativeCancelScan() }
    }

    fun refreshHits(maxReturn: Int = 300): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeRefreshHits(maxReturn) }
            .getOrElse { "{\"ok\":false,\"error\":\"Refresh failed\"}" }
    }

    fun undoScan(maxReturn: Int = 300): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeUndoScan(maxReturn) }
            .getOrElse { "{\"ok\":false,\"error\":\"Undo failed\"}" }
    }

    fun resetScan() {
        if (isAvailable) runCatching { nativeResetScan() }
    }

    fun readValue(
        addrHex: String,
        dtype: MemDataType,
        strLen: Int = 16,
        xorMode: Int = 0,
        xorKey: Long = 0L,
        xorKeyAddrHex: String = ""
    ): String {
        if (!isAvailable) return "<n/a>"
        val addr = parseHexAddress(addrHex) ?: return "<bad addr>"
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        val kAddr = if (xorKeyAddrHex.isNotBlank()) (parseHexAddress(xorKeyAddrHex) ?: 0L) else 0L
        return runCatching {
            if (xorMode == 0) nativeReadValue(addr, nativeType, strLen)
            else nativeReadValueEx(addr, nativeType, strLen, xorMode, xorKey, kAddr)
        }.getOrDefault("<err>")
    }

    fun writeValue(
        addrHex: String,
        dtype: MemDataType,
        valStr: String,
        xorMode: Int = 0,
        xorKey: Long = 0L,
        xorKeyAddrHex: String = ""
    ): Boolean {
        if (!isAvailable) return false
        val addr = parseHexAddress(addrHex) ?: return false
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        val kAddr = if (xorKeyAddrHex.isNotBlank()) (parseHexAddress(xorKeyAddrHex) ?: 0L) else 0L
        return runCatching {
            if (xorMode == 0) nativeWriteValue(addr, nativeType, valStr)
            else nativeWriteValueEx(addr, nativeType, valStr, xorMode, xorKey, kAddr)
        }.getOrDefault(false)
    }

    fun writeHex(addrHex: String, hexStr: String): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(addrHex) ?: return "{\"ok\":false,\"error\":\"Invalid address\"}"
        return runCatching { nativeWriteHex(addr, hexStr) }
            .getOrElse { "{\"ok\":false,\"error\":\"Hex write failed\"}" }
    }

    fun pointerScan(targetAddrHex: String, maxOffset: Int = 4096, maxReturn: Int = 120, timeoutMs: Int = 12_000): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(targetAddrHex) ?: return "{\"ok\":false,\"error\":\"Invalid target address\"}"
        return runCatching { nativePointerScan(addr, maxOffset.coerceIn(0, 65536), maxReturn.coerceIn(1, 200), timeoutMs.coerceIn(1_000, 30_000)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Pointer scan error\"}" }
    }

    fun setFreeze(
        addrHex: String,
        dtype: MemDataType,
        valStr: String,
        ruleCode: Int = 0,
        xorMode: Int = 0,
        xorKey: Long = 0L,
        xorKeyAddrHex: String = ""
    ): Boolean {
        if (!isAvailable) return false
        val addr = parseHexAddress(addrHex) ?: return false
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        val kAddr = if (xorKeyAddrHex.isNotBlank()) (parseHexAddress(xorKeyAddrHex) ?: 0L) else 0L
        return runCatching {
            if (xorMode != 0) nativeSetFreezeEx(addr, nativeType, valStr, ruleCode, xorMode, xorKey, kAddr)
            else if (ruleCode == 0) nativeSetFreeze(addr, nativeType, valStr)
            else nativeSetFreezeRule(addr, nativeType, valStr, ruleCode)
        }.getOrDefault(false)
    }

    fun peekStruct(addrHex: String, radius: Int = 64): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(addrHex) ?: return "{\"ok\":false,\"error\":\"Invalid address\"}"
        return runCatching { nativePeekStruct(addr, radius.coerceIn(32, 256)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Struct peek failed\"}" }
    }

    fun unfreeze(addrHex: String) {
        if (!isAvailable) return
        val addr = parseHexAddress(addrHex) ?: return
        runCatching { nativeUnfreeze(addr) }
    }

    fun unfreezeAll() {
        if (isAvailable) runCatching { nativeUnfreezeAll() }
    }

    fun getFreezeStats(): String {
        if (!isAvailable) return "{\"ok\":true,\"stats\":{}}"
        return runCatching { nativeGetFreezeStats() }.getOrDefault("{\"ok\":true,\"stats\":{}}")
    }

    fun setFreezeStrength(level: Int) {
        if (isAvailable) runCatching { nativeSetFreezeStrength(level.coerceIn(0, 2)) }
    }

    fun findMirrors(addrHex: String, dtype: MemDataType, maxReturn: Int = 60, timeoutMs: Int = 8_000): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(addrHex) ?: return "{\"ok\":false,\"error\":\"Invalid address\"}"
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        return runCatching { nativeFindMirrors(addr, nativeType, maxReturn.coerceIn(1, 100), timeoutMs.coerceIn(1_000, 20_000)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Mirror scan failed\"}" }
    }

    fun makeSignature(addrHex: String): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(addrHex) ?: return "{\"ok\":false,\"error\":\"Invalid address\"}"
        return runCatching { nativeMakeSignature(addr) }.getOrElse { "{\"ok\":false,\"error\":\"Signature error\"}" }
    }

    fun resolveSignature(sigHex: String, valueOffset: Int = 12, timeoutMs: Int = 8_000): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeResolveSignature(sigHex, valueOffset, timeoutMs.coerceIn(1_000, 15_000)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Signature resolve error\"}" }
    }

    fun testAuthority(addrHex: String, dtype: MemDataType): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(addrHex) ?: return "{\"ok\":false,\"error\":\"Invalid address\"}"
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        return runCatching { nativeTestAuthority(addr, nativeType) }.getOrElse { "{\"ok\":false,\"error\":\"Authority test error\"}" }
    }

    fun hexDump(addrHex: String, len: Int = 128): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val addr = parseHexAddress(addrHex) ?: return "{\"ok\":false,\"error\":\"Invalid address\"}"
        return runCatching { nativeHexDump(addr, len.coerceIn(16, 256)) }
            .getOrElse { "{\"ok\":false,\"error\":\"Hex dump error\"}" }
    }

    fun listRegions(): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeListRegions() }
            .getOrElse { "{\"ok\":false,\"error\":\"Regions list error\"}" }
    }

    fun parseHexAddress(addrHex: String): Long? {
        val clean = addrHex.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return null
        return runCatching { java.lang.Long.parseUnsignedLong(clean, 16) }.getOrNull()
    }
}
