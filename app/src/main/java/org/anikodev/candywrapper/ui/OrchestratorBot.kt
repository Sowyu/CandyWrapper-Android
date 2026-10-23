package org.anikodev.candywrapper.ui

import android.provider.Settings
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.orchestrator.BotColor
import org.anikodev.candywrapper.core.orchestrator.BotGaze
import org.anikodev.candywrapper.core.orchestrator.BotGeometry
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.BotMood
import org.anikodev.candywrapper.core.orchestrator.BotPose

/**
 * The poses a bot draws, worked out once per identity, gaze and pixel size on `Dispatchers.Default` (the first pose
 * of a shape also works out its turn limits) and kept for reuse: a sidebar row redraws from cached polygons.
 */
private object BotPoses {
    private val cache = LruCache<String, BotPose>(96)

    fun cached(identity: BotIdentity, gaze: BotGaze, px: Float): BotPose? = cache.get(key(identity, gaze, px))

    fun get(identity: BotIdentity, gaze: BotGaze, px: Float): BotPose =
        cache.get(key(identity, gaze, px)) ?: BotGeometry.pose(identity, gaze, px).also { cache.put(key(identity, gaze, px), it) }

    private fun key(identity: BotIdentity, gaze: BotGaze, px: Float) = "${identity.shape}:${identity.color}:${gaze.yaw}:${gaze.pitch}:$px"
}

/** The slow look to each side a bot plays once when its turn starts (CandyWrapper BotView.lookAround). */
private val LOOK = listOf(BotGaze(-0.55, 0.05), BotGaze(0.55, 0.05))

/**
 * One bot: a flat shape with two dark capsule eyes, drawn from precomputed polygons. Its face carries the [mood]:
 * a resting gaze, looking up with an amber "!" when it needs you, looking down when blocked, grey when archived.
 * A state change plays one flourish under a second and stops: nothing animates while nothing changes, and with
 * animations off the face changes without moving.
 */
@Composable
fun Bot(identity: BotIdentity, mood: BotMood, size: Dp, modifier: Modifier = Modifier, unread: Int = 0, description: String? = null) {
    val px = with(LocalDensity.current) { size.toPx() }
    val rest = mood.restingGaze(identity)
    val poses by produceState(listOfNotNull(BotPoses.cached(identity, rest, px)), identity, rest, px) {
        value = withContext(Dispatchers.Default) { (listOf(rest) + LOOK).map { BotPoses.get(identity, it, px) } }
    }
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // 0 is rest; the look runs 0 → 1 → 2 → 3 (back to rest) over 900 ms. The hop is a height fraction.
    val look = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    // The first mood is where the face starts, not a change to play.
    val seen = remember { BooleanArray(1) }
    LaunchedEffect(mood) {
        if (!seen[0]) { seen[0] = true; return@LaunchedEffect }
        if (still) return@LaunchedEffect
        when (mood) {
            BotMood.WORKING -> { look.snapTo(0f); look.animateTo(3f, tween(900, easing = Motion.Ease)); look.snapTo(0f) }
            BotMood.NEEDS_YOU -> { hop.snapTo(0f); hop.animateTo(1f, tween(550)); hop.snapTo(0f) }
            else -> Unit
        }
    }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(size).semantics { if (description != null) contentDescription = description }) {
        val list = poses
        if (list.isEmpty()) return@Canvas
        val pose = if (look.value <= 0f || list.size < 3) list[0] else lookPose(list, look.value)
        val lift = hopOffset(hop.value) * this.size.height * 0.18f
        val color = if (mood == BotMood.ARCHIVED) BotColor.GREY else identity.color
        val fill = Color(0xFF000000 or color.rgb.toLong()).copy(alpha = if (mood == BotMood.ARCHIVED) 0.7f else 1f)
        translate(top = -lift) { drawPose(pose, fill) }
        val badge = when {
            mood == BotMood.NEEDS_YOU -> "!" to Color(0xFFFFB829)
            unread > 0 -> (if (unread > 9) "9+" else "$unread") to Color(0xFF458CFF)
            else -> null
        }
        if (badge != null) {
            val d = maxOf(9.dp.toPx(), this.size.height * 0.42f)
            val text = measurer.measure(badge.first, TextStyle(fontSize = (d * 0.72f / density / fontScale).sp, fontWeight = FontWeight.Bold,
                color = if (mood == BotMood.NEEDS_YOU) Color.Black else Color.White))
            val w = maxOf(d, text.size.width + d * 0.4f)
            val left = this.size.width - w * 0.75f
            drawRoundRect(badge.second, androidx.compose.ui.geometry.Offset(left, -d * 0.2f), androidx.compose.ui.geometry.Size(w, d), androidx.compose.ui.geometry.CornerRadius(d / 2))
            drawText(text, topLeft = androidx.compose.ui.geometry.Offset(left + (w - text.size.width) / 2, -d * 0.2f + (d - text.size.height) / 2))
        }
    }
}

/** `[0, h, 0, 0.3h, 0]` over the hop, as the Mac's keyframes. */
private fun hopOffset(t: Float): Float = when {
    t <= 0f || t >= 1f -> 0f
    t < 0.3f -> t / 0.3f
    t < 0.6f -> 1f - (t - 0.3f) / 0.3f
    t < 0.8f -> (t - 0.6f) / 0.2f * 0.3f
    else -> 0.3f * (1f - (t - 0.8f) / 0.2f)
}

/** Poses share their point counts, so a look between two of them is a point-by-point blend. */
private fun lookPose(list: List<BotPose>, t: Float): BotPose {
    val order = listOf(0, 1, 2, 0)
    val i = t.toInt().coerceAtMost(2)
    val f = t - i
    val a = list[order[i]]
    val b = list[order[i + 1]]
    fun mix(x: FloatArray, y: FloatArray) = FloatArray(x.size) { k -> x[k] + (y[k] - x[k]) * f }
    return BotPose(a.body.zip(b.body, ::mix), a.eyes.zip(b.eyes, ::mix), a.stem?.let { s -> b.stem?.let { mix(s, it) } }, a.lineWidth)
}

private fun DrawScope.drawPose(pose: BotPose, fill: Color) {
    fun path(polys: List<FloatArray>) = Path().apply {
        fillType = PathFillType.NonZero
        for (p in polys) {
            if (p.size < 4) continue
            moveTo(p[0], p[1])
            var k = 2
            while (k < p.size) { lineTo(p[k], p[k + 1]); k += 2 }
            close()
        }
    }
    // The union of the slices, rounded by a same-colour stroke with round joins.
    val body = path(pose.body)
    drawPath(body, fill, style = Fill)
    drawPath(body, fill, style = Stroke(pose.lineWidth, join = StrokeJoin.Round))
    pose.stem?.let { s ->
        val stem = path(listOf(s))
        val green = Color(0xFF5C7838)
        drawPath(stem, green)
        drawPath(stem, green, style = Stroke(size.minDimension * 0.05f, join = StrokeJoin.Round))
    }
    drawPath(path(pose.eyes), Color(0xFF141417))
}

/** Works out a bot's poses now, on the calling thread, so a screenshot draws it on its first frame. */
internal fun warmBotPoses(identity: BotIdentity, mood: BotMood, px: Float) {
    val rest = mood.restingGaze(identity)
    for (g in listOf(rest) + LOOK) BotPoses.get(identity, g, px)
}
