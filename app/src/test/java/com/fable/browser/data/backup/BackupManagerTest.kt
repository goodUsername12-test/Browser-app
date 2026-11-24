package com.fable.browser.data.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {
    @Test
    fun sealsAndOpensEncryptedPayloadWithFourCharPassword() {
        val plain = "fable-site-cookies-and-storage-test".toByteArray(Charsets.UTF_8)
        val pw = "1234".toCharArray()
        val sealed = BackupManager.sealBytes(plain, pw)
        val opened = BackupManager.openBytes(sealed, "1234".toCharArray())
        assertArrayEquals(plain, opened)
    }

    @Test
    fun mergesSiteStorageMapsAndFormatsSummary() {
        val target = JSONObject()
        val src1 = JSONObject().put(
            "https://bloxd.io",
            JSONObject()
                .put("ls", JSONObject().put("token_a", "val1"))
                .put("ss", JSONObject().put("tab_k", "s1"))
        )
        val src2 = JSONObject().put(
            "https://bloxd.io",
            JSONObject()
                .put("ls", JSONObject().put("prefs", "val2"))
                .put("ss", JSONObject())
        )
        BackupManager.mergeSiteStorageMaps(target, src1)
        BackupManager.mergeSiteStorageMaps(target, src2)
        val bloxd = target.getJSONObject("https://bloxd.io")
        assertEquals("val1", bloxd.getJSONObject("ls").getString("token_a"))
        assertEquals("val2", bloxd.getJSONObject("ls").getString("prefs"))
        assertEquals("s1", bloxd.getJSONObject("ss").getString("tab_k"))

        val origins = LinkedHashSet<String>()
        BackupManager.addOriginFromUrl(origins, "https://app.dust.tt/w/abc/assistant?x=1")
        assertTrue(origins.contains("https://app.dust.tt"))

        val summary = BackupResult(passwords = 3, cookies = 12, siteStorages = 2, settings = true).summary()
        assertTrue(summary.contains("3 passwords"))
        assertTrue(summary.contains("12 cookies"))
        assertTrue(summary.contains("2 site storages"))
        assertTrue(summary.contains("settings"))
    }
}
