package org.anikodev.candywrapper.ui

import java.io.File

/**
 * Copies the bundled test photo (src/test/resources/redesign/photo.jpg) into [filesDir] as Redesign's
 * picture and returns its id, so the screenshots show dithered art as the owner's photo would.
 */
fun redesignPhoto(filesDir: File): String {
    val target = File(DitherPictures.folder(filesDir).apply { mkdirs() }, "photo-fixture.img")
    if (!target.exists()) {
        val input = checkNotNull(DitherPictures::class.java.getResourceAsStream("/redesign/photo.jpg")) { "redesign/photo.jpg is missing" }
        input.use { i -> target.outputStream().use { i.copyTo(it) } }
    }
    return DitherPictures.photoId(target)
}
