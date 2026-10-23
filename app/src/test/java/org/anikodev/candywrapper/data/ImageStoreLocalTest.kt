package org.anikodev.candywrapper.data

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.anikodev.candywrapper.core.media.ImageRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The composer's images: a file reads in place, a content URI is copied into the cache once. */
@RunWith(RobolectricTestRunner::class)
class ImageStoreLocalTest {
    private val dir = Files.createTempDirectory("img").toFile()

    @Test fun fileRefReadsInPlace() = runBlocking {
        val picture = File(dir, "paste.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = picture.toURI().toString()
        val store = ImageStore(dir) { error("a file never goes through the resolver") }
        assertEquals(picture.canonicalPath, store.file(ImageRef.Local("local:$uri", "paste.png", uri)) { error("no URL") }?.canonicalPath)
    }

    @Test fun contentRefCopiesOnce() = runBlocking {
        var opens = 0
        val store = ImageStore(dir) { opens++; ByteArrayInputStream(byteArrayOf(4, 5, 6)) }
        val ref = ImageRef.Local("local:content://media/1", "photo.jpg", "content://media/1")
        assertArrayEquals(byteArrayOf(4, 5, 6), store.file(ref) { error("no URL") }!!.readBytes())
        store.file(ref) { error("no URL") }
        assertEquals(1, opens)
    }

    @Test fun unreadableContentIsNull() = runBlocking {
        val store = ImageStore(dir) { null }
        assertNull(store.file(ImageRef.Local("local:content://gone", "gone.png", "content://gone")) { error("no URL") })
    }
}
