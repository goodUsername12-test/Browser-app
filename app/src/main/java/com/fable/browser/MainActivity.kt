package com.fable.browser

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
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
import android.view.KeyEvent
import android.view.MotionEvent
import com.fable.browser.engine.FableBatteryGovernor
import com.fable.browser.engine.ScreenRecorderManager
import com.fable.browser.util.FreezeWatchdog
import com.fable.browser.util.HighRefreshRateEnforcer
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.engine.CacheCleaner
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var tabManager: TabManager
    @Inject lateinit var activityBridge: ActivityBridge
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var cacheCleaner: CacheCleaner

    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile private var currentHighRefresh: Boolean = true
    @Volatile private var currentShowButtonPresses: Boolean = false

    private fun checkScreenRecordingState() {
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            val recording = dm.displays.any { d ->
                d.displayId != Display.DEFAULT_DISPLAY && (
                    (d.flags and Display.FLAG_PRESENTATION) != 0 ||
                        d.name.contains("record", ignoreCase = true) ||
                        d.name.contains("screen", ignoreCase = true) ||
                        d.name.contains("virtual", ignoreCase = true)
                    )
            }
            com.fable.browser.engine.NativeFastEngine.setScreenRecording(recording)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.setFormat(android.graphics.PixelFormat.OPAQUE)
        enableEdgeToEdge()

        FableBatteryGovernor.init(this)
        activityBridge.bind(this)

        if (tabManager.tabs.value.isEmpty()) tabManager.restoreOrCreate()
        handleIntent(intent)

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
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (dm != null) {
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = checkScreenRecordingState()
                    override fun onDisplayRemoved(displayId: Int) = checkScreenRecordingState()
                    override fun onDisplayChanged(displayId: Int) = checkScreenRecordingState()
                }
                displayListener = listener
                dm.registerDisplayListener(listener, null)
                checkScreenRecordingState()
            }
        }
        lifecycleScope.launch {
            settingsStore.settings
                .map { it.freezeDetector }
                .distinctUntilChanged()
                .collect { FreezeWatchdog.setEnabled(it) }
        }

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

    private data class RefreshConfig(
        val highRefresh: Boolean,
        val saverMaster: Boolean,
        val gameMaxFps: Int,
        val showButtons: Boolean
    )

    private fun observeRefreshRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsStore.settings
                    .map { RefreshConfig(it.highRefreshRate, it.batterySaverMaster, it.batteryGameMaxFps, it.recShowButtonPresses) }
                    .distinctUntilChanged()
                    .collect { cfg ->
                        currentHighRefresh = cfg.highRefresh && !cfg.saverMaster
                        currentShowButtonPresses = cfg.showButtons
                        runCatching {
                            if (!cfg.highRefresh) {
                                applyRefreshRate(false)
                            } else if (!cfg.saverMaster && cfg.gameMaxFps >= 115) {
                                applyRefreshRate(true)
                            } else {
                                val targetHz = if (cfg.saverMaster && cfg.gameMaxFps >= 120) 90f else cfg.gameMaxFps.toFloat()
                                HighRefreshRateEnforcer.applyCustomRateToWindow(this@MainActivity, targetHz)
                            }
                        }
                    }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN || ev?.actionMasked == MotionEvent.ACTION_MOVE) {
            FableBatteryGovernor.onUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyRefreshRate(high: Boolean) {
        HighRefreshRateEnforcer.applyToWindow(this, high)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            HighRefreshRateEnforcer.applyToWindow(this, currentHighRefresh)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentShowButtonPresses && ScreenRecorderManager.isRecording.value) {
            val label = when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> "Back"
                KeyEvent.KEYCODE_VOLUME_UP -> "Volume +"
                KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume -"
                KeyEvent.KEYCODE_VOLUME_MUTE -> "Mute"
                KeyEvent.KEYCODE_MENU -> "Menu"
                else -> null
            }
            if (label != null) {
                ScreenRecorderManager.onButtonPress(label, true)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyFullScreen(fullScreen: Boolean) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { window.setSustainedPerformanceMode(false) }
            }
            com.fable.browser.engine.NativeFastEngine.setGameBoost(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val a = window.attributes
                    a.preferMinimalPostProcessing = true
                    window.attributes = a
                }
            }
        } else {
            setCutoutMode(fullScreen = false)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            com.fable.browser.engine.NativeFastEngine.setGameBoost(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { window.setSustainedPerformanceMode(false) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val a = window.attributes
                    a.preferMinimalPostProcessing = false
                    window.attributes = a
                }
            }
        }
    }

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
                val data = intent.data
                if (data != null && (data.scheme == "content" || data.scheme == "file")) {
                    openLocalHtml(data, intent.type)
                } else if (BuildConfig.DEBUG && intent.getBooleanExtra("fable_super", false)) {
                    tabManager.openSuperTab(url)
                } else if (BuildConfig.DEBUG && intent.getBooleanExtra("fable_incognito", false)) {
                    tabManager.openTab(url, incognito = true)
                } else if (BuildConfig.DEBUG && intent.getBooleanExtra("fable_hybrid", false)) {
                    val tab = tabManager.openTab(incognito = false)
                    tabManager.setHybridBlinkMode(tab, true)
                    tabManager.load(tab, url)
                } else {
                    tabManager.openExternalUrl(url)
                }
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { q ->
                tabManager.openExternalSearch(q)
            }
            else -> if (BuildConfig.DEBUG && intent?.getBooleanExtra("fable_save_page_code", false) == true) {
                tabManager.savePageCode()
            }
        }
    }

    private fun openLocalHtml(uri: Uri, type: String?) {
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { copyLocalHtml(uri, type) }
            if (local != null) tabManager.openExternalUrl(Uri.fromFile(local).toString())
            else activityBridge.toast(getString(R.string.cannot_open_file))
        }
    }

    private fun copyLocalHtml(uri: Uri, type: String?): File? = runCatching {
        val rawName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "page.html"
        val safe = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "page.html" }
        val lower = safe.lowercase()
        val ext = when {
            lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml") || lower.endsWith(".mht") || lower.endsWith(".mhtml") -> ""
            type == "application/xhtml+xml" -> ".xhtml"
            type == "multipart/related" || type == "message/rfc822" -> ".mht"
            else -> ".html"
        }
        val dir = File(cacheDir, "opened_html/" + System.currentTimeMillis())
        if (!dir.mkdirs() && !dir.isDirectory) return@runCatching null
        val out = File(dir, safe + ext)
        val input = contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
        File(cacheDir, "opened_html").listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.deleteRecursively() }
        out
    }.getOrNull()

    override fun onStart() {
        super.onStart()
        FreezeWatchdog.onForeground()
        tabManager.onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        HighRefreshRateEnforcer.applyToWindow(this, currentHighRefresh)
        Choreographer.getInstance().postFrameCallback {
            window.decorView.post {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tabManager.onAppResumed()
            }
        }
    }

    override fun onPause() {
        tabManager.onAppPaused()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        tabManager.onAppBackgrounded(temporary = activityBridge.externalPickerOpen)
        if (!activityBridge.externalPickerOpen) cacheCleaner.onAppBackgrounded()
        FreezeWatchdog.onBackground()
    }

    override fun onDestroy() {
        runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayListener?.let { dm?.unregisterDisplayListener(it) }
            displayListener = null
        }
        activityBridge.unbind(this)
        if (isFinishing) tabManager.onAppExit()
        super.onDestroy()
    }
}
