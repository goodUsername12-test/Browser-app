package com.fable.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotifierTest {
    @Test
    fun formatsMbLeftSpeedAndEtaSecondsInOneSummary() {
        val total = 50L * 1024L * 1024L
        val written = 12L * 1024L * 1024L
        val speed = 2.0 * 1024.0 * 1024.0
        val fmt = DownloadNotifier.formatProgress("game.apk", written, total, speed)
        assertFalse(fmt.indeterminate)
        assertEquals(24, fmt.percent)
        assertTrue(fmt.title.contains("game.apk"))
        assertTrue(fmt.title.contains("24%"))
        assertTrue(fmt.compactText.contains("12.00 MB / 50.00 MB"))
        assertTrue(fmt.compactText.contains("38.00 MB left"))
        assertTrue(fmt.compactText.contains("2.00 MB/s"))
        assertTrue(fmt.compactText.contains("19s left"))
        assertTrue(fmt.expandedText.contains("Speed: 2.00 MB/s"))
    }

    @Test
    fun formatsMinutesAndUnknownSizeCleanly() {
        assertEquals("1m 15s left", DownloadNotifier.formatEtaSeconds(75L * 1024L, 1024.0))
        val unk = DownloadNotifier.formatProgress("stream.bin", 5L * 1024L * 1024L, -1L, 512.0 * 1024.0)
        assertTrue(unk.indeterminate)
        assertTrue(unk.compactText.contains("5.00 MB"))
        assertTrue(unk.compactText.contains("512.0 KB/s"))
    }
}
