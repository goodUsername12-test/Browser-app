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
import kotlinx.coroutines.withContext
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
    private var modelDownloadJob: Job? = null
    private var pendingDecision: CompletableDeferred<ConfirmationDecision>? = null
    private val sessionApprovedZones = HashSet<AgentRiskZone>()
    private var lastSearchHits: List<WebSearchHit> = emptyList()

    private val _state = MutableStateFlow(
        AgentLiveState(
            freeStorageGb = FableVideoSkillLearner.freeStorageGb(context),
            learnedSkills = FableVideoSkillLearner.loadSavedSkills(context),
            installedModels = FableVideoSkillLearner.listInstalledModels(context),
            activeModelBadge = computeCurrentModelBadge(),
            ownerPasswordConfigured = FableOwnerAuthManager.isPasswordConfigured(context),
            ownerResearchUnlocked = FableOwnerAuthManager.isUnlocked(),
            ownerUnlockRemainingSec = FableOwnerAuthManager.currentStatus(context).remainingUnlockSeconds,
            logs = listOf(
                AgentLogEntry(
                    nowTime(),
                    "AI",
                    "Fable AI Agent v45 ready. Dual-Model GGUF Vault (Qwen3.5-0.8B + Qwen3-0.6B), YouTube Strategy Learner, Natural Typing, Camera/Hold Gaming & Owner Research Beta active."
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
        // Keep Owner Research Mode auto-lock countdown & model badge synchronized
        scope.launch {
            while (isActive) {
                delay(4_000L)
                val ownerSt = FableOwnerAuthManager.currentStatus(context)
                _state.update { cur ->
                    if (cur.ownerResearchUnlocked != ownerSt.unlocked ||
                        cur.ownerUnlockRemainingSec != ownerSt.remainingUnlockSeconds ||
                        cur.ownerPasswordConfigured != ownerSt.passwordConfigured
                    ) {
                        cur.copy(
                            ownerPasswordConfigured = ownerSt.passwordConfigured,
                            ownerResearchUnlocked = ownerSt.unlocked,
                            ownerUnlockRemainingSec = ownerSt.remainingUnlockSeconds
                        )
                    } else cur
                }
            }
        }
    }

    private fun computeCurrentModelBadge(): String {
        val loaded = FableLocalLlmRuntime.loadedModelName
        if (loaded.isNotBlank()) return "$loaded (Loaded)"
        val installed = FableLocalLlmRuntime.resolveInstalledGgufFiles(context)
        if (installed.isNotEmpty() && FableLocalLlmRuntime.isNativeRuntimeCompiled) {
            return "${installed.size} GGUF Ready (On-Demand)"
        }
        return "Built-in Fast Brain"
    }

    fun refreshStorageAndSkills() {
        val ownerSt = FableOwnerAuthManager.currentStatus(context)
        _state.update {
            it.copy(
                freeStorageGb = FableVideoSkillLearner.freeStorageGb(context),
                learnedSkills = FableVideoSkillLearner.loadSavedSkills(context),
                installedModels = FableVideoSkillLearner.listInstalledModels(context),
                activeModelBadge = computeCurrentModelBadge(),
                ownerPasswordConfigured = ownerSt.passwordConfigured,
                ownerResearchUnlocked = ownerSt.unlocked,
                ownerUnlockRemainingSec = ownerSt.remainingUnlockSeconds
            )
        }
    }

    fun selectMode(mode: AgentMode) {
        scope.launch { settingsStore.setAgentDefaultMode(mode) }
        if (mode == AgentMode.GAMING_SCREEN_ONLY) {
            FableLocalLlmRuntime.unloadForFastGaming()
        }
        _state.update {
            it.copy(
                mode = mode,
                strictScreenOnlyActive = mode == AgentMode.GAMING_SCREEN_ONLY,
                activeModelBadge = computeCurrentModelBadge(),
                statusLine = "Mode: ${mode.shortLabel}"
            )
        }
    }

    fun stopEverythingImmediately(reason: String = "Emergency Stop pressed") {
        FableLocalLlmRuntime.abortImmediately()
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
        appendLog("System", "$reason. All touch/joystick/key inputs & AI generation stopped immediately.")
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

    // --- Owner-Only Research Mode (Beta) Controls ---

    fun createOwnerPassword(password: String): Result<Unit> {
        return runCatching {
            val autoLock = kotlinx.coroutines.runBlocking { settingsStore.settings.first().ownerAutoLockMinutes }
            FableOwnerAuthManager.createOrReplacePassword(context, password, autoLock).getOrThrow()
            refreshStorageAndSkills()
            appendLog("System", "🔓 Owner Research Mode (Beta) password created & unlocked (${autoLock}m auto-lock). Read-Only Page Guard active.")
        }
    }

    fun unlockOwnerModeWithPassword(password: String): Result<Unit> {
        return runCatching {
            val autoLock = kotlinx.coroutines.runBlocking { settingsStore.settings.first().ownerAutoLockMinutes }
            FableOwnerAuthManager.unlockWithPassword(context, password, autoLock).getOrThrow()
            refreshStorageAndSkills()
            appendLog("System", "🔓 Owner Research Mode (Beta) unlocked (${autoLock}m session). Read-Only No-Injection & Emergency Stop enforced.")
        }
    }

    fun onBiometricOwnerUnlockResult(ok: Boolean, msg: String) {
        refreshStorageAndSkills()
        if (ok) {
            appendLog("System", "🔓 $msg")
        } else if (msg.isNotBlank()) {
            appendLog("System", msg)
        }
    }

    fun lockOwnerModeNow() {
        FableOwnerAuthManager.lockNow()
        refreshStorageAndSkills()
        appendLog("System", "🔒 Owner Research Mode locked. Returned to Standard Profile.")
    }

    fun resetOwnerModeCredentials() {
        FableOwnerAuthManager.resetOwnerSecurityData(context)
        refreshStorageAndSkills()
        appendLog("System", "Owner Research Mode security verifier wiped. You can set a new owner password anytime.")
    }

    // --- Dual-Model GGUF Vault Management (Ai_models GitHub Release) ---

    fun downloadCompactModelPreset(presetId: String) {
        modelDownloadJob?.cancel()
        modelDownloadJob = scope.launch {
            val specs = when (presetId) {
                "qwen35_0_8b_q4" -> listOf(FableLocalLlmRuntime.PRIMARY_QWEN35_0_8B)
                "qwen3_0_6b_q4", "qwen_0_5b_q4" -> listOf(FableLocalLlmRuntime.FAST_QWEN3_0_6B)
                "download_both" -> FableLocalLlmRuntime.OFFICIAL_SPECS
                else -> listOf(FableLocalLlmRuntime.PRIMARY_QWEN35_0_8B)
            }

            for (spec in specs) {
                _state.update { it.copy(modelDownloadStatus = "Starting ${spec.fileName}...") }
                appendLog("AI", "Downloading '${spec.fileName}' (~${spec.approxSizeMb} MB) directly into Fable Model Vault...")
                val res = FableLocalLlmRuntime.downloadSpecDirectlyToVault(context, spec) { prog ->
                    _state.update { it.copy(modelDownloadStatus = prog) }
                }
                res.onSuccess { file ->
                    refreshStorageAndSkills()
                    appendLog("AI", "✅ Verified & installed '${file.name}' into Vault! Ready for local llama.cpp inference.")
                    activityBridge.toast("Installed ${file.name}")
                }.onFailure { err ->
                    // Fallback to system/browser downloader if direct vault stream was interrupted
                    downloader.enqueueUrl(
                        url = spec.githubReleaseUrl,
                        suggestedName = spec.fileName,
                        fromIncognito = false,
                        mimeTypeHint = "application/octet-stream"
                    )
                    appendLog("System", "Vault stream interrupted (${err.message}); queued '${spec.fileName}' in Downloads as backup.")
                }
            }
            _state.update { it.copy(modelDownloadStatus = "") }
            refreshStorageAndSkills()
        }
    }

    fun loadAndWarmupModel(preferFast: Boolean) {
        scope.launch {
            val target = FableLocalLlmRuntime.chooseBestModelFile(context, preferFastRouter = preferFast)
            if (target == null) {
                val msg = "No GGUF model found in Vault yet. Tap 'Download Both Models' or 'Import .gguf Model' first."
                appendLog("System", msg)
                activityBridge.toast(msg)
                return@launch
            }
            _state.update { it.copy(modelDownloadStatus = "Loading ${target.name} into native llama.cpp...") }
            val ok = FableLocalLlmRuntime.ensureModelLoaded(target, nCtx = if (preferFast) 2048 else 3072)
            _state.update { it.copy(modelDownloadStatus = "") }
            refreshStorageAndSkills()
            if (ok) {
                appendLog("AI", "⚡ Loaded '${target.name}' into native llama.cpp! Active badge updated.")
                activityBridge.toast("Loaded ${target.name}")
            } else {
                appendLog("System", "Could not load '${target.name}' (verify valid .gguf file & free RAM).")
            }
        }
    }

    fun unloadModelNow() {
        FableLocalLlmRuntime.unloadForFastGaming()
        refreshStorageAndSkills()
        appendLog("System", "Unloaded GGUF model from RAM for maximum gaming/browser headroom.")
    }

    fun importLocalModelUri(uri: Uri) {
        scope.launch {
            _state.update { it.copy(modelDownloadStatus = "Importing .gguf into Vault...") }
            appendLog("System", "Importing local model file into Fable storage...")
            val imported = FableVideoSkillLearner.importModelFile(context, uri)
            _state.update { it.copy(modelDownloadStatus = "") }
            refreshStorageAndSkills()
            if (imported != null) {
                appendLog("AI", "✅ Imported model into Vault: $imported")
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
                appendLog("AI", "✅ Video understood & saved as skill '${skill.name}':\n${skill.deepSummary}\nTap '${skill.name}' chip anytime to run it!")
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
        if (trimmed.lowercase() in setOf("stop", "cancel", "halt", "abort", "release")) {
            stopEverythingImmediately("Stopped by command")
            return
        }

        FableLocalLlmRuntime.abortImmediately()
        FableScreenInputController.releaseAllImmediately()
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            appendLog("User", trimmed)
            val settings = settingsStore.settings.first()
            val curMode = _state.value.mode
            val activeTab = tabManager.activeTab
            val curUrl = activeTab?.url?.value.orEmpty()
            val curTitle = activeTab?.title?.value.orEmpty()
            val ownerUnlocked = FableOwnerAuthManager.isUnlocked()

            _state.update {
                it.copy(
                    running = true,
                    statusLine = "Understanding screen & command...",
                    currentStep = 0,
                    totalSteps = 0,
                    ownerResearchUnlocked = ownerUnlocked
                )
            }

            val quickObs = if (curMode != AgentMode.GAMING_SCREEN_ONLY) {
                runCatching { FableVisionAndOcr.observeFullScreen() }.getOrNull()
            } else null

            val plan = FableNeuralBrain.planUserInstruction(
                context = context,
                rawInput = trimmed,
                currentMode = curMode,
                currentPageUrl = curUrl,
                currentPageTitle = curTitle,
                lastSearchHits = lastSearchHits,
                learnedSkills = _state.value.learnedSkills,
                screenObs = quickObs,
                recentLogs = _state.value.logs,
                llmEndpointCfg = settings.agentLocalLlmEndpoint,
                ownerResearchUnlocked = ownerUnlocked
            )

            if (plan.suggestedMode != null && plan.suggestedMode != _state.value.mode) {
                _state.update {
                    it.copy(
                        mode = plan.suggestedMode,
                        strictScreenOnlyActive = plan.suggestedMode == AgentMode.GAMING_SCREEN_ONLY,
                        activeModelBadge = plan.modelUsedLabel
                    )
                }
            } else {
                _state.update { it.copy(activeModelBadge = plan.modelUsedLabel) }
            }

            appendLog("AI", plan.initialReply)

            if (plan.steps.isEmpty()) {
                _state.update { it.copy(running = false, statusLine = "Ready", activeModelBadge = computeCurrentModelBadge()) }
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
                        it !is AgentActionStep.ReadAndExplainScreen &&
                        it !is AgentActionStep.OwnerReadOnlyInspectPage
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
                        activeModelBadge = computeCurrentModelBadge(),
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

        // Non-bypassable irreversible action guard (even when Owner Research Mode is unlocked!)
        val forceIrreversibleGate =
            zone == AgentRiskZone.IMPORTANT_SENSITIVE && FableOwnerAuthManager.requiresIrreversibleActionConfirmation()

        val mustAsk = when {
            forceIrreversibleGate -> true
            settings.agentAutonomyPolicy == AgentAutonomyPolicy.ALWAYS_ASK_EVERYTHING -> true
            zonePolicy == RiskZonePolicy.ASK_EVERY_TIME -> true
            zonePolicy == RiskZonePolicy.ASK_ONCE && zone !in sessionApprovedZones -> true
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
                    withContext(Dispatchers.Main) { tabManager.loadInActive(searchPage) }
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
            is AgentActionStep.ResearchBestMethod -> {
                _state.update { it.copy(statusLine = "Scanning YouTube & guides: ${step.topic}") }
                val pkg = FableStrategyResearcher.researchBestMethod(
                    context = context,
                    rawQuery = step.topic,
                    synthesizeExecutableSkill = true
                ) { st ->
                    _state.update { it.copy(statusLine = st) }
                }
                refreshStorageAndSkills()

                val report = buildString {
                    append("🎥 Best Method Playbook for '${pkg.topic}' (${pkg.elapsedMs}ms):\n")
                    pkg.synthesizedSteps.forEachIndexed { idx, line ->
                        append("${idx + 1}. $line\n")
                    }
                    if (pkg.youtubeVideos.isNotEmpty()) {
                        append("\nTop YouTube Sources Analyzed:\n")
                        pkg.youtubeVideos.take(3).forEach { v ->
                            append("• ${v.title} (${v.url})\n")
                        }
                    }
                    if (pkg.compiledSkill != null) {
                        append("\n⚡ Saved executable skill '${pkg.compiledSkill.name}' (${pkg.compiledSkill.steps.size} calibrated movement/camera/button steps).")
                    }
                }.trim()
                appendLog("AI", report)

                if (step.autoExecuteCompiledSkill && pkg.compiledSkill != null) {
                    appendLog("AI", "Executing compiled method '${pkg.compiledSkill.name}' in strict Screen-Only mode...")
                    runLearnedVideoSkill(AgentActionStep.ReplayLearnedSkill(pkg.compiledSkill.id, repeats = 1), settings)
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
            is AgentActionStep.OwnerReadOnlyInspectPage -> {
                if (!FableOwnerAuthManager.isUnlocked()) {
                    appendLog("System", "Owner Research Mode is currently locked. Unlock it in AI Settings first.")
                    return
                }
                check(!FableOwnerAuthManager.canInjectIntoPage()) { "Read-only page guard violated" }
                _state.update { it.copy(statusLine = "Owner Read-Only Deep Inspect...") }
                val curUrl = tabManager.activeTab?.url?.value.orEmpty()
                val obs = FableVisionAndOcr.observeFullScreen()
                val page = if (curUrl.startsWith("http")) {
                    FableSearchAndReader.fetchAndReadPage(
                        url = curUrl,
                        allowCookies = tabManager.activeTab?.isIncognito != true,
                        includeRawSourcePreview = true
                    )
                } else null

                val msg = buildString {
                    append("🔓 Owner Read-Only Inspection (0% page injection):\n")
                    if (obs != null) append("• Screen: ${obs.dominantSummary}\n")
                    if (page != null) {
                        append("• Title: ${page.title}\n")
                        append("• Links discovered: ${page.discoveredLinks.size}\n")
                        if (page.responseHeadersSummary.isNotBlank()) {
                            append("• Response Headers:\n${page.responseHeadersSummary}\n")
                        }
                        if (page.rawHtmlPreview.isNotBlank()) {
                            append("• Raw HTML Head/Body Preview:\n${page.rawHtmlPreview.take(900)}")
                        }
                    } else {
                        append("• Active tab URL ($curUrl) is not an HTTP document.")
                    }
                }
                appendLog("AI", msg.trim())
            }
            is AgentActionStep.TapVisibleText -> {
                _state.update { it.copy(statusLine = "Locating '${step.targetText}' on screen...") }
                val obs = FableVisionAndOcr.observeFullScreen()
                val match = obs?.ocrBlocks?.firstOrNull {
                    it.text.contains(step.targetText, ignoreCase = true)
                }
                if (match == null) {
                    appendLog("AI", "I scanned the screen but '${step.targetText}' is not currently visible. Scroll or open the section first.")
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
                appendLog("AI", "Tapped '${match.text}' on screen. Waiting for visual response...")
                FableVisionAndOcr.waitUntilScreenVisuallyReady(6_000L) { msg ->
                    _state.update { it.copy(statusLine = msg) }
                }
            }
            is AgentActionStep.TapCoordinates -> {
                FableScreenInputController.tapNormalized(step.normX, step.normY, step.holdMs)
                appendLog("AI", "Tapped screen coordinates (${step.normX / 10}%, ${step.normY / 10}%).")
            }
            is AgentActionStep.TypeText -> {
                _state.update { it.copy(statusLine = "Typing '${step.textToType.take(24)}'...") }
                var focusCoords: Pair<Int, Int>? = null
                if (step.fieldHint.isNotBlank()) {
                    val obs = FableVisionAndOcr.observeFullScreen()
                    focusCoords = FableVisionAndOcr.findBestInputBoxCoordinate(obs, step.fieldHint)
                }
                val ok = FableScreenInputController.typeTextOnScreen(
                    textToType = step.textToType,
                    focusNormX = focusCoords?.first,
                    focusNormY = focusCoords?.second,
                    clearExistingFirst = step.clearFirst,
                    pressEnter = step.pressEnter
                )
                if (ok) {
                    appendLog("AI", "⌨️ Typed '${step.textToType}'${if (step.pressEnter) " + pressed Enter" else ""} via OS key events (zero JS injection).")
                    if (step.pressEnter) {
                        FableVisionAndOcr.waitUntilScreenVisuallyReady(5_000L) { msg ->
                            _state.update { it.copy(statusLine = msg) }
                        }
                    }
                } else {
                    appendLog("System", "No active browser/game surface attached to receive typed keys.")
                }
            }
            is AgentActionStep.ScrollScreen -> {
                repeat(step.times.coerceIn(1, 10)) {
                    if (step.down) {
                        FableScreenInputController.swipeNormalized(500, 760, 500, 260, 220L)
                    } else {
                        FableScreenInputController.swipeNormalized(500, 280, 500, 760, 220L)
                    }
                    delay(320L)
                }
                appendLog("AI", "Scrolled ${if (step.down) "down" else "up"} (${step.times}x).")
            }
            is AgentActionStep.GamingMoveCamera -> {
                FableLocalLlmRuntime.unloadForFastGaming()
                _state.update { it.copy(strictScreenOnlyActive = true, statusLine = "Moving camera...") }
                try {
                    val ok = FableScreenInputController.moveCameraSmooth(
                        yawPercent = step.yawPercent,
                        pitchPercent = step.pitchPercent,
                        durationMs = step.durationMs,
                        sensitivityPct = settings.agentCameraSensitivityPct
                    )
                    if (ok) {
                        appendLog("AI", "🎯 Turned camera smoothly (yaw=${step.yawPercent}%, pitch=${step.pitchPercent}%).")
                    }
                } finally {
                    FableScreenInputController.releaseAllImmediately()
                }
            }
            is AgentActionStep.GamingHoldDirection -> {
                FableLocalLlmRuntime.unloadForFastGaming()
                runStrictScreenOnlyHold(step, settings)
            }
            is AgentActionStep.GamingTrackTarget -> {
                FableLocalLlmRuntime.unloadForFastGaming()
                runStrictScreenOnlyGamingLoop(step, settings)
            }
            is AgentActionStep.ReplayLearnedSkill -> {
                FableLocalLlmRuntime.unloadForFastGaming()
                runLearnedVideoSkill(step, settings)
            }
            is AgentActionStep.LongTaskMonitorSections -> {
                runLongTaskMonitoring(step, settings)
            }
        }
    }

    private suspend fun openUrlAndWaitReady(url: String) {
        _state.update { it.copy(statusLine = "Opening $url...") }
        withContext(Dispatchers.Main) {
            tabManager.loadInActive(url)
        }
        val t0 = SystemClock.uptimeMillis()
        delay(420L)
        while (SystemClock.uptimeMillis() - t0 < 12_000L) {
            val loading = tabManager.activeTab?.isLoading?.value == true
            if (!loading) break
            _state.update { it.copy(statusLine = "Waiting for page load...") }
            delay(240L)
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
        _state.update { it.copy(strictScreenOnlyActive = true) }
        val interval = settings.agentReactionIntervalMs.coerceIn(40, 150).toLong()
        val endAt = SystemClock.uptimeMillis() + step.durationMs
        var tick = 0
        try {
            while (SystemClock.uptimeMillis() < endAt) {
                val t0 = SystemClock.uptimeMillis()
                val remSec = ((endAt - t0) / 1000L).toInt().coerceAtLeast(0)
                val doJump = step.jumpTap && (tick % 10 == 0)
                tick++
                FableScreenInputController.stepGamingControl(
                    joyCenterXPercent = settings.agentJoystickCenterX,
                    joyCenterYPercent = settings.agentJoystickCenterY,
                    joyXPercent = step.joyX,
                    joyYPercent = step.joyY,
                    camDxPercent = step.camDx,
                    camDyPercent = step.camDy,
                    tapAttack = false,
                    holdPrimaryButton = step.holdPrimaryButton,
                    tapJumpButton = doJump,
                    attackBtnXPercent = settings.agentAttackBtnX,
                    attackBtnYPercent = settings.agentAttackBtnY,
                    jumpBtnXPercent = settings.agentJumpBtnX,
                    jumpBtnYPercent = settings.agentJumpBtnY,
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
        // STRICT SCREEN-ONLY GUARANTEE: Only reads rendered screen pixels and injects real OS touch events!
        check(!FableOwnerAuthManager.canBypassScreenOnlyGaming()) { "Strict screen-only gaming invariant violated" }
        _state.update { it.copy(strictScreenOnlyActive = true) }
        val intervalMs = settings.agentReactionIntervalMs.coerceIn(40, 150).toLong()
        val totalDurationMs = step.durationSec.coerceIn(2, 600) * 1000L
        val endTime = SystemClock.uptimeMillis() + totalDurationMs

        val isNamedTarget = step.targetLabel.lowercase() !in setOf(
            "target", "cow", "sheep", "pig", "tree", "wood", "diamond", "gold", "iron", "coal", "ore", "mob", "worker", "chest", "bed"
        )
        var ocrHintX: Int? = null
        var ocrHintY: Int? = null
        var prevTargetX = -1
        var prevTargetY = -1
        var lastOcrSweepAt = 0L
        var lostFrames = 0
        var framePhase = 0

        try {
            while (SystemClock.uptimeMillis() < endTime) {
                val loopStart = SystemClock.uptimeMillis()
                val remSec = ((endTime - loopStart + 999L) / 1000L).toInt().coerceAtLeast(0)
                framePhase++

                val frameBmp = FableVisionAndOcr.captureScreenOnlyBitmap(
                    targetMaxWidth = if (isNamedTarget && loopStart - lastOcrSweepAt > 700L) 640 else 240
                )
                if (frameBmp == null) {
                    FableScreenInputController.releaseAllImmediately()
                    delay(110L)
                    continue
                }

                if (isNamedTarget && loopStart - lastOcrSweepAt > 700L) {
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
                    val vec = RustFastCore.computeAgentControlVectorV2(
                        targetXPermille = targetMatch.normX,
                        targetYPermille = targetMatch.normY,
                        prevXPermille = prevTargetX,
                        prevYPermille = prevTargetY,
                        coveragePermille = targetMatch.coveragePermille,
                        confidence = targetMatch.confidence,
                        modeCode = step.trackingStyle.code,
                        strafePhase = framePhase,
                        camSensitivityPct = settings.agentCameraSensitivityPct
                    )
                    prevTargetX = targetMatch.normX
                    prevTargetY = targetMatch.normY

                    FableScreenInputController.stepGamingControl(
                        joyCenterXPercent = settings.agentJoystickCenterX,
                        joyCenterYPercent = settings.agentJoystickCenterY,
                        joyXPercent = vec.joyXPercent,
                        joyYPercent = vec.joyYPercent,
                        camDxPercent = vec.camDxPercent,
                        camDyPercent = vec.camDyPercent,
                        tapAttack = vec.shouldAttack,
                        holdPrimaryButton = vec.shouldHoldPrimary,
                        tapJumpButton = vec.shouldJump,
                        attackBtnXPercent = settings.agentAttackBtnX,
                        attackBtnYPercent = settings.agentAttackBtnY,
                        jumpBtnXPercent = settings.agentJumpBtnX,
                        jumpBtnYPercent = settings.agentJumpBtnY,
                        leaseMs = (intervalMs * 4).coerceAtLeast(240L)
                    )

                    if (step.trackingStyle == GamingTrackingStyle.APPROACH &&
                        targetMatch.coveragePermille >= 125 &&
                        step.targetLabel.lowercase() in setOf("cow", "sheep", "pig", "tree", "worker", "chest", "bed")
                    ) {
                        FableScreenInputController.releaseAllImmediately()
                        appendLog("AI", "✅ Reached '${step.targetLabel}' on screen and released all controls cleanly.")
                        break
                    }
                } else {
                    lostFrames++
                    prevTargetX = -1
                    prevTargetY = -1
                    val scanCamDx = when {
                        lostFrames in 2..14 -> 28
                        lostFrames in 15..26 -> -28
                        else -> 0
                    }
                    FableScreenInputController.stepGamingControl(
                        joyCenterXPercent = settings.agentJoystickCenterX,
                        joyCenterYPercent = settings.agentJoystickCenterY,
                        joyXPercent = 0,
                        joyYPercent = 0,
                        camDxPercent = scanCamDx,
                        camDyPercent = 0,
                        tapAttack = false,
                        holdPrimaryButton = false,
                        tapJumpButton = false,
                        attackBtnXPercent = settings.agentAttackBtnX,
                        attackBtnYPercent = settings.agentAttackBtnY,
                        jumpBtnXPercent = settings.agentJumpBtnX,
                        jumpBtnYPercent = settings.agentJumpBtnY,
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
                        statusLine = "${step.trackingStyle.label} '${step.targetLabel}' · ${reactionMs}ms · ${remSec}s"
                    )
                }

                val sleepMs = (intervalMs - reactionMs).coerceAtLeast(12L)
                delay(sleepMs)
            }
            appendLog("AI", "Finished Gaming Mode task (${step.trackingStyle.label} '${step.targetLabel}') and released all touch controls.")
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
                        tapAttack = s.tapNormX > 0 && !s.holdPrimaryButton,
                        holdPrimaryButton = s.holdPrimaryButton,
                        tapJumpButton = s.tapSecondaryJump,
                        attackBtnXPercent = settings.agentAttackBtnX,
                        attackBtnYPercent = settings.agentAttackBtnY,
                        jumpBtnXPercent = settings.agentJumpBtnX,
                        jumpBtnYPercent = settings.agentJumpBtnY,
                        leaseMs = s.durationMs + 220L
                    )
                    val took = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
                    _state.update {
                        it.copy(
                            lastReactionMs = took,
                            activeInputsLabel = FableScreenInputController.activeSummary,
                            statusLine = "Skill '${skill.name}' ($c/$cycles · step ${idx + 1}/${skill.steps.size})"
                        )
                    }
                    delay((s.durationMs - took).coerceIn(40L, 2_200L))
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
