package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings

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
        val isIdle = idleMs >= settings.batteryIdleTimeoutSec.coerceIn(3, 120) * 1000L

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
