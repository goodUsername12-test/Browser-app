package com.fable.browser.agent

import android.graphics.Rect

enum class AgentMode(val label: String, val shortLabel: String, val summary: String) {
    BASIC_BROWSING(
        label = "Basic Browsing Mode",
        shortLabel = "Browsing",
        summary = "Fast web understanding, live search, navigate, copy page text, and full .zip page code export"
    ),
    GAMING_SCREEN_ONLY(
        label = "Gaming Mode (Strict Screen-Only · 50-100ms)",
        shortLabel = "Gaming",
        summary = "100% pixel & touch only (zero page-code/console access). Fast 30-80ms target tracking, multi-touch joystick, camera & combat"
    ),
    LONG_TASK(
        label = "Long Task Mode",
        shortLabel = "Long Task",
        summary = "Multi-step state machine with adaptive load waiting, multi-tab section monitoring, change detection, and bounded cycles"
    )
}

enum class AgentAutonomyPolicy(val label: String, val summary: String) {
    AUTO_SAFE(
        label = "Auto Safe, Ask Important (Recommended)",
        summary = "Executes safe browsing/movement automatically; always asks before purchases, deletes, account changes, or ask-flagged zones"
    ),
    FULL_AUTO_ALLOWED(
        label = "Automatic in All Allowed Zones",
        summary = "Runs all actions in Allowed risk zones without interruption; still respects Ask/Blocked zones and Emergency Stop"
    ),
    ASK_IMPORTANT(
        label = "Ask Before External / State-Changing Actions",
        summary = "Asks before navigating to new sites, downloading, switching modes, or clicking action buttons"
    ),
    ALWAYS_ASK_EVERYTHING(
        label = "Always Ask Everything",
        summary = "Asks for confirmation before every single tap, navigation, search, or joystick command"
    )
}

enum class RiskZonePolicy(val label: String) {
    ALLOWED("Allowed automatically"),
    ASK_ONCE("Ask once per session"),
    ASK_EVERY_TIME("Ask every time"),
    BLOCKED("Blocked")
}

enum class AgentRiskZone(val title: String, val subtitle: String) {
    ACCESSIBILITY("Accessibility & Voice Control", "Hands-free voice/text navigation and assistive touch control"),
    PRIVATE_WORLDS("Private Worlds", "Automation and movement inside private lobbies or your own worlds"),
    SINGLE_PLAYER("Single-Player Games", "Offline or solo web games"),
    GAME_TESTING("Game Testing & Diagnostics", "Automated QA, stress movement, and FPS testing"),
    ALLOWED_AUTOMATION_GAMES("Games Allowing Automation", "Games/servers that explicitly permit bots or scripts"),
    USER_APPROVED_MACROS("User-Approved Macros & Learned Skills", "Replaying movement/action skills learned from your uploaded videos"),
    COMPETITIVE_ONLINE("Competitive Online Games (Risk Zone)", "PvP combat/target tracking on public multiplayer servers"),
    IMPORTANT_SENSITIVE("Important Actions (Buy, Account, Delete, Upload)", "Payments, upgrades, deleting data, sending messages, or private exports")
}

data class OcrTextBlock(
    val text: String,
    val bounds: Rect,
    val normCenterX: Int,
    val normCenterY: Int
)

data class VisualTargetMatch(
    val found: Boolean,
    val label: String,
    val normX: Int,
    val normY: Int,
    val coveragePermille: Int,
    val confidence: Int,
    val frameDiffPermille: Int,
    val latencyMs: Long
)

data class ScreenObservation(
    val width: Int,
    val height: Int,
    val frameDiffPermille: Int,
    val isVisuallyStable: Boolean,
    val ocrBlocks: List<OcrTextBlock>,
    val fullVisibleText: String,
    val dominantSummary: String,
    val captureLatencyMs: Long
)

data class LearnedVideoSkill(
    val id: String,
    val name: String,
    val sourceFileName: String,
    val durationMs: Long,
    val sampledFrames: Int,
    val deepSummary: String,
    val detectedLabels: List<String>,
    val steps: List<SkillStep>,
    val createdAtIso: String
)

data class SkillStep(
    val offsetMs: Long,
    val durationMs: Long,
    val joyXPercent: Int,
    val joyYPercent: Int,
    val camDxPercent: Int,
    val camDyPercent: Int,
    val tapNormX: Int = -1,
    val tapNormY: Int = -1,
    val note: String = ""
)

data class AgentPendingConfirmation(
    val id: String,
    val title: String,
    val detail: String,
    val zone: AgentRiskZone,
    val allowSessionOption: Boolean = true
)

data class AgentLogEntry(
    val timeLabel: String,
    val role: String,
    val message: String
)

data class AgentLiveState(
    val mode: AgentMode = AgentMode.BASIC_BROWSING,
    val running: Boolean = false,
    val strictScreenOnlyActive: Boolean = false,
    val statusLine: String = "Ready",
    val activeInputsLabel: String = "None",
    val lastReactionMs: Long = 0L,
    val confidencePercent: Int = 100,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val remainingSeconds: Int = 0,
    val pendingConfirmation: AgentPendingConfirmation? = null,
    val logs: List<AgentLogEntry> = emptyList(),
    val learnedSkills: List<LearnedVideoSkill> = emptyList(),
    val freeStorageGb: Long = 0L,
    val installedModels: List<String> = emptyList()
)
