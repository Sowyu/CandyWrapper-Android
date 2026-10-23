package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.sync.ConnectionState

/** Screen header: a back button when [onBack] is set, a title, and actions on the right. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("‹ Back", color = c.foreground) }
        } else {
            Box(Modifier.size(12.dp))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
    HorizontalDivider(color = c.border)
}

/** One plain-text action for a [TopBar]. */
@Composable
fun BarAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Candy.colors.primary) }
}

/** A short line naming the real connection state: the step in progress, the route, or the error. */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.Idle -> "Waiting to connect"
    is ConnectionState.Connecting -> "Connecting · ${step.substringBefore('[').replace('.', ' ')}"
    is ConnectionState.Live -> "Live via ${if (route == "tunnel") "T3 Connect" else route} · ${connectMs} ms"
    is ConnectionState.Offline -> message
    is ConnectionState.Blocked -> message
}

@Composable
fun ConnectionState.color(): Color = when (this) {
    is ConnectionState.Live -> Candy.colors.success
    is ConnectionState.Offline, is ConnectionState.Blocked -> Candy.colors.error
    else -> Candy.colors.mutedForeground
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** The sidebar pill. Ready threads show nothing, or a dot when unread. */
@Composable
fun PillChip(pill: StatusPill, unread: Boolean, workingSince: Long, now: Long) {
    val c = Candy.colors
    val (text, color) = when (pill) {
        StatusPill.WORKING -> (if (workingSince > 0) "Working ${duration(now - workingSince)}" else "Working") to c.info
        StatusPill.WAITING -> "Waiting" to c.mutedForeground
        StatusPill.APPROVAL -> "Approval" to c.warning
        StatusPill.INPUT -> "Input" to c.warning
        StatusPill.FAILED -> "Failed" to c.error
        StatusPill.LIMITED -> "Limited" to c.warning
        StatusPill.READY, StatusPill.NONE -> {
            if (unread) StatusDot(c.primary)
            return
        }
    }
    Text(
        text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Sidebar time: "now", "5m", "3h", "2d" (apps/web/src/timestampFormat.ts:235-248, " ago" stripped). */
fun relativeTime(atMs: Long, now: Long): String {
    if (atMs <= 0) return ""
    val s = (now - atMs) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

/** `Ns`, `Nm`, `Nh Mm`. */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/**
 * Wall-clock time that advances on the minute while the screen is started, for time labels.
 * Nothing ticks in the background.
 */
@Composable
fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000 - now % 60_000)
            }
        }
    }
    return now
}

/** A full-width row that reacts to taps. */
fun Modifier.rowClick(onClick: () -> Unit) = clickable(role = Role.Button, onClick = onClick)

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 0.6.sp, color = Candy.colors.mutedForeground, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun Hint(text: String, color: Color = Candy.colors.mutedForeground) {
    Text(text, fontSize = 13.sp, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

val ButtonRow = Arrangement.spacedBy(8.dp)
