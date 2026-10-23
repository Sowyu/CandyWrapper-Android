package org.anikodev.candywrapper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.sync.ConnectionState

/** A short line naming the real connection state: the step in progress, the route, or the error. */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.Idle -> "Waiting to connect"
    is ConnectionState.Connecting -> "Connecting · ${step.substringBefore('[').replace('.', ' ')}"
    is ConnectionState.Live -> "Live via ${if (route == "tunnel") "T3 Connect" else route} · $connectMs ms"
    is ConnectionState.Offline -> message
    is ConnectionState.Blocked -> message
}

@Composable
fun ConnectionState.color(): Color = when (this) {
    is ConnectionState.Live -> Candy.colors.success
    is ConnectionState.Offline, is ConnectionState.Blocked -> Candy.colors.error
    else -> Candy.colors.mutedForeground
}

/** The sidebar status (protocol.md 7.9). Ready threads show nothing, or a dot when unread. */
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
            if (unread) Pill("Done", c.success)
            return
        }
    }
    Pill(text, color)
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

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayAndTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

/** A message timestamp: "14:02" today, "Tue 21 Oct, 14:02" before. */
fun clockTime(atMs: Long): String {
    if (atMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) timeOnly.format(at) else dayAndTime.format(at)
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
