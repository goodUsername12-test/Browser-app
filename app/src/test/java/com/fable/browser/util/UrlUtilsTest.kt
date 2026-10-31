package com.fable.browser.util

import com.fable.browser.data.model.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL-vs-search detection, https upgrading, host parsing and tracking-param stripping. */
class UrlUtilsTest {

    // ------------------------------------------------- URL vs search detection

    @Test
    fun `hostnames with a dot are treated as urls`() {
        assertTrue(UrlUtils.isLikelyUrl("google.com"))
        assertTrue(UrlUtils.isLikelyUrl("news.ycombinator.com/newest"))
        assertTrue(UrlUtils.isLikelyUrl("example.co.uk?q=1"))
    }

    @Test
    fun `explicit schemes are urls`() {
        assertTrue(UrlUtils.isLikelyUrl("https://example.com"))
        assertTrue(UrlUtils.isLikelyUrl("http://example.com"))
        assertTrue(UrlUtils.isLikelyUrl("about:blank"))
        assertTrue(UrlUtils.isLikelyUrl("mailto:me@example.com"))
        assertTrue(UrlUtils.isLikelyUrl("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `localhost and ip literals are urls`() {
        assertTrue(UrlUtils.isLikelyUrl("localhost"))
        assertTrue(UrlUtils.isLikelyUrl("localhost:8080/admin"))
        assertTrue(UrlUtils.isLikelyUrl("192.168.1.1"))
        assertTrue(UrlUtils.isLikelyUrl("127.0.0.1:3000/path"))
    }

    @Test
    fun `words and sentences are searches`() {
        assertFalse(UrlUtils.isLikelyUrl("kotlin"))
        assertFalse(UrlUtils.isLikelyUrl("how to build an android browser"))
        assertFalse(UrlUtils.isLikelyUrl("best pizza near me"))
        assertFalse(UrlUtils.isLikelyUrl("what is 2 + 2"))
        assertFalse(UrlUtils.isLikelyUrl(""))
        assertFalse(UrlUtils.isLikelyUrl("   "))
    }

    @Test
    fun `a dotted phrase with spaces is still a search`() {
        assertFalse(UrlUtils.isLikelyUrl("i went to google.com yesterday"))
    }

    @Test
    fun `trailing or leading dots do not make a url`() {
        assertFalse(UrlUtils.isLikelyUrl("hello."))
        assertFalse(UrlUtils.isLikelyUrl(".hello"))
    }

    // ------------------------------------------------------------ normalization

    @Test
    fun `bare hosts are upgraded to https never http`() {
        assertEquals("https://example.com", UrlUtils.normalizeInput("example.com", "s?q=%s"))
        assertEquals(
            "https://example.com/a/b?c=d",
            UrlUtils.normalizeInput("example.com/a/b?c=d", "s?q=%s")
        )
    }

    @Test
    fun `explicit http is preserved so the interstitial can warn`() {
        assertEquals("http://example.com", UrlUtils.normalizeInput("http://example.com", "s?q=%s"))
        assertTrue(UrlUtils.isInsecure("http://example.com"))
        assertFalse(UrlUtils.isInsecure("https://example.com"))
    }

    @Test
    fun `non-url input becomes a search url for the chosen engine`() {
        assertEquals(
            "https://www.google.com/search?q=kotlin+flows",
            UrlUtils.toUrlOrSearch("kotlin flows", SearchEngine.GOOGLE)
        )
        assertEquals(
            "https://duckduckgo.com/?q=kotlin+flows",
            UrlUtils.toUrlOrSearch("kotlin flows", SearchEngine.DUCKDUCKGO)
        )
    }

    @Test
    fun `search queries are percent encoded`() {
        assertEquals(
            "https://www.bing.com/search?q=c%2B%2B+%26+rust",
            UrlUtils.toUrlOrSearch("c++ & rust", SearchEngine.BING)
        )
    }

    @Test
    fun `custom schemes pass through untouched`() {
        assertEquals(
            "mailto:me@example.com",
            UrlUtils.normalizeInput("mailto:me@example.com", "s?q=%s")
        )
        assertEquals("about:blank", UrlUtils.normalizeInput("about:blank", "s?q=%s"))
    }

    @Test
    fun `https upgrade helper rewrites only http`() {
        assertEquals("https://a.example", UrlUtils.upgradeToHttps("http://a.example"))
        assertEquals("https://a.example", UrlUtils.upgradeToHttps("https://a.example"))
        assertEquals("about:blank", UrlUtils.upgradeToHttps("about:blank"))
    }

    // ------------------------------------------------------------------- hosts

    @Test
    fun `host strips userinfo port and case`() {
        assertEquals("example.com", UrlUtils.host("https://user:pw@Example.com:8443/path?a=b"))
        assertEquals("example.com", UrlUtils.host("example.com/path"))
        assertEquals("2001:db8::1", UrlUtils.host("https://[2001:db8::1]:8080/"))
    }

    @Test
    fun `host is null for authority-less urls`() {
        assertNull(UrlUtils.host("about:blank"))
        assertNull(UrlUtils.host("mailto:me@example.com"))
        assertNull(UrlUtils.host("data:text/html,hello"))
    }

    @Test
    fun `origin is the permission key`() {
        assertEquals("https://example.com", UrlUtils.origin("https://example.com/a/b?c=d"))
        assertEquals("https://example.com:8443", UrlUtils.origin("https://example.com:8443/a"))
    }

    @Test
    fun `display host drops the www prefix`() {
        assertEquals("example.com", UrlUtils.displayHost("https://www.example.com/x"))
        assertEquals("about:blank", UrlUtils.displayHost("about:blank"))
    }

    @Test
    fun `same site matches subdomains only`() {
        assertTrue(UrlUtils.isSameSite("cdn.example.com", "example.com"))
        assertTrue(UrlUtils.isSameSite("example.com", "example.com"))
        assertFalse(UrlUtils.isSameSite("notexample.com", "example.com"))
        assertFalse(UrlUtils.isSameSite(null, "example.com"))
    }

    @Test
    fun `scheme classification routes external links to the os`() {
        assertTrue(UrlUtils.isExternalScheme("mailto:me@example.com"))
        assertTrue(UrlUtils.isExternalScheme("tel:+15551234"))
        assertTrue(UrlUtils.isExternalScheme("market://details?id=com.example"))
        assertTrue(UrlUtils.isExternalScheme("intent://x#Intent;end"))
        assertFalse(UrlUtils.isExternalScheme("https://example.com"))
        assertTrue(UrlUtils.isWebScheme("https://example.com"))
        assertTrue(UrlUtils.isWebScheme("about:blank"))
        assertFalse(UrlUtils.isWebScheme("tel:+15551234"))
    }

    // -------------------------------------------------- tracking-param removal

    @Test
    fun `utm parameters are removed`() {
        assertEquals(
            "https://example.com/article",
            UrlUtils.stripTrackingParams(
                "https://example.com/article?utm_source=twitter&utm_medium=social&utm_campaign=x"
            )
        )
    }

    @Test
    fun `click ids are removed`() {
        assertEquals(
            "https://shop.example/item",
            UrlUtils.stripTrackingParams(
                "https://shop.example/item?fbclid=abc&gclid=def&igshid=ghi&mc_eid=jkl&msclkid=mno"
            )
        )
    }

    @Test
    fun `legitimate parameters survive`() {
        assertEquals(
            "https://example.com/search?q=privacy&page=2",
            UrlUtils.stripTrackingParams(
                "https://example.com/search?q=privacy&utm_source=news&page=2"
            )
        )
    }

    @Test
    fun `fragments are preserved`() {
        assertEquals(
            "https://example.com/doc#section-3",
            UrlUtils.stripTrackingParams("https://example.com/doc?utm_source=x#section-3")
        )
        assertEquals(
            "https://example.com/doc?q=1#section-3",
            UrlUtils.stripTrackingParams("https://example.com/doc?q=1&gclid=x#section-3")
        )
    }

    @Test
    fun `urls without tracking params are returned identically`() {
        val url = "https://example.com/a?b=c&d=e"
        assertEquals(url, UrlUtils.stripTrackingParams(url))
        assertEquals("https://example.com/a", UrlUtils.stripTrackingParams("https://example.com/a"))
    }

    @Test
    fun `parameter matching is case insensitive`() {
        assertEquals(
            "https://example.com/a",
            UrlUtils.stripTrackingParams("https://example.com/a?UTM_Source=x&FBCLID=y")
        )
    }
}
