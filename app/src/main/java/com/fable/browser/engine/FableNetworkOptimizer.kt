package com.fable.browser.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class WifiLinkSnapshot(
    val isConnected: Boolean = true,
    val isWifi: Boolean = false,
    val isMetered: Boolean = false,
    val rssiDbm: Int = -55,
    val linkSpeedMbps: Int = 150,
    val frequencyMhz: Int = 5200,
    val downMbpsEstimate: Int = 100,
    val upMbpsEstimate: Int = 40,
    val qualityScore: Int = 85,
    val parallelStreams: Int = 4,
    val uploadChunkKb: Int = 1024,
    val platformCronetAvailable: Boolean = Build.VERSION.SDK_INT >= 34,
    val lastRttMs: Long? = null
) {
    val bandLabel: String
        get() = when {
            !isWifi -> "Mobile / Ethernet"
            frequencyMhz >= 5925 -> "Wi-Fi 6E / 7 (6 GHz)"
            frequencyMhz >= 4900 -> "5 GHz High-Speed Wi-Fi"
            frequencyMhz >= 2400 -> "2.4 GHz Long-Range Wi-Fi"
            else -> "Wi-Fi"
        }

    val tierLabel: String
        get() = when {
            !isConnected -> "Offline"
            qualityScore >= 85 -> "Ultra Fast"
            qualityScore >= 68 -> "Good"
            qualityScore >= 45 -> "Moderate"
            else -> "Weak Signal"
        }
}

object FableNetworkOptimizer {
    private val initialized = AtomicBoolean(false)
    @Volatile private var platformHttpEngine: Any? = null

    private val _snapshot = MutableStateFlow(WifiLinkSnapshot())
    val snapshot: StateFlow<WifiLinkSnapshot> = _snapshot.asStateFlow()

    fun initAsync(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val builderCls = Class.forName("android.net.http.HttpEngine\$Builder")
                val builder = builderCls.getConstructor(Context::class.java).newInstance(appCtx)
                runCatching { builderCls.getMethod("setEnableHttp2", Boolean::class.javaPrimitiveType).invoke(builder, true) }
                runCatching { builderCls.getMethod("setEnableQuic", Boolean::class.javaPrimitiveType).invoke(builder, true) }
                runCatching { builderCls.getMethod("setEnableBrotli", Boolean::class.javaPrimitiveType).invoke(builder, true) }
                platformHttpEngine = builderCls.getMethod("build").invoke(builder)
            }
        }
        refreshSnapshot(appCtx, fastConn = true)
    }

    @Suppress("DEPRECATION")
    fun refreshSnapshot(context: Context, fastConn: Boolean = true, sampleFileMb: Int = 20): WifiLinkSnapshot {
        return runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            if (caps == null) {
                val offline = WifiLinkSnapshot(isConnected = false, qualityScore = 0, parallelStreams = 1)
                _snapshot.value = offline
                return offline
            }
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val downKbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(1000)
            val upKbps = caps.linkUpstreamBandwidthKbps.coerceAtLeast(500)

            var rssi = -58
            var linkMbps = (downKbps / 1000).coerceAtLeast(50)
            var freqMhz = 5200

            if (isWifi) {
                val transportWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    caps.transportInfo as? WifiInfo
                } else {
                    null
                } ?: runCatching {
                    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
                }.getOrNull()

                if (transportWifi != null) {
                    if (transportWifi.rssi in -120..-1) rssi = transportWifi.rssi
                    if (transportWifi.linkSpeed > 0) linkMbps = transportWifi.linkSpeed
                    if (transportWifi.frequency > 0) freqMhz = transportWifi.frequency
                }
            }

            val plan = RustFastCore.recommendNetworkPlan(
                rssiDbm = rssi,
                linkMbps = linkMbps,
                downKbps = downKbps,
                upKbps = upKbps,
                freqMhz = freqMhz,
                isWifi = isWifi,
                isMetered = isMetered,
                fastConn = fastConn,
                fileSizeMb = sampleFileMb
            )
            val next = WifiLinkSnapshot(
                isConnected = true,
                isWifi = isWifi,
                isMetered = isMetered,
                rssiDbm = rssi,
                linkSpeedMbps = linkMbps,
                frequencyMhz = freqMhz,
                downMbpsEstimate = (downKbps / 1000).coerceAtLeast(1),
                upMbpsEstimate = (upKbps / 1000).coerceAtLeast(1),
                qualityScore = plan.qualityScore,
                parallelStreams = plan.parallelStreams,
                uploadChunkKb = plan.uploadChunkKb,
                platformCronetAvailable = platformHttpEngine != null || Build.VERSION.SDK_INT >= 34,
                lastRttMs = _snapshot.value.lastRttMs
            )
            _snapshot.value = next
            next
        }.getOrElse { _snapshot.value }
    }

    fun planForDownload(context: Context, totalBytes: Long, fastConn: Boolean): NetworkPlan {
        val snap = refreshSnapshot(context, fastConn, sampleFileMb = ((totalBytes / (1024L * 1024L)).toInt()).coerceAtLeast(1))
        return RustFastCore.recommendNetworkPlan(
            rssiDbm = snap.rssiDbm,
            linkMbps = snap.linkSpeedMbps,
            downKbps = snap.downMbpsEstimate * 1000,
            upKbps = snap.upMbpsEstimate * 1000,
            freqMhz = snap.frequencyMhz,
            isWifi = snap.isWifi,
            isMetered = snap.isMetered,
            fastConn = fastConn,
            fileSizeMb = ((totalBytes / (1024L * 1024L)).toInt()).coerceAtLeast(0)
        )
    }

    suspend fun probeLatencyMs(context: Context): Long? = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        val code = runCatching {
            val conn = (URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                useCaches = false
            }
            val rc = conn.responseCode
            conn.disconnect()
            rc
        }.getOrNull()
        if (code != null && code in 200..399) {
            val rtt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            refreshSnapshot(context)
            _snapshot.value = _snapshot.value.copy(lastRttMs = rtt)
            rtt
        } else {
            null
        }
    }
}
