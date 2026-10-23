package org.anikodev.candywrapper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.ProjectGroup
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.BlockReason
import org.anikodev.candywrapper.core.sync.ConnectionState

/** One computer as the sidebar's switcher shows it. */
@Immutable
data class SidebarEnvironment(
    val id: String,
    val label: String,
    val kind: EnvironmentKind,
    val state: ConnectionState,
    /** Threads waiting on an approval or an answer. */
    val waiting: Int = 0,
)

/** The archived list: closed, loading, loaded or failed. */
@Immutable
data class ArchivedList(val open: Boolean = false, val model: ThreadListModel? = null, val error: String? = null)

/** What the sidebar asks of the app. */
class SidebarActions(
    val openThread: (ThreadSummary) -> Unit = {},
    val newThread: (projectId: String) -> Unit = {},
    val switchEnvironment: (String) -> Unit = {},
    val pair: () -> Unit = {},
    val connect: () -> Unit = {},
    val settings: () -> Unit = {},
    val timing: () -> Unit = {},
    val retry: (BlockReason?) -> Unit = {},
    val rename: (ThreadSummary) -> Unit = {},
    val toggleRead: (ThreadSummary) -> Unit = {},
    val archive: (ThreadSummary) -> Unit = {},
    val unarchive: (ThreadSummary) -> Unit = {},
    val toggleArchived: () -> Unit = {},
    /** Pull-to-refresh: check the connection now. */
    val refresh: () -> Unit = {},
)

/** `DEFAULT_SIDEBAR_THREAD_PREVIEW_COUNT` (contracts settings.ts): threads a project shows before "Show more". */
const val ThreadPreviewCount = 6

/**
 * T3's sidebar (LegacySidebar.tsx, the project tree): the computer switcher where T3 puts its
 * wordmark, search, every project with its threads nested under a guide line, and Settings at the
 * bottom. Drawn on `--sidebar` with the sidebar palette.
 *
 * State that only the sidebar cares about (search, collapsed projects, "Show more", the open menu)
 * lives here; everything else comes in as immutable models, so rows that did not change are skipped.
 */
@Composable
fun SidebarPanel(
    current: SidebarEnvironment?,
    environments: List<SidebarEnvironment>,
    model: ThreadListModel,
    activeThreadId: String?,
    now: Long,
    actions: SidebarActions,
    modifier: Modifier = Modifier,
    archived: ArchivedList = ArchivedList(),
    initialQuery: String = "",
) {
    val c = Candy.colors
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val list = if (archived.open) archived.model ?: ThreadListModel() else model
    val groups = remember(list, query) { filterGroups(list.groups, query) }
    val searching = query.isNotBlank()
    val pull = rememberPullRefresh(actions.refresh)

    Column(modifier.fillMaxSize().background(c.sidebar).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EnvironmentSwitcher(current, environments, actions)
            SearchRow(query, { query = it }) {
                val pid = activeThreadId?.let { id -> model.groups.firstOrNull { g -> g.threads.any { it.id == id } } }?.project?.id
                    ?: model.groups.firstOrNull { it.project != null }?.project?.id
                if (pid != null) actions.newThread(pid)
            }
        }
        current?.state?.let { state -> ConnectionAlert(state, actions) }
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(if (archived.open) "Archived" else "Projects", CandyType.xsMedium, c.sidebarMutedForeground, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f)) {
            when {
                archived.open && archived.error != null -> SidebarNote(archived.error, "Retry") { actions.toggleArchived(); actions.toggleArchived() }
                archived.open && archived.model == null -> SidebarNote("Loading archived threads")
                list.threadCount == 0 && !archived.open -> SidebarNote(
                    if (current?.state is ConnectionState.Live) "No threads yet. Start one from a project on the computer." else "Threads appear once ${current?.label ?: "a computer"} answers.",
                )
                list.threadCount == 0 -> SidebarNote("No archived threads.")
                groups.isEmpty() -> SidebarNote("No thread matches “${query.trim()}”.")
                else -> LazyColumn(Modifier.fillMaxSize().nestedScroll(pull.connection), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    item(key = "pull", contentType = "pull") { PullIndicator(pull, "check the connection") }
                    for (group in groups) {
                        val key = group.project?.id ?: "none"
                        val isCollapsed = key in collapsed && !searching
                        item(key = "p:$key", contentType = "project") {
                            ProjectHeader(group, isCollapsed, onToggle = { collapsed = if (key in collapsed) collapsed - key else collapsed + key }, onNewThread = if (archived.open) null else actions.newThread)
                        }
                        if (!isCollapsed) {
                            val showAll = searching || key in expanded || group.threads.size <= ThreadPreviewCount
                            // Keep the open thread visible even when it sits past the preview count.
                            val shown = if (showAll) group.threads else group.threads.take(ThreadPreviewCount).let { head ->
                                val active = group.threads.firstOrNull { it.id == activeThreadId }
                                if (active != null && active !in head) head + active else head
                            }
                            items(shown, key = { "t:" + it.id }, contentType = { "thread" }) { thread ->
                                ThreadRow(
                                    thread, active = thread.id == activeThreadId, now = now, menuOpen = menuFor == thread.id,
                                    onClick = { actions.openThread(thread) }, onLongClick = { menuFor = thread.id },
                                ) {
                                    Menu(menuFor == thread.id, { menuFor = null }, minWidth = 180.dp) {
                                        if (archived.open) {
                                            MenuItem("Unarchive", Icons.ArchiveX) { menuFor = null; actions.unarchive(thread) }
                                        } else {
                                            MenuItem("Rename thread", Icons.Pencil) { menuFor = null; actions.rename(thread) }
                                            if (thread.unread || thread.pill == StatusPill.READY) {
                                                MenuItem(if (thread.unread) "Mark read" else "Mark unread", if (thread.unread) Icons.Eye else Icons.EyeOff) { menuFor = null; actions.toggleRead(thread) }
                                            }
                                            MenuSeparator()
                                            MenuItem("Archive", Icons.Archive, enabled = thread.pill != StatusPill.WORKING) { menuFor = null; actions.archive(thread) }
                                        }
                                    }
                                }
                            }
                            if (group.threads.size > ThreadPreviewCount && !searching) {
                                item(key = "more:$key", contentType = "more") {
                                    val open = key in expanded
                                    val hidden = remember(group) { group.threads.drop(ThreadPreviewCount) }
                                    ShowMoreRow(open, if (open) null else hiddenStatus(hidden)) {
                                        expanded = if (open) expanded - key else expanded + key
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SidebarFooter(archived.open, actions)
    }
}

private fun filterGroups(groups: List<ProjectGroup>, query: String): List<ProjectGroup> {
    val q = query.trim()
    if (q.isEmpty()) return groups
    return groups.mapNotNull { g ->
        val projectHit = g.project?.title?.contains(q, ignoreCase = true) == true
        val hits = if (projectHit) g.threads else g.threads.filter { it.title.contains(q, ignoreCase = true) }
        if (hits.isEmpty()) null else ProjectGroup(g.project, hits)
    }
}

/** The status a collapsed list shows for its hidden threads: the most urgent one, if any. */
private fun hiddenStatus(threads: List<ThreadSummary>): StatusPill? =
    listOf(StatusPill.APPROVAL, StatusPill.INPUT, StatusPill.FAILED, StatusPill.WORKING).firstOrNull { p -> threads.any { it.pill == p } }

/** T3's thread status (Sidebar.logic.ts `resolveThreadStatusPill`): label, text colour and dot colour. */
@Immutable
data class ThreadStatus(val label: String, val color: Color, val dot: Color)

@Composable
fun threadStatus(pill: StatusPill, unread: Boolean): ThreadStatus? {
    val c = Candy.colors
    return when (pill) {
        StatusPill.APPROVAL -> ThreadStatus("Pending Approval", c.statusApproval, c.statusApprovalDot)
        StatusPill.INPUT -> ThreadStatus("Awaiting Input", c.statusInput, c.statusInputDot)
        StatusPill.WORKING -> ThreadStatus("Working", c.statusWorking, c.statusWorkingDot)
        StatusPill.WAITING -> ThreadStatus("Waiting", c.sidebarMutedForeground, c.sidebarMutedForeground)
        StatusPill.FAILED -> ThreadStatus("Failed", c.errorForeground, c.error)
        StatusPill.LIMITED -> ThreadStatus("Limited", c.warningForeground, c.warning)
        StatusPill.READY, StatusPill.NONE -> if (unread) ThreadStatus("Completed", c.statusDone, c.statusDoneDot) else null
    }
}

/** `ThreadStatusLabel`: a 6dp dot and the label in the status colour, `text-3xs`. Static: no pulse. */
@Composable
fun StatusLabel(status: ThreadStatus, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { contentDescription = status.label }) {
        StatusDot(status.dot, size = if (compact) 8.dp else 6.dp)
        if (!compact) Txt(status.label, CandyType.xxs, status.color, maxLines = 1)
    }
}

/**
 * The header: the computer switcher (a `SidebarMenuButton size="lg"`), with the connection's real
 * state under the name. It opens a menu of every computer, pairing and T3 Connect.
 */
@Composable
private fun EnvironmentSwitcher(current: SidebarEnvironment?, environments: List<SidebarEnvironment>, actions: SidebarActions) {
    val c = Candy.colors
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(Radius.lg))
                .background(if (pressed || open) c.sidebarRowActive else Color.Transparent)
                .combinedClickable(source, null, role = Role.Button, onClickLabel = "Switch computer") { open = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(Radius.md)).background(c.sidebarControlSurface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (current?.kind == EnvironmentKind.CONNECT) Icons.Cloud else Icons.Laptop, c.sidebarForeground, 16.dp)
            }
            Column(Modifier.weight(1f)) {
                Txt(current?.label ?: "No computer", CandyType.smMedium, c.sidebarForeground, maxLines = 1)
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(current.state.dotColor())
                        Txt(current.state.label(), CandyType.xs, c.sidebarMutedForeground, maxLines = 1)
                    }
                }
            }
            Icon(Icons.ChevronsUpDown, c.sidebarMutedForeground, 16.dp)
        }
        Menu(open, { open = false }, minWidth = 260.dp) {
            if (environments.isNotEmpty()) MenuLabel("Computers")
            for (e in environments) {
                MenuItem(
                    e.label, if (e.kind == EnvironmentKind.CONNECT) Icons.Cloud else Icons.Laptop,
                    detail = e.state.label(), selected = e.id == current?.id,
                    trailing = if (e.waiting > 0) ({ Pill("${e.waiting} waiting", c.statusApproval) }) else null,
                ) { open = false; actions.switchEnvironment(e.id) }
            }
            if (environments.isNotEmpty()) MenuSeparator()
            MenuItem("Pair a computer", Icons.Plus) { open = false; actions.pair() }
            MenuItem("T3 Connect", Icons.Cloud) { open = false; actions.connect() }
        }
    }
}

/** The connection's dot: success when live, the error colour when it needs the user, muted while it works. */
@Composable
fun ConnectionState.dotColor(): Color = when (this) {
    is ConnectionState.Live -> Candy.colors.success
    is ConnectionState.Offline, is ConnectionState.Blocked -> Candy.colors.error
    is ConnectionState.Connecting -> Candy.colors.statusWorkingDot
    ConnectionState.Idle -> Candy.colors.sidebarMutedForeground
}

/** The search row: T3's "Search" line with its icon, typed into directly, and New thread at its end. */
@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit, onNewThread: () -> Unit) {
    val c = Candy.colors
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Search, c.sidebarMutedForeground, 16.dp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            query, onQuery,
            Modifier.weight(1f).semantics { contentDescription = "Search threads" },
            textStyle = CandyType.sm.merge(TextStyle(color = c.sidebarForeground)),
            singleLine = true,
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Txt("Search", CandyType.sm, c.sidebarMutedForeground, maxLines = 1)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) IconButton(Icons.X, "Clear search", tint = c.sidebarMutedForeground, size = 16.dp, box = 32.dp) { onQuery("") }
        IconButton(Icons.SquarePen, "New thread", tint = c.sidebarMutedForeground, size = 16.dp, box = 32.dp, onClick = onNewThread)
    }
}

/** Blocked and offline states under the header, with the action that fixes them. */
@Composable
private fun ConnectionAlert(state: ConnectionState, actions: SidebarActions) {
    when (state) {
        is ConnectionState.Blocked -> Alert(
            state.message, NoticeTone.ERROR, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            action = when (state.reason) { BlockReason.SIGN_IN -> "Sign in"; BlockReason.PAIR_AGAIN -> "Pair"; else -> "Retry" },
        ) { actions.retry(state.reason) }
        is ConnectionState.Offline -> Alert(state.message, NoticeTone.NEUTRAL, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), action = "Retry") { actions.retry(null) }
        else -> Unit
    }
}

@Composable
private fun SidebarNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(text, CandyType.sm, Candy.colors.sidebarMutedForeground)
        if (action != null) CButton(action, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, onClick = onAction)
    }
}

/**
 * A project row (LegacySidebar `SidebarProjectItem`): the chevron that turns as it opens, the
 * project's monogram, its title in `text-sm font-medium`, and New thread, which T3 shows on touch
 * screens without hover. A collapsed project shows the most urgent status among its threads.
 */
@Composable
private fun ProjectHeader(group: ProjectGroup, collapsed: Boolean, onToggle: () -> Unit, onNewThread: ((String) -> Unit)?) {
    val c = Candy.colors
    val project = group.project
    val turn by animateFloatAsState(if (collapsed) 0f else 90f, Motion.fast(), label = "chevron")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp).height(40.dp).clip(RoundedCornerShape(Radius.md))
            .background(if (pressed) c.sidebarRowHover.compositeOverSidebar(c) else Color.Transparent)
            .combinedClickable(source, null, role = Role.Button, onClickLabel = if (collapsed) "Expand" else "Collapse", onClick = onToggle)
            .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.ChevronRight, c.mutedForeground.copy(alpha = 0.7f), 14.dp, Modifier.rotate(turn))
        Spacer(Modifier.width(8.dp))
        Monogram(project?.title ?: "Other")
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Txt(project?.title ?: "Other", CandyType.smMedium, c.sidebarForeground.copy(alpha = 0.9f), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (collapsed) {
                hiddenStatus(group.threads)?.let { p -> threadStatus(p, false)?.let { Spacer(Modifier.width(8.dp)); StatusLabel(it, compact = true) } }
            }
        }
        if (project != null && onNewThread != null) {
            IconButton(Icons.SquarePen, "New thread in ${project.title}", tint = c.iconMuted, size = 15.dp, box = 32.dp) { onNewThread(project.id) }
        }
    }
}

/** On the light sidebar the hover token is lighter than the sidebar itself; pressing reads as a step darker instead. */
private fun Color.compositeOverSidebar(c: CandyColors): Color = if (c.dark) this else c.sidebarControlSurface

/**
 * A thread row (LegacySidebar `SidebarThreadRow`), inside T3's `SidebarMenuSub` guide line: status,
 * title in `text-sm`, the relative time in `text-3xs`. The open thread takes `bg-sidebar-row-active`
 * and `font-medium`; others read in `text-sidebar-muted-foreground/80`. A long press opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: ThreadSummary,
    active: Boolean,
    now: Long,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val status = threadStatus(thread.pill, thread.unread)
    val fill = when {
        active -> c.sidebarRowActive
        pressed || menuOpen -> c.sidebarRowHover.compositeOverSidebar(c)
        else -> Color.Transparent
    }
    SubTreeLine {
        Box {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).height(36.dp).clip(RoundedCornerShape(Radius.md)).background(fill)
                    .combinedClickable(source, null, role = Role.Button, onLongClickLabel = "Thread actions", onLongClick = onLongClick, onClick = onClick)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (status != null) StatusLabel(status)
                Txt(
                    thread.title, CandyType.sm,
                    if (active || thread.unread) c.sidebarForeground else c.sidebarMutedForeground.copy(alpha = 0.8f),
                    maxLines = 1, modifier = Modifier.weight(1f),
                    weight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
                Txt(relativeTime(thread.activityAt, now), CandyType.xxs.merge(TextStyle(fontFeatureSettings = "tnum")), if (active) c.sidebarForeground else c.secondaryLabel, maxLines = 1)
            }
            menu()
        }
    }
}

/** `SidebarMenuSub`: `mx-3.5 border-l border-sidebar-border px-2.5`, the line that ties threads to their project. */
@Composable
private fun SubTreeLine(content: @Composable () -> Unit) {
    val c = Candy.colors
    Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 0.dp)) {
        // Every row under the line is 40dp, so the line takes that height without measuring intrinsics.
        Box(Modifier.width(1.dp).height(40.dp).background(c.sidebarBorder))
        Box(Modifier.weight(1f).padding(start = 6.dp)) { content() }
    }
}

@Composable
private fun ShowMoreRow(open: Boolean, hidden: StatusPill?, onClick: () -> Unit) {
    val c = Candy.colors
    SubTreeLine {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).height(36.dp).clip(RoundedCornerShape(Radius.md))
                .combinedClickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            hidden?.let { p -> threadStatus(p, false)?.let { StatusLabel(it, compact = true) } }
            Txt(if (open) "Show less" else "Show more", CandyType.xs, c.sidebarMutedForeground, maxLines = 1)
        }
    }
}

/** `SidebarFooter`: Settings, the archived list and the Timing screen. */
@Composable
private fun SidebarFooter(archivedOpen: Boolean, actions: SidebarActions) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        Divider(color = c.sidebarBorder)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(Radius.md)).background(if (pressed) c.sidebarRowHover.compositeOverSidebar(c) else Color.Transparent)
                    .combinedClickable(source, null, role = Role.Button, onClick = actions.settings).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Settings, c.sidebarMutedForeground, 16.dp)
                Txt("Settings", CandyType.smMedium, c.sidebarMutedForeground.copy(alpha = 0.9f))
            }
            Box(Modifier.clip(RoundedCornerShape(Radius.md)).background(if (archivedOpen) c.sidebarRowActive else Color.Transparent)) {
                IconButton(Icons.Archive, if (archivedOpen) "Show active threads" else "Show archived threads", tint = if (archivedOpen) c.sidebarForeground else c.sidebarMutedForeground, size = 16.dp, box = 36.dp, onClick = actions.toggleArchived)
            }
            IconButton(Icons.Activity, "Timing", tint = c.sidebarMutedForeground, size = 16.dp, box = 36.dp, onClick = actions.timing)
        }
    }
}
