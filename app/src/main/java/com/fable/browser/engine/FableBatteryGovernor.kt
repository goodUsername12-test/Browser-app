package com.fable.browser.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
    private var bgHandler: Handler? = null

    private val _snapshot = MutableStateFlow(BatteryThermalSnapshot())
    val snapshot: StateFlow<BatteryThermalSnapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        val thread = HandlerThread("FableBatteryWatch", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val handler = Handler(thread.looper)
        bgHandler = handler

        handler.post {
            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent == null) return
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val pct = if (level >= 0 && scale > 0) ((level * 100) / scale).coerceIn(1, 100) else 100
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                        val cur = _snapshot.value
                        if (cur.batteryPercent != pct || cur.isCharging != charging) {
                            _snapshot.value = cur.copy(
                                batteryPercent = pct,
                                isCharging = charging,
                                rustCoreActive = RustFastCore.isRustAvailable
                            )
                        }
                    }
                }
                val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler)
                if (sticky != null) {
                    receiver.onReceive(ctx, sticky)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
                    _snapshot.value = _snapshot.value.copy(
                        thermalStatus = pm.currentThermalStatus,
                        rustCoreActive = RustFastCore.isRustAvailable
                    )
                    pm.addThermalStatusListener({ cmd -> handler.post(cmd) }) { status ->
                        _snapshot.value = _snapshot.value.copy(
                            thermalStatus = status,
                            rustCoreActive = RustFastCore.isRustAvailable
                        )
                    }
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

    fun refreshBatteryState(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        // State is updated asynchronously via background BroadcastReceiver — zero main-thread IPC.
    }

    fun evaluatePlan(settings: Settings, isPureGame: Boolean): PowerPlan {
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
