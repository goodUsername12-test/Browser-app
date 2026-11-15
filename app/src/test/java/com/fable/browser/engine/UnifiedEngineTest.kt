package com.fable.browser.engine

import com.fable.browser.agent.AgentActionStep
import com.fable.browser.agent.AgentMode
import com.fable.browser.agent.AgentRiskZone
import com.fable.browser.agent.FableNeuralBrain
import com.fable.browser.agent.RiskZonePolicy
import com.fable.browser.data.model.DefaultBrowserEngine
import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `neural brain plans gaming cow approach player follow and combat with risk zones`() {
        val cowPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "go to the nearest cow you see on the screen",
            currentMode = AgentMode.BASIC_BROWSING,
            currentPageUrl = "https://bloxd.io/play/classic",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        assertEquals(AgentMode.GAMING_SCREEN_ONLY, cowPlan.suggestedMode)
        val cowStep = cowPlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("cow", cowStep.targetLabel)
        assertFalse(cowStep.combatMode)

        val followPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "follow the player OraOraRush and then stop doing it in 10 seconds",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val followStep = followPlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("OraOraRush", followStep.targetLabel)
        assertEquals(10, followStep.durationSec)

        val fightPlan = FableNeuralBrain.buildDeterministicSmartPlan(
            text = "fight with player Killiboi and make sure you hitting him and moving fast and turning camera",
            currentMode = AgentMode.GAMING_SCREEN_ONLY,
            currentPageUrl = "https://bloxd.io/play/1v1_simulator",
            lastSearchHits = emptyList(),
            learnedSkills = emptyList(),
            screenObs = null
        )
        val fightStep = fightPlan.steps.single() as AgentActionStep.GamingTrackTarget
        assertEquals("Killiboi", fightStep.targetLabel)
        assertTrue(fightStep.combatMode)
        assertEquals(AgentRiskZone.COMPETITIVE_ONLINE, fightPlan.riskZone)
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
    fun `rust agent control vector steers joystick camera and attack deterministically`() {
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

        val centeredCloseCombat = RustFastCore.computeAgentControlVector(
            targetXPermille = 510,
            targetYPermille = 485,
            coveragePermille = 60,
            confidence = 90,
            combatMode = true
        )
        assertTrue(centeredCloseCombat.shouldAttack)
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
}
