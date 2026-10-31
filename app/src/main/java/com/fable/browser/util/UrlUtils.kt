package com.fable.browser.util

import java.net.URLEncoder

/**
 * Pure-Kotlin URL helpers — deliberately free of android.net.Uri so that every
 * rule here (URL vs search detection, tracking-param stripping, host parsing)
 * is covered by plain JVM unit tests in UrlUtilsTest.
 */
object UrlUtils {

    /** Known tracking query parameters, stripped on every navigation. */
    private val TRACKING_PARAM_PREFIXES = listOf("utm_", "pk_", "piwik_", "matomo_")
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid",
        "igshid", "igsh", "mc_eid", "mc_cid", "msclkid", "twclid",
        "yclid", "srsltid", "_hsenc", "_hsmi", "hsctatracking",
        "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id",
        "s_cid", "mkt_tok", "trk", "trkcampaign", "sc_campaign",
        "sc_channel", "sc_content", "sc_medium", "sc_outcome",
        "sc_geo", "sc_country", "ref_src", "ref_url", "spm", "scm",
        "wickedid", "cmpid", "ncid", "campaign_id", "ad_id", "adset_id"
    )

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val IP_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$")
    private const val LOCALHOST = "localhost"

    /**
     * Decide whether omnibox input is a URL or a search query.
     * Rules: explicit scheme -> URL; localhost/IP -> URL;
     * contains a dot with characters on both sides and no spaces -> URL;
     * otherwise -> search.
     */
    fun isLikelyUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains(' ')) return false
        if (SCHEME_REGEX.containsMatchIn(trimmed)) return true
        val hostPart = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.equals(LOCALHOST, ignoreCase = true) ||
            hostPart.startsWith("$LOCALHOST:", ignoreCase = true)
        ) return true
        if (IP_REGEX.matches(trimmed)) return true
        val dot = hostPart.indexOf('.')
        return dot in 1 until hostPart.length - 1
    }

    /**
     * Normalize omnibox input into a loadable URL, or a search URL when the
     * input is not a URL. Auto-prepends https:// — never http:// — unless the
     * user explicitly typed http:// themselves.
     */
    fun normalizeInput(input: String, searchTemplate: String): String {
        val trimmed = input.trim()
        if (!isLikelyUrl(trimmed)) return searchUrl(trimmed, searchTemplate)
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            SCHEME_REGEX.containsMatchIn(trimmed) -> trimmed // about:, file:, data:, intent:, mailto:
            else -> "https://$trimmed"
        }
    }

    /**
     * Omnibox entry point: normalize [input] into a URL, or into a search URL
     * for the given engine when it is not a URL.
     */
    fun toUrlOrSearch(
        input: String,
        engine: com.fable.browser.data.model.SearchEngine
    ): String = normalizeInput(input, engine.queryUrl)

    fun searchUrl(query: String, template: String): String =
        template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))

    /** True when the user is navigating over plain HTTP. */
    fun isInsecure(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

    /** scheme of [url] in lowercase, or "" when there is none. */
    fun scheme(url: String): String {
        val match = SCHEME_REGEX.find(url) ?: return ""
        return match.value.dropLast(1).lowercase()
    }

    /**
     * Host of [url] in lowercase without userinfo or port, or null when the URL
     * has no authority component (about:, data:, mailto:, malformed input).
     */
    fun host(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val afterScheme = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else {
            if (SCHEME_REGEX.containsMatchIn(url)) return null else url
        }
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[") // IPv6 literal
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().ifBlank { null }
    }

    /** Host for omnibox display; falls back to the whole URL for scheme-only URLs. */
    fun displayHost(url: String): String = host(url)?.removePrefix("www.") ?: url

    /** scheme://host[:port] used as the per-site permission key. */
    fun origin(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd).lowercase()
        val authority = url.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        if (authority.isBlank()) return url
        return "$scheme://${authority.lowercase()}"
    }

    /** Remove known tracking parameters. Returns the input unchanged when nothing matched. */
    fun stripTrackingParams(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart)
        val base = url.substring(0, queryStart)
        val query = if (fragmentStart < 0) url.substring(queryStart + 1)
        else url.substring(queryStart + 1, fragmentStart)
        val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
        if (query.isEmpty()) return url

        val kept = query.split('&').filter { pair ->
            if (pair.isEmpty()) return@filter false
            val name = pair.substringBefore('=').lowercase()
            !(name in TRACKING_PARAMS || TRACKING_PARAM_PREFIXES.any { name.startsWith(it) })
        }
        val originalCount = query.split('&').count { it.isNotEmpty() }
        if (kept.size == originalCount) return url
        return if (kept.isEmpty()) base + fragment
        else base + "?" + kept.joinToString("&") + fragment
    }

    /** Upgrade http:// to https:// (HTTPS-only mode). */
    fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /** External (non-web) schemes handed off to the OS. */
    fun isExternalScheme(url: String): Boolean =
        scheme(url) in setOf("intent", "mailto", "tel", "sms", "market", "geo", "whatsapp", "bitcoin")

    fun isWebScheme(url: String): Boolean =
        scheme(url) in setOf("http", "https", "about", "data", "file", "blob", "resource")

    /** True when [candidateHost] is [baseHost] or a subdomain of it. */
    fun isSameSite(candidateHost: String?, baseHost: String?): Boolean {
        if (candidateHost == null || baseHost == null) return false
        return candidateHost == baseHost || candidateHost.endsWith(".$baseHost")
    }
}
