package com.fable.browser

import android.app.Application
import android.os.Build
import com.fable.browser.engine.EngineManager
import com.fable.browser.engine.TabManager
import com.fable.browser.util.CrashReporter
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class FableApplication : Application() {
    @Inject lateinit var engineManager: Lazy<EngineManager>
    @Inject lateinit var tabManager: Lazy<TabManager>

    val isMainProcess: Boolean by lazy { currentProcessName().let { it == null || it == packageName } }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess) return

        CrashReporter.install(this)
        engineManager.get().warmUp()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isMainProcess) tabManager.get().onTrimMemory(level)
    }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readBytes()
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(Charsets.UTF_8)
                    .trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
}
