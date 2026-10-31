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

/**
 * Saves the stack trace of an app crash so it can be shown (and copied or reported)
 * the next time the browser starts. Nothing is sent anywhere automatically.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(appContext).writeText(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
        // Android 11+: also learn about freezes (ANR) and native/engine crashes,
        // which never reach the Java handler above. Runs off the main thread.
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
        // A Java crash report is more precise: keep it if there is one.
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
        // Killed for memory while on screen (e.g. a big game page).
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

    /** The "main" thread block of an ANR trace: shows exactly what the UI thread was waiting for. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun mainThreadTrace(info: ApplicationExitInfo): String? {
        val stream = info.traceInputStream ?: return null
        val lines = stream.bufferedReader().use { reader -> reader.lineSequence().take(4000).toList() }
        val subject = lines.firstOrNull { it.startsWith("Subject:") }
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return subject
        val block = lines.drop(start).takeWhile { it.isNotBlank() }.take(60)
        return (listOfNotNull(subject) + block).joinToString("\n")
    }

    fun pendingReport(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
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
