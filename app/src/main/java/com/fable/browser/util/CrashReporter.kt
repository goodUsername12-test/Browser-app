package com.fable.browser.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import androidx.annotation.RequiresApi

import android.content.Context
import android.os.Build
import com.fable.browser.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(appContext).writeText(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
        FreezeWatchdog.install(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread({ runCatching { collectExitInfo(appContext) } }, "fable-exit-info").start()
        }
    }

    private const val PREFS = "fable_crash_reporter"
    private const val KEY_LAST_EXIT = "last_exit_timestamp"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectExitInfo(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_EXIT, System.currentTimeMillis() - DAY_MS)
        val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            .filter { it.timestamp > last }
        if (fresh.isEmpty()) return
        prefs.edit().putLong(KEY_LAST_EXIT, fresh.maxOf { it.timestamp }).apply()
        val worth = fresh.filter { isWorthReporting(it) }.sortedByDescending { it.timestamp }
        if (worth.isEmpty()) return
        if (file(context).exists()) return
        val report = buildString {
            appendLine("Fable Browser ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("The last session had a problem that Android reported:")
            worth.take(3).forEach { appendExit(context, it) }
        }
        file(context).writeText(report.take(24_000))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isWorthReporting(info: ApplicationExitInfo): Boolean = when (info.reason) {
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH_NATIVE -> true
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun StringBuilder.appendExit(context: Context, info: ApplicationExitInfo) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed: out of memory"
            else -> "Exit reason ${info.reason}"
        }
        val process = if (info.processName == context.packageName) "app" else info.processName
        appendLine()
        appendLine("== $reason in $process at $time")
        info.description?.let { appendLine("Details: ${it.take(400)}") }
        appendLine("Importance: ${info.importance}, memory: ${info.pss / 1024} MB PSS / ${info.rss / 1024} MB RSS")
        if (info.reason == ApplicationExitInfo.REASON_ANR) {
            val main = runCatching { mainThreadTrace(info) }.getOrNull()
            if (!main.isNullOrBlank()) {
                appendLine("Main thread:")
                appendLine(main)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mainThreadTrace(info: ApplicationExitInfo): String? {
        val stream = info.traceInputStream ?: return null
        var subject: String? = null
        val block = ArrayList<String>()
        var inMain = false
        var scanned = 0
        stream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (++scanned > 200_000) break
                if (subject == null && line.startsWith("Subject:")) subject = line
                if (!inMain) {
                    if (line.startsWith("\"main\"") || (line.startsWith("\"") && line.contains(" tid=1 "))) {
                        inMain = true
                        block += line
                    }
                } else {
                    if (line.isBlank() || block.size >= 70) break
                    block += line
                }
            }
        }
        if (block.isEmpty()) {
            return listOfNotNull(subject, "(Android did not include the main thread; see the freeze report below if there is one.)")
                .joinToString("\n")
        }
        val idle = block.any { it.contains("nativePollOnce") } && block.size < 14
        val note = if (idle) "(Main thread was already idle when Android took the dump.)" else null
        return (listOfNotNull(subject, note) + block).joinToString("\n")
    }

    fun pendingReport(context: Context): String? {
        val crash = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
        val freeze = FreezeWatchdog.pendingReport(context)
        return listOfNotNull(crash?.takeIf { it.isNotBlank() }, freeze).joinToString("\n\n").ifBlank { null }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        FreezeWatchdog.clear(context)
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun describe(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("Fable Browser ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time: $time, thread: ${thread.name}")
            appendLine()
            append(trace.take(12_000))
        }
    }
}
