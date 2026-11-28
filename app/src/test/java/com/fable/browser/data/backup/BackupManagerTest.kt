package com.fable.browser.data.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun streamingFileSealAndOpenMatchesByteFormatAndRejectsCorruptOrEmptyFiles() {
        val plainFile = File.createTempFile("fable_bk_plain_", ".bin")
        val sealedFile = File.createTempFile("fable_bk_sealed_", ".fable")
        val outFile = File.createTempFile("fable_bk_out_", ".bin")
        val emptyFile = File.createTempFile("fable_bk_empty_", ".fable")
        try {
            val payload = ByteArray(180_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            plainFile.writeBytes(payload)
            BackupManager.sealFileToFile(plainFile, sealedFile, "fable1234".toCharArray())

            val probe = BackupManager.probeFileHeader(sealedFile)
            assertTrue(probe.valid)
            assertEquals(sealedFile.length(), probe.sizeBytes)

            // Cross-compatible with openBytes
            val viaBytes = BackupManager.openBytes(sealedFile.readBytes(), "fable1234".toCharArray())
            assertArrayEquals(payload, viaBytes)

            // Streaming decrypt round-trip
            BackupManager.openFileToFile(sealedFile, outFile, "fable1234".toCharArray())
            assertArrayEquals(payload, outFile.readBytes())

            // Empty 0-byte file explicitly flagged
            val emptyProbe = BackupManager.probeFileHeader(emptyFile)
            assertFalse(emptyProbe.valid)
            assertTrue((emptyProbe.error ?: "").contains("0 bytes"))

            // Wrong password rejected
            var wrongPwThrew = false
            try {
                BackupManager.openFileToFile(sealedFile, outFile, "wrong999".toCharArray())
            } catch (e: BackupException) {
                wrongPwThrew = (e.message ?: "").contains("Wrong password", ignoreCase = true)
            }
            assertTrue(wrongPwThrew)

            // Non-fable magic rejected
            emptyFile.writeBytes(ByteArray(128) { 0x42 })
            val badProbe = BackupManager.probeFileHeader(emptyFile)
            assertFalse(badProbe.valid)
            assertTrue((badProbe.error ?: "").contains("FABLEBK1"))
        } finally {
            plainFile.delete()
            sealedFile.delete()
            outFile.delete()
            emptyFile.delete()
        }
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

        val summary = BackupResult(
            passwords = 3,
            cookies = 12,
            siteStorages = 2,
            ceTables = 1,
            bytesWritten = 24_576L,
            settings = true
        ).summary()
        assertTrue(summary.contains("24.0 KB"))
        assertTrue(summary.contains("3 passwords"))
        assertTrue(summary.contains("12 cookies"))
        assertTrue(summary.contains("2 site storages"))
        assertTrue(summary.contains("1 CE table"))
        assertTrue(summary.contains("settings"))
    }
}
