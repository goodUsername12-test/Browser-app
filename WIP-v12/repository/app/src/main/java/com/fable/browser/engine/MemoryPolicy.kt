package com.fable.browser.engine

import android.app.ActivityManager
import android.content.Context
import com.fable.browser.data.model.MemoryMode
import com.fable.browser.data.model.TabUnloadMode
import com.fable.browser.data.prefs.Settings

data class MemoryPolicy(
    val mode: MemoryMode,
    val keepLoaded: Int,
    val idleUnloadMs: Long,
    val hiddenUnloadMs: Long,
    val unloadOnBackgroundTrim: Boolean,
    val memoryCacheKb: Int,
    val backForwardPages: Int,
    val imageCacheKb: Int,
    val thumbnailWidth: Int
) {
    companion object {
        @Volatile private var ramMb: Long = -1
        @Volatile private var lowRamDevice = false

        fun totalRamMb(context: Context): Long {
            if (ramMb > 0) return ramMb
            return runCatching {
                val am = context.getSystemService(ActivityManager::class.java)
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                lowRamDevice = am.isLowRamDevice
                (info.totalMem / (1024L * 1024L)).also { ramMb = it }
            }.getOrDefault(4096L)
        }

        @Volatile private var weak: Boolean? = null

        fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()

        fun maxCpuMhz(): Int {
            val cached = maxMhz
            if (cached >= 0) return cached
            val value = runCatching {
                (0 until cpuCores()).maxOfOrNull { i ->
                    runCatching {
                        java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000L
                    }.getOrDefault(0L)
                }?.toInt() ?: 0
            }.getOrDefault(0)
            maxMhz = value
            return value
        }

        @Volatile private var maxMhz: Int = -1

        fun isWeakDevice(context: Context): Boolean {
            weak?.let { return it }
            val mb = totalRamMb(context)
            val mhz = maxCpuMhz()
            val result = lowRamDevice || mb <= 4608 || cpuCores() <= 4 || mhz in 1..1999
            weak = result
            return result
        }

        fun optimizing(context: Context, s: Settings): Boolean = s.optimizerEnabled && isWeakDevice(context)

        fun autoMode(context: Context): MemoryMode {
            val mb = totalRamMb(context)
            return if (lowRamDevice || mb <= 3584) MemoryMode.LOW else MemoryMode.BALANCED
        }

        fun resolve(context: Context, s: Settings): MemoryPolicy {
            val mode = if (s.memoryMode == MemoryMode.AUTO) {
                if (optimizing(context, s)) MemoryMode.LOW else autoMode(context)
            } else s.memoryMode
            val bigPhone = totalRamMb(context) >= 7168
            return when (mode) {
                MemoryMode.LOW -> MemoryPolicy(
                    mode = mode,
                    keepLoaded = 1,
                    idleUnloadMs = 30_000L,
                    hiddenUnloadMs = 30_000L,
                    unloadOnBackgroundTrim = true,
                    memoryCacheKb = 8192,
                    backForwardPages = 0,
                    imageCacheKb = 65536,
                    thumbnailWidth = 240
                )
                MemoryMode.FAST -> MemoryPolicy(
                    mode = mode,
                    keepLoaded = -1,
                    idleUnloadMs = 0L,
                    hiddenUnloadMs = 0L,
                    unloadOnBackgroundTrim = false,
                    memoryCacheKb = 65536,
                    backForwardPages = 4,
                    imageCacheKb = 262144,
                    thumbnailWidth = 360
                )
                MemoryMode.CUSTOM -> MemoryPolicy(
                    mode = mode,
                    keepLoaded = when (s.tabUnloadMode) {
                        TabUnloadMode.KEEP_ALL -> -1
                        TabUnloadMode.AGGRESSIVE -> 0
                        TabUnloadMode.SMART -> s.keepLoadedTabs
                    },
                    idleUnloadMs = s.idleTabMinutes * 60_000L,
                    hiddenUnloadMs = 0L,
                    unloadOnBackgroundTrim = s.tabUnloadMode != TabUnloadMode.KEEP_ALL,
                    memoryCacheKb = if (s.speedMemory) 65536 else 16384,
                    backForwardPages = if (s.speedMemory) 3 else 1,
                    imageCacheKb = if (s.speedMemory) 262144 else 131072,
                    thumbnailWidth = 360
                )
                else -> MemoryPolicy(
                    mode = MemoryMode.BALANCED,
                    keepLoaded = if (bigPhone) 3 else 2,
                    idleUnloadMs = (if (bigPhone) 20 else 10) * 60_000L,
                    hiddenUnloadMs = 0L,
                    unloadOnBackgroundTrim = true,
                    memoryCacheKb = if (bigPhone) 32768 else 16384,
                    backForwardPages = if (bigPhone) 2 else 1,
                    imageCacheKb = 131072,
                    thumbnailWidth = 360
                )
            }
        }
    }
}
