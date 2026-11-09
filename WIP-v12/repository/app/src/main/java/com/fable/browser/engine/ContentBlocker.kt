package com.fable.browser.engine

import android.content.Context
import com.fable.browser.util.UrlUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        internal val exceptionSubstrings: List<String>,
        internal val cosmeticGeneric: List<String> = emptyList(),
        internal val cosmeticDomains: Map<String, List<String>> = emptyMap(),
        internal val cosmeticExceptions: Map<String, Set<String>> = emptyMap(),
        internal val genericHideDomains: Set<String> = emptySet()
    ) {
        val ruleCount: Int
            get() = blockedDomains.size + blockedSubstrings.size
        val cosmeticCount: Int
            get() = cosmeticGeneric.size + cosmeticDomains.values.sumOf { it.size }
    }

    data class FilterList(val file: String, val title: String, val url: String)

    @Volatile private var filters: FilterSet? = null
    private val loadLock = Mutex()
    private val updateLock = Mutex()

    suspend fun ensureLoaded() {
        if (filters != null) return
        loadLock.withLock {
            if (filters != null) return
            withContext(Dispatchers.IO) {
                val builder = Builder()
                LISTS.forEach { list -> readList(list.file)?.let { builder.add(it) } }
                filters = builder.build()
            }
        }
    }

    private fun readList(name: String): String? {
        val updated = File(context.filesDir, "filters/$name")
        if (updated.isFile && updated.length() > 0) return runCatching { updated.readText() }.getOrNull()
        val bundled = runCatching { context.assets.open("filters/$name").bufferedReader().use { it.readText() } }.getOrNull()
        if (!bundled.isNullOrEmpty()) return bundled
        return runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
    }

    suspend fun updateFromUrl(easylistUrl: String, easyprivacyUrl: String): Result<Int> = updateAll(
        mapOf(LISTS[0].file to easylistUrl, LISTS[1].file to easyprivacyUrl)
    )

    suspend fun updateAll(overrides: Map<String, String> = emptyMap()): Result<Int> =
        withContext(Dispatchers.IO) {
            updateLock.withLock {
                runCatching {
                    val dir = File(context.filesDir, "filters").apply { mkdirs() }
                    var ok = 0
                    LISTS.forEach { list ->
                        val url = overrides[list.file] ?: list.url
                        if (runCatching { download(url, File(dir, list.file)) }.isSuccess) ok++
                    }
                    if (ok == 0) throw IllegalStateException("No filter list could be downloaded")
                    File(dir, STAMP).writeText(System.currentTimeMillis().toString())
                    val builder = Builder()
                    LISTS.forEach { list -> readList(list.file)?.let { builder.add(it) } }
                    val fresh = builder.build()
                    filters = fresh
                    fresh.ruleCount
                }
            }
        }

    fun lastUpdated(): Long =
        runCatching { File(context.filesDir, "filters/$STAMP").readText().trim().toLong() }.getOrDefault(0L)

    suspend fun autoUpdateIfDue(): Boolean {
        val last = lastUpdated()
        val now = System.currentTimeMillis()
        if (last > 0 && now - last < AUTO_UPDATE_INTERVAL_MS) return false
        val attempt = File(context.filesDir, "filters/$ATTEMPT")
        val lastAttempt = runCatching { attempt.readText().trim().toLong() }.getOrDefault(0L)
        if (now - lastAttempt < RETRY_INTERVAL_MS) return false
        withContext(Dispatchers.IO) {
            runCatching {
                attempt.parentFile?.mkdirs()
                attempt.writeText(now.toString())
            }
        }
        return updateAll().isSuccess
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "FableBrowser-FilterUpdater")
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val head = tmp.bufferedReader().use { r -> CharArray(512).let { buf -> String(buf, 0, maxOf(0, r.read(buf))) } }
            if (tmp.length() < 1024 || head.trimStart().startsWith("<")) throw IllegalStateException("Not a filter list")
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IllegalStateException("Could not save list")
            }
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }

    fun shouldBlock(url: String, documentHost: String? = null): Boolean {
        val f = filters ?: return false
        return shouldBlock(f, url, documentHost)
    }

    fun ruleCount(): Int = filters?.ruleCount ?: 0

    fun cosmeticCount(): Int = filters?.cosmeticCount ?: 0

    fun rulesJson(): JSONObject {
        val f = filters
        return JSONObject().apply {
            put("type", "rules")
            put("blockedDomains", JSONArray(f?.blockedDomains?.toList() ?: emptyList<String>()))
            put("blockedSubstrings", JSONArray(f?.blockedSubstrings ?: emptyList<String>()))
            put("exceptionDomains", JSONArray(f?.exceptionDomains?.toList() ?: emptyList<String>()))
            put("exceptionSubstrings", JSONArray(f?.exceptionSubstrings ?: emptyList<String>()))
            put("cosmeticGeneric", JSONArray(f?.cosmeticGeneric ?: emptyList<String>()))
            val domains = JSONObject()
            f?.cosmeticDomains?.forEach { (d, list) -> domains.put(d, JSONArray(list)) }
            put("cosmeticDomains", domains)
            val exceptions = JSONObject()
            f?.cosmeticExceptions?.forEach { (d, set) -> exceptions.put(d, JSONArray(set.toList())) }
            put("cosmeticExceptions", exceptions)
            put("genericHideDomains", JSONArray(f?.genericHideDomains?.toList() ?: emptyList<String>()))
        }
    }

    private class Builder {
        val blockedDomains = HashSet<String>(131_072)
        val blockedSubstrings = LinkedHashSet<String>(8_192)
        val exceptionDomains = HashSet<String>(4_096)
        val exceptionSubstrings = LinkedHashSet<String>(2_048)
        val cosmeticGeneric = LinkedHashSet<String>(16_384)
        val cosmeticDomains = HashMap<String, LinkedHashSet<String>>(8_192)
        val cosmeticExceptions = HashMap<String, HashSet<String>>(1_024)
        val genericHideDomains = HashSet<String>(256)

        fun add(text: String) {
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line[0] == '#' && !line.startsWith("##")) continue
                if (parseCosmetic(line)) continue
                if (parseHosts(line)) continue
                parseNetwork(line)
            }
        }

        private fun parseHosts(line: String): Boolean {
            if (!(line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 "))) return false
            val host = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
            if (host.contains('.') && host != "localhost" && !host.contains('*')) blockedDomains.add(host.removePrefix("www."))
            return true
        }

        private fun parseCosmetic(line: String): Boolean {
            val ext = line.indexOf("#@#")
            val hide = line.indexOf("##")
            if (ext < 0 && hide < 0) {
                return line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("\$\$")
            }
            val isException = ext >= 0 && (hide < 0 || ext < hide)
            val idx = if (isException) ext else hide
            val domainsPart = line.substring(0, idx)
            val selector = line.substring(idx + if (isException) 3 else 2).trim()
            if (!usableSelector(selector)) return true
            val domains = domainsPart.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val positive = domains.filter { !it.startsWith("~") && !it.contains('*') && !it.contains('/') }
            val hasNegative = domains.any { it.startsWith("~") }
            if (isException) {
                if (positive.isEmpty()) return true
                positive.forEach { d -> cosmeticExceptions.getOrPut(d.removePrefix("www.")) { HashSet() }.add(selector) }
                return true
            }
            if (domains.isEmpty()) {
                if (cosmeticGeneric.size < MAX_GENERIC) cosmeticGeneric.add(selector)
                return true
            }
            if (positive.isEmpty() || hasNegative && positive.isEmpty()) return true
            positive.forEach { d ->
                val set = cosmeticDomains.getOrPut(d.removePrefix("www.")) { LinkedHashSet() }
                if (set.size < MAX_PER_DOMAIN) set.add(selector)
            }
            return true
        }

        private fun usableSelector(selector: String): Boolean {
            if (selector.isEmpty() || selector.length > 600) return false
            if (selector.startsWith("+js") || selector.startsWith("^")) return false
            if (selector.contains(":-abp-") || selector.contains(":style(") || selector.contains(":remove(") ||
                selector.contains(":matches-") || selector.contains(":xpath(") || selector.contains(":upward(") ||
                selector.contains(":has-text(") || selector.contains(":contains(") || selector.contains(":min-text-length(") ||
                selector.contains(":watch-attr(") || selector.contains(":others(") || selector.contains(":matches-css")
            ) return false
            if (selector.contains('{') || selector.contains('}')) return false
            return true
        }

        private fun parseNetwork(line: String) {
            val isException = line.startsWith("@@")
            val body = if (isException) line.substring(2) else line
            val optIdx = body.lastIndexOf('$')
            val pattern = if (optIdx > 0 && !body.substring(optIdx).contains('/')) {
                val opts = body.substring(optIdx + 1).lowercase()
                val list = opts.split(',')
                if (isException && list.any { it == "document" || it == "elemhide" || it == "generichide" || it == "ehide" || it == "ghide" }) {
                    if (list.any { it == "generichide" || it == "ghide" || it == "elemhide" || it == "ehide" || it == "document" }) {
                        domainOf(body.substring(0, optIdx))?.let { genericHideDomains.add(it) }
                    }
                    if (list.none { it == "document" }) return
                }
                if (list.any { it !in ALLOWED_OPTIONS }) return
                body.substring(0, optIdx)
            } else body

            if (pattern.isEmpty() || pattern == "|" || pattern == "||" || pattern == "*") return

            if (pattern.startsWith("||")) {
                val rest = pattern.substring(2)
                val end = rest.indexOfFirst { it == '^' || it == '/' || it == '$' || it == ':' || it == '?' }
                val domain = (if (end == -1) rest else rest.substring(0, end)).lowercase().trimEnd('.')
                if (domain.isEmpty() || domain.contains('*') || !domain.contains('.')) return
                val tail = if (end == -1) "" else rest.substring(end)
                val cleanTail = tail.removePrefix("^").removeSuffix("|").removeSuffix("^")
                if (cleanTail.isNotEmpty()) {
                    val cut = cleanTail.indexOfFirst { it == '*' || it == '^' }
                    val path = if (cut == -1) cleanTail else cleanTail.substring(0, cut)
                    if (cut != -1 && path.length < 5) return
                    val sub = domain + path
                    if (isException) exceptionSubstrings.add(sub) else blockedSubstrings.add(sub)
                } else {
                    val d = domain.removePrefix("www.")
                    if (isException) exceptionDomains.add(d) else blockedDomains.add(d)
                }
                return
            }
            if (pattern.startsWith("|") || pattern.startsWith("/") && pattern.endsWith("/") && pattern.length > 2) return
            if (pattern.contains('*') || pattern.contains('^')) return
            if (pattern.length >= 6) {
                if (isException) exceptionSubstrings.add(pattern) else blockedSubstrings.add(pattern)
            }
        }

        private fun domainOf(pattern: String): String? {
            val p = pattern.removePrefix("||").removePrefix("|").removePrefix("https://").removePrefix("http://")
            val d = p.substringBefore('^').substringBefore('/').substringBefore('$').lowercase().removePrefix("www.")
            return d.takeIf { it.contains('.') && !it.contains('*') }
        }

        fun build(): FilterSet {
            exceptionDomains.forEach { blockedDomains.remove(it) }
            return FilterSet(
                blockedDomains = blockedDomains,
                blockedSubstrings = blockedSubstrings.toList(),
                exceptionDomains = exceptionDomains,
                exceptionSubstrings = exceptionSubstrings.toList(),
                cosmeticGeneric = cosmeticGeneric.toList(),
                cosmeticDomains = cosmeticDomains.mapValues { it.value.toList() },
                cosmeticExceptions = cosmeticExceptions,
                genericHideDomains = genericHideDomains
            )
        }
    }

    companion object {
        const val DEFAULT_EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        const val DEFAULT_EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
        private const val STAMP = ".updated"
        private const val ATTEMPT = ".attempt"
        private const val AUTO_UPDATE_INTERVAL_MS = 4L * 24 * 60 * 60 * 1000
        private const val RETRY_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val MAX_GENERIC = 12_000
        private const val MAX_PER_DOMAIN = 400

        val LISTS: List<FilterList> = listOf(
            FilterList("easylist.txt", "EasyList", DEFAULT_EASYLIST_URL),
            FilterList("easyprivacy.txt", "EasyPrivacy", DEFAULT_EASYPRIVACY_URL),
            FilterList("adguard_mobile.txt", "AdGuard Mobile Ads", "https://filters.adtidy.org/extension/ublock/filters/11.txt"),
            FilterList("adguard_tracking.txt", "AdGuard Tracking Protection", "https://filters.adtidy.org/extension/ublock/filters/3.txt"),
            FilterList("peterlowe.txt", "Peter Lowe's ad and tracking servers", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext")
        )

        private val ALLOWED_OPTIONS = setOf(
            "third-party", "3p", "script", "image", "xmlhttprequest", "xhr", "subdocument", "frame", "popup",
            "stylesheet", "css", "media", "font", "ping", "beacon", "websocket", "other", "object", "important",
            "all", "match-case", "fetch"
        )

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

        fun parse(text: String): FilterSet = Builder().apply { add(text) }.build()
    }
}
