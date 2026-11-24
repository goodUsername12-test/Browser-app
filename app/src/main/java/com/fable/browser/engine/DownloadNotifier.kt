package com.fable.browser.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fable.browser.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

data class DownloadProgressFormat(
    val title: String,
    val compactText: String,
    val expandedText: String,
    val percent: Int,
    val indeterminate: Boolean
)

@Singleton
class DownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private data class ActiveTransfer(
        val id: Long,
        val fileName: String,
        val startedMs: Long,
        var lastSampleMs: Long,
        var lastSampleBytes: Long,
        var writtenBytes: Long,
        var totalBytes: Long,
        var smoothedSpeedBps: Double
    )

    private val active = ConcurrentHashMap<Long, ActiveTransfer>()

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun foregroundNotification(): Notification {
        buildUnifiedProgressNotification()?.let { return it }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.downloading))
            .setContentText("Starting download...")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    fun showProgress(id: Long, fileName: String, written: Long, total: Long) {
        val now = monotonicMs()
        val state = active.compute(id) { _, prev ->
            if (prev == null) {
                ActiveTransfer(
                    id = id,
                    fileName = fileName,
                    startedMs = now,
                    lastSampleMs = now,
                    lastSampleBytes = written.coerceAtLeast(0L),
                    writtenBytes = written.coerceAtLeast(0L),
                    totalBytes = total,
                    smoothedSpeedBps = 0.0
                )
            } else {
                val safeWritten = written.coerceAtLeast(0L)
                val dtMs = now - prev.lastSampleMs
                val dBytes = safeWritten - prev.lastSampleBytes
                if (dtMs >= 250L && dBytes >= 0L) {
                    val instantBps = (dBytes * 1000.0) / dtMs.toDouble()
                    prev.smoothedSpeedBps = if (prev.smoothedSpeedBps <= 1.0) {
                        instantBps
                    } else {
                        prev.smoothedSpeedBps * 0.65 + instantBps * 0.35
                    }
                    prev.lastSampleMs = now
                    prev.lastSampleBytes = safeWritten
                } else if (prev.smoothedSpeedBps <= 1.0 && safeWritten > 0L) {
                    val totalDt = (now - prev.startedMs).coerceAtLeast(100L)
                    prev.smoothedSpeedBps = (safeWritten * 1000.0) / totalDt.toDouble()
                }
                prev.writtenBytes = safeWritten
                if (total > 0L) prev.totalBytes = total
                prev
            }
        }
        if (state == null || !canPost()) return
        buildUnifiedProgressNotification()?.let { notif ->
            manager.notify(FOREGROUND_ID, notif)
        }
    }

    fun cancelProgress(id: Long) {
        active.remove(id)
        if (!canPost()) return
        val next = buildUnifiedProgressNotification()
        if (next != null) {
            manager.notify(FOREGROUND_ID, next)
        }
    }

    fun showComplete(id: Long, fileName: String, contentUri: Uri?, mimeType: String?, finalBytes: Long = -1L) {
        active.remove(id)
        if (!canPost()) return
        buildUnifiedProgressNotification()?.let { manager.notify(FOREGROUND_ID, it) }
        val sizeSuffix = if (finalBytes > 0L) " · ${formatSize(finalBytes)}" else ""
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText("${context.getString(R.string.download_complete)}$sizeSuffix")
            .setAutoCancel(true)
        if (contentUri != null) {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId(id),
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        manager.notify(notificationId(id), builder.build())
    }

    fun showFailure(fileName: String, id: Long = -1L) {
        if (id >= 0L) active.remove(id)
        if (!canPost()) return
        buildUnifiedProgressNotification()?.let { manager.notify(FOREGROUND_ID, it) }
        manager.notify(
            fileName.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(fileName)
                .setContentText(context.getString(R.string.download_failed))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun buildUnifiedProgressNotification(): Notification? {
        val list = active.values.toList()
        if (list.isEmpty()) return null
        val fmt = if (list.size == 1) {
            val t = list[0]
            formatProgress(t.fileName, t.writtenBytes, t.totalBytes, t.smoothedSpeedBps)
        } else {
            val sumWritten = list.sumOf { it.writtenBytes }
            val allKnown = list.all { it.totalBytes > 0L }
            val sumTotal = if (allKnown) list.sumOf { it.totalBytes } else -1L
            val sumSpeed = list.sumOf { it.smoothedSpeedBps }
            formatProgress("${list.size} downloads", sumWritten, sumTotal, sumSpeed)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fmt.title)
            .setContentText(fmt.compactText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fmt.expandedText))
            .setProgress(100, fmt.percent, fmt.indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun notificationId(id: Long): Int = (BASE_ID + id).toInt()

    private fun monotonicMs(): Long {
        val up = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        return if (up > 0L) up else (System.nanoTime() / 1_000_000L)
    }

    companion object {
        const val CHANNEL_ID = "fable_downloads"
        const val FOREGROUND_ID = 1000
        private const val BASE_ID = 2000L

        fun formatSize(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L)
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            return when {
                b >= gb -> String.format(Locale.US, "%.2f GB", b / gb)
                b >= mb -> String.format(Locale.US, "%.2f MB", b / mb)
                b >= kb -> String.format(Locale.US, "%.1f KB", b / kb)
                else -> "$b B"
            }
        }

        fun formatMegabytes(bytes: Long): String {
            val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.2f MB", mb)
        }

        fun formatSpeed(bytesPerSec: Double): String {
            if (!bytesPerSec.isFinite() || bytesPerSec <= 1.0) return "calculating..."
            val kb = 1024.0
            val mb = kb * 1024.0
            return when {
                bytesPerSec >= mb -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / mb)
                bytesPerSec >= kb -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / kb)
                else -> "${bytesPerSec.toInt()} B/s"
            }
        }

        fun formatEtaSeconds(remainingBytes: Long, bytesPerSec: Double): String {
            if (remainingBytes <= 0L) return "0s left"
            if (!bytesPerSec.isFinite() || bytesPerSec <= 1.0) return "estimating..."
            val sec = ceil(remainingBytes / bytesPerSec).toLong().coerceAtLeast(1L)
            return when {
                sec < 60L -> "${sec}s left"
                sec < 3600L -> "${sec / 60L}m ${sec % 60L}s left"
                else -> "${sec / 3600L}h ${(sec % 3600L) / 60L}m ${sec % 60L}s left"
            }
        }

        fun formatProgress(
            fileName: String,
            written: Long,
            total: Long,
            speedBps: Double
        ): DownloadProgressFormat {
            val safeWritten = written.coerceAtLeast(0L)
            val speedStr = formatSpeed(speedBps)
            if (total <= 0L) {
                val doneStr = formatMegabytes(safeWritten)
                val compact = "$doneStr downloaded · $speedStr"
                val expanded = "Downloaded: $doneStr\nSpeed: $speedStr"
                return DownloadProgressFormat(
                    title = fileName,
                    compactText = compact,
                    expandedText = expanded,
                    percent = 0,
                    indeterminate = true
                )
            }
            val safeTotal = maxOf(total, safeWritten)
            val remaining = (safeTotal - safeWritten).coerceAtLeast(0L)
            val pct = ((safeWritten * 100L) / safeTotal).toInt().coerceIn(0, 100)
            val doneMb = formatMegabytes(safeWritten)
            val totalMb = formatMegabytes(safeTotal)
            val leftMb = formatMegabytes(remaining)
            val eta = formatEtaSeconds(remaining, speedBps)
            val compact = "$doneMb / $totalMb · $leftMb left · $speedStr · $eta"
            val expanded = "$doneMb / $totalMb ($pct%) · $leftMb left\nSpeed: $speedStr · $eta"
            return DownloadProgressFormat(
                title = "$fileName ($pct%)",
                compactText = compact,
                expandedText = expanded,
                percent = pct,
                indeterminate = false
            )
        }
    }
}
