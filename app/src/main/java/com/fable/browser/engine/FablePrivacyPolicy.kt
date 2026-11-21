package com.fable.browser.engine

import com.fable.browser.data.prefs.Settings
import com.fable.browser.util.UrlUtils

enum class CapabilityLevel {
    ENFORCED,
    PARTIAL,
    DISABLED,
    UNAVAILABLE
}

data class PrivacyCapabilityItem(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val geckoStatus: CapabilityLevel,
    val blinkStatus: CapabilityLevel,
    val note: String = ""
)

data class EffectivePrivacyDecision(
    val host: String?,
    val isTrustedSite: Boolean,
    val isIncognito: Boolean,
    val isSuper: Boolean,
    val etpEnabled: Boolean,
    val cookiePurging: Boolean,
    val stealthLowDetectability: Boolean,
    val blinkPrivacyParity: Boolean,
    val isolateNativeBridge: Boolean,
    val proxyStripLeakHeaders: Boolean,
    val adAndTrackerBlocking: Boolean,
    val stripTrackingParams: Boolean,
    val blockThirdPartyCookies: Boolean,
    val httpsOnly: Boolean,
    val sendGpc: Boolean,
    val sendDnt: Boolean,
    val strictReferrer: Boolean,
    val hideLocalIp: Boolean,
    val webRtcDisabled: Boolean,
    val fingerprintProtectionActive: Boolean,
    val fpCanvas: Boolean,
    val fpWebGlVendor: Boolean,
    val fpWebGlLimits: Boolean,
    val fpAudio: Boolean,
    val fpFonts: Boolean,
    val fpHardware: Boolean,
    val fpScreen: Boolean,
    val fpTimezone: Boolean,
    val fpLanguage: Boolean,
    val fpTimers: Boolean,
    val fpMedia: Boolean,
    val fpSensors: Boolean,
    val blockBatteryApi: Boolean,
    val blockNetworkInfoApi: Boolean
)

object FablePrivacyPolicy {
    private val BUILT_IN_AUTH_DOMAINS = setOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "dust.tt",
        "recaptcha.net",
        "hcaptcha.com"
    )

    fun isTrustedSite(host: String?, settings: Settings): Boolean {
        val clean = host?.trim()?.lowercase()?.removePrefix("www.") ?: return false
        if (clean.isEmpty()) return false
        if (BUILT_IN_AUTH_DOMAINS.any { clean == it || clean.endsWith(".$it") }) return true
        val userSites = settings.fpTrustedSites.split(',')
            .map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotEmpty() }
        return userSites.any { clean == it || clean.endsWith(".$it") }
    }

    fun isAdBlockAllowedSite(host: String?, settings: Settings): Boolean {
        val clean = host?.trim()?.lowercase()?.removePrefix("www.") ?: return false
        if (clean.isEmpty()) return false
        return settings.adBlockAllowSites.any { raw ->
            val allowed = raw.trim().lowercase().removePrefix("www.")
            allowed.isNotEmpty() && (clean == allowed || clean.endsWith(".$allowed"))
        }
    }

    fun evaluate(
        settings: Settings,
        url: String?,
        isIncognito: Boolean,
        isSuper: Boolean
    ): EffectivePrivacyDecision {
        val host = url?.let { UrlUtils.host(it) }
        val trusted = isTrustedSite(host, settings)
        val adAllowed = isAdBlockAllowedSite(host, settings)
        val adBlock = (if (isIncognito) settings.incognitoAdBlock else settings.adBlockEnabled) && !adAllowed
        val stripParams = if (isIncognito) settings.incognitoStripParams else settings.stripTrackingParams
        val block3p = if (isIncognito) settings.incognitoBlockThirdPartyCookies else settings.blockThirdPartyCookies
        val httpsOnly = if (isIncognito) settings.incognitoHttpsOnly else settings.httpsOnly
        val gpc = if (isIncognito) settings.incognitoGpc else settings.sendGpc
        val dnt = if (isIncognito) settings.incognitoSendDnt else settings.sendGpc
        val strictRef = if (isIncognito) settings.incognitoTrimReferrer else settings.strictReferrer
        val stealth = settings.stealthLowDetectability && !isSuper
        val fpMaster = when {
            trusted -> false
            isSuper -> true
            isIncognito -> settings.incognitoFingerprintProtection
            // In Stealth Low-Detectability mode, avoid noisy JS/API spoofing unless user explicitly enables Normal Tab Shield
            stealth && !settings.normalTabShield -> false
            else -> settings.fingerprintProtectionAll && settings.normalTabShield && !settings.gameMode
        }

        return EffectivePrivacyDecision(
            host = host,
            isTrustedSite = trusted,
            isIncognito = isIncognito,
            isSuper = isSuper,
            etpEnabled = settings.etpEnabled,
            cookiePurging = settings.cookiePurging,
            stealthLowDetectability = stealth,
            blinkPrivacyParity = settings.blinkPrivacyParity,
            isolateNativeBridge = settings.isolateNativeBridge,
            proxyStripLeakHeaders = settings.proxyStripLeakHeaders,
            adAndTrackerBlocking = adBlock,
            stripTrackingParams = stripParams,
            blockThirdPartyCookies = block3p,
            httpsOnly = httpsOnly,
            sendGpc = gpc,
            sendDnt = dnt,
            strictReferrer = strictRef,
            hideLocalIp = settings.hideLocalIp || isSuper,
            webRtcDisabled = settings.webRtcDisabled,
            fingerprintProtectionActive = fpMaster,
            fpCanvas = fpMaster && settings.fpCanvas,
            fpWebGlVendor = fpMaster && settings.hideGpuModel,
            fpWebGlLimits = fpMaster && settings.fpWebGlLimits,
            fpAudio = fpMaster && settings.fpAudio,
            fpFonts = fpMaster && settings.fpFonts,
            fpHardware = fpMaster && settings.fpHardware,
            fpScreen = fpMaster && settings.fpScreen,
            fpTimezone = fpMaster && settings.fpTimezone,
            fpLanguage = fpMaster && settings.fpLanguage,
            fpTimers = fpMaster && settings.fpTimers,
            fpMedia = fpMaster && settings.fpMedia,
            fpSensors = fpMaster && settings.fpSensors,
            blockBatteryApi = !settings.featureBatteryApi,
            blockNetworkInfoApi = !settings.featureNetworkInfo
        )
    }

    fun capabilityMatrix(settings: Settings): List<PrivacyCapabilityItem> {
        val bp = settings.blinkPrivacyParity
        fun cap(on: Boolean, gecko: CapabilityLevel, blink: CapabilityLevel): Pair<CapabilityLevel, CapabilityLevel> =
            if (!on) CapabilityLevel.DISABLED to CapabilityLevel.DISABLED
            else gecko to (if (bp) blink else CapabilityLevel.DISABLED)

        val (adG, adB) = cap(settings.adBlockEnabled, CapabilityLevel.ENFORCED, CapabilityLevel.ENFORCED)
        val (etpG, etpB) = cap(settings.etpEnabled, CapabilityLevel.ENFORCED, CapabilityLevel.PARTIAL)
        val (stripG, stripB) = cap(settings.stripTrackingParams, CapabilityLevel.ENFORCED, CapabilityLevel.ENFORCED)
        val (ckG, ckB) = cap(settings.blockThirdPartyCookies, CapabilityLevel.ENFORCED, CapabilityLevel.ENFORCED)
        val (httpsG, httpsB) = cap(settings.httpsOnly, CapabilityLevel.ENFORCED, CapabilityLevel.ENFORCED)
        val (gpcG, gpcB) = cap(settings.sendGpc, CapabilityLevel.ENFORCED, CapabilityLevel.ENFORCED)
        val (rtcG, rtcB) = cap(settings.hideLocalIp || settings.webRtcDisabled, CapabilityLevel.ENFORCED, CapabilityLevel.PARTIAL)
        val (fpG, fpB) = cap(settings.fingerprintProtectionAll && settings.normalTabShield, CapabilityLevel.ENFORCED, CapabilityLevel.PARTIAL)
        val (bridgeG, bridgeB) = if (settings.isolateNativeBridge) {
            CapabilityLevel.ENFORCED to CapabilityLevel.ENFORCED
        } else {
            CapabilityLevel.DISABLED to CapabilityLevel.DISABLED
        }
        val (proxyG, proxyB) = cap(settings.proxyStripLeakHeaders, CapabilityLevel.ENFORCED, CapabilityLevel.PARTIAL)
        val (dohG, dohB) = cap(settings.dohProvider.name != "OFF", CapabilityLevel.ENFORCED, CapabilityLevel.UNAVAILABLE)

        return listOf(
            PrivacyCapabilityItem("adblock", "Network ad & tracker blocking", settings.adBlockEnabled, adG, adB, "Shared ContentBlocker ruleset"),
            PrivacyCapabilityItem("etp", "Enhanced Tracking Protection", settings.etpEnabled, etpG, etpB, "Native Gecko ETP; filter list in Blink"),
            PrivacyCapabilityItem("query_strip", "Tracking URL parameter stripping", settings.stripTrackingParams, stripG, stripB, "Shared UrlUtils + Gecko query stripping"),
            PrivacyCapabilityItem("third_party_cookies", "Third-party cookie blocking", settings.blockThirdPartyCookies, ckG, ckB, "Gecko Total Cookie Protection + Blink CookieManager"),
            PrivacyCapabilityItem("https_only", "HTTPS-only mode", settings.httpsOnly, httpsG, httpsB, "Blocks plaintext HTTP unless confirmed"),
            PrivacyCapabilityItem("gpc", "Global Privacy Control (Sec-GPC)", settings.sendGpc, gpcG, gpcB, "HTTP header + navigator.globalPrivacyControl"),
            PrivacyCapabilityItem("webrtc", "WebRTC IP protection", settings.hideLocalIp || settings.webRtcDisabled, rtcG, rtcB, "Native Gecko ICE prefs; Blink JS ICE filter"),
            PrivacyCapabilityItem("fingerprint", "Anti-fingerprinting overrides", settings.fingerprintProtectionAll && settings.normalTabShield, fpG, fpB, "Native RFP in Gecko; per-toggle Blink guards"),
            PrivacyCapabilityItem("bridge_iso", "Native bridge isolation", settings.isolateNativeBridge, bridgeG, bridgeB, "Per-tab token gate + hides internal window markers"),
            PrivacyCapabilityItem("proxy_hdrs", "Proxy leak-header & DNS hardening", settings.proxyStripLeakHeaders, proxyG, proxyB, "Strips X-Forwarded-For/Via/Forwarded; remote DNS"),
            PrivacyCapabilityItem("doh", "DNS over HTTPS (DoH)", settings.dohProvider.name != "OFF", dohG, dohB, "Native Gecko TRR; Blink follows Android Private DNS")
        )
    }
}
