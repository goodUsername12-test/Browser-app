package com.fable.browser.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var downloader: Downloader
    @Inject lateinit var notifier: DownloadNotifier

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                DownloadNotifier.FOREGROUND_ID,
                notifier.foregroundNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else 0
            )
        }.isSuccess
        if (!started) stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watcher == null) {
            watcher = scope.launch {
                downloader.activeCount.collect { active ->
                    if (active == 0) {
                        ServiceCompat.stopForeground(
                            this@DownloadService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE
                        )
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
