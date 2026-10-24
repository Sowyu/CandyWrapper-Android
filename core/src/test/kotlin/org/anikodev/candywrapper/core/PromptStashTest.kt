package org.anikodev.candywrapper.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.anikodev.candywrapper.core.stash.NewStashImage
import org.anikodev.candywrapper.core.stash.PromptStash
import org.anikodev.candywrapper.core.stash.Restored
import org.anikodev.candywrapper.core.stash.StashEntry

/** The prompt stash: newest first, 20 at most, images as files, every change written atomically. */
class PromptStashTest {
    private val dir: File = Files.createTempDirectory("stash").toFile()
    private var clock = 1_000L
    private var ids = 0

    private fun open() = PromptStash(dir, { clock++ }, { "e${ids++}" })
    private fun png(n: Int) = NewStashImage("shot$n.png", "image/png", ByteArray(10 + n) { it.toByte() })
    private fun imageFiles() = File(dir, "images").listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun newestFirstAndTheOldestEvictedPastTwenty() {
        val stash = open()
        val first = stash.stash("first", listOf(png(1)))
        repeat(20) { stash.stash("prompt $it", emptyList()) }
        val entries = stash.entries.value
        assertEquals(PromptStash.MAX_ENTRIES, entries.size)
        assertEquals("prompt 19", entries.first().prompt)
        assertFalse(entries.any { it.id == first.id })
        // The evicted entry's image went with it.
        assertTrue(imageFiles().isEmpty())
    }

    @Test
    fun restoreTakesTheEntryAndHandsItsImagesOver() {
        val stash = open()
        val entry = stash.stash("fix the inset", listOf(png(1), png(2)))
        assertEquals(2, imageFiles().size)
        val restored = stash.restore(entry.id)!!
        assertEquals("fix the inset", restored.prompt)
        assertEquals(listOf("shot1.png", "shot2.png"), restored.images.map { it.name })
        assertTrue(restored.images.all { it.file.exists() && it.file.length() > 0 })
        assertTrue(stash.entries.value.isEmpty())
        assertTrue(imageFiles().isEmpty())
        assertNull(stash.restore(entry.id))
    }

    @Test
    fun swapTradesTheDraftForTheEntryInOneWrite() {
        val stash = open()
        val older = stash.stash("older", emptyList())
        val target = stash.stash("stashed prompt", listOf(png(1)))
        val restored = stash.swap(target.id, "my draft", listOf(png(2)))!!
        assertEquals("stashed prompt", restored.prompt)
        assertEquals(1, restored.images.size)
        assertEquals(listOf("my draft", "older"), stash.entries.value.map { it.prompt })
        assertEquals(listOf(older.id), stash.entries.value.drop(1).map { it.id })
        assertEquals(1, stash.entries.value.first().images.size)
        // A reopened stash reads the same queue.
        assertEquals(stash.entries.value, open().entries.value)
    }

    @Test
    fun persistenceRoundTripsAndDeleteRemovesFiles() {
        val stash = open()
        stash.stash("one", listOf(png(1)))
        val two = stash.stash("two\n\nlines", emptyList())
        val reopened = open().entries.value
        assertEquals(listOf("two\n\nlines", "one"), reopened.map { it.prompt })
        assertEquals(stash.entries.value, reopened)
        val again = open()
        again.delete(reopened.last().id)
        assertTrue(imageFiles().isEmpty())
        assertEquals(listOf(two.id), open().entries.value.map { it.id })
    }

    @Test
    fun aFailedWriteLeavesTheStashAsItWas() {
        val stash = open()
        stash.stash("kept", emptyList())
        // A directory where the queue file goes makes the atomic rename fail.
        File(dir, "stash.json").delete()
        File(dir, "stash.json").mkdirs()
        File(dir, "stash.json/blocker").writeText("x")
        assertFailsWith<IOException> { stash.stash("lost", listOf(png(1))) }
        assertEquals(listOf("kept"), stash.entries.value.map { it.prompt })
        assertTrue(imageFiles().isEmpty())
    }

    @Test
    fun anUnreadableFileStartsEmptyAndRestoredLeftoversAreCleared() {
        File(dir, "stash.json").writeText("{not json")
        File(dir, "restored").mkdirs()
        File(dir, "restored/old").writeText("x")
        File(dir, "images").mkdirs()
        File(dir, "images/orphan").writeText("x")
        val stash = open()
        assertTrue(stash.entries.value.isEmpty())
        assertTrue(File(dir, "restored").listFiles().orEmpty().isEmpty())
        assertTrue(imageFiles().isEmpty())
    }

    @Test
    fun snippetsAreOneLineOrCountImages() {
        assertEquals("fix the inset on the card", StashEntry("a", 0, "  fix the\n inset   on the card ").snippet)
        assertEquals("x".repeat(90) + "…", StashEntry("a", 0, "x".repeat(120)).snippet)
        assertEquals("(empty)", StashEntry("a", 0, " ").snippet)
    }

    @Test
    fun aRestoredPromptGoesAfterTheDraftOrReplacesItOnASwap() {
        val r = Restored("stashed", emptyList())
        assertEquals("stashed", r.into("", replace = false))
        assertEquals("stashed", r.into("  \n", replace = false))
        assertEquals("my draft\n\nstashed", r.into("my draft \n", replace = false))
        assertEquals("stashed", r.into("my draft", replace = true))
        // An image-only entry leaves the text alone.
        assertEquals("my draft", Restored("", emptyList()).into("my draft", replace = false))
    }
}
