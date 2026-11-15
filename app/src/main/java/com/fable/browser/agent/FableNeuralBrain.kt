package com.fable.browser.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface AgentActionStep {
    data class Reply(val message: String) : AgentActionStep
    data class AskClarification(val question: String) : AgentActionStep
    data class SearchWeb(val query: String, val autoOpenFirst: Boolean) : AgentActionStep
    data class ResearchBestMethod(val topic: String, val autoExecuteCompiledSkill: Boolean) : AgentActionStep
    data class OpenUrl(val url: String) : AgentActionStep
    data class CopyPageTextAndZip(val includeZip: Boolean) : AgentActionStep
    data class ReadAndExplainScreen(val focusQuery: String = "") : AgentActionStep
    data class OwnerReadOnlyInspectPage(val focusQuery: String = "") : AgentActionStep
    data class TapVisibleText(val targetText: String) : AgentActionStep
    data class TapCoordinates(val normX: Int, val normY: Int, val holdMs: Long = 60L) : AgentActionStep
    data class TypeText(
        val textToType: String,
        val fieldHint: String = "",
        val clearFirst: Boolean = false,
        val pressEnter: Boolean = false
    ) : AgentActionStep
    data class ScrollScreen(val down: Boolean, val times: Int = 1) : AgentActionStep
    data class GamingMoveCamera(
        val yawPercent: Int,
        val pitchPercent: Int,
        val durationMs: Long = 380L
    ) : AgentActionStep
    data class GamingTrackTarget(
        val targetLabel: String,
        val combatMode: Boolean,
        val durationSec: Int,
        val trackingStyle: GamingTrackingStyle = if (combatMode) GamingTrackingStyle.COMBAT else GamingTrackingStyle.APPROACH
    ) : AgentActionStep
    data class GamingHoldDirection(
        val joyX: Int,
        val joyY: Int,
        val camDx: Int,
        val durationMs: Long,
        val jumpTap: Boolean,
        val camDy: Int = 0,
        val holdPrimaryButton: Boolean = false
    ) : AgentActionStep
    data class ReplayLearnedSkill(val skillNameOrId: String, val repeats: Int = 1) : AgentActionStep
    data class LongTaskMonitorSections(
        val startUrl: String?,
        val sectionLabels: List<String>,
        val durationSec: Int,
        val stopIfNoChange: Boolean
    ) : AgentActionStep
}

data class PlannedAgentProgram(
    val suggestedMode: AgentMode?,
    val initialReply: String,
    val steps: List<AgentActionStep>,
    val riskZone: AgentRiskZone,
    val modelUsedLabel: String = "Built-in Fast Brain"
)

object FableNeuralBrain {

    private val urlRegex = Regex("""https?://[^\s"'<>]+|(?:[a-zA-Z0-9-]+\.)+(?:com|org|net|io|gg|app|dev|ai|edu|gov)(?:/[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)

    fun normalizeInformalTypos(input: String): String {
        var s = input
        val replacements = listOf(
            Regex("(?i)\\banalize\\b") to "analyze",
            Regex("(?i)\\bfolow\\b") to "follow",
            Regex("(?i)\\bnerest\\b|\\bneares\\b|\\bnearst\\b") to "nearest",
            Regex("(?i)\\bcmera\\b|\\bcamra\\b") to "camera",
            Regex("(?i)\\batack\\b|\\battak\\b") to "attack",
            Regex("(?i)\\bserch\\b") to "search",
            Regex("(?i)\\bdirrect\\b") to "direct",
            Regex("(?i)\\besspesially\\b") to "especially",
            Regex("(?i)\\byt\\b") to "youtube",
            Regex("(?i)\\bclic\\b") to "click"
        )
        for ((re, rep) in replacements) {
            s = s.replace(re, rep)
        }
        return s
    }

    fun buildSpecializedSystemPrompt(
        mode: AgentMode,
        ownerResearchUnlocked: Boolean
    ): String {
        val ownerClause = if (ownerResearchUnlocked) {
            """
            OWNER RESEARCH MODE (BETA · UNLOCKED):
            - You are speaking directly with the verified device owner in private local research mode.
            - Answer technical, creative, mature, controversial, security-research, and experimental questions directly and thoroughly without unnecessary generic refusals.
            - HARD NON-BYPASSABLE GUARD: All web interaction remains strictly READ-ONLY (zero JS/DOM/CSS page injection). Gaming Mode remains 100% screen-only. Emergency stop and irreversible-action confirmations remain enforced.
            """.trimIndent()
        } else {
            "Standard Safety Profile active: be helpful, fast, accurate, and ask before important external actions."
        }

        val allowedActions = when (mode) {
            AgentMode.GAMING_SCREEN_ONLY ->
                "reply | ask | gaming_track | gaming_camera | gaming_hold | tap_text | type_text | replay_skill | youtube_method"
            AgentMode.LONG_TASK ->
                "reply | ask | long_monitor | open_url | search | youtube_method | tap_text | type_text | scroll | copy_and_zip | inspect_readonly"
            AgentMode.BASIC_BROWSING ->
                "reply | ask | search | youtube_method | open_url | copy_and_zip | read_screen | inspect_readonly | tap_text | type_text | scroll | gaming_track | gaming_camera | gaming_hold"
        }

        return """
            You are Fable AI Agent v45 built into Fable Browser on Android.
            You ALWAYS know where you are from the Live Context JSON below.
            Current Mode: ${mode.name} (${mode.label}).
            $ownerClause
            Respond ONLY with ONE valid JSON object matching this exact schema:
            {"reply":"concise natural message","action":"$allowedActions","target":"url, query, entity, button, or text","textToType":"optional text to type","pressEnter":false,"durationSec":10,"yawPct":0,"pitchPct":0,"holdPrimary":false,"jump":false,"combat":false,"autoExecuteSkill":false}
        """.trimIndent()
    }

    fun buildLiveContextPacket(
        mode: AgentMode,
        currentPageUrl: String,
        currentPageTitle: String,
        screenObs: ScreenObservation?,
        learnedSkills: List<LearnedVideoSkill>,
        recentHistory: List<AgentLogEntry>,
        ownerResearchUnlocked: Boolean
    ): String {
        val ctx = JSONObject()
            .put("app", "Fable Browser v45")
            .put("mode", mode.name)
            .put("ownerResearchBeta", ownerResearchUnlocked)
            .put("readOnlyNoPageInjection", true)

        if (mode == AgentMode.GAMING_SCREEN_ONLY) {
            ctx.put("strictScreenOnly", true)
            ctx.put("forbidden", JSONArray(listOf("dom", "javascript", "console", "network_state", "game_memory")))
        } else {
            ctx.put("currentUrl", currentPageUrl.ifBlank { "about:blank" })
            ctx.put("currentTitle", currentPageTitle.take(80))
        }

        if (screenObs != null) {
            ctx.put("screenSize", "${screenObs.width}x${screenObs.height}")
            ctx.put("visuallyStable", screenObs.isVisuallyStable)
            val visLabels = JSONArray()
            screenObs.ocrBlocks.take(12).forEach { visLabels.put(it.text.take(40)) }
            ctx.put("visibleScreenLabels", visLabels)
        }

        if (learnedSkills.isNotEmpty()) {
            val skArr = JSONArray()
            learnedSkills.take(6).forEach { sk -> skArr.put(sk.name) }
            ctx.put("savedSkills", skArr)
        }

        if (recentHistory.isNotEmpty()) {
            val histArr = JSONArray()
            recentHistory.takeLast(4).forEach { h ->
                histArr.put("${h.role}: ${h.message.take(110)}")
            }
            ctx.put("recentConversation", histArr)
        }

        return ctx.toString()
    }

    suspend fun planUserInstruction(
        context: Context? = null,
        rawInput: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        currentPageTitle: String = "",
        lastSearchHits: List<WebSearchHit>,
        learnedSkills: List<LearnedVideoSkill>,
        screenObs: ScreenObservation?,
        recentLogs: List<AgentLogEntry> = emptyList(),
        llmEndpointCfg: String,
        ownerResearchUnlocked: Boolean = false
    ): PlannedAgentProgram {
        val text = rawInput.trim()
        if (text.isBlank()) {
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "Tell me what you want me to do, type, research on YouTube, or control on screen.",
                steps = emptyList(),
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        val normalized = normalizeInformalTypos(text)
        val lower = normalized.lowercase().trim()

        // 0. Instant < 1ms Emergency Stop reflex
        if (lower in setOf("stop", "cancel", "halt", "abort", "release", "pause ai", "stop ai")) {
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "Stopped all actions and released all controls.",
                steps = emptyList(),
                riskZone = AgentRiskZone.ACCESSIBILITY,
                modelUsedLabel = "Instant Reflex (<1ms)"
            )
        }

        // 1. Deterministic high-precision match for unambiguous structured workflows (fastest zero-latency path)
        val deterministicPlan = buildDeterministicSmartPlan(
            text = text,
            currentMode = currentMode,
            currentPageUrl = currentPageUrl,
            lastSearchHits = lastSearchHits,
            learnedSkills = learnedSkills,
            screenObs = screenObs,
            ownerResearchUnlocked = ownerResearchUnlocked
        )

        val isOpenEndedOrConversational =
            deterministicPlan.steps.singleOrNull() is AgentActionStep.AskClarification ||
            (ownerResearchUnlocked && !lower.startsWith("go to ") && !lower.startsWith("type ") && !lower.contains("zip")) ||
            (lower.endsWith("?") && !lower.contains("youtube") && !lower.contains("best method"))

        // 2. Try Embedded On-Device GGUF Runtime (Qwen3-0.6B Fast Router or Qwen3.5-0.8B Primary Brain)
        if (context != null && FableLocalLlmRuntime.isNativeRuntimeCompiled &&
            FableLocalLlmRuntime.chooseBestModelFile(context, preferFastRouter = true) != null
        ) {
            val wordCount = text.split(Regex("\\s+")).size
            val useFastModel = wordCount <= 14 && !ownerResearchUnlocked && !isOpenEndedOrConversational
            if (isOpenEndedOrConversational || currentMode != AgentMode.GAMING_SCREEN_ONLY || useFastModel) {
                val sysPrompt = buildSpecializedSystemPrompt(currentMode, ownerResearchUnlocked)
                val liveCtx = buildLiveContextPacket(
                    mode = currentMode,
                    currentPageUrl = currentPageUrl,
                    currentPageTitle = currentPageTitle,
                    screenObs = screenObs,
                    learnedSkills = learnedSkills,
                    recentHistory = recentLogs,
                    ownerResearchUnlocked = ownerResearchUnlocked
                )
                val usrPayload = "LIVE_CONTEXT=$liveCtx\nUSER_COMMAND=$text"
                val gen = FableLocalLlmRuntime.generateOnDevice(
                    context = context,
                    preferFastRouter = useFastModel,
                    systemPrompt = sysPrompt,
                    userPrompt = usrPayload,
                    maxTokens = if (useFastModel) 150 else 280,
                    temperature = if (ownerResearchUnlocked && isOpenEndedOrConversational) 0.55f else 0.30f,
                    enableThinking = false
                )
                if (gen != null) {
                    val parsed = parseModelJsonIntoPlan(
                        rawModelText = gen.cleanOutput,
                        userText = text,
                        currentMode = currentMode,
                        currentPageUrl = currentPageUrl,
                        modelBadge = "${gen.modelLabel} (${gen.latencyMs}ms)"
                    )
                    if (parsed != null) return parsed
                }
            }
        }

        // 3. Try optional Local/Remote OpenAI-compatible HTTP endpoint if configured
        if (llmEndpointCfg.isNotBlank() && currentMode != AgentMode.GAMING_SCREEN_ONLY) {
            val remotePlan = tryRemoteOrLocalLlmPlan(
                userText = text,
                currentMode = currentMode,
                currentPageUrl = currentPageUrl,
                screenObs = screenObs,
                endpointRaw = llmEndpointCfg,
                ownerResearchUnlocked = ownerResearchUnlocked
            )
            if (remotePlan != null) return remotePlan
        }

        return deterministicPlan
    }

    fun buildDeterministicSmartPlan(
        text: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        lastSearchHits: List<WebSearchHit>,
        learnedSkills: List<LearnedVideoSkill>,
        screenObs: ScreenObservation?,
        ownerResearchUnlocked: Boolean = false
    ): PlannedAgentProgram {
        val normalized = normalizeInformalTypos(text.trim())
        val lower = normalized.lowercase()

        if (lower in setOf("stop", "cancel", "halt", "abort", "release", "pause ai", "stop ai")) {
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "Stopped all actions and released all controls.",
                steps = emptyList(),
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        // 1. Typing commands: e.g. `type "hello world" and press enter`, `in search type bloxd wiki`, `clear and type OraOraRush`
        val explicitTypePlan = parseTypingCommand(text, lower)
        if (explicitTypePlan != null) {
            return explicitTypePlan
        }

        // 2. YouTube / Best-Method / How-to Strategy Research (+ optional "and do the same / try it")
        val wantsBestMethodOrYt =
            lower.contains("youtube") ||
            lower.contains("best method") ||
            lower.contains("best way to") ||
            lower.contains("best strategy") ||
            lower.contains("fastest way to") ||
            (lower.contains("how to ") && (lower.contains("in game") || lower.contains("bloxd") || lower.contains("farm") || lower.contains("bridge") || lower.contains("pvp") || lower.contains("method") || lower.contains("video")))
        if (wantsBestMethodOrYt) {
            val autoExec = lower.contains("do the same") || lower.contains("do same") ||
                lower.contains("and do it") || lower.contains("then do it") || lower.contains("perform it") || lower.contains("try it")
            val topicClean = normalized
                .replace(Regex("(?i)\\s+(?:and\\s+)?(?:do\\s+the\\s+same|do\\s+same|then\\s+do\\s+it|and\\s+do\\s+it|perform\\s+it|try\\s+it).*$"), "")
                .replace(Regex("(?i)\\b(?:on\\s+youtube|from\\s+youtube|youtube\\s+videos?|look\\s+at\\s+youtube)\\b"), "")
                .trim()
                .ifBlank { normalized }
            return PlannedAgentProgram(
                suggestedMode = if (autoExec) AgentMode.GAMING_SCREEN_ONLY else currentMode,
                initialReply = if (autoExec) {
                    "Fast-researching YouTube tutorials & top guides for '$topicClean', compiling the skill, and preparing screen-only execution..."
                } else {
                    "Fast-scanning YouTube videos, transcripts & top guides for '$topicClean'..."
                },
                steps = listOf(AgentActionStep.ResearchBestMethod(topicClean, autoExecuteCompiledSkill = autoExec)),
                riskZone = if (autoExec) AgentRiskZone.USER_APPROVED_MACROS else AgentRiskZone.ACCESSIBILITY
            )
        }

        // 3. Owner Research Mode Read-Only Deep Page Inspection
        if (ownerResearchUnlocked && (lower.contains("inspect source") || lower.contains("read raw") || lower.contains("inspect headers") || lower.contains("deep inspect"))) {
            return PlannedAgentProgram(
                suggestedMode = AgentMode.BASIC_BROWSING,
                initialReply = "Owner Research Mode (Read-Only): inspecting page headers, clean text, links, and raw HTML source without any page injection...",
                steps = listOf(AgentActionStep.OwnerReadOnlyInspectPage(text)),
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        // 4. Conversational / Question about current screen or capability
        if (lower.startsWith("what is on") || lower.startsWith("what do you see") ||
            lower.startsWith("read screen") || lower.startsWith("analyze screen") ||
            lower.startsWith("explain") || lower.startsWith("summarize") ||
            (lower.endsWith("?") && !lower.contains("go to") && !lower.contains("open "))
        ) {
            val hasSearchNeed = lower.contains("search") || lower.contains("internet") || lower.contains("look up") || lower.contains("who is") || lower.contains("what does")
            val steps = ArrayList<AgentActionStep>()
            if (hasSearchNeed) {
                val q = normalized.replace(Regex("(?i)^(search|look up|find)\\s+"), "").trim()
                steps.add(AgentActionStep.SearchWeb(q, autoOpenFirst = false))
            } else {
                steps.add(AgentActionStep.ReadAndExplainScreen(text))
            }
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = if (hasSearchNeed) "Searching and analyzing results..." else "Analyzing the visible screen and page content...",
                steps = steps,
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        // 5. Replay a learned video or YouTube skill
        val matchedSkill = learnedSkills.firstOrNull { sk ->
            lower.contains(sk.name.lowercase()) || lower.contains("skill ${sk.id.lowercase()}")
        }
        if (matchedSkill != null || lower.startsWith("run skill") || lower.startsWith("play skill")) {
            val reps = extractRepeatCount(lower)
            val skKey = matchedSkill?.id ?: text.substringAfter("skill", "").trim()
            if (skKey.isBlank() && learnedSkills.isEmpty()) {
                return PlannedAgentProgram(
                    suggestedMode = null,
                    initialReply = "No video skill has been learned yet. Tap 'Learn from Video' or ask me to learn a method from YouTube first.",
                    steps = emptyList(),
                    riskZone = AgentRiskZone.USER_APPROVED_MACROS
                )
            }
            val chosen = matchedSkill ?: learnedSkills.first()
            return PlannedAgentProgram(
                suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                initialReply = "Running learned skill '${chosen.name}' (${reps}x) in strict Screen-Only mode.",
                steps = listOf(AgentActionStep.ReplayLearnedSkill(chosen.id, reps)),
                riskZone = AgentRiskZone.USER_APPROVED_MACROS
            )
        }

        // 6. Long Task Mode: multi-section monitoring ("check them for X minutes, if no changes stop...")
        val durationSec = extractDurationSeconds(lower)
        val isMonitoringTask = (lower.contains("check") || lower.contains("monitor") || lower.contains("watch")) &&
            (lower.contains("minute") || lower.contains("change") || lower.contains("section") || lower.contains("analytics"))
        if (isMonitoringTask) {
            val foundUrl = normalizeExtractedUrl(urlRegex.find(text)?.value)
            val sections = extractSectionNames(normalized)
            val stopUnchanged = lower.contains("no change") || lower.contains("not change") || lower.contains("unchanged")
            val monitorSec = if (durationSec > 0) durationSec else 300
            return PlannedAgentProgram(
                suggestedMode = AgentMode.LONG_TASK,
                initialReply = "Starting Long Task Mode: checking ${if (sections.isEmpty()) "page state" else sections.joinToString(", ")} for ${monitorSec}s (${if (stopUnchanged) "auto-stop if unchanged" else "continuous"}).",
                steps = listOf(
                    AgentActionStep.LongTaskMonitorSections(
                        startUrl = foundUrl,
                        sectionLabels = sections,
                        durationSec = monitorSec,
                        stopIfNoChange = stopUnchanged
                    )
                ),
                riskZone = detectSensitiveZone(lower, AgentRiskZone.ACCESSIBILITY)
            )
        }

        // 7. Dedicated Camera Control commands: "turn camera right", "look behind / 180", "look up", "move camera left 90", "360 scan"
        val isPureCameraMove = (lower.contains("camera") || lower.startsWith("look ") || lower.startsWith("turn ")) &&
            !lower.contains("fight") && !lower.contains("follow") && !lower.contains("nearest") && !lower.contains("hold forward")
        if (isPureCameraMove) {
            val deg = Regex("""(\d+)\s*(?:deg|degree|degrees|°)?""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val yaw = when {
                lower.contains("360") || lower.contains("all around") -> 320
                lower.contains("180") || lower.contains("behind") || lower.contains("turn around") -> 180
                lower.contains("left") -> -(deg ?: 85).coerceIn(15, 360)
                lower.contains("right") -> (deg ?: 85).coerceIn(15, 360)
                else -> 75
            }
            val pitch = when {
                lower.contains("up") -> -55
                lower.contains("down") -> 55
                else -> 0
            }
            val durMs = if (durationSec > 0) (durationSec * 1000L).coerceIn(150L, 5_000L) else 420L
            return PlannedAgentProgram(
                suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                initialReply = "Moving game camera smoothly (yaw=${yaw}%, pitch=${pitch}%, strict screen-only touch drag).",
                steps = listOf(AgentActionStep.GamingMoveCamera(yaw, pitch, durMs)),
                riskZone = classifyGamingRiskZone(lower, currentPageUrl, combat = false)
            )
        }

        // 8. Gaming Mode (Strict Screen-Only): combat / follow / approach / mine-harvest / hold buttons & joystick
        val isCombat = lower.contains("fight") || lower.contains("attack") || lower.contains("hit ") || lower.contains("hitting") || lower.contains("pvp")
        val isMineOrHarvest = (lower.contains("mine") || lower.contains("chop") || lower.contains("harvest") || lower.contains("break") || lower.contains("dig")) &&
            (lower.contains("tree") || lower.contains("wood") || lower.contains("diamond") || lower.contains("gold") || lower.contains("iron") || lower.contains("ore") || lower.contains("block") || lower.contains("nearest"))
        val isFollow = lower.contains("follow")
        val isApproach = lower.contains("nearest ") ||
            (lower.contains("go to") && (lower.contains("cow") || lower.contains("player") || lower.contains("mob") || lower.contains("sheep") || lower.contains("pig") || lower.contains("tree") || lower.contains("worker") || lower.contains("chest") || lower.contains("bed") || lower.contains("diamond")))
        val isDirectMoveOrHold = lower.contains("joystick") || lower.contains("hold forward") || lower.contains("walk forward") ||
            lower.contains("move forward") || lower.contains("hold button") || lower.contains("hold attack") || lower.contains("hold mine") ||
            lower.contains("strafe") || lower == "jump" || lower.startsWith("jump ")

        if (isCombat || isMineOrHarvest || isFollow || isApproach || isDirectMoveOrHold) {
            val repeats = extractRepeatCount(lower)
            val runSec = if (durationSec > 0) durationSec else if (isCombat || isMineOrHarvest) 15 else 10

            if (isDirectMoveOrHold && !isCombat && !isMineOrHarvest && !isFollow && !isApproach) {
                val jx = when {
                    lower.contains("left") -> -80
                    lower.contains("right") -> 80
                    else -> 0
                }
                val jy = when {
                    lower.contains("back") -> 80
                    lower.contains("forward") || lower.contains("ahead") || lower.contains("walk") -> -85
                    else -> 0
                }
                val camDx = when {
                    lower.contains("turn left") || lower.contains("look left") || lower.contains("camera left") -> -55
                    lower.contains("turn right") || lower.contains("look right") || lower.contains("camera right") -> 55
                    else -> 0
                }
                val holdPrim = lower.contains("hold button") || lower.contains("hold attack") || lower.contains("hold mine") || lower.contains("hold click")
                val jump = lower.contains("jump")
                val holdMs = (if (durationSec > 0) durationSec * 1000L else 1400L).coerceIn(150L, 30_000L)
                return PlannedAgentProgram(
                    suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                    initialReply = "Executing screen-only multi-touch hold (${holdMs}ms, zero page-code interaction).",
                    steps = List(repeats) {
                        AgentActionStep.GamingHoldDirection(
                            joyX = jx,
                            joyY = jy,
                            camDx = camDx,
                            durationMs = holdMs,
                            jumpTap = jump,
                            holdPrimaryButton = holdPrim
                        )
                    },
                    riskZone = classifyGamingRiskZone(lower, currentPageUrl, combat = false)
                )
            }

            val target = extractGamingTarget(normalized)
            val style = when {
                isCombat -> GamingTrackingStyle.COMBAT
                isMineOrHarvest -> GamingTrackingStyle.MINE_HARVEST
                isFollow -> GamingTrackingStyle.FOLLOW
                else -> GamingTrackingStyle.APPROACH
            }
            val zone = classifyGamingRiskZone(lower, currentPageUrl, combat = isCombat)
            return PlannedAgentProgram(
                suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                initialReply = when (style) {
                    GamingTrackingStyle.COMBAT ->
                        "Gaming Mode (Strict Screen-Only): predictive-tracking '$target', orbit strafing, turning camera & attacking for ${runSec}s."
                    GamingTrackingStyle.MINE_HARVEST ->
                        "Gaming Mode (Strict Screen-Only): approaching '$target', locking camera & holding primary harvest button for ${runSec}s."
                    GamingTrackingStyle.FOLLOW ->
                        "Gaming Mode (Strict Screen-Only): smoothly following '$target' with predictive camera & distance control for ${runSec}s."
                    GamingTrackingStyle.APPROACH ->
                        "Gaming Mode (Strict Screen-Only): visually locating '$target' and steering on-screen joystick for ${runSec}s."
                },
                steps = List(repeats) {
                    AgentActionStep.GamingTrackTarget(
                        targetLabel = target,
                        combatMode = isCombat,
                        durationSec = runSec,
                        trackingStyle = style
                    )
                },
                riskZone = zone
            )
        }

        // 9. Multi-Step Browsing Workflow (open URL, search, copy page text, full .zip, click, type, scroll)
        val steps = ArrayList<AgentActionStep>()
        val extractedUrl = normalizeExtractedUrl(urlRegex.find(text)?.value)
        val wantsCopyText = lower.contains("copy") && (lower.contains("text") || lower.contains("page") || lower.contains("content"))
        val wantsZip = lower.contains("zip") || lower.contains("page code") || lower.contains("whole page") || lower.contains("archive")
        val wantsSearch = lower.startsWith("find ") || lower.startsWith("search ") || lower.contains("search for ")

        val idxPick = when {
            lower.contains("first") || lower == "open 1" -> 0
            lower.contains("second") || lower == "open 2" -> 1
            lower.contains("third") || lower == "open 3" -> 2
            else -> -1
        }
        if (idxPick in lastSearchHits.indices && extractedUrl == null) {
            steps.add(AgentActionStep.OpenUrl(lastSearchHits[idxPick].url))
        } else if (extractedUrl != null) {
            steps.add(AgentActionStep.OpenUrl(extractedUrl))
        } else if (wantsSearch) {
            val cleanQuery = normalized
                .replace(Regex("(?i)^(find\\s+go\\s+to|find|search\\s+for|search)\\s+"), "")
                .replace(Regex("(?i)\\s+and\\s+copy.*$"), "")
                .trim()
            if (cleanQuery.isNotBlank()) {
                val shouldOpenTop = lower.contains("go to") || lower.contains("open") || wantsCopyText || wantsZip
                steps.add(AgentActionStep.SearchWeb(cleanQuery, autoOpenFirst = shouldOpenTop))
            }
        }

        if (wantsCopyText || wantsZip) {
            if (steps.isEmpty() && (currentPageUrl.isBlank() || currentPageUrl == "about:blank")) {
                return PlannedAgentProgram(
                    suggestedMode = AgentMode.BASIC_BROWSING,
                    initialReply = "Which website link or topic would you like me to open before copying the text and saving the ZIP?",
                    steps = listOf(AgentActionStep.AskClarification("Please share the website URL or topic name to open.")),
                    riskZone = AgentRiskZone.ACCESSIBILITY
                )
            }
            steps.add(AgentActionStep.CopyPageTextAndZip(includeZip = wantsZip))
        }

        // Support compound "... and type <text>" after opening URL or clicking a box
        val inlineTypeMatch = Regex("(?i)\\band\\s+type\\s+[\"']?([^\"']+?)[\"']?(?:\\s+and\\s+(?:press\\s+)?enter)?$").find(text)
        if (inlineTypeMatch != null) {
            val toType = inlineTypeMatch.groupValues[1].trim()
            val enter = lower.endsWith("enter") || lower.contains("press enter") || lower.contains("hit enter")
            if (toType.isNotEmpty()) {
                steps.add(AgentActionStep.TypeText(textToType = toType, pressEnter = enter))
            }
        }

        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) {
            val btnLabel = text
                .replace(Regex("(?i)^(click|tap|press)\\s+(on\\s+)?"), "")
                .replace(Regex("(?i)\\s+and\\s+type\\s+.*$"), "")
                .trim()
                .trim('"', '\'')
            if (btnLabel.isNotBlank()) {
                steps.add(0, AgentActionStep.TapVisibleText(btnLabel))
            }
        } else if (lower.contains("scroll down") || lower.contains("scroll up")) {
            steps.add(AgentActionStep.ScrollScreen(down = !lower.contains("up"), times = extractRepeatCount(lower)))
        }

        if (steps.isEmpty()) {
            if (text.split(Regex("\\s+")).size >= 2) {
                steps.add(AgentActionStep.SearchWeb(normalized, autoOpenFirst = false))
                return PlannedAgentProgram(
                    suggestedMode = AgentMode.BASIC_BROWSING,
                    initialReply = "Searching the web for '$normalized' and summarizing the top findings...",
                    steps = steps,
                    riskZone = detectSensitiveZone(lower, AgentRiskZone.ACCESSIBILITY)
                )
            }
            val obsHint = screenObs?.ocrBlocks?.take(4)?.joinToString(", ") { it.text }.orEmpty()
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "I am ready. ${if (obsHint.isNotBlank()) "I currently see [$obsHint] on screen. " else ""}Should I search the web for '$text', type it on screen, or click it?",
                steps = listOf(AgentActionStep.AskClarification("Clarify action for '$text'")),
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        val zone = detectSensitiveZone(lower, AgentRiskZone.ACCESSIBILITY)
        return PlannedAgentProgram(
            suggestedMode = if (steps.size > 2) AgentMode.LONG_TASK else AgentMode.BASIC_BROWSING,
            initialReply = "Executing ${steps.size}-step plan...",
            steps = steps,
            riskZone = zone
        )
    }

    private fun parseTypingCommand(rawText: String, lower: String): PlannedAgentProgram? {
        val startsWithType = lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("enter text ") ||
            lower.startsWith("clear and type ") || Regex("(?i)^in\\s+[a-z0-9_ ]{2,20}\\s+type\\s+").containsMatchIn(rawText)
        if (!startsWithType) return null

        val clearFirst = lower.contains("clear and type") || lower.contains("clear text")
        val pressEnter = lower.endsWith(" and press enter") || lower.endsWith(" and hit enter") || lower.endsWith(" then enter") || lower.contains("press enter")

        var fieldHint = ""
        val inFieldMatch = Regex("(?i)^in\\s+([a-z0-9_ ]{2,20}?)\\s+type\\s+(.*)$").find(rawText)
        val rawBody = if (inFieldMatch != null) {
            fieldHint = inFieldMatch.groupValues[1].trim()
            inFieldMatch.groupValues[2]
        } else {
            rawText.replace(Regex("(?i)^(clear\\s+and\\s+type|enter\\s+text|type|write)\\s+"), "")
        }

        val quoted = Regex("^[\"'](.*?)[\"']").find(rawBody.trim())?.groupValues?.get(1)
        val cleanedPayload = (quoted ?: rawBody
            .replace(Regex("(?i)\\s+(?:and\\s+press\\s+enter|and\\s+hit\\s+enter|then\\s+enter|press\\s+enter)\\s*$"), "")
            .replace(Regex("(?i)\\s+into\\s+([a-z0-9_ ]{2,20})$")) { m ->
                if (fieldHint.isEmpty()) fieldHint = m.groupValues[1].trim()
                ""
            })
            .trim()

        if (cleanedPayload.isEmpty()) return null

        return PlannedAgentProgram(
            suggestedMode = null,
            initialReply = buildString {
                append("Typing '${cleanedPayload.take(40)}' on screen")
                if (fieldHint.isNotBlank()) append(" into '$fieldHint'")
                if (pressEnter) append(" + Enter")
                append(" (zero JS injection).")
            },
            steps = listOf(
                AgentActionStep.TypeText(
                    textToType = cleanedPayload,
                    fieldHint = fieldHint,
                    clearFirst = clearFirst,
                    pressEnter = pressEnter
                )
            ),
            riskZone = detectSensitiveZone(lower, AgentRiskZone.ACCESSIBILITY)
        )
    }

    fun parseModelJsonIntoPlan(
        rawModelText: String,
        userText: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        modelBadge: String
    ): PlannedAgentProgram? {
        return runCatching {
            val jStart = rawModelText.indexOf('{')
            val jEnd = rawModelText.lastIndexOf('}')
            if (jStart < 0 || jEnd <= jStart) {
                if (rawModelText.length >= 6 && currentMode != AgentMode.GAMING_SCREEN_ONLY) {
                    return PlannedAgentProgram(
                        suggestedMode = null,
                        initialReply = rawModelText.trim(),
                        steps = emptyList(),
                        riskZone = AgentRiskZone.ACCESSIBILITY,
                        modelUsedLabel = modelBadge
                    )
                }
                return null
            }

            val parsed = JSONObject(rawModelText.substring(jStart, jEnd + 1))
            val reply = parsed.optString("reply", "Working on it...").ifBlank { "Working on it..." }
            val action = parsed.optString("action", "reply").lowercase().trim()
            val target = parsed.optString("target", "").trim()
            val textToType = parsed.optString("textToType", "").trim()
            val pressEnter = parsed.optBoolean("pressEnter", false)
            val dur = parsed.optInt("durationSec", 10).coerceIn(2, 3600)
            val yawPct = parsed.optInt("yawPct", 0).coerceIn(-360, 360)
            val pitchPct = parsed.optInt("pitchPct", 0).coerceIn(-180, 180)
            val holdPrim = parsed.optBoolean("holdPrimary", false)
            val jump = parsed.optBoolean("jump", false)
            val combat = parsed.optBoolean("combat", false)
            val autoExecSkill = parsed.optBoolean("autoExecuteSkill", false)

            // STRICT GAMING FIREWALL: block any page/DOM/URL/archive tool if model is in GAMING_SCREEN_ONLY
            if (currentMode == AgentMode.GAMING_SCREEN_ONLY && action in setOf("open_url", "copy_and_zip", "inspect_readonly", "search")) {
                return PlannedAgentProgram(
                    suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                    initialReply = "Strict Screen-Only Gaming Mode is active; switch to Basic Browsing Mode for URL/archive tools.",
                    steps = emptyList(),
                    riskZone = AgentRiskZone.ACCESSIBILITY,
                    modelUsedLabel = modelBadge
                )
            }

            val stepList: List<AgentActionStep> = when (action) {
                "search" -> listOf(AgentActionStep.SearchWeb(target.ifBlank { userText }, autoOpenFirst = false))
                "youtube_method" -> listOf(AgentActionStep.ResearchBestMethod(target.ifBlank { userText }, autoExecuteCompiledSkill = autoExecSkill))
                "open_url" -> listOfNotNull(normalizeExtractedUrl(target)?.let { AgentActionStep.OpenUrl(it) })
                "copy_and_zip" -> buildList {
                    normalizeExtractedUrl(target)?.let { add(AgentActionStep.OpenUrl(it)) }
                    add(AgentActionStep.CopyPageTextAndZip(includeZip = true))
                }
                "read_screen" -> listOf(AgentActionStep.ReadAndExplainScreen(target.ifBlank { userText }))
                "inspect_readonly" -> listOf(AgentActionStep.OwnerReadOnlyInspectPage(target.ifBlank { userText }))
                "tap_text" -> if (target.isNotBlank()) listOf(AgentActionStep.TapVisibleText(target)) else emptyList()
                "type_text" -> {
                    val payload = textToType.ifBlank { target }
                    if (payload.isNotBlank()) {
                        listOf(AgentActionStep.TypeText(textToType = payload, fieldHint = if (textToType.isNotBlank()) target else "", pressEnter = pressEnter))
                    } else emptyList()
                }
                "scroll" -> listOf(AgentActionStep.ScrollScreen(down = !target.contains("up", true), times = 1))
                "gaming_camera" -> listOf(
                    AgentActionStep.GamingMoveCamera(
                        yawPercent = if (yawPct != 0) yawPct else 85,
                        pitchPercent = pitchPct,
                        durationMs = (dur * 100L).coerceIn(220L, 2_500L)
                    )
                )
                "gaming_hold" -> listOf(
                    AgentActionStep.GamingHoldDirection(
                        joyX = 0,
                        joyY = -85,
                        camDx = yawPct.coerceIn(-80, 80),
                        durationMs = (dur * 1000L).coerceIn(200L, 30_000L),
                        jumpTap = jump,
                        camDy = pitchPct.coerceIn(-60, 60),
                        holdPrimaryButton = holdPrim
                    )
                )
                "gaming_track" -> {
                    val style = when {
                        combat -> GamingTrackingStyle.COMBAT
                        holdPrim -> GamingTrackingStyle.MINE_HARVEST
                        userText.contains("follow", true) -> GamingTrackingStyle.FOLLOW
                        else -> GamingTrackingStyle.APPROACH
                    }
                    listOf(AgentActionStep.GamingTrackTarget(target.ifBlank { "target" }, combat, dur, style))
                }
                "replay_skill" -> listOf(AgentActionStep.ReplayLearnedSkill(target, 1))
                "long_monitor" -> listOf(
                    AgentActionStep.LongTaskMonitorSections(
                        startUrl = normalizeExtractedUrl(urlRegex.find(userText)?.value),
                        sectionLabels = extractSectionNames(userText),
                        durationSec = dur.coerceAtLeast(30),
                        stopIfNoChange = userText.contains("no change", true) || userText.contains("not change", true)
                    )
                )
                "ask" -> listOf(AgentActionStep.AskClarification(reply))
                else -> emptyList()
            }

            val suggested = when {
                action in setOf("gaming_track", "gaming_camera", "gaming_hold") -> AgentMode.GAMING_SCREEN_ONLY
                action == "long_monitor" -> AgentMode.LONG_TASK
                else -> null
            }
            val zone = if (suggested == AgentMode.GAMING_SCREEN_ONLY) {
                classifyGamingRiskZone(userText.lowercase(), currentPageUrl, combat = combat)
            } else {
                detectSensitiveZone((userText + " " + target).lowercase(), AgentRiskZone.ACCESSIBILITY)
            }

            PlannedAgentProgram(
                suggestedMode = suggested,
                initialReply = reply,
                steps = stepList,
                riskZone = zone,
                modelUsedLabel = modelBadge
            )
        }.getOrNull()
    }

    private suspend fun tryRemoteOrLocalLlmPlan(
        userText: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        screenObs: ScreenObservation?,
        endpointRaw: String,
        ownerResearchUnlocked: Boolean
    ): PlannedAgentProgram? = withContext(Dispatchers.IO) {
        runCatching {
            val parts = endpointRaw.split('|').map { it.trim() }
            val endpoint = parts.getOrNull(0).orEmpty()
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) return@runCatching null
            val apiKey = parts.getOrNull(1).orEmpty()
            val modelName = parts.getOrNull(2)?.ifBlank { "local-model" } ?: "local-model"

            val sysPrompt = buildSpecializedSystemPrompt(currentMode, ownerResearchUnlocked)
            val liveCtx = buildLiveContextPacket(
                mode = currentMode,
                currentPageUrl = currentPageUrl,
                currentPageTitle = "",
                screenObs = screenObs,
                learnedSkills = emptyList(),
                recentHistory = emptyList(),
                ownerResearchUnlocked = ownerResearchUnlocked
            )
            val userPayload = "LIVE_CONTEXT=$liveCtx\nUSER_COMMAND=$userText"

            val reqJson = JSONObject()
                .put("model", modelName)
                .put("temperature", 0.25)
                .put("max_tokens", 240)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", sysPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPayload))
                )

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_500
                readTimeout = 6_500
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val respTxt = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val content = JSONObject(respTxt)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            val (clean, _) = FableLocalLlmRuntime.splitThinkingAndCleanOutput(content)
            parseModelJsonIntoPlan(clean, userText, currentMode, currentPageUrl, "Endpoint ($modelName)")
        }.getOrNull()
    }

    private fun normalizeExtractedUrl(raw: String?): String? {
        val clean = raw?.trim()?.trimEnd('.', ',', ')', ']', '"', '\'') ?: return null
        if (clean.isBlank()) return null
        return if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            clean
        } else {
            "https://$clean"
        }
    }

    private fun extractDurationSeconds(lower: String): Int {
        Regex("""(\d+)\s*(?:sec|secs|second|seconds|s)\b""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it.coerceIn(1, 7200)
        }
        Regex("""(\d+)\s*(?:min|mins|minute|minutes|m)\b""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return (it * 60).coerceIn(10, 7200)
        }
        return 0
    }

    private fun extractRepeatCount(lower: String): Int {
        Regex("""(\d+)\s*(?:more\s+)?times""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it.coerceIn(1, 20)
        }
        if (lower.contains("twice")) return 2
        if (lower.contains("three times")) return 3
        return 1
    }

    private fun extractGamingTarget(text: String): String {
        val lower = text.lowercase()
        for (entity in listOf("cow", "sheep", "pig", "zombie", "skeleton", "worker", "diamond", "gold", "iron", "coal", "tree", "wood", "chest", "bed")) {
            if (lower.contains(entity)) return entity
        }
        val playerMatch = Regex("""(?i)(?:player|user|target|with|follow)\s+([A-Za-z0-9_]{2,24})""").find(text)
        if (playerMatch != null) {
            val candidate = playerMatch.groupValues[1]
            if (candidate.lowercase() !in setOf("and", "then", "for", "in", "on", "the", "you", "nearest", "closest", "him", "her")) {
                return candidate
            }
        }
        return "target"
    }

    private fun extractSectionNames(text: String): List<String> {
        val known = listOf("analytics", "members", "upgrade", "projects", "billing", "overview", "settings", "dashboard", "logs")
        val lower = text.lowercase()
        return known.filter { lower.contains(it) }.map { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun classifyGamingRiskZone(lower: String, url: String, combat: Boolean): AgentRiskZone {
        val u = url.lowercase()
        if (lower.contains("private") || u.contains("private") || u.contains("classic") || u.contains("peaceful")) {
            return AgentRiskZone.PRIVATE_WORLDS
        }
        if (lower.contains("test")) return AgentRiskZone.GAME_TESTING
        if (lower.contains("single") || lower.contains("solo")) return AgentRiskZone.SINGLE_PLAYER
        return if (combat) AgentRiskZone.COMPETITIVE_ONLINE else AgentRiskZone.ACCESSIBILITY
    }

    fun detectSensitiveZone(lowerText: String, fallback: AgentRiskZone): AgentRiskZone {
        val sensitiveWords = listOf(
            "buy", "purchase", "pay", "checkout", "upgrade plan", "subscribe",
            "delete", "remove account", "erase", "wipe", "password", "transfer money", "send message"
        )
        if (sensitiveWords.any { lowerText.contains(it) }) {
            return AgentRiskZone.IMPORTANT_SENSITIVE
        }
        return fallback
    }
}
