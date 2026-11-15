package com.fable.browser.engine

import com.fable.browser.agent.AgentActionStep
import com.fable.browser.agent.AgentMode
import com.fable.browser.agent.AgentRiskZone
import com.fable.browser.agent.FableLocalLlmRuntime
import com.fable.browser.agent.FableNeuralBrain
import com.fable.browser.agent.FableOwnerAuthManager
import com.fable.browser.agent.GamingTrackingStyle
import com.fable.browser.agent.RiskZonePolicy
import com.fable.browser.data.model.DefaultBrowserEngine
import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedEngineTest {

    @Test
    fun `default settings have zero unexpected afk or low-battery throttling`() {
        val defaults = Settings()
        assertEquals(0, defaults.batteryIdleGameFps)
        assertEquals(0, defaults.batteryAutoLowPct)
        assertEquals(120, defaults.batteryGameMaxFps)
        assertEquals(100, defaults.batteryGameRenderScale)
        assertEquals(DefaultBrowserEngine.BLINK_GECKO_AUTO, defaults.defaultEngine)
        assertEquals(60, defaults.agentReactionIntervalMs)
        assertEquals(100, defaults.agentCameraSensitivityPct)
        assertEquals(30, defaults.ownerAutoLockMinutes)
        assertEquals(RiskZonePolicy.ALLOWED, defaults.policyForZone(AgentRiskZone.PRIVATE_WORLDS))
        assertEquals(RiskZonePolicy.ASK_EVERY_TIME, defaults.policyForZone(AgentRiskZone.IMPORTANT_SENSITIVE))
    }

    @Test
    fun `neural brain plans copy text and zip workflow accurately`() {
        val plan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "find go to https://example.com and copy the page text and deliver whole page code as in zip file",
            currentMode = AgentMode.BASIC_BROWSING,
            currentPageUrl = "about:blank",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps[0] is AgentActionStep.OpenUrl)
        assertEquals("https://example.com", (plan.steps[0] as AgentActionStep.OpenUrl).url)
        assertTrue(plan.steps[1] is AgentActionStep.CopyPageTextAndZip)
        assertTrue((plan.steps[1] as AgentActionStep.CopyPageTextAndZip).includeZip)
    }

    @Test
    fun `neural brain plans gaming cow approach player follow mine and combat with typos`() {
        val cowPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "go to the nerest cow you see on the screen",
            currentMode = AgentMode.BASIC_BROWSING,
            currentPageUrl = "https://bloxd.io/play/classic",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        assertEquals(AgentMode.GAMING_SCREEN_ONLY, cowPlan.suggestedMode)
        val cowStep = cowPlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("cow", cowStep.targetLabel)
        assertEquals(GamingTrackingStyle.APPROACH, cowStep.trackingStyle)
        assertFalse(cowStep.combatMode)

        val followPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "folow the player OraOraRush and then stop doing it in 10 seconds",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val followStep = followPlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("OraOraRush", followStep.targetLabel)
        assertEquals(GamingTrackingStyle.FOLLOW, followStep.trackingStyle)
        assertEquals(10, followStep.durationSec)

        val minePlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "mine nearest diamond for 12 seconds",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/play/peaceful",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val mineStep = minePlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("diamond", mineStep.targetLabel)
        assertEquals(GamingTrackingStyle.MINE_HARVEST, mineStep.trackingStyle)

        val fightPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "fight with player Killiboi and make sure you hitting him and moving fast and turning cmera",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/play/1v1_simulator",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val fightStep = fightPlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("Killiboi", fightStep.targetLabel)
        assertTrue(fightStep.combatMode)
        assertEquals(GamingTrackingStyle.COMBAT, fightStep.trackingStyle)
        assertEquals(AgentRiskZone.COMPETITIVE_ONLINE, fightPlan.riskZone)
    }

    @Test
    fun `neural brain plans typing camera turns button holds and youtube strategy learning`() {
        val typePlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "in search type WebGPU browser benchmarks and press enter",
            currentMode = AgentMode.BASIC_BROWSING,
            currentPageUrl = "https://google.com",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val typeStep = typePlan.steps.single() as AgentActionStep.TypeText
        assertEquals("search", typeStep.fieldHint)
        assertEquals("WebGPU browser benchmarks", typeStep.textToType)
        assertTrue(typeStep.pressEnter)

        val camPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "turn cmera right 180",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val camStep = camPlan.steps.single() as AgentActionStep.GamingMoveCamera
        assertEquals(180, camStep.yawPercent)

        val holdPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "hold forward and hold button for 4 seconds and jump",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val holdStep = holdPlan.steps.single() as AgentActionStep.GamingHoldDirection
        assertTrue(holdStep.joyY < -50)
        assertTrue(holdStep.holdPrimaryButton)
        assertTrue(holdStep.jumpTap)
        assertEquals(4000L, holdStep.durationMs)

        val ytPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "find best method on yt to bridge fast in bloxd and do the same",
            currentMode = AgentMode.BASIC_BROWSING,
            currentPageUrl = "https://bloxd.io/",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        assertEquals(AgentMode.GAMING_SCREEN_ONLY, ytPlan.suggestedMode)
        val ytStep = ytPlan.steps.single() as AgentActionStep.ResearchBestMethod
        assertTrue(ytStep.autoExecuteCompiledSkill)
    }

    @Test
    fun `owner research mode enforces non-bypassable read-only and screen-only guards`() {
        assertFalse(FableOwnerAuthManager.canInjectIntoPage())
        assertFalse(FableOwnerAuthManager.canBypassScreenOnlyGaming())
        assertFalse(FableOwnerAuthManager.canDisableEmergencyStop())
        assertTrue(FableOwnerAuthManager.requiresIrreversibleActionConfirmation())

        // Verify Gaming Mode firewall blocks URL/DOM/ZIP actions even if LLM JSON requests them
        val blockedGamingPlan = FableNeuralBrain.parseModelJsonIntoPlan(
            rawModelText = """{"reply":"Opening page","action":"copy_and_zip","target":"https://example.com"}""",
            userText = "archive page",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/",
            modelBadge = "Qwen3-0.6B"
        )
        assertNotNull(blockedGamingPlan)
        assertTrue(blockedGamingPlan!!.steps.isEmpty())

        val stripped = FableLocalLlmRuntime.splitThinkingAndCleanOutput(
            "<think>\ninternal reasoning\n</think>\n{\"reply\":\"Done\",\"action\":\"reply\"}<|im_end|>"
        )
        assertEquals("{\"reply\":\"Done\",\"action\":\"reply\"}", stripped.first)
        assertEquals("internal reasoning", stripped.second)
    }

    @Test
    fun `neural brain plans long task multi-section monitoring`() {
        val monitorPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "go to https://example.com, then open analytics, members, upgrade sections and check them for 5 minutes, if not changes stop",
            currentMode = AgentMode.BASIC_BROWSING,
            currentPageUrl = "about:blank",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        assertEquals(AgentMode.LONG_TASK, monitorPlan.suggestedMode)
        val step = monitorPlan.steps.single() as AgentActionStep.LongTaskMonitorSections
        assertEquals("https://example.com", step.startUrl)
        assertEquals(300, step.durationSec)
        assertTrue(step.stopIfNoChange)
        assertTrue(step.sectionLabels.containsAll(listOf("Analytics", "Members", "Upgrade")))
    }

    @Test
    fun `rust agent control vector v1 and v2 steer joystick camera hold and attack deterministically`() {
        val rightFar = RustFastCore.computeAgentControlVector(
            targetXPermille = 750,
            targetYPermille = 480,
            coveragePermille = 35,
            confidence = 85,
            combatMode = false
        )
        assertTrue(rightFar.camDxPercent > 20)
        assertTrue(rightFar.joyYPercent < -20)
        assertFalse(rightFar.shouldAttack)

        val centeredCloseCombat = RustFastCore.computeAgentControlVectorV2(
            targetXPermille = 510,
            targetYPermille = 485,
            prevXPermille = 490,
            prevYPermille = 485,
            coveragePermille = 60,
            confidence = 90,
            modeCode = GamingTrackingStyle.COMBAT.code,
            strafePhase = 2,
            camSensitivityPct = 100
        )
        assertTrue(centeredCloseCombat.shouldAttack)

        val harvestHold = RustFastCore.computeAgentControlVectorV2(
            targetXPermille = 505,
            targetYPermille = 480,
            prevXPermille = 505,
            prevYPermille = 480,
            coveragePermille = 95,
            confidence = 88,
            modeCode = GamingTrackingStyle.MINE_HARVEST.code,
            strafePhase = 1,
            camSensitivityPct = 100
        )
        assertTrue(harvestHold.shouldHoldPrimary)
    }

    @Test
    fun `bootstrap script keeps 2d hud canvases composited for bloxd fps and retains revoked blobs`() {
        val boot = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = Settings(),
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://bloxd.io/play/1v1_simulator"
        )
        assertFalse(boot.contains("desynchronized: true"))
        assertTrue(boot.contains("__fableRect"))
        assertTrue(boot.contains("blobRegistry"))
        assertTrue(boot.contains("beginJsDownload"))
    }

    @Test
    fun `same-site registrable domain matches auth and api subdomains for bloxd`() {
        assertTrue(UrlUtils.isSameSite("auth.bloxd.io", "bloxd.io"))
        assertTrue(UrlUtils.isSameSite("api.bloxd.io", "staging.bloxd.io"))
        assertTrue(UrlUtils.isSameSite("bloxd.io", "www.bloxd.io"))
        assertFalse(UrlUtils.isSameSite("doubleclick.net", "bloxd.io"))
    }

    @Test
    fun `v46 webgpu vulkan hyper-core script includes uid cache 3-tier cascade turnstile guard and polyfills`() {
        val boot = HybridEngineBridge.buildCombinedBootstrapScript(
            settings = Settings(),
            isIncognito = false,
            isSuper = false,
            superIdentity = null,
            pageUrl = "https://bloxd.io/play/classic"
        )
        assertTrue(boot.contains("challenges") && boot.contains("cloudflare"))
        assertTrue(boot.contains("__fableNativeToStringMap"))
        assertTrue(boot.contains("requestAdapterInfo"))
        assertTrue(boot.contains("shader-f16"))
        assertTrue(boot.contains("texture-compression-astc"))
        assertTrue(boot.contains("__fableGpuUid"))
        assertTrue(boot.contains("KHR_parallel_shader_compile"))
        assertTrue(boot.contains("bindVertexArray"))
        assertFalse(boot.contains("desynchronized: true"))
    }

    @Test
    fun `v46 vulkan hardware json parser extracts extensions and core counts`() {
        val parsed = FableWebGpuManager.parseVulkanHardwareJson(
            """{"available":true,"deviceName":"Adreno (TM) 740","apiVersion":"1.3.277","maxImageDimension2D":16384,"maxStorageBufferMb":512,"maxComputeSharedKb":32,"maxDescriptorSets":8,"extFp16Int8":true,"extDescriptorIndexing":true,"extBufferDeviceAddress":true,"extRobustness2":true,"bigCores":4,"totalCores":8}"""
        )
        assertTrue(parsed.available)
        assertEquals("Adreno (TM) 740", parsed.deviceName)
        assertEquals("1.3.277", parsed.apiVersion)
        assertEquals(16384, parsed.maxImageDimension2D)
        assertTrue(parsed.extFp16Int8)
        assertTrue(parsed.extRobustness2)
        assertEquals(4, parsed.bigCores)
    }

    @Test
    fun `v46 upload mime normalizer prevents greyed-out supported files`() {
        val customExt = com.fable.browser.util.UploadFiles.normalizeMimeTypes(arrayOf(".gguf"))
        assertEquals("*/*", customExt.single())

        val docAndVideo = com.fable.browser.util.UploadFiles.normalizeMimeTypes(arrayOf(".mp4,.json,.zip"))
        assertTrue(docAndVideo.contains("video/mp4"))
        assertTrue(docAndVideo.contains("application/json"))
        assertTrue(docAndVideo.contains("application/zip"))
        assertTrue(docAndVideo.contains("application/octet-stream"))
    }
}
