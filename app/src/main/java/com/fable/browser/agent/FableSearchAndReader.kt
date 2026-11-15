package com.fable.browser.agent

import android.text.Html
import android.webkit.CookieManager
import com.fable.browser.engine.FableUnifiedEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

data class WebPageUnderstanding(
    val url: String,
    val title: String,
    val plainText: String,
    val summaryBullets: List<String>,
    val discoveredLinks: List<Pair<String, String>>
)

object FableSearchAndReader {

    suspend fun searchWeb(query: String, maxResults: Int = 6): List<WebSearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val searchUrl = "https://html.duckduckgo.com/html/?q=$encoded"
            val ua = FableUnifiedEngine.defaultBlinkUserAgent.ifBlank {
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            }
            val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connect()
            }
            val html = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else ""
            conn.disconnect()
            if (html.isBlank()) return@runCatching emptyList()

            val hits = ArrayList<WebSearchHit>()
            val linkRe = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val snipRe = Regex("""<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val snippets = snipRe.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()

            linkRe.findAll(html).forEachIndexed { idx, m ->
                if (hits.size >= maxResults) return@forEachIndexed
                val rawHref = m.groupValues[1]
                val realUrl = unwrapDdgUrl(rawHref)
                val title = cleanHtml(m.groupValues[2])
                val snip = snippets.getOrNull(idx).orEmpty()
                if (realUrl.startsWith("http") && title.isNotBlank()) {
                    hits.add(WebSearchHit(title = title, url = realUrl, snippet = snip))
                }
            }
            hits
        }.getOrDefault(emptyList())
    }

    suspend fun fetchAndReadPage(url: String, allowCookies: Boolean = true): WebPageUnderstanding? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@withContext null
        runCatching {
            val ua = FableUnifiedEngine.defaultBlinkUserAgent.ifBlank {
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            }
            val cookie = if (allowCookies) runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() else null
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                connect()
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val rawHtml = conn.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (total < 1_500_000) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                sb.toString()
            }
            conn.disconnect()

            val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(rawHtml)
            val title = titleMatch?.let { cleanHtml(it.groupValues[1]) }.orEmpty().ifBlank { url }

            val links = ArrayList<Pair<String, String>>()
            val aRe = Regex("""<a[^>]*href=["']([^"'#]+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (m in aRe.findAll(rawHtml)) {
                if (links.size >= 40) break
                val label = cleanHtml(m.groupValues[2]).trim()
                val href = runCatching { URL(URL(url), m.groupValues[1].trim()).toString() }.getOrDefault("")
                if (label.length in 2..80 && href.startsWith("http")) {
                    links.add(label to href)
                }
            }

            val stripped = rawHtml
                .replace(Regex("""<(script|style|noscript|svg|template)[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                .replace(Regex("""<(br|p|div|li|h[1-6]|tr|section|article)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""<[^>]+>"""), " ")

            val decoded = Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY).toString()
            val lines = decoded.lines()
                .map { it.replace(Regex("""\s+"""), " ").trim() }
                .filter { it.length >= 3 }
            val plainText = lines.joinToString("\n")

            val bullets = lines
                .filter { it.length in 35..220 }
                .distinct()
                .take(6)

            WebPageUnderstanding(
                url = url,
                title = title,
                plainText = plainText,
                summaryBullets = bullets,
                discoveredLinks = links.distinctBy { it.second }
            )
        }.getOrNull()
    }

    private fun unwrapDdgUrl(rawHref: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(rawHref)?.groupValues?.get(1)
        if (!uddg.isNullOrBlank()) {
            return runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(rawHref)
        }
        return if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
    }

    private fun cleanHtml(fragment: String): String {
        val noTags = fragment.replace(Regex("""<[^>]+>"""), " ")
        return Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("""\s+"""), " ").trim()
    }
}
