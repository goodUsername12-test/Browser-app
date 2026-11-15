package com.fable.browser.agent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs

object FableVisionAndOcr {
    private const val GRID_W = 96
    private const val GRID_H = 54
    private const val GRID_CELLS = GRID_W * GRID_H
    private var prevGrid: IntArray? = null
    private val scratchGrid = IntArray(GRID_CELLS)

    private val recognizer by lazy {
        runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull()
    }

    private fun unwrapActivity(ctx: Context?): Activity? {
        var cur = ctx
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    suspend fun captureScreenOnlyBitmap(targetMaxWidth: Int = 640): Bitmap? = withContext(Dispatchers.Main) {
        val view: View = FableScreenInputController.getAttachedView() ?: return@withContext null
        if (view.width <= 8 || view.height <= 8 || !view.isShown) return@withContext null

        val scale = (targetMaxWidth.toFloat() / view.width.toFloat()).coerceIn(0.14f, 1.0f)
        val outW = (view.width * scale).toInt().coerceAtLeast(64)
        val outH = (view.height * scale).toInt().coerceAtLeast(36)

        val act = unwrapActivity(view.context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && act != null && act.window != null) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val srcRect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            val hwBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                runCatching {
                    PixelCopy.request(
                        act.window,
                        srcRect,
                        hwBmp,
                        { copyResult ->
                            if (cont.isActive) cont.resume(copyResult == PixelCopy.SUCCESS)
                        },
                        Handler(Looper.getMainLooper())
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (ok) return@withContext hwBmp
            hwBmp.recycle()
        }

        runCatching {
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
            bmp
        }.getOrNull()
    }

    suspend fun recognizeVisibleText(bitmap: Bitmap): List<OcrTextBlock> = withContext(Dispatchers.Default) {
        val client = recognizer ?: return@withContext emptyList()
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        suspendCancellableCoroutine { cont ->
            runCatching {
                val img = InputImage.fromBitmap(bitmap, 0)
                client.process(img)
                    .addOnSuccessListener { visionText ->
                        val blocks = ArrayList<OcrTextBlock>()
                        for (b in visionText.textBlocks) {
                            for (line in b.lines) {
                                val box = line.boundingBox ?: b.boundingBox ?: continue
                                val txt = line.text.trim()
                                if (txt.isNotEmpty()) {
                                    val nx = ((box.exactCenterX() / w) * 1000f).toInt().coerceIn(0, 1000)
                                    val ny = ((box.exactCenterY() / h) * 1000f).toInt().coerceIn(0, 1000)
                                    blocks.add(OcrTextBlock(txt, box, nx, ny))
                                }
                            }
                        }
                        if (cont.isActive) cont.resume(blocks)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }.onFailure {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    fun findBestInputBoxCoordinate(obs: ScreenObservation?, fieldHint: String): Pair<Int, Int>? {
        val blocks = obs?.ocrBlocks ?: return null
        if (blocks.isEmpty()) return null
        val hint = fieldHint.trim().lowercase()
        if (hint.isNotEmpty()) {
            val exact = blocks.firstOrNull { it.text.contains(hint, ignoreCase = true) }
            if (exact != null) return exact.normCenterX to exact.normCenterY
        }
        val genericHints = listOf("search", "type", "message", "chat", "enter", "username", "email", "ask", "prompt", "query")
        val fallback = blocks.firstOrNull { b ->
            val l = b.text.lowercase()
            genericHints.any { l.contains(it) }
        }
        return fallback?.let { it.normCenterX to it.normCenterY }
    }

    suspend fun observeFullScreen(): ScreenObservation? {
        val t0 = SystemClock.uptimeMillis()
        val bmp = captureScreenOnlyBitmap(720) ?: return null
        return try {
            val gridAnalysis = analyzeFastTarget(bmp, "scene", null, null)
            val blocks = recognizeVisibleText(bmp)
            val fullTxt = blocks.joinToString("\n") { it.text }
            val stable = gridAnalysis.frameDiffPermille <= 24
            val summary = buildString {
                append("Screen ${bmp.width}x${bmp.height} · ")
                append(if (stable) "Visually stable" else "Changing/Loading (${gridAnalysis.frameDiffPermille / 10}%)")
                if (blocks.isNotEmpty()) {
                    append(" · ${blocks.size} text lines visible: ")
                    append(blocks.take(8).joinToString(" | ") { it.text.take(36) })
                } else {
                    append(" · Pure 3D/Canvas view")
                }
            }
            ScreenObservation(
                width = bmp.width,
                height = bmp.height,
                frameDiffPermille = gridAnalysis.frameDiffPermille,
                isVisuallyStable = stable,
                ocrBlocks = blocks,
                fullVisibleText = fullTxt,
                dominantSummary = summary,
                captureLatencyMs = SystemClock.uptimeMillis() - t0
            )
        } finally {
            bmp.recycle()
        }
    }

    suspend fun waitUntilScreenVisuallyReady(
        maxWaitMs: Long = 10_000L,
        pollMs: Long = 200L,
        onProgress: ((String) -> Unit)? = null
    ): Boolean {
        val start = SystemClock.uptimeMillis()
        var stableStreak = 0
        delay(220L)
        while (SystemClock.uptimeMillis() - start < maxWaitMs) {
            val bmp = captureScreenOnlyBitmap(320)
            if (bmp != null) {
                val res = analyzeFastTarget(bmp, "scene", null, null)
                bmp.recycle()
                if (res.frameDiffPermille <= 22) {
                    stableStreak++
                    if (stableStreak >= 2) return true
                } else {
                    stableStreak = 0
                    onProgress?.invoke("Waiting for screen/world to finish loading...")
                }
            }
            delay(pollMs)
        }
        return false
    }

    @Synchronized
    fun analyzeFastTarget(
        bmp: Bitmap,
        targetQuery: String,
        hintNormX: Int?,
        hintNormY: Int?
    ): VisualTargetMatch {
        val t0 = SystemClock.uptimeMillis()
        val scaled = if (bmp.width == GRID_W && bmp.height == GRID_H) {
            bmp
        } else {
            Bitmap.createScaledBitmap(bmp, GRID_W, GRID_H, false)
        }
        scaled.getPixels(scratchGrid, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        if (scaled !== bmp) scaled.recycle()

        val prev = prevGrid
        var diffCount = 0
        if (prev != null && prev.size == GRID_CELLS) {
            for (i in 0 until GRID_CELLS) {
                val p1 = scratchGrid[i]
                val p0 = prev[i]
                val dr = abs(((p1 ushr 16) and 0xFF) - ((p0 ushr 16) and 0xFF))
                val dg = abs(((p1 ushr 8) and 0xFF) - ((p0 ushr 8) and 0xFF))
                val db = abs((p1 and 0xFF) - (p0 and 0xFF))
                if (dr + dg + db > 42) diffCount++
            }
        }
        val nextPrev = prev ?: IntArray(GRID_CELLS)
        System.arraycopy(scratchGrid, 0, nextPrev, 0, GRID_CELLS)
        prevGrid = nextPrev

        val diffPermille = if (prev == null) 0 else ((diffCount * 1000) / GRID_CELLS).coerceIn(0, 1000)

        val q = targetQuery.lowercase().trim()
        val lookForCow = q.contains("cow") || q.contains("animal") || q.contains("mob")
        val lookForSheep = q.contains("sheep")
        val lookForPig = q.contains("pig")
        val lookForTreeOrWood = q.contains("tree") || q.contains("wood") || q.contains("log")
        val lookForDiamondOrGold = q.contains("diamond") || q.contains("gold") || q.contains("iron") || q.contains("coal") || q.contains("ore")
        val lookForChestOrBed = q.contains("chest") || q.contains("bed") || q.contains("crate")

        var sumX = 0L
        var sumY = 0L
        var sumW = 0L
        var matchedCells = 0

        val yStart = (GRID_H * 0.14f).toInt()
        val yEnd = (GRID_H * 0.82f).toInt()
        val xStart = (GRID_W * 0.06f).toInt()
        val xEnd = (GRID_W * 0.94f).toInt()

        for (gy in yStart until yEnd) {
            val rowOff = gy * GRID_W
            for (gx in xStart until xEnd) {
                // Mask bottom-left joystick HUD & right-side action buttons
                if (gx < GRID_W * 0.28 && gy > GRID_H * 0.56) continue
                if (gx > GRID_W * 0.76 && gy > GRID_H * 0.52) continue

                val c = scratchGrid[rowOff + gx]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                val isSky = b > 155 && g > 120 && b > r + 35 && gy < GRID_H / 2
                val isGrass = g > r + 22 && g > b + 28
                if (isSky || isGrass) continue

                var weight = 0
                when {
                    lookForCow -> {
                        val isBrownHide = r in 75..175 && g in 45..130 && b in 25..95 && r > b + 25
                        val isWhitePatch = r > 205 && g > 205 && b > 200 && gy > GRID_H * 0.24
                        if (isBrownHide) weight = 4 else if (isWhitePatch) weight = 2
                    }
                    lookForSheep -> {
                        if (r > 205 && g > 205 && b > 200 && abs(r - b) < 22) weight = 4
                    }
                    lookForPig -> {
                        if (r > 185 && g in 115..175 && b in 115..175 && r > g + 22) weight = 4
                    }
                    lookForTreeOrWood -> {
                        if (r in 80..168 && g in 50..118 && b in 18..72 && r > g + 15) weight = 4
                    }
                    lookForDiamondOrGold -> {
                        val isDiamond = b > 180 && g > 175 && r < 115
                        val isGold = r > 195 && g > 165 && b < 92
                        val isIron = r in 175..215 && g in 155..195 && b in 140..180 && r > b + 12
                        if (isDiamond || isGold) weight = 5 else if (isIron) weight = 3
                    }
                    lookForChestOrBed -> {
                        val isRedBed = r > 175 && g < 75 && b < 75
                        val isChestAmber = r in 150..215 && g in 95..155 && b in 20..70
                        if (isRedBed || isChestAmber) weight = 5
                    }
                    else -> {
                        val moved = if (prev != null) {
                            val p0 = prev[rowOff + gx]
                            abs(r - ((p0 ushr 16) and 0xFF)) + abs(g - ((p0 ushr 8) and 0xFF)) + abs(b - (p0 and 0xFF)) > 46
                        } else false
                        val saturation = maxOf(r, g, b) - minOf(r, g, b)
                        if (moved && saturation > 26) weight = 4
                        else if (saturation > 54 && gy in (GRID_H / 4)..(GRID_H * 3 / 4)) weight = 2
                    }
                }

                if (weight > 0 && hintNormX != null && hintNormY != null) {
                    val cellNx = (gx * 1000) / GRID_W
                    val cellNy = (gy * 1000) / GRID_H
                    val dist = abs(cellNx - hintNormX) + abs(cellNy - hintNormY)
                    weight = if (dist < 220) weight * 4 else if (dist < 380) weight * 2 else 0
                }

                if (weight > 0) {
                    sumX += gx * weight
                    sumY += gy * weight
                    sumW += weight
                    matchedCells++
                }
            }
        }

        val elapsed = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
        if (sumW <= 0L || matchedCells < 3) {
            if (hintNormX != null && hintNormY != null) {
                return VisualTargetMatch(
                    found = true,
                    label = targetQuery,
                    normX = hintNormX,
                    normY = (hintNormY + 65).coerceIn(100, 900),
                    coveragePermille = 45,
                    confidence = 78,
                    frameDiffPermille = diffPermille,
                    latencyMs = elapsed
                )
            }
            return VisualTargetMatch(false, targetQuery, 500, 500, 0, 0, diffPermille, elapsed)
        }

        val cx = ((sumX.toDouble() / sumW.toDouble()) * 1000.0 / GRID_W).toInt().coerceIn(0, 1000)
        val cy = ((sumY.toDouble() / sumW.toDouble()) * 1000.0 / GRID_H).toInt().coerceIn(0, 1000)
        val cov = ((matchedCells * 1000) / GRID_CELLS).coerceIn(5, 600)
        val conf = (38 + matchedCells * 2).coerceIn(42, 97)
        return VisualTargetMatch(true, targetQuery, cx, cy, cov, conf, diffPermille, elapsed)
    }
}
