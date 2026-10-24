package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.BlockReason
import org.anikodev.candywrapper.core.sync.ConnectionState

/** The machine switcher's id for every machine at once (AppGraph `ALL_MACHINES`). */
const val AllMachines = "all"

/** What a machine's computer can do to threads (`environment.capabilities`), and its providers' drivers for the glyphs. */
@Immutable
data class MachineCaps(
    val settle: Boolean = false,
    val snooze: Boolean = false,
    val pin: Boolean = false,
    /** Provider instance id to driver. */
    val drivers: Map<String, String> = emptyMap(),
)

/** One computer as the sidebar's switcher shows it, or "All machines" when [id] is [AllMachines]. */
@Immutable
data class SidebarEnvironment(
    val id: String,
    val label: String,
    val kind: EnvironmentKind,
    val state: ConnectionState,
    /** Threads waiting on an approval or an answer. */
    val waiting: Int = 0,
    val caps: MachineCaps = MachineCaps(),
    /** Replaces the state line, for All machines. */
    val summary: String? = null,
) {
    val all: Boolean get() = id == AllMachines

    companion object {
        /** The All machines entry: live when every machine is, the count of connected ones under it. */
        fun all(machines: List<SidebarEnvironment>): SidebarEnvironment {
            val live = machines.count { it.state is ConnectionState.Live }
            val state = machines.firstOrNull { it.state !is ConnectionState.Live }?.state.takeIf { live < machines.size } ?: machines.first().state
            return SidebarEnvironment(
                AllMachines, "All machines", EnvironmentKind.PAIRED, state, machines.sumOf { it.waiting },
                summary = if (live == machines.size) "${machines.size} machines connected" else "$live of ${machines.size} machines connected",
            )
        }
    }
}

/** The archived list: closed, loading, loaded or failed. */
@Immutable
data class ArchivedList(val open: Boolean = false, val model: ThreadListModel? = null, val error: String? = null)

/** The footer's "Settled 1 thread, Undo" (SidebarThreadUndoNotice), for five seconds after a park. */
@Immutable
data class UndoNotice(val action: String, val count: Int, val id: Long)

/**
 * What the sidebars ask of the app. Every thread carries its computer, so one set of actions serves
 * one machine or all of them. The host keeps the instance, so rows keep equal lambdas.
 */
class SidebarActions(
    val openThread: (ThreadSummary) -> Unit = {},
    val newThread: (envId: String, projectId: String) -> Unit = { _, _ -> },
    /** The header's New thread: the host asks which project when there is more than one. */
    val newThreadAnywhere: () -> Unit = {},
    val switchMachine: (String) -> Unit = {},
    val pair: () -> Unit = {},
    val connect: () -> Unit = {},
    val settings: () -> Unit = {},
    val timing: () -> Unit = {},
    val retry: (envId: String, BlockReason?) -> Unit = { _, _ -> },
    val rename: (ThreadSummary) -> Unit = {},
    val toggleRead: (ThreadSummary) -> Unit = {},
    val archive: (ThreadSummary) -> Unit = {},
    val unarchive: (ThreadSummary) -> Unit = {},
    val settle: (ThreadSummary) -> Unit = {},
    val unsettle: (ThreadSummary) -> Unit = {},
    val snooze: (ThreadSummary, untilMs: Long) -> Unit = { _, _ -> },
    val unsnooze: (ThreadSummary) -> Unit = {},
    val pin: (ThreadSummary) -> Unit = {},
    val unpin: (ThreadSummary) -> Unit = {},
    /** Dismisses Woke: a visit at the wake time (`useAcknowledgeThreadWoke`). */
    val acknowledgeWoke: (ThreadSummary) -> Unit = {},
    val toggleArchived: () -> Unit = {},
    /** The project filter: a group key, null for All projects. */
    val scopeProject: (String?) -> Unit = {},
    val toggleSettled: () -> Unit = {},
    val toggleSnoozed: () -> Unit = {},
    val toggleWorking: () -> Unit = {},
    /** Redesign: folds or opens a project section, by group key ("pinned" for Pinned). */
    val toggleSection: (String) -> Unit = {},
    val undo: () -> Unit = {},
    /** Pull-to-refresh: check the connection now. */
    val refresh: () -> Unit = {},
    /** The Orchestrator's pinned row (Redesign, Settings > Experimental Orchestrator features). */
    val openOrchestrator: () -> Unit = {},
    val renameOrchestrator: () -> Unit = {},
    /** Whether the Orchestrator leaves a thread alone; null while the Orchestrator is off, which hides the item. */
    val handsOff: (ThreadSummary) -> Boolean? = { null },
    val toggleHandsOff: (ThreadSummary) -> Unit = {},
    /** The "Pick a picture for Redesign" banner: open the photo picker, or close the banner. */
    val pickPicture: () -> Unit = {},
    val dismissPicturePrompt: () -> Unit = {},
)

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
 * The header: the machine switcher (a `SidebarMenuButton size="lg"`) where T3 puts its wordmark,
 * with the connection's real state under the name. Its menu lists All machines when there are
 * several, every machine, pairing and T3 Connect.
 */
@Composable
fun MachineSwitcher(current: SidebarEnvironment?, environments: List<SidebarEnvironment>, actions: SidebarActions) {
    val c = Candy.colors
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(Radius.lg))
                .background(if (pressed || open) c.sidebarRowActive else Color.Transparent)
                .combinedClickable(source, null, role = Role.Button, onClickLabel = "Switch machine") { open = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(Radius.md)).background(c.sidebarControlSurface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(current.icon(), c.sidebarForeground, 16.dp)
            }
            Column(Modifier.weight(1f)) {
                Txt(current?.label ?: "No computer", CandyType.smMedium, c.sidebarForeground, maxLines = 1)
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(current.state.dotColor())
                        Txt(current.summary ?: current.state.label(), CandyType.xs, c.sidebarMutedForeground, maxLines = 1)
                    }
                }
            }
            Icon(Icons.ChevronsUpDown, c.sidebarMutedForeground, 16.dp)
        }
        Menu(open, { open = false }, minWidth = 260.dp) {
            if (environments.size > 1) {
                val all = SidebarEnvironment.all(environments)
                MenuItem(
                    all.label, Icons.Layers, detail = all.summary, selected = current?.all == true,
                    trailing = if (all.waiting > 0) ({ Pill("${all.waiting} waiting", c.statusApproval) }) else null,
                ) { open = false; actions.switchMachine(AllMachines) }
                MenuSeparator()
            }
            if (environments.isNotEmpty()) MenuLabel("Machines")
            for (e in environments) {
                MenuItem(
                    e.label, e.icon(),
                    detail = e.state.label(), selected = e.id == current?.id,
                    trailing = if (e.waiting > 0) ({ Pill("${e.waiting} waiting", c.statusApproval) }) else null,
                ) { open = false; actions.switchMachine(e.id) }
            }
            if (environments.isNotEmpty()) MenuSeparator()
            MenuItem("Pair a computer", Icons.Plus) { open = false; actions.pair() }
            MenuItem("T3 Connect", Icons.Cloud) { open = false; actions.connect() }
        }
    }
}

private fun SidebarEnvironment?.icon() = when {
    this == null -> Icons.Laptop
    all -> Icons.Layers
    kind == EnvironmentKind.CONNECT -> Icons.Cloud
    else -> Icons.Laptop
}

/** The connection's dot: success when live, the error colour when it needs the user, muted while it works. */
@Composable
fun ConnectionState.dotColor(): Color = when (this) {
    is ConnectionState.Live -> Candy.colors.success
    is ConnectionState.Offline, is ConnectionState.Blocked -> Candy.colors.error
    is ConnectionState.Connecting -> Candy.colors.statusWorkingDot
    ConnectionState.Idle -> Candy.colors.sidebarMutedForeground
}

/**
 * Blocked and offline states under the header, with the action that fixes them. With several
 * machines in view only the blocked ones show, named, since an offline one retries by itself.
 */
@Composable
fun ConnectionAlerts(current: SidebarEnvironment?, environments: List<SidebarEnvironment>, actions: SidebarActions) {
    if (current == null) return
    val shown = if (current.all) environments.filter { it.state is ConnectionState.Blocked } else listOf(current)
    for (e in shown) {
        val prefix = if (current.all) "${e.label}: " else ""
        when (val state = e.state) {
            is ConnectionState.Blocked -> Alert(
                prefix + state.message, NoticeTone.ERROR, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                action = when (state.reason) { BlockReason.SIGN_IN -> "Sign in"; BlockReason.PAIR_AGAIN -> "Pair"; else -> "Retry" },
            ) { actions.retry(e.id, state.reason) }
            is ConnectionState.Offline -> Alert(prefix + state.message, NoticeTone.NEUTRAL, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), action = "Retry") { actions.retry(e.id, null) }
            else -> Unit
        }
    }
}

/** Redesign is on with no photo, as after an update from the painted pictures: a tap opens the picker, the X closes it. */
@Composable
fun PicturePrompt(actions: SidebarActions) {
    Alert(
        "Pick a picture for Redesign", NoticeTone.INFO,
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(Radius.xl)).clickable(role = Role.Button, onClick = actions.pickPicture),
        onDismiss = actions.dismissPicturePrompt,
    )
}

@Composable
fun SidebarNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(text, CandyType.sm, Candy.colors.sidebarMutedForeground)
        if (action != null) CButton(action, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, onClick = onAction)
    }
}

/** On the light sidebar the hover token is lighter than the sidebar itself; pressing reads as a step darker instead. */
fun Color.compositeOverSidebar(c: CandyColors): Color = if (c.dark) this else c.sidebarControlSurface

/** `SidebarChromeFooter`: the undo notice, then Settings, the archived list and the Timing screen. */
@Composable
fun SidebarFooter(archivedOpen: Boolean, actions: SidebarActions, undo: UndoNotice? = null) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        if (undo != null) {
            val noun = if (undo.count == 1) "thread" else "threads"
            Alert("${undo.action} ${undo.count} $noun", NoticeTone.NEUTRAL, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), action = "Undo", onAction = actions.undo)
        }
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
