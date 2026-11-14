package com.fable.browser.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.fable.browser.data.prefs.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class BatteryThermalSnapshot(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val thermalStatus: Int = 0,
    val rustCoreActive: Boolean = RustFastCore.isRustAvailable
) {
    val thermalLabel: String
        get() = when (thermalStatus) {
            0 -> "Cool (None)"
            1 -> "Warm (Light)"
            2 -> "Hot (Moderate)"
            3 -> "Throttling (Severe)"
            else -> "Critical"
        }
}

object FableBatteryGovernor {
    private val initialized = AtomicBoolean(false)
    private val lastInputUptimeMs = AtomicLong(SystemClock.uptimeMillis())
    private var appContext: Context? = null

    private val _snapshot = MutableStateFlow(BatteryThermalSnapshot())
    val snapshot: StateFlow<BatteryThermalSnapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!initialized.compareAndSet(false, true)) return
        refreshBatteryState(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
                val cur = pm.currentThermalStatus
                _snapshot.value = _snapshot.value.copy(
                    thermalStatus = cur,
                    rustCoreActive = RustFastCore.isRustAvailable
                )
                pm.addThermalStatusListener { status ->
                    _snapshot.value = _snapshot.value.copy(
                        thermalStatus = status,
                        rustCoreActive = RustFastCore.isRustAvailable
                    )
                }
            }
        }
    }

    fun onUserInteraction() {
        lastInputUptimeMs.set(SystemClock.uptimeMillis())
    }

    fun idleDurationMs(): Long {
        return (SystemClock.uptimeMillis() - lastInputUptimeMs.get()).coerceAtLeast(0L)
    }

    fun refreshBatteryState(context: Context? = appContext) {
        val ctx = context ?: return
        runCatching {
            val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) ((level * 100) / scale).coerceIn(1, 100) else 100
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val sysSaver = (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
            val cur = _snapshot.value
            val effectivePct = if (sysSaver && pct > 20) 15 else pct
            if (cur.batteryPercent != effectivePct || cur.isCharging != charging) {
                _snapshot.value = cur.copy(
                    batteryPercent = effectivePct,
                    isCharging = charging,
                    rustCoreActive = RustFastCore.isRustAvailable
                )
            }
        }
    }

    fun evaluatePlan(settings: Settings, isPureGame: Boolean): PowerPlan {
        refreshBatteryState()
        val snap = _snapshot.value
        return RustFastCore.computePowerPlan(
            settings = settings,
            batteryPct = snap.batteryPercent,
            isCharging = snap.isCharging,
            thermalStatus = snap.thermalStatus,
            isGame = isPureGame,
            idleMs = idleDurationMs()
        )
    }
}
