package com.fable.browser.agent

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
    data class OpenUrl(val url: String) : AgentActionStep
    data class CopyPageTextAndZip(val includeZip: Boolean) : AgentActionStep
    data class ReadAndExplainScreen(val focusQuery: String = "") : AgentActionStep
    data class TapVisibleText(val targetText: String) : AgentActionStep
    data class ScrollScreen(val down: Boolean, val times: Int = 1) : AgentActionStep
    data class GamingTrackTarget(
        val targetLabel: String,
        val combatMode: Boolean,
        val durationSec: Int
    ) : AgentActionStep
    data class GamingHoldDirection(
        val joyX: Int,
        val joyY: Int,
        val camDx: Int,
        val durationMs: Long,
        val jumpTap: Boolean
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
    val riskZone: AgentRiskZone
)

object FableNeuralBrain {

    private val urlRegex = Regex("""https?://[^\s"'<>]+|(?:[a-zA-Z0-9-]+\.)+(?:com|org|net|io|gg|app|dev|ai|edu|gov)(?:/[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)

    suspend fun planUserInstruction(
        rawInput: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        lastSearchHits: List<WebSearchHit>,
        learnedSkills: List<LearnedVideoSkill>,
        screenObs: ScreenObservation?,
        llmEndpointCfg: String
    ): PlannedAgentProgram {
        val text = rawInput.trim()
        if (text.isBlank()) {
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "Tell me what you want me to do, or ask me about what is on screen.",
                steps = emptyList(),
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        // If user configured a local llama.cpp / Ollama / OpenAI-compatible neural endpoint and isn't in ultra-fast gaming reflex command, query neural planner first with fast timeout!
        if (llmEndpointCfg.isNotBlank() && currentMode != AgentMode.GAMING_SCREEN_ONLY) {
            val neuralPlan = tryRemoteOrLocalLlmPlan(text, currentMode, currentPageUrl, screenObs, llmEndpointCfg)
            if (neuralPlan != null) return neuralPlan
        }

        return buildDeterministicSmartPlan(
            text = text,
            currentMode = currentMode,
            currentPageUrl = currentPageUrl,
            lastSearchHits = lastSearchHits,
            learnedSkills = learnedSkills,
            screenObs = screenObs
        )
    }

    fun buildDeterministicSmartPlan(
        text: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        lastSearchHits: List<WebSearchHit>,
        learnedSkills: List<LearnedVideoSkill>,
        screenObs: ScreenObservation?
    ): PlannedAgentProgram {
        val lower = text.lowercase().trim()

        // 0. Instant stop / cancel commands
        if (lower in setOf("stop", "cancel", "halt", "abort", "release", "pause ai", "stop ai")) {
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "Stopped all actions and released all controls.",
                steps = emptyList(),
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        // 1. Conversational / Question about current screen or capability
        if (lower.startsWith("what is on") || lower.startsWith("what do you see") ||
            lower.startsWith("read screen") || lower.startsWith("explain") ||
            lower.startsWith("summarize") || lower.endsWith("?") && !lower.contains("go to") && !lower.contains("open ")) {
            val hasSearchNeed = lower.contains("search") || lower.contains("internet") || lower.contains("look up") || lower.contains("who is") || lower.contains("what does")
            val steps = ArrayList<AgentActionStep>()
            if (hasSearchNeed) {
                val q = text.replace(Regex("(?i)^(search|look up|find)\\s+"), "").trim()
                steps.add(AgentActionStep.SearchWeb(q, autoOpenFirst = false))
            } else {
                steps.add(AgentActionStep.ReadAndExplainScreen(text))
            }
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = if (hasSearchNeed) "Searching and analyzing results..." else "Reading the visible screen and page content...",
                steps = steps,
                riskZone = AgentRiskZone.ACCESSIBILITY
            )
        }

        // 2. Check if user wants to replay a learned video skill
        val matchedSkill = learnedSkills.firstOrNull { sk ->
            lower.contains(sk.name.lowercase()) || lower.contains("skill ${sk.id.lowercase()}")
        }
        if (matchedSkill != null || lower.startsWith("run skill") || lower.startsWith("play skill")) {
            val reps = extractRepeatCount(lower)
            val skKey = matchedSkill?.id ?: text.substringAfter("skill", "").trim()
            if (skKey.isBlank() && learnedSkills.isEmpty()) {
                return PlannedAgentProgram(
                    suggestedMode = null,
                    initialReply = "No video skill has been learned yet. Tap 'Learn from Video' to upload a gameplay or task video first.",
                    steps = emptyList(),
                    riskZone = AgentRiskZone.USER_APPROVED_MACROS
                )
            }
            val chosen = matchedSkill ?: learnedSkills.first()
            return PlannedAgentProgram(
                suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                initialReply = "Running learned video skill '${chosen.name}' (${reps}x) in strict Screen-Only mode.",
                steps = listOf(AgentActionStep.ReplayLearnedSkill(chosen.id, reps)),
                riskZone = AgentRiskZone.USER_APPROVED_MACROS
            )
        }

        // 3. Long Task Mode: multi-section monitoring ("check them for X minutes, if no changes stop...")
        val durationSec = extractDurationSeconds(lower)
        val isMonitoringTask = (lower.contains("check") || lower.contains("monitor") || lower.contains("watch")) &&
            (lower.contains("minute") || lower.contains("change") || lower.contains("section") || lower.contains("analytics"))
        if (isMonitoringTask) {
            val foundUrl = normalizeExtractedUrl(urlRegex.find(text)?.value)
            val sections = extractSectionNames(text)
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

        // 4. Gaming Mode (Strict Screen-Only): fight / hit / follow / go to nearest cow / player / hold joystick / jump
        val isCombat = lower.contains("fight") || lower.contains("attack") || lower.contains("hit ") || lower.contains("hitting") || lower.contains("pvp")
        val isFollowOrApproach = lower.contains("follow") || lower.contains("nearest ") ||
            (lower.contains("go to") && (lower.contains("cow") || lower.contains("player") || lower.contains("mob") || lower.contains("sheep") || lower.contains("pig") || lower.contains("tree") || lower.contains("worker")))
        val isDirectMove = lower.contains("joystick") || lower.contains("hold forward") || lower.contains("walk forward") ||
            lower.contains("move forward") || lower.contains("strafe") || lower == "jump" || lower.startsWith("jump ")

        if (isCombat || isFollowOrApproach || (currentMode == AgentMode.GAMING_SCREEN_ONLY && isDirectMove) || isDirectMove) {
            val repeats = extractRepeatCount(lower)
            val runSec = if (durationSec > 0) durationSec else if (isCombat) 15 else 10
            if (isDirectMove && !isCombat && !isFollowOrApproach) {
                val jx = when {
                    lower.contains("left") -> -80
                    lower.contains("right") -> 80
                    else -> 0
                }
                val jy = when {
                    lower.contains("back") -> 80
                    lower.contains("forward") || lower.contains("ahead") -> -85
                    else -> 0
                }
                val camDx = when {
                    lower.contains("turn left") || lower.contains("look left") -> -55
                    lower.contains("turn right") || lower.contains("look right") -> 55
                    else -> 0
                }
                val jump = lower.contains("jump")
                val holdMs = (if (durationSec > 0) durationSec * 1000L else 1200L).coerceIn(150L, 30_000L)
                return PlannedAgentProgram(
                    suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                    initialReply = "Executing screen-only movement (${holdMs}ms, zero page-code interaction).",
                    steps = List(repeats) { AgentActionStep.GamingHoldDirection(jx, jy, camDx, holdMs, jump) },
                    riskZone = classifyGamingRiskZone(lower, currentPageUrl, combat = false)
                )
            }

            val target = extractGamingTarget(text)
            val zone = classifyGamingRiskZone(lower, currentPageUrl, combat = isCombat)
            return PlannedAgentProgram(
                suggestedMode = AgentMode.GAMING_SCREEN_ONLY,
                initialReply = if (isCombat) {
                    "Gaming Mode (Strict Screen-Only): tracking '$target', fast strafing, turning camera & attacking for ${runSec}s."
                } else {
                    "Gaming Mode (Strict Screen-Only): visually locating '$target' and steering on-screen joystick for ${runSec}s."
                },
                steps = List(repeats) {
                    AgentActionStep.GamingTrackTarget(
                        targetLabel = target,
                        combatMode = isCombat,
                        durationSec = runSec
                    )
                },
                riskZone = zone
            )
        }

        // 5. Multi-Step Browsing Workflow: e.g. "find / go to <website> and copy the page text and deliver whole page code as in zip file"
        val steps = ArrayList<AgentActionStep>()
        val extractedUrl = normalizeExtractedUrl(urlRegex.find(text)?.value)
        val wantsCopyText = lower.contains("copy") && (lower.contains("text") || lower.contains("page") || lower.contains("content"))
        val wantsZip = lower.contains("zip") || lower.contains("page code") || lower.contains("whole page") || lower.contains("archive")
        val wantsSearch = lower.startsWith("find ") || lower.startsWith("search ") || lower.contains("search for ")

        // Reference to previous search result ("open 1", "open first", "open second")
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
            val cleanQuery = text
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

        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) {
            val btnLabel = text.replace(Regex("(?i)^(click|tap|press)\\s+(on\\s+)?"), "").trim().trim('"', '\'')
            if (btnLabel.isNotBlank()) {
                steps.add(AgentActionStep.TapVisibleText(btnLabel))
            }
        } else if (lower.contains("scroll down") || lower.contains("scroll up")) {
            steps.add(AgentActionStep.ScrollScreen(down = !lower.contains("up"), times = extractRepeatCount(lower)))
        }

        if (steps.isEmpty()) {
            // Treat remaining natural-language request as a smart search + screen/page answer if it looks like a lookup, or ask clarification
            if (text.split(Regex("\\s+")).size >= 2) {
                steps.add(AgentActionStep.SearchWeb(text, autoOpenFirst = false))
                return PlannedAgentProgram(
                    suggestedMode = AgentMode.BASIC_BROWSING,
                    initialReply = "Searching the web for '$text' and summarizing the best results...",
                    steps = steps,
                    riskZone = detectSensitiveZone(lower, AgentRiskZone.ACCESSIBILITY)
                )
            }
            val obsHint = screenObs?.ocrBlocks?.take(4)?.joinToString(", ") { it.text }.orEmpty()
            return PlannedAgentProgram(
                suggestedMode = null,
                initialReply = "I am ready. ${if (obsHint.isNotBlank()) "I currently see [$obsHint] on screen. " else ""}Should I search the web for '$text', click it on screen, or open a URL?",
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

    private suspend fun tryRemoteOrLocalLlmPlan(
        userText: String,
        currentMode: AgentMode,
        currentPageUrl: String,
        screenObs: ScreenObservation?,
        endpointRaw: String
    ): PlannedAgentProgram? = withContext(Dispatchers.IO) {
        runCatching {
            // Format: "http://127.0.0.1:8080/v1/chat/completions" or "https://...|BEARER_KEY|MODEL_NAME"
            val parts = endpointRaw.split('|').map { it.trim() }
            val endpoint = parts.getOrNull(0).orEmpty()
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) return@runCatching null
            val apiKey = parts.getOrNull(1).orEmpty()
            val modelName = parts.getOrNull(2)?.ifBlank { "local-model" } ?: "local-model"

            val screenSummary = screenObs?.fullVisibleText?.take(1200).orEmpty()
            val sysPrompt = """
                You are Fable Browser's ultra-fast mobile AI Agent. Respond ONLY with a compact JSON object:
                {"reply":"short helpful message","action":"none|search|open_url|copy_and_zip|tap_text|gaming_track|ask","target":"url or query or button or entity","durationSec":10,"combat":false}
            """.trimIndent()
            val userPayload = "Mode=${currentMode.name}\nCurrentURL=$currentPageUrl\nVisibleScreenText:\n$screenSummary\n\nUser: $userText"

            val reqJson = JSONObject()
                .put("model", modelName)
                .put("temperature", 0.1)
                .put("max_tokens", 220)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", sysPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPayload))
                )

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_500
                readTimeout = 6_000
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
            val jStart = content.indexOf('{')
            val jEnd = content.lastIndexOf('}')
            if (jStart < 0 || jEnd <= jStart) return@runCatching null
            val parsed = JSONObject(content.substring(jStart, jEnd + 1))
            val reply = parsed.optString("reply", "Working on it...")
            val action = parsed.optString("action", "none")
            val target = parsed.optString("target", "")
            val dur = parsed.optInt("durationSec", 10).coerceIn(2, 1800)
            val combat = parsed.optBoolean("combat", false)

            val stepList = when (action) {
                "search" -> listOf(AgentActionStep.SearchWeb(target.ifBlank { userText }, autoOpenFirst = false))
                "open_url" -> listOfNotNull(normalizeExtractedUrl(target)?.let { AgentActionStep.OpenUrl(it) })
                "copy_and_zip" -> buildList {
                    normalizeExtractedUrl(target)?.let { add(AgentActionStep.OpenUrl(it)) }
                    add(AgentActionStep.CopyPageTextAndZip(includeZip = true))
                }
                "tap_text" -> listOf(AgentActionStep.TapVisibleText(target))
                "gaming_track" -> listOf(AgentActionStep.GamingTrackTarget(target.ifBlank { "target" }, combat, dur))
                "ask" -> listOf(AgentActionStep.AskClarification(reply))
                else -> listOf(AgentActionStep.Reply(reply))
            }
            if (stepList.isEmpty()) return@runCatching null
            PlannedAgentProgram(
                suggestedMode = if (action == "gaming_track") AgentMode.GAMING_SCREEN_ONLY else null,
                initialReply = reply,
                steps = stepList,
                riskZone = detectSensitiveZone(userText.lowercase() + " " + target.lowercase(), AgentRiskZone.ACCESSIBILITY)
            )
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
        for (animal in listOf("cow", "sheep", "pig", "zombie", "skeleton", "worker", "diamond", "gold", "tree")) {
            if (lower.contains(animal)) return animal
        }
        val playerMatch = Regex("""(?i)(?:player|user|target|with)\s+([A-Za-z0-9_]{2,24})""").find(text)
        if (playerMatch != null) {
            val candidate = playerMatch.groupValues[1]
            if (candidate.lowercase() !in setOf("and", "then", "for", "in", "on", "the", "you")) {
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
