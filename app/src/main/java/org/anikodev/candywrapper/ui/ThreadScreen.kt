package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.MdSpan
import org.anikodev.candywrapper.core.model.RowKind
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.anikodev.candywrapper.core.sync.TimelineItem
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.anikodev.candywrapper.core.sync.ToolGroup
import org.anikodev.candywrapper.core.timing.Timeline

@Composable
fun ThreadScreen(graph: AppGraph, nav: Navigator, envId: String, threadId: String) {
    val session = graph.environments.session(envId)
    if (session == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val thread = remember(envId, threadId) { session.thread(threadId) }
    DisposableEffect(thread) {
        thread.acquire()
        onDispose { thread.release() }
    }
    val state by thread.state.collectAsStateWithLifecycle()
    val connection by session.state.collectAsStateWithLifecycle()
    val shell by session.threads.collectAsStateWithLifecycle()
    val summary = remember(shell, threadId) { shell.groups.firstNotNullOfOrNull { g -> g.threads.firstOrNull { it.id == threadId } } }
    val now = rememberMinuteClock()

    // "Catching up" only when it takes long enough to notice, so a quick resume does not flash it.
    var slowSync by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        slowSync = false
        if (state.phase == ThreadSession.Phase.SYNCING || state.phase == ThreadSession.Phase.LOADING) {
            delay(700)
            slowSync = true
        }
    }
    val subtitle = when {
        state.phase == ThreadSession.Phase.DELETED -> "Deleted on the computer"
        connection !is ConnectionState.Live && state.items.isNotEmpty() -> "Saved copy · ${connection.label()}"
        connection !is ConnectionState.Live -> connection.label()
        slowSync -> "Catching up…"
        summary?.pill == org.anikodev.candywrapper.core.model.StatusPill.WORKING ->
            if (summary.workingSince > 0) "Working ${duration(now - summary.workingSince)}" else "Working"
        else -> null
    }

    // First paint after open: the frame that shows the first rows.
    var painted by remember(thread) { mutableStateOf(false) }
    if (!painted && state.items.isNotEmpty()) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            painted = true
            Timeline.mark("thread.${threadId.take(8)}.paint", "${state.items.size} items, ${state.phase}")
        }
    }

    val expanded = remember(threadId) { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxSize()) {
        TopBar(state.title ?: summary?.title ?: "Thread", onBack = { nav.pop() }, subtitle = subtitle)
        if (state.items.isEmpty()) {
            Hint(when {
                state.phase == ThreadSession.Phase.LOADING && !slowSync -> ""
                connection is ConnectionState.Live -> "Loading the thread…"
                else -> "This thread is not saved on the phone yet. It loads when the computer answers."
            })
            return@Column
        }
        // Newest at the bottom with no scroll work: the list is reversed and laid out from the end.
        val reversed = remember(state.items) { state.items.asReversed() }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(reversed, key = { it.key }, contentType = { contentType(it) }) { item ->
                when (item) {
                    is ToolGroup -> ToolGroupView(item, expanded[item.key] == true, { expanded[item.key] = !(expanded[item.key] ?: false) }, expanded)
                    is TimelineRow -> RowView(item, expanded[item.key] == true) { expanded[item.key] = !(expanded[item.key] ?: false) }
                }
            }
        }
    }
}

private fun contentType(item: TimelineItem): Any = when (item) {
    is ToolGroup -> "group"
    is TimelineRow -> item.kind
}

@Composable
private fun RowView(item: TimelineRow, expanded: Boolean, toggle: () -> Unit) {
    val row = item.row
    when (row.kind) {
        RowKind.USER -> UserBubble(row)
        RowKind.ASSISTANT -> Column {
            MarkdownBlocks(item.markdown ?: listOf(MdBlock.Paragraph(listOf(MdSpan(row.text.orEmpty())))))
            if (row.streaming) Text("…", color = Candy.colors.mutedForeground)
        }
        RowKind.REASONING -> Collapsible(if (row.streaming) "Thinking…" else "Thought", expanded, toggle) {
            Text(row.text.orEmpty(), fontSize = 13.sp, lineHeight = 19.sp, color = Candy.colors.mutedForeground)
        }
        RowKind.PLAN -> Column(
            Modifier.fillMaxWidth().border(1.dp, Candy.colors.border, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(row.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Candy.colors.mutedForeground)
            MarkdownBlocks(item.markdown ?: emptyList())
        }
        RowKind.TOOL -> ToolLine(row, expanded, toggle)
        RowKind.NOTICE -> Text(
            listOfNotNull(row.title, row.text?.takeIf { it != row.title }).joinToString(" · "),
            fontSize = 12.sp, color = Candy.colors.mutedForeground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        RowKind.ERROR -> Text(
            row.text ?: row.title, fontSize = 13.sp, color = Candy.colors.error,
            modifier = Modifier.fillMaxWidth().background(Candy.colors.errorSurface, RoundedCornerShape(8.dp)).padding(10.dp),
        )
    }
}

@Composable
private fun UserBubble(row: TurnRow) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.85f).dp
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            row.text.orEmpty(), fontSize = 15.sp, lineHeight = 22.sp, color = Candy.colors.foreground,
            modifier = Modifier.widthIn(max = maxWidth).background(Candy.colors.messageSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ToolGroupView(group: ToolGroup, expanded: Boolean, toggle: () -> Unit, rowExpanded: MutableMap<String, Boolean>) {
    val summary = buildString {
        append("${group.rows.size} tool calls")
        if (group.running) append(" · running")
        if (group.failed > 0) append(" · ${group.failed} failed")
    }
    Collapsible(summary, expanded, toggle) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in group.rows) ToolLine(r.row, rowExpanded[r.key] == true) { rowExpanded[r.key] = !(rowExpanded[r.key] ?: false) }
        }
    }
}

@Composable
private fun ToolLine(row: TurnRow, expanded: Boolean, toggle: () -> Unit) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().rowClick(toggle).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(when { row.failed -> c.error; row.running -> c.info; else -> c.mutedForeground.copy(alpha = 0.5f) })
            Spacer(Modifier.width(8.dp))
            Text(row.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.foreground, maxLines = 1)
            if (row.detail != null) {
                Spacer(Modifier.width(8.dp))
                Text(row.detail!!, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.mutedForeground, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        val body = row.body ?: row.detail
        if (expanded && body != null) {
            Box(Modifier.padding(start = 16.dp, top = 4.dp).fillMaxWidth().background(c.codeBackground, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState()).padding(8.dp)) {
                Text(body, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.foreground, softWrap = false)
            }
        }
    }
}

@Composable
private fun Collapsible(label: String, expanded: Boolean, toggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text((if (expanded) "▾ " else "▸ ") + label, fontSize = 13.sp, color = Candy.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth().rowClick(toggle).padding(vertical = 4.dp))
        if (expanded) Box(Modifier.padding(start = 14.dp, top = 4.dp)) { content() }
    }
}
