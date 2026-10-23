package org.anikodev.candywrapper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.anikodev.candywrapper.core.sync.TimelineItem
import org.anikodev.candywrapper.core.sync.TimelineRow

// Redesign's floating chrome around the composer and in the thread header, after CandyWrapper's
// DitherPill: frosted, a 1dp rim, a soft shadow, control corners. Nothing here draws while idle.

/** DitherPill's metrics: 30dp pills with control corners, 8dp between the composer and a row. */
object Pill {
    val height = 30.dp
    val gap = 8.dp
    /** A row of pills sits this far in from the composer's sides, inside its 16dp curve. */
    val inset = 8.dp
}

/** Redesign's floating surface: shadow, frost and rim on [shape]. */
@Composable
fun Modifier.floating(shape: Shape, tint: Color = Candy.colors.paneTint()): Modifier {
    val c = Candy.colors
    return shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = if (c.dark) 0.28f else 0.08f), spotColor = Color.Black.copy(alpha = if (c.dark) 0.28f else 0.08f))
        .frost(shape, tint).border(1.dp, c.rim(), shape)
}

/**
 * Capy's 3x3 dot grid. [sweep] is the working mark (3dp dots 2dp apart): each second of the shared
 * ticker sends one light across it from the top left over 600 ms, then it rests, so it steps with
 * "Working for" and stops when that does. [wave] is the waiting mark (2dp dots on a 3.5dp pitch);
 * at rest both hold still at half strength.
 */
@Composable
fun DotGrid(color: Color, modifier: Modifier = Modifier, running: Boolean = true, small: Boolean = false) {
    val phase = remember { Animatable(1f) }
    if (running) {
        val tick = rememberSecondTick()
        LaunchedEffect(tick) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(600, easing = LinearEasing))
        }
    }
    val dot = if (small) 2.dp else 3.dp
    val pitch = if (small) 3.5.dp else 5.dp
    Canvas(modifier.size(16.dp)) {
        val d = dot.toPx()
        val p = pitch.toPx()
        val side = 2 * p + d
        val x0 = (size.width - side) / 2
        val y0 = (size.height - side) / 2
        val t = phase.value
        for (i in 0 until 9) {
            val col = i % 3
            val row = i / 3
            // The light reaches each diagonal in turn: 0 at the top left to 4 at the bottom right.
            val step = (col + row) / 4f
            val alpha = if (!running || t >= 1f) 0.5f else {
                val local = t - step * 0.6f
                when {
                    local < 0f -> 0.25f
                    local < 0.2f -> 0.25f + 0.75f * (local / 0.2f)
                    local < 0.4f -> 1f - 0.75f * ((local - 0.2f) / 0.2f)
                    else -> 0.25f
                }
            }
            drawRoundRect(color.copy(alpha = color.alpha * alpha), Offset(x0 + col * p, y0 + row * p), Size(d, d), CornerRadius(d / 2))
        }
    }
}

/**
 * Redesign's quiet indicator under the composer (DitherWaitingView): the waiting dot grid and a
 * short label on a pill. A tap opens [menu].
 */
@Composable
fun WaitingPill(label: String, menu: @Composable (open: Boolean, close: () -> Unit) -> Unit) {
    val c = Candy.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box {
        Row(
            Modifier.height(Pill.height).floating(shape)
                .clickable(source, null, role = Role.Button, onClickLabel = "Show what it waits on") { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DotGrid(c.foreground.copy(alpha = 0.7f), Modifier.size(9.dp), running = true, small = true)
            Txt(label, CandyType.xsMedium, if (pressed || open) c.foreground else c.secondaryLabel, maxLines = 1)
        }
        menu(open) { open = false }
    }
}

/**
 * Capy's machine row under the composer (DitherDeviceRowView): a frosted pill with the laptop glyph,
 * the computer's name, muted, and its connection dot. The Mac's Worktree/Local half and its menu need
 * the checkout, which the phone does not read, so the machine stands alone.
 */
@Composable
fun RowScope.DeviceRow(machine: String, live: Boolean) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.height(Pill.height).floating(shape).padding(horizontal = 10.dp)
                .semantics { contentDescription = "$machine, ${if (live) "connected" else "not connected"}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Laptop, c.mutedForeground, 14.dp)
            Txt(machine, CandyType.sm, c.secondaryLabel, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            StatusDot(if (live) c.success else c.mutedForeground, size = 7.dp)
        }
    }
}

/** The last run's changed lines, read from the newest checkpoint row, for the header pill. */
data class DiffTotals(val checkpointId: String, val title: String, val additions: Long, val deletions: Long)

private val diffLine = Regex("""\+(\d+) -(\d+)$""")

fun latestDiff(items: List<TimelineItem>): DiffTotals? {
    for (i in items.indices.reversed()) {
        val row = (items[i] as? TimelineRow)?.row ?: continue
        val id = row.checkpointId ?: continue
        var add = 0L
        var del = 0L
        for (line in row.body.orEmpty().lineSequence()) diffLine.find(line.trim())?.let { add += it.groupValues[1].toLong(); del += it.groupValues[2].toLong() }
        return DiffTotals(id, row.text ?: "Changes", add, del)
    }
    return null
}

/**
 * Redesign's header pill (DitherHeaderPillView), adapted to a phone that has no tab band: one slim
 * frosted pill at the header's right holding the last run's diff totals, which open the diff, and the
 * thread's ⋯ actions. The Mac's cost, terminal and right panel controls have no data or panel here.
 */
@Composable
fun HeaderPill(diff: DiffTotals?, onDiff: (DiffTotals) -> Unit, actions: @Composable () -> Unit) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier.padding(end = 8.dp).height(36.dp).floating(shape).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (diff != null && (diff.additions > 0 || diff.deletions > 0)) {
            val tnum = CandyType.xsMedium.merge(TextStyle(fontFeatureSettings = "tnum"))
            Row(
                Modifier.fillMaxHeight().clickable(role = Role.Button, onClickLabel = "Show changes") { onDiff(diff) }
                    .semantics { contentDescription = "${diff.additions} lines added, ${diff.deletions} removed. Show changes." }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt("+${diff.additions}", tnum, c.diffAdditionForeground, maxLines = 1)
                Txt("-${diff.deletions}", tnum, c.diffDeletionForeground, maxLines = 1)
            }
            Box(Modifier.width(1.dp).height(16.dp).background(c.rim()))
        }
        actions()
    }
}

/** A pill-sized ghost icon control for the header pill, inside a 44dp touch target. */
@Composable
fun PillIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, box: Dp = 32.dp, onClick: () -> Unit) =
    IconButton(icon, description, tint = Candy.colors.foreground, size = 16.dp, box = box, onClick = onClick)

/** Anthropic's "A" mark: Redesign draws Claude as its provider with its maker's logo, like OpenAI's for Codex. */
val AnthropicMark: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("anthropic", 24.dp, 24.dp, 24f, 24f).addPath(
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M17.3041 3.541h-3.6718l6.696 16.918H24Zm-10.6082 0L0 20.459h3.7442l1.3693-3.5527h7.0052l1.3693 3.5528h3.7442L10.5363 3.5409Zm-.3712 10.2232 2.2914-5.9456 2.2914 5.9456Z",
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
    ).build()
}

/** A provider's glyph as Redesign's picker draws it: Anthropic's mark for Claude, in the foreground. */
@Composable
fun providerMark(driver: String?): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> = when (driver) {
    null, "codex", "openai" -> providerIcon(driver)
    else -> AnthropicMark to Candy.colors.foreground
}

/**
 * Redesign's provider card at the top of the model list (DitherProviderCard): the glyph, the name and
 * a status dot, then the e-mail, blurred until tapped, and the plan, then the most-used limit window
 * as a thin bar with "N% used". Lines without data stay out. Below Android 12 there is no blur, so
 * the hidden e-mail shows as dots.
 */
@Composable
fun ProviderCard(p: org.anikodev.candywrapper.core.model.ProviderInfo) {
    val c = Candy.colors
    var reveal by remember(p.instanceId) { mutableStateOf(false) }
    val (glyph, tint) = providerMark(p.driver)
    val statusColor = when (p.status) {
        "ready" -> c.success
        "warning" -> c.warning
        "error" -> c.error
        else -> c.mutedForeground
    }
    val statusText = when (p.status) {
        "ready" -> "Connected"
        "warning" -> p.message ?: "Needs attention"
        "error" -> p.message ?: "Not connected"
        else -> p.message ?: "Disabled"
    }
    androidx.compose.foundation.layout.Column(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)
            .semantics { contentDescription = listOfNotNull(p.name, statusText, p.authLabel, p.usage?.let { "${it.usedPercent}% used" }).joinToString() },
    ) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(glyph, tint, 18.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            Txt(p.name, CandyType.smMedium, c.foreground, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            StatusDot(statusColor, size = 7.dp)
        }
        if (p.authEmail != null || p.authLabel != null) {
            Row(Modifier.padding(start = 28.dp).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                p.authEmail?.let { email ->
                    val blur = android.os.Build.VERSION.SDK_INT >= 31
                    val shown = if (reveal || blur) email else "•".repeat(minOf(email.length, 12))
                    Txt(
                        shown, CandyType.xs, c.mutedForeground, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                            .clickable(role = Role.Button, onClickLabel = if (reveal) "Hide e-mail" else "Show e-mail") { reveal = !reveal }
                            .let { if (blur && !reveal) it.blur(4.dp) else it },
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                }
                p.authLabel?.let { Txt(if (p.authEmail != null) "· $it" else it, CandyType.xs, c.mutedForeground, maxLines = 1) }
            }
        }
        p.usage?.let { u ->
            Row(Modifier.padding(start = 28.dp, top = 2.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(c.foreground.copy(alpha = 0.1f))) {
                    Box(Modifier.fillMaxWidth(u.usedPercent / 100f).height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(c.primary))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                Txt("${u.usedPercent}% used", CandyType.xsMedium, c.foreground, maxLines = 1)
            }
        }
    }
}
