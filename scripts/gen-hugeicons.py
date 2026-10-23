# Generates app/src/main/java/org/anikodev/candywrapper/ui/HugeIcons.kt from CandyWrapper's
# IconDataHugeicons.generated.swift (Hugeicons stroke-rounded, MIT), the glyphs Redesign draws in
# place of lucide. Only the icons in icon-names.txt that Hugeicons maps are written.
#
#   python3 scripts/gen-hugeicons.py ~/CandyWrapper/Packages/CandyKit/Sources/DesignSystem/Icons/IconDataHugeicons.generated.swift \
#     "$(cat scripts/icon-names.txt)" > app/src/main/java/org/anikodev/candywrapper/ui/HugeIcons.kt
import re, sys
src = open(sys.argv[1]).read()
names = sys.argv[2].strip().split(',')
def fmt(v): return '%g' % float(v)
out = []
for name in names:
    m = re.search(r'case \.%s: // ([\w-]+)\n\s*return IconDefinition\(viewBox: IconViewBox\(x: 0, y: 0, width: 24, height: 24\), elements: \[\n(.*?)\n\s*\]\)' % name, src, re.S)
    if not m: continue
    source, body = m.groups()
    els = []
    for line in body.split('\n'):
        line = line.strip()
        if not line: continue
        if (p := re.match(r'IconElement\(\.path\(d: "([^"]*)"\), (.*)\),$', line)): d, paint = p.groups()
        elif (p := re.match(r'IconElement\(\.circle\(cx: ([-\d.]+), cy: ([-\d.]+), r: ([-\d.]+)\), (.*)\),$', line)):
            cx, cy, r, paint = p.groups(); cx, cy, r = float(cx), float(cy), float(r)
            d = f"M{fmt(cx-r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2*r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2*r)} 0"
        elif (p := re.match(r'IconElement\(\.ellipse\(cx: ([-\d.]+), cy: ([-\d.]+), rx: ([-\d.]+), ry: ([-\d.]+)\), (.*)\),$', line)):
            cx, cy, rx, ry, paint = p.groups(); cx, cy, rx, ry = map(float, (cx, cy, rx, ry))
            d = f"M{fmt(cx-rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2*rx)} 0a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2*rx)} 0"
        else: raise SystemExit('unparsed ' + name + ': ' + line[:90])
        if 'opacity: 0.16' in paint: kind = 'T'
        elif 'stroke: .none' in paint: kind = 'F'
        elif (w := re.search(r'strokeWidth: ([\d.]+)', paint)): kind = 'S' if float(w.group(1)) == 1.8 else 'D'
        else: raise SystemExit('paint ' + name + ': ' + paint)
        els.append(f'"{kind}{d}"')
    out.append(f'        "{name}" -> arrayOf({", ".join(els)}) // {source}')
print('''package org.anikodev.candywrapper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Hugeicons stroke-rounded (MIT, hugeicons.com), the duotone set CandyWrapper's Redesign draws in place
 * of lucide, generated from its IconDataHugeicons.generated.swift by scripts/gen-hugeicons.py. Do not
 * edit the paths by hand. Each path starts with its paint: S a 1.8 stroke, D a 2.4 dot, T the 16% tint
 * under a closed shape, F a solid fill.
 */
object HugeIcons {
    /**
    * Redesign's glyph size for an icon box (IconView.ditherGlyphSize): row, menu and badge glyphs draw at
    * 14, toolbar glyphs at 16, other sizes keep their box.
    */
    fun glyph(box: Float) = when {
        box in 11f..14f -> 14f
        box > 14f && box < 17f -> 16f
        else -> box
    }

    private val cache = HashMap<Pair<String, Float>, ImageVector?>()

    /**
     * [name]'s glyph at [glyph] dp, or null where Hugeicons maps none and lucide stays. One 1.2dp stroke at
     * every size, and the duotone tint only above 16dp, where it reads as a fill, not weight.
     */
    fun of(name: String, glyph: Float): ImageVector? = synchronized(cache) {
        cache.getOrPut(name to glyph) {
            val paths = paths(name) ?: return@getOrPut null
            val unit = 1.2f * 24f / glyph
            val b = ImageVector.Builder("huge-$name", glyph.dp, glyph.dp, 24f, 24f)
            for (p in paths) {
                val nodes = PathParser().parsePathString(p.substring(1)).toNodes()
                when (p[0]) {
                    'T' -> if (glyph > 16f) b.addPath(nodes, fill = SolidColor(Color.Black), fillAlpha = 0.16f)
                    'F' -> b.addPath(nodes, fill = SolidColor(Color.Black))
                    else -> b.addPath(
                        nodes, stroke = SolidColor(Color.Black), strokeLineWidth = if (p[0] == 'D') unit * 4f / 3f else unit,
                        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            b.build()
        }
    }

    private fun paths(name: String): Array<String>? = when (name) {
''' + '\n'.join(out) + '''
        else -> null
    }
}''')
