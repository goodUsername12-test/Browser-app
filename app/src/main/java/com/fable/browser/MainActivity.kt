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
import kotlinx.coroutines.flow.first
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
    @Inject lateinit var memoryEngineManager: com.fable.browser.engine.MemoryEngineManager

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
        lifecycleScope.launch(Dispatchers.IO) {
            com.fable.browser.engine.FableProxyProfileManager.ensureLoaded(applicationContext)
        }
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
        Choreographer.getInstance().postFrameCallback {
            val hz = HighRefreshRateEnforcer.resolveMaxRefreshRate(this@MainActivity)
            com.fable.browser.engine.PerformanceTelemetry.markFirstFrame(hz)
            runCatching { reportFullyDrawn() }
            if (!com.fable.browser.engine.MemoryPolicy.isWeakDevice(applicationContext)) {
                com.fable.browser.engine.FableUnifiedEngine.prewarmBlinkPostFrame(applicationContext)
            }
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
        val ecoCpu: Boolean,
        val gameMaxFps: Int,
        val showButtons: Boolean,
        val hybridActive: Boolean
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRefreshRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val hybridFlow = tabManager.activeTabId
                    .flatMapLatest { id -> tabManager.tab(id)?.isHybridBlink ?: flowOf(false) }
                    .distinctUntilChanged()
                settingsStore.settings
                    .combine(hybridFlow) { s, hybrid ->
                        RefreshConfig(
                            highRefresh = s.highRefreshRate,
                            saverMaster = s.batterySaverMaster,
                            ecoCpu = s.batteryEcoCpu,
                            gameMaxFps = s.batteryGameMaxFps,
                            showButtons = s.recShowButtonPresses,
                            hybridActive = hybrid
                        )
                    }
                    .distinctUntilChanged()
                    .collect { cfg ->
                        currentHighRefresh = cfg.highRefresh && !cfg.saverMaster && (cfg.hybridActive || !cfg.ecoCpu)
                        currentShowButtonPresses = cfg.showButtons
                        runCatching {
                            if (!cfg.highRefresh || (!cfg.hybridActive && (cfg.saverMaster || cfg.ecoCpu))) {
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
        if (BuildConfig.DEBUG && intent != null) {
            android.util.Log.i("FABLE-INTENT", "action=${intent.action} data=${intent.dataString} hybrid=${intent.getBooleanExtra("fable_hybrid", false)}")
        }
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
                    tab.manualHybridOverride = true
                    tabManager.setHybridBlinkMode(tab, true)
                    tabManager.load(tab, url)
                } else {
                    tabManager.openExternalUrl(url)
                }
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { q ->
                tabManager.openExternalSearch(q)
            }
            else -> if (BuildConfig.DEBUG && intent != null) {
                when {
                    intent.getBooleanExtra("fable_save_page_code", false) -> tabManager.savePageCode("all", continuous = false)
                    intent.getBooleanExtra("fable_start_snapshot", false) -> tabManager.savePageCode("memory", continuous = true)
                    intent.getBooleanExtra("fable_pause_capture", false) -> tabManager.pauseCapture()
                    intent.getBooleanExtra("fable_resume_capture", false) -> tabManager.resumeCapture()
                    intent.getBooleanExtra("fable_stop_capture", false) -> tabManager.stopAndSaveCapture()
                    intent.getBooleanExtra("fable_cancel_capture", false) -> tabManager.cancelCapture()
                    intent.getBooleanExtra("fable_perf_dump", false) -> {
                        com.fable.browser.engine.PerformanceTelemetry.dumpSummary("debug_intent")
                    }
                    intent.hasExtra("fable_test_adblock") -> {
                        val on = intent.getBooleanExtra("fable_test_adblock", true)
                        lifecycleScope.launch {
                            settingsStore.setAdBlockEnabled(on)
                            settingsStore.setStripTrackingParams(on)
                            settingsStore.setSendGpc(on)
                            settingsStore.setBlockThirdPartyCookies(on)
                            settingsStore.setBlinkPrivacyParity(on)
                            settingsStore.setIsolateNativeBridge(on)
                            android.util.Log.i("FABLE-PRIVACY-TEST", "toggled all_privacy=$on")
                        }
                    }
                    intent.hasExtra("fable_test_perf_hud") -> {
                        val on = intent.getBooleanExtra("fable_test_perf_hud", false)
                        com.fable.browser.engine.PerformanceTelemetry.hudEnabled = on
                        lifecycleScope.launch { settingsStore.setPerfHudEnabled(on) }
                    }
                    intent.hasExtra("fable_test_timescale") -> {
                        val on = intent.getBooleanExtra("fable_test_timescale", false)
                        val tenths = intent.getIntExtra("fable_test_timescale_tenths", 10).coerceIn(1, com.fable.browser.engine.WebsiteSpeedEngine.MAX_TENTHS)
                        lifecycleScope.launch {
                            settingsStore.setTimeScaleMultiplierTenths(tenths)
                            settingsStore.setTimeScaleEnabled(on)
                            android.util.Log.i("FABLE-SPEED-TEST", "set enabled=$on tenths=$tenths")
                        }
                    }
                    intent.hasExtra("fable_test_mem_toggle") -> {
                        val on = intent.getBooleanExtra("fable_test_mem_toggle", false)
                        memoryEngineManager.setEngineActive(on)
                        lifecycleScope.launch {
                            settingsStore.setMemoryEngineEnabled(on)
                            android.util.Log.i("FABLE-MEM-TEST", "toggle enabled=$on")
                        }
                    }
                    intent.hasExtra("fable_test_mem_selfcheck") -> {
                        val scopeName = intent.getStringExtra("fable_test_mem_selfcheck").orEmpty()
                        lifecycleScope.launch {
                            if (scopeName == "native") {
                                val regions = withContext(Dispatchers.IO) { com.fable.browser.engine.NativeMemoryScanner.listRegions() }
                                val regObj = runCatching { org.json.JSONObject(regions) }.getOrNull()
                                val okReg = regObj?.optBoolean("ok", false) == true && (regObj.optInt("count", 0) > 0)
                                val buf = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val sentinel1 = 193847261
                                val sentinel2 = 193847361
                                buf.putInt(0, sentinel1)
                                val r1 = withContext(Dispatchers.IO) {
                                    com.fable.browser.engine.NativeMemoryScanner.firstScan(
                                        com.fable.browser.engine.MemDataType.INT32,
                                        com.fable.browser.engine.MemScanType.EXACT,
                                        sentinel1.toString(),
                                        "",
                                        300,
                                        15_000
                                    )
                                }
                                buf.putInt(0, sentinel2)
                                val r2 = withContext(Dispatchers.IO) {
                                    com.fable.browser.engine.NativeMemoryScanner.nextScan(
                                        com.fable.browser.engine.MemScanType.INCREASED_BY,
                                        "100",
                                        "",
                                        300,
                                        15_000
                                    )
                                }
                                val r2Obj = runCatching { org.json.JSONObject(r2) }.getOrNull()
                                val firstAddr = r2Obj?.optJSONArray("items")?.optJSONObject(0)?.optString("addr").orEmpty()
                                val writeOk = if (firstAddr.isNotEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        com.fable.browser.engine.NativeMemoryScanner.writeValue(
                                            firstAddr,
                                            com.fable.browser.engine.MemDataType.INT32,
                                            "987654321"
                                        )
                                    }
                                } else false
                                val readBack = buf.getInt(0)
                                val hexOk = if (firstAddr.isNotEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        com.fable.browser.engine.NativeMemoryScanner.hexDump(firstAddr, 32).contains("\"ok\":true")
                                    }
                                } else false
                                var freezeOk = false
                                if (firstAddr.isNotEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        val locked = com.fable.browser.engine.NativeMemoryScanner.setFreeze(
                                            firstAddr,
                                            com.fable.browser.engine.MemDataType.INT32,
                                            "555444333"
                                        )
                                        if (locked) {
                                            repeat(5) {
                                                buf.putInt(0, 111)
                                                Thread.sleep(10)
                                            }
                                            Thread.sleep(20)
                                            val held = buf.getInt(0) == 555444333
                                            val stats = com.fable.browser.engine.NativeMemoryScanner.getFreezeStats()
                                            val statsObj = runCatching { org.json.JSONObject(stats).optJSONObject("stats") }.getOrNull()
                                            val natBlocked = statsObj?.optJSONObject(firstAddr)?.optLong("blocked", 0L) ?: 0L
                                            val minLocked = com.fable.browser.engine.NativeMemoryScanner.setFreeze(
                                                firstAddr,
                                                com.fable.browser.engine.MemDataType.INT32,
                                                "500",
                                                com.fable.browser.engine.MemFreezeRule.MIN.nativeCode
                                            )
                                            var minRuleOk = false
                                            if (minLocked) {
                                                buf.putInt(0, 900)
                                                Thread.sleep(15)
                                                val allowedUp = buf.getInt(0) == 900
                                                buf.putInt(0, 100)
                                                Thread.sleep(15)
                                                val clampedFloor = buf.getInt(0) == 500
                                                minRuleOk = allowedUp && clampedFloor
                                            }
                                            val structOk = com.fable.browser.engine.NativeMemoryScanner.peekStruct(firstAddr, 64).contains("\"ok\":true")
                                            var rangeRuleOk = false
                                            val rngLocked = com.fable.browser.engine.NativeMemoryScanner.setFreeze(
                                                firstAddr,
                                                com.fable.browser.engine.MemDataType.INT32,
                                                "50..100",
                                                com.fable.browser.engine.MemFreezeRule.RANGE.nativeCode
                                            )
                                            if (rngLocked) {
                                                buf.putInt(0, 10)
                                                Thread.sleep(15)
                                                val loClamp = buf.getInt(0) == 50
                                                buf.putInt(0, 82)
                                                Thread.sleep(15)
                                                val midKeep = buf.getInt(0) == 82
                                                buf.putInt(0, 250)
                                                Thread.sleep(15)
                                                val hiClamp = buf.getInt(0) == 100
                                                rangeRuleOk = loClamp && midKeep && hiClamp
                                            }
                                            freezeOk = held && natBlocked > 0L && minRuleOk && rangeRuleOk && structOk && stats.contains("\"mode\":\"500Hz\"")
                                        }
                                        com.fable.browser.engine.NativeMemoryScanner.unfreezeAll()
                                    }
                                }
                                // Ultra native checks: Group Search, XOR Auto-Key + Write, Live Hex Write, Size Manager persistence
                                var groupOk = false
                                var xorOk = false
                                var hexWriteOk = false
                                withContext(Dispatchers.IO) {
                                    buf.putInt(16, -12345)
                                    buf.putFloat(20, 77.5f)
                                    buf.putInt(24, 888999)
                                    val rg = com.fable.browser.engine.NativeMemoryScanner.firstScan(
                                        com.fable.browser.engine.MemDataType.AUTO,
                                        com.fable.browser.engine.MemScanType.GROUP,
                                        "-12345i;77.5f;888999i:32",
                                        "",
                                        100,
                                        10_000
                                    )
                                    groupOk = (runCatching { org.json.JSONObject(rg).optInt("total", 0) }.getOrDefault(0)) >= 3

                                    val xKey = 0x5A3C91E7
                                    buf.putInt(32, xKey)
                                    buf.putInt(36, 55443322 xor xKey)
                                    val rx = com.fable.browser.engine.NativeMemoryScanner.firstScan(
                                        com.fable.browser.engine.MemDataType.INT32,
                                        com.fable.browser.engine.MemScanType.XOR_ENCRYPTED,
                                        "55443322",
                                        "",
                                        400,
                                        12_000
                                    )
                                    val rxItems = runCatching { org.json.JSONObject(rx).optJSONArray("items") }.getOrNull()
                                    val expectedXorAddr = if (firstAddr.isNotEmpty()) {
                                        com.fable.browser.engine.NativeMemoryScanner.parseHexAddress(firstAddr)?.let {
                                            "0x" + java.lang.Long.toHexString(it + 36L).uppercase()
                                        }
                                    } else null
                                    if (rxItems != null && rxItems.length() > 0) {
                                        for (xi in 0 until rxItems.length()) {
                                            val xo = rxItems.optJSONObject(xi) ?: continue
                                            val candAddr = xo.optString("addr")
                                            if (xo.optInt("xorMode", 0) == 2 && (expectedXorAddr == null || candAddr.equals(expectedXorAddr, ignoreCase = true))) {
                                                buf.putInt(32, xKey)
                                                buf.putInt(36, 55443322 xor xKey)
                                                val wroteX = com.fable.browser.engine.NativeMemoryScanner.writeValue(
                                                    candAddr,
                                                    com.fable.browser.engine.MemDataType.INT32,
                                                    "9990001",
                                                    2,
                                                    xo.optLong("xorKey", 0L),
                                                    xo.optString("xorKeyAddr", "")
                                                )
                                                if (wroteX && (buf.getInt(36) xor xKey) == 9990001) {
                                                    xorOk = true
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    if (firstAddr.isNotEmpty()) {
                                        buf.putInt(0, 0x11223344)
                                        val whRes = com.fable.browser.engine.NativeMemoryScanner.writeHex(firstAddr, "AA ?? CC DD")
                                        hexWriteOk = whRes.contains("\"ok\":true") && (buf.get(0).toInt() and 0xFF) == 0xAA && (buf.get(1).toInt() and 0xFF) == 0x33 && (buf.get(2).toInt() and 0xFF) == 0xCC
                                    }
                                    com.fable.browser.engine.NativeMemoryScanner.resetScan()
                                }
                                settingsStore.setMemoryEnginePanelSize(75, 80, 115)
                                val s1Set = settingsStore.settings.first()
                                settingsStore.setMemoryEnginePanelSize(10, 200, 999)
                                val s2Clamp = settingsStore.settings.first()
                                settingsStore.resetMemoryEnginePanelLayout()
                                val s3Reset = settingsStore.settings.first()
                                settingsStore.setMemoryEngineFreezeStrength(2)
                                settingsStore.setMemoryEngineAutoReattach(true)
                                val s4V66 = settingsStore.settings.first()
                                settingsStore.setMemoryEngineFreezeStrength(1)
                                val mirOk = withContext(Dispatchers.IO) {
                                    com.fable.browser.engine.NativeMemoryScanner.setFreezeStrength(2)
                                    val mirRaw = if (firstAddr.isNotEmpty()) com.fable.browser.engine.NativeMemoryScanner.findMirrors(firstAddr, com.fable.browser.engine.MemDataType.INT32, 20, 4000) else "{}"
                                    com.fable.browser.engine.NativeMemoryScanner.setFreezeStrength(1)
                                    runCatching { org.json.JSONObject(mirRaw).optBoolean("ok", false) }.getOrDefault(false)
                                }
                                val sizeMgrOk = (s1Set.memoryEnginePanelWidthPct == 75 && s1Set.memoryEnginePanelHeightPct == 80 && s1Set.memoryEnginePanelScalePct == 115) &&
                                    (s2Clamp.memoryEnginePanelWidthPct == 55 && s2Clamp.memoryEnginePanelHeightPct == 95 && s2Clamp.memoryEnginePanelScalePct == 140) &&
                                    (s3Reset.memoryEnginePanelWidthPct == 88 && s3Reset.memoryEnginePanelHeightPct == 62 && s3Reset.memoryEnginePanelScalePct == 100) &&
                                    (s4V66.memoryEngineFreezeStrength == 2 && s4V66.memoryEngineAutoReattach) && mirOk
                                val ultraNativeOk = groupOk && xorOk && hexWriteOk && sizeMgrOk
                                android.util.Log.i(
                                    "FABLE-MEM-CHECK",
                                    "scope=native okReg=$okReg total1=${runCatching { org.json.JSONObject(r1).optInt("total") }.getOrDefault(-1)} " +
                                        "total2=${r2Obj?.optInt("total") ?: -1} writeOk=$writeOk readBack=$readBack hexOk=$hexOk freezeOk=$freezeOk " +
                                        "ultraNativeOk=$ultraNativeOk groupOk=$groupOk xorOk=$xorOk hexWriteOk=$hexWriteOk sizeMgrOk=$sizeMgrOk"
                                )
                            } else if (intent.getStringExtra("fable_test_mem_mode") == "ultra_ce") {
                                suspend fun waitScanDone() {
                                    var wc = 0
                                    while (wc < 45) {
                                        kotlinx.coroutines.delay(120)
                                        wc++
                                        if (!memoryEngineManager.state.value.busy && wc >= 2) break
                                    }
                                }
                                memoryEngineManager.clearWatchList()
                                memoryEngineManager.setScanTimeoutSec(20)
                                memoryEngineManager.setScope(com.fable.browser.engine.MemScope.PAGE_WASM)

                                // 1. Group Search (ordered)
                                memoryEngineManager.setDataType(com.fable.browser.engine.MemDataType.AUTO)
                                memoryEngineManager.setScanType(com.fable.browser.engine.MemScanType.GROUP)
                                memoryEngineManager.firstScan("-500i;33.5f;7777i:32")
                                waitScanDone()
                                val stGrp = memoryEngineManager.state.value
                                val grpHit = stGrp.results.firstOrNull { it.addr.contains("wasm#0+0x208") }
                                val groupOk = grpHit != null && stGrp.results.size >= 3

                                // 2. Pointer Scan on WASM target
                                var ptrOk = false
                                if (grpHit != null) {
                                    memoryEngineManager.pointerScan(grpHit.addr, com.fable.browser.engine.MemScope.PAGE_WASM, 64)
                                    waitScanDone()
                                    ptrOk = memoryEngineManager.state.value.pointerResults.any {
                                        it.ptrAddr.contains("0x400") && it.offset == "+0x8"
                                    }
                                }

                                // 3. Exact 100 scan + Live Hex Write at +0x10 (0x50) + Deterministic Handshake for Bug #1 & Trace
                                var hexWOk = false
                                var nextBugFixOk = false
                                var traceOk = false
                                var tableOk = false
                                memoryEngineManager.setDataType(com.fable.browser.engine.MemDataType.AUTO)
                                memoryEngineManager.setScanType(com.fable.browser.engine.MemScanType.EXACT)
                                memoryEngineManager.firstScan("100")
                                waitScanDone()
                                val w40 = memoryEngineManager.state.value.results.firstOrNull { it.addr.contains("wasm#0+0x40") }
                                if (w40 != null) {
                                    memoryEngineManager.inspectHex(w40.addr, com.fable.browser.engine.MemScope.PAGE_WASM, 0L)
                                    kotlinx.coroutines.delay(350)
                                    memoryEngineManager.writeHexBytes("DE ?? AD BE", 16L)
                                    kotlinx.coroutines.delay(450)
                                    hexWOk = memoryEngineManager.state.value.hex.hexBytes.contains("DE00ADBE", ignoreCase = true)

                                    // Signal page (0x40 = 200) -> page immediately sets ultraHero.hp = 250!
                                    memoryEngineManager.writeScanItem(w40, "200")
                                    kotlinx.coroutines.delay(350)
                                    memoryEngineManager.setScanType(com.fable.browser.engine.MemScanType.INCREASED)
                                    // Pass stale "100" intentionally to prove Bug #1 fix!
                                    memoryEngineManager.nextScan("100")
                                    waitScanDone()
                                    val hpHit = memoryEngineManager.state.value.results.firstOrNull { it.addr.contains("ultraHero.hp") }
                                    nextBugFixOk = hpHit != null

                                    if (hpHit != null) {
                                        memoryEngineManager.addToWatch(hpHit, "Hero HP")
                                        val wHp = memoryEngineManager.state.value.watchList.lastOrNull()
                                        if (wHp != null) {
                                            memoryEngineManager.toggleWatchTrace(wHp.id)
                                            kotlinx.coroutines.delay(250)
                                            // Signal page (0x40 = 300) -> page calls applyHeroDamage()!
                                            memoryEngineManager.writeScanItem(w40, "300")
                                            kotlinx.coroutines.delay(750)
                                            val wAfter = memoryEngineManager.state.value.watchList.firstOrNull { it.id == wHp.id }
                                            traceOk = (wAfter?.traceCount ?: 0) > 0
                                            memoryEngineManager.toggleWatchTrace(wHp.id)
                                            val exported = memoryEngineManager.exportWatchTableJson()
                                            memoryEngineManager.clearWatchList()
                                            kotlinx.coroutines.delay(200)
                                            memoryEngineManager.importCheatTableJson(exported)
                                            kotlinx.coroutines.delay(500)
                                            tableOk = memoryEngineManager.state.value.watchList.any { it.addr == hpHit.addr && it.attached }
                                        }
                                    }
                                }

                                // 4. XOR Auto-Key Scan + Transparent Write (triggers final /ce-ultra-probe on page!)
                                var xorOk = false
                                memoryEngineManager.setDataType(com.fable.browser.engine.MemDataType.INT32)
                                memoryEngineManager.setScanType(com.fable.browser.engine.MemScanType.XOR_ENCRYPTED)
                                memoryEngineManager.firstScan("123456", "")
                                waitScanDone()
                                val xHit = memoryEngineManager.state.value.results.firstOrNull { it.addr.contains("wasm#0+0x304") }
                                if (xHit != null) {
                                    memoryEngineManager.writeScanItem(xHit, "999888")
                                    kotlinx.coroutines.delay(450)
                                    xorOk = true
                                }

                                memoryEngineManager.clearWatchList()
                                memoryEngineManager.resetScan()
                                val engLbl = memoryEngineManager.state.value.activeEngineLabel
                                val ultraOk = groupOk && ptrOk && hexWOk && xorOk && nextBugFixOk && traceOk && tableOk
                                android.util.Log.i(
                                    "FABLE-MEM-CHECK",
                                    "scope=$scopeName engine=$engLbl ultraOk=$ultraOk groupOk=$groupOk ptrOk=$ptrOk hexWOk=$hexWOk xorOk=$xorOk nextBugFixOk=$nextBugFixOk traceOk=$traceOk tableOk=$tableOk"
                                )
                            } else {
                                val queryVal = intent.getStringExtra("fable_test_mem_query")?.takeIf { it.isNotBlank() } ?: "133700"
                                val writeVal = intent.getStringExtra("fable_test_mem_write").orEmpty()
                                val testMode = intent.getStringExtra("fable_test_mem_mode").orEmpty()
                                val timeoutSec = intent.getIntExtra("fable_test_mem_timeout_sec", 20)
                                val doWrite = if (intent.hasExtra("fable_test_mem_write")) (writeVal.isNotBlank() && !writeVal.equals("skip", ignoreCase = true)) else true
                                val targetWrite = if (writeVal.isNotBlank()) writeVal else "777888"
                                memoryEngineManager.setScanTimeoutSec(timeoutSec)
                                memoryEngineManager.setScope(com.fable.browser.engine.MemScope.PAGE_WASM)
                                memoryEngineManager.setDataType(com.fable.browser.engine.MemDataType.AUTO)
                                memoryEngineManager.setScanType(com.fable.browser.engine.MemScanType.EXACT)
                                memoryEngineManager.firstScan(queryVal)
                                var waitCnt = 0
                                while (waitCnt < 45) {
                                    kotlinx.coroutines.delay(150)
                                    waitCnt++
                                    if (!memoryEngineManager.state.value.busy && waitCnt >= 3) break
                                }
                                val st1 = memoryEngineManager.state.value
                                val hit = st1.results.firstOrNull { it.addr.contains("wasm#") } ?: st1.results.firstOrNull { it.addr.contains("gold") } ?: st1.results.firstOrNull()
                                var bulkFreezeOk = false
                                var blockedSum = 0L
                                if (testMode == "multi_freeze" && st1.results.isNotEmpty()) {
                                    memoryEngineManager.clearWatchList()
                                    memoryEngineManager.selectAllScanResults()
                                    memoryEngineManager.addSelectedScanResultsToWatch(freezeImmediately = false)
                                    memoryEngineManager.selectAllWatchEntries()
                                    memoryEngineManager.editSelectedWatchEntries(targetWrite)
                                    kotlinx.coroutines.delay(450)
                                    memoryEngineManager.freezeSelectedWatchEntries(true)
                                    kotlinx.coroutines.delay(2000)
                                    val stF = memoryEngineManager.state.value
                                    blockedSum = stF.watchList.sumOf { it.blockedWrites }
                                    val failedCnt = stF.watchList.count { it.freezeStatus == "failed" || it.freezeStatus == "detached" }
                                    bulkFreezeOk = stF.watchList.size >= 4 && stF.watchList.all { it.frozen } && failedCnt == 0 && blockedSum > 0L
                                    memoryEngineManager.unfreezeAll()
                                    memoryEngineManager.selectAllWatchEntries()
                                    memoryEngineManager.removeSelectedWatchEntries()
                                } else if (hit != null && doWrite) {
                                    memoryEngineManager.addToWatch(hit)
                                    val w = memoryEngineManager.state.value.watchList.lastOrNull()
                                    if (w != null) {
                                        memoryEngineManager.setWatchValue(w.id, targetWrite)
                                        kotlinx.coroutines.delay(700)
                                    }
                                    if (hit.kind == "wasm") {
                                        memoryEngineManager.inspectHex(hit.addr, com.fable.browser.engine.MemScope.PAGE_WASM, 0L)
                                        kotlinx.coroutines.delay(600)
                                    }
                                }
                                val st2 = memoryEngineManager.state.value
                                val hexOk = st2.hex.hexBytes.isNotBlank()
                                val engStr = st1.detectedEngines.joinToString("+").ifBlank { "none" }
                                android.util.Log.i(
                                    "FABLE-MEM-CHECK",
                                    "scope=$scopeName engine=${st2.activeEngineLabel} total=${st1.totalMatches} wasm=${st1.wasmCount} buf=${st1.bufCount} " +
                                        "frames=${st1.frameCount} workers=${st1.workerCount} ms=${st1.lastScanDurationMs} engines=$engStr " +
                                        "hit=${hit?.addr ?: "none"} hexOk=$hexOk bulkFreezeOk=$bulkFreezeOk blocked=$blockedSum status=${st1.statusText}"
                                )
                                memoryEngineManager.resetScan()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openLocalHtml(uri: Uri, type: String?) {
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { copyLocalHtml(uri, type) }
            if (local != null) {
                val root = File(cacheDir, "opened_html").canonicalPath
                val full = local.canonicalPath
                val rel = if (full.startsWith(root)) full.removePrefix(root).trimStart('/') else local.name
                val encodedRel = rel.split('/').joinToString("/") { seg ->
                    android.net.Uri.encode(seg)
                }
                val secureLocalUrl = "https://local.fable.app/opened_html/$encodedRel"
                val tab = tabManager.openTab(incognito = false)
                tabManager.setHybridBlinkMode(tab, true)
                tabManager.load(tab, secureLocalUrl)
            } else {
                activityBridge.toast(getString(R.string.cannot_open_file))
            }
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
        com.fable.browser.engine.FableUnifiedEngine.scheduleCookieFlush(immediate = false)
        tabManager.onAppPaused()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        com.fable.browser.engine.FableUnifiedEngine.scheduleCookieFlush(immediate = true)
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
