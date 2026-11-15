package com.fable.browser.agent

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

object FableVideoSkillLearner {

    private fun skillsDir(context: Context): File =
        File(context.getExternalFilesDir("fable_agent") ?: context.filesDir, "skills").apply { mkdirs() }

    fun modelsDir(context: Context): File =
        (context.getExternalFilesDir("fable_models") ?: File(context.filesDir, "fable_models")).apply { mkdirs() }

    fun freeStorageGb(context: Context): Long = runCatching {
        val path = context.getExternalFilesDir(null) ?: Environment.getDataDirectory()
        val stat = StatFs(path.absolutePath)
        (stat.availableBytes / (1024L * 1024L * 1024L)).coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun listInstalledModels(context: Context): List<String> = runCatching {
        val builtIn = listOf("Fable-Nano-Intent + MLKit-OCR + Rust-PD-V2 (Built-in, <4 MB, Always Ready)")
        val ggufs = FableLocalLlmRuntime.resolveInstalledGgufFiles(context).map { f ->
            val role = when {
                f.name.contains("qwen3.5", true) || f.name.contains("0.8b", true) -> "Primary Brain"
                f.name.contains("qwen3-0.6b", true) || f.name.contains("0.6b", true) -> "Fast Command Router"
                else -> "Local GGUF"
            }
            val activeTag = if (FableLocalLlmRuntime.loadedModelPath == f.absolutePath) " · LOADED" else ""
            "${f.name} (${f.length() / (1024L * 1024L)} MB · $role$activeTag)"
        }
        val other = modelsDir(context).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".onnx", true) || it.name.endsWith(".tflite", true)) }
            ?.map { "${it.name} (${it.length() / (1024L * 1024L)} MB)" }
            .orEmpty()
        builtIn + ggufs + other
    }.getOrDefault(listOf("Fable-Nano-Intent + Rust-PD-V2 (Active)"))

    suspend fun importModelFile(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(context, uri) ?: "model_${System.currentTimeMillis()}.gguf"
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dst = File(modelsDir(context), safeName)
            context.contentResolver.openInputStream(uri)?.use { inp ->
                dst.outputStream().buffered(512 * 1024).use { out -> inp.copyTo(out, 512 * 1024) }
            } ?: return@runCatching null
            "${dst.name} (${dst.length() / (1024L * 1024L)} MB)"
        }.getOrNull()
    }

    fun persistLearnedSkill(context: Context, skill: LearnedVideoSkill) {
        saveSkillJson(context, skill)
    }

    fun loadSavedSkills(context: Context): List<LearnedVideoSkill> = runCatching {
        skillsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseSkillJson(JSONObject(it.readText())) }
            .orEmpty()
    }.getOrDefault(emptyList())

    suspend fun analyzeAndLearnVideo(
        context: Context,
        videoUri: Uri,
        customSkillName: String = "",
        onProgress: ((String) -> Unit)? = null
    ): LearnedVideoSkill? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            onProgress?.invoke("Opening demonstration video...")
            retriever.setDataSource(context, videoUri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0L) return@withContext null

            val fileName = queryDisplayName(context, videoUri) ?: "video_demo.mp4"
            val sampleCount = (durMs / 500L).toInt().coerceIn(8, 28)
            val stepIntervalMs = (durMs / sampleCount).coerceAtLeast(180L)

            val steps = ArrayList<SkillStep>()
            val ocrHighlights = LinkedHashSet<String>()
            var prevSmall: IntArray? = null
            var consecutiveActionFlashes = 0
            val sw = 80
            val sh = 45

            for (idx in 0 until sampleCount) {
                val timeMs = idx * stepIntervalMs
                onProgress?.invoke("Analyzing video frame ${idx + 1}/$sampleCount...")
                val rawFrame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 480, 270)
                } else {
                    retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: continue

                if (idx % 3 == 0) {
                    val blocks = FableVisionAndOcr.recognizeVisibleText(rawFrame)
                    blocks.take(6).forEach { b ->
                        if (b.text.length in 2..42) ocrHighlights.add(b.text)
                    }
                }

                val small = Bitmap.createScaledBitmap(rawFrame, sw, sh, false)
                val curPx = IntArray(sw * sh)
                small.getPixels(curPx, 0, sw, 0, 0, sw, sh)
                if (small !== rawFrame) small.recycle()
                rawFrame.recycle()

                val prev = prevSmall
                if (prev != null) {
                    var flowX = 0L
                    var flowY = 0L
                    var activeMotion = 0
                    var primaryHudFlash = 0
                    var jumpHudFlash = 0
                    for (y in 6 until (sh - 5)) {
                        for (x in 6 until (sw - 5)) {
                            val cNow = luma(curPx[y * sw + x])
                            val cPrev = luma(prev[y * sw + x])
                            val diff = abs(cNow - cPrev)
                            if (diff > 20) {
                                activeMotion++
                                val lRight = abs(cNow - luma(prev[y * sw + (x + 2)]))
                                val lLeft = abs(cNow - luma(prev[y * sw + (x - 2)]))
                                val lDown = abs(cNow - luma(prev[(y + 2) * sw + x]))
                                val lUp = abs(cNow - luma(prev[(y - 2) * sw + x]))
                                flowX += (lLeft - lRight)
                                flowY += (lUp - lDown)
                                if (x in (sw * 70 / 100)..(sw * 86 / 100) && y in (sh * 46 / 100)..(sh * 68 / 100) && diff > 42) {
                                    primaryHudFlash++
                                }
                                if (x > sw * 82 / 100 && y > sh * 66 / 100 && diff > 42) {
                                    jumpHudFlash++
                                }
                            }
                        }
                    }
                    val motionRatio = (activeMotion * 100) / (sw * sh)
                    val camDx = if (activeMotion > 18) ((flowX / activeMotion) * 3).toInt().coerceIn(-88, 88) else 0
                    val camDy = if (activeMotion > 18) ((flowY / activeMotion) * 2).toInt().coerceIn(-55, 55) else 0
                    val joyY = if (motionRatio >= 14) -82 else if (motionRatio >= 6) -48 else 0
                    val joyX = (camDx / 3).coerceIn(-50, 50)

                    if (primaryHudFlash > 9) consecutiveActionFlashes++ else consecutiveActionFlashes = 0
                    val holdPrim = consecutiveActionFlashes >= 2
                    val tapX = if (primaryHudFlash > 9 && !holdPrim) 780 else -1
                    val tapY = if (primaryHudFlash > 9 && !holdPrim) 580 else -1
                    val jumpTap = jumpHudFlash > 8

                    val note = buildString {
                        if (joyY < 0) append("Move forward ")
                        if (abs(camDx) >= 12) append(if (camDx > 0) "+ Turn right " else "+ Turn left ")
                        if (holdPrim) append("+ Hold action ")
                        else if (tapX > 0) append("+ Tap action ")
                        if (jumpTap) append("+ Jump")
                        if (isEmpty()) append("Hold/Observe")
                    }.trim()

                    steps.add(
                        SkillStep(
                            offsetMs = timeMs,
                            durationMs = stepIntervalMs,
                            joyXPercent = joyX,
                            joyYPercent = joyY,
                            camDxPercent = camDx,
                            camDyPercent = camDy,
                            tapNormX = tapX,
                            tapNormY = tapY,
                            holdPrimaryButton = holdPrim,
                            tapSecondaryJump = jumpTap,
                            note = note
                        )
                    )
                }
                prevSmall = curPx
            }

            val cleanBase = customSkillName.trim().ifBlank {
                fileName.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Video Skill" }
            }
            val moveSteps = steps.count { it.joyYPercent != 0 || abs(it.camDxPercent) >= 10 }
            val actionCount = steps.count { it.tapNormX > 0 || it.holdPrimaryButton || it.tapSecondaryJump }
            val summary = buildString {
                append("Learned from '$fileName' (${"%.1f".format(durMs / 1000.0)}s, $sampleCount frames). ")
                append("Extracted $moveSteps movement/camera vectors and $actionCount button holds/taps. ")
                if (ocrHighlights.isNotEmpty()) {
                    append("Visible labels: ${ocrHighlights.take(8).joinToString(", ")}.")
                }
            }

            val skill = LearnedVideoSkill(
                id = UUID.randomUUID().toString().take(8),
                name = cleanBase,
                sourceFileName = fileName,
                durationMs = durMs,
                sampledFrames = sampleCount,
                deepSummary = summary,
                detectedLabels = ocrHighlights.take(12).toList(),
                steps = steps,
                createdAtIso = Instant.now().toString()
            )
            saveSkillJson(context, skill)
            skill
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) ushr 8
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun saveSkillJson(context: Context, s: LearnedVideoSkill) {
        val stepsArr = JSONArray()
        s.steps.forEach { st ->
            stepsArr.put(
                JSONObject()
                    .put("offsetMs", st.offsetMs)
                    .put("durationMs", st.durationMs)
                    .put("joyX", st.joyXPercent)
                    .put("joyY", st.joyYPercent)
                    .put("camDx", st.camDxPercent)
                    .put("camDy", st.camDyPercent)
                    .put("tapX", st.tapNormX)
                    .put("tapY", st.tapNormY)
                    .put("holdPrimary", st.holdPrimaryButton)
                    .put("tapJump", st.tapSecondaryJump)
                    .put("note", st.note)
            )
        }
        val labelsArr = JSONArray()
        s.detectedLabels.forEach { labelsArr.put(it) }
        val root = JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("sourceFileName", s.sourceFileName)
            .put("durationMs", s.durationMs)
            .put("sampledFrames", s.sampledFrames)
            .put("deepSummary", s.deepSummary)
            .put("detectedLabels", labelsArr)
            .put("steps", stepsArr)
            .put("createdAtIso", s.createdAtIso)
        File(skillsDir(context), "skill_${s.id}.json").writeText(root.toString(2))
    }

    private fun parseSkillJson(obj: JSONObject): LearnedVideoSkill? = runCatching {
        val labels = ArrayList<String>()
        val lArr = obj.optJSONArray("detectedLabels")
        if (lArr != null) {
            for (i in 0 until lArr.length()) labels.add(lArr.optString(i))
        }
        val steps = ArrayList<SkillStep>()
        val sArr = obj.optJSONArray("steps")
        if (sArr != null) {
            for (i in 0 until sArr.length()) {
                val o = sArr.optJSONObject(i) ?: continue
                steps.add(
                    SkillStep(
                        offsetMs = o.optLong("offsetMs"),
                        durationMs = o.optLong("durationMs", 400L),
                        joyXPercent = o.optInt("joyX"),
                        joyYPercent = o.optInt("joyY"),
                        camDxPercent = o.optInt("camDx"),
                        camDyPercent = o.optInt("camDy"),
                        tapNormX = o.optInt("tapX", -1),
                        tapNormY = o.optInt("tapY", -1),
                        holdPrimaryButton = o.optBoolean("holdPrimary", false),
                        tapSecondaryJump = o.optBoolean("tapJump", false),
                        note = o.optString("note")
                    )
                )
            }
        }
        LearnedVideoSkill(
            id = obj.getString("id"),
            name = obj.getString("name"),
            sourceFileName = obj.optString("sourceFileName", "video.mp4"),
            durationMs = obj.optLong("durationMs"),
            sampledFrames = obj.optInt("sampledFrames"),
            deepSummary = obj.optString("deepSummary"),
            detectedLabels = labels,
            steps = steps,
            createdAtIso = obj.optString("createdAtIso")
        )
    }.getOrNull()
}
