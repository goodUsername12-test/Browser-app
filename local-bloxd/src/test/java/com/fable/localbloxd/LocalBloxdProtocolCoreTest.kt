package com.fable.localbloxd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class LocalBloxdProtocolCoreTest {

    @Test
    fun unicodeUsernameTruncationPreservesEmojiSymbolsSpacesAndCase() {
        val sample = "  🔥 Player_One 世界 ★!@#  "
        assertEquals(sample, LocalBloxdProtocolCore.truncateByCodePoints(sample, 64))

        // 70 astral-plane emoji (140 UTF-16 code units, 70 Unicode code points)
        val seventyFire = "🔥".repeat(70)
        val truncated = LocalBloxdProtocolCore.truncateByCodePoints(seventyFire, 64)
        assertEquals(64, truncated.codePointCount(0, truncated.length))
        assertEquals("🔥".repeat(64), truncated)
        // Must not split surrogate pairs
        assertFalse(Character.isHighSurrogate(truncated.last()))
        assertEquals("", LocalBloxdProtocolCore.truncateByCodePoints(sample, 0))
    }

    @Test
    fun safeWorldStorageKeyPreservesDefaultAndAvoidsUnicodeCollisions() {
        assertEquals("local_world", LocalBloxdProtocolCore.safeWorldStorageKey("local_world"))
        assertEquals("my-world_42", LocalBloxdProtocolCore.safeWorldStorageKey("my-world_42"))
        assertEquals("default", LocalBloxdProtocolCore.safeWorldStorageKey("   "))

        val k1 = LocalBloxdProtocolCore.safeWorldStorageKey("世界")
        val k2 = LocalBloxdProtocolCore.safeWorldStorageKey("日本")
        val k3 = LocalBloxdProtocolCore.safeWorldStorageKey("My World 🎮")
        val k4 = LocalBloxdProtocolCore.safeWorldStorageKey("../escape/attempt")

        assertNotEquals(k1, k2)
        assertEquals(k1, LocalBloxdProtocolCore.safeWorldStorageKey("世界"))
        assertFalse(k4.contains(".."))
        assertFalse(k4.contains("/"))
        assertTrue(k1.matches(Regex("[a-zA-Z0-9_\\-]+")))
        assertTrue(k3.matches(Regex("[a-zA-Z0-9_\\-]+")))
    }

    @Test
    fun urlSegmentEncodingRoundTripsUnicodeSpacesAndPunctuation() {
        val samples = listOf(
            "local_world",
            "My World 🎮",
            "世界 / Room #1 + 100%",
            "a__b"
        )
        for (s in samples) {
            val enc = LocalBloxdProtocolCore.encodeUrlSegment(s)
            assertFalse("Encoded segment must not contain spaces", enc.contains(" "))
            assertFalse("Encoded segment must not contain slash", enc.contains("/"))
            assertEquals(s, LocalBloxdProtocolCore.decodeUrlSegment(enc))
        }
        assertEquals("local_world", LocalBloxdProtocolCore.encodeUrlSegment("local_world"))
    }

    @Test
    fun lobbyPathAndDocKeyValidationMatchesChunk82Rules() {
        assertTrue(LocalBloxdProtocolCore.isValidLobbyPath("main.js"))
        assertTrue(LocalBloxdProtocolCore.isValidLobbyPath("mods/helper.ts"))
        assertTrue(LocalBloxdProtocolCore.isValidLobbyPath("types/api.d.ts"))

        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("vendor.min.js"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("app.bundle.js"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("../secret.js"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("/abs/main.js"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("a\\b.js"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath(" main.js"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("notes.txt"))
        assertFalse(LocalBloxdProtocolCore.isValidLobbyPath("a".repeat(62) + ".js"))

        assertEquals("mods/a.ts", LocalBloxdProtocolCore.normalizeLobbyPath("/mods\\a.ts"))
        assertNull(LocalBloxdProtocolCore.normalizeLobbyPath("a/../b.js"))
        assertNull(LocalBloxdProtocolCore.normalizeLobbyPath("bad\u0000.js"))

        assertEquals("lobby:main.js", LocalBloxdProtocolCore.validateDocKey("lobby:main.js"))
        assertEquals("codeBlock:-12,64,7", LocalBloxdProtocolCore.validateDocKey("codeBlock:-12, 64, 7"))
        assertNull(LocalBloxdProtocolCore.validateDocKey("codeBlock:1,2"))
        assertNull(LocalBloxdProtocolCore.validateDocKey("codeBlock:1,x,3"))
        assertNull(LocalBloxdProtocolCore.validateDocKey("unknown:foo.js"))
    }

    @Test
    fun miniJsonRoundTripAndErrorHandling() {
        val input = LinkedHashMap<String, Any?>()
        input["name"] = "🔥 \"Quoted\" \\ Line\nTab\t世界"
        input["count"] = 42L
        input["ratio"] = -3.25
        input["ok"] = true
        input["none"] = null
        input["arr"] = listOf(1L, "x", false, mapOf("k" to "v"))

        val json = LocalBloxdProtocolCore.MiniJson.stringify(input)
        val parsed = LocalBloxdProtocolCore.MiniJson.parseObjectOrNull(json)
        assertNotNull(parsed)
        assertEquals(input["name"], parsed!!["name"])
        assertEquals(42L, (parsed["count"] as Number).toLong())
        assertEquals(-3.25, (parsed["ratio"] as Number).toDouble(), 1e-9)
        assertEquals(true, parsed["ok"])
        assertNull(parsed["none"])
        assertNull(LocalBloxdProtocolCore.MiniJson.parseObjectOrNull("{broken"))
        assertNull(LocalBloxdProtocolCore.MiniJson.parseObjectOrNull("[1,2,3]"))
    }

    @Test
    fun avroBinaryRoundTripsAllSixCapturedSchemas() {
        // 1. MetricsEnvelope
        val inner = byteArrayOf(1, 2, 3, -1, 0, 127)
        val env = LocalBloxdProtocolCore.MetricsEnvelope(
            mapOf("3PSIDMCPP" to "tok_123", "user" to "🔥世界"),
            inner
        )
        val envBytes = LocalBloxdProtocolCore.AvroBinary.encodeMetricsEnvelope(env)
        val envDec = LocalBloxdProtocolCore.AvroBinary.decodeMetricsEnvelope(envBytes)
        assertNotNull(envDec)
        assertEquals(env.metricsCookies, envDec!!.metricsCookies)
        assertArrayEquals(inner, envDec.contents)

        // 2. GetLobbyCodeReq (null and non-null path)
        for (path in listOf(null, "mods/main.ts")) {
            val req = LocalBloxdProtocolCore.GetLobbyCodeReq("classic_worlds", "世界", path)
            val enc = LocalBloxdProtocolCore.AvroBinary.encodeGetLobbyCode(req)
            val dec = LocalBloxdProtocolCore.AvroBinary.decodeGetLobbyCode(enc)
            assertEquals(req, dec)
        }

        // 3. GetCodeBlockReq (negative and positive coordinates)
        val cbReq = LocalBloxdProtocolCore.GetCodeBlockReq("classic", "local_world", Triple(-16, -4, 32))
        assertEquals(
            cbReq,
            LocalBloxdProtocolCore.AvroBinary.decodeGetCodeBlock(
                LocalBloxdProtocolCore.AvroBinary.encodeGetCodeBlock(cbReq)
            )
        )

        // 4. CollabPushReq
        val pushReq = LocalBloxdProtocolCore.CollabPushReq(
            "classic", "local_world", "lobby:main.js", 7, "[{\"clientID\":\"c1\",\"changes\":[5,[0,\"hi\"]]}]"
        )
        assertEquals(
            pushReq,
            LocalBloxdProtocolCore.AvroBinary.decodeCollabPush(
                LocalBloxdProtocolCore.AvroBinary.encodeCollabPush(pushReq)
            )
        )

        // 5. CollabPullReq
        val pullReq = LocalBloxdProtocolCore.CollabPullReq("classic", "local_world", "lobby:main.js", 3)
        assertEquals(
            pullReq,
            LocalBloxdProtocolCore.AvroBinary.decodeCollabPull(
                LocalBloxdProtocolCore.AvroBinary.encodeCollabPull(pullReq)
            )
        )

        // 6. SchematicsReq
        val schReq = LocalBloxdProtocolCore.SchematicsReq("classic", "local_world", "{\"action\":\"state\"}")
        assertEquals(
            schReq,
            LocalBloxdProtocolCore.AvroBinary.decodeSchematics(
                LocalBloxdProtocolCore.AvroBinary.encodeSchematics(schReq)
            )
        )
    }

    @Test
    fun avroBinaryRejectsMalformedTruncatedAndOversizedPayloads() {
        // Truncated varint (all high bits set)
        val truncatedVarint = ByteArray(12) { 0x80.toByte() }
        assertNull(LocalBloxdProtocolCore.AvroBinary.decodeMetricsEnvelope(truncatedVarint))
        assertNull(LocalBloxdProtocolCore.AvroBinary.decodeGetLobbyCode(truncatedVarint))

        // Out-of-bounds declared byte length (zigzag 1000000 = 2000000, but only 2 bytes follow)
        val w = LocalBloxdProtocolCore.AvroBinary.Writer()
        w.writeZigZagLong(1_000_000L)
        w.writeInt(1)
        assertNull(LocalBloxdProtocolCore.AvroBinary.decodeGetLobbyCode(w.toByteArray()))

        // Trailing garbage rejected
        val valid = LocalBloxdProtocolCore.AvroBinary.encodeCollabPull(
            LocalBloxdProtocolCore.CollabPullReq("classic", "w", "lobby:main.js", 0)
        )
        assertNull(LocalBloxdProtocolCore.AvroBinary.decodeCollabPull(valid + byteArrayOf(0x00)))

        // Wrong pos length for GetCodeBlock (2 ints instead of 3)
        val wBadPos = LocalBloxdProtocolCore.AvroBinary.Writer()
        wBadPos.writeString("classic")
        wBadPos.writeString("w")
        wBadPos.writeIntArray(listOf(1, 2))
        assertNull(LocalBloxdProtocolCore.AvroBinary.decodeGetCodeBlock(wBadPos.toByteArray()))
    }

    @Test
    fun extractBinaryContentsUnwrapsAllModule934WireFormats() {
        val inner = LocalBloxdProtocolCore.AvroBinary.encodeGetLobbyCode(
            LocalBloxdProtocolCore.GetLobbyCodeReq("classic", "w1", "main.js")
        )

        // 1) JSON Buffer object: { metricsCookies, contents: { type: "Buffer", data: [...] } }
        val dataList = inner.map { (it.toInt() and 0xFF) }
        val jsonBufferPayload = LocalBloxdProtocolCore.MiniJson.stringify(
            mapOf(
                "metricsCookies" to mapOf("a" to "b"),
                "contents" to mapOf("type" to "Buffer", "data" to dataList)
            )
        ).toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(inner, LocalBloxdProtocolCore.extractBinaryContents(jsonBufferPayload))

        // 2) JSON Uint8Array numeric-keyed object: { contents: { "0": ..., "1": ... } }
        val numMap = LinkedHashMap<String, Int>()
        for (i in inner.indices) numMap[i.toString()] = inner[i].toInt() and 0xFF
        val jsonU8Payload = LocalBloxdProtocolCore.MiniJson.stringify(
            mapOf("metricsCookies" to emptyMap<String, String>(), "contents" to numMap)
        ).toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(inner, LocalBloxdProtocolCore.extractBinaryContents(jsonU8Payload))

        // 3) Base64-encoded Avro Dy MetricsEnvelope (Module 934 postBinary + Chunk 117)
        val dyBytes = LocalBloxdProtocolCore.AvroBinary.encodeMetricsEnvelope(
            LocalBloxdProtocolCore.MetricsEnvelope(mapOf("3PSIDMCPP" to "xyz"), inner)
        )
        val dyBase64 = Base64.getEncoder().encodeToString(dyBytes).toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(inner, LocalBloxdProtocolCore.extractBinaryContents(dyBase64))

        // 4) Raw binary Avro Dy envelope
        assertArrayEquals(inner, LocalBloxdProtocolCore.extractBinaryContents(dyBytes))
    }

    @Test
    fun codeMirrorChangeSetUtf16ExactAndMultiLineInsertion() {
        // Insert emoji and newline inside existing text
        val doc0 = "const x = 1;\n"
        // Retain 10 ("const x = "), replace 1 ("1") with "42\nconst y = \"🔥\"", retain 2 (";\n")
        val step1 = LocalBloxdProtocolCore.applyCodeMirrorChangeSet(
            doc0,
            listOf(10, listOf(1, "42", "const y = \"🔥\""), 2)
        )
        assertEquals("const x = 42\nconst y = \"🔥\";\n", step1)

        // Surrogate pair UTF-16 offset check: "🔥" occupies 2 UTF-16 code units
        val emojiIdx = step1.indexOf("🔥")
        val step2 = LocalBloxdProtocolCore.applyCodeMirrorChangeSet(
            step1,
            listOf(emojiIdx, listOf(2, "🚀"), step1.length - emojiIdx - 2)
        )
        assertEquals("const x = 42\nconst y = \"🚀\";\n", step2)

        // Length mismatch must throw
        var threw = false
        try {
            LocalBloxdProtocolCore.applyCodeMirrorChangeSet("abc", listOf(2))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("ChangeSet length mismatch must throw", threw)
    }

    @Test
    fun collabPushEnforcesRevisionsPermissionsAndValidatesUpdates() {
        val docs = LinkedHashMap<String, LocalBloxdProtocolCore.CodeDocRecord>()
        val initial = "// Local lobby script\n"
        val updates1 = "[{\"clientID\":\"peerA\",\"changes\":[${initial.length},[0,\"api.broadcastMessage('hi');\",\"\"]]}]"

        // Valid push from revision 0 -> revision 1
        val ok1 = LocalBloxdProtocolCore.applyCollabPush(
            docs = docs,
            rawDocKey = "lobby:main.js",
            baseRevision = 0,
            rawUpdates = updates1,
            directCode = null,
            clientId = "peerA"
        )
        assertTrue(ok1.ok)
        assertTrue(ok1.accepted)
        assertFalse(ok1.conflict)
        assertEquals(1, ok1.record.revision)
        assertEquals(initial + "api.broadcastMessage('hi');\n", ok1.record.code)

        // Stale revision 0 must be rejected with conflict=true
        val stale = LocalBloxdProtocolCore.applyCollabPush(
            docs = docs,
            rawDocKey = "lobby:main.js",
            baseRevision = 0,
            rawUpdates = updates1,
            directCode = null,
            clientId = "peerB"
        )
        assertFalse(stale.ok)
        assertFalse(stale.accepted)
        assertTrue(stale.conflict)
        assertEquals(1, stale.record.revision)

        // Read-only permission blocks edits
        docs["lobby:main.js"] = ok1.record.copy(canEditCode = false)
        val ro = LocalBloxdProtocolCore.applyCollabPush(
            docs = docs,
            rawDocKey = "lobby:main.js",
            baseRevision = 1,
            rawUpdates = null,
            directCode = "hack",
            clientId = "peerA"
        )
        assertFalse(ro.ok)
        assertFalse(ro.accepted)
        assertFalse(ro.conflict)

        // Hidden permission blocks lobby/codeBlock/pull responses from leaking source
        docs["lobby:secret.js"] = LocalBloxdProtocolCore.CodeDocRecord(
            code = "TOP_SECRET",
            revision = 3,
            canSeeCode = false,
            canEditCode = false
        )
        val hiddenLobby = LocalBloxdProtocolCore.buildLobbyCodeResponseJson(docs, "secret.js")
        assertFalse(hiddenLobby.contains("TOP_SECRET"))
        val hiddenObj = LocalBloxdProtocolCore.MiniJson.parseObjectOrNull(hiddenLobby)!!
        assertEquals(false, hiddenObj["canSeeCode"])

        val hiddenPull = LocalBloxdProtocolCore.buildCollabPullResponseJson(docs, "lobby:secret.js")
        assertFalse(hiddenPull.contains("TOP_SECRET"))
    }

    @Test
    fun schematicsHandlerReturnsEmptyObjectForStateAnd435ForMutations() {
        val (stOk, bodyOk) = LocalBloxdProtocolCore.handleSchematicsRequest("{\"action\":\"state\"}")
        assertEquals(200, stOk)
        val okObj = LocalBloxdProtocolCore.MiniJson.parseObjectOrNull(bodyOk)!!
        assertTrue(okObj["assets"] is Map<*, *>)
        assertEquals(0L, (okObj["revision"] as Number).toLong())

        val (stMut, bodyMut) = LocalBloxdProtocolCore.handleSchematicsRequest("{\"action\":\"upload\",\"path\":\"a\"}")
        assertEquals(435, stMut)
        val mutObj = LocalBloxdProtocolCore.MiniJson.parseObjectOrNull(bodyMut)!!
        assertNotNull(mutObj["errorMessageForUser"])
    }
}
