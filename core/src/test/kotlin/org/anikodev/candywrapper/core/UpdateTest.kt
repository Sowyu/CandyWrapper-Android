package org.anikodev.candywrapper.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.anikodev.candywrapper.core.update.UpdateClient
import org.anikodev.candywrapper.core.update.UpdateManifest
import org.anikodev.candywrapper.core.update.hasSha256
import org.anikodev.candywrapper.core.update.sha256Hex

class UpdateTest {
    // SHA-256 of "abc", from FIPS 180-2.
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val manifestJson = """{"versionCode":42,"versionName":"0.1.42-abc1234","commit":"abc1234def","publishedAt":"2026-10-23T05:00:00Z",
        "apkName":"CandyWrapper-42.apk","sha256":"${abc.uppercase()}","notes":["fix(app): one","feat(app): two"],"extra":true}"""
    private val server = MockWebServer().also { it.start() }
    private val dir = Files.createTempDirectory("update").toFile()

    @AfterTest
    fun close() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun parsesTheManifestCiWrites() {
        val m = UpdateManifest.parse(manifestJson)
        assertEquals(42, m.versionCode)
        assertEquals("0.1.42-abc1234", m.versionName)
        assertEquals("CandyWrapper-42.apk", m.apkName)
        assertEquals(abc, m.sha256)
        assertEquals(listOf("fix(app): one", "feat(app): two"), m.notes)
    }

    @Test
    fun rejectsManifestsItCannotActOn() {
        assertFailsWith<IllegalArgumentException> { UpdateManifest.parse("not json") }
        assertFailsWith<IllegalArgumentException> { UpdateManifest.parse(manifestJson.replace(abc.uppercase(), "1234")) }
        assertFailsWith<IllegalArgumentException> { UpdateManifest.parse(manifestJson.replace("CandyWrapper-42.apk", "../x.apk")) }
        assertFailsWith<IllegalArgumentException> { UpdateManifest.parse(manifestJson.replace("\"versionCode\":42", "\"versionCode\":0")) }
    }

    @Test
    fun onlyAHigherRunNumberIsAnUpdate() {
        val m = UpdateManifest.parse(manifestJson)
        assertTrue(m.isNewerThan(41))
        assertFalse(m.isNewerThan(42))
        assertFalse(m.isNewerThan(43))
    }

    @Test
    fun hashesAKnownVector() {
        assertEquals(abc, sha256Hex("abc".byteInputStream()))
        val f = File(dir, "f").apply { writeText("abc") }
        assertTrue(f.hasSha256(abc.uppercase()))
        assertFalse(f.hasSha256("0".repeat(64)))
        assertFalse(File(dir, "missing").hasSha256(abc))
    }

    @Test
    fun downloadKeepsAMatchingApkAndDeletesAMismatch() {
        val client = UpdateClient(server.url("/").toString())
        val m = UpdateManifest.parse(manifestJson)
        val target = File(dir, "a/CandyWrapper-42.apk")

        server.enqueue(MockResponse.Builder().body("abc").build())
        val percents = mutableListOf<Int>()
        client.download(m, target) { percents += it }
        assertTrue(target.hasSha256(abc))
        assertEquals(100, percents.last())
        assertEquals("/CandyWrapper-42.apk", server.takeRequest().target)

        server.enqueue(MockResponse.Builder().body("abd").build())
        assertFailsWith<java.io.IOException> { client.download(m, target) {} }
        assertFalse(target.exists())
    }

    @Test
    fun fetchesTheManifest() {
        server.enqueue(MockResponse.Builder().body(manifestJson).build())
        assertEquals(42, UpdateClient(server.url("/").toString()).fetchManifest().versionCode)
        assertEquals("/update.json", server.takeRequest().target)
    }
}
