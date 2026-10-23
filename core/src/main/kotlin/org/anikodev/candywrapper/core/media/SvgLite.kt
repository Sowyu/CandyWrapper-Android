package org.anikodev.candywrapper.core.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * One shape of an SVG, ready to paint: [path] is SVG path data in the root's user units, drawn
 * through [matrix] (`a b c d e f`, as SVG's `matrix()`). Colours are ARGB; null paints nothing.
 */
data class SvgShape(
    val path: String,
    val fill: Int?,
    val stroke: Int?,
    val strokeWidth: Float,
    val evenOdd: Boolean,
    val roundCap: Boolean,
    val roundJoin: Boolean,
    val matrix: FloatArray,
)

/** An SVG as shapes over its view box. */
data class SvgImage(val minX: Float, val minY: Float, val width: Float, val height: Float, val shapes: List<SvgShape>)

/**
 * The part of SVG that project favicons use, for drawing them at icon size without a WebView or
 * a library: paths and basic shapes, groups, `use`, transforms, solid fills and strokes, opacity,
 * presentation attributes, inline styles and `.class` rules. A gradient paints as the average of
 * its stops. Clip paths, masks, filters, text and embedded images are skipped.
 */
// ponytail: flat gradients and no clipping; render with a real SVG engine if favicons come out wrong in practice
object SvgLite {
    /** Above this many elements the file is not a favicon worth drawing. */
    private const val MAX_ELEMENTS = 4000

    private class Node(val name: String, val attrs: Map<String, String>, val parent: Node?) {
        val children = ArrayList<Node>()
        val text = StringBuilder()
    }

    /** Null when the text is not an SVG this can draw: no root, no size, or nothing visible. */
    fun parse(text: String): SvgImage? {
        val root = tree(text) ?: return null
        if (root.name != "svg") return null
        val ids = HashMap<String, Node>()
        val classes = HashMap<String, Map<String, String>>()
        walk(root) { n ->
            n.attrs["id"]?.let { ids[it] = n }
            if (n.name == "style") parseCss(n.text.toString(), classes)
        }
        val box = numbers(root.attrs["viewBox"].orEmpty())
        val (minX, minY, w, h) = if (box.size == 4) box else listOf(0f, 0f, length(root.attrs["width"]) ?: return null, length(root.attrs["height"]) ?: return null)
        if (w <= 0f || h <= 0f) return null
        val shapes = ArrayList<SvgShape>()
        val context = Context(ids, classes, shapes)
        for (child in root.children) draw(child, context, Style.ROOT.inherit(root, classes), IDENTITY, 0)
        return if (shapes.isEmpty()) null else SvgImage(minX, minY, w, h, shapes)
    }

    // Parsing

    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun tree(text: String): Node? {
        var root: Node? = null
        var current: Node? = null
        var count = 0
        var i = 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            current?.text?.append(text, i, lt)
            when {
                text.startsWith("<!--", lt) -> i = text.indexOf("-->", lt).let { if (it < 0) text.length else it + 3 }
                text.startsWith("<![CDATA[", lt) -> {
                    val end = text.indexOf("]]>", lt).let { if (it < 0) text.length else it }
                    current?.text?.append(text, lt + 9, end)
                    i = minOf(text.length, end + 3)
                }
                text.startsWith("<?", lt) || text.startsWith("<!", lt) -> i = text.indexOf('>', lt).let { if (it < 0) text.length else it + 1 }
                text.startsWith("</", lt) -> {
                    i = text.indexOf('>', lt).let { if (it < 0) text.length else it + 1 }
                    current = current?.parent
                }
                else -> {
                    val gt = tagEnd(text, lt)
                    if (gt < 0) break
                    val body = text.substring(lt + 1, gt)
                    val selfClosing = body.endsWith("/")
                    val raw = body.removeSuffix("/")
                    val name = raw.takeWhile { !it.isWhitespace() }.substringAfter(':')
                    val attrs = ATTR.findAll(raw).associate { m -> m.groupValues[1].substringAfter("xlink:") to unescape(m.groups[2]?.value ?: m.groupValues[3]) }
                    val node = Node(name, attrs, current)
                    if (++count > MAX_ELEMENTS) return null
                    if (current == null) root = root ?: node else current.children += node
                    if (!selfClosing) current = node
                    i = gt + 1
                }
            }
        }
        return root
    }

    /** The `>` closing the tag at [start], skipping any inside quoted attribute values. */
    private fun tagEnd(text: String, start: Int): Int {
        var quote = 0.toChar()
        for (j in start + 1 until text.length) {
            val ch = text[j]
            if (quote != 0.toChar()) { if (ch == quote) quote = 0.toChar() }
            else if (ch == '"' || ch == '\'') quote = ch
            else if (ch == '>') return j
        }
        return -1
    }

    private fun unescape(s: String) = if ('&' !in s) s else s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun walk(n: Node, visit: (Node) -> Unit) {
        visit(n)
        for (c in n.children) walk(c, visit)
    }

    private fun parseCss(css: String, into: MutableMap<String, Map<String, String>>) {
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (m in Regex("""([^{}]+)\{([^}]*)\}""").findAll(clean)) {
            val decls = declarations(m.groupValues[2])
            for (selector in m.groupValues[1].split(',')) {
                val s = selector.trim()
                if (s.startsWith('.') && s.drop(1).all { it.isLetterOrDigit() || it == '-' || it == '_' }) into[s.drop(1)] = into[s.drop(1)].orEmpty() + decls
            }
        }
    }

    private fun declarations(s: String): Map<String, String> = s.split(';').mapNotNull { d ->
        val k = d.substringBefore(':', "").trim().lowercase()
        val v = d.substringAfter(':', "").trim().removeSuffix("!important").trim()
        if (k.isEmpty() || v.isEmpty()) null else k to v
    }.toMap()

    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    private fun numbers(s: String) = NUMBER.findAll(s).map { it.value.toFloat() }.toList()

    private fun length(s: String?): Float? = s?.trim()?.takeIf { !it.endsWith("%") }?.let { NUMBER.find(it)?.value?.toFloatOrNull() }

    // Styles

    private data class Style(
        val fill: String, val stroke: String, val strokeWidth: Float, val fillOpacity: Float, val strokeOpacity: Float,
        val opacity: Float, val evenOdd: Boolean, val roundCap: Boolean, val roundJoin: Boolean, val color: String, val hidden: Boolean,
    ) {
        /** The style [n] draws with: its parent's, then presentation attributes, `.class` rules and `style`, in CSS order. */
        fun inherit(n: Node, classes: Map<String, Map<String, String>>): Style {
            val props = HashMap<String, String>()
            for (k in PROPS) n.attrs[k]?.let { props[k] = it.trim() }
            n.attrs["class"]?.split(Regex("\\s+"))?.forEach { c -> classes[c]?.let(props::putAll) }
            n.attrs["style"]?.let { props.putAll(declarations(it)) }
            fun f(k: String) = props[k]?.let { v -> if (v.endsWith("%")) v.dropLast(1).toFloatOrNull()?.div(100f) else v.toFloatOrNull() }
            return Style(
                fill = props["fill"] ?: fill,
                stroke = props["stroke"] ?: stroke,
                strokeWidth = length(props["stroke-width"]) ?: strokeWidth,
                fillOpacity = f("fill-opacity") ?: fillOpacity,
                strokeOpacity = f("stroke-opacity") ?: strokeOpacity,
                // Group opacity multiplies into each child: close enough for shapes that do not overlap.
                opacity = opacity * (f("opacity") ?: 1f),
                evenOdd = props["fill-rule"]?.let { it == "evenodd" } ?: evenOdd,
                roundCap = props["stroke-linecap"]?.let { it == "round" } ?: roundCap,
                roundJoin = props["stroke-linejoin"]?.let { it == "round" } ?: roundJoin,
                color = props["color"] ?: color,
                hidden = hidden || props["display"] == "none" || props["visibility"] == "hidden",
            )
        }

        companion object {
            val ROOT = Style("black", "none", 1f, 1f, 1f, 1f, false, false, false, "black", false)
            val PROPS = listOf(
                "fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "opacity", "fill-rule",
                "stroke-linecap", "stroke-linejoin", "color", "display", "visibility",
            )
        }
    }

    private class Context(val ids: Map<String, Node>, val classes: Map<String, Map<String, String>>, val out: MutableList<SvgShape>)

    private val SKIPPED = setOf(
        "defs", "clipPath", "mask", "symbol", "pattern", "linearGradient", "radialGradient", "style", "title",
        "desc", "metadata", "text", "image", "filter", "marker", "foreignObject", "script",
    )

    private fun draw(n: Node, cx: Context, parent: Style, parentMatrix: FloatArray, depth: Int) {
        if (n.name in SKIPPED || depth > 32) return
        val style = parent.inherit(n, cx.classes)
        if (style.hidden) return
        var matrix = n.attrs["transform"]?.let { multiply(parentMatrix, transform(it)) } ?: parentMatrix
        when (n.name) {
            "g", "a", "switch", "svg" -> for (c in n.children) draw(c, cx, style, matrix, depth + 1)
            "use" -> {
                val target = n.attrs["href"]?.removePrefix("#")?.let(cx.ids::get) ?: return
                matrix = multiply(matrix, floatArrayOf(1f, 0f, 0f, 1f, length(n.attrs["x"]) ?: 0f, length(n.attrs["y"]) ?: 0f))
                if (target.name == "symbol") for (c in target.children) draw(c, cx, style, matrix, depth + 1) else draw(target, cx, style, matrix, depth + 1)
            }
            else -> {
                val d = pathData(n) ?: return
                val fill = paint(style.fill, style.color, style.fillOpacity * style.opacity, cx)
                val stroke = paint(style.stroke, style.color, style.strokeOpacity * style.opacity, cx)?.takeIf { style.strokeWidth > 0f }
                if (fill == null && stroke == null) return
                cx.out += SvgShape(d, fill, stroke, style.strokeWidth, style.evenOdd, style.roundCap, style.roundJoin, matrix)
            }
        }
    }

    /** Every shape as path data, as `scripts/gen-icons.py` turns lucide's circles and rects into paths. */
    private fun pathData(n: Node): String? {
        fun a(k: String) = length(n.attrs[k]) ?: 0f
        return when (n.name) {
            "path" -> n.attrs["d"]?.takeIf { it.isNotBlank() }
            "rect" -> {
                val (x, y, w, h) = listOf(a("x"), a("y"), a("width"), a("height"))
                if (w <= 0f || h <= 0f) return null
                var rx = length(n.attrs["rx"]) ?: length(n.attrs["ry"]) ?: 0f
                var ry = length(n.attrs["ry"]) ?: rx
                rx = rx.coerceIn(0f, w / 2); ry = ry.coerceIn(0f, h / 2)
                if (rx == 0f || ry == 0f) "M$x $y h$w v$h h${-w} Z"
                else "M${x + rx} $y H${x + w - rx} A$rx $ry 0 0 1 ${x + w} ${y + ry} V${y + h - ry} A$rx $ry 0 0 1 ${x + w - rx} ${y + h} " +
                    "H${x + rx} A$rx $ry 0 0 1 $x ${y + h - ry} V${y + ry} A$rx $ry 0 0 1 ${x + rx} $y Z"
            }
            "circle", "ellipse" -> {
                val rx = if (n.name == "circle") a("r") else a("rx")
                val ry = if (n.name == "circle") rx else a("ry")
                if (rx <= 0f || ry <= 0f) return null
                val (cx, cy) = a("cx") to a("cy")
                "M${cx - rx} $cy A$rx $ry 0 1 0 ${cx + rx} $cy A$rx $ry 0 1 0 ${cx - rx} $cy Z"
            }
            "line" -> "M${a("x1")} ${a("y1")} L${a("x2")} ${a("y2")}"
            "polyline", "polygon" -> {
                val p = numbers(n.attrs["points"].orEmpty())
                if (p.size < 4) return null
                buildString {
                    append("M${p[0]} ${p[1]}")
                    for (i in 2 until p.size - 1 step 2) append(" L${p[i]} ${p[i + 1]}")
                    if (n.name == "polygon") append(" Z")
                }
            }
            else -> null
        }
    }

    // Colours

    private fun paint(value: String, current: String, alpha: Float, cx: Context): Int? {
        val v = value.trim()
        val base = when {
            v == "none" || v == "transparent" -> return null
            v.startsWith("url(") -> gradient(v.substringAfter("#").substringBefore(")").trim(), cx) ?: return null
            v.equals("currentColor", ignoreCase = true) -> color(current) ?: return null
            else -> color(v) ?: return null
        }
        val a = ((base ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
        return if (a == 0) null else (a shl 24) or (base and 0xFFFFFF)
    }

    /** A gradient's stops averaged into one colour, following one `href` for stops defined elsewhere. */
    private fun gradient(id: String, cx: Context): Int? {
        var g = cx.ids[id] ?: return null
        if (g.children.none { it.name == "stop" }) g = g.attrs["href"]?.removePrefix("#")?.let(cx.ids::get) ?: g
        val stops = g.children.filter { it.name == "stop" }.mapNotNull { s ->
            val props = HashMap<String, String>()
            s.attrs["stop-color"]?.let { props["stop-color"] = it }
            s.attrs["stop-opacity"]?.let { props["stop-opacity"] = it }
            s.attrs["style"]?.let { props.putAll(declarations(it)) }
            val c = color(props["stop-color"] ?: "black") ?: return@mapNotNull null
            val o = props["stop-opacity"]?.toFloatOrNull() ?: 1f
            (((c ushr 24) * o).toInt() shl 24) or (c and 0xFFFFFF)
        }
        if (stops.isEmpty()) return null
        fun avg(shift: Int) = stops.sumOf { (it ushr shift) and 0xFF } / stops.size
        return (avg(24) shl 24) or (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
    }

    private val NAMED = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "orange" to 0xFFA500, "purple" to 0x800080, "gray" to 0x808080, "grey" to 0x808080,
        "silver" to 0xC0C0C0, "navy" to 0x000080, "teal" to 0x008080, "maroon" to 0x800000, "lime" to 0x00FF00,
        "aqua" to 0x00FFFF, "cyan" to 0x00FFFF, "fuchsia" to 0xFF00FF, "magenta" to 0xFF00FF, "olive" to 0x808000,
        "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "gold" to 0xFFD700, "indigo" to 0x4B0082, "violet" to 0xEE82EE,
    )

    /** `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()` and the common names, as opaque-by-default ARGB. */
    fun color(value: String): Int? {
        val v = value.trim().lowercase()
        if (v.startsWith("#")) {
            val hex = v.drop(1)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val full = when (hex.length) {
                3, 4 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val rgb = full.substring(0, 6).toInt(16)
            val alpha = if (full.length == 8) full.substring(6).toInt(16) else 0xFF
            return (alpha shl 24) or rgb
        }
        if (v.startsWith("rgb")) {
            val parts = v.substringAfter('(').substringBefore(')').split(Regex("[,\\s/]+")).filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            fun channel(s: String) = (if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.times(2.55f) else s.toFloatOrNull())?.toInt()?.coerceIn(0, 255)
            val (r, g, b) = parts.take(3).map { channel(it) ?: return null }
            val alpha = parts.getOrNull(3)?.let { s -> (if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.div(100f) else s.toFloatOrNull()) }?.coerceIn(0f, 1f) ?: 1f
            return ((alpha * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        return NAMED[v]?.let { (0xFF shl 24) or it }
    }

    // Transforms

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    /** `m` then `n`: the matrix that applies [n] first, as nested SVG transforms do. */
    private fun multiply(m: FloatArray, n: FloatArray) = floatArrayOf(
        m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
        m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
        m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5],
    )

    fun transform(s: String): FloatArray {
        var out = IDENTITY
        for (m in Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""").findAll(s)) {
            val v = numbers(m.groupValues[2])
            val step = when (m.groupValues[1]) {
                "matrix" -> if (v.size == 6) v.toFloatArray() else continue
                "translate" -> floatArrayOf(1f, 0f, 0f, 1f, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "scale" -> { val sx = v.getOrElse(0) { 1f }; floatArrayOf(sx, 0f, 0f, v.getOrElse(1) { sx }, 0f, 0f) }
                "rotate" -> {
                    val r = v.getOrElse(0) { 0f } * PI.toFloat() / 180f
                    val rot = floatArrayOf(cos(r), sin(r), -sin(r), cos(r), 0f, 0f)
                    if (v.size >= 3) multiply(multiply(floatArrayOf(1f, 0f, 0f, 1f, v[1], v[2]), rot), floatArrayOf(1f, 0f, 0f, 1f, -v[1], -v[2])) else rot
                }
                "skewX" -> floatArrayOf(1f, 0f, tan(v.getOrElse(0) { 0f } * PI.toFloat() / 180f), 1f, 0f, 0f)
                else -> floatArrayOf(1f, tan(v.getOrElse(0) { 0f } * PI.toFloat() / 180f), 0f, 1f, 0f, 0f)
            }
            out = multiply(out, step)
        }
        return out
    }
}
