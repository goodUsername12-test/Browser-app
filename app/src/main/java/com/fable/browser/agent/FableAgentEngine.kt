package com.fable.browser.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.fable.browser.data.prefs.Settings
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.engine.Downloader
import com.fable.browser.engine.RustFastCore
import com.fable.browser.engine.TabManager
import com.fable.browser.util.ActivityBridge
import com.fable.browser.util.UrlUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ConfirmationDecision {
    ALLOW_ONCE,
    ALLOW_SESSION,
    CANCEL
}

@Singleton
class FableAgentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val tabManager: TabManager,
    private val downloader: Downloader,
    private val activityBridge: ActivityBridge
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeTaskJob: Job? = null
    private var pendingDecision: CompletableDeferred<ConfirmationDecision>? = null
    private val sessionApprovedZones = HashSet<AgentRiskZone>()
    private var lastSearchHits: List<WebSearchHit> = emptyList()

    private val _state = MutableStateFlow(
        AgentLiveState(
            freeStorageGb = FableVideoSkillLearner.freeStorageGb(context),
            learnedSkills = FableVideoSkillLearner.loadSavedSkills(context),
            installedModels = FableVideoSkillLearner.listInstalledModels(context),
            logs = listOf(
                AgentLogEntry(
                    nowTime(),
                    "AI",
                    "Fable AI Agent ready. Modes: Basic Browsing, Gaming (Strict Screen-Only 50-100ms), and Long Task."
                )
            )
        )
    )
    val state: StateFlow<AgentLiveState> = _state.asStateFlow()

    init {
        scope.launch {
            val s = settingsStore.settings.first()
            _state.update { it.copy(mode = s.agentDefaultMode) }
        }
    }

    fun refreshStorageAndSkills() {
        _state.update {
            it.copy(
                freeStorageGb = FableVideoSkillLearner.freeStorageGb(context),
                learnedSkills = FableVideoSkillLearner.loadSavedSkills(context),
                installedModels = FableVideoSkillLearner.listInstalledModels(context)
            )
        }
    }

    fun selectMode(mode: AgentMode) {
        scope.launch { settingsStore.setAgentDefaultMode(mode) }
        _state.update {
            it.copy(
                mode = mode,
                strictScreenOnlyActive = mode == AgentMode.GAMING_SCREEN_ONLY,
                statusLine = "Mode: ${mode.shortLabel}"
            )
        }
    }

    fun stopEverythingImmediately(reason: String = "Emergency Stop pressed") {
        FableScreenInputController.releaseAllImmediately()
        pendingDecision?.complete(ConfirmationDecision.CANCEL)
        pendingDecision = null
        activeTaskJob?.cancel()
        activeTaskJob = null
        _state.update {
            it.copy(
                running = false,
                strictScreenOnlyActive = it.mode == AgentMode.GAMING_SCREEN_ONLY,
                statusLine = "Stopped",
                activeInputsLabel = "None",
                remainingSeconds = 0,
                pendingConfirmation = null
            )
        }
        appendLog("System", "$reason. All touch/joystick inputs released.")
    }

    fun resolveConfirmation(decision: ConfirmationDecision) {
        val currentConf = _state.value.pendingConfirmation
        if (decision == ConfirmationDecision.ALLOW_SESSION && currentConf != null) {
            sessionApprovedZones.add(currentConf.zone)
        }
        _state.update { it.copy(pendingConfirmation = null) }
        pendingDecision?.complete(decision)
        pendingDecision = null
    }

    fun inspectCurrentScreenNow() {
        submitInstruction("what is on screen right now?")
    }

    fun downloadCompactModelPreset(presetId: String) {
        val (url, fileName) = when (presetId) {
            "qwen_0_5b_q4" ->
                "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf" to "qwen2.5-0.5b-instruct-q4_k_m.gguf"
            else ->
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf" to "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        }
        downloader.enqueueUrl(
            url = url,
            suggestedName = fileName,
            fromIncognito = false,
            mimeTypeHint = "application/octet-stream"
        )
        appendLog("AI", "Started 6x parallel download of '$fileName' into Downloads. Once downloaded, tap 'Import .gguf Model' in AI Settings to store it in Fable's local model vault (${_state.value.freeStorageGb} GB free).")
    }

    fun importLocalModelUri(uri: Uri) {
        scope.launch {
            appendLog("System", "Importing local model file into Fable storage...")
            val imported = FableVideoSkillLearner.importModelFile(context, uri)
            refreshStorageAndSkills()
            if (imported != null) {
                appendLog("AI", "Imported model: $imported")
                activityBridge.toast("Imported AI model: $imported")
            } else {
                appendLog("System", "Could not read selected model file.")
            }
        }
    }

    fun learnFromUploadedVideo(uri: Uri, customSkillName: String = "") {
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            _state.update { it.copy(running = true, statusLine = "Analyzing uploaded video...") }
            appendLog("User", "Uploaded video demonstration ${if (customSkillName.isNotBlank()) "('$customSkillName')" else ""}")
            val skill = FableVideoSkillLearner.analyzeAndLearnVideo(context, uri, customSkillName) { prog ->
                _state.update { it.copy(statusLine = prog) }
            }
            refreshStorageAndSkills()
            if (skill != null) {
                appendLog("AI", "✅ Video understood & saved as skill '${skill.name}':\n${skill.deepSummary}\nTap the '${skill.name}' chip anytime to run it!")
                _state.update { it.copy(running = false, statusLine = "Learned '${skill.name}'") }
            } else {
                appendLog("AI", "Could not decode frames from that video file. Try a standard .mp4 or .webm clip.")
                _state.update { it.copy(running = false, statusLine = "Video read failed") }
            }
        }
    }

    fun submitInstruction(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) return
        if (trimmed.lowercase() in setOf("stop", "cancel", "halt", "abort")) {
            stopEverythingImmediately("Stopped by command")
            return
        }

        FableScreenInputController.releaseAllImmediately()
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            appendLog("User", trimmed)
            val settings = settingsStore.settings.first()
            val curMode = _state.value.mode
            val curUrl = tabManager.activeTab?.url?.value.orEmpty()

            _state.update {
                it.copy(
                    running = true,
                    statusLine = "Understanding screen & command...",
                    currentStep = 0,
                    totalSteps = 0
                )
            }

            val quickObs = if (curMode != AgentMode.GAMING_SCREEN_ONLY) {
                runCatching { FableVisionAndOcr.observeFullScreen() }.getOrNull()
            } else null

            val plan = FableNeuralBrain.planUserInstruction(
                rawInput = trimmed,
                currentMode = curMode,
                currentPageUrl = curUrl,
                lastSearchHits = lastSearchHits,
                learnedSkills = _state.value.learnedSkills,
                screenObs = quickObs,
                llmEndpointCfg = settings.agentLocalLlmEndpoint
            )

            if (plan.suggestedMode != null && plan.suggestedMode != _state.value.mode) {
                _state.update {
                    it.copy(
                        mode = plan.suggestedMode,
                        strictScreenOnlyActive = plan.suggestedMode == AgentMode.GAMING_SCREEN_ONLY
                    )
                }
            }
            appendLog("AI", plan.initialReply)

            if (plan.steps.isEmpty()) {
                _state.update { it.copy(running = false, statusLine = "Ready") }
                return@launch
            }

            val approved = checkAndAwaitPermission(
                settings = settings,
                zone = plan.riskZone,
                actionTitle = "Run ${plan.suggestedMode?.shortLabel ?: curMode.shortLabel} Task",
                actionDetail = trimmed,
                isExternalOrStateChange = plan.steps.any {
                    it !is AgentActionStep.Reply &&
                        it !is AgentActionStep.AskClarification &&
                        it !is AgentActionStep.ReadAndExplainScreen
                }
            )
            if (!approved) {
                appendLog("System", "Action cancelled by user or Risk Zone policy (${plan.riskZone.title}).")
                _state.update { it.copy(running = false, statusLine = "Cancelled") }
                return@launch
            }

            _state.update { it.copy(totalSteps = plan.steps.size) }
            try {
                plan.steps.forEachIndexed { index, step ->
                    if (!isActive) return@launch
                    _state.update { it.copy(currentStep = index + 1) }
                    executeStep(step, settingsStore.settings.first())
                }
                _state.update {
                    it.copy(
                        running = false,
                        activeInputsLabel = "None",
                        remainingSeconds = 0,
                        statusLine = "Completed"
                    )
                }
            } finally {
                FableScreenInputController.releaseAllImmediately()
            }
        }
    }

    private suspend fun checkAndAwaitPermission(
        settings: Settings,
        zone: AgentRiskZone,
        actionTitle: String,
        actionDetail: String,
        isExternalOrStateChange: Boolean
    ): Boolean {
        val zonePolicy = settings.policyForZone(zone)
        if (zonePolicy == RiskZonePolicy.BLOCKED) {
            appendLog("System", "Blocked by Risk Zone setting: ${zone.title} is set to Blocked.")
            return false
        }

        val mustAsk = when {
            settings.agentAutonomyPolicy == AgentAutonomyPolicy.ALWAYS_ASK_EVERYTHING -> true
            zonePolicy == RiskZonePolicy.ASK_EVERY_TIME -> true
            zonePolicy == RiskZonePolicy.ASK_ONCE && zone !in sessionApprovedZones -> true
            zone == AgentRiskZone.IMPORTANT_SENSITIVE -> true
            settings.agentAutonomyPolicy == AgentAutonomyPolicy.ASK_IMPORTANT && isExternalOrStateChange -> true
            else -> false
        }
        if (!mustAsk) return true

        val deferred = CompletableDeferred<ConfirmationDecision>()
        pendingDecision = deferred
        _state.update {
            it.copy(
                statusLine = "Waiting for your confirmation...",
                pendingConfirmation = AgentPendingConfirmation(
                    id = UUID.randomUUID().toString(),
                    title = actionTitle,
                    detail = "$actionDetail\nZone: ${zone.title}",
                    zone = zone,
                    allowSessionOption = zonePolicy != RiskZonePolicy.ASK_EVERY_TIME && zone != AgentRiskZone.IMPORTANT_SENSITIVE
                )
            )
        }
        val decision = deferred.await()
        return decision == ConfirmationDecision.ALLOW_ONCE || decision == ConfirmationDecision.ALLOW_SESSION
    }

    private suspend fun executeStep(step: AgentActionStep, settings: Settings) {
        when (step) {
            is AgentActionStep.Reply -> appendLog("AI", step.message)
            is AgentActionStep.AskClarification -> {
                _state.update { it.copy(statusLine = "Waiting for clarification") }
            }
            is AgentActionStep.SearchWeb -> {
                _state.update { it.copy(statusLine = "Searching web: ${step.query}") }
                val hits = FableSearchAndReader.searchWeb(step.query, maxResults = 5)
                lastSearchHits = hits
                if (hits.isEmpty()) {
                    val searchPage = UrlUtils.toUrlOrSearch(step.query, settings.searchEngine)
                    tabManager.loadInActive(searchPage)
                    appendLog("AI", "Opened search results directly in browser tab for '${step.query}'.")
                    FableVisionAndOcr.waitUntilScreenVisuallyReady(8_000L) { msg ->
                        _state.update { it.copy(statusLine = msg) }
                    }
                } else {
                    val formatted = hits.mapIndexed { idx, h ->
                        "${idx + 1}. ${h.title}\n   ${h.url}\n   ${h.snippet.take(140)}"
                    }.joinToString("\n\n")
                    appendLog("AI", "Top search results for '${step.query}':\n$formatted")
                    if (step.autoOpenFirst) {
                        val top = hits.first()
                        appendLog("AI", "Opening #1: ${top.url}")
                        openUrlAndWaitReady(top.url)
                    }
                }
            }
            is AgentActionStep.OpenUrl -> {
                openUrlAndWaitReady(step.url)
            }
            is AgentActionStep.CopyPageTextAndZip -> {
                val activeTab = tabManager.activeTab
                val targetUrl = activeTab?.url?.value.orEmpty()
                _state.update { it.copy(statusLine = "Reading & copying page text...") }

                val pageData = FableSearchAndReader.fetchAndReadPage(targetUrl, allowCookies = activeTab?.isIncognito != true)
                val obs = FableVisionAndOcr.observeFullScreen()
                val combinedText = when {
                    pageData != null && pageData.plainText.length > 40 -> pageData.plainText
                    obs != null && obs.fullVisibleText.isNotBlank() -> obs.fullVisibleText
                    else -> ""
                }

                if (combinedText.isNotBlank()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Fable Page Text", combinedText.take(250_000)))

                    val hostSlug = (UrlUtils.host(targetUrl) ?: "page").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val txtFile = File(context.cacheDir, "page_text_${System.currentTimeMillis()}.txt")
                    txtFile.writeText("URL: $targetUrl\nTitle: ${pageData?.title ?: activeTab?.title?.value.orEmpty()}\n\n$combinedText")
                    downloader.saveLocalFile(txtFile, "$hostSlug-text-$stamp.txt", "text/plain", activeTab?.isIncognito == true)
                    appendLog("AI", "✅ Copied ${combinedText.length} characters of page text to Clipboard and saved '$hostSlug-text-$stamp.txt' to Downloads.")
                } else {
                    appendLog("AI", "Page had no extractable plain text yet; proceeding with ZIP export.")
                }

                if (step.includeZip) {
                    _state.update { it.copy(statusLine = "Building full Page Code (.zip)...") }
                    val started = tabManager.savePageCode("all")
                    if (started) {
                        appendLog("AI", "📦 Triggered full 12-stream Page Code (.zip) export (HTML, JS, CSS, assets, IndexedDB, HAR 1.2 & manifest) -> saving to Downloads!")
                    } else {
                        appendLog("System", "Could not start ZIP archiver on this tab.")
                    }
                }
            }
            is AgentActionStep.ReadAndExplainScreen -> {
                _state.update { it.copy(statusLine = "Scanning screen pixels & page...") }
                val obs = FableVisionAndOcr.observeFullScreen()
                val curUrl = tabManager.activeTab?.url?.value.orEmpty()
                val pageInfo = if (_state.value.mode != AgentMode.GAMING_SCREEN_ONLY && curUrl.startsWith("http")) {
                    FableSearchAndReader.fetchAndReadPage(curUrl, allowCookies = tabManager.activeTab?.isIncognito != true)
                } else null

                val report = buildString {
                    if (obs != null) {
                        append("👁 Screen (${obs.captureLatencyMs}ms): ${obs.dominantSummary}\n")
                        if (obs.ocrBlocks.isNotEmpty()) {
                            append("Visible UI / Text:\n• ")
                            append(obs.ocrBlocks.take(12).joinToString("\n• ") { it.text })
                            append("\n")
                        }
                    }
                    if (pageInfo != null && pageInfo.summaryBullets.isNotEmpty()) {
                        append("\n📄 Page Summary (${pageInfo.title}):\n• ")
                        append(pageInfo.summaryBullets.joinToString("\n• "))
                    }
                    if (isEmpty()) append("No active web view is attached yet. Open a page or game first.")
                }
                appendLog("AI", report.trim())
            }
            is AgentActionStep.TapVisibleText -> {
                _state.update { it.copy(statusLine = "Locating '${step.targetText}' on screen...") }
                val obs = FableVisionAndOcr.observeFullScreen()
                val match = obs?.ocrBlocks?.firstOrNull {
                    it.text.contains(step.targetText, ignoreCase = true)
                }
                if (match == null) {
                    appendLog("AI", "I scanned the screen but '${step.targetText}' is not currently visible. Waiting or scroll to reveal it first.")
                    return
                }
                val sensitiveZone = FableNeuralBrain.detectSensitiveZone(match.text.lowercase(), AgentRiskZone.ACCESSIBILITY)
                if (sensitiveZone == AgentRiskZone.IMPORTANT_SENSITIVE) {
                    val ok = checkAndAwaitPermission(
                        settings = settings,
                        zone = AgentRiskZone.IMPORTANT_SENSITIVE,
                        actionTitle = "Confirm Important Screen Tap",
                        actionDetail = "Tap visible button '${match.text}' at (${match.normCenterX / 10}%, ${match.normCenterY / 10}%)",
                        isExternalOrStateChange = true
                    )
                    if (!ok) {
                        appendLog("System", "Skipped sensitive button '${match.text}'.")
                        return
                    }
                }
                FableScreenInputController.tapNormalized(match.normCenterX, match.normCenterY)
                appendLog("AI", "Tapped '${match.text}' on screen. Waiting for screen to respond...")
                FableVisionAndOcr.waitUntilScreenVisuallyReady(6_000L) { msg ->
                    _state.update { it.copy(statusLine = msg) }
                }
            }
            is AgentActionStep.ScrollScreen -> {
                repeat(step.times.coerceIn(1, 10)) {
                    if (step.down) {
                        FableScreenInputController.swipeNormalized(500, 760, 500, 260, 220L)
                    } else {
                        FableScreenInputController.swipeNormalized(500, 280, 500, 760, 220L)
                    }
                    delay(350L)
                }
                appendLog("AI", "Scrolled ${if (step.down) "down" else "up"} (${step.times}x).")
            }
            is AgentActionStep.GamingHoldDirection -> {
                runStrictScreenOnlyHold(step, settings)
            }
            is AgentActionStep.GamingTrackTarget -> {
                runStrictScreenOnlyGamingLoop(step, settings)
            }
            is AgentActionStep.ReplayLearnedSkill -> {
                runLearnedVideoSkill(step, settings)
            }
            is AgentActionStep.LongTaskMonitorSections -> {
                runLongTaskMonitoring(step, settings)
            }
        }
    }

    private suspend fun openUrlAndWaitReady(url: String) {
        _state.update { it.copy(statusLine = "Opening $url...") }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            tabManager.loadInActive(url)
        }
        // Wait for tab loading flag + visual frame stability!
        val t0 = SystemClock.uptimeMillis()
        delay(450L)
        while (SystemClock.uptimeMillis() - t0 < 12_000L) {
            val loading = tabManager.activeTab?.isLoading?.value == true
            if (!loading) break
            _state.update { it.copy(statusLine = "Waiting for page load...") }
            delay(250L)
        }
        FableVisionAndOcr.waitUntilScreenVisuallyReady(6_000L) { msg ->
            _state.update { it.copy(statusLine = msg) }
        }
        appendLog("AI", "Loaded & visually verified: $url")
    }

    private suspend fun runStrictScreenOnlyHold(
        step: AgentActionStep.GamingHoldDirection,
        settings: Settings
    ) {
        // STRICT SCREEN-ONLY: zero DOM/JS/console interaction!
        _state.update { it.copy(strictScreenOnlyActive = true) }
        val interval = settings.agentReactionIntervalMs.coerceIn(40, 150).toLong()
        val endAt = SystemClock.uptimeMillis() + step.durationMs
        var jumped = false
        try {
            while (SystemClock.uptimeMillis() < endAt) {
                val t0 = SystemClock.uptimeMillis()
                val remSec = ((endAt - t0) / 1000L).toInt().coerceAtLeast(0)
                val doJump = step.jumpTap && !jumped
                if (doJump) jumped = true
                FableScreenInputController.stepGamingControl(
                    joyCenterXPercent = settings.agentJoystickCenterX,
                    joyCenterYPercent = settings.agentJoystickCenterY,
                    joyXPercent = step.joyX,
                    joyYPercent = step.joyY,
                    camDxPercent = step.camDx,
                    camDyPercent = 0,
                    tapAttack = doJump,
                    leaseMs = interval * 4
                )
                val took = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
                _state.update {
                    it.copy(
                        lastReactionMs = took,
                        activeInputsLabel = FableScreenInputController.activeSummary,
                        remainingSeconds = remSec,
                        statusLine = "Gaming Hold (${remSec}s left)"
                    )
                }
                delay((interval - took).coerceAtLeast(16L))
            }
        } finally {
            FableScreenInputController.releaseAllImmediately()
        }
    }

    private suspend fun runStrictScreenOnlyGamingLoop(
        step: AgentActionStep.GamingTrackTarget,
        settings: Settings
    ) {
        // STRICT SCREEN-ONLY GUARANTEE: Only reads rendered screen pixels and injects real touch events!
        _state.update { it.copy(strictScreenOnlyActive = true) }
        val intervalMs = settings.agentReactionIntervalMs.coerceIn(40, 150).toLong()
        val totalDurationMs = step.durationSec.coerceIn(2, 600) * 1000L
        val endTime = SystemClock.uptimeMillis() + totalDurationMs

        val isNamedTarget = step.targetLabel.lowercase() !in setOf("target", "cow", "sheep", "pig", "tree", "diamond", "gold", "mob", "worker")
        var ocrHintX: Int? = null
        var ocrHintY: Int? = null
        var lastOcrSweepAt = 0L
        var lostFrames = 0

        try {
            while (SystemClock.uptimeMillis() < endTime) {
                val loopStart = SystemClock.uptimeMillis()
                val remSec = ((endTime - loopStart + 999L) / 1000L).toInt().coerceAtLeast(0)

                val frameBmp = FableVisionAndOcr.captureScreenOnlyBitmap(
                    targetMaxWidth = if (isNamedTarget && loopStart - lastOcrSweepAt > 750L) 640 else 240
                )
                if (frameBmp == null) {
                    FableScreenInputController.releaseAllImmediately()
                    delay(120L)
                    continue
                }

                // Refresh nametag coordinates periodically when tracking a named player ("Player X")
                if (isNamedTarget && loopStart - lastOcrSweepAt > 750L) {
                    lastOcrSweepAt = loopStart
                    val blocks = FableVisionAndOcr.recognizeVisibleText(frameBmp)
                    val tagMatch = blocks.firstOrNull { it.text.contains(step.targetLabel, ignoreCase = true) }
                    if (tagMatch != null) {
                        ocrHintX = tagMatch.normCenterX
                        ocrHintY = tagMatch.normCenterY
                    } else if (lostFrames > 8) {
                        ocrHintX = null
                        ocrHintY = null
                    }
                }

                val targetMatch = FableVisionAndOcr.analyzeFastTarget(
                    bmp = frameBmp,
                    targetQuery = step.targetLabel,
                    hintNormX = ocrHintX,
                    hintNormY = ocrHintY
                )
                frameBmp.recycle()

                if (targetMatch.found) {
                    lostFrames = 0
                    val vec = RustFastCore.computeAgentControlVector(
                        targetXPermille = targetMatch.normX,
                        targetYPermille = targetMatch.normY,
                        coveragePermille = targetMatch.coveragePermille,
                        confidence = targetMatch.confidence,
                        combatMode = step.combatMode
                    )
                    FableScreenInputController.stepGamingControl(
                        joyCenterXPercent = settings.agentJoystickCenterX,
                        joyCenterYPercent = settings.agentJoystickCenterY,
                        joyXPercent = vec.joyXPercent,
                        joyYPercent = vec.joyYPercent,
                        camDxPercent = vec.camDxPercent,
                        camDyPercent = vec.camDyPercent,
                        tapAttack = vec.shouldAttack,
                        leaseMs = (intervalMs * 4).coerceAtLeast(240L)
                    )
                    if (!step.combatMode && targetMatch.coveragePermille >= 125 && step.targetLabel.lowercase() in setOf("cow", "sheep", "pig", "tree", "worker")) {
                        FableScreenInputController.releaseAllImmediately()
                        appendLog("AI", "✅ Reached '${step.targetLabel}' on screen and released joystick cleanly.")
                        break
                    }
                } else {
                    lostFrames++
                    // Gentle visual scan pan to find the target on screen; stop holding forward if lost
                    val scanCamDx = if (lostFrames in 2..18) 26 else 0
                    FableScreenInputController.stepGamingControl(
                        joyCenterXPercent = settings.agentJoystickCenterX,
                        joyCenterYPercent = settings.agentJoystickCenterY,
                        joyXPercent = 0,
                        joyYPercent = 0,
                        camDxPercent = scanCamDx,
                        camDyPercent = 0,
                        tapAttack = false,
                        leaseMs = 240L
                    )
                }

                val reactionMs = (SystemClock.uptimeMillis() - loopStart).coerceAtLeast(1L)
                _state.update {
                    it.copy(
                        lastReactionMs = reactionMs,
                        confidencePercent = if (targetMatch.found) targetMatch.confidence else 25,
                        activeInputsLabel = FableScreenInputController.activeSummary,
                        remainingSeconds = remSec,
                        statusLine = "${if (step.combatMode) "Combat" else "Tracking"} '${step.targetLabel}' · ${reactionMs}ms · ${remSec}s"
                    )
                }

                val sleepMs = (intervalMs - reactionMs).coerceAtLeast(12L)
                delay(sleepMs)
            }
            appendLog("AI", "Finished Gaming Mode task ('${step.targetLabel}') and released all touch controls.")
        } finally {
            FableScreenInputController.releaseAllImmediately()
        }
    }

    private suspend fun runLearnedVideoSkill(
        step: AgentActionStep.ReplayLearnedSkill,
        settings: Settings
    ) {
        val skill = _state.value.learnedSkills.firstOrNull {
            it.id.equals(step.skillNameOrId, true) || it.name.contains(step.skillNameOrId, true)
        } ?: _state.value.learnedSkills.firstOrNull()
        if (skill == null) {
            appendLog("AI", "Learned skill '${step.skillNameOrId}' not found.")
            return
        }

        _state.update { it.copy(strictScreenOnlyActive = true) }
        try {
            val cycles = step.repeats.coerceIn(1, 10)
            for (c in 1..cycles) {
                appendLog("AI", "Replaying '${skill.name}' (cycle $c/$cycles)...")
                for ((idx, s) in skill.steps.withIndex()) {
                    val t0 = SystemClock.uptimeMillis()
                    FableScreenInputController.stepGamingControl(
                        joyCenterXPercent = settings.agentJoystickCenterX,
                        joyCenterYPercent = settings.agentJoystickCenterY,
                        joyXPercent = s.joyXPercent,
                        joyYPercent = s.joyYPercent,
                        camDxPercent = s.camDxPercent,
                        camDyPercent = s.camDyPercent,
                        tapAttack = s.tapNormX > 0,
                        leaseMs = s.durationMs + 200L
                    )
                    val took = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
                    _state.update {
                        it.copy(
                            lastReactionMs = took,
                            activeInputsLabel = FableScreenInputController.activeSummary,
                            statusLine = "Skill '${skill.name}' ($c/$cycles · step ${idx + 1}/${skill.steps.size})"
                        )
                    }
                    delay((s.durationMs - took).coerceIn(40L, 2_000L))
                }
                FableScreenInputController.releaseAllImmediately()
                FableVisionAndOcr.waitUntilScreenVisuallyReady(3_000L)
            }
            appendLog("AI", "✅ Completed learned skill '${skill.name}' ($cycles cycle(s)).")
        } finally {
            FableScreenInputController.releaseAllImmediately()
        }
    }

    private suspend fun runLongTaskMonitoring(
        step: AgentActionStep.LongTaskMonitorSections,
        settings: Settings
    ) {
        if (step.startUrl != null) {
            openUrlAndWaitReady(step.startUrl)
        }

        val endAt = SystemClock.uptimeMillis() + step.durationSec.coerceIn(15, 7200) * 1000L
        val baselines = HashMap<String, String>()
        var cycle = 0

        while (SystemClock.uptimeMillis() < endAt) {
            cycle++
            var changesThisCycle = 0
            val sections = if (step.sectionLabels.isEmpty()) listOf("__current__") else step.sectionLabels

            for (sec in sections) {
                val remSec = ((endAt - SystemClock.uptimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                if (remSec <= 0) break

                if (sec != "__current__") {
                    _state.update { it.copy(remainingSeconds = remSec, statusLine = "Long Task: opening '$sec' (${remSec}s left)") }
                    val preObs = FableVisionAndOcr.observeFullScreen()
                    val btn = preObs?.ocrBlocks?.firstOrNull { it.text.contains(sec, ignoreCase = true) }
                    if (btn != null) {
                        if (sec.equals("Upgrade", ignoreCase = true)) {
                            // Navigating to view an Upgrade tab is allowed if confirmed, never auto-clicking a purchase confirm!
                            val ok = checkAndAwaitPermission(
                                settings = settings,
                                zone = AgentRiskZone.IMPORTANT_SENSITIVE,
                                actionTitle = "Open '$sec' Section (Read-Only Check)",
                                actionDetail = "Tap '$sec' tab to inspect changes without purchasing anything.",
                                isExternalOrStateChange = true
                            )
                            if (!ok) continue
                        }
                        FableScreenInputController.tapNormalized(btn.normCenterX, btn.normCenterY)
                        FableVisionAndOcr.waitUntilScreenVisuallyReady(5_000L)
                    }
                }

                val obs = FableVisionAndOcr.observeFullScreen()
                val signature = obs?.fullVisibleText?.trim().orEmpty()
                val prevSig = baselines[sec]
                if (prevSig == null) {
                    baselines[sec] = signature
                } else if (prevSig != signature) {
                    baselines[sec] = signature
                    changesThisCycle++
                    appendLog("AI", "🔔 Change detected in '${if (sec == "__current__") "page" else sec}' during check #$cycle!")
                }
            }

            if (cycle >= 2 && changesThisCycle == 0 && step.stopIfNoChange) {
                appendLog("AI", "No visible changes detected across monitored sections; stopping Long Task cleanly as requested.")
                return
            }

            val waitChunk = minOf(15_000L, (endAt - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            if (waitChunk <= 0L) break
            val remSec = ((endAt - SystemClock.uptimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            _state.update { it.copy(remainingSeconds = remSec, statusLine = "Monitoring (cycle #$cycle · ${remSec}s left)") }
            delay(waitChunk)
        }
        appendLog("AI", "Long Task monitoring window completed.")
    }

    private fun appendLog(role: String, message: String) {
        val entry = AgentLogEntry(nowTime(), role, message)
        _state.update { cur ->
            cur.copy(logs = (cur.logs + entry).takeLast(60))
        }
    }

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
