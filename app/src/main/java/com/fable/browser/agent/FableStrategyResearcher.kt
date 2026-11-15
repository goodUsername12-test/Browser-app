package com.fable.browser.agent

import android.content.Context
import android.os.SystemClock
import android.text.Html
import com.fable.browser.engine.FableUnifiedEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

data class YouTubeTutorialDigest(
    val videoId: String,
    val title: String,
    val url: String,
    val channelOrMeta: String,
    val keyTranscriptLines: List<String>
)

data class StrategyResearchPackage(
    val topic: String,
    val youtubeVideos: List<YouTubeTutorialDigest>,
    val webHits: List<WebSearchHit>,
    val synthesizedSteps: List<String>,
    val compiledSkill: LearnedVideoSkill?,
    val elapsedMs: Long
)

/**
 * Ultra-fast parallel YouTube + Web strategy & method researcher.
 * Pulls YouTube video metadata + timedtext captions + top web tutorial guides in parallel
 * (~1.2-3.0s) and compiles actionable step-by-step playbooks + screen-only LearnedVideoSkills.
 */
object FableStrategyResearcher {

    suspend fun researchBestMethod(
        context: Context,
        rawQuery: String,
        synthesizeExecutableSkill: Boolean = true,
        onStatus: ((String) -> Unit)? = null
    ): StrategyResearchPackage = withContext(Dispatchers.IO) {
        val t0 = SystemClock.uptimeMillis()
        val cleanTopic = rawQuery
            .replace(Regex("(?i)^(find|search|look\\s+up|watch|check|get\\s+info(?:rmation)?\\s+about|what\\s+is\\s+the)\\s+"), "")
            .trim()
            .ifBlank { rawQuery.trim() }

        onStatus?.invoke("Fast-scanning YouTube tutorials & strategy guides for '$cleanTopic'...")

        coroutineScope {
            val ytDeferred = async { searchAndExtractYouTubeTutorials(cleanTopic, maxVideos = 3) }
            val webDeferred = async {
                FableSearchAndReader.searchWeb("$cleanTopic best method tutorial guide", maxResults = 4)
            }

            val ytList = ytDeferred.await()
            val webHits = webDeferred.await()

            val topGuideDeferred = webHits.firstOrNull { !it.url.contains("youtube.com", true) }?.let { hit ->
                async { FableSearchAndReader.fetchAndReadPage(hit.url, allowCookies = false) }
            }
            val topGuide = topGuideDeferred?.await()

            val evidenceLines = ArrayList<String>()
            ytList.forEach { vid ->
                if (vid.keyTranscriptLines.isNotEmpty()) {
                    evidenceLines.addAll(vid.keyTranscriptLines.take(6))
                } else if (vid.channelOrMeta.isNotBlank()) {
                    evidenceLines.add("${vid.title}: ${vid.channelOrMeta}")
                }
            }
            webHits.forEach { h ->
                if (h.snippet.isNotBlank()) evidenceLines.add(h.snippet)
            }
            topGuide?.summaryBullets?.let { evidenceLines.addAll(it.take(5)) }

            val distinctSteps = extractActionableSteps(cleanTopic, ytList, evidenceLines)

            // Optionally refine with on-device Qwen3.5-0.8B if installed in Vault!
            val finalSteps = runCatching {
                if (FableLocalLlmRuntime.chooseBestModelFile(context, preferFastRouter = false) != null) {
                    onStatus?.invoke("Synthesizing pro playbook with local Qwen model...")
                    val sys = "You are Fable Strategy Synthesizer. Summarize the fastest, most effective step-by-step method in 4 to 6 short numbered lines. Include exact movement, camera, timing, or UI steps."
                    val usr = "Topic: $cleanTopic\nYouTube & Guide Evidence:\n" +
                        evidenceLines.take(12).joinToString("\n- ")
                    val llmRes = FableLocalLlmRuntime.generateOnDevice(
                        context = context,
                        preferFastRouter = false,
                        systemPrompt = sys,
                        userPrompt = usr,
                        maxTokens = 220,
                        temperature = 0.35f,
                        enableThinking = false
                    )
                    val parsedLines = llmRes?.cleanOutput
                        ?.lines()
                        ?.map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                        ?.filter { it.length >= 12 }
                        .orEmpty()
                    if (parsedLines.size >= 2) parsedLines.take(6) else distinctSteps
                } else {
                    distinctSteps
                }
            }.getOrDefault(distinctSteps)

            val compiledSkill = if (synthesizeExecutableSkill) {
                compileMethodIntoScreenSkill(context, cleanTopic, finalSteps, ytList)
            } else null

            StrategyResearchPackage(
                topic = cleanTopic,
                youtubeVideos = ytList,
                webHits = webHits,
                synthesizedSteps = finalSteps,
                compiledSkill = compiledSkill,
                elapsedMs = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1L)
            )
        }
    }

    private suspend fun searchAndExtractYouTubeTutorials(
        topic: String,
        maxVideos: Int
    ): List<YouTubeTutorialDigest> = coroutineScope {
        val ua = FableUnifiedEngine.defaultBlinkUserAgent.ifBlank {
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
        }
        val candidates = ArrayList<Triple<String, String, String>>() // videoId, title, snippet

        runCatching {
            val q = URLEncoder.encode("$topic best method tutorial", "UTF-8")
            val html = httpGetFast("https://www.youtube.com/results?search_query=$q&hl=en", ua, 5_500)
            val idTitleRe = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"\\}")
            val seen = HashSet<String>()
            for (m in idTitleRe.findAll(html)) {
                val vid = m.groupValues[1]
                val title = unescapeJsonString(m.groupValues[2])
                if (seen.add(vid) && title.isNotBlank()) {
                    candidates.add(Triple(vid, title, ""))
                    if (candidates.size >= maxVideos) break
                }
            }
        }

        if (candidates.isEmpty()) {
            val ddg = FableSearchAndReader.searchWeb("site:youtube.com/watch $topic tutorial best method", maxResults = maxVideos)
            for (hit in ddg) {
                val vid = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(hit.url)?.groupValues?.get(1)
                    ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(hit.url)?.groupValues?.get(1)
                    ?: continue
                candidates.add(Triple(vid, hit.title, hit.snippet))
            }
        }

        candidates.take(maxVideos).map { (vid, title, fallbackSnip) ->
            async {
                val transcript = fetchYouTubeTranscriptOrDescriptionFast(vid, ua)
                YouTubeTutorialDigest(
                    videoId = vid,
                    title = title,
                    url = "https://www.youtube.com/watch?v=$vid",
                    channelOrMeta = fallbackSnip,
                    keyTranscriptLines = transcript
                )
            }
        }.map { it.await() }
    }

    private fun fetchYouTubeTranscriptOrDescriptionFast(videoId: String, ua: String): List<String> {
        return runCatching {
            val watchHtml = httpGetFast("https://www.youtube.com/watch?v=$videoId&hl=en", ua, 5_500)
            if (watchHtml.isBlank()) return@runCatching emptyList()

            val out = ArrayList<String>()

            // 1. Try captionTracks timedtext XML URL for ultra-fast spoken tutorial steps
            val capMatch = Regex("\"captionTracks\":\\[\\{\"baseUrl\":\"([^\"]+)\"").find(watchHtml)
            if (capMatch != null) {
                val capUrl = unescapeJsonString(capMatch.groupValues[1])
                if (capUrl.startsWith("https://")) {
                    val xml = httpGetFast(capUrl, ua, 4_500)
                    val textRe = Regex("""<text[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
                    val chunks = ArrayList<String>()
                    val sb = StringBuilder()
                    for (tm in textRe.findAll(xml)) {
                        val line = Html.fromHtml(tm.groupValues[1], Html.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .replace('\n', ' ')
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        if (line.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(line)
                            if (sb.length >= 110) {
                                chunks.add(sb.toString())
                                sb.setLength(0)
                                if (chunks.size >= 8) break
                            }
                        }
                    }
                    if (sb.length >= 25 && chunks.size < 8) chunks.add(sb.toString())
                    out.addAll(chunks)
                }
            }

            // 2. Also extract shortDescription chapters / numbered tips
            val descMatch = Regex("\"shortDescription\":\"((?:\\\\.|[^\"\\\\])*)\"").find(watchHtml)
            if (descMatch != null) {
                val desc = unescapeJsonString(descMatch.groupValues[1])
                desc.lines()
                    .map { it.trim() }
                    .filter { it.length in 14..160 && !it.startsWith("http", true) && !it.startsWith("#") }
                    .take(5)
                    .forEach { out.add(it) }
            }

            out.distinct().take(8)
        }.getOrDefault(emptyList())
    }

    private fun extractActionableSteps(
        topic: String,
        ytList: List<YouTubeTutorialDigest>,
        evidence: List<String>
    ): List<String> {
        val actionKeywords = listOf(
            "first", "step", "hold", "jump", "sprint", "strafe", "crouch", "sneak",
            "aim", "camera", "turn", "click", "tap", "mine", "dig", "level", "layer",
            "farm", "build", "bridge", "place", "upgrade", "fast", "method", "trick", "tip"
        )
        val ranked = evidence
            .flatMap { block -> block.split(Regex("(?<=[.!?])\\s+")) }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 22..200 }
            .distinct()
            .sortedByDescending { sentence ->
                val l = sentence.lowercase()
                actionKeywords.count { l.contains(it) }
            }
            .take(5)

        if (ranked.isNotEmpty()) return ranked

        val topTitle = ytList.firstOrNull()?.title ?: topic
        return listOf(
            "Align camera smoothly on target/objective before engaging ($topTitle).",
            "Maintain optimal movement spacing with controlled forward/strafe joystick vectors.",
            "Verify visual screen state after each action cycle before repeating."
        )
    }

    private fun compileMethodIntoScreenSkill(
        context: Context,
        topic: String,
        steps: List<String>,
        ytList: List<YouTubeTutorialDigest>
    ): LearnedVideoSkill? = runCatching {
        val joined = (topic + " " + steps.joinToString(" ")).lowercase()
        val skillSteps = ArrayList<SkillStep>()
        var offset = 0L

        fun push(dur: Long, jx: Int, jy: Int, cx: Int, cy: Int, tapX: Int = -1, tapY: Int = -1, holdPrimary: Boolean = false, jumpTap: Boolean = false, note: String) {
            skillSteps.add(
                SkillStep(
                    offsetMs = offset,
                    durationMs = dur,
                    joyXPercent = jx,
                    joyYPercent = jy,
                    camDxPercent = cx,
                    camDyPercent = cy,
                    tapNormX = tapX,
                    tapNormY = tapY,
                    holdPrimaryButton = holdPrimary,
                    tapSecondaryJump = jumpTap,
                    note = note
                )
            )
            offset += dur
        }

        when {
            joined.contains("bridge") || joined.contains("build") || joined.contains("scaffold") -> {
                push(450L, 0, 75, 0, 38, holdPrimary = false, jumpTap = false, note = "Tilt camera down & walk backward to block edge")
                repeat(4) { i ->
                    push(380L, if (i % 2 == 0) 28 else -28, 72, 0, 0, tapX = 820, tapY = 680, holdPrimary = true, jumpTap = false, note = "Rhythmic backward strafe + place block #${i + 1}")
                }
            }
            joined.contains("mine") || joined.contains("diamond") || joined.contains("gold") || joined.contains("ore") || joined.contains("wood") || joined.contains("farm") -> {
                push(550L, 0, -85, 22, 8, note = "Scan & advance toward resource vein")
                push(1400L, 0, -30, 0, 6, tapX = 760, tapY = 560, holdPrimary = true, note = "Hold primary mine/harvest action while centered")
                push(500L, 0, -80, -24, 0, jumpTap = true, note = "Collect drop & scan next node")
            }
            joined.contains("pvp") || joined.contains("combo") || joined.contains("fight") || joined.contains("combat") || joined.contains("hit") -> {
                push(420L, -55, -90, 20, 0, tapX = 760, tapY = 560, jumpTap = true, note = "Sprint-jump left orbit engage + strike")
                push(420L, 55, -75, -20, 0, tapX = 760, tapY = 560, jumpTap = false, note = "Counter-strafe right + camera lock + strike")
                push(380L, 0, 35, 0, 0, tapX = 760, tapY = 560, jumpTap = false, note = "Spacing reset micro-backstep + strike")
            }
            else -> {
                push(500L, 0, -80, 0, 0, jumpTap = joined.contains("jump"), note = "Advance smoothly toward objective")
                push(450L, 0, 0, 32, 0, note = "Smooth camera verification sweep")
                push(500L, 0, -75, -16, 0, tapX = if (joined.contains("attack") || joined.contains("click")) 760 else -1, tapY = 560, note = "Execute primary method action")
            }
        }

        val cleanName = "YT Method: " + topic.take(26).trim()
        val srcLabel = ytList.firstOrNull()?.title?.take(48) ?: "YouTube + Web Strategy Synthesis"
        val skill = LearnedVideoSkill(
            id = UUID.randomUUID().toString().take(8),
            name = cleanName,
            sourceFileName = srcLabel,
            durationMs = offset,
            sampledFrames = skillSteps.size,
            deepSummary = "Compiled from ${ytList.size} YouTube tutorial(s) & web guides: " + steps.take(3).joinToString(" | "),
            detectedLabels = steps.take(5),
            steps = skillSteps,
            createdAtIso = Instant.now().toString()
        )
        FableVideoSkillLearner.persistLearnedSkill(context, skill)
        skill
    }.getOrNull()

    private fun httpGetFast(urlStr: String, ua: String, timeoutMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connect()
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var readTotal = 0
                    while (readTotal < 750_000) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                        readTotal += n
                    }
                    sb.toString()
                }
            } else ""
        } finally {
            conn.disconnect()
        }
    }

    private fun unescapeJsonString(raw: String): String {
        return raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", " ")
            .replace("\\\\", "\\")
    }
}
