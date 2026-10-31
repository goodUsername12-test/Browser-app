package com.fable.browser

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.engine.TabManager
import com.fable.browser.ui.CrashReportDialog
import com.fable.browser.ui.FableApp
import com.fable.browser.util.ActivityBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var tabManager: TabManager
    @Inject lateinit var activityBridge: ActivityBridge
    @Inject lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Must happen before setContent: registers the file picker and the
        // runtime-permission launcher the engine layer calls into.
        activityBridge.bind(this)

        // Singleton TabManager survives configuration changes, so only restore
        // when the process really is starting cold.
        if (tabManager.tabs.value.isEmpty()) tabManager.restoreOrCreate()
        handleIntent(intent)

        // FLAG_SECURE while in incognito: blocks screenshots, screen recording,
        // and blanks the app-switcher thumbnail. Also hide title from Recents.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                tabManager.isIncognitoMode.collect { incognito ->
                    if (incognito) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                        setTaskDescription(appTaskDescription())
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        observeFullScreen()
        observeRefreshRate()

        // FableApp owns the theme so that entering incognito repaints everything.
        setContent {
            FableApp()
            CrashReportDialog()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFullScreen() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tabManager.activeTabId
                    .flatMapLatest { id -> tabManager.tab(id)?.isFullScreen ?: flowOf(false) }
                    .combine(tabManager.manualFullScreen) { web, manual -> web || manual }
                    .distinctUntilChanged()
                    .collect { fullScreen -> applyFullScreen(fullScreen) }
            }
        }
    }

    /** Run the screen at its highest refresh rate (90/120 Hz panels) for smooth games. */
    private fun observeRefreshRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsStore.settings
                    .map { it.highRefreshRate }
                    .distinctUntilChanged()
                    .collect { high -> runCatching { applyRefreshRate(high) } }
            }
        }
    }

    private fun applyRefreshRate(high: Boolean) {
        @Suppress("DEPRECATION")
        val display = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) this.display else windowManager.defaultDisplay
            ) ?: return
        val current = display.mode
        val modeId = if (high) {
            display.supportedModes
                .filter {
                    it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?.modeId ?: 0
        } else {
            0
        }
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != modeId) {
            attrs.preferredDisplayModeId = modeId
            window.attributes = attrs
        }
    }

    private fun applyFullScreen(fullScreen: Boolean) {
        // Never let a window/inset quirk on some phones take the app down.
        runCatching { applyFullScreenUnsafe(fullScreen) }
    }

    private fun applyFullScreenUnsafe(fullScreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullScreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setCutoutMode(fullScreen = true)
        } else {
            setCutoutMode(fullScreen = false)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Use the whole screen, including the camera notch area, while in full screen. */
    private fun setCutoutMode(fullScreen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val mode = if (fullScreen) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (window.attributes.layoutInDisplayCutoutMode != mode) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
        }
    }

    @Suppress("DEPRECATION")
    private fun appTaskDescription(): ActivityManager.TaskDescription {
        val label = getString(R.string.app_name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder().setLabel(label).build()
        } else {
            ActivityManager.TaskDescription(label)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { url ->
                tabManager.openExternalUrl(url)
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { q ->
                tabManager.openExternalSearch(q)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        tabManager.onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        tabManager.onAppResumed()
    }

    override fun onPause() {
        // Stop page rendering before Android removes the drawing surface; this
        // prevents the false "Fable Browser isn't responding" when switching apps.
        tabManager.onAppPaused()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Persist normal tabs; if the incognito lock is on, arm it for next resume.
        tabManager.onAppBackgrounded()
    }

    override fun onDestroy() {
        // Drop the Activity reference so the singleton engine layer cannot leak it.
        activityBridge.unbind(this)
        if (isFinishing) tabManager.onAppExit()
        super.onDestroy()
    }
}
