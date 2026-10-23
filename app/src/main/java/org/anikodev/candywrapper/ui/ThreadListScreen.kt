package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.BlockReason
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.timing.Timeline

@Composable
fun ThreadListScreen(graph: AppGraph, nav: Navigator, envId: String) {
    val session = graph.environments.session(envId)
    if (session == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val env by session.env.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val model by session.threads.collectAsStateWithLifecycle()
    val now = rememberMinuteClock()
    val c = Candy.colors
    Column(Modifier.fillMaxSize()) {
        TopBar(env.label, onBack = { nav.pop() }, subtitle = state.label())
        val blocked = state as? ConnectionState.Blocked
        if (blocked != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(blocked.message, color = c.error, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(8.dp))
                when (blocked.reason) {
                    BlockReason.SIGN_IN -> BarAction("Sign in") { nav.push(Screen.Connect) }
                    BlockReason.PAIR_AGAIN -> BarAction("Pair") { nav.push(Screen.Pair()) }
                    else -> BarAction("Retry") { session.wake() }
                }
            }
        }
        if (model.threadCount == 0) {
            Hint(if (state is ConnectionState.Live) "No threads on this computer yet." else "Threads appear here once the computer answers.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            for (group in model.groups) {
                item(key = "project:" + (group.project?.id ?: "none"), contentType = "header") {
                    SectionLabel(group.project?.title ?: "Other")
                }
                items(group.threads, key = { it.id }, contentType = { "thread" }) { thread ->
                    ThreadRow(thread, now) {
                        Timeline.mark("thread.tap", thread.id.take(8))
                        nav.push(Screen.Thread(envId, thread.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, now: Long, onClick: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            thread.title, modifier = Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = c.foreground, fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(8.dp))
        PillChip(thread.pill, thread.unread, thread.workingSince, now)
        Spacer(Modifier.width(8.dp))
        Text(relativeTime(thread.activityAt, now), fontSize = 12.sp, color = c.mutedForeground)
    }
}
