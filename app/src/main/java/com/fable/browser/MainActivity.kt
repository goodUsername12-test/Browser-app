package com.fable.browser

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
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
import com.fable.browser.util.FreezeWatchdog
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                } else {
                    tabManager.openExternalUrl(url)
                }
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { q ->
                tabManager.openExternalSearch(q)
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
        activityBridge.unbind(this)
        if (isFinishing) tabManager.onAppExit()
        super.onDestroy()
    }
}
