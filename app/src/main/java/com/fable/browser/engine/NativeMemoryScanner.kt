package com.fable.browser.engine

enum class MemScope(val label: String) {
    PAGE_WASM("Page & WASM"),
    FABLE_NATIVE("Fable Process")
}

enum class MemDataType(val code: Int, val jsCode: String, val shortLabel: String) {
    AUTO(2, "auto", "Auto"),
    INT8(0, "i8", "1B (i8)"),
    INT16(1, "i16", "2B (i16)"),
    INT32(2, "i32", "4B (i32)"),
    INT64(3, "i64", "8B (i64)"),
    FLOAT(4, "f32", "Float"),
    DOUBLE(5, "f64", "Double"),
    STRING(6, "str", "String"),
    AOB(7, "aob", "Hex AOB")
}

enum class MemScanType(val code: Int, val jsCode: String, val label: String, val nextOnly: Boolean = false) {
    EXACT(0, "exact", "Exact Value"),
    UNKNOWN(4, "unknown", "Unknown Initial"),
    BIGGER(1, "bigger", "Bigger than"),
    SMALLER(2, "smaller", "Smaller than"),
    BETWEEN(3, "between", "Between"),
    INCREASED(5, "increased", "Increased", nextOnly = true),
    DECREASED(6, "decreased", "Decreased", nextOnly = true),
    INCREASED_BY(7, "increased_by", "Increased by", nextOnly = true),
    DECREASED_BY(8, "decreased_by", "Decreased by", nextOnly = true),
    CHANGED(9, "changed", "Changed", nextOnly = true),
    UNCHANGED(10, "unchanged", "Unchanged", nextOnly = true)
}

object NativeMemoryScanner {
    val isAvailable: Boolean get() = NativeFastEngine.isNativeAvailable

    @JvmStatic
    private external fun nativeFirstScan(dtype: Int, scanType: Int, val1: String, val2: String, maxReturn: Int): String

    @JvmStatic
    private external fun nativeNextScan(scanType: Int, val1: String, val2: String, maxReturn: Int): String

    @JvmStatic
    private external fun nativeUndoScan(maxReturn: Int): String

    @JvmStatic
    private external fun nativeResetScan()

    @JvmStatic
    private external fun nativeReadValue(addr: Long, dtype: Int, strLen: Int): String

    @JvmStatic
    private external fun nativeWriteValue(addr: Long, dtype: Int, valStr: String): Boolean

    @JvmStatic
    private external fun nativeHexDump(addr: Long, len: Int): String

    @JvmStatic
    private external fun nativeListRegions(): String

    fun firstScan(dtype: MemDataType, scanType: MemScanType, val1: String, val2: String = "", maxReturn: Int = 150): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        return runCatching { nativeFirstScan(nativeType, scanType.code, val1, val2, maxReturn) }
            .getOrElse { "{\"ok\":false,\"error\":\"Native scan error\"}" }
    }

    fun nextScan(scanType: MemScanType, val1: String, val2: String = "", maxReturn: Int = 150): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeNextScan(scanType.code, val1, val2, maxReturn) }
            .getOrElse { "{\"ok\":false,\"error\":\"Native next scan error\"}" }
    }

    fun undoScan(maxReturn: Int = 150): String {
        if (!isAvailable) return "{\"ok\":false,\"error\":\"Native library unavailable\"}"
        return runCatching { nativeUndoScan(maxReturn) }
            .getOrElse { "{\"ok\":false,\"error\":\"Undo failed\"}" }
    }

    fun resetScan() {
        if (isAvailable) runCatching { nativeResetScan() }
    }

    fun readValue(addrHex: String, dtype: MemDataType, strLen: Int = 16): String {
        if (!isAvailable) return "<n/a>"
        val addr = parseHexAddress(addrHex) ?: return "<bad addr>"
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        return runCatching { nativeReadValue(addr, nativeType, strLen) }.getOrDefault("<err>")
    }

    fun writeValue(addrHex: String, dtype: MemDataType, valStr: String): Boolean {
        if (!isAvailable) return false
        val addr = parseHexAddress(addrHex) ?: return false
        val nativeType = if (dtype == MemDataType.AUTO) MemDataType.INT32.code else dtype.code
        return runCatching { nativeWriteValue(addr, nativeType, valStr) }.getOrDefault(false)
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
