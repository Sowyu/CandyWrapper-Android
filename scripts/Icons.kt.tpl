package org.anikodev.candywrapper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Lucide v0.564.0 (ISC licence, the version T3 pins) and T3's brand glyphs, generated from
 * CandyWrapper's IconData.generated.swift by /tmp/cwa-tools/gen_icons.py (see docs/design.md).
 * Do not edit the paths by hand. Each icon builds on first use.
 *
 * Lucide icons are 24-unit strokes drawn in black and tinted by [Icon]. Brand glyphs with a fixed
 * colour keep it when drawn with `tint = Color.Unspecified`.
 */
object Icons {
    private fun stroke(name: String, vararg paths: String): Lazy<ImageVector> = lazy {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }

    /** A filled glyph; a null colour paints in the tint. */
    private fun filled(name: String, x: Float, y: Float, w: Float, h: Float, vararg paths: Pair<String, Color?>): Lazy<ImageVector> = lazy {
        val b = ImageVector.Builder(name, 24.dp, 24.dp * (h / w), w, h)
        b.addGroup(translationX = -x, translationY = -y)
        for ((d, color) in paths) {
            b.addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(color ?: Color.Black), pathFillType = PathFillType.EvenOdd)
        }
        b.clearGroup()
        b.build()
    }

//ICONS
}
