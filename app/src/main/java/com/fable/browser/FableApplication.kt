package com.fable.browser

import android.app.Application
import com.fable.browser.engine.EngineManager
import com.fable.browser.engine.TabManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FableApplication : Application() {

    @Inject lateinit var engineManager: EngineManager
    @Inject lateinit var tabManager: TabManager

    override fun onCreate() {
        super.onCreate()
        // Warm the engine on a background thread so the first page load is fast.
        engineManager.warmUp()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        tabManager.onTrimMemory(level)
    }
}
