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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fable.browser.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

    fun foregroundNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.downloading))
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun showProgress(id: Long, fileName: String, written: Long, total: Long) {
        if (!canPost()) return
        val indeterminate = total <= 0
        val percent = if (indeterminate) 0 else ((written * 100) / total).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(
                if (indeterminate) context.getString(R.string.downloading)
                else context.getString(R.string.download_percent, percent)
            )
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .build()
        manager.notify(notificationId(id), notification)
    }

    fun showComplete(id: Long, fileName: String, contentUri: Uri?, mimeType: String?) {
        if (!canPost()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText(context.getString(R.string.download_complete))
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

    fun showFailure(fileName: String) {
        if (!canPost()) return
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

    private fun notificationId(id: Long): Int = (BASE_ID + id).toInt()

    companion object {
        const val CHANNEL_ID = "fable_downloads"
        const val FOREGROUND_ID = 1000
        private const val BASE_ID = 2000L
    }
}
