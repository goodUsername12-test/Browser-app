package com.fable.browser.engine

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class PerfSnapshot(
    val coldStartMs: Long = 0L,
    val lastPageLoadMs: Long = 0L,
    val avgPageLoadMs: Long = 0L,
    val pageLoadsMeasured: Int = 0,
    val fps: Int = 60,
    val frameP50Ms: Float = 0f,
    val frameP95Ms: Float = 0f,
    val frameP99Ms: Float = 0f,
    val jankFrames: Long = 0L,
    val severeJankFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val jankPercent: Float = 0f,
    val displayHz: Float = 60f,
    val activeEngine: String = "GeckoView",
    val heapUsedMb: Int = 0,
    val cookieFlushCount: Int = 0,
    val lastCookieFlushAtMs: Long = 0L,
    val lastUrl: String = ""
)

object PerformanceTelemetry {
    private const val TAG = "FABLE-PERF"
    private const val RING_SIZE = 120
    private const val JANK_NS = 16_700_000L
    private const val SEVERE_JANK_NS = 33_400_000L

    private val appCreateMs = AtomicLong(0L)
    private val firstFrameMs = AtomicLong(0L)
    private val navStartMs = AtomicLong(0L)
    private val totalPageLoadMs = AtomicLong(0L)
    private val pageLoadCount = AtomicInteger(0)
    private val cookieFlushes = AtomicInteger(0)

    private val ringLock = Any()
    private val frameRingNs = LongArray(RING_SIZE)
    private var ringCursor = 0
    private var ringCount = 0
    private var totalFrames = 0L
    private var jankFrames = 0L
    private var severeJankFrames = 0L
    private var lastPublishMs = 0L

    @Volatile private var activeEngineLabel: String = "GeckoView"
    @Volatile private var currentDisplayHz: Float = 60f
    @Volatile private var currentUrl: String = ""
    @Volatile var hudEnabled: Boolean = false

    private val _snapshot = MutableStateFlow(PerfSnapshot())
    val snapshot: StateFlow<PerfSnapshot> = _snapshot.asStateFlow()

    private fun nowElapsedMs(): Long = runCatching {
        SystemClock.elapsedRealtime()
    }.getOrElse { System.nanoTime() / 1_000_000L }

    private fun logInfo(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    fun markAppCreated() {
        val now = nowElapsedMs()
        appCreateMs.compareAndSet(0L, now)
    }

    fun markFirstFrame(displayHz: Float) {
        currentDisplayHz = displayHz.coerceIn(30f, 165f)
        val now = nowElapsedMs()
        if (firstFrameMs.compareAndSet(0L, now)) {
            val start = appCreateMs.get()
            val cold = if (start > 0L && now >= start) now - start else 0L
            val heapMb = currentHeapMb()
            _snapshot.value = _snapshot.value.copy(
                coldStartMs = cold,
                displayHz = currentDisplayHz,
                heapUsedMb = heapMb
            )
            logInfo("COLD_START ms=$cold displayHz=${currentDisplayHz.toInt()} heapMb=$heapMb")
        }
    }

    fun setActiveEngine(engine: String) {
        if (activeEngineLabel == engine) return
        activeEngineLabel = engine
        _snapshot.value = _snapshot.value.copy(activeEngine = engine)
    }

    fun markNavigationStart(url: String, engine: String) {
        if (url.isBlank() || url == "about:blank") return
        activeEngineLabel = engine
        currentUrl = url
        navStartMs.set(nowElapsedMs())
    }

    fun markPageLoaded(url: String, engine: String) {
        if (url.isBlank() || url == "about:blank") return
        activeEngineLabel = engine
        currentUrl = url
        val started = navStartMs.getAndSet(0L)
        if (started <= 0L) return
        val elapsed = (nowElapsedMs() - started).coerceAtLeast(1L)
        val count = pageLoadCount.incrementAndGet()
        val sum = totalPageLoadMs.addAndGet(elapsed)
        val avg = sum / count
        val heapMb = currentHeapMb()
        _snapshot.value = _snapshot.value.copy(
            lastPageLoadMs = elapsed,
            avgPageLoadMs = avg,
            pageLoadsMeasured = count,
            activeEngine = engine,
            heapUsedMb = heapMb,
            lastUrl = url.take(120)
        )
        logInfo("PAGE_LOAD engine=$engine ms=$elapsed avgMs=$avg count=$count heapMb=$heapMb url=${url.take(120)}")
    }

    fun recordFrameDeltaNs(deltaNs: Long, fpsHint: Int = 0) {
        if (deltaNs !in 1_000_000L..500_000_000L) return
        var shouldPublish = false
        var snapFps = fpsHint
        var p50 = 0f
        var p95 = 0f
        var p99 = 0f
        var jank = 0L
        var severe = 0L
        var total = 0L
        synchronized(ringLock) {
            frameRingNs[ringCursor] = deltaNs
            ringCursor = (ringCursor + 1) % RING_SIZE
            if (ringCount < RING_SIZE) ringCount++
            totalFrames++
            if (deltaNs > JANK_NS) jankFrames++
            if (deltaNs > SEVERE_JANK_NS) severeJankFrames++
            val nowMs = nowElapsedMs()
            if (nowMs - lastPublishMs >= 500L) {
                lastPublishMs = nowMs
                shouldPublish = true
                val n = ringCount
                if (n > 0) {
                    val copy = LongArray(n)
                    for (i in 0 until n) copy[i] = frameRingNs[i]
                    copy.sort()
                    p50 = copy[(n * 50 / 100).coerceAtMost(n - 1)] / 1_000_000f
                    p95 = copy[(n * 95 / 100).coerceAtMost(n - 1)] / 1_000_000f
                    p99 = copy[(n * 99 / 100).coerceAtMost(n - 1)] / 1_000_000f
                    if (snapFps <= 0) {
                        var sumNs = 0L
                        for (v in copy) sumNs += v
                        val avgNs = (sumNs / n).coerceAtLeast(1_000_000L)
                        snapFps = (1_000_000_000L / avgNs).toInt().coerceIn(1, 240)
                    }
                }
                jank = jankFrames
                severe = severeJankFrames
                total = totalFrames
            }
        }
        if (shouldPublish) {
            val pct = if (total > 0L) (jank * 100f) / total.toFloat() else 0f
            _snapshot.value = _snapshot.value.copy(
                fps = snapFps.coerceIn(1, 240),
                frameP50Ms = p50,
                frameP95Ms = p95,
                frameP99Ms = p99,
                jankFrames = jank,
                severeJankFrames = severe,
                totalFrames = total,
                jankPercent = pct,
                displayHz = currentDisplayHz,
                activeEngine = activeEngineLabel,
                heapUsedMb = currentHeapMb()
            )
        }
    }

    fun recordCookieFlush() {
        val count = cookieFlushes.incrementAndGet()
        val now = System.currentTimeMillis()
        _snapshot.value = _snapshot.value.copy(
            cookieFlushCount = count,
            lastCookieFlushAtMs = now
        )
    }

    fun dumpSummary(reason: String = "check"): String {
        val s = _snapshot.value
        val msg = "SUMMARY[$reason] coldStartMs=${s.coldStartMs} lastLoadMs=${s.lastPageLoadMs} avgLoadMs=${s.avgPageLoadMs} " +
            "fps=${s.fps} p50=${"%.1f".format(s.frameP50Ms)}ms p95=${"%.1f".format(s.frameP95Ms)}ms p99=${"%.1f".format(s.frameP99Ms)}ms " +
            "jank=${s.jankFrames}/${s.totalFrames} (${"%.1f".format(s.jankPercent)}%) severeJank=${s.severeJankFrames} " +
            "hz=${s.displayHz.toInt()} heapMb=${s.heapUsedMb} engine=${s.activeEngine} cookieFlushes=${s.cookieFlushCount}"
        logInfo(msg)
        return msg
    }

    fun resetForTest() {
        synchronized(ringLock) {
            frameRingNs.fill(0L)
            ringCursor = 0
            ringCount = 0
            totalFrames = 0L
            jankFrames = 0L
            severeJankFrames = 0L
            lastPublishMs = 0L
        }
        appCreateMs.set(0L)
        firstFrameMs.set(0L)
        navStartMs.set(0L)
        totalPageLoadMs.set(0L)
        pageLoadCount.set(0)
        cookieFlushes.set(0)
        _snapshot.value = PerfSnapshot()
    }

    private fun currentHeapMb(): Int {
        val rt = Runtime.getRuntime()
        return (((rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)) / (1024L * 1024L)).toInt()
    }
}
