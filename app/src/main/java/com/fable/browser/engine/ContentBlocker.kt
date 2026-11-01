package com.fable.browser.engine

import android.content.Context
import com.fable.browser.util.UrlUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentBlocker @Inject constructor(@ApplicationContext private val context: Context) {
    class FilterSet internal constructor(
        internal val blockedDomains: Set<String>,
        internal val blockedSubstrings: List<String>,
        internal val exceptionDomains: Set<String>,
        internal val exceptionSubstrings: List<String>
    ) {
        val ruleCount: Int
            get() = blockedDomains.size + blockedSubstrings.size
    }

    @Volatile private var filters: FilterSet? = null

    suspend fun ensureLoaded() {
        if (filters != null) return
        withContext(Dispatchers.IO) {
            if (filters != null) return@withContext
            val text = buildString {
                appendLine(readList("easylist.txt"))
                appendLine(readList("easyprivacy.txt"))
            }
            filters = parse(text)
        }
    }

    private fun readList(name: String): String {
        val updated = File(context.filesDir, "filters/$name")
        return if (updated.exists()) updated.readText()
        else context.assets.open(name).bufferedReader().use { it.readText() }
    }

    suspend fun updateFromUrl(easylistUrl: String, easyprivacyUrl: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "filters").apply { mkdirs() }
                download(easylistUrl, File(dir, "easylist.txt"))
                download(easyprivacyUrl, File(dir, "easyprivacy.txt"))
                filters = null
                ensureLoaded()
                filters?.ruleCount ?: 0
            }
        }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    fun shouldBlock(url: String, documentHost: String? = null): Boolean {
        val f = filters ?: return false
        return shouldBlock(f, url, documentHost)
    }

    fun ruleCount(): Int = filters?.ruleCount ?: 0

    fun rulesJson(): JSONObject {
        val f = filters
        return JSONObject().apply {
            put("type", "rules")
            put("blockedDomains", JSONArray(f?.blockedDomains?.toList() ?: emptyList<String>()))
            put("blockedSubstrings", JSONArray(f?.blockedSubstrings ?: emptyList<String>()))
            put("exceptionDomains", JSONArray(f?.exceptionDomains?.toList() ?: emptyList<String>()))
            put("exceptionSubstrings", JSONArray(f?.exceptionSubstrings ?: emptyList<String>()))
        }
    }

    companion object {
        const val DEFAULT_EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        const val DEFAULT_EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"

        fun shouldBlock(f: FilterSet, url: String, documentHost: String? = null): Boolean {
            val host = UrlUtils.host(url) ?: return false
            if (matchesDomain(host, f.exceptionDomains)) return false
            if (f.exceptionSubstrings.any { url.contains(it) }) return false
            if (matchesDomain(host, f.blockedDomains)) {
                if (UrlUtils.isSameSite(host, documentHost)) return false
                return true
            }
            return f.blockedSubstrings.any { url.contains(it) }
        }

        private fun matchesDomain(host: String, domains: Set<String>): Boolean {
            var h: String? = host
            while (h != null) {
                if (h in domains) return true
                val dot = h.indexOf('.')
                h = if (dot == -1) null else h.substring(dot + 1)
            }
            return false
        }

        fun parse(text: String): FilterSet {
            val blockedDomains = HashSet<String>(65_536)
            val blockedSubstrings = ArrayList<String>(4_096)
            val exceptionDomains = HashSet<String>(2_048)
            val exceptionSubstrings = ArrayList<String>(1_024)

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
                if (line.contains("##") || line.contains("#@#") || line.contains("#?#")) continue

                val isException = line.startsWith("@@")
                val body = if (isException) line.substring(2) else line

                val optIdx = body.indexOf('$')
                val pattern = if (optIdx >= 0) {
                    val opts = body.substring(optIdx + 1)
                    val unsupported = opts.split(',').any { opt ->
                        opt !in setOf("third-party", "script", "image", "xmlhttprequest", "subdocument", "popup")
                    }
                    if (unsupported) continue
                    body.substring(0, optIdx)
                } else body

                if (pattern.isEmpty()) continue

                if (pattern.startsWith("||")) {
                    val rest = pattern.substring(2)
                    val end = rest.indexOfFirst { it == '^' || it == '/' || it == '$' }
                    val domain = (if (end == -1) rest else rest.substring(0, end)).lowercase()
                    val hasPath = end != -1 && end < rest.length - 1 && rest[end] == '/'
                    if (domain.isEmpty() || domain.contains('*')) continue
                    if (hasPath) {
                        val sub = domain + rest.substring(end).removeSuffix("^")
                        if (isException) exceptionSubstrings.add(sub) else blockedSubstrings.add(sub)
                    } else {
                        if (isException) exceptionDomains.add(domain) else blockedDomains.add(domain)
                    }
                } else if (!pattern.startsWith("|") && !pattern.contains('*') &&
                    !pattern.contains('^') && pattern.length >= 6
                ) {
                    if (isException) exceptionSubstrings.add(pattern) else blockedSubstrings.add(pattern)
                }
            }
            return FilterSet(blockedDomains, blockedSubstrings, exceptionDomains, exceptionSubstrings)
        }
    }
}
