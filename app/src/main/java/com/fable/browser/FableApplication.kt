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

    // Lazy so that nothing is constructed in GeckoView's child processes.
    @Inject lateinit var engineManager: Lazy<EngineManager>
    @Inject lateinit var tabManager: Lazy<TabManager>

    /** True only in the app's main (UI) process. */
    val isMainProcess: Boolean by lazy { currentProcessName().let { it == null || it == packageName } }

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs web content in extra processes (":tab0", ":gpu", ...) and Android
        // creates this Application object in each of them too. Starting a second
        // GeckoRuntime in such a process breaks the content process and crashes tabs,
        // so all app initialisation is restricted to the main process.
        if (!isMainProcess) return

        CrashReporter.install(this)
        // Create the engine right after start-up so the first page load is fast.
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
