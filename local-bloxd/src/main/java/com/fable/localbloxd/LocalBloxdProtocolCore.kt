package com.fable.localbloxd

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Pure Kotlin + JDK protocol, Avro binary codec, MiniJson parser/serializer,
 * Unicode key/username utilities, and CodeMirror 6 collaboration core.
 *
 * Intentionally avoids Android framework dependencies so both Android runtime
 * (minSdk 26+) and pure JVM unit tests (:local-bloxd:testDebugUnitTest) execute
 * the exact same production code without mocked stubs.
 */
object LocalBloxdProtocolCore {
    const val MAX_NAME_CODEPOINTS: Int = 64
    private const val MAX_AVRO_ITEMS: Int = 65536
    private const val MAX_AVRO_BYTES: Int = 4 * 1024 * 1024

    data class CodeDocRecord(
        val code: String,
        val revision: Int,
        val canSeeCode: Boolean = true,
        val canEditCode: Boolean = true,
        val engine: String = "default"
    )

    data class MetricsEnvelope(
        val metricsCookies: Map<String, String>,
        val contents: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MetricsEnvelope) return false
            return metricsCookies == other.metricsCookies && contents.contentEquals(other.contents)
        }

        override fun hashCode(): Int {
            var result = metricsCookies.hashCode()
            result = 31 * result + contents.contentHashCode()
            return result
        }
    }

    data class GetLobbyCodeReq(
        val gameNameWithVariation: String,
        val lobbyName: String,
        val path: String?
    )

    data class GetCodeBlockReq(
        val gameNameWithVariation: String,
        val lobbyName: String,
        val pos: Triple<Int, Int, Int>
    )

    data class CollabPushReq(
        val gameNameWithVariation: String,
        val lobbyName: String,
        val docKey: String,
        val version: Int,
        val updates: String
    )

    data class CollabPullReq(
        val gameNameWithVariation: String,
        val lobbyName: String,
        val docKey: String,
        val fromVersion: Int
    )

    data class SchematicsReq(
        val gameNameWithVariation: String,
        val lobbyName: String,
        val request: String
    )

    data class CmUpdate(
        val clientId: String,
        val changes: List<Any?>
    )

    data class CmApplyResult(
        val code: String,
        val count: Int
    )

    data class CollabPushOutcome(
        val ok: Boolean,
        val accepted: Boolean,
        val conflict: Boolean,
        val error: String?,
        val docKey: String,
        val record: CodeDocRecord,
        val clientId: String
    )

    fun truncateByCodePoints(raw: String, maxCodePoints: Int = MAX_NAME_CODEPOINTS): String {
        if (maxCodePoints <= 0) return ""
        val count = raw.codePointCount(0, raw.length)
        if (count <= maxCodePoints) return raw
        val endOffset = raw.offsetByCodePoints(0, maxCodePoints)
        return raw.substring(0, endOffset)
    }

    private fun isSimpleSafeAsciiChar(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x30..0x39) ||
            (code in 0x41..0x5A) ||
            (code in 0x61..0x7A) ||
            code == 0x5F ||
            code == 0x2D
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Collision-free, filesystem-safe storage key for world/lobby names.
     * Preserves simple keys like "local_world" verbatim for backward compatibility,
     * while hashing any Unicode, spaces, punctuation, or long names deterministically
     * so distinct Unicode worlds never collide.
     */
    fun safeWorldStorageKey(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "default"
        var allSimple = trimmed.length <= 64
        if (allSimple) {
            for (i in 0 until trimmed.length) {
                if (!isSimpleSafeAsciiChar(trimmed[i])) {
                    allSimple = false
                    break
                }
            }
        }
        if (allSimple) return trimmed

        val prefix = StringBuilder(32)
        for (i in 0 until trimmed.length) {
            val ch = trimmed[i]
            if (isSimpleSafeAsciiChar(ch) && prefix.length < 24) {
                prefix.append(ch)
            }
        }
        val hash = sha256Hex(trimmed).substring(0, 20)
        return if (prefix.isNotEmpty()) "${prefix}_h$hash" else "u_$hash"
    }

    fun encodeUrlSegment(raw: String): String {
        val encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8.name())
        return encoded.replace("+", "%20")
    }

    fun decodeUrlSegment(encoded: String): String {
        return runCatching {
            URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(encoded)
    }

    // ---- Chunk 82 Lobby Path & DocKey Validation ----

    fun hasAllowedCodeExtension(filePath: String): Boolean {
        if (filePath.endsWith(".d.ts")) return true
        if (filePath.endsWith(".ts")) return true
        if (filePath.endsWith(".js") &&
            !filePath.endsWith(".min.js") &&
            !filePath.endsWith(".bundle.js")
        ) {
            return true
        }
        return false
    }

    fun isValidLobbyPath(rawPath: String): Boolean {
        if (rawPath.isEmpty() || rawPath != rawPath.trim()) return false
        if (rawPath.length > 64) return false
        if (rawPath.startsWith("/") || rawPath.contains('\\') || rawPath.contains('\u0000')) return false
        if (!hasAllowedCodeExtension(rawPath)) return false
        val segments = rawPath.split('/')
        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") return false
        }
        return true
    }

    fun normalizeLobbyPath(rawPath: String): String? {
        if (rawPath.contains('\u0000')) return null
        val clean = rawPath.replace('\\', '/').trim().trimStart('/')
        if (clean.isEmpty() || clean.length > 64) return null
        val segments = clean.split('/')
        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") return null
        }
        val joined = segments.joinToString("/")
        if (!hasAllowedCodeExtension(joined)) return null
        return joined
    }

    fun validateDocKey(rawKey: String): String? {
        if (rawKey.isEmpty() || rawKey.length > 80) return null
        if (rawKey.startsWith("lobby:")) {
            val norm = normalizeLobbyPath(rawKey.removePrefix("lobby:")) ?: return null
            return "lobby:$norm"
        }
        if (rawKey.startsWith("codeBlock:")) {
            val parts = rawKey.removePrefix("codeBlock:").split(',')
            if (parts.size != 3) return null
            val x = parts[0].trim().toIntOrNull() ?: return null
            val y = parts[1].trim().toIntOrNull() ?: return null
            val z = parts[2].trim().toIntOrNull() ?: return null
            return "codeBlock:$x,$y,$z"
        }
        return null
    }

    // ---- Pure-Kotlin JSON Parser & Serializer ----

    object MiniJson {
        fun parse(text: String): Any? {
            val parser = Parser(text)
            val result = parser.parseValue()
            parser.skipWhitespace()
            if (!parser.eof()) throw IllegalArgumentException("Trailing characters in JSON")
            return result
        }

        fun parseObjectOrNull(text: String): Map<String, Any?>? {
            val v = runCatching { parse(text) }.getOrNull() ?: return null
            @Suppress("UNCHECKED_CAST")
            return v as? Map<String, Any?>
        }

        fun stringify(value: Any?): String {
            val sb = StringBuilder(256)
            writeValue(sb, value)
            return sb.toString()
        }

        private fun writeValue(sb: StringBuilder, value: Any?) {
            when (value) {
                null -> sb.append("null")
                is Boolean -> sb.append(if (value) "true" else "false")
                is Number -> sb.append(value.toString())
                is String -> writeString(sb, value)
                is Map<*, *> -> {
                    sb.append('{')
                    var first = true
                    for ((k, v) in value.entries) {
                        if (!first) sb.append(',')
                        first = false
                        writeString(sb, k.toString())
                        sb.append(':')
                        writeValue(sb, v)
                    }
                    sb.append('}')
                }
                is Iterable<*> -> {
                    sb.append('[')
                    var first = true
                    for (item in value) {
                        if (!first) sb.append(',')
                        first = false
                        writeValue(sb, item)
                    }
                    sb.append(']')
                }
                else -> writeString(sb, value.toString())
            }
        }

        private fun writeString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (i in 0 until s.length) {
                when (val c = s[i]) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (c.code < 0x20) {
                            sb.append("\\u")
                            val hex = c.code.toString(16).padStart(4, '0')
                            sb.append(hex)
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            sb.append('"')
        }

        private class Parser(private val src: String) {
            private var pos: Int = 0

            fun eof(): Boolean = pos >= src.length

            fun skipWhitespace() {
                while (pos < src.length) {
                    val c = src[pos]
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++ else break
                }
            }

            fun parseValue(): Any? {
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
                return when (src[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> {
                        expectLiteral("true")
                        true
                    }
                    'f' -> {
                        expectLiteral("false")
                        false
                    }
                    'n' -> {
                        expectLiteral("null")
                        null
                    }
                    '-', in '0'..'9' -> parseNumber()
                    else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
                }
            }

            private fun expectLiteral(lit: String) {
                if (!src.startsWith(lit, pos)) {
                    throw IllegalArgumentException("Expected '$lit' at $pos")
                }
                pos += lit.length
            }

            private fun parseObject(): Map<String, Any?> {
                pos++ // '{'
                val map = LinkedHashMap<String, Any?>()
                skipWhitespace()
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                while (true) {
                    skipWhitespace()
                    if (pos >= src.length || src[pos] != '"') {
                        throw IllegalArgumentException("Expected string key at $pos")
                    }
                    val key = parseString()
                    skipWhitespace()
                    if (pos >= src.length || src[pos] != ':') {
                        throw IllegalArgumentException("Expected ':' at $pos")
                    }
                    pos++
                    val value = parseValue()
                    map[key] = value
                    skipWhitespace()
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated object")
                    when (src[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return map
                        }
                        else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                    }
                }
            }

            private fun parseArray(): List<Any?> {
                pos++ // '['
                val list = ArrayList<Any?>()
                skipWhitespace()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (true) {
                    list.add(parseValue())
                    skipWhitespace()
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated array")
                    when (src[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return list
                        }
                        else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                    }
                }
            }

            private fun parseString(): String {
                pos++ // '"'
                val sb = StringBuilder()
                while (pos < src.length) {
                    val c = src[pos++]
                    if (c == '"') return sb.toString()
                    if (c == '\\') {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
                        }
                    } else {
                        sb.append(c)
                    }
                }
                throw IllegalArgumentException("Unterminated string")
            }

            private fun parseNumber(): Number {
                val start = pos
                if (src[pos] == '-') pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
                var isFloat = false
                if (pos < src.length && src[pos] == '.') {
                    isFloat = true
                    pos++
                    while (pos < src.length && src[pos] in '0'..'9') pos++
                }
                if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                    isFloat = true
                    pos++
                    if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                    while (pos < src.length && src[pos] in '0'..'9') pos++
                }
                val token = src.substring(start, pos)
                return if (isFloat) token.toDouble() else token.toLong()
            }
        }
    }

    // ---- Apache Avro Binary Codec (strictly bounds-checked) ----

    object AvroBinary {
        class Reader(private val data: ByteArray) {
            var offset: Int = 0
                private set

            fun remaining(): Int = data.size - offset

            fun readZigZagLong(): Long {
                var result = 0L
                var shift = 0
                var count = 0
                while (true) {
                    if (offset >= data.size || count >= 10) {
                        throw IllegalArgumentException("Truncated or overflowing Avro varint")
                    }
                    val b = data[offset++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                    count++
                }
                return (result ushr 1) xor -(result and 1L)
            }

            fun readInt(): Int {
                val v = readZigZagLong()
                if (v < Int.MIN_VALUE.toLong() || v > Int.MAX_VALUE.toLong()) {
                    throw IllegalArgumentException("Avro int out of range: $v")
                }
                return v.toInt()
            }

            fun readBytes(): ByteArray {
                val lenLong = readZigZagLong()
                if (lenLong < 0L || lenLong > MAX_AVRO_BYTES.toLong() || lenLong > remaining().toLong()) {
                    throw IllegalArgumentException("Avro bytes length out of bounds: $lenLong")
                }
                val len = lenLong.toInt()
                val out = data.copyOfRange(offset, offset + len)
                offset += len
                return out
            }

            fun readString(): String {
                return String(readBytes(), StandardCharsets.UTF_8)
            }

            fun readNullableString(): String? {
                return when (val branch = readZigZagLong()) {
                    0L -> null
                    1L -> readString()
                    else -> throw IllegalArgumentException("Invalid union branch: $branch")
                }
            }

            fun readIntArray(): List<Int> {
                val out = ArrayList<Int>()
                while (true) {
                    var count = readZigZagLong()
                    if (count == 0L) break
                    if (count == Long.MIN_VALUE) {
                        throw IllegalArgumentException("Avro array count overflow")
                    }
                    if (count < 0L) {
                        count = -count
                        readZigZagLong() // optional block byte size
                    }
                    if (count < 0L || count > MAX_AVRO_ITEMS.toLong() || out.size + count > MAX_AVRO_ITEMS.toLong()) {
                        throw IllegalArgumentException("Avro array too large")
                    }
                    for (i in 0 until count.toInt()) {
                        out.add(readInt())
                    }
                }
                return out
            }

            fun readStringMap(): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                while (true) {
                    var count = readZigZagLong()
                    if (count == 0L) break
                    if (count == Long.MIN_VALUE) {
                        throw IllegalArgumentException("Avro map count overflow")
                    }
                    if (count < 0L) {
                        count = -count
                        readZigZagLong() // optional block byte size
                    }
                    if (count < 0L || count > MAX_AVRO_ITEMS.toLong() || out.size + count > MAX_AVRO_ITEMS.toLong()) {
                        throw IllegalArgumentException("Avro map too large")
                    }
                    for (i in 0 until count.toInt()) {
                        val k = readString()
                        val v = readString()
                        out[k] = v
                    }
                }
                return out
            }
        }

        class Writer {
            private val buf = ByteArrayOutputStream(128)

            fun writeZigZagLong(v: Long) {
                var u = (v shl 1) xor (v shr 63)
                while ((u and 0x7FL.inv()) != 0L) {
                    buf.write(((u and 0x7FL) or 0x80L).toInt())
                    u = u ushr 7
                }
                buf.write((u and 0x7FL).toInt())
            }

            fun writeInt(v: Int) = writeZigZagLong(v.toLong())

            fun writeBytes(bytes: ByteArray) {
                writeZigZagLong(bytes.size.toLong())
                buf.write(bytes)
            }

            fun writeString(s: String) {
                writeBytes(s.toByteArray(StandardCharsets.UTF_8))
            }

            fun writeNullableString(s: String?) {
                if (s == null) {
                    writeZigZagLong(0L)
                } else {
                    writeZigZagLong(1L)
                    writeString(s)
                }
            }

            fun writeIntArray(values: List<Int>) {
                if (values.isNotEmpty()) {
                    writeZigZagLong(values.size.toLong())
                    for (v in values) writeInt(v)
                }
                writeZigZagLong(0L)
            }

            fun writeStringMap(map: Map<String, String>) {
                if (map.isNotEmpty()) {
                    writeZigZagLong(map.size.toLong())
                    for ((k, v) in map.entries) {
                        writeString(k)
                        writeString(v)
                    }
                }
                writeZigZagLong(0L)
            }

            fun toByteArray(): ByteArray = buf.toByteArray()
        }

        fun encodeMetricsEnvelope(env: MetricsEnvelope): ByteArray {
            val w = Writer()
            w.writeStringMap(env.metricsCookies)
            w.writeBytes(env.contents)
            return w.toByteArray()
        }

        fun decodeMetricsEnvelope(bytes: ByteArray): MetricsEnvelope? {
            return runCatching {
                val r = Reader(bytes)
                val cookies = r.readStringMap()
                val contents = r.readBytes()
                if (r.remaining() != 0) return null
                MetricsEnvelope(cookies, contents)
            }.getOrNull()
        }

        fun encodeGetLobbyCode(req: GetLobbyCodeReq): ByteArray {
            val w = Writer()
            w.writeString(req.gameNameWithVariation)
            w.writeString(req.lobbyName)
            w.writeNullableString(req.path)
            return w.toByteArray()
        }

        fun decodeGetLobbyCode(bytes: ByteArray): GetLobbyCodeReq? {
            return runCatching {
                val r = Reader(bytes)
                val game = r.readString()
                val lobby = r.readString()
                val path = r.readNullableString()
                if (r.remaining() != 0) return null
                GetLobbyCodeReq(game, lobby, path)
            }.getOrNull()
        }

        fun encodeGetCodeBlock(req: GetCodeBlockReq): ByteArray {
            val w = Writer()
            w.writeString(req.gameNameWithVariation)
            w.writeString(req.lobbyName)
            w.writeIntArray(listOf(req.pos.first, req.pos.second, req.pos.third))
            return w.toByteArray()
        }

        fun decodeGetCodeBlock(bytes: ByteArray): GetCodeBlockReq? {
            return runCatching {
                val r = Reader(bytes)
                val game = r.readString()
                val lobby = r.readString()
                val posArr = r.readIntArray()
                if (posArr.size != 3 || r.remaining() != 0) return null
                GetCodeBlockReq(game, lobby, Triple(posArr[0], posArr[1], posArr[2]))
            }.getOrNull()
        }

        fun encodeCollabPush(req: CollabPushReq): ByteArray {
            val w = Writer()
            w.writeString(req.gameNameWithVariation)
            w.writeString(req.lobbyName)
            w.writeString(req.docKey)
            w.writeInt(req.version)
            w.writeString(req.updates)
            return w.toByteArray()
        }

        fun decodeCollabPush(bytes: ByteArray): CollabPushReq? {
            return runCatching {
                val r = Reader(bytes)
                val game = r.readString()
                val lobby = r.readString()
                val docKey = r.readString()
                val version = r.readInt()
                val updates = r.readString()
                if (r.remaining() != 0) return null
                CollabPushReq(game, lobby, docKey, version, updates)
            }.getOrNull()
        }

        fun encodeCollabPull(req: CollabPullReq): ByteArray {
            val w = Writer()
            w.writeString(req.gameNameWithVariation)
            w.writeString(req.lobbyName)
            w.writeString(req.docKey)
            w.writeInt(req.fromVersion)
            return w.toByteArray()
        }

        fun decodeCollabPull(bytes: ByteArray): CollabPullReq? {
            return runCatching {
                val r = Reader(bytes)
                val game = r.readString()
                val lobby = r.readString()
                val docKey = r.readString()
                val fromVersion = r.readInt()
                if (r.remaining() != 0) return null
                CollabPullReq(game, lobby, docKey, fromVersion)
            }.getOrNull()
        }

        fun encodeSchematics(req: SchematicsReq): ByteArray {
            val w = Writer()
            w.writeString(req.gameNameWithVariation)
            w.writeString(req.lobbyName)
            w.writeString(req.request)
            return w.toByteArray()
        }

        fun decodeSchematics(bytes: ByteArray): SchematicsReq? {
            return runCatching {
                val r = Reader(bytes)
                val game = r.readString()
                val lobby = r.readString()
                val request = r.readString()
                if (r.remaining() != 0) return null
                SchematicsReq(game, lobby, request)
            }.getOrNull()
        }
    }

    /**
     * Unwraps captured HTTP POST payloads sent by Module 934 / Module 1386:
     *  - JSON `{ metricsCookies, contents: { type: "Buffer", data: [ ... ] } }`
     *  - JSON `{ metricsCookies, contents: "<base64>" }`
     *  - Base64-encoded Avro `Dy` envelope `{ metricsCookies, contents: bytes }`
     *  - Raw binary Avro `Dy` envelope or direct inner Avro bytes
     */
    fun extractBinaryContents(rawPostBytes: ByteArray): ByteArray? {
        if (rawPostBytes.isEmpty()) return null
        val text = runCatching { String(rawPostBytes, StandardCharsets.UTF_8).trim() }.getOrNull()
        if (text != null && text.startsWith("{")) {
            val obj = MiniJson.parseObjectOrNull(text)
            if (obj != null) {
                val contents = obj["contents"]
                extractBytesFromJsonValue(contents)?.let { return it }
            }
        }
        // Check if UTF-8 text is a base64-encoded Dy binary envelope (as sent by Module 934 postBinary)
        if (text != null && text.length >= 4 && text.length % 4 == 0 && !text.startsWith("{") && isStrictBase64(text)) {
            val b64Bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            if (b64Bytes != null && b64Bytes.isNotEmpty()) {
                AvroBinary.decodeMetricsEnvelope(b64Bytes)?.let { return it.contents }
                return b64Bytes
            }
        }
        AvroBinary.decodeMetricsEnvelope(rawPostBytes)?.let { return it.contents }
        return rawPostBytes
    }

    private fun isStrictBase64(s: String): Boolean {
        for (i in 0 until s.length) {
            val c = s[i]
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '='
            if (!ok) return false
        }
        return true
    }

    private fun extractBytesFromJsonValue(value: Any?): ByteArray? {
        when (value) {
            is String -> {
                return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            }
            is Map<*, *> -> {
                val dataField = value["data"]
                if (dataField is List<*>) {
                    val out = ByteArray(dataField.size)
                    for (i in dataField.indices) {
                        val n = (dataField[i] as? Number)?.toInt() ?: return null
                        out[i] = (n and 0xFF).toByte()
                    }
                    return out
                }
                // Numeric-keyed Uint8Array JSON serialization: {"0":12,"1":34,...}
                if (value.isNotEmpty() && value.containsKey("0")) {
                    val out = ByteArray(value.size)
                    for (i in 0 until value.size) {
                        val n = (value[i.toString()] as? Number)?.toInt() ?: return null
                        out[i] = (n and 0xFF).toByte()
                    }
                    return out
                }
            }
            is List<*> -> {
                val out = ByteArray(value.size)
                for (i in value.indices) {
                    val n = (value[i] as? Number)?.toInt() ?: return null
                    out[i] = (n and 0xFF).toByte()
                }
                return out
            }
        }
        return null
    }

    fun extractJsonContentsObject(rawPostBytes: ByteArray?): Map<String, Any?>? {
        if (rawPostBytes == null || rawPostBytes.isEmpty()) return null
        val text = runCatching { String(rawPostBytes, StandardCharsets.UTF_8).trim() }.getOrNull() ?: return null
        if (!text.startsWith("{")) return null
        val root = MiniJson.parseObjectOrNull(text) ?: return null
        @Suppress("UNCHECKED_CAST")
        val nested = root["contents"] as? Map<String, Any?>
        return nested ?: root
    }

    // ---- CodeMirror 6 ChangeSet.toJSON() Applier (UTF-16 code-unit exact) ----

    private fun requireSafeNonNegInt(num: Number, label: String): Int {
        val d = num.toDouble()
        if (!d.isFinite() || d < 0.0 || d > Int.MAX_VALUE.toDouble() || d != kotlin.math.floor(d)) {
            throw IllegalArgumentException("$label must be a valid non-negative integer")
        }
        return num.toLong().toInt()
    }

    fun applyCodeMirrorChangeSet(docText: String, changes: List<Any?>): String {
        var pos = 0
        val out = StringBuilder(docText.length + 32)
        for (item in changes) {
            when (item) {
                is Number -> {
                    val retain = requireSafeNonNegInt(item, "CodeMirror retain count")
                    if (retain > docText.length - pos) {
                        throw IllegalArgumentException("CodeMirror retain count out of bounds")
                    }
                    out.append(docText, pos, pos + retain)
                    pos += retain
                }
                is List<*> -> {
                    if (item.isEmpty()) throw IllegalArgumentException("Empty CodeMirror replace entry")
                    val delNum = item[0] as? Number
                        ?: throw IllegalArgumentException("CodeMirror delete length must be integer")
                    val delLen = requireSafeNonNegInt(delNum, "CodeMirror delete length")
                    if (delLen > docText.length - pos) {
                        throw IllegalArgumentException("CodeMirror delete length out of bounds")
                    }
                    pos += delLen
                    if (item.size > 1) {
                        for (j in 1 until item.size) {
                            if (j > 1) out.append('\n')
                            val line = item[j] as? String
                                ?: throw IllegalArgumentException("CodeMirror inserted line must be string")
                            out.append(line)
                        }
                    }
                }
                else -> throw IllegalArgumentException("Malformed CodeMirror ChangeSet entry")
            }
        }
        if (pos != docText.length) {
            throw IllegalArgumentException("CodeMirror ChangeSet length mismatch: consumed $pos of ${docText.length}")
        }
        return out.toString()
    }

    fun tryParseCodeMirrorUpdates(rawUpdates: String): List<CmUpdate>? {
        val trimmed = rawUpdates.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val parsed = runCatching { MiniJson.parse(trimmed) }.getOrNull() as? List<*> ?: return null
        if (parsed.isEmpty()) return null
        val out = ArrayList<CmUpdate>(parsed.size)
        for (entry in parsed) {
            val map = entry as? Map<*, *> ?: return null
            val changes = map["changes"] as? List<*> ?: return null
            val cid = (map["clientID"] as? String)?.takeIf { it.isNotEmpty() } ?: "local_client"
            @Suppress("UNCHECKED_CAST")
            out.add(CmUpdate(cid, changes as List<Any?>))
        }
        return out
    }

    fun applyCodeMirrorUpdates(
        docText: String,
        updates: List<CmUpdate>,
        fallbackClientId: String = "local_client"
    ): CmApplyResult {
        var cur = docText
        for (u in updates) {
            cur = applyCodeMirrorChangeSet(cur, u.changes)
        }
        return CmApplyResult(cur, updates.size)
    }

    // ---- Collaboration State Transitions & Response Builders ----

    fun applyCollabPush(
        docs: MutableMap<String, CodeDocRecord>,
        rawDocKey: String,
        baseRevision: Int?,
        rawUpdates: String?,
        directCode: String?,
        clientId: String = "local_client"
    ): CollabPushOutcome {
        val docKey = validateDocKey(rawDocKey)
            ?: return CollabPushOutcome(
                ok = false,
                accepted = false,
                conflict = false,
                error = "Invalid docKey",
                docKey = rawDocKey,
                record = CodeDocRecord("", 0),
                clientId = clientId
            )

        val current = docs[docKey] ?: CodeDocRecord(
            code = if (docKey == "lobby:main.js") "// Local lobby script\n" else "",
            revision = 0,
            canSeeCode = true,
            canEditCode = true,
            engine = "default"
        )
        if (!current.canSeeCode || !current.canEditCode) {
            return CollabPushOutcome(
                ok = false,
                accepted = false,
                conflict = false,
                error = "Permission denied: read-only document",
                docKey = docKey,
                record = current,
                clientId = clientId
            )
        }
        if (baseRevision != null && baseRevision != current.revision) {
            return CollabPushOutcome(
                ok = false,
                accepted = false,
                conflict = true,
                error = "Stale revision rejected",
                docKey = docKey,
                record = current,
                clientId = clientId
            )
        }

        val updated: CodeDocRecord = if (rawUpdates != null) {
            val cmUpdates = tryParseCodeMirrorUpdates(rawUpdates)
            if (cmUpdates != null) {
                val applied = runCatching {
                    applyCodeMirrorUpdates(current.code, cmUpdates, clientId)
                }.getOrElse { err ->
                    return CollabPushOutcome(
                        ok = false,
                        accepted = false,
                        conflict = false,
                        error = err.message ?: "Invalid ChangeSet",
                        docKey = docKey,
                        record = current,
                        clientId = clientId
                    )
                }
                current.copy(code = applied.code, revision = current.revision + applied.count)
            } else {
                // Reject non-CodeMirror malformed update strings when directCode is absent
                return CollabPushOutcome(
                    ok = false,
                    accepted = false,
                    conflict = false,
                    error = "Malformed CodeMirror updates payload",
                    docKey = docKey,
                    record = current,
                    clientId = clientId
                )
            }
        } else {
            current.copy(code = directCode ?: "", revision = current.revision + 1)
        }

        docs[docKey] = updated
        return CollabPushOutcome(
            ok = true,
            accepted = true,
            conflict = false,
            error = null,
            docKey = docKey,
            record = updated,
            clientId = clientId
        )
    }

    fun buildLobbyCodeResponseJson(
        docs: Map<String, CodeDocRecord>,
        requestedPath: String?
    ): String {
        val activePath = when {
            !requestedPath.isNullOrBlank() -> normalizeLobbyPath(requestedPath) ?: "main.js"
            else -> "main.js"
        }
        val activeKey = "lobby:$activePath"
        val target = docs[activeKey] ?: CodeDocRecord(
            code = if (activePath == "main.js") "// Local lobby script\n" else "",
            revision = 0,
            canSeeCode = true,
            canEditCode = true,
            engine = "default"
        )
        if (!target.canSeeCode) {
            val denied = LinkedHashMap<String, Any?>()
            denied["ok"] = false
            denied["error"] = "Permission denied: hidden code"
            denied["canSeeCode"] = false
            denied["canEditCode"] = false
            denied["code"] = null
            denied["collabVersion"] = target.revision
            denied["revision"] = target.revision
            return MiniJson.stringify(denied)
        }
        val filesMap = LinkedHashMap<String, Any?>()
        var hasAnyLobby = false
        for ((k, rec) in docs.entries) {
            if (k.startsWith("lobby:")) {
                hasAnyLobby = true
                if (rec.canSeeCode) {
                    filesMap[k.removePrefix("lobby:")] = rec.code
                }
            }
        }
        if (!hasAnyLobby) {
            filesMap["main.js"] = target.code
        }
        val res = LinkedHashMap<String, Any?>()
        res["ok"] = true
        res["docKey"] = activeKey
        res["canSeeCode"] = true
        res["canEditCode"] = target.canEditCode
        res["code"] = target.code
        res["collabVersion"] = target.revision
        res["revision"] = target.revision
        res["files"] = filesMap
        return MiniJson.stringify(res)
    }

    fun buildCodeBlockResponseJson(
        docs: Map<String, CodeDocRecord>,
        docKey: String
    ): String {
        val target = docs[docKey] ?: CodeDocRecord("", 0, canSeeCode = true, canEditCode = true)
        if (!target.canSeeCode) {
            val denied = LinkedHashMap<String, Any?>()
            denied["ok"] = false
            denied["error"] = "Permission denied: hidden code"
            denied["docKey"] = docKey
            denied["canSeeCode"] = false
            denied["canEditCode"] = false
            denied["code"] = null
            denied["collabVersion"] = target.revision
            denied["revision"] = target.revision
            return MiniJson.stringify(denied)
        }
        val res = LinkedHashMap<String, Any?>()
        res["ok"] = true
        res["docKey"] = docKey
        res["canSeeCode"] = true
        res["canEditCode"] = target.canEditCode
        res["code"] = target.code
        res["collabVersion"] = target.revision
        res["revision"] = target.revision
        return MiniJson.stringify(res)
    }

    fun buildCollabPullResponseJson(
        docs: Map<String, CodeDocRecord>,
        rawDocKey: String,
        clientId: String = "local_client_1"
    ): String {
        val docKey = validateDocKey(rawDocKey)
        if (docKey == null) {
            val err = LinkedHashMap<String, Any?>()
            err["ok"] = false
            err["error"] = "Invalid docKey"
            return MiniJson.stringify(err)
        }
        val target = docs[docKey] ?: CodeDocRecord(
            code = if (docKey == "lobby:main.js") "// Local lobby script\n" else "",
            revision = 0,
            canSeeCode = true,
            canEditCode = true
        )
        if (!target.canSeeCode) {
            val denied = LinkedHashMap<String, Any?>()
            denied["ok"] = false
            denied["error"] = "Permission denied: hidden code"
            denied["docKey"] = docKey
            denied["canSeeCode"] = false
            denied["canEditCode"] = false
            denied["code"] = null
            denied["revision"] = target.revision
            denied["version"] = target.revision
            denied["collabVersion"] = target.revision
            return MiniJson.stringify(denied)
        }
        val res = LinkedHashMap<String, Any?>()
        res["ok"] = true
        res["docKey"] = docKey
        res["canSeeCode"] = true
        res["canEditCode"] = target.canEditCode
        res["code"] = target.code
        res["revision"] = target.revision
        res["version"] = target.revision
        res["collabVersion"] = target.revision
        res["updates"] = emptyList<Any>()
        res["clientID"] = clientId
        return MiniJson.stringify(res)
    }

    fun buildCollabPushResponseJson(outcome: CollabPushOutcome): String {
        val res = LinkedHashMap<String, Any?>()
        res["ok"] = outcome.ok
        res["accepted"] = outcome.accepted
        if (outcome.conflict) res["conflict"] = true
        if (outcome.error != null) res["error"] = outcome.error
        res["docKey"] = outcome.docKey
        if (outcome.record.canSeeCode) {
            res["code"] = outcome.record.code
        }
        res["revision"] = outcome.record.revision
        res["version"] = outcome.record.revision
        res["collabVersion"] = outcome.record.revision
        res["clientID"] = outcome.clientId
        return MiniJson.stringify(res)
    }

    fun handleSchematicsRequest(requestJson: String?): Pair<Int, String> {
        val parsed = requestJson?.let { MiniJson.parseObjectOrNull(it) }
        val action = (parsed?.get("action") as? String)?.lowercase() ?: "state"
        if (action == "state") {
            return Pair(200, "{\"assets\":{},\"revision\":0}")
        }
        val err = LinkedHashMap<String, Any?>()
        err["errorMessageForUser"] = "Schematics '$action' is not supported in offline mode."
        err["assets"] = emptyMap<String, Any?>()
        err["revision"] = 0
        return Pair(435, MiniJson.stringify(err))
    }
}
