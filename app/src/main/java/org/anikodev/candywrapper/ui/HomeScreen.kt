package org.anikodev.candywrapper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.ProjectGroup
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.BlockReason
import org.anikodev.candywrapper.core.sync.Commands
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.timing.Timeline

private sealed interface HomeSheet {
    data object Environments : HomeSheet
    data class Actions(val thread: ThreadSummary) : HomeSheet
    data class Rename(val thread: ThreadSummary) : HomeSheet
}

/**
 * One environment's threads, grouped by project, with the environment switcher in the header.
 * Drawn from the cached list on the first frame; it updates live once the socket is up.
 */
@Composable
fun HomeScreen(graph: AppGraph, nav: Navigator, envId: String?) {
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    val session = sessions.firstOrNull { it.id == envId } ?: sessions.firstOrNull()
    if (session == null) {
        Welcome(nav)
        return
    }
    LaunchedEffect(session.id) { graph.rememberEnvironment(session.id) }
    val env by session.env.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val model by session.threads.collectAsStateWithLifecycle()
    val now = rememberMinuteClock()
    val c = Candy.colors
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val pull = rememberPullRefresh { session.networkChanged() }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = EnvironmentSession.describe(e)
            }
        }
    }

    val groups = remember(model, query) {
        val q = query.trim()
        if (q.isEmpty()) model.groups
        else model.groups.mapNotNull { g ->
            val hits = g.threads.filter { it.title.contains(q, ignoreCase = true) || g.project?.title?.contains(q, ignoreCase = true) == true }
            if (hits.isEmpty()) null else ProjectGroup(g.project, hits)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(Radius.lg))
                            .combinedClickable(role = Role.Button, onClickLabel = "Switch computer", onClick = { sheet = HomeSheet.Environments })
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(state.color())
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt(env.label, CandyType.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.ChevronDown, c.mutedForeground, 16.dp)
                            }
                            Txt(state.label(), CandyType.meta, c.mutedForeground, maxLines = 1)
                        }
                    }
                    IconButton(Icons.Search, if (searching) "Close search" else "Search threads", tint = if (searching) c.foreground else c.iconMuted) {
                        searching = !searching
                        if (!searching) query = ""
                    }
                    IconButton(Icons.Settings, "Settings") { nav.push(Screen.Settings) }
                }
                if (searching) {
                    TextInput(
                        query, { query = it }, "Search threads", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        style = CandyType.ui,
                        keyboard = KeyboardOptions(imeAction = ImeAction.Search), actions = KeyboardActions(onSearch = {}),
                    )
                }
                Divider()
            }
            val blocked = state as? ConnectionState.Blocked
            if (blocked != null) {
                Notice(
                    blocked.message, c.error, c.errorSurface, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    action = when (blocked.reason) { BlockReason.SIGN_IN -> "Sign in"; BlockReason.PAIR_AGAIN -> "Pair"; else -> "Retry" },
                ) {
                    when (blocked.reason) {
                        BlockReason.SIGN_IN -> nav.push(Screen.Connect)
                        BlockReason.PAIR_AGAIN -> nav.push(Screen.Pair())
                        else -> session.wake()
                    }
                }
            }
            (state as? ConnectionState.Offline)?.let { offline ->
                Notice(offline.message, c.foreground, c.muted, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), action = "Retry") { session.wake() }
            }
            message?.let { m -> Notice(m, c.error, c.errorSurface, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), action = "Dismiss") { message = null } }
            if (model.threadCount == 0) {
                EmptyState(
                    if (state is ConnectionState.Live) "No threads yet" else "Waiting for ${env.label}",
                    if (state is ConnectionState.Live) "Start one from a project on the computer, then it shows up here." else "Threads appear here once the computer answers.",
                )
                return@Column
            }
            if (groups.isEmpty()) {
                EmptyState("No matches", "No thread title contains “${query.trim()}”.")
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(pull.connection),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item(key = "pull", contentType = "pull") { PullIndicator(pull, "check the connection") }
                for (group in groups) {
                    item(key = "project:" + (group.project?.id ?: "none"), contentType = "header") {
                        ProjectHeader(group) { pid -> nav.push(Screen.NewThread(session.id, pid)) }
                    }
                    items(group.threads, key = { it.id }, contentType = { "thread" }) { thread ->
                        ThreadRow(thread, now, onLongClick = { sheet = HomeSheet.Actions(thread) }) {
                            Timeline.mark("thread.tap", thread.id.take(8))
                            nav.push(Screen.Thread(session.id, thread.id))
                        }
                    }
                }
                item(key = "nav", contentType = "spacer") { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
            }
        }

        when (val s = sheet) {
            HomeSheet.Environments -> Sheet({ sheet = null }, "Computers") {
                for (other in sessions) EnvironmentRow(other, selected = other.id == session.id) {
                    sheet = null
                    if (other.id != session.id) nav.reset(Screen.Home(other.id))
                }
                Divider(Modifier.padding(vertical = 6.dp))
                ListRow("Pair a computer", icon = Icons.Plus) { sheet = null; nav.push(Screen.Pair()) }
                ListRow("T3 Connect", icon = Icons.Cloud) { sheet = null; nav.push(Screen.Connect) }
            }
            is HomeSheet.Actions -> Sheet({ sheet = null }, s.thread.title) {
                ListRow("Open", icon = Icons.ChevronRight) { sheet = null; nav.push(Screen.Thread(session.id, s.thread.id)) }
                ListRow("Rename", icon = Icons.Pencil) { sheet = HomeSheet.Rename(s.thread) }
                if (s.thread.unread || s.thread.pill == StatusPill.READY) {
                    ListRow(if (s.thread.unread) "Mark as read" else "Mark as unread", icon = Icons.Eye) {
                        sheet = null
                        act {
                            session.dispatch(if (s.thread.unread) Commands.visit(s.thread.id, s.thread.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) else Commands.markUnread(s.thread.id))
                        }
                    }
                }
                ListRow("Archive", icon = Icons.Archive) { sheet = null; act { session.dispatch(Commands.archive(s.thread.id)) } }
            }
            is HomeSheet.Rename -> RenameSheet(s.thread.title, onDismiss = { sheet = null }) { title ->
                sheet = null
                act { session.dispatch(Commands.rename(s.thread.id, title)) }
            }
            null -> Unit
        }
    }
}

@Composable
private fun ProjectHeader(group: ProjectGroup, onNewThread: (String) -> Unit) {
    val c = Candy.colors
    val project = group.project
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Monogram(project?.title ?: "?")
        Spacer(Modifier.width(8.dp))
        Txt(project?.title ?: "Other", CandyType.label, c.mutedForeground, maxLines = 1, modifier = Modifier.weight(1f))
        if (project != null) IconButton(Icons.SquarePen, "New thread in ${project.title}", size = 18.dp) { onNewThread(project.id) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(thread: ThreadSummary, now: Long, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(role = Role.Button, onLongClickLabel = "Thread actions", onLongClick = onLongClick, onClick = onClick)
            .padding(start = 44.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            thread.title, CandyType.body, c.foreground, maxLines = 1, modifier = Modifier.weight(1f),
            weight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(8.dp))
        PillChip(thread.pill, thread.unread, thread.workingSince, now)
        Spacer(Modifier.width(8.dp))
        Txt(relativeTime(thread.activityAt, now), CandyType.meta, c.mutedForeground)
    }
}

@Composable
private fun EnvironmentRow(session: EnvironmentSession, selected: Boolean, onClick: () -> Unit) {
    val env by session.env.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val threads by session.threads.collectAsStateWithLifecycle()
    val waiting = remember(threads) { threads.groups.sumOf { g -> g.threads.count { it.pill == StatusPill.APPROVAL || it.pill == StatusPill.INPUT } } }
    val c = Candy.colors
    ListRow(
        env.label,
        detail = state.label() + (if (env.kind == EnvironmentKind.CONNECT) " · T3 Connect" else ""),
        selected = selected,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting > 0) Pill("$waiting waiting", c.warning)
                StatusDot(state.color())
            }
        },
        onClick = onClick,
    )
}

/** A sheet with one field for a new title. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.RenameSheet(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(current) }
    Sheet(onDismiss, "Rename thread") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                title, { title = it }, "Thread title",
                keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                actions = KeyboardActions(onDone = { if (title.isNotBlank()) onSave(title) }),
            )
            Row(horizontalArrangement = ButtonRow) {
                CButton("Save", kind = ButtonKind.PRIMARY, enabled = title.isNotBlank() && title.trim() != current) { onSave(title) }
                CButton("Cancel", kind = ButtonKind.GHOST, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun Welcome(nav: Navigator) {
    val c = Candy.colors
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.End) {
            IconButton(Icons.Settings, "Settings") { nav.push(Screen.Settings) }
        }
        EmptyState(
            "Connect a computer",
            "Run `npx t3 pair` on the computer running T3 Code and paste the link here, or sign in to T3 Connect to reach the computers linked to your account.",
        ) {
            CButton("Pair a computer", kind = ButtonKind.PRIMARY) { nav.push(Screen.Pair()) }
            CButton("T3 Connect") { nav.push(Screen.Connect) }
        }
        Txt(
            "The phone keeps a copy of what it has seen, so threads open instantly next time.",
            CandyType.meta, c.mutedForeground, modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
