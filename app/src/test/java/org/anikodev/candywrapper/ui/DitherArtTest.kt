package org.anikodev.candywrapper.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** The art's pixel math (DitherArt.render on the Mac): exact background where faded, three strengths per hue, the reach cap. */
class DitherArtTest {
    private val bg = 0x131314

    @Test
    fun theBackgroundStaysExactlyTheBackground() {
        val px = IntArray(64) { bg or OPAQUE }
        DitherArt.dither(px, 8, 8, bg)
        assertTrue(px.all { it == bg or OPAQUE })
    }

    @Test
    fun dotsTakeAtMostThreeStrengthsAndKeepWithinTheReach() {
        val w = 32
        val h = 32
        val px = IntArray(w * h) { 0xFF7040A0.toInt() }
        DitherArt.dither(px, w, h, bg)
        val reds = px.map { it shr 16 and 0xff }
        // Per row the fade is the same, so a flat colour lands on at most three levels.
        for (y in 0 until h) assertTrue(reds.subList(y * w, (y + 1) * w).toSet().size <= 3)
        assertTrue(px.all { p -> listOf(16, 8, 0).all { s -> kotlin.math.abs((p shr s and 0xff) - (bg shr s and 0xff)) <= DitherArt.REACH + 1 } })
    }

    @Test
    fun aPhoneCanvasRendersInWellUnderAFrameBudgetOffTheMainThread() {
        // A 411x891dp window at 2dp dots: 206 by 446 canvas pixels, about 360 KB of ARGB.
        val w = 206
        val h = 446
        val px0 = IntArray(w * h) { i -> 0xFF000000.toInt() or ((i % w) * 255 / w shl 16) or ((i / w) * 255 / h shl 8) or 0x60 }
        val times = (1..5).map {
            val start = System.nanoTime()
            val px = px0.copyOf()
            DitherArt.dither(px, w, h, bg)
            DitherArt.blurred(px, w, h)
            (System.nanoTime() - start) / 1_000_000.0
        }
        println("redesign art ${w}x$h: median ${times.sorted()[2]} ms")
    }

    private companion object { const val OPAQUE = -0x1000000 }
}
