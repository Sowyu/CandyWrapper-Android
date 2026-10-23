package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Pull past the start of a list to refresh. The list's nested scroll feeds [connection]; the
 * indicator grows with the finger and says what letting go will do. Nothing moves on its own.
 */
class PullRefresh(private val thresholdPx: Float, private val onRefresh: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    val armed: Boolean get() = offset >= thresholdPx

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0 || offset <= 0f) return Offset.Zero
            val take = maxOf(available.y, -offset)
            offset += take
            return Offset(0f, take)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            offset = (offset + available.y * 0.5f).coerceAtMost(thresholdPx * 1.6f)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val fire = armed
            offset = 0f
            if (fire) onRefresh()
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberPullRefresh(onRefresh: () -> Unit): PullRefresh {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val current by rememberUpdatedState(onRefresh)
    return remember(threshold) { PullRefresh(threshold) { current() } }
}

/** The space the pull opens at the top of the list, with its hint. */
@Composable
fun PullIndicator(pull: PullRefresh, label: String) {
    if (pull.offset <= 0f) return
    val height = with(LocalDensity.current) { pull.offset.toDp() }
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Txt(if (pull.armed) "Release to $label" else "Pull to $label", CandyType.meta, Candy.colors.mutedForeground)
    }
}
