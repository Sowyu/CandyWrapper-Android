package org.anikodev.candywrapper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import org.anikodev.candywrapper.core.sidebar.ProjectSection
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sidebar.SidebarData
import org.anikodev.candywrapper.core.sidebar.SidebarModel
import org.anikodev.candywrapper.core.sidebar.SidebarProjectGroup
import org.anikodev.candywrapper.core.sidebar.SidebarSection
import org.anikodev.candywrapper.core.sidebar.SidebarStatus
import org.anikodev.candywrapper.core.sidebar.Snooze
import org.anikodev.candywrapper.core.sidebar.TopStatus
import org.anikodev.candywrapper.core.sync.ConnectionState

/** Whether the Working, Snoozed and Settled shelves are open. */
@Immutable
data class Shelves(val snoozed: Boolean = false, val settled: Boolean = false, val working: Boolean = false)

/**
 * T3's sidebar (Sidebar.tsx, the default since the flat redesign): the machine switcher where T3
 * puts its wordmark, the thread header (search, project filter, New thread), then one flat list of
 * every thread across the machine scope: pinned and active threads as cards, then, with Settings >
 * Working section on, the Working shelf of cards, then the Snoozed and Settled shelves as slim rows.
 * The shelves sit at the bottom while the list is short. Long press opens a
 * row's actions, since a phone has no hover.
 *
 * [data] is built off the main thread; null draws an empty list rather than a wrong empty state.
 */
@Composable
fun SidebarPanel(
    current: SidebarEnvironment?,
    environments: List<SidebarEnvironment>,
    data: SidebarData?,
    openKey: String?,
    now: Long,
    actions: SidebarActions,
    modifier: Modifier = Modifier,
    shelves: Shelves = Shelves(),
    folded: Set<String> = emptySet(),
    archived: ArchivedList = ArchivedList(),
    undo: UndoNotice? = null,
    initialQuery: String = "",
    initialMenu: String? = null,
    orchestrator: OrchestratorRowInfo? = null,
    picturePrompt: Boolean = false,
) {
    val c = Candy.colors
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var menuFor by remember { mutableStateOf(initialMenu) }
    // The settled tail restarts whenever the project filter changes.
    var settledCount by remember(data?.scope?.key) { mutableIntStateOf(SidebarModel.SETTLED_INITIAL) }
    val machines = remember(environments) { environments.associateBy { it.id } }
    // Several machines in one list: each row names its machine.
    val tagMachines = current?.all == true && environments.size > 1
    val searching = query.isNotBlank() && !archived.open
    val results = remember(data, query) { data?.let { SidebarModel.search(it.sections.all, query) }.orEmpty() }
    val pull = rememberPullRefresh(actions.refresh)

    @Composable
    fun Menu(thread: ThreadSummary, section: SidebarSection) =
        RowMenu(thread, section, machines[thread.envId]?.caps ?: MachineCaps(), now, menuFor == thread.key, { menuFor = null }, actions, archived.open)

    fun row(thread: ThreadSummary) = RowInfo(
        thread = thread,
        project = data?.projectLabel?.get("${thread.envId}:${thread.projectId}") ?: "Other",
        projectRef = data?.projects?.get("${thread.envId}:${thread.projectId}"),
        machine = if (tagMachines) machines[thread.envId]?.label else null,
        driver = thread.providerInstanceId?.let { machines[thread.envId]?.caps?.drivers?.get(it) },
        active = thread.key == openKey,
    )

    Column(modifier.fillMaxSize().background(c.sidebar).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MachineSwitcher(current, environments, actions)
            if (c.redesign) RedesignHeader(query, { query = it }, data, actions) else ThreadHeader(query, { query = it }, data, actions)
        }
        ConnectionAlerts(current, environments, actions)
        if (picturePrompt) PicturePrompt(actions)
        if (c.redesign && orchestrator != null) OrchestratorRow(orchestrator, actions)
        Box(Modifier.weight(1f)) {
            when {
                archived.open -> ArchivedRows(archived, now, openKey, menuFor, { menuFor = it }, actions, ::row) { t -> Menu(t, SidebarSection.ACTIVE) }
                data == null -> Unit
                searching && results.isEmpty() -> EmptyNote("No threads found")
                searching -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    items(results, key = { it.key }, contentType = { "slim" }) { t ->
                        val section = data.sections.sectionOf(t.key) ?: SidebarSection.ACTIVE
                        SlimRow(row(t), section, now, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }, showProject = true) { Menu(t, section) }
                    }
                }
                data.sections.isEmpty -> EmptyNote(
                    when {
                        data.projects.isEmpty() && current?.state !is ConnectionState.Live -> "Projects appear once ${if (current?.all == true) "a machine" else current?.label ?: "a computer"} answers."
                        data.projects.isEmpty() -> "No projects yet"
                        data.scope != null -> "No threads in ${data.scope?.label} yet"
                        else -> "No threads yet"
                    },
                )
                else -> {
                    val s = data.sections
                    val working = SidebarModel.visibleShelf(s.working, shelves.working, openKey)
                    val snoozed = SidebarModel.visibleShelf(s.snoozed, shelves.snoozed, openKey)
                    val settled = SidebarModel.visibleSettled(s.settled, settledCount, shelves.settled, openKey)
                    val hiddenSettled = if (shelves.settled) s.settled.size - settled.size else 0
                    // Redesign (after Capy): Pinned and each project are sections with a header, above the shelves.
                    val sections = if (c.redesign) remember(s.pinned, s.active, data.groups) {
                        listOfNotNull(s.pinned.takeIf { it.isNotEmpty() }?.let { ProjectSection(PINNED_SECTION, null, it) }) + SidebarModel.projectSections(s.active, data.groups)
                    } else emptyList()
                    val shown = sections.map { sec -> if (sec.key in folded) sec.threads.filter { it.key == openKey } else sec.threads }
                    val lead = if (c.redesign) 1 + sections.size + shown.sumOf { it.size } else 1 + s.pinned.size + s.active.size
                    val arrangement = remember(lead) { ShelvesAtBottom(lead) }
                    LazyColumn(
                        Modifier.fillMaxSize().nestedScroll(pull.connection),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalArrangement = arrangement,
                    ) {
                        item(key = "pull", contentType = "pull") { PullIndicator(pull, "check the connection") }
                        if (c.redesign) {
                            for ((i, sec) in sections.withIndex()) {
                                val pinned = sec.key == PINNED_SECTION
                                item(key = "sec:${sec.key}", contentType = "section") {
                                    SectionHeader(sec.group, if (pinned) "Pinned" else sec.group?.label ?: data.projectLabel[sec.threads.first().let { "${it.envId}:${it.projectId}" }] ?: "Project",
                                        sec.threads.size, sec.key !in folded) { actions.toggleSection(sec.key) }
                                }
                                items(shown[i], key = { it.key }, contentType = { "capy" }) { t ->
                                    val section = if (pinned) SidebarSection.PINNED else SidebarSection.ACTIVE
                                    CapyRow(row(t), now, inSection = true, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, section) }
                                }
                            }
                        } else {
                            items(s.pinned, key = { it.key }, contentType = { "card" }) { t ->
                                CardRow(row(t), now, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, SidebarSection.PINNED) }
                            }
                            items(s.active, key = { it.key }, contentType = { "card" }) { t ->
                                CardRow(row(t), now, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, SidebarSection.ACTIVE) }
                            }
                        }
                        if (s.working.isNotEmpty()) {
                            item(key = "h:working", contentType = "shelf") {
                                ShelfHeader(if (shelves.working) "Working" else "Working (${s.working.size})", shelves.working, info = false, onToggle = actions.toggleWorking)
                            }
                            // A closed shelf keeps the open thread, so sending folds it in without losing its row.
                            items(working, key = { it.key }, contentType = { if (c.redesign) "capy" else "card" }) { t ->
                                if (c.redesign) CapyRow(row(t), now, inSection = false, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, SidebarSection.WORKING) }
                                else CardRow(row(t), now, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, SidebarSection.WORKING) }
                            }
                        }
                        if (s.snoozed.isNotEmpty()) {
                            item(key = "h:snoozed", contentType = "shelf") {
                                ShelfHeader(if (shelves.snoozed) "Snoozed" else "Snoozed (${s.snoozed.size})", shelves.snoozed, info = true, onToggle = actions.toggleSnoozed)
                            }
                            items(snoozed, key = { it.key }, contentType = { "slim" }) { t ->
                                SlimRow(row(t), SidebarSection.SNOOZED, now, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, SidebarSection.SNOOZED) }
                            }
                        }
                        item(key = "h:settled", contentType = "shelf") {
                            ShelfHeader(if (shelves.settled) "Settled" else "Settled (${s.settled.size})", shelves.settled, info = false, onToggle = actions.toggleSettled)
                        }
                        items(settled, key = { it.key }, contentType = { "slim" }) { t ->
                            SlimRow(row(t), SidebarSection.SETTLED, now, menuFor == t.key, { actions.openThread(t) }, { menuFor = t.key }) { Menu(t, SidebarSection.SETTLED) }
                        }
                        if (hiddenSettled > 0) {
                            item(key = "more", contentType = "more") {
                                ShowMore(minOf(hiddenSettled, SidebarModel.SETTLED_PAGE)) { settledCount += SidebarModel.SETTLED_PAGE }
                            }
                        }
                    }
                }
            }
        }
        SidebarFooter(archived.open, actions, undo)
    }
}

/** What a row draws besides the thread: its project's group label, its machine when tagged, its provider. */
@Immutable
data class RowInfo(val thread: ThreadSummary, val project: String, val machine: String?, val driver: String?, val active: Boolean, val projectRef: ProjectSummary? = null)

/**
 * The list's arrangement when it is shorter than the sidebar: rows from the top, and from
 * [firstShelf] on pushed to the bottom, as T3's `mt-auto` holds the shelves down. A longer list
 * scrolls and never asks.
 */
private class ShelvesAtBottom(private val firstShelf: Int) : Arrangement.Vertical {
    override val spacing = 0.dp
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = (totalSize - sizes.sum()).coerceAtLeast(0)
        var y = 0
        for (i in sizes.indices) {
            if (i == firstShelf) y += gap
            outPositions[i] = y
            y += sizes[i]
        }
    }
}

/**
 * `SidebarThreadHeader`: the search field, the project filter (`ListFilter`, or the project's
 * monogram once filtered) and New thread. T3's Add project needs a folder on the computer, so it
 * is not here.
 */
@Composable
private fun ThreadHeader(query: String, onQuery: (String) -> Unit, data: SidebarData?, actions: SidebarActions) {
    val c = Candy.colors
    var scopeOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(Radius.md)).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Search, c.sidebarIconColor, 16.dp)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                query, onQuery,
                Modifier.weight(1f).semantics { contentDescription = "Search threads" },
                textStyle = CandyType.smMedium.merge(TextStyle(color = c.sidebarForeground)),
                singleLine = true,
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Txt("Search", CandyType.smMedium, c.sidebarMutedForeground, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) IconButton(Icons.X, "Clear thread search", tint = c.sidebarMutedForeground, size = 12.dp, box = 32.dp) { onQuery("") }
        }
        val groups = data?.groups.orEmpty()
        if (groups.isNotEmpty()) {
            Box {
                val scope = data?.scope
                Box(
                    Modifier.clip(RoundedCornerShape(Radius.md)).background(if (scopeOpen) c.sidebarRowActive else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (scope == null) {
                        IconButton(Icons.ListFilter, "Filter threads by project", tint = c.sidebarMutedForeground, size = 16.dp, box = 36.dp) { scopeOpen = true }
                    } else {
                        Box(
                            Modifier.width(36.dp).height(36.dp).combinedClickable(role = Role.Button, onClickLabel = "Filter threads by project: ${scope.label}") { scopeOpen = true },
                            contentAlignment = Alignment.Center,
                        ) { ProjectIcon(scope.representative, scope.label) }
                    }
                }
                ScopeMenu(scopeOpen, { scopeOpen = false }, groups, scope, data?.spansMachines == true, actions)
            }
        }
        IconButton(Icons.SquarePen, "New thread", tint = c.sidebarMutedForeground, size = 16.dp, box = 36.dp, onClick = actions.newThreadAnywhere)
    }
}

/** The project filter's popup: All projects, then every project group, the machines it is on when they differ. */
@Composable
private fun ScopeMenu(open: Boolean, onDismiss: () -> Unit, groups: List<SidebarProjectGroup>, scope: SidebarProjectGroup?, spans: Boolean, actions: SidebarActions) {
    val c = Candy.colors
    Menu(open, onDismiss, minWidth = 240.dp, maxWidth = 300.dp) {
        MenuItem("All projects", Icons.Folder, selected = scope == null) { onDismiss(); actions.scopeProject(null) }
        for (g in groups) {
            MenuItem(
                g.label, selected = g.key == scope?.key,
                leading = { ProjectIcon(g.representative, g.label, Modifier.padding(vertical = 1.dp)) },
                trailing = if (spans) ({ Txt(machineBadge(g), CandyType.xxs, c.mutedForeground, maxLines = 1) }) else null,
            ) { onDismiss(); actions.scopeProject(g.key) }
        }
    }
}

/** `ProjectEnvironmentBadge`, as text: how many machines have the project. */
private fun machineBadge(g: SidebarProjectGroup): String = g.envIds.size.let { n -> if (n == 1) "1 machine" else "$n machines" }

/**
 * A card row (SidebarThreadRow `variant="card"`, 78px): the project monogram, pin and name with the
 * status or time on the right; the title; then the branch, the machine when tagged, and the
 * provider glyph. A row with nothing for the user recedes (`shouldRecedeSidebarThread`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(info: RowInfo, now: Long, menuOpen: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, menu: @Composable () -> Unit) {
    val c = Candy.colors
    val t = info.thread
    val woke = t.isWoke(now)
    val recede = SidebarStatus.recede(t.pill, t.unread, woke, info.active)
    val top = SidebarStatus.top(t.pill, t.unread, woke)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        info.active -> c.sidebarRowActive
        pressed || menuOpen -> c.sidebarRowHover.compositeOverSidebar(c)
        else -> Color.Transparent
    }
    val titleColor = when {
        recede -> c.secondaryLabel
        t.unread || woke || t.pill == StatusPill.INPUT -> c.foreground
        t.pill == StatusPill.FAILED -> c.foreground.copy(alpha = 0.95f)
        else -> c.foreground.copy(alpha = 0.9f)
    }
    Box(Modifier.padding(vertical = 1.dp)) {
        Column(
            Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(Radius.md)).background(fill)
                .graphicsLayer { alpha = if (recede && t.pill == StatusPill.WORKING) 0.7f else 1f }
                .combinedClickable(source, null, role = Role.Button, onLongClickLabel = "Thread actions", onLongClick = onLongClick, onClick = onClick)
                .semantics { stateDescription = listOfNotNull(top?.let(::topLabel), info.project).joinToString() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProjectIcon(info.projectRef, info.project)
                if (t.section == 0) Icon(Icons.Pin, c.mutedForeground.copy(alpha = 0.65f), 12.dp, description = "Pinned")
                Txt(info.project, CandyType.xs, c.secondaryLabel, maxLines = 1, modifier = Modifier.weight(1f), weight = if (recede) FontWeight.Normal else FontWeight.Medium)
                if (top != null) TopStatusLabel(top, t, now) else TimeLabel(relativeTime(t.activityAt, now), c.secondaryLabel)
            }
            Spacer(Modifier.height(4.dp))
            Txt(t.title, CandyType.sm, titleColor, maxLines = 1, weight = if (recede) FontWeight.Normal else FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val faint = c.mutedForeground.copy(alpha = 0.4f)
                if (t.branch != null) {
                    Icon(Icons.GitBranch, faint, 12.dp)
                    Txt(t.branch.orEmpty(), CandyType.xs, faint, maxLines = 1, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                info.machine?.let { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Laptop, c.sidebarMutedForeground.copy(alpha = 0.7f), 12.dp)
                        Txt(m, CandyType.xxs, c.sidebarMutedForeground.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.widthIn(max = 120.dp))
                    }
                }
                if (info.driver != null) {
                    val (glyph, tint) = providerIcon(info.driver)
                    Icon(glyph, tint, 14.dp, Modifier.graphicsLayer { alpha = 0.6f })
                }
            }
        }
        menu()
    }
}

private fun topLabel(top: TopStatus) = when (top) {
    TopStatus.APPROVAL -> "Approval"
    TopStatus.INPUT -> "Input"
    TopStatus.WORKING -> "Working"
    TopStatus.WAITING -> "Waiting"
    TopStatus.FAILED -> "Failed"
    TopStatus.LIMITED -> "Limited"
    TopStatus.WOKE -> "Woke"
    TopStatus.DONE -> "Done"
}

/** The card's status slot: T3's icon, label and, while working, how long, ticking each second. */
@Composable
private fun TopStatusLabel(top: TopStatus, t: ThreadSummary, now: Long) {
    val c = Candy.colors
    val (icon, color) = when (top) {
        TopStatus.WORKING -> Icons.CircleDashed to c.info
        TopStatus.WAITING -> Icons.CircleDotDashed to c.mutedForeground
        TopStatus.APPROVAL -> Icons.ShieldQuestion to c.warningForeground
        TopStatus.INPUT -> Icons.MessageCircleQuestion to c.statusInput
        TopStatus.LIMITED -> Icons.CircleAlert to c.warning
        TopStatus.FAILED -> Icons.CircleAlert to c.error
        TopStatus.WOKE -> Icons.AlarmClock to c.warning
        TopStatus.DONE -> Icons.CircleCheck to c.success
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, color, 16.dp)
        Txt(topLabel(top), CandyType.xsMedium, color, maxLines = 1)
        if (top == TopStatus.WORKING && t.workingSince > 0) {
            TickingText(t.workingSince, ::duration, CandyType.xsMedium.merge(TextStyle(fontFeatureSettings = "tnum")), color)
        }
    }
}

@Composable
private fun TimeLabel(text: String, color: Color) =
    Txt(text, CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), color, maxLines = 1)

/**
 * A slim row (SidebarThreadRow `variant="slim"`) for the shelves and search results: the faded
 * monogram, the title, the pin, and on the right the wake countdown, Woke, or the time. Search
 * results add the project under the title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlimRow(
    info: RowInfo,
    section: SidebarSection,
    now: Long,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showProject: Boolean = false,
    menu: @Composable () -> Unit,
) {
    val c = Candy.colors
    val t = info.thread
    val woke = t.isWoke(now)
    val recede = SidebarStatus.recede(t.pill, t.unread, woke, info.active)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        info.active -> c.sidebarRowActive
        pressed || menuOpen -> c.sidebarRowHover.compositeOverSidebar(c)
        else -> Color.Transparent
    }
    val titleColor = when {
        recede -> c.secondaryLabel.copy(alpha = 0.7f)
        info.active || woke || t.pill == StatusPill.INPUT -> c.foreground
        t.unread -> c.mutedForeground
        else -> c.secondaryLabel.copy(alpha = 0.7f)
    }
    Box(Modifier.padding(vertical = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().height(if (showProject) 48.dp else 40.dp).clip(RoundedCornerShape(Radius.md)).background(fill)
                .combinedClickable(source, null, role = Role.Button, onLongClickLabel = "Thread actions", onLongClick = onLongClick, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.graphicsLayer { alpha = if (info.active) 1f else 0.4f }) { ProjectIcon(info.projectRef, info.project) }
            Column(Modifier.weight(1f)) {
                Txt(t.title, CandyType.sm, titleColor, maxLines = 1)
                if (showProject) {
                    Txt(listOfNotNull(info.project, info.machine).joinToString(" · "), CandyType.xs, c.secondaryLabel, maxLines = 1)
                }
            }
            if (t.section == 0) Icon(Icons.Pin, c.mutedForeground.copy(alpha = 0.65f), 12.dp, description = "Pinned")
            when {
                section == SidebarSection.SNOOZED && t.snoozedUntil > 0 -> TimeLabel(Snooze.wakeLabel(t.snoozedUntil, now), c.infoForeground)
                woke -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.AlarmClock, c.warningForeground, 12.dp)
                    Txt("Woke", CandyType.xsMedium, c.warningForeground, maxLines = 1)
                }
                else -> TimeLabel(relativeTime(if (section == SidebarSection.SETTLED) t.sortTime else t.activityAt, now), c.secondaryLabel)
            }
        }
        menu()
    }
}

/**
 * `CollapsibleSectionHeader`: the label, a hairline to the edge and a chevron that points down
 * when open. Snoozed takes the info tone.
 */
@Composable
private fun ShelfHeader(label: String, expanded: Boolean, info: Boolean, onToggle: () -> Unit) {
    val c = Candy.colors
    val color = if (info) c.infoForeground else c.sidebarMutedForeground.copy(alpha = 0.6f)
    val line = if (info) c.info.copy(alpha = 0.2f) else c.sidebarBorder.copy(alpha = 0.6f)
    val turn by animateFloatAsState(if (expanded) 0f else 180f, Motion.fast(), label = "shelf")
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(32.dp).clip(RoundedCornerShape(Radius.md))
            .combinedClickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(label, CandyType.xsMedium, color, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Icon(Icons.ChevronDown, color, 12.dp, Modifier.rotate(turn))
    }
}

/** "Show 25 more" under an open Settled shelf. */
@Composable
private fun ShowMore(count: Int, onClick: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(Radius.md)).combinedClickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Plus, c.sidebarMutedForeground.copy(alpha = 0.55f), 16.dp)
        Txt("Show $count more", CandyType.sm, c.sidebarMutedForeground.copy(alpha = 0.55f), maxLines = 1)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Txt(text, CandyType.xs, Candy.colors.mutedForeground.copy(alpha = 0.6f))
    }
}

/** The archived list behind the footer's archive button, newest first, as slim rows. */
@Composable
private fun ArchivedRows(
    archived: ArchivedList,
    now: Long,
    openKey: String?,
    menuFor: String?,
    onMenu: (String) -> Unit,
    actions: SidebarActions,
    row: (ThreadSummary) -> RowInfo,
    menu: @Composable (ThreadSummary) -> Unit,
) {
    val model = archived.model
    when {
        archived.error != null -> SidebarNote(archived.error, "Retry") { actions.toggleArchived(); actions.toggleArchived() }
        model == null -> SidebarNote("Loading archived threads")
        model.threadCount == 0 -> SidebarNote("No archived threads.")
        else -> {
            val threads = remember(model) { model.groups.flatMap { it.threads }.sortedByDescending { it.activityAt } }
            Column {
                Txt("Archived", CandyType.xsMedium, Candy.colors.sidebarMutedForeground, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    items(threads, key = { it.key }, contentType = { "slim" }) { t ->
                        SlimRow(row(t).copy(active = t.key == openKey), SidebarSection.ACTIVE, now, menuFor == t.key, { actions.openThread(t) }, { onMenu(t.key) }, showProject = true) { menu(t) }
                    }
                }
            }
        }
    }
}

/**
 * A row's actions on long press: what T3 puts on hover (Settle, Snooze, Unpin, Woke) and in its
 * context menu. Settle, snooze and pin show only where the machine's server supports them.
 */
@Composable
private fun RowMenu(
    t: ThreadSummary,
    section: SidebarSection,
    caps: MachineCaps,
    now: Long,
    open: Boolean,
    onDismiss: () -> Unit,
    actions: SidebarActions,
    archivedOpen: Boolean,
) {
    var snoozing by remember(open) { mutableStateOf(false) }
    fun run(action: (ThreadSummary) -> Unit) { onDismiss(); action(t) }
    Menu(open, onDismiss, minWidth = 200.dp) {
        when {
            archivedOpen -> MenuItem("Unarchive", Icons.ArchiveX) { run(actions.unarchive) }
            snoozing -> {
                MenuLabel("Snooze until")
                for (p in remember(now) { Snooze.presets(now) }) {
                    MenuItem(p.label, trailing = { Txt(p.whenLabel, CandyType.sm, Candy.colors.mutedForeground, maxLines = 1) }) { onDismiss(); actions.snooze(t, p.untilMs) }
                }
            }
            else -> {
                if (t.isWoke(now)) MenuItem("Dismiss Woke", Icons.AlarmClockOff) { run(actions.acknowledgeWoke) }
                if (caps.pin && section != SidebarSection.SNOOZED && section != SidebarSection.SETTLED) {
                    if (t.section == 0) MenuItem("Unpin", Icons.PinOff) { run(actions.unpin) } else MenuItem("Pin", Icons.Pin) { run(actions.pin) }
                }
                if (caps.snooze) {
                    if (section == SidebarSection.SNOOZED) MenuItem("Wake now", Icons.AlarmClockOff) { run(actions.unsnooze) }
                    else if (Snooze.canSnooze(t)) MenuItem("Snooze", Icons.AlarmClock) { snoozing = true }
                }
                if (caps.settle) {
                    if (section == SidebarSection.SETTLED) MenuItem("Un-settle", Icons.Undo2) { run(actions.unsettle) }
                    else MenuItem("Settle", Icons.Check) { run(actions.settle) }
                }
                actions.handsOff(t)?.let { off ->
                    MenuItem(if (off) "Let the Orchestrator prompt it" else "Hands off", if (off) Icons.Shield else Icons.ShieldCheck) { run(actions.toggleHandsOff) }
                }
                MenuSeparator()
                MenuItem("Rename thread", Icons.Pencil) { run(actions.rename) }
                if (t.unread || t.pill == StatusPill.READY) {
                    MenuItem(if (t.unread) "Mark read" else "Mark unread", if (t.unread) Icons.Eye else Icons.EyeOff) { run(actions.toggleRead) }
                }
                MenuSeparator()
                MenuItem("Archive", Icons.Archive, enabled = t.pill != StatusPill.WORKING) { run(actions.archive) }
            }
        }
    }
}

/**
 * T3's "New thread in" palette as a sheet: every project group, the preferred one first, with the
 * machines it is on. A project on several machines then asks which.
 */
@Composable
fun BoxScope.NewThreadSheet(
    groups: List<SidebarProjectGroup>?,
    machineChoice: SidebarProjectGroup?,
    machines: Map<String, String>,
    onDismiss: () -> Unit,
    onGroup: (SidebarProjectGroup) -> Unit,
    onStart: (envId: String, projectId: String) -> Unit,
) {
    val c = Candy.colors
    if (machineChoice != null) {
        Sheet(onDismiss, "New thread in ${machineChoice.label}", description = "This project is on more than one machine. Pick where the thread runs.") {
            for (p in machineChoice.members) {
                ListRow(machines[p.envId] ?: p.envId, icon = Icons.Laptop, detail = p.workspaceRoot) { onStart(p.envId, p.id) }
            }
        }
    } else if (groups != null) {
        Sheet(onDismiss, "New thread in") {
            for (g in groups) {
                ListRow(
                    g.label,
                    leading = { ProjectIcon(g.representative, g.label, size = 18.dp) },
                    detail = if (g.envIds.size > 1 || machines.size > 1) g.envIds.mapNotNull { machines[it] }.joinToString(" · ") else null,
                    trailing = { Icon(Icons.ChevronRight, c.mutedForeground, 16.dp) },
                ) { onGroup(g) }
            }
        }
    }
}

/** Redesign's Pinned section key, beside the project group keys. */
private const val PINNED_SECTION = "pinned"

/**
 * Redesign's top of the sidebar, after Capy: New thread as a nav row, the search field, then a
 * "Threads" label carrying the project filter, so each control is named once.
 */
@Composable
private fun RedesignHeader(query: String, onQuery: (String) -> Unit, data: SidebarData?, actions: SidebarActions) {
    val c = Candy.colors
    var scopeOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(Radius.md))
                .combinedClickable(role = Role.Button, onClick = actions.newThreadAnywhere).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.SquarePen, c.sidebarMutedForeground, 16.dp)
            Txt("New thread", CandyType.sm, c.sidebarForeground, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(Radius.md)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        if (query.isEmpty()) Txt("Search", CandyType.sm, c.sidebarForeground, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) IconButton(Icons.X, "Clear thread search", tint = c.sidebarMutedForeground, size = 12.dp, box = 32.dp) { onQuery("") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(28.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Threads", CandyType.xsMedium, c.sidebarMutedForeground, maxLines = 1, modifier = Modifier.weight(1f))
            val groups = data?.groups.orEmpty()
            if (groups.isNotEmpty()) {
                Box {
                    val scope = data?.scope
                    if (scope == null) IconButton(Icons.ListFilter, "Filter threads by project", tint = c.sidebarMutedForeground, size = 14.dp, box = 28.dp) { scopeOpen = true }
                    else Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(Radius.md)).background(c.sidebarRowActive)
                            .combinedClickable(role = Role.Button, onClickLabel = "Filter threads by project: ${scope.label}") { scopeOpen = true },
                        contentAlignment = Alignment.Center,
                    ) { ProjectIcon(scope.representative, scope.label, size = 14.dp) }
                    ScopeMenu(scopeOpen, { scopeOpen = false }, groups, scope, data?.spansMachines == true, actions)
                }
            }
        }
    }
}

/**
 * A Redesign section header (SidebarProjectHeaderRowView): quiet so the threads lead. The 16dp
 * project icon (a pin for Pinned), the name in muted 12, the count a step lighter, and a chevron
 * while folded. It sits on the lower 22dp of its 32dp, so sections breathe above. A tap folds it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeader(group: SidebarProjectGroup?, label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(Radius.md))
            .combinedClickable(role = Role.Button, onClickLabel = if (expanded) "Fold" else "Open", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded, $count threads" else "Folded, $count threads" }
            .padding(start = 8.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group == null && label == "Pinned") Icon(Icons.Pin, c.sidebarMutedForeground, 16.dp)
        else ProjectIcon(group?.representative, label)
        Spacer(Modifier.width(6.dp))
        Txt(label, CandyType.xsMedium, c.sidebarMutedForeground, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(5.dp))
        Txt("$count", CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), c.sidebarMutedForeground.copy(alpha = 0.6f), maxLines = 1)
        if (!expanded) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.ChevronRight, c.sidebarMutedForeground, 12.dp)
        }
    }
}

/**
 * Redesign's thread row (after Capy, 48dp): the title with the status at its right, then a small
 * gray line with the branch and the time. Under a section the header names the project; elsewhere
 * a folder icon leads and the line names the project. Working shows Capy's dot grid, Waiting a white
 * one. Long press opens the row's actions, Capy's hover group on a phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CapyRow(info: RowInfo, now: Long, inSection: Boolean, menuOpen: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, menu: @Composable () -> Unit) {
    val c = Candy.colors
    val t = info.thread
    val woke = t.isWoke(now)
    val recede = SidebarStatus.recede(t.pill, t.unread, woke, info.active)
    val top = SidebarStatus.top(t.pill, t.unread, woke)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        info.active -> c.sidebarRowActive
        pressed || menuOpen -> c.sidebarRowHover.compositeOverSidebar(c)
        else -> Color.Transparent
    }
    val titleColor = when {
        recede -> c.secondaryLabel
        t.unread || woke || t.pill == StatusPill.INPUT -> c.foreground
        t.pill == StatusPill.FAILED -> c.foreground.copy(alpha = 0.95f)
        else -> c.foreground.copy(alpha = 0.9f)
    }
    val tnum = CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum"))
    Box(Modifier.padding(vertical = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radius.xl)).background(fill)
                .graphicsLayer { alpha = if (recede && t.pill == StatusPill.WORKING) 0.7f else 1f }
                .combinedClickable(source, null, role = Role.Button, onLongClickLabel = "Thread actions", onLongClick = onLongClick, onClick = onClick)
                .semantics { stateDescription = listOfNotNull(top?.let(::topLabel), info.project).joinToString() }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!inSection) {
                Icon(Icons.Folder, c.sidebarMutedForeground, 16.dp, Modifier.padding(end = 8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(t.title, CandyType.sm, titleColor, maxLines = 1, modifier = Modifier.weight(1f), weight = if (recede) FontWeight.Normal else FontWeight.Medium)
                    if (t.section == 0 && !inSection) Icon(Icons.Pin, c.mutedForeground.copy(alpha = 0.65f), 12.dp, Modifier.padding(start = 6.dp), description = "Pinned")
                    when (top) {
                        null -> if (!inSection) Txt(relativeTime(t.activityAt, now), tnum, c.secondaryLabel, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                        TopStatus.WORKING, TopStatus.WAITING -> Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val color = if (top == TopStatus.WORKING) c.info else c.foreground
                            if (t.workingSince > 0) TickingText(t.workingSince, ::duration, tnum, c.secondaryLabel, roll = true)
                            DotGrid(color, Modifier.size(16.dp), running = top == TopStatus.WORKING, small = top == TopStatus.WAITING)
                        }
                        else -> {
                            val color = when (top) {
                                TopStatus.APPROVAL -> c.warningForeground
                                TopStatus.INPUT -> c.statusInput
                                TopStatus.FAILED -> c.error
                                TopStatus.LIMITED, TopStatus.WOKE -> c.warning
                                else -> c.success
                            }
                            Txt(topLabel(top), CandyType.xsMedium, color, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                val meta = listOfNotNull(
                    if (!inSection && t.branch == null) info.project else null,
                    t.branch,
                    info.machine,
                    if (inSection) relativeTime(t.activityAt, now) else null,
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) Txt(meta, tnum.copy(fontSize = 11.sp, lineHeight = 16.sp), c.secondaryLabel, maxLines = 1)
            }
        }
        menu()
    }
}
