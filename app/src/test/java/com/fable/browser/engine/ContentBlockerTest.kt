package com.fable.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockerTest {
    private val list = """
        [Adblock Plus 2.0]
        ! Title: test list
        ||doubleclick.net^
        ||ads.example.com^
        ||tracker.test^${'$'}third-party
        ||partner.example.com/pixel^
        /adframe.
        -ad-banner-
        @@||ads.example.com/allowed^
        @@||whitelisted.doubleclick.net^
        example.com##.sidebar-ad
        ! trailing comment
    """.trimIndent()

    private fun filters() = ContentBlocker.parse(list)

    @Test
    fun `domain rules are parsed and matched`() {
        val f = filters()
        assertTrue(ContentBlocker.shouldBlock(f, "https://doubleclick.net/x.js", "news.test"))
        assertTrue(ContentBlocker.shouldBlock(f, "https://ads.example.com/a.gif", "news.test"))
    }

    @Test
    fun `subdomains of a blocked domain are blocked`() {
        val f = filters()
        assertTrue(
            ContentBlocker.shouldBlock(f, "https://static.ads.example.com/a.gif", "news.test")
        )
    }

    @Test
    fun `unrelated requests are allowed`() {
        val f = filters()
        assertFalse(ContentBlocker.shouldBlock(f, "https://example.com/app.js", "example.com"))
        assertFalse(ContentBlocker.shouldBlock(f, "https://cdn.wikipedia.org/x.css", "wikipedia.org"))
    }

    @Test
    fun `exception rules win over block rules`() {
        val f = filters()
        assertFalse(
            ContentBlocker.shouldBlock(f, "https://whitelisted.doubleclick.net/x.js", "news.test")
        )
        assertFalse(
            ContentBlocker.shouldBlock(f, "https://ads.example.com/allowed/a.gif", "news.test")
        )
    }

    @Test
    fun `first-party requests to a listed domain are not blocked`() {
        val f = filters()
        assertFalse(
            ContentBlocker.shouldBlock(f, "https://ads.example.com/a.gif", "ads.example.com")
        )
        assertFalse(
            ContentBlocker.shouldBlock(f, "https://static.ads.example.com/a.gif", "ads.example.com")
        )
    }

    @Test
    fun `substring rules match anywhere in the url`() {
        val f = filters()
        assertTrue(ContentBlocker.shouldBlock(f, "https://cdn.test/js/adframe.js", "news.test"))
        assertTrue(ContentBlocker.shouldBlock(f, "https://cdn.test/x-ad-banner-1.png", "news.test"))
        assertFalse(ContentBlocker.shouldBlock(f, "https://cdn.test/js/frame.js", "news.test"))
    }

    @Test
    fun `path rules become substring rules`() {
        val f = filters()
        assertTrue(
            ContentBlocker.shouldBlock(f, "https://partner.example.com/pixel?id=1", "news.test")
        )
        assertFalse(
            ContentBlocker.shouldBlock(f, "https://partner.example.com/legit.js", "news.test")
        )
    }

    @Test
    fun `cosmetic and comment lines are ignored`() {
        val f = filters()
        assertEquals(3, f.blockedDomains.size)
        assertTrue(f.blockedDomains.contains("doubleclick.net"))
        assertTrue(f.blockedDomains.contains("ads.example.com"))
        assertTrue(f.blockedDomains.contains("tracker.test"))
        assertFalse(f.blockedSubstrings.any { it.contains("sidebar-ad") })
    }

    @Test
    fun `third-party option is supported`() {
        val f = filters()
        assertTrue(ContentBlocker.shouldBlock(f, "https://tracker.test/t.gif", "news.test"))
    }

    @Test
    fun `wildcard domain rules are skipped rather than over-blocking`() {
        val f = ContentBlocker.parse("||*.evil^")
        assertFalse(ContentBlocker.shouldBlock(f, "https://a.evil/x.js", "news.test"))
    }

    @Test
    fun `rule count reports both buckets`() {
        val f = filters()
        assertEquals(f.blockedDomains.size + f.blockedSubstrings.size, f.ruleCount)
    }

    @Test
    fun `an empty list blocks nothing`() {
        val f = ContentBlocker.parse("")
        assertEquals(0, f.ruleCount)
        assertFalse(ContentBlocker.shouldBlock(f, "https://doubleclick.net/x.js", "news.test"))
    }

    @Test
    fun `requests without a host are never blocked`() {
        val f = filters()
        assertFalse(ContentBlocker.shouldBlock(f, "about:blank", "news.test"))
        assertFalse(ContentBlocker.shouldBlock(f, "data:text/html,adframe.", "news.test"))
    }
}
