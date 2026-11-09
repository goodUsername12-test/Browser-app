package com.fable.browser.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FreezeWatchdog {
    private const val PING_MS = 1_000L
    private const val FREEZE_MS = 2_500L
    private const val FILE = "freeze_report.txt"

    @Volatile private var enabled = true
    @Volatile private var runUntil = Long.MAX_VALUE
    @Volatile private var answered = true
    @Volatile private var pingAt = 0L
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pong = Runnable { answered = true }

    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Thread({ loop() }, "FableFreezeWatchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
    }

    fun setEnabled(on: Boolean) { enabled = on }

    fun onForeground() { runUntil = Long.MAX_VALUE }

    fun onBackground() { runUntil = SystemClock.uptimeMillis() + 15_000L }

    private fun loop() {
        var lastWake = SystemClock.uptimeMillis()
        var samples = 0
        var reportedFor = -1L
        while (true) {
            try {
                Thread.sleep(PING_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = SystemClock.uptimeMillis()
            val overslept = now - lastWake > PING_MS * 3
            lastWake = now
            if (!enabled || now > runUntil || overslept) {
                answered = true
                samples = 0
                continue
            }
            if (answered) {
                if (samples > 0 && reportedFor == pingAt) finish(now - pingAt)
                samples = 0
                answered = false
                pingAt = now
                main.post(pong)
                continue
            }
            val stuck = now - pingAt
            if (stuck >= FREEZE_MS && samples < 2) {
                samples++
                reportedFor = pingAt
                record(stuck, samples)
            }
        }
    }

    private val lines = StringBuilder()

    private fun record(stuckMs: Long, sample: Int) {
        val ctx = appContext ?: return
        runCatching {
            if (sample == 1) {
                lines.setLength(0)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                lines.append("== Freeze (screen thread stuck) at ").append(time).append('\n')
            }
            lines.append("Sample ").append(sample).append(" after ")
                .append(stuckMs).append(" ms. Main thread:\n")
            val main = Looper.getMainLooper().thread
            main.stackTrace.take(45).forEach { lines.append("  at ").append(it).append('\n') }
            if (sample == 1) {
                Thread.getAllStackTraces().forEach { (t, stack) ->
                    val n = t.name
                    if (t !== main && stack.isNotEmpty() &&
                        (n.contains("Gecko", true) || n.contains("InputConnection", true) || n.contains("IC", false))
                    ) {
                        lines.append("Thread ").append(n).append(" (").append(t.state).append("):\n")
                        stack.take(12).forEach { lines.append("    at ").append(it).append('\n') }
                    }
                }
            }
            File(ctx.filesDir, FILE).writeText(lines.toString())
        }
    }

    private fun finish(totalMs: Long) {
        val ctx = appContext ?: return
        runCatching {
            lines.append("Recovered after about ").append(totalMs).append(" ms.\n")
            File(ctx.filesDir, FILE).writeText(lines.toString())
        }
    }

    fun pendingReport(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
