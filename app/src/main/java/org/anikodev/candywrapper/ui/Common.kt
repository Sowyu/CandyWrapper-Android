package org.anikodev.candywrapper.ui

import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.timing.SecondTicker

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
    is ConnectionState.Live -> Candy.colors.successForeground
    is ConnectionState.Offline, is ConnectionState.Blocked -> Candy.colors.errorForeground
    else -> Candy.colors.mutedForeground
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

/** `Ns`, `Nm`, `Nh Mm` (client-runtime `formatWorkingDurationLabel`), the sidebar's working time. */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/** "4s", "1m 5s", "1h 2m 3s": T3's `formatWorkingTimer` for the thread's "Working for" line. */
fun workingTimer(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    if (s < 60) return "${s}s"
    return listOfNotNull(
        (s / 3600).takeIf { it > 0 }?.let { "${it}h" },
        ((s % 3600) / 60).takeIf { it > 0 }?.let { "${it}m" },
        (s % 60).takeIf { it > 0 }?.let { "${it}s" },
    ).joinToString(" ")
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

/** Wall-clock time for the ticking labels; screenshot tests pin it. */
internal var wallClock: () -> Long = System::currentTimeMillis

private val tickSecond = mutableLongStateOf(0L)
private val secondTicker by lazy { SecondTicker(MainScope(), { wallClock() }) { tickSecond.longValue = it } }

/**
 * Whether ticking labels below are on screen. The phone sidebar stays composed while closed, so
 * it provides false then.
 */
val LocalTicking = compositionLocalOf { true }

/**
 * The shared second ticker's count while the caller is on screen and the app is started, for marks
 * that step with it (Redesign's dot grid). Only the caller recomposes, once a second.
 */
@Composable
fun rememberSecondTick(): Long {
    val ticking = LocalTicking.current
    LifecycleStartEffect(ticking) {
        if (ticking) secondTicker.acquire()
        onStopOrDispose { if (ticking) secondTicker.release() }
    }
    return tickSecond.longValue
}

/**
 * A duration since [since] that ticks each second while it is on screen and the app is started,
 * on one ticker shared by every such label. Only this text recomposes, and only when the string
 * changes; nothing ticks once the last one leaves.
 */
@Composable
fun TickingText(since: Long, format: (Long) -> String, style: TextStyle, color: Color, prefix: String = "", suffix: String = "", modifier: Modifier = Modifier, roll: Boolean = false) {
    val ticking = LocalTicking.current
    LifecycleStartEffect(ticking) {
        if (ticking) secondTicker.acquire()
        onStopOrDispose { if (ticking) secondTicker.release() }
    }
    val text by remember(since, format, prefix, suffix) {
        derivedStateOf {
            val second = tickSecond.longValue
            val now = if (secondTicker.running) second else wallClock()
            prefix + format(now - since) + suffix
        }
    }
    if (roll) RollingText(prefix, text.removePrefix(prefix), style, color, modifier) else Txt(text, style, color, modifier = modifier, maxLines = 1)
}

/**
 * Redesign's "Working for 13s" (DitherWorkingTicker): a fixed [prefix], then each character of [value]
 * in its own slot, lined up from the right, so a changed digit rolls up out of its slot as the next
 * rolls in over 320 ms. It moves only when the ticker changes the text.
 */
@Composable
fun RollingText(prefix: String, value: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier) {
        Txt(prefix, style, color, maxLines = 1)
        for ((i, ch) in value.withIndex()) {
            // Keyed from the right, where the seconds change: "9s" to "10s" rolls the 9 and adds a slot.
            androidx.compose.runtime.key(value.length - i) {
                androidx.compose.animation.AnimatedContent(
                    ch,
                    transitionSpec = {
                        val curve = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(320, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))
                        val fade = androidx.compose.animation.core.tween<Float>(320)
                        (androidx.compose.animation.slideInVertically(curve) { (it * 0.8f).toInt() } + androidx.compose.animation.fadeIn(fade)) togetherWith
                            (androidx.compose.animation.slideOutVertically(curve) { -(it * 0.8f).toInt() } + androidx.compose.animation.fadeOut(fade))
                    },
                    label = "roll",
                ) { c -> Txt(c.toString(), style, color, maxLines = 1) }
            }
        }
    }
}
