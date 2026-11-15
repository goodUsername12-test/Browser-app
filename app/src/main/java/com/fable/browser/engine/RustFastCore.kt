package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings

data class AgentControlVector(
    val joyXPercent: Int,
    val joyYPercent: Int,
    val camDxPercent: Int,
    val camDyPercent: Int,
    val shouldAttack: Boolean,
    val shouldHoldPrimary: Boolean = false,
    val shouldJump: Boolean = false
)

data class NetworkPlan(
    val qualityScore: Int,
    val parallelStreams: Int,
    val socketBufferKb: Int,
    val uploadChunkKb: Int
)

data class PowerPlan(
    val targetDisplayHz: Int,
    val targetGameFps: Int,
    val renderScalePercent: Int,
    val cpuBoostMode: Int,
    val choreographerActive: Boolean,
    val batterySaverActive: Boolean
)

object RustFastCore {
    val isRustAvailable: Boolean = runCatching {
        System.loadLibrary("fable_rust")
        true
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativeRustVersion(): Int

    @JvmStatic
    private external fun nativeRecommendNetworkPlan(
        rssiDbm: Int,
        linkMbps: Int,
        downKbps: Int,
        upKbps: Int,
        freqMhz: Int,
        isWifi: Boolean,
        isMetered: Boolean,
        fastConn: Boolean,
        fileSizeMb: Int
    ): Long

    fun recommendNetworkPlan(
        rssiDbm: Int,
        linkMbps: Int,
        downKbps: Int,
        upKbps: Int,
        freqMhz: Int,
        isWifi: Boolean,
        isMetered: Boolean,
        fastConn: Boolean,
        fileSizeMb: Int
    ): NetworkPlan {
        val packed = if (isRustAvailable) {
            runCatching {
                nativeRecommendNetworkPlan(rssiDbm, linkMbps, downKbps, upKbps, freqMhz, isWifi, isMetered, fastConn, fileSizeMb)
            }.getOrNull()
        } else {
            null
        }
        if (packed != null) {
            return NetworkPlan(
                qualityScore = (packed and 0xFFL).toInt().coerceIn(5, 100),
                parallelStreams = ((packed ushr 8) and 0xFFL).toInt().coerceIn(1, 6),
                socketBufferKb = ((packed ushr 16) and 0xFFFFL).toInt().coerceIn(64, 512),
                uploadChunkKb = ((packed ushr 32) and 0xFFFFL).toInt().coerceIn(128, 2048)
            )
        }
        val baseScore = if (isWifi) {
            val rssiScore = ((rssiDbm + 95) * 2).coerceIn(10, 70)
            val bandBonus = if (freqMhz >= 5900) 20 else if (freqMhz >= 4900) 15 else 5
            val speedBonus = (linkMbps / 40).coerceIn(0, 15)
            (rssiScore + bandBonus + speedBonus).coerceIn(5, 100)
        } else {
            (35 + (downKbps / 1000).coerceIn(0, 55)).coerceIn(15, 90)
        }.let { if (isMetered && it > 75) 75 else it }

        val streams = when {
            !fastConn || fileSizeMb < 2 || isMetered -> 1
            baseScore >= 85 && fileSizeMb >= 10 -> 6
            baseScore >= 70 && fileSizeMb >= 4 -> 4
            baseScore >= 50 -> 2
            else -> 1
        }
        val bufKb = if (baseScore >= 80) 512 else if (baseScore >= 55) 256 else 128
        val upMbps = maxOf(upKbps / 1000, linkMbps / 2)
        val upChunk = when {
            upMbps >= 80 && baseScore >= 80 -> 2048
            upMbps >= 30 && baseScore >= 60 -> 1024
            upMbps >= 10 -> 512
            else -> 256
        }
        return NetworkPlan(baseScore, streams, bufKb, upChunk)
    }

    @JvmStatic
    private external fun nativeComputeControlVector(
        targetXPermille: Int,
        targetYPermille: Int,
        coveragePermille: Int,
        confidence: Int,
        combatMode: Boolean
    ): Long

    fun computeAgentControlVector(
        targetXPermille: Int,
        targetYPermille: Int,
        coveragePermille: Int,
        confidence: Int,
        combatMode: Boolean
    ): AgentControlVector {
        if (confidence < 15) return AgentControlVector(0, 0, 0, 0, false)
        val packed = if (isRustAvailable) {
            runCatching {
                nativeComputeControlVector(targetXPermille, targetYPermille, coveragePermille, confidence, combatMode)
            }.getOrNull()
        } else null
        if (packed != null && packed != 0L) {
            val jx = ((packed and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val jy = (((packed ushr 8) and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val cx = (((packed ushr 16) and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val cy = (((packed ushr 24) and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val atk = ((packed ushr 32) and 0x01L) == 1L
            return AgentControlVector(jx, jy, cx, cy, atk)
        }
        val tx = targetXPermille.coerceIn(0, 1000)
        val ty = targetYPermille.coerceIn(0, 1000)
        val cov = coveragePermille.coerceIn(0, 1000)
        val errX = tx - 500
        val errY = ty - 480
        val camDx = ((errX * 22) / 100).coerceIn(-100, 100)
        val camDy = ((errY * 14) / 100).coerceIn(-65, 65)
        val stopCov = if (combatMode) 85 else 110
        val joyY = when {
            cov < stopCov -> -(stopCov - cov).coerceIn(20, 100)
            combatMode && cov > 190 -> 45
            else -> 0
        }
        val joyX = if (combatMode && kotlin.math.abs(errX) < 160) {
            if ((tx and 1) == 0) 38 else -38
        } else {
            (errX / 8).coerceIn(-45, 45)
        }
        val atk = combatMode && kotlin.math.abs(errX) <= 115 && kotlin.math.abs(errY) <= 125 && cov >= 18
        return AgentControlVector(joyX, joyY, camDx, camDy, atk)
    }

    @JvmStatic
    private external fun nativeComputeControlVectorV2(
        targetXPermille: Int,
        targetYPermille: Int,
        prevXPermille: Int,
        prevYPermille: Int,
        coveragePermille: Int,
        confidence: Int,
        modeCode: Int,
        strafePhase: Int,
        camSensitivityPct: Int
    ): Long

    /**
     * modeCode: 0 = APPROACH, 1 = FOLLOW, 2 = COMBAT, 3 = MINE_HARVEST
     */
    fun computeAgentControlVectorV2(
        targetXPermille: Int,
        targetYPermille: Int,
        prevXPermille: Int = -1,
        prevYPermille: Int = -1,
        coveragePermille: Int,
        confidence: Int,
        modeCode: Int,
        strafePhase: Int = 0,
        camSensitivityPct: Int = 100
    ): AgentControlVector {
        if (confidence < 15) return AgentControlVector(0, 0, 0, 0, false, false, false)
        val packed = if (isRustAvailable) {
            runCatching {
                nativeComputeControlVectorV2(
                    targetXPermille,
                    targetYPermille,
                    prevXPermille,
                    prevYPermille,
                    coveragePermille,
                    confidence,
                    modeCode,
                    strafePhase,
                    camSensitivityPct
                )
            }.getOrNull()
        } else null
        if (packed != null && packed != 0L) {
            val jx = ((packed and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val jy = (((packed ushr 8) and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val cx = (((packed ushr 16) and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val cy = (((packed ushr 24) and 0xFFL).toInt() - 128).coerceIn(-100, 100)
            val atk = ((packed ushr 32) and 0x01L) == 1L
            val holdPrim = ((packed ushr 33) and 0x01L) == 1L
            val jump = ((packed ushr 34) and 0x01L) == 1L
            return AgentControlVector(jx, jy, cx, cy, atk, holdPrim, jump)
        }

        val tx = targetXPermille.coerceIn(0, 1000)
        val ty = targetYPermille.coerceIn(0, 1000)
        val px = if (prevXPermille < 0) tx else prevXPermille.coerceIn(0, 1000)
        val py = if (prevYPermille < 0) ty else prevYPermille.coerceIn(0, 1000)
        val cov = coveragePermille.coerceIn(0, 1000)
        val sens = camSensitivityPct.coerceIn(40, 220)

        val vx = (tx - px).coerceIn(-180, 180)
        val vy = (ty - py).coerceIn(-140, 140)
        val leadX = (tx + (vx * 55) / 100).coerceIn(0, 1000)
        val leadY = (ty + (vy * 35) / 100).coerceIn(0, 1000)

        val rawErrX = leadX - 500
        val rawErrY = leadY - 480
        val errX = if (kotlin.math.abs(rawErrX) <= 14) 0 else rawErrX
        val errY = if (kotlin.math.abs(rawErrY) <= 16) 0 else rawErrY

        val camDx = ((errX * 24 * sens) / 10000).coerceIn(-100, 100)
        val camDy = ((errY * 15 * sens) / 10000).coerceIn(-70, 70)

        return when (modeCode) {
            1 -> {
                val jy = when {
                    cov < 88 -> -(105 - cov).coerceIn(25, 95)
                    cov > 168 -> 42
                    else -> 0
                }
                val jx = (errX / 9).coerceIn(-40, 40)
                val jump = jy < -70 && (strafePhase % 14 == 0)
                AgentControlVector(jx, jy, camDx, camDy, false, false, jump)
            }
            2 -> {
                val jy = when {
                    cov < 80 -> -92
                    cov > 195 -> 48
                    else -> -35
                }
                val orbitDir = if (((strafePhase / 6) and 1) == 0) 1 else -1
                val jx = if (kotlin.math.abs(errX) < 175) orbitDir * 56 else (errX / 6).coerceIn(-65, 65)
                val aligned = kotlin.math.abs(errX) <= 120 && kotlin.math.abs(errY) <= 135 && cov >= 16
                val jump = cov in 30..145 && (strafePhase % 9 == 0)
                AgentControlVector(jx, jy, camDx, camDy, aligned, false, jump)
            }
            3 -> {
                val jy = if (cov < 118) -(125 - cov).coerceIn(24, 85) else 0
                val jx = (errX / 10).coerceIn(-35, 35)
                val hold = kotlin.math.abs(errX) <= 110 && kotlin.math.abs(errY) <= 130 && cov >= 45
                AgentControlVector(jx, jy, camDx, camDy, false, hold, false)
            }
            else -> {
                val jy = if (cov < 115) -(120 - cov).coerceIn(22, 92) else 0
                val jx = (errX / 8).coerceIn(-45, 45)
                AgentControlVector(jx, jy, camDx, camDy, false, false, false)
            }
        }
    }

    @JvmStatic
    private external fun nativeComputePowerPlan(
        batteryPct: Int,
        isCharging: Boolean,
        thermalStatus: Int,
        masterSaver: Boolean,
        autoLowPct: Int,
        thermalGuard: Boolean,
        adaptiveHz: Boolean,
        gameMaxFps: Int,
        gameRenderScalePct: Int,
        ecoCpuCores: Boolean,
        idleGameFps: Int,
        idleTimeoutSec: Int,
        isGame: Boolean,
        idleMs: Long,
        highRefreshPref: Boolean
    ): Long

    fun rustVersion(): Int {
        if (isRustAvailable) {
            runCatching { return nativeRustVersion() }
        }
        return 0
    }

    fun computePowerPlan(
        settings: Settings,
        batteryPct: Int,
        isCharging: Boolean,
        thermalStatus: Int,
        isGame: Boolean,
        idleMs: Long
    ): PowerPlan {
        val packed = if (isRustAvailable) {
            runCatching {
                nativeComputePowerPlan(
                    batteryPct = batteryPct,
                    isCharging = isCharging,
                    thermalStatus = thermalStatus,
                    masterSaver = settings.batterySaverMaster,
                    autoLowPct = settings.batteryAutoLowPct,
                    thermalGuard = settings.batteryThermalGuard,
                    adaptiveHz = settings.batteryAdaptiveRefresh,
                    gameMaxFps = settings.batteryGameMaxFps,
                    gameRenderScalePct = settings.batteryGameRenderScale,
                    ecoCpuCores = settings.batteryEcoCpu,
                    idleGameFps = settings.batteryIdleGameFps,
                    idleTimeoutSec = settings.batteryIdleTimeoutSec,
                    isGame = isGame,
                    idleMs = idleMs,
                    highRefreshPref = settings.highRefreshRate
                )
            }.getOrNull()
        } else {
            null
        } ?: computeFallbackPacked(
            settings = settings,
            batteryPct = batteryPct,
            isCharging = isCharging,
            thermalStatus = thermalStatus,
            isGame = isGame,
            idleMs = idleMs
        )

        val targetHz = (packed and 0xFFL).toInt().coerceIn(30, 120)
        val targetGameFps = ((packed ushr 8) and 0xFFL).toInt().coerceIn(15, 120)
        val renderScale = ((packed ushr 16) and 0xFFL).toInt().coerceIn(50, 100)
        val cpuMode = ((packed ushr 24) and 0x0FL).toInt().coerceIn(0, 2)
        val choreo = ((packed ushr 28) and 0x01L) == 1L
        val saverActive = ((packed ushr 29) and 0x01L) == 1L

        return PowerPlan(
            targetDisplayHz = targetHz,
            targetGameFps = targetGameFps,
            renderScalePercent = renderScale,
            cpuBoostMode = cpuMode,
            choreographerActive = choreo,
            batterySaverActive = saverActive
        )
    }

    private fun computeFallbackPacked(
        settings: Settings,
        batteryPct: Int,
        isCharging: Boolean,
        thermalStatus: Int,
        isGame: Boolean,
        idleMs: Long
    ): Long {
        val lowTriggered = !isCharging && settings.batteryAutoLowPct > 0 && batteryPct in 1..settings.batteryAutoLowPct
        val saverActive = settings.batterySaverMaster || lowTriggered
        val hotModerate = settings.batteryThermalGuard && thermalStatus >= 2
        val hotSevere = settings.batteryThermalGuard && thermalStatus >= 3
        val isIdle = settings.batteryIdleGameFps > 0 && idleMs >= settings.batteryIdleTimeoutSec.coerceIn(3, 120) * 1000L

        var targetHz = when {
            !settings.highRefreshRate -> 60
            isGame -> settings.batteryGameMaxFps.coerceIn(30, 120)
            saverActive || hotModerate -> 60
            settings.batteryAdaptiveRefresh && idleMs >= 2500L -> 60
            else -> 120
        }
        if (hotSevere && targetHz > 60) targetHz = 60
        else if (hotModerate && targetHz > 90) targetHz = 90

        var targetGameFps = if (isGame) {
            var cap = settings.batteryGameMaxFps.coerceIn(15, 120)
            if (saverActive && settings.batterySaverMaster && settings.batteryGameMaxFps >= 120 && cap > 90) {
                cap = 90
            }
            if (hotSevere && cap > 60) cap = 60
            else if (hotModerate && cap > 90) cap = 90
            if (settings.batteryIdleGameFps > 0 && isIdle && settings.batteryIdleGameFps < cap) {
                cap = settings.batteryIdleGameFps.coerceIn(15, 60)
                if (targetHz > 60 && cap <= 60) targetHz = 60
            }
            cap
        } else if (saverActive) {
            60
        } else {
            120
        }
        targetGameFps = targetGameFps.coerceIn(15, 120)

        var renderScale = if (isGame) {
            var sc = settings.batteryGameRenderScale.coerceIn(50, 100)
            if (hotSevere && sc > 75) sc = 75
            else if (lowTriggered && sc == 100) sc = 85
            sc
        } else {
            100
        }
        renderScale = renderScale.coerceIn(50, 100)

        val cpuMode = when {
            !isGame -> 0
            settings.batteryEcoCpu || saverActive || hotSevere || isIdle -> 0
            hotModerate -> 1
            else -> 2
        }
        val choreo = if (isGame && !isIdle && !saverActive && !hotSevere && cpuMode == 2) 1L else 0L
        val saverBit = if (saverActive) 1L else 0L

        return (targetHz.toLong() and 0xFFL) or
            ((targetGameFps.toLong() and 0xFFL) shl 8) or
            ((renderScale.toLong() and 0xFFL) shl 16) or
            ((cpuMode.toLong() and 0x0FL) shl 24) or
            (choreo shl 28) or
            (saverBit shl 29)
    }
}
