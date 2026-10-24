package org.anikodev.candywrapper.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redesign takes the owner's photo: the toggle waits for the picker, and the painted pictures are gone. */
class RedesignPictureTest {
    private class Harness(var on: Boolean = false, var picture: String? = null) {
        var opened = 0
        val flow = RedesignPhotoFlow({ picture != null }, { on = it }, { picture = it }).also { it.open = { opened++ } }
    }

    @Test
    fun turningRedesignOnWithoutAPhotoOpensThePickerAndACancelKeepsItOff() {
        val h = Harness()
        h.flow.toggle(true)
        assertEquals(1, h.opened)
        assertFalse(h.on)
        h.flow.picked(null)
        assertFalse(h.on)
        assertNull(h.picture)
    }

    @Test
    fun aPickTurnsRedesignOnWithThatPhoto() {
        val h = Harness()
        h.flow.toggle(true)
        h.flow.picked("photo:a.img")
        assertTrue(h.on)
        assertEquals("photo:a.img", h.picture)
    }

    @Test
    fun aCancelledPickDoesNotTurnOnALaterChange() {
        val h = Harness()
        h.flow.toggle(true)
        h.flow.picked(null)
        h.flow.change()
        h.flow.picked("photo:b.img")
        assertEquals("photo:b.img", h.picture)
        assertFalse(h.on)
    }

    @Test
    fun withAPhotoTheToggleSwitchesStraightAway() {
        val h = Harness(picture = "photo:a.img")
        h.flow.toggle(true)
        assertTrue(h.on)
        h.flow.toggle(false)
        assertFalse(h.on)
        assertEquals(0, h.opened)
    }

    @Test
    fun changingThePictureWhileOnKeepsItOn() {
        val h = Harness(on = true, picture = "photo:a.img")
        h.flow.change()
        h.flow.picked(null)
        assertEquals("photo:a.img", h.picture)
        h.flow.change()
        h.flow.picked("photo:b.img")
        assertTrue(h.on)
        assertEquals("photo:b.img", h.picture)
    }

    @Test
    fun aStoredPaintedPictureKeepsRedesignOnWithNoArtAndAsksForAPhoto() {
        for (id in OLD_IDS) {
            val style = RedesignStyle.stored(on = true, accent = "violet", picture = id, dim = "50")
            assertTrue(style.on)
            assertNull(style.picture)
            assertTrue(style.needsPicture)
            assertEquals("violet", style.accent)
            assertEquals(50, style.dim)
        }
        assertFalse(RedesignStyle.stored(on = false, accent = null, picture = "dusk", dim = null).needsPicture)
        assertEquals("photo:a.img", RedesignStyle.stored(true, null, "photo:a.img", null).picture)
        assertFalse(RedesignStyle.stored(true, null, "photo:a.img", null).needsPicture)
    }

    @Test
    fun anOldIdRendersNothingAndNoPaintedPictureIsLeft() {
        val dir = Files.createTempDirectory("redesign").toFile()
        for (id in OLD_IDS) {
            assertNull(DitherPictures.file(id, dir))
            assertNull(DitherPictures.pixels(id, dir, 4, 4))
        }
        val main = File("src/main/java").walk().filter { it.extension == "kt" }.toList()
        assertTrue(main.isNotEmpty())
        for (f in main) {
            val text = f.readText()
            for (id in OLD_IDS + listOf("Dusk", "Lagoon", "Ember", "Meadow")) assertFalse("$id in ${f.name}", "\"$id\"" in text)
        }
    }

    private companion object { val OLD_IDS = listOf("dusk", "lagoon", "ember", "meadow") }
}
