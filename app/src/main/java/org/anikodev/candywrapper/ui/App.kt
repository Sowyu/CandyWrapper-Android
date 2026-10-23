package org.anikodev.candywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.BlockReason
import org.anikodev.candywrapper.core.sync.Commands
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.timing.Timeline
import org.anikodev.candywrapper.update.UpdateBanner

sealed interface Screen {
    /** The sidebar and one environment's open thread, or T3's empty state when none is open. */
    data class Main(val envId: String?, val threadId: String?) : Screen
    data class NewThread(val envId: String, val projectId: String) : Screen
    data class Diff(val envId: String, val threadId: String, val checkpointId: String, val title: String) : Screen
    data class Pair(val prefill: String? = null) : Screen
    data object Connect : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
}

/** A plain back stack. Screens swap on the next frame; the sidebar is the only thing that slides. */
class Navigator(initial: List<Screen>, private val onPopped: (Screen) -> Unit = {}) {
    val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.size > 1) onPopped(stack.removeAt(stack.lastIndex)) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    /** Makes [s] the only screen, for opening a thread or switching environments. */
    fun reset(s: Screen) { stack.clear(); stack += s }
    /** The thread the sidebar marks open: the root's. */
    val main: Screen.Main get() = stack.first() as? Screen.Main ?: Screen.Main(null, null)
}

@Composable
fun App(graph: AppGraph, sharedText: String?, consumeShared: () -> Unit) {
    val nav = remember {
        // A cold start reopens the last thread with the sidebar closed; its rows were read at boot.
        val envs = graph.environments.sessions.value
        val env = graph.lastEnvironmentId?.takeIf { graph.environments.session(it) != null } ?: envs.firstOrNull()?.id
        val thread = graph.lastThreadId?.takeIf { env != null && env == graph.lastEnvironmentId }
        Navigator(listOf(Screen.Main(env, thread)))
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            nav.push(Screen.Pair(sharedText))
            consumeShared()
        }
    }
    val main = nav.main
    LaunchedEffect(main) { main.envId?.let { graph.rememberThread(it, main.threadId) } }
    val drawer = remember { DrawerState(open = main.threadId == null) }
    // With no thread open the sidebar is where the next step is, so a phone shows it.
    LaunchedEffect(main) { if (main.threadId == null && !drawer.isOpen) drawer.open() }
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Pair<String, ThreadSummary>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    fun act(session: EnvironmentSession, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = EnvironmentSession.describe(e)
            }
        }
    }

    // Keeps scroll positions and drafts of screens under the top one.
    val saveable = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Candy.colors.background)) {
        val screen: @Composable () -> Unit = {
            val s = nav.top
            saveable.SaveableStateProvider(s.toString()) {
                when (s) {
                    is Screen.Main -> if (s.envId != null && s.threadId != null) ThreadScreen(graph, nav, s.envId, s.threadId) else NoThread(sessions.isNotEmpty())
                    is Screen.NewThread -> NewThreadScreen(graph, nav, s.envId, s.projectId)
                    is Screen.Diff -> DiffScreen(graph, nav, s)
                    is Screen.Pair -> PairScreen(graph, nav, s.prefill)
                    Screen.Connect -> ConnectScreen(graph, nav)
                    Screen.Settings -> SettingsScreen(graph, nav)
                    Screen.Diagnostics -> DiagnosticsScreen(graph, nav)
                }
            }
        }
        when {
            // Nothing paired yet: no sidebar, only the welcome screen and the screens it opens.
            sessions.isEmpty() && nav.stack.size == 1 -> Welcome(nav)
            sessions.isEmpty() -> screen()
            else -> AppShell(
                drawer,
                sidebar = {
                    SidebarHost(graph, nav, main, drawer, onRename = { env, t -> renaming = env to t }) { s, block -> act(s, block) }
                },
                content = screen,
            )
        }
        notice?.let { m ->
            Alert(m, NoticeTone.ERROR, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(12.dp), action = "Dismiss") { notice = null }
        }
        UpdateBanner(graph.updater, Modifier.align(Alignment.BottomCenter))
        renaming?.let { (envId, thread) ->
            RenameSheet(thread.title, onDismiss = { renaming = null }) { title ->
                renaming = null
                graph.environments.session(envId)?.let { s -> act(s) { s.dispatch(Commands.rename(thread.id, title)) } }
            }
        }
    }
}

/**
 * Collects what the sidebar shows (the open environment's thread list, every environment's
 * connection, the archived list while it is open) and turns its taps into navigation and commands.
 */
@Composable
private fun SidebarHost(
    graph: AppGraph,
    nav: Navigator,
    main: Screen.Main,
    drawer: DrawerState,
    onRename: (String, ThreadSummary) -> Unit,
    act: (EnvironmentSession, suspend () -> Unit) -> Unit,
) {
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    val session = sessions.firstOrNull { it.id == main.envId } ?: sessions.firstOrNull() ?: return
    val model by session.threads.collectAsStateWithLifecycle()
    val now = rememberMinuteClock()
    val scope = rememberCoroutineScope()
    val environments = sessions.map { s -> key(s.id) { sidebarEnvironment(s) } }
    val current = environments.firstOrNull { it.id == session.id }
    var archived by remember(session.id) { mutableStateOf(ArchivedList()) }
    // Each opening of the archived list reads it again; it is not cached.
    var archivedLoads by remember(session.id) { mutableIntStateOf(0) }
    // Keyed on the open state too, so closing the list cancels a read still on its way.
    LaunchedEffect(archivedLoads, archived.open) {
        if (archivedLoads == 0 || !archived.open || archived.model != null || archived.error != null) return@LaunchedEffect
        archived = try {
            ArchivedList(open = true, model = session.archivedThreads())
        } catch (e: TimeoutCancellationException) {
            ArchivedList(open = true, error = "The computer did not answer in time.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArchivedList(open = true, error = EnvironmentSession.describe(e))
        }
    }

    fun closeOnPhone() { scope.launch { drawer.close() } }

    // One actions object per environment, so thread rows keep equal lambdas and skip recomposing
    // when the list updates. What changes between updates is read through these states.
    val mainNow by rememberUpdatedState(main)
    val actNow by rememberUpdatedState(act)
    val renameNow by rememberUpdatedState(onRename)
    val actions = remember(session.id, nav, drawer) {
        SidebarActions(
            openThread = { t ->
                Timeline.mark("thread.tap", t.id.take(8))
                nav.reset(Screen.Main(session.id, t.id))
                closeOnPhone()
            },
            newThread = { pid -> nav.reset(Screen.Main(session.id, mainNow.threadId)); nav.push(Screen.NewThread(session.id, pid)); closeOnPhone() },
            switchEnvironment = { id -> if (id != session.id) nav.reset(Screen.Main(id, null)) },
            pair = { nav.push(Screen.Pair()); closeOnPhone() },
            connect = { nav.push(Screen.Connect); closeOnPhone() },
            settings = { nav.push(Screen.Settings); closeOnPhone() },
            timing = { nav.push(Screen.Diagnostics); closeOnPhone() },
            retry = { reason ->
                when (reason) {
                    BlockReason.SIGN_IN -> { nav.push(Screen.Connect); closeOnPhone() }
                    BlockReason.PAIR_AGAIN -> { nav.push(Screen.Pair()); closeOnPhone() }
                    else -> session.wake()
                }
            },
            rename = { t -> renameNow(session.id, t) },
            toggleRead = { t ->
                actNow(session) {
                    session.dispatch(if (t.unread) Commands.visit(t.id, t.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) else Commands.markUnread(t.id))
                }
            },
            archive = { t ->
                actNow(session) { session.dispatch(Commands.archive(t.id)) }
                if (t.id == mainNow.threadId) nav.reset(Screen.Main(session.id, null))
            },
            unarchive = { t ->
                actNow(session) {
                    session.dispatch(Commands.unarchive(t.id))
                    archived = archived.copy(model = archived.model?.let { m ->
                        ThreadListModel.build(m.groups.mapNotNull { it.project }.associateBy { it.id }, m.groups.flatMap { g -> g.threads.filter { it.id != t.id } })
                    })
                }
            },
            refresh = { session.networkChanged() },
            toggleArchived = {
                archived = if (archived.open) ArchivedList() else ArchivedList(open = true)
                if (archived.open) archivedLoads++
            },
        )
    }

    SidebarPanel(
        current = current,
        environments = environments,
        model = model,
        activeThreadId = main.threadId.takeIf { main.envId == session.id },
        now = now,
        archived = archived,
        actions = actions,
    )
}

@Composable
private fun sidebarEnvironment(s: EnvironmentSession): SidebarEnvironment {
    val env by s.env.collectAsStateWithLifecycle()
    val state by s.state.collectAsStateWithLifecycle()
    val threads by s.threads.collectAsStateWithLifecycle()
    val waiting = remember(threads) { threads.groups.sumOf { g -> g.threads.count { it.pill == StatusPill.APPROVAL || it.pill == StatusPill.INPUT } } }
    return SidebarEnvironment(s.id, env.label, env.kind, state, waiting)
}

/** T3's `NoActiveThreadState`: the header and "Pick a thread to continue". */
@Composable
private fun NoThread(hasEnvironments: Boolean) {
    val c = Candy.colors
    val shell = LocalShell.current
    Column(Modifier.fillMaxSize().background(c.background)) {
        TopBar(
            "No active thread",
            leading = if (!shell.wide) ({ IconButton(Icons.PanelLeft, "Open the sidebar", tint = c.foreground, onClick = shell.openSidebar) }) else null,
        )
        Box(Modifier.weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
            EmptyState(
                "Pick a thread to continue",
                if (hasEnvironments) "Select an existing thread or create a new one to get started." else "Pair a computer to see its threads.",
            )
        }
    }
}

/** T3's rename dialog, pinned to the bottom edge as T3 does on phones. */
@Composable
fun BoxScope.RenameSheet(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(current) }
    val valid = title.isNotBlank() && title.trim() != current
    Sheet(
        onDismiss, "Rename thread",
        footer = {
            CButton("Cancel", onClick = onDismiss)
            CButton("Save", kind = ButtonKind.PRIMARY, enabled = valid) { onSave(title.trim()) }
        },
    ) {
        TextInput(
            title, { title = it }, "Thread title", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            keyboard = KeyboardOptions(imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { if (valid) onSave(title.trim()) }),
        )
    }
}

/** The first run: nothing paired yet. */
@Composable
private fun Welcome(nav: Navigator) {
    val c = Candy.colors
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.padding(top = 4.dp, end = 4.dp).align(Alignment.End)) {
            IconButton(Icons.Settings, "Settings") { nav.push(Screen.Settings) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EmptyState(
                "Connect a computer",
                "Run npx t3 pair on the computer running T3 Code and paste the link here, or sign in to T3 Connect to reach the computers linked to your account.",
                icon = Icons.Laptop,
            ) {
                CButton("T3 Connect") { nav.push(Screen.Connect) }
                CButton("Pair a computer", kind = ButtonKind.PRIMARY) { nav.push(Screen.Pair()) }
            }
        }
        Txt(
            "The phone keeps a copy of what it has seen, so threads open instantly next time.",
            CandyType.xs, c.mutedForeground, modifier = Modifier.padding(24.dp),
        )
    }
}
