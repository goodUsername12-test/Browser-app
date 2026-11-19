package com.fable.localbloxd

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object NativeBloxdBridge {
    private const val TAG = "LocalBloxdNative"
    private const val MAX_NAME_CODEPOINTS = LocalBloxdProtocolCore.MAX_NAME_CODEPOINTS
    private const val MAX_STAGED_POST_BYTES = 4 * 1024 * 1024
    private const val MAX_STAGED_POST_ENTRIES = 64
    private const val STAGED_POST_TTL_MS = 60_000L
    private const val MAX_COLLAB_HISTORY_PER_DOC = 256

    private data class StagedPostBody(
        val bytes: ByteArray,
        val createdAtMs: Long
    )

    private data class StoredCollabUpdate(
        val fromRevision: Int,
        val toRevision: Int,
        val clientId: String,
        val changes: List<Any?>
    )

    val hasCpp: Boolean = runCatching {
        System.loadLibrary("local_bloxd_native")
        true
    }.onFailure { Log.w(TAG, "Cpp load skipped: ${it.message}") }.getOrDefault(false)

    val hasRust: Boolean = runCatching {
        System.loadLibrary("local_bloxd_rust")
        true
    }.onFailure { Log.w(TAG, "Rust load skipped: ${it.message}") }.getOrDefault(false)

    @Volatile
    var requestedMode: String = "classic"

    @Volatile
    var requestedLobby: String = "local_world"

    @Volatile
    var persistedPlayerName: String = ""

    @Volatile
    var activeCodeLobby: String = "local_world"

    @Volatile
    var activeCodeDocKey: String = "lobby:main.js"

    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LocalBloxdSaveIO").apply { isDaemon = true }
    }

    private val stagedPostCounter = AtomicLong(0L)
    private val stagedPostBodies = ConcurrentHashMap<String, StagedPostBody>()

    private val worldBlocks = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val worldMeta = ConcurrentHashMap<String, String>()
    private val worldCodeDocs = ConcurrentHashMap<String, ConcurrentHashMap<String, LocalBloxdProtocolCore.CodeDocRecord>>()
    private val worldCodeHistory = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableList<StoredCollabUpdate>>>()
    private val dirtyBlockWorlds = ConcurrentHashMap<String, AtomicBoolean>()
    private val dirtyMetaWorlds = ConcurrentHashMap<String, AtomicBoolean>()
    private val dirtyCodeWorlds = ConcurrentHashMap<String, AtomicBoolean>()
    private val dirtyProfile = AtomicBoolean(false)

    @Volatile
    private var initializedFromDisk = false

    @JvmStatic external fun nativeGenerateChunk32Rle(cx: Int, cy: Int, cz: Int, seed: Int, modeId: Int): ByteArray?
    @JvmStatic external fun nativeGenerateSubchunk(cx: Int, cy: Int, cz: Int, seed: Int, modeId: Int): ByteArray?
    @JvmStatic external fun nativeGetSurfaceHeight(wx: Int, wz: Int, seed: Int, modeId: Int): Int
    @JvmStatic external fun nativeBoostThreads()
    @JvmStatic external fun nativeRelaxThreads()

    @JvmStatic external fun nativeRustVersion(): Int
    @JvmStatic external fun nativeRustPutBlock(x: Int, y: Int, z: Int, blockId: Int): Int
    @JvmStatic external fun nativeRustGetBlock(x: Int, y: Int, z: Int): Int
    @JvmStatic external fun nativeRustClearBlocks(): Int
    @JvmStatic external fun nativeRustSaveCodeBlock(x: Int, y: Int, z: Int, code: String): Int
    @JvmStatic external fun nativeRustExportWorldJson(): String?
    @JvmStatic external fun nativeRustLoadWorldEdits(flat: String): Int

    fun truncateByCodePoints(raw: String, maxCodePoints: Int = MAX_NAME_CODEPOINTS): String =
        LocalBloxdProtocolCore.truncateByCodePoints(raw, maxCodePoints)

    fun safeKey(raw: String): String = LocalBloxdProtocolCore.safeWorldStorageKey(raw)

    fun stagePostBody(bodyUtf8: String?): String {
        if (bodyUtf8 == null) return ""
        val bytes = bodyUtf8.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_STAGED_POST_BYTES) return ""
        val now = System.currentTimeMillis()
        if (stagedPostBodies.size >= MAX_STAGED_POST_ENTRIES) {
            val iter = stagedPostBodies.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value.createdAtMs > STAGED_POST_TTL_MS) {
                    iter.remove()
                }
            }
            if (stagedPostBodies.size >= MAX_STAGED_POST_ENTRIES) {
                stagedPostBodies.entries.minByOrNull { it.value.createdAtMs }?.key?.let {
                    stagedPostBodies.remove(it)
                }
            }
        }
        val token = "p${stagedPostCounter.incrementAndGet()}"
        stagedPostBodies[token] = StagedPostBody(bytes, now)
        return token
    }

    fun consumeStagedPostBody(token: String?): ByteArray? {
        if (token.isNullOrBlank()) return null
        val entry = stagedPostBodies.remove(token.trim()) ?: return null
        if (System.currentTimeMillis() - entry.createdAtMs > STAGED_POST_TTL_MS) {
            return null
        }
        return entry.bytes
    }

    fun updatePlayerName(ctx: Context, rawName: String?): String? {
        if (rawName == null) return null
        val truncated = truncateByCodePoints(rawName, MAX_NAME_CODEPOINTS)
        if (truncated.isEmpty()) return null
        persistedPlayerName = truncated
        scheduleProfilePersist(ctx)
        return truncated
    }

    fun boostAsync() {
        if (!hasCpp) return
        ioExecutor.execute { runCatching { nativeBoostThreads() } }
    }

    fun relaxAsync() {
        if (!hasCpp) return
        ioExecutor.execute { runCatching { nativeRelaxThreads() } }
    }

    fun flushNow(ctx: Context) {
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            for ((wKey, flag) in dirtyBlockWorlds.entries) {
                if (flag.compareAndSet(true, false)) {
                    writeBlocksFile(appCtx, wKey)
                }
            }
            for ((wKey, flag) in dirtyMetaWorlds.entries) {
                if (flag.compareAndSet(true, false)) {
                    worldMeta[wKey]?.let { writeMetaFile(appCtx, wKey, it) }
                }
            }
            for ((wKey, flag) in dirtyCodeWorlds.entries) {
                if (flag.compareAndSet(true, false)) {
                    writeCodeFile(appCtx, wKey)
                }
            }
            if (dirtyProfile.compareAndSet(true, false)) {
                writeProfileFile(appCtx)
            }
        }
    }

    private fun worldDir(ctx: Context): File = File(ctx.filesDir, "bloxd_local_worlds").apply { mkdirs() }

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (initializedFromDisk) return
        initializedFromDisk = true
        val dir = worldDir(ctx)
        dir.listFiles()?.forEach { f ->
            runCatching {
                when {
                    f.name == "player_profile.json" -> {
                        val obj = JSONObject(f.readText())
                        persistedPlayerName = truncateByCodePoints(obj.optString("playerName", ""))
                    }
                    f.name.startsWith("blocks_") && f.name.endsWith(".txt") -> {
                        val wKey = f.name.removePrefix("blocks_").removeSuffix(".txt")
                        val map = ConcurrentHashMap<String, Int>(1024)
                        val flat = f.readText()
                        for (rec in flat.split(';')) {
                            if (rec.isBlank()) continue
                            val p = rec.split(',')
                            if (p.size == 4) {
                                val x = p[0].toIntOrNull() ?: continue
                                val y = p[1].toIntOrNull() ?: continue
                                val z = p[2].toIntOrNull() ?: continue
                                val id = p[3].toIntOrNull() ?: continue
                                map["$x,$y,$z"] = id
                            }
                        }
                        worldBlocks[wKey] = map
                        if (wKey == "local_world" && hasRust) {
                            nativeRustLoadWorldEdits(flat)
                        }
                    }
                    f.name.startsWith("meta_") && f.name.endsWith(".json") -> {
                        val wKey = f.name.removePrefix("meta_").removeSuffix(".json")
                        worldMeta[wKey] = f.readText()
                    }
                    f.name.startsWith("code_") && f.name.endsWith(".json") -> {
                        val wKey = f.name.removePrefix("code_").removeSuffix(".json")
                        val obj = JSONObject(f.readText())
                        val map = ConcurrentHashMap<String, LocalBloxdProtocolCore.CodeDocRecord>(16)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val entry = obj.optJSONObject(k)
                            if (entry != null) {
                                val rev = if (entry.has("revision")) {
                                    entry.optInt("revision", 0)
                                } else {
                                    entry.optInt("version", 0)
                                }
                                map[k] = LocalBloxdProtocolCore.CodeDocRecord(
                                    code = entry.optString("code", ""),
                                    revision = rev.coerceAtLeast(0),
                                    canSeeCode = entry.optBoolean("canSeeCode", true),
                                    canEditCode = entry.optBoolean("canEditCode", true),
                                    engine = entry.optString("engine", "default").ifBlank { "default" }
                                )
                            }
                        }
                        worldCodeDocs[wKey] = map
                    }
                }
            }
        }
    }

    private fun writeProfileFile(ctx: Context) {
        runCatching {
            val dir = worldDir(ctx)
            val tmp = File(dir, "player_profile.json.tmp")
            val dst = File(dir, "player_profile.json")
            val obj = JSONObject().put("playerName", persistedPlayerName)
            tmp.writeText(obj.toString())
            tmp.renameTo(dst)
        }
    }

    private fun writeBlocksFile(ctx: Context, wKey: String) {
        runCatching {
            val map = worldBlocks[wKey] ?: return
            val sb = StringBuilder(map.size * 16)
            for ((coord, id) in map.entries) {
                sb.append(coord).append(',').append(id).append(';')
            }
            val dir = worldDir(ctx)
            val tmp = File(dir, "blocks_${wKey}.txt.tmp")
            val dst = File(dir, "blocks_${wKey}.txt")
            tmp.writeText(sb.toString())
            tmp.renameTo(dst)
        }
    }

    private fun writeMetaFile(ctx: Context, wKey: String, json: String) {
        runCatching {
            val dir = worldDir(ctx)
            val tmp = File(dir, "meta_${wKey}.json.tmp")
            val dst = File(dir, "meta_${wKey}.json")
            tmp.writeText(json)
            tmp.renameTo(dst)
        }
    }

    private fun writeCodeFile(ctx: Context, wKey: String) {
        runCatching {
            val map = worldCodeDocs[wKey] ?: return
            val obj = JSONObject()
            for ((docKey, rec) in map.entries) {
                obj.put(
                    docKey,
                    JSONObject()
                        .put("code", rec.code)
                        .put("revision", rec.revision)
                        .put("version", rec.revision)
                        .put("canSeeCode", rec.canSeeCode)
                        .put("canEditCode", rec.canEditCode)
                        .put("engine", rec.engine)
                )
            }
            val dir = worldDir(ctx)
            val tmp = File(dir, "code_${wKey}.json.tmp")
            val dst = File(dir, "code_${wKey}.json")
            tmp.writeText(obj.toString())
            tmp.renameTo(dst)
        }
    }

    private fun scheduleProfilePersist(ctx: Context) {
        if (dirtyProfile.compareAndSet(false, true)) {
            val appCtx = ctx.applicationContext
            ioExecutor.schedule({
                if (dirtyProfile.compareAndSet(true, false)) {
                    writeProfileFile(appCtx)
                }
            }, 200L, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleBlocksPersist(ctx: Context, wKey: String) {
        val flag = dirtyBlockWorlds.getOrPut(wKey) { AtomicBoolean(false) }
        if (flag.compareAndSet(false, true)) {
            val appCtx = ctx.applicationContext
            ioExecutor.schedule({
                if (flag.compareAndSet(true, false)) {
                    writeBlocksFile(appCtx, wKey)
                }
            }, 250L, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleMetaPersist(ctx: Context, wKey: String) {
        val flag = dirtyMetaWorlds.getOrPut(wKey) { AtomicBoolean(false) }
        if (flag.compareAndSet(false, true)) {
            val appCtx = ctx.applicationContext
            ioExecutor.schedule({
                if (flag.compareAndSet(true, false)) {
                    worldMeta[wKey]?.let { writeMetaFile(appCtx, wKey, it) }
                }
            }, 300L, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleCodePersist(ctx: Context, wKey: String) {
        val flag = dirtyCodeWorlds.getOrPut(wKey) { AtomicBoolean(false) }
        if (flag.compareAndSet(false, true)) {
            val appCtx = ctx.applicationContext
            ioExecutor.schedule({
                if (flag.compareAndSet(true, false)) {
                    writeCodeFile(appCtx, wKey)
                }
            }, 250L, TimeUnit.MILLISECONDS)
        }
    }

    private fun syncCodeBlockToRustIfNeeded(docKey: String, code: String) {
        if (docKey.startsWith("codeBlock:") && hasRust) {
            val parts = docKey.removePrefix("codeBlock:").split(',')
            if (parts.size == 3) {
                val x = parts[0].trim().toIntOrNull()
                val y = parts[1].trim().toIntOrNull()
                val z = parts[2].trim().toIntOrNull()
                if (x != null && y != null && z != null) {
                    runCatching { nativeRustSaveCodeBlock(x, y, z, code) }
                }
            }
        }
    }

    private fun parseCodeBlockPos(rawPos: Any?): Triple<Int, Int, Int>? {
        when (rawPos) {
            is List<*> -> {
                if (rawPos.size == 3) {
                    val x = (rawPos[0] as? Number)?.toInt() ?: return null
                    val y = (rawPos[1] as? Number)?.toInt() ?: return null
                    val z = (rawPos[2] as? Number)?.toInt() ?: return null
                    return Triple(x, y, z)
                }
            }
            is String -> {
                val clean = rawPos.removePrefix("codeBlock:").trim()
                val parts = clean.split(',')
                if (parts.size == 3) {
                    val x = parts[0].trim().toIntOrNull() ?: return null
                    val y = parts[1].trim().toIntOrNull() ?: return null
                    val z = parts[2].trim().toIntOrNull() ?: return null
                    return Triple(x, y, z)
                }
            }
        }
        return null
    }

    fun handleNameUpdateHttp(
        ctx: Context,
        postBodyBytes: ByteArray?,
        queryName: String?
    ): Pair<Int, String> {
        ensureLoaded(ctx)
        val obj = LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        val bodyName = obj?.get("name") as? String
        val candidate = bodyName ?: queryName
        if (candidate != null) {
            if (candidate.isEmpty()) {
                return Pair(200, "{\"err\":\"Name cannot be empty\"}")
            }
            val saved = updatePlayerName(ctx, candidate)
            if (saved != null) {
                val res = LinkedHashMap<String, Any?>()
                res["nameExists"] = false
                res["name"] = saved
                return Pair(200, LocalBloxdProtocolCore.MiniJson.stringify(res))
            }
        }
        return Pair(200, "{\"nameExists\":false}")
    }

    fun handleGetLobbyCodeHttp(
        ctx: Context,
        postBodyBytes: ByteArray?,
        queryPath: String?
    ): Pair<Int, String> {
        ensureLoaded(ctx)
        val bin = postBodyBytes?.let { LocalBloxdProtocolCore.extractBinaryContents(it) }
        val avroReq = bin?.let { LocalBloxdProtocolCore.AvroBinary.decodeGetLobbyCode(it) }
        val jsonObj = if (avroReq == null) {
            LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        } else {
            null
        }
        val lobbyName = avroReq?.lobbyName?.takeIf { it.isNotBlank() }
            ?: (jsonObj?.get("lobbyName") as? String)?.takeIf { it.isNotBlank() }
            ?: activeCodeLobby.ifBlank { requestedLobby }
        val requestedPath = avroReq?.path
            ?: (jsonObj?.get("path") as? String)
            ?: queryPath
            ?: if (activeCodeDocKey.startsWith("lobby:")) activeCodeDocKey.removePrefix("lobby:") else null

        if (!requestedPath.isNullOrBlank() && LocalBloxdProtocolCore.normalizeLobbyPath(requestedPath) == null) {
            return Pair(400, "{\"ok\":false,\"error\":\"Invalid lobby path\"}")
        }

        activeCodeLobby = lobbyName
        val wKey = safeKey(lobbyName)
        val docs = worldCodeDocs.getOrPut(wKey) { ConcurrentHashMap() }
        val json = synchronized(docs) {
            LocalBloxdProtocolCore.buildLobbyCodeResponseJson(docs, requestedPath)
        }
        return Pair(200, json)
    }

    fun handleGetCodeBlockHttp(
        ctx: Context,
        postBodyBytes: ByteArray?,
        queryPos: String?
    ): Pair<Int, String> {
        ensureLoaded(ctx)
        val bin = postBodyBytes?.let { LocalBloxdProtocolCore.extractBinaryContents(it) }
        val avroReq = bin?.let { LocalBloxdProtocolCore.AvroBinary.decodeGetCodeBlock(it) }
        val jsonObj = if (avroReq == null) {
            LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        } else {
            null
        }
        val lobbyName = avroReq?.lobbyName?.takeIf { it.isNotBlank() }
            ?: (jsonObj?.get("lobbyName") as? String)?.takeIf { it.isNotBlank() }
            ?: activeCodeLobby.ifBlank { requestedLobby }

        val posTriple = avroReq?.pos
            ?: parseCodeBlockPos(jsonObj?.get("pos"))
            ?: parseCodeBlockPos(queryPos)

        val rawDocKey = when {
            posTriple != null -> "codeBlock:${posTriple.first},${posTriple.second},${posTriple.third}"
            !queryPos.isNullOrBlank() -> if (queryPos.startsWith("codeBlock:")) queryPos else "codeBlock:$queryPos"
            activeCodeDocKey.startsWith("codeBlock:") -> activeCodeDocKey
            else -> "codeBlock:0,0,0"
        }
        val validKey = LocalBloxdProtocolCore.validateDocKey(rawDocKey)
            ?: return Pair(400, "{\"ok\":false,\"error\":\"Invalid code block position\"}")

        activeCodeLobby = lobbyName
        activeCodeDocKey = validKey
        val wKey = safeKey(lobbyName)
        val docs = worldCodeDocs.getOrPut(wKey) { ConcurrentHashMap() }
        val json = synchronized(docs) {
            LocalBloxdProtocolCore.buildCodeBlockResponseJson(docs, validKey)
        }
        return Pair(200, json)
    }

    fun handleCollabPushHttp(
        ctx: Context,
        postBodyBytes: ByteArray?
    ): Pair<Int, String> {
        ensureLoaded(ctx)
        val bin = postBodyBytes?.let { LocalBloxdProtocolCore.extractBinaryContents(it) }
        val avroReq = bin?.let { LocalBloxdProtocolCore.AvroBinary.decodeCollabPush(it) }
        val jsonObj = if (avroReq == null) {
            LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        } else {
            null
        }
        if (avroReq == null && jsonObj == null) {
            return Pair(400, "{\"ok\":false,\"accepted\":false,\"error\":\"Missing collab-push payload\"}")
        }

        val lobbyName = avroReq?.lobbyName?.takeIf { it.isNotBlank() }
            ?: (jsonObj?.get("lobbyName") as? String)?.takeIf { it.isNotBlank() }
            ?: activeCodeLobby.ifBlank { requestedLobby }
        val rawDocKey = avroReq?.docKey
            ?: (jsonObj?.get("docKey") as? String)
            ?: activeCodeDocKey
        val baseVersion: Int? = avroReq?.version
            ?: (jsonObj?.get("version") as? Number)?.toInt()
            ?: (jsonObj?.get("revision") as? Number)?.toInt()
        val rawUpdates: String? = avroReq?.updates ?: when (val u = jsonObj?.get("updates")) {
            is String -> u
            is List<*> -> LocalBloxdProtocolCore.MiniJson.stringify(u)
            else -> null
        }
        val directCode: String? = if (avroReq == null) jsonObj?.get("code") as? String else null
        val clientId: String = (jsonObj?.get("clientID") as? String)?.takeIf { it.isNotBlank() }
            ?: rawUpdates?.let { LocalBloxdProtocolCore.tryParseCodeMirrorUpdates(it)?.lastOrNull()?.clientId }
            ?: "local_client"

        val wKey = safeKey(lobbyName)
        val docs = worldCodeDocs.getOrPut(wKey) { ConcurrentHashMap() }
        val historyByDoc = worldCodeHistory.getOrPut(wKey) { ConcurrentHashMap() }

        val json = synchronized(docs) {
            val prevRev = LocalBloxdProtocolCore.validateDocKey(rawDocKey)?.let { docs[it]?.revision } ?: 0
            val parsedCmUpdates = rawUpdates?.let { LocalBloxdProtocolCore.tryParseCodeMirrorUpdates(it) }
            val outcome = LocalBloxdProtocolCore.applyCollabPush(
                docs = docs,
                rawDocKey = rawDocKey,
                baseRevision = baseVersion,
                rawUpdates = rawUpdates,
                directCode = directCode,
                clientId = clientId
            )
            if (outcome.accepted) {
                activeCodeLobby = lobbyName
                activeCodeDocKey = outcome.docKey
                if (parsedCmUpdates != null && parsedCmUpdates.isNotEmpty()) {
                    val list = historyByDoc.getOrPut(outcome.docKey) { ArrayList() }
                    var revCursor = prevRev
                    for (u in parsedCmUpdates) {
                        list.add(StoredCollabUpdate(revCursor, revCursor + 1, u.clientId, u.changes))
                        revCursor++
                    }
                    if (list.size > MAX_COLLAB_HISTORY_PER_DOC) {
                        val drop = list.size - MAX_COLLAB_HISTORY_PER_DOC
                        list.subList(0, drop).clear()
                    }
                }
                syncCodeBlockToRustIfNeeded(outcome.docKey, outcome.record.code)
                scheduleCodePersist(ctx, wKey)
            }
            LocalBloxdProtocolCore.buildCollabPushResponseJson(outcome)
        }
        return Pair(200, json)
    }

    fun handleCollabPullHttp(
        ctx: Context,
        postBodyBytes: ByteArray?
    ): Pair<Int, String> {
        ensureLoaded(ctx)
        val bin = postBodyBytes?.let { LocalBloxdProtocolCore.extractBinaryContents(it) }
        val avroReq = bin?.let { LocalBloxdProtocolCore.AvroBinary.decodeCollabPull(it) }
        val jsonObj = if (avroReq == null) {
            LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        } else {
            null
        }

        val lobbyName = avroReq?.lobbyName?.takeIf { it.isNotBlank() }
            ?: (jsonObj?.get("lobbyName") as? String)?.takeIf { it.isNotBlank() }
            ?: activeCodeLobby.ifBlank { requestedLobby }
        val rawDocKey = avroReq?.docKey?.takeIf { it.isNotBlank() }
            ?: (jsonObj?.get("docKey") as? String)?.takeIf { it.isNotBlank() }
            ?: activeCodeDocKey
        val fromVersion = avroReq?.fromVersion
            ?: (jsonObj?.get("fromVersion") as? Number)?.toInt()
            ?: (jsonObj?.get("version") as? Number)?.toInt()
            ?: 0

        val validKey = LocalBloxdProtocolCore.validateDocKey(rawDocKey)
            ?: return Pair(400, "{\"ok\":false,\"error\":\"Invalid docKey\"}")

        val wKey = safeKey(lobbyName)
        val docs = worldCodeDocs.getOrPut(wKey) { ConcurrentHashMap() }
        val historyByDoc = worldCodeHistory[wKey]

        val json = synchronized(docs) {
            val target = docs[validKey] ?: LocalBloxdProtocolCore.CodeDocRecord(
                code = if (validKey == "lobby:main.js") "// Local lobby script\n" else "",
                revision = 0,
                canSeeCode = true,
                canEditCode = true
            )
            if (!target.canSeeCode) {
                LocalBloxdProtocolCore.buildCollabPullResponseJson(docs, validKey)
            } else {
                val updatesList = ArrayList<Map<String, Any?>>()
                val hist = historyByDoc?.get(validKey)
                if (hist != null) {
                    for (entry in hist) {
                        if (entry.fromRevision >= fromVersion) {
                            val item = LinkedHashMap<String, Any?>()
                            item["clientID"] = entry.clientId
                            item["changes"] = entry.changes
                            updatesList.add(item)
                        }
                    }
                }
                val res = LinkedHashMap<String, Any?>()
                res["ok"] = true
                res["docKey"] = validKey
                res["canSeeCode"] = true
                res["canEditCode"] = target.canEditCode
                res["code"] = target.code
                res["revision"] = target.revision
                res["version"] = target.revision
                res["collabVersion"] = target.revision
                res["updates"] = updatesList
                res["clientID"] = "local_client_1"
                LocalBloxdProtocolCore.MiniJson.stringify(res)
            }
        }
        return Pair(200, json)
    }

    fun handleSchematicsHttp(
        ctx: Context,
        postBodyBytes: ByteArray?
    ): Pair<Int, String> {
        ensureLoaded(ctx)
        val bin = postBodyBytes?.let { LocalBloxdProtocolCore.extractBinaryContents(it) }
        val avroReq = bin?.let { LocalBloxdProtocolCore.AvroBinary.decodeSchematics(it) }
        val requestJson = avroReq?.request ?: run {
            val jsonObj = LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
            when (val r = jsonObj?.get("request")) {
                is String -> r
                is Map<*, *> -> LocalBloxdProtocolCore.MiniJson.stringify(r)
                else -> if (jsonObj != null && jsonObj.containsKey("action")) {
                    LocalBloxdProtocolCore.MiniJson.stringify(jsonObj)
                } else {
                    null
                }
            }
        }
        return LocalBloxdProtocolCore.handleSchematicsRequest(requestJson)
    }

    fun handleMatchmakeHttp(
        ctx: Context,
        postBodyBytes: ByteArray?,
        queryGame: String?,
        queryLobby: String?
    ): String {
        ensureLoaded(ctx)
        val obj = LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        val bodyGame = (obj?.get("gameNameWithVariation") as? String)?.takeIf { it.isNotBlank() }
            ?: (obj?.get("game") as? String)?.takeIf { it.isNotBlank() }
        val rawLobby = obj?.get("lobbyNameOrDiscordContext")
            ?: obj?.get("lobbyName")
            ?: obj?.get("lobby")
        val bodyLobby = when (rawLobby) {
            is String -> rawLobby.takeIf { it.isNotBlank() }
            is Map<*, *> -> ((rawLobby["lobbyName"] as? String) ?: (rawLobby["name"] as? String))?.takeIf { it.isNotBlank() }
            else -> null
        }

        val mode = bodyGame ?: queryGame?.takeIf { it.isNotBlank() } ?: requestedMode
        val lobby = bodyLobby ?: queryLobby?.takeIf { it.isNotBlank() } ?: requestedLobby
        requestedMode = mode
        requestedLobby = lobby
        activeCodeLobby = lobby

        return JSONObject()
            .put("succeeded", true)
            .put("lobbyName", lobby)
            .put("gameNameWithVariation", mode)
            .put("gameServerHost", "game-server-local1.bloxd.io")
            .toString()
    }

    fun handleColyseusMatchmakeHttp(
        ctx: Context,
        rawPath: String,
        postBodyBytes: ByteArray?
    ): String {
        ensureLoaded(ctx)
        val roomName = LocalBloxdProtocolCore.decodeUrlSegment(rawPath.substringAfterLast('/')).ifBlank { "classic" }
        val isSocial = roomName.startsWith("social")
        val obj = LocalBloxdProtocolCore.extractJsonContentsObject(postBodyBytes)
        val bodyLobby = ((obj?.get("lobbyName") as? String)
            ?: (obj?.get("lobby") as? String))?.takeIf { it.isNotBlank() }
        if (!isSocial) {
            requestedMode = roomName
            if (bodyLobby != null) {
                requestedLobby = bodyLobby
                activeCodeLobby = bodyLobby
            }
        }
        val lobby = requestedLobby.ifBlank { "local_world" }
        val modeSeg = LocalBloxdProtocolCore.encodeUrlSegment(roomName)
        val lobbySeg = LocalBloxdProtocolCore.encodeUrlSegment(lobby)
        val roomId = if (isSocial) "social_room_01" else "game_room_${modeSeg}__${lobbySeg}"
        val sessionId = if (isSocial) "social_sess_01" else "local_sess_01"
        val roomObj = JSONObject()
            .put("clients", 1)
            .put("locked", false)
            .put("private", false)
            .put("maxClients", if (isSocial) JSONObject.NULL else 50)
            .put("unlisted", false)
            .put("createdAt", "2026-11-16T00:00:00.000Z")
            .put("name", roomName)
            .put("processId", "local01")
            .put("lobbyName", lobby)
            .put("roomId", roomId)
        return JSONObject()
            .put("room", roomObj)
            .put("sessionId", sessionId)
            .put("reconnectionToken", "$roomId:$sessionId")
            .toString()
    }

    fun getLobbyCodeHttpJson(ctx: Context, pathQuery: String?): String =
        handleGetLobbyCodeHttp(ctx, null, pathQuery).second

    fun getCodeBlockHttpJson(ctx: Context, posQuery: String?): String =
        handleGetCodeBlockHttp(ctx, null, posQuery).second

    class JsBridge(private val appContext: Context) {
        init {
            ensureLoaded(appContext)
        }

        @JavascriptInterface
        fun isNativeReady(): String {
            val rv = if (hasRust) runCatching { nativeRustVersion() }.getOrDefault(0) else 0
            return "{\"cpp\":$hasCpp,\"rust\":$hasRust,\"rustVersion\":$rv,\"chunk32Rle\":$hasCpp}"
        }

        @JavascriptInterface
        fun stagePostBody(bodyUtf8: String?): String = NativeBloxdBridge.stagePostBody(bodyUtf8)

        @JavascriptInterface
        fun setPlayerName(name: String?) {
            updatePlayerName(appContext, name)
        }

        @JavascriptInterface
        fun getPlayerName(): String {
            ensureLoaded(appContext)
            return persistedPlayerName
        }

        @JavascriptInterface
        fun setRequestedMode(mode: String?, lobby: String?) {
            val cleanMode = mode?.trim()?.takeIf { it.isNotEmpty() } ?: "classic"
            val cleanLobby = lobby?.trim()?.takeIf { it.isNotEmpty() } ?: "local_world"
            requestedMode = cleanMode
            requestedLobby = cleanLobby
            activeCodeLobby = cleanLobby
            Log.i(TAG, "Requested mode=$cleanMode lobby=$cleanLobby")
        }

        @JavascriptInterface
        fun getRequestedModeJson(): String {
            return JSONObject()
                .put("mode", requestedMode)
                .put("lobby", requestedLobby)
                .toString()
        }

        @JavascriptInterface
        fun setBlockEdit(x: Int, y: Int, z: Int, blockId: Int) {
            setWorldBlockEdit(requestedLobby, x, y, z, blockId)
        }

        @JavascriptInterface
        fun setWorldBlockEdit(world: String?, x: Int, y: Int, z: Int, blockId: Int) {
            val wKey = safeKey(world ?: requestedLobby)
            val map = worldBlocks.getOrPut(wKey) { ConcurrentHashMap(1024) }
            map["$x,$y,$z"] = blockId
            if (hasRust) {
                runCatching { nativeRustPutBlock(x, y, z, blockId) }
            }
            scheduleBlocksPersist(appContext, wKey)
        }

        @JavascriptInterface
        fun clearWorldEdits(world: String?) {
            val wKey = safeKey(world ?: requestedLobby)
            worldBlocks[wKey]?.clear()
            if (hasRust) {
                runCatching { nativeRustClearBlocks() }
            }
            scheduleBlocksPersist(appContext, wKey)
        }

        @JavascriptInterface
        fun saveWorldMetaJson(world: String?, metaJson: String?) {
            val wKey = safeKey(world ?: requestedLobby)
            val data = metaJson?.takeIf { it.isNotBlank() } ?: return
            worldMeta[wKey] = data
            scheduleMetaPersist(appContext, wKey)
        }

        @JavascriptInterface
        fun setCodeSessionTarget(world: String?, docKey: String?) {
            val cleanWorld = world?.trim()?.takeIf { it.isNotEmpty() } ?: requestedLobby
            val cleanKey = docKey?.trim()?.takeIf { it.isNotEmpty() } ?: "lobby:main.js"
            activeCodeLobby = cleanWorld
            activeCodeDocKey = cleanKey
        }

        @JavascriptInterface
        fun syncCodeDoc(world: String?, docKey: String?, code: String?, version: Int) {
            val cleanWorld = world?.trim()?.takeIf { it.isNotEmpty() } ?: requestedLobby
            val wKey = safeKey(cleanWorld)
            val key = docKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val map = worldCodeDocs.getOrPut(wKey) { ConcurrentHashMap(16) }
            val validKey = LocalBloxdProtocolCore.validateDocKey(key) ?: key
            val existing = map[validKey]
            syncCodeDocFull(
                world = cleanWorld,
                docKey = key,
                code = code,
                version = version,
                canSeeCode = existing?.canSeeCode ?: true,
                canEditCode = existing?.canEditCode ?: true,
                engine = existing?.engine ?: "default"
            )
        }

        @JavascriptInterface
        fun syncCodeDocFull(
            world: String?,
            docKey: String?,
            code: String?,
            version: Int,
            canSeeCode: Boolean,
            canEditCode: Boolean,
            engine: String?
        ) {
            val cleanWorld = world?.trim()?.takeIf { it.isNotEmpty() } ?: requestedLobby
            val wKey = safeKey(cleanWorld)
            val key = docKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val validKey = LocalBloxdProtocolCore.validateDocKey(key) ?: key
            activeCodeLobby = cleanWorld
            activeCodeDocKey = validKey
            val map = worldCodeDocs.getOrPut(wKey) { ConcurrentHashMap(16) }
            synchronized(map) {
                map[validKey] = LocalBloxdProtocolCore.CodeDocRecord(
                    code = code ?: "",
                    revision = version.coerceAtLeast(0),
                    canSeeCode = canSeeCode,
                    canEditCode = canEditCode,
                    engine = engine?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
                )
            }
            syncCodeBlockToRustIfNeeded(validKey, code ?: "")
            scheduleCodePersist(appContext, wKey)
        }

        @JavascriptInterface
        fun deleteCodeDoc(world: String?, docKey: String?) {
            val wKey = safeKey(world ?: requestedLobby)
            val key = docKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
            worldCodeDocs[wKey]?.let { map ->
                synchronized(map) {
                    map.remove(key)
                }
            }
            worldCodeHistory[wKey]?.remove(key)
            scheduleCodePersist(appContext, wKey)
        }

        @JavascriptInterface
        fun loadWorldBundleJson(world: String?): String {
            ensureLoaded(appContext)
            val wKey = safeKey(world ?: requestedLobby)
            val map = worldBlocks[wKey]
            val arr = JSONArray()
            if (map != null) {
                for ((coord, id) in map.entries) {
                    val p = coord.split(',')
                    if (p.size == 3) {
                        val row = JSONArray()
                        row.put(p[0].toIntOrNull() ?: 0)
                        row.put(p[1].toIntOrNull() ?: 0)
                        row.put(p[2].toIntOrNull() ?: 0)
                        row.put(id)
                        arr.put(row)
                    }
                }
            }
            val codeObj = JSONObject()
            worldCodeDocs[wKey]?.let { docs ->
                synchronized(docs) {
                    docs.forEach { (docKey, rec) ->
                        codeObj.put(
                            docKey,
                            JSONObject()
                                .put("code", rec.code)
                                .put("revision", rec.revision)
                                .put("version", rec.revision)
                                .put("canSeeCode", rec.canSeeCode)
                                .put("canEditCode", rec.canEditCode)
                                .put("engine", rec.engine)
                        )
                    }
                }
            }
            val root = JSONObject()
            root.put("world", wKey)
            root.put("blocks", arr)
            root.put("codeDocs", codeObj)
            if (persistedPlayerName.isNotEmpty()) {
                root.put("playerName", persistedPlayerName)
            }
            val mStr = worldMeta[wKey]
            if (!mStr.isNullOrBlank()) {
                runCatching { root.put("meta", JSONObject(mStr)) }
            }
            return root.toString()
        }

        @JavascriptInterface
        fun generateChunk32RleBase64(cx: Int, cy: Int, cz: Int, seed: Int, modeId: Int): String {
            if (hasCpp) {
                val bytes = runCatching { nativeGenerateChunk32Rle(cx, cy, cz, seed, modeId) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
            return ""
        }

        @JavascriptInterface
        fun generateSubchunkBase64(cx: Int, cy: Int, cz: Int, seed: Int, modeId: Int): String {
            if (hasCpp) {
                val bytes = runCatching { nativeGenerateSubchunk(cx, cy, cz, seed, modeId) }.getOrNull()
                if (bytes != null && bytes.size == 4096) {
                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
            return ""
        }

        @JavascriptInterface
        fun getSurfaceHeight(wx: Int, wz: Int, seed: Int, modeId: Int): Int {
            if (hasCpp) {
                return runCatching { nativeGetSurfaceHeight(wx, wz, seed, modeId) }.getOrDefault(-4)
            }
            return -4
        }

        @JavascriptInterface
        fun saveCodeBlock(x: Int, y: Int, z: Int, code: String) {
            syncCodeDoc(requestedLobby, "codeBlock:$x,$y,$z", code, 1)
        }

        @JavascriptInterface
        fun loadSavedWorldJson(): String {
            if (hasRust) {
                val live = runCatching { nativeRustExportWorldJson() }.getOrNull()
                if (!live.isNullOrBlank()) return live
            }
            return "{\"blocks\":[],\"codeBlocks\":[],\"worldCode\":\"\"}"
        }
    }
}
