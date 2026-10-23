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
import androidx.compose.ui.draw.shadow
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
 * Capy's machine row under the composer (DitherDeviceRowView): the computer's name with its
 * connection dot, muted. The Mac's Worktree/Local half needs the checkout, which the phone does not
 * read, so the machine stands alone.
 */
@Composable
fun RowScope.DeviceRow(machine: String, live: Boolean) {
    val c = Candy.colors
    Row(
        Modifier.weight(1f).heightIn(min = Pill.height).semantics { contentDescription = "$machine, ${if (live) "connected" else "not connected"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(if (live) c.success else c.mutedForeground, size = 7.dp)
        Txt(machine, CandyType.sm, c.secondaryLabel, maxLines = 1)
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
