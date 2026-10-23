package org.anikodev.candywrapper.core.orchestrator

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Grok Bot's shapes and capsule eyes; eye logic from github.com/njpatel/omabot, Apache-2.0.
 * Outlines inflate into cushion slices. Eyes sit on the dome and turn with the head.
 * Geometry uses fractions of the radius, with y up until conversion to a canvas pose.
 */
enum class BotShape(val label: String, val thickness: Double, val eyeCenterY: Double = 0.02, val eyeSpread: Double = 0.27) {
    CIRCLE("Circle", 1.0), CLOUD("Cloud", 0.75), TILE("Tilted tile", 0.65),
    STAR("Four-point star", 0.5, eyeSpread = 0.15), CLOVER("Clover", 0.6),
    HEART("Heart blob", 0.7, 0.06), FLOWER("Flower", 0.6), DROP("Drop", 0.8, -0.22, 0.2),
    BEAN("Bean", 0.75, -0.04), TRIANGLE("Rounded triangle", 0.65, -0.24, 0.2),
    CHUNK("Chunk", 0.8), SPLAT("Splat", 0.55), HEXAGON("Hexagon", 0.7), PUMPKIN("Pumpkin", 0.85, -0.04)
}

/** Grok Bot's eleven sRGB picker colours. */
enum class BotColor(val rgb: Int) {
    WHITE(0xF1F1EF), BROWN(0xA77250), RED(0xF2554B), ORANGE(0xFF8A3A), AMBER(0xFFC53D),
    GREEN(0x5BCB6B), TEAL(0x37C6B3), BLUE(0x4A9BFF), VIOLET(0x9C7BFF), PINK(0xFF7EB8), GREY(0x9EA3A8)
}

/** The Orchestrator's picked look. Pumpkins are always orange. */
@ConsistentCopyVisibility
data class BotIdentity private constructor(val shape: BotShape, val color: BotColor) {
    init { require(shape != BotShape.PUMPKIN || color == BotColor.ORANGE) }

    companion object {
        fun of(shape: BotShape, color: BotColor) =
            BotIdentity(shape, if (shape == BotShape.PUMPKIN) BotColor.ORANGE else color)
        val ORCHESTRATOR = of(BotShape.FLOWER, BotColor.VIOLET)
        fun fromRaw(shape: Int?, color: Int?) = of(
            shape?.let { BotShape.entries.getOrNull(it) } ?: ORCHESTRATOR.shape,
            color?.let { BotColor.entries.getOrNull(it) } ?: ORCHESTRATOR.color,
        )
    }
}

/** Yaw turns right and pitch turns up, in radians. */
data class BotGaze(val yaw: Double, val pitch: Double) {
    companion object { val FRONT = BotGaze(0.0, 0.0) }
}

enum class BotMood {
    IDLE, WORKING, NEEDS_YOU, UNREAD, BLOCKED, ARCHIVED;

    /** Each identity looks slightly off centre at rest. */
    fun restingGaze(identity: BotIdentity): BotGaze = when (this) {
        NEEDS_YOU -> BotGaze(0.0, 0.5)
        BLOCKED, ARCHIVED -> BotGaze(0.0, -0.45)
        else -> {
            val h = identity.shape.ordinal.toUInt() * 31u + identity.color.ordinal.toUInt() * 17u
            BotGaze((h.rem(7u).toDouble() - 3) * 0.05, (h.rem(5u).toDouble() - 2) * 0.03)
        }
    }
}

/** Closed polygons, without a repeated closing vertex, in canvas coordinates with y down. */
class BotPose(val body: List<FloatArray>, val eyes: List<FloatArray>, val stem: FloatArray?, val lineWidth: Float)

object BotGeometry {
    const val strokeWidth = 0.14
    const val eyeMargin = 0.09
    const val maxTurn = 0.9
    private const val outlinePoints = 64
    private const val sliceCount = 14
    private const val eyePoints = 16
    private const val directions = 32
    private const val clampShare = 0.94
    private const val eyeWidth = 0.25
    private const val eyeHeight = 0.5

    private data class P(val x: Double, val y: Double) {
        operator fun plus(p: P) = P(x + p.x, y + p.y)
        operator fun minus(p: P) = P(x - p.x, y - p.y)
        operator fun times(s: Double) = P(x * s, y * s)
        fun squared() = x * x + y * y
    }
    private data class P3(val x: Double, val y: Double, val z: Double) {
        operator fun minus(p: P3) = P3(x - p.x, y - p.y, z - p.z)
    }
    private data class Slice(val z: Double, val scale: Double)
    private data class EyePoint(val p: P, val facing: Boolean)
    private val outlineCache = ConcurrentHashMap<BotShape, List<P>>()
    private val limitCache = ConcurrentHashMap<BotShape, DoubleArray>()

    /** Centre on the bounding box and scale the farthest point to radius one. */
    private fun outline(shape: BotShape): List<P> = outlineCache.computeIfAbsent(shape) {
        val pts = List(outlinePoints) { raw(shape, it.toDouble() / outlinePoints * 2 * PI) }
        val c = P((pts.minOf { it.x } + pts.maxOf { it.x }) / 2, (pts.minOf { it.y } + pts.maxOf { it.y }) / 2)
        val centered = pts.map { it - c }
        val r = sqrt(centered.maxOf { it.squared() })
        centered.map { it * (1 / r) }
    }

    /** Outline point before centring and scaling. */
    private fun raw(shape: BotShape, t: Double): P {
        fun polar(r: Double) = P(cos(t) * r, sin(t) * r)
        return when (shape) {
            BotShape.CIRCLE -> polar(1.0)
            BotShape.CLOUD -> {
                var r = 0.8 + 0.2 * sqrt(0.5 + 0.5 * cos(5 * (t - PI / 2)))
                if (sin(t) < -0.45) r = min(r, 0.72 / -sin(t))
                polar(r)
            }
            BotShape.TILE -> {
                val a = t - 0.21
                polar((abs(cos(a)).pow(4) + abs(sin(a)).pow(4)).pow(-0.25))
            }
            BotShape.STAR -> polar(0.46 + 0.54 * abs(cos(2 * t)).pow(3))
            BotShape.CLOVER -> polar(0.6 + 0.4 * abs(sin(2 * t)).pow(0.55))
            BotShape.HEART -> {
                val heart = P(16 * sin(t).pow(3) / 16, (13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)) / 16)
                val circle = P(sin(t), cos(t)) * 0.95
                heart * 0.68 + circle * 0.32
            }
            BotShape.FLOWER -> polar(0.7 + 0.3 * abs(cos(3 * t)).pow(0.6))
            BotShape.DROP -> P(-sin(t) * sin(t / 2).pow(1.3) * 0.95, cos(t))
            BotShape.BEAN -> {
                val x = cos(t)
                val y = 0.7 * sin(t) - 0.24 * max(0.0, sin(t)) * exp(-cos(t) * cos(t) / 0.14)
                val a = -0.26
                P(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
            }
            BotShape.TRIANGLE -> {
                val sector = 2 * PI / 3
                var a = (t - PI / 2) % sector
                if (a < 0) a += sector
                polar((cos(PI / 3) / cos(a - PI / 3)).pow(0.8))
            }
            BotShape.CHUNK -> {
                val radii = doubleArrayOf(1.0, 0.86, 0.95, 0.79, 0.93, 0.83, 0.97)
                val f = t / (2 * PI) * radii.size
                val i = f.toInt() % radii.size
                val j = (i + 1) % radii.size
                val s = (1 - cos((f - floor(f)) * PI)) / 2
                polar(radii[i] * (1 - s) + radii[j] * s)
            }
            BotShape.SPLAT -> polar(0.7 + 0.3 * (0.5 + 0.5 * cos(9 * t)).pow(2) * (0.72 + 0.28 * sin(2 * t + 1)))
            BotShape.HEXAGON -> {
                val sector = PI / 3
                var a = t % sector
                if (a < 0) a += sector
                polar(cos(PI / 6) / cos(a - PI / 6))
            }
            BotShape.PUMPKIN -> {
                val ribs = 0.94 + 0.06 * cos(4 * t).pow(2)
                P(cos(t) * ribs, 0.8 * sin(t) * ribs)
            }
        }
    }

    /** Cast a ray from the centre to find the outline's radius. */
    private fun radius(shape: BotShape, at: Double): Double {
        val pts = outline(shape)
        val d = P(cos(at), sin(at))
        var best = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val e = pts[(i + 1) % pts.size] - p
            val den = d.x * e.y - d.y * e.x
            if (abs(den) <= 1e-12) continue
            val s = (p.x * e.y - p.y * e.x) / den
            val u = (p.x * d.y - p.y * d.x) / den
            if (s > 0 && u >= -1e-9 && u <= 1 + 1e-9) best = max(best, s)
        }
        return best
    }

    /** Front-to-back depth and scale follow a half ellipse. */
    private fun slices(shape: BotShape) = List(sliceCount) { k ->
        val phi = PI * (k + 0.5) / sliceCount
        Slice(shape.thickness * cos(phi), sin(phi))
    }

    private fun rotate(p: P3, g: BotGaze): P3 {
        val cy = cos(g.yaw); val sy = sin(g.yaw)
        val cp = cos(g.pitch); val sp = sin(g.pitch)
        val x = p.x * cy + p.z * sy
        val z1 = -p.x * sy + p.z * cy
        return P3(x, p.y * cp + z1 * sp, -p.y * sp + z1 * cp)
    }

    private fun projectedSlices(shape: BotShape, g: BotGaze): List<List<P>> {
        val pts = outline(shape)
        return slices(shape).map { slice ->
            pts.map { p ->
                val r = rotate(P3(p.x * slice.scale, p.y * slice.scale, slice.z), g)
                P(r.x, r.y)
            }
        }
    }

    /** Lift a point of the front plane onto the dome. */
    private fun lift(shape: BotShape, p: P): P3 {
        val len = sqrt(p.squared())
        val edge = if (len < 1e-9) 1.0 else radius(shape, atan2(p.y, p.x))
        val rho = min(1.0, len / max(edge, 1e-9))
        return P3(p.x, p.y, shape.thickness * sqrt(max(0.0, 1 - rho * rho)))
    }

    /** Capsule top cap followed by bottom cap; straight sides join them. */
    private fun eyeOutlines(shape: BotShape): List<List<P>> {
        val r = eyeWidth / 2
        val half = eyePoints / 2
        return listOf(-1.0, 1.0).map { side ->
            val c = P(side * shape.eyeSpread, shape.eyeCenterY)
            List(eyePoints) { i ->
                val top = i < half
                val a = (if (top) 0.0 else PI) + PI * (i % half) / (half - 1)
                c + P(cos(a) * r, (if (top) eyeHeight / 2 - r else -eyeHeight / 2 + r) + sin(a) * r)
            }
        }
    }

    /** Each eye point turns with the face, retaining whether its normal faces the viewer. */
    private fun projectedEyes(shape: BotShape, g: BotGaze) = eyeOutlines(shape).map { eye ->
        eye.map { q ->
            val a = lift(shape, q)
            val dx = lift(shape, q + P(0.01, 0.0)) - a
            val dy = lift(shape, q + P(0.0, 0.01)) - a
            val n = rotate(P3(dx.y * dy.z - dx.z * dy.y, dx.z * dy.x - dx.x * dy.z, dx.x * dy.y - dx.y * dy.x), g)
            val len = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
            val r = rotate(a, g)
            EyePoint(P(r.x, r.y), len > 0 && n.z / len > 0.2)
        }
    }

    /** Distance inside the polygon boundary, negative outside. */
    private fun insideDistance(p: P, poly: List<P>): Double {
        var inside = false
        var best = Double.POSITIVE_INFINITY
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[j]; val b = poly[i]
            if ((b.y > p.y) != (a.y > p.y) && p.x < (a.x - b.x) * (p.y - b.y) / (a.y - b.y) + b.x) inside = !inside
            val e = a - b; val w = p - b
            val u = max(0.0, min(1.0, (w.x * e.x + w.y * e.y) / max(e.squared(), 1e-12)))
            best = min(best, (w - e * u).squared())
            j = i
        }
        return if (inside) sqrt(best) else -sqrt(best)
    }

    private val sliceOrder = (0 until sliceCount).sortedBy { abs(it - sliceCount / 2) }

    /** The deepest containing slice safely bounds distance inside the union of slices. */
    fun eyesClear(shape: BotShape, gaze: BotGaze): Boolean {
        val body = projectedSlices(shape, gaze)
        val need = eyeMargin - strokeWidth / 2
        for (eye in projectedEyes(shape, gaze)) for (point in eye) {
            if (!point.facing) return false
            if (sliceOrder.none { insideDistance(point.p, body[it]) >= need }) return false
        }
        return true
    }

    /** Walk outward to the first failing pose, then refine with five bisections. */
    fun turnLimits(shape: BotShape): DoubleArray = cachedLimits(shape).copyOf()

    private fun cachedLimits(shape: BotShape): DoubleArray = limitCache.computeIfAbsent(shape) {
        DoubleArray(directions) { j ->
            val phi = 2 * PI * j / directions
            fun ok(m: Double) = eyesClear(shape, BotGaze(cos(phi) * m, sin(phi) * m))
            if (!ok(0.0)) return@DoubleArray 0.0
            var pass = 0.0
            var fail: Double? = null
            var m = 0.05
            while (m <= maxTurn + 1e-9) {
                if (ok(m)) pass = m else { fail = m; break }
                m += 0.05
            }
            var hi = fail ?: return@DoubleArray maxTurn
            var lo = pass
            repeat(5) {
                val mid = (lo + hi) / 2
                if (ok(mid)) lo = mid else hi = mid
            }
            lo
        }
    }

    /** Keep the direction, using at most 94% of the tighter neighbouring turn limit. */
    fun clamp(gaze: BotGaze, shape: BotShape): BotGaze {
        val m = sqrt(gaze.yaw * gaze.yaw + gaze.pitch * gaze.pitch)
        if (m <= 1e-9) return gaze
        val limits = cachedLimits(shape)
        var phi = atan2(gaze.pitch, gaze.yaw)
        if (phi < 0) phi += 2 * PI
        val i = (phi / (2 * PI) * directions).toInt() % directions
        val limit = min(limits[i], limits[(i + 1) % directions]) * clampShare
        return if (m <= limit) gaze else BotGaze(gaze.yaw / m * limit, gaze.pitch / m * limit)
    }

    /** A short post on top of the pumpkin. */
    private fun stem(g: BotGaze) = listOf(
        P3(0.02 - 0.09, 0.74, 0.1), P3(0.1 - 0.09 * 0.8, 1.02, 0.1),
        P3(0.1 + 0.09 * 0.8, 1.02, 0.1), P3(0.02 + 0.09, 0.74, 0.1),
    ).map { val r = rotate(it, g); P(r.x, r.y) }

    /** Clamp first and reserve room for the rounding stroke and pumpkin stem. */
    fun pose(identity: BotIdentity, gaze: BotGaze, size: Float): BotPose {
        val g = clamp(gaze, identity.shape)
        val pumpkin = identity.shape == BotShape.PUMPKIN
        val radius = size.toDouble() / 2 / (1 + strokeWidth / 2 + if (pumpkin) 0.08 else 0.0)
        val cx = size.toDouble() / 2
        val cy = size.toDouble() / 2 - if (pumpkin) radius * 0.08 else 0.0
        fun polygon(pts: List<P>) = FloatArray(pts.size * 2) { i ->
            val p = pts[i / 2]
            (if (i % 2 == 0) cx + p.x * radius else size - (cy + p.y * radius)).toFloat()
        }
        return BotPose(
            projectedSlices(identity.shape, g).map(::polygon),
            projectedEyes(identity.shape, g).map { eye -> polygon(eye.map { it.p }) },
            if (pumpkin) polygon(stem(g)) else null,
            lineWidth(identity, size),
        )
    }

    /** Width of the rounding stroke at the requested canvas size. */
    fun lineWidth(identity: BotIdentity, size: Float): Float =
        (size.toDouble() / 2 / (1 + strokeWidth / 2 + if (identity.shape == BotShape.PUMPKIN) 0.08 else 0.0) * strokeWidth).toFloat()
}
