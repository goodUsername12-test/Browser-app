package com.fable.browser.util

import com.fable.browser.data.model.Bookmark

object BookmarkHtml {
    fun export(bookmarks: List<Bookmark>): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Bookmarks</TITLE>")
        appendLine("<H1>Bookmarks</H1>")
        appendLine("<DL><p>")
        for (b in bookmarks) {
            val added = b.createdAt / 1000
            appendLine("    <DT><A HREF=\"${escape(b.url)}\" ADD_DATE=\"$added\">${escape(b.title)}</A>")
        }
        appendLine("</DL><p>")
    }

    private val ANCHOR_REGEX = Regex(
        "<A\\s+[^>]*HREF=\"([^\"]+)\"[^>]*>([^<]*)</A>",
        RegexOption.IGNORE_CASE
    )

    fun parse(html: String): List<Bookmark> =
        ANCHOR_REGEX.findAll(html).mapNotNull { match ->
            val url = unescape(match.groupValues[1]).trim()
            val title = unescape(match.groupValues[2]).trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                Bookmark(title = title.ifBlank { url }, url = url)
            } else null
        }.toList()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
