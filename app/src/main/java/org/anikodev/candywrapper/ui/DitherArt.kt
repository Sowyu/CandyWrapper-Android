package org.anikodev.candywrapper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Redesign art, after CandyWrapper's `DitherArt` (DitherArtView.swift): the picture covering the
 * panel in its own colours at ordered-dither dots of brightness over the panel's background. One canvas
 * pixel is one dot; the panel draws it scaled up with nearest filtering, so each dot is the same whole
 * number of device pixels. Pure pixel math over ARGB ints, so it runs on any thread and in JUnit.
 */
object DitherArt {
    /** Dot size in dp, the Mac's 2pt. */
    const val DOT_DP = 2f

    /** The background's share over an open thread comes from the user's dim; these are the fixed pages. */
    const val SETTINGS_SCRIM = 0.8f
    const val PAGE_SCRIM = 0.92f

    /** How far, in 0..255 on any channel, the picture may stray from the background. */
    const val REACH = 98.0

    /** The 8x8 Bayer matrix, thresholds 0..<64. */
    val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
    )

    fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** The share of the background mixed into the picture at [y], 0 at the top to 1 at the bottom. */
    fun fade(y: Double) = 0.15 + 0.2 * smoothstep(0.6, 1.0, y)

    /**
     * Dithers [px] (opaque ARGB, row 0 at the top) in place over [background]. Brightness only, three
     * strengths per hue (none, half the reach, the full reach), so dots keep the picture's hue and a
     * faded area is exactly the background.
     */
    fun dither(px: IntArray, w: Int, h: Int, background: Int) {
        val bg = doubleArrayOf((background shr 16 and 0xff).toDouble(), (background shr 8 and 0xff).toDouble(), (background and 0xff).toDouble())
        val step = REACH / 2
        val d = DoubleArray(3)
        for (y in 0 until h) {
            val keep = 1 - fade((y + 0.5) / h)
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                d[0] = ((p shr 16 and 0xff) - bg[0]) * keep
                d[1] = ((p shr 8 and 0xff) - bg[1]) * keep
                d[2] = ((p and 0xff) - bg[2]) * keep
                var m = max(abs(d[0]), max(abs(d[1]), abs(d[2])))
                if (m > REACH) { for (c in 0..2) d[c] = d[c] * REACH / m; m = REACH }
                if (m <= 0.5) { px[i] = background or OPAQUE; continue }
                val threshold = (BAYER[(y and 7) * 8 + (x and 7)] + 0.5) / 64
                val scaled = m / step
                val low = kotlin.math.floor(scaled)
                val q = (if (scaled - low > threshold) low + 1 else low) * step / m
                px[i] = OPAQUE or (channel(bg[0] + d[0] * q) shl 16) or (channel(bg[1] + d[1] * q) shl 8) or channel(bg[2] + d[2] * q)
            }
        }
    }

    /**
     * The art blurred for frosted panes, as the Mac's Glass frosts over a pre-blurred wallpaper: the
     * canvas averaged down [factor] times, then three box passes. Drawn scaled up with bilinear filtering,
     * it costs one texture draw and never blurs per frame.
     */
    fun blurred(px: IntArray, w: Int, h: Int, factor: Int = 4, radius: Int = 2): Triple<IntArray, Int, Int> {
        val sw = max(1, w / factor)
        val sh = max(1, h / factor)
        val r = IntArray(sw * sh); val g = IntArray(sw * sh); val b = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var rs = 0; var gs = 0; var bs = 0; var n = 0
            for (yy in y * factor until min(h, (y + 1) * factor)) for (xx in x * factor until min(w, (x + 1) * factor)) {
                val p = px[yy * w + xx]
                rs += p shr 16 and 0xff; gs += p shr 8 and 0xff; bs += p and 0xff; n++
            }
            val i = y * sw + x
            r[i] = rs / max(1, n); g[i] = gs / max(1, n); b[i] = bs / max(1, n)
        }
        repeat(3) { for (ch in listOf(r, g, b)) boxBlur(ch, sw, sh, radius) }
        return Triple(IntArray(sw * sh) { OPAQUE or (r[it] shl 16) or (g[it] shl 8) or b[it] }, sw, sh)
    }

    private fun boxBlur(ch: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(ch.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { s += ch[y * w + xx]; n++ } }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
            ch[y * w + x] = s / n
        }
    }

    private fun channel(v: Double) = v.coerceIn(0.0, 255.0).roundToInt()
    private const val OPAQUE = -0x1000000
}

/**
 * The picture Redesign dithers: the owner's photo from the system picker, copied into the app's files.
 * The Mac dithers the desktop picture; Android hands an app the wallpaper only with a storage permission.
 */
object DitherPictures {
    private const val PHOTO = "photo:"

    fun isPhoto(id: String?) = id != null && id.startsWith(PHOTO)
    fun photoId(file: File) = PHOTO + file.name
    fun folder(filesDir: File) = File(filesDir, "redesign")
    /** The photo's file for [id], or null for anything else, such as the painted ids older builds stored. */
    fun file(id: String, filesDir: File) = if (isPhoto(id)) File(folder(filesDir), id.removePrefix(PHOTO)) else null

    /**
     * The photo covering a [w] by [h] canvas, centred, as opaque ARGB; null when there is none or it cannot
     * be read. It decodes downsampled near the canvas size, so a 12 MP picture costs a few hundred KB.
     */
    fun pixels(id: String, filesDir: File, w: Int, h: Int): IntArray? = file(id, filesDir)?.let { photo(it, w, h) }

    private fun photo(file: File, w: Int, h: Int): IntArray? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w * 2 && bounds.outHeight / (sample * 2) >= h * 2) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val cover = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val pw = src.width * cover
        val ph = src.height * cover
        val canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).drawBitmap(src, Rect(0, 0, src.width, src.height), RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        val out = IntArray(w * h)
        canvasBitmap.getPixels(out, 0, w, 0, 0, w, h)
        canvasBitmap.recycle()
        for (i in out.indices) out[i] = out[i] or -0x1000000
        return out
    }
}
