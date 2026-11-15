package com.fable.browser.agent

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class FableModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val roleSummary: String,
    val approxSizeMb: Int,
    val githubReleaseUrl: String,
    val fallbackUrl: String,
    val releaseSha256: String,
    val defaultCtx: Int
)

data class LocalLlmGenerationResult(
    val rawOutput: String,
    val cleanOutput: String,
    val thinkingTrace: String,
    val modelLabel: String,
    val latencyMs: Long
)

object FableLocalLlmRuntime {

    val PRIMARY_QWEN35_0_8B = FableModelSpec(
        id = "qwen35_0_8b_q4",
        title = "Qwen3.5-0.8B Q4_K_M (Primary Brain)",
        fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
        roleSummary = "Conversation, multi-step planning, YouTube/page synthesis, Long Task & Owner Research",
        approxSizeMb = 508,
        githubReleaseUrl = "https://github.com/goodUsername12-test/Browser-app/releases/download/Ai_models/Qwen3.5-0.8B-Q4_K_M.gguf",
        fallbackUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf?download=true",
        releaseSha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        defaultCtx = 3072
    )

    val FAST_QWEN3_0_6B = FableModelSpec(
        id = "qwen3_0_6b_q4",
        title = "Qwen3-0.6B Q4_K_M (Fast Command Router)",
        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
        roleSummary = "Instant /no_think command parsing, typing, camera, button holds & quick corrections",
        approxSizeMb = 378,
        githubReleaseUrl = "https://github.com/goodUsername12-test/Browser-app/releases/download/Ai_models/Qwen3-0.6B-Q4_K_M.gguf",
        fallbackUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true",
        releaseSha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        defaultCtx = 2048
    )

    val OFFICIAL_SPECS: List<FableModelSpec> = listOf(PRIMARY_QWEN35_0_8B, FAST_QWEN3_0_6B)

    val isNativeRuntimeCompiled: Boolean = runCatching {
        System.loadLibrary("fable_llama")
        true
    }.getOrDefault(false)

    @Volatile
    var loadedModelName: String = ""
        private set

    @Volatile
    var loadedModelPath: String = ""
        private set

    @JvmStatic
    private external fun nativeInitBackend(): String

    @JvmStatic
    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): String

    @JvmStatic
    private external fun nativeUnloadModel()

    @JvmStatic
    private external fun nativeAbortGeneration()

    @JvmStatic
    private external fun nativeGenerate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        enableThinking: Boolean
    ): String

    fun backendStatusSummary(): String {
        if (!isNativeRuntimeCompiled) {
            return "Built-in Fast Semantic Brain active (native GGUF runtime unavailable on this ABI)"
        }
        return if (loadedModelName.isNotBlank()) {
            "llama.cpp Native Active · Loaded: $loadedModelName"
        } else {
            "llama.cpp Native Ready (On-Demand RAM Governor)"
        }
    }

    fun abortImmediately() {
        if (isNativeRuntimeCompiled) {
            runCatching { nativeAbortGeneration() }
        }
    }

    fun unloadForFastGaming() {
        if (isNativeRuntimeCompiled && loadedModelPath.isNotBlank()) {
            runCatching { nativeUnloadModel() }
            loadedModelPath = ""
            loadedModelName = ""
        }
    }

    fun resolveInstalledGgufFiles(context: Context): List<File> {
        val vaultDir = FableVideoSkillLearner.modelsDir(context)
        val found = LinkedHashMap<String, File>()

        vaultDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() > 16 * 1024 * 1024L }
            ?.sortedByDescending { it.length() }
            ?.forEach { found[it.name.lowercase()] = it }

        // Also check public Download folder if readable (e.g. user already downloaded both .gguf files)
        runCatching {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dlDir?.listFiles()
                ?.filter { it.isFile && it.canRead() && it.name.endsWith(".gguf", ignoreCase = true) && it.length() > 16 * 1024 * 1024L }
                ?.forEach { f -> found.putIfAbsent(f.name.lowercase(), f) }
        }

        return found.values.toList()
    }

    fun chooseBestModelFile(context: Context, preferFastRouter: Boolean): File? {
        val files = resolveInstalledGgufFiles(context)
        if (files.isEmpty()) return null

        val primaryMatch = files.firstOrNull {
            it.name.contains("qwen3.5", ignoreCase = true) || it.name.contains("0.8b", ignoreCase = true)
        }
        val fastMatch = files.firstOrNull {
            it.name.contains("qwen3-0.6b", ignoreCase = true) || it.name.contains("0.6b", ignoreCase = true) || it.name.contains("0.5b", ignoreCase = true)
        }

        return if (preferFastRouter) {
            fastMatch ?: primaryMatch ?: files.minByOrNull { it.length() }
        } else {
            primaryMatch ?: fastMatch ?: files.maxByOrNull { it.length() }
        }
    }

    suspend fun ensureModelLoaded(
        modelFile: File,
        nCtx: Int = 2560,
        nThreads: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isNativeRuntimeCompiled || !modelFile.isFile || !modelFile.canRead()) {
            return@withContext false
        }
        if (!hasValidGgufHeader(modelFile)) {
            return@withContext false
        }
        runCatching {
            val resJson = nativeLoadModel(modelFile.absolutePath, nCtx, nThreads)
            val obj = JSONObject(resJson)
            val ok = obj.optBoolean("ok", false)
            if (ok) {
                loadedModelPath = modelFile.absolutePath
                loadedModelName = modelFile.nameWithoutExtension
            }
            ok
        }.getOrDefault(false)
    }

    suspend fun generateOnDevice(
        context: Context,
        preferFastRouter: Boolean,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 220,
        temperature: Float = 0.35f,
        enableThinking: Boolean = false
    ): LocalLlmGenerationResult? = withContext(Dispatchers.IO) {
        if (!isNativeRuntimeCompiled) return@withContext null
        val chosen = chooseBestModelFile(context, preferFastRouter) ?: return@withContext null
        val targetCtx = if (preferFastRouter) 2048 else 3072

        val t0 = SystemClock.uptimeMillis()
        if (!ensureModelLoaded(chosen, nCtx = targetCtx)) {
            return@withContext null
        }
        coroutineContext.ensureActive()

        val raw = runCatching {
            nativeGenerate(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = maxTokens.coerceIn(24, 768),
                temperature = temperature,
                topP = if (enableThinking) 0.95f else 0.82f,
                topK = 20,
                enableThinking = enableThinking
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            ""
        }

        if (raw.isBlank()) return@withContext null
        val (clean, think) = splitThinkingAndCleanOutput(raw)
        if (clean.isBlank()) return@withContext null

        LocalLlmGenerationResult(
            rawOutput = raw,
            cleanOutput = clean,
            thinkingTrace = think,
            modelLabel = chosen.nameWithoutExtension,
            latencyMs = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
        )
    }

    suspend fun downloadSpecDirectlyToVault(
        context: Context,
        spec: FableModelSpec,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val vault = FableVideoSkillLearner.modelsDir(context)
        val dest = File(vault, spec.fileName)
        val part = File(vault, "${spec.fileName}.part")

        if (dest.isFile && dest.length() > 100L * 1024L * 1024L && hasValidGgufHeader(dest)) {
            return@withContext Result.success(dest)
        }

        val candidateUrls = listOf(spec.githubReleaseUrl, spec.fallbackUrl)
        var lastErr: Throwable? = null

        for ((attemptIdx, urlStr) in candidateUrls.withIndex()) {
            try {
                if (part.exists()) part.delete()
                onProgress("Connecting to ${if (attemptIdx == 0) "Fable GitHub Release (Ai_models)" else "HuggingFace Mirror"} for ${spec.fileName}...")

                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "FableBrowser/1.45")
                    connect()
                }
                if (conn.responseCode !in 200..299) {
                    val code = conn.responseCode
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code from model server")
                }

                val totalBytes = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastReportAt = 0L

                BufferedInputStream(conn.inputStream, 256 * 1024).use { input ->
                    BufferedOutputStream(FileOutputStream(part), 256 * 1024).use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            downloaded += n
                            val now = SystemClock.uptimeMillis()
                            if (now - lastReportAt >= 500L) {
                                lastReportAt = now
                                val mb = downloaded / (1024L * 1024L)
                                val pctStr = if (totalBytes > 0L) {
                                    " (${((downloaded * 100L) / totalBytes).coerceIn(0L, 100L)}%)"
                                } else ""
                                onProgress("Downloading ${spec.fileName}: ${mb} MB$pctStr")
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()

                if (!hasValidGgufHeader(part)) {
                    part.delete()
                    throw IllegalStateException("Downloaded file did not have a valid GGUF header")
                }

                val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (attemptIdx == 0 && spec.releaseSha256.isNotBlank() && !shaHex.equals(spec.releaseSha256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException("SHA-256 mismatch on release asset")
                }

                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                onProgress("Verified & installed ${dest.name} (${dest.length() / (1024L * 1024L)} MB)")
                return@withContext Result.success(dest)
            } catch (ce: CancellationException) {
                part.delete()
                throw ce
            } catch (t: Throwable) {
                part.delete()
                lastErr = t
            }
        }

        Result.failure(lastErr ?: IllegalStateException("Could not download ${spec.fileName}"))
    }

    fun hasValidGgufHeader(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 16L) return false
        val magic = ByteArray(4)
        FileInputStream(file).use { fis ->
            if (fis.read(magic) != 4) return false
        }
        magic[0] == 'G'.code.toByte() &&
            magic[1] == 'G'.code.toByte() &&
            magic[2] == 'U'.code.toByte() &&
            magic[3] == 'F'.code.toByte()
    }.getOrDefault(false)

    fun splitThinkingAndCleanOutput(raw: String): Pair<String, String> {
        var text = raw
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trim()
        var thinking = ""
        val thinkStart = text.indexOf("<think>")
        val thinkEnd = text.indexOf("</think>")
        if (thinkStart >= 0 && thinkEnd > thinkStart) {
            thinking = text.substring(thinkStart + 7, thinkEnd).trim()
            text = (text.substring(0, thinkStart) + text.substring(thinkEnd + 8)).trim()
        } else if (thinkEnd >= 0) {
            thinking = text.substring(0, thinkEnd).trim()
            text = text.substring(thinkEnd + 8).trim()
        }
        return text to thinking
    }
}
