package com.fable.localbloxd

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LocalBloxdAssetServer(context: Context) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val ramCache = ConcurrentHashMap<String, ByteArray>(256)
    private val existingFiles: Set<String>
    private val gameThumbByFolder: Map<String, String>
    private val apiSnapshots: Map<String, ByteArray>
    private val metricsCookieTemplate: String
    private val fallbackThumbBytes: ByteArray
    private val dioritePngBytes: ByteArray
    private val indexHtmlBytes: ByteArray

    init {
        NativeBloxdBridge.ensureLoaded(appContext)
        val discovered = HashSet<String>(1024)
        val loadedFromManifest = runCatching {
            assets.open("bloxd/asset_manifest.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) discovered.add(trimmed)
                }
            }
            discovered.isNotEmpty()
        }.getOrDefault(false)

        if (!loadedFromManifest) {
            fun walk(dir: String) {
                val list = runCatching { assets.list(dir) }.getOrNull() ?: return
                for (name in list) {
                    val full = "$dir/$name"
                    val sub = runCatching { assets.list(full) }.getOrNull()
                    if (sub != null && sub.isNotEmpty()) walk(full) else discovered.add(full)
                }
            }
            walk("bloxd")
        }
        discovered.add("bloxd/local_bloxd_engine.js")
        existingFiles = discovered

        val thumbMap = HashMap<String, String>(64)
        for (p in discovered) {
            if (p.startsWith("bloxd/textures/games/")) {
                val parts = p.split('/')
                if (parts.size >= 4) {
                    thumbMap.putIfAbsent(parts[3], p)
                }
            }
        }
        gameThumbByFolder = thumbMap

        val snaps = HashMap<String, ByteArray>(16)
        var cookieRaw = "{}"
        runCatching {
            val raw = assets.open("bloxd/offline_api_cache.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "{}")
                if (k == "/index/metrics/cookies") cookieRaw = v
                snaps[k] = v.toByteArray(Charsets.UTF_8)
            }
        }
        apiSnapshots = snaps
        metricsCookieTemplate = cookieRaw

        fallbackThumbBytes = runCatching {
            val anyThumb = thumbMap.values.firstOrNull() ?: "bloxd/android-chrome-192x192.png"
            assets.open(anyThumb).use { it.readBytes() }
        }.getOrDefault(ByteArray(0))

        dioritePngBytes = runCatching {
            assets.open("bloxd/images/Diorite.png").use { it.readBytes() }
        }.getOrDefault(ByteArray(0))

        indexHtmlBytes = runCatching {
            assets.open("bloxd/index.html").use { it.readBytes() }
        }.getOrDefault("<!doctype html><html><head><script src=\"/local_bloxd_engine.js\"></script></head><body><div id=\"root\"></div></body></html>".toByteArray())
    }

    private fun headerValue(requestHeaders: Map<String, String>, name: String): String? {
        requestHeaders[name]?.let { return it }
        for ((k, v) in requestHeaders.entries) {
            if (k.equals(name, ignoreCase = true)) return v
        }
        return null
    }

    private fun reasonPhraseFor(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        416 -> "Range Not Satisfiable"
        435 -> "Offline Action Error"
        500 -> "Internal Server Error"
        else -> if (status in 200..299) "OK" else "Error"
    }

    private fun headers(
        mime: String,
        immutable: Boolean,
        requestHeaders: Map<String, String> = emptyMap(),
        isPreflight: Boolean = false
    ): Map<String, String> {
        val origin = headerValue(requestHeaders, "Origin")?.trim()?.takeIf { it.isNotEmpty() }
        val reqAllowHeaders = headerValue(requestHeaders, "Access-Control-Request-Headers")?.trim()?.takeIf { it.isNotEmpty() }
        val out = LinkedHashMap<String, String>(8)
        if (origin != null) {
            out["Access-Control-Allow-Origin"] = origin
            out["Access-Control-Allow-Credentials"] = "true"
            out["Vary"] = "Origin"
        } else {
            out["Access-Control-Allow-Origin"] = "*"
        }
        out["Access-Control-Allow-Methods"] = "GET, POST, PUT, PATCH, DELETE, OPTIONS"
        out["Access-Control-Allow-Headers"] = reqAllowHeaders
            ?: "Content-Type, Accept, X-Fable-Post-Id, X-Requested-With, Authorization"
        if (isPreflight) {
            out["Access-Control-Max-Age"] = "600"
        }
        out["Cache-Control"] = if (immutable) "public, max-age=31536000, immutable" else "no-store"
        out["Content-Type"] = mime
        return out
    }

    private fun parseByteRange(rangeHeader: String?, totalLen: Int): Pair<Int, Int>? {
        if (rangeHeader.isNullOrBlank() || totalLen <= 0) return null
        val trimmed = rangeHeader.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
        val spec = trimmed.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()
        return if (startStr.isEmpty()) {
            val suffix = endStr.toIntOrNull() ?: return null
            if (suffix <= 0) return null
            val start = (totalLen - suffix).coerceAtLeast(0)
            start to (totalLen - 1)
        } else {
            val start = startStr.toIntOrNull() ?: return null
            val end = if (endStr.isEmpty()) totalLen - 1 else (endStr.toIntOrNull() ?: return null)
            if (start < 0 || start >= totalLen || end < start) return null
            start to end.coerceAtMost(totalLen - 1)
        }
    }

    private fun responseBytes(
        mime: String,
        bytes: ByteArray,
        status: Int = 200,
        reason: String = reasonPhraseFor(status),
        immutable: Boolean = false,
        requestHeaders: Map<String, String> = emptyMap(),
        isPreflight: Boolean = false
    ): WebResourceResponse {
        val encoding = if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("svg")) "utf-8" else null
        val hdrs = LinkedHashMap(headers(mime, immutable, requestHeaders, isPreflight))
        hdrs["Accept-Ranges"] = "bytes"
        if (status == 200 && !isPreflight) {
            val rangeHdr = headerValue(requestHeaders, "Range")
            if (!rangeHdr.isNullOrBlank() && bytes.isNotEmpty()) {
                val range = parseByteRange(rangeHdr, bytes.size)
                if (range != null) {
                    val (start, end) = range
                    val sliceLen = end - start + 1
                    val slice = bytes.copyOfRange(start, end + 1)
                    hdrs["Content-Range"] = "bytes $start-$end/${bytes.size}"
                    hdrs["Content-Length"] = sliceLen.toString()
                    return WebResourceResponse(
                        mime.substringBefore(';'),
                        encoding,
                        206,
                        "Partial Content",
                        hdrs,
                        ByteArrayInputStream(slice)
                    )
                } else {
                    hdrs["Content-Range"] = "bytes */${bytes.size}"
                    hdrs["Content-Length"] = "0"
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        416,
                        "Range Not Satisfiable",
                        hdrs,
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }
        }
        hdrs["Content-Length"] = bytes.size.toString()
        return WebResourceResponse(
            mime.substringBefore(';'),
            encoding,
            status,
            reason,
            hdrs,
            ByteArrayInputStream(bytes)
        )
    }

    private fun responseJson(
        status: Int,
        json: String,
        requestHeaders: Map<String, String>
    ): WebResourceResponse = responseBytes(
        mime = "application/json",
        bytes = json.toByteArray(Charsets.UTF_8),
        status = status,
        reason = reasonPhraseFor(status),
        immutable = false,
        requestHeaders = requestHeaders
    )

    private fun responseAsset(
        assetPath: String,
        mime: String,
        immutable: Boolean,
        requestHeaders: Map<String, String>
    ): WebResourceResponse? {
        if (assetPath.split('/').any { it == ".." }) return null
        ramCache[assetPath]?.let { cached ->
            return responseBytes(mime, cached, 200, "OK", immutable, requestHeaders)
        }
        if (assetPath !in existingFiles) return null
        val stream: InputStream = runCatching {
            assets.open(assetPath, AssetManager.ACCESS_STREAMING)
        }.getOrNull() ?: return null
        val availableEst = runCatching { stream.available() }.getOrDefault(0)
        val wantsRange = !headerValue(requestHeaders, "Range").isNullOrBlank()
        if ((availableEst in 1..(256 * 1024)) || (wantsRange && availableEst in 1..(2 * 1024 * 1024))) {
            val bytes = runCatching { stream.use { it.readBytes() } }.getOrNull() ?: return null
            if (bytes.size <= 256 * 1024) {
                ramCache[assetPath] = bytes
            }
            return responseBytes(mime, bytes, 200, "OK", immutable, requestHeaders)
        }
        val encoding = if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("svg")) "utf-8" else null
        val hdrs = LinkedHashMap(headers(mime, immutable, requestHeaders))
        hdrs["Accept-Ranges"] = "bytes"
        return WebResourceResponse(
            mime.substringBefore(';'),
            encoding,
            200,
            "OK",
            hdrs,
            stream
        )
    }

    private fun guessMime(path: String): String {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".html") -> "text/html"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".wasm") -> "application/wasm"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".glb") -> "model/gltf-binary"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".ogg") -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    fun intercept(
        uri: Uri,
        method: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): WebResourceResponse {
        // Handle CORS preflight immediately without consuming any staged POST body token
        if (method.equals("OPTIONS", ignoreCase = true)) {
            return responseBytes(
                mime = "text/plain",
                bytes = ByteArray(0),
                status = 200,
                reason = "OK",
                immutable = false,
                requestHeaders = requestHeaders,
                isPreflight = true
            )
        }

        val host = (uri.host ?: "").lowercase(Locale.US)
        val rawPath = uri.path ?: "/"
        val postToken = headerValue(requestHeaders, "X-Fable-Post-Id")
        val postBodyBytes = if (!postToken.isNullOrBlank()) {
            NativeBloxdBridge.consumeStagedPostBody(postToken)
        } else {
            null
        }

        when (rawPath) {
            "/local_bloxd_engine.js" -> {
                return responseAsset("bloxd/local_bloxd_engine.js", "application/javascript", false, requestHeaders)
                    ?: responseBytes("application/javascript", ByteArray(0), requestHeaders = requestHeaders)
            }
            "/localforage_seed.json", "/bloxd_protocol_map.json", "/bloxd_world_meta.json", "/offline_api_cache.json" -> {
                val rel = rawPath.trimStart('/')
                return responseAsset("bloxd/$rel", "application/json", true, requestHeaders)
                    ?: responseBytes("application/json", "{}".toByteArray(), requestHeaders = requestHeaders)
            }
            "/images/Diorite.png" -> {
                return responseBytes("image/png", dioritePngBytes, 200, "OK", false, requestHeaders)
            }
        }

        if (rawPath.endsWith("/index/name/update")) {
            val (status, json) = NativeBloxdBridge.handleNameUpdateHttp(
                appContext,
                postBodyBytes,
                uri.getQueryParameter("name")
            )
            return responseJson(status, json, requestHeaders)
        }

        if (rawPath.endsWith("/index/metrics/cookies")) {
            NativeBloxdBridge.ensureLoaded(appContext)
            val savedName = NativeBloxdBridge.persistedPlayerName
            if (savedName.isNotEmpty()) {
                val patched = runCatching {
                    JSONObject(metricsCookieTemplate).put("name", savedName).toString()
                }.getOrDefault(metricsCookieTemplate)
                return responseJson(200, patched, requestHeaders)
            }
            apiSnapshots["/index/metrics/cookies"]?.let {
                return responseBytes("application/json", it, requestHeaders = requestHeaders)
            }
        }

        if (rawPath.endsWith("/index/schematic/get-owned-schematics")) {
            return responseJson(200, "[]", requestHeaders)
        }

        if (rawPath.endsWith("/code/get-lobby-code")) {
            val (status, json) = NativeBloxdBridge.handleGetLobbyCodeHttp(
                appContext,
                postBodyBytes,
                uri.getQueryParameter("path")
            )
            return responseJson(status, json, requestHeaders)
        }

        if (rawPath.endsWith("/code/get-code-block")) {
            val (status, json) = NativeBloxdBridge.handleGetCodeBlockHttp(
                appContext,
                postBodyBytes,
                uri.getQueryParameter("pos")
            )
            return responseJson(status, json, requestHeaders)
        }

        if (rawPath.endsWith("/code/collab-push")) {
            val (status, json) = NativeBloxdBridge.handleCollabPushHttp(appContext, postBodyBytes)
            return responseJson(status, json, requestHeaders)
        }

        if (rawPath.endsWith("/code/collab-pull")) {
            val (status, json) = NativeBloxdBridge.handleCollabPullHttp(appContext, postBodyBytes)
            return responseJson(status, json, requestHeaders)
        }

        if (rawPath.endsWith("/code/schematics")) {
            val (status, json) = NativeBloxdBridge.handleSchematicsHttp(appContext, postBodyBytes)
            return responseJson(status, json, requestHeaders)
        }

        if (host.contains("cloudflare") || rawPath.contains("turnstile")) {
            val turnstileJs = """
                (function(){
                  window.turnstile = {
                    render: function(container, opts) {
                      var cb = opts && opts.callback;
                      if (typeof cb === 'function') {
                        setTimeout(function(){ try { cb('local_turnstile_ok'); } catch(e){} }, 0);
                      }
                      return 'local_widget_1';
                    },
                    execute: function(container, opts) {
                      var cb = opts && opts.callback;
                      if (typeof cb === 'function') {
                        setTimeout(function(){ try { cb('local_turnstile_ok'); } catch(e){} }, 0);
                      }
                    },
                    reset: function(){},
                    remove: function(){},
                    getResponse: function(){ return 'local_turnstile_ok'; }
                  };
                  if (typeof window.onTurnstileLoaded === 'function') {
                    try { window.onTurnstileLoaded(); } catch(e) {}
                  }
                })();
            """.trimIndent()
            return responseBytes("application/javascript", turnstileJs.toByteArray(Charsets.UTF_8), requestHeaders = requestHeaders)
        }

        if (rawPath == "/noop_sdk.js" || host.contains("page-kit") ||
            host.contains("recaptcha") || host.contains("bytebrew") || host.contains("googletagmanager") ||
            host.contains("google-analytics") || host.contains("crazygames") || host.contains("jsdelivr")
        ) {
            return if (rawPath.endsWith(".html")) {
                responseBytes("text/html", "<!doctype html><html><body></body></html>".toByteArray(), requestHeaders = requestHeaders)
            } else {
                responseBytes("application/javascript", "window.gtag=window.gtag||function(){};".toByteArray(), requestHeaders = requestHeaders)
            }
        }

        if (host.contains("firebase") || host.contains("googleapis") || host.contains("gstatic.com") || host.contains("google.com") || host.contains("ytimg.com") || host.contains("imgur.com")) {
            if (rawPath.endsWith(".woff2") || rawPath.endsWith(".ttf")) {
                return responseAsset("bloxd/static/rubik-variable.woff2", "font/woff2", true, requestHeaders)
                    ?: responseBytes("font/woff2", ByteArray(0), requestHeaders = requestHeaders)
            }
            if (rawPath.endsWith(".jpg") || rawPath.endsWith(".png") || rawPath.endsWith(".webp")) {
                return responseBytes("image/jpeg", fallbackThumbBytes, 200, "OK", true, requestHeaders)
            }
            if (rawPath.endsWith(".html")) {
                return responseBytes("text/html", "<!doctype html><html><body></body></html>".toByteArray(), requestHeaders = requestHeaders)
            }
            if (rawPath.endsWith(".js")) {
                return responseBytes("application/javascript", "window.gapi=window.gapi||{load:function(){},loaded_0:function(){}};".toByteArray(), requestHeaders = requestHeaders)
            }
            return responseJson(200, "{\"projectId\":\"bloxd-ee1aa\",\"authorizedDomains\":[\"bloxd.io\",\"localhost\"]}", requestHeaders)
        }

        if (rawPath.contains("/social/bloxd-matchmake")) {
            val json = NativeBloxdBridge.handleMatchmakeHttp(
                appContext,
                postBodyBytes,
                uri.getQueryParameter("game"),
                uri.getQueryParameter("lobby")
            )
            return responseJson(200, json, requestHeaders)
        }

        if (rawPath.contains("/matchmake/joinOrCreate/") || rawPath.contains("/matchmake/join/") || rawPath.contains("/matchmake/create/") || rawPath.contains("/matchmake/reconnect/")) {
            val json = NativeBloxdBridge.handleColyseusMatchmakeHttp(appContext, rawPath, postBodyBytes)
            return responseJson(200, json, requestHeaders)
        }

        apiSnapshots[rawPath]?.let { return responseBytes("application/json", it, requestHeaders = requestHeaders) }
        for ((apiKey, snapBytes) in apiSnapshots) {
            if (rawPath.endsWith(apiKey)) {
                return responseBytes("application/json", snapBytes, requestHeaders = requestHeaders)
            }
        }
        if (rawPath.contains("/persisted/keep-alive") || rawPath.contains("/social/") || rawPath.contains("/index/")) {
            return responseJson(200, "{}", requestHeaders)
        }

        if (rawPath == "/" || rawPath == "/index.html" || rawPath.startsWith("/play") || rawPath.startsWith("/game") || rawPath.startsWith("/worlds")) {
            if (!rawPath.substringAfterLast('/').contains('.') || rawPath.endsWith(".html")) {
                return responseBytes("text/html", indexHtmlBytes, 200, "OK", false, requestHeaders)
            }
        }

        // Android Uri.getPath() is already percent-decoded; avoid double-decoding.
        val cleanRel = rawPath.trimStart('/')
        if (cleanRel.split('/').any { it == ".." }) {
            return responseBytes("text/plain", "Forbidden".toByteArray(), 403, "Forbidden", false, requestHeaders)
        }
        val directAsset = "bloxd/$cleanRel"
        responseAsset(directAsset, guessMime(cleanRel), true, requestHeaders)?.let { return it }

        if (cleanRel.startsWith("sounds/generatedSprites/")) {
            val baseSound = cleanRel.substringAfterLast('/').substringBefore('.') + ".ogg"
            responseAsset("bloxd/sounds/generatedSprites/$baseSound", "audio/ogg", true, requestHeaders)?.let { return it }
        }

        if (cleanRel.startsWith("textures/games/")) {
            val parts = cleanRel.split('/')
            if (parts.size >= 3) {
                gameThumbByFolder[parts[2]]?.let { match ->
                    responseAsset(match, guessMime(match), true, requestHeaders)?.let { return it }
                }
            }
            return responseBytes("image/jpeg", fallbackThumbBytes, 200, "OK", true, requestHeaders)
        }

        if (cleanRel.endsWith(".css")) {
            return responseBytes("text/css", ByteArray(0), 200, "OK", false, requestHeaders)
        }
        if (cleanRel.endsWith(".png") || cleanRel.endsWith(".jpg") || cleanRel.endsWith(".webp")) {
            return if (dioritePngBytes.isNotEmpty()) {
                responseBytes("image/png", dioritePngBytes, 200, "OK", true, requestHeaders)
            } else {
                responseBytes("image/jpeg", fallbackThumbBytes, 200, "OK", true, requestHeaders)
            }
        }
        if (cleanRel.endsWith(".mp3") || cleanRel.endsWith(".ogg") || cleanRel.endsWith(".wav")) {
            return responseAsset("bloxd/sounds/generatedSprites/step.ogg", "audio/ogg", true, requestHeaders)
                ?: responseBytes("audio/ogg", ByteArray(0), requestHeaders = requestHeaders)
        }
        if (cleanRel.endsWith(".chunk.js") || cleanRel.endsWith(".js")) {
            return responseBytes("text/plain", "Not found".toByteArray(), 404, "Not Found", false, requestHeaders)
        }
        return responseJson(200, "{}", requestHeaders)
    }
}
