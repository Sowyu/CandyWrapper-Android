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
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.sidebar.NewThread
import org.anikodev.candywrapper.core.sidebar.NewThreadTarget
import org.anikodev.candywrapper.core.sidebar.SidebarData
import org.anikodev.candywrapper.core.sidebar.SidebarModel
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
    /** Redesign's Orchestrator pane, full screen on a phone. */
    data object Orchestrator : Screen
    /** Settings > Orchestrator. */
    data object OrchestratorSettings : Screen
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
    var renaming by remember { mutableStateOf<ThreadSummary?>(null) }
    var renamingOrchestrator by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<NewThreadTarget?>(null) }
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
                    Screen.Orchestrator -> OrchestratorScreen(graph, nav)
                    Screen.OrchestratorSettings -> OrchestratorSettingsScreen(graph, nav)
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
                    SidebarHost(graph, nav, main, drawer, onRename = { t -> renaming = t }, onNewThreadIn = { picker = it }, onRenameOrchestrator = { renamingOrchestrator = true }) { s, block -> act(s, block) }
                },
                content = screen,
            )
        }
        notice?.let { m ->
            Alert(m, NoticeTone.ERROR, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(12.dp), action = "Dismiss") { notice = null }
        }
        UpdateBanner(graph.updater, Modifier.align(Alignment.BottomCenter))
        renaming?.let { thread ->
            RenameSheet(thread.title, onDismiss = { renaming = null }) { title ->
                renaming = null
                graph.environments.session(thread.envId)?.let { s -> act(s) { s.dispatch(Commands.rename(thread.id, title)) } }
            }
        }
        if (renamingOrchestrator) {
            // A rename changes the brief's {{name}}, so it goes out as an updated brief with your next message.
            RenameSheet(graph.orchestratorStore.config.value.displayName, title = "Rename the Orchestrator", onDismiss = { renamingOrchestrator = false }) { name ->
                renamingOrchestrator = false
                graph.orchestratorStore.update { it.copy(name = name) }
            }
        }
        picker?.let { target ->
            fun start(envId: String, projectId: String) {
                picker = null
                nav.reset(Screen.Main(main.envId, main.threadId))
                nav.push(Screen.NewThread(envId, projectId))
                scope.launch { drawer.close() }
            }
            NewThreadSheet(
                groups = (target as? NewThreadTarget.PickProject)?.groups,
                machineChoice = (target as? NewThreadTarget.PickMachine)?.group,
                machines = sessions.associate { it.id to it.env.value.label },
                onDismiss = { picker = null },
                onGroup = { g ->
                    when (val next = NewThread.inGroup(g)) {
                        is NewThreadTarget.Start -> start(next.envId, next.projectId)
                        else -> picker = next
                    }
                },
                onStart = ::start,
            )
        }
    }
}

/**
 * Collects what the sidebar shows and turns its taps into navigation and commands. The machine
 * switcher picks one machine or all of them; with more than one configured, all is the default.
 * The new sidebar's model is merged off the main thread from every scoped machine's cached shell,
 * so the first frame never waits on it.
 */
@Composable
private fun SidebarHost(
    graph: AppGraph,
    nav: Navigator,
    main: Screen.Main,
    drawer: DrawerState,
    onRename: (ThreadSummary) -> Unit,
    onNewThreadIn: (NewThreadTarget) -> Unit,
    onRenameOrchestrator: () -> Unit,
    act: (EnvironmentSession, suspend () -> Unit) -> Unit,
) {
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) return
    val legacy by graph.legacySidebar.value.collectAsStateWithLifecycle()
    val machineSetting by graph.machineScope.value.collectAsStateWithLifecycle()
    val all = sessions.size > 1 && (machineSetting == null || machineSetting == AllMachines || sessions.none { it.id == machineSetting })
    val scoped = if (all) sessions else listOf(sessions.firstOrNull { it.id == machineSetting } ?: sessions.firstOrNull { it.id == main.envId } ?: sessions.first())
    val environments = sessions.map { s -> key(s.id) { sidebarEnvironment(s) } }
    val current = if (all) SidebarEnvironment.all(environments) else environments.first { it.id == scoped[0].id }
    val openKey = main.threadId?.let { "${main.envId}:$it" }
    val now = rememberMinuteClock()
    val scope = rememberCoroutineScope()
    val scopeKey = scoped.joinToString { it.id }

    var archived by remember(scopeKey) { mutableStateOf(ArchivedList()) }
    // Each opening of the archived list reads it again; it is not cached.
    var archivedLoads by remember(scopeKey) { mutableIntStateOf(0) }
    // Keyed on the open state too, so closing the list cancels a read still on its way.
    LaunchedEffect(archivedLoads, archived.open) {
        if (archivedLoads == 0 || !archived.open || archived.model != null || archived.error != null) return@LaunchedEffect
        archived = try {
            val models = coroutineScope { scoped.map { s -> async { s.env.value.label to s.archivedThreads() } }.awaitAll() }
            ArchivedList(open = true, model = ThreadListModel.merge(models))
        } catch (e: TimeoutCancellationException) {
            ArchivedList(open = true, error = "The computer did not answer in time.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArchivedList(open = true, error = EnvironmentSession.describe(e))
        }
    }

    // The footer's undo for the last run of one kind of park, for five seconds.
    var undo by remember { mutableStateOf<Pair<UndoNotice, List<suspend () -> Unit>>?>(null) }
    LaunchedEffect(undo?.first?.id) {
        if (undo == null) return@LaunchedEffect
        delay(5_000)
        undo = null
    }

    // The new sidebar's model, rebuilt on Dispatchers.Default whenever a scoped shell, the project
    // filter or a snooze's end changes it. The last model stays on screen until the next is ready.
    val wake = remember { MutableStateFlow(0L) }
    val data by produceState<SidebarData?>(null, scopeKey, legacy) {
        if (legacy) return@produceState
        combine(scoped.map { it.threads }) { it.toList() }
            .combine(graph.projectScope.value) { models, key -> models to key }
            .combine(graph.workingSection.value) { input, working -> Triple(input.first, input.second, working) }
            .combine(wake) { input, _ -> input }
            .combine(graph.orchestratorStore.config.combine(graph.orchestrator.ui) { c, ui -> if (ui.active) c.machines.map { (env, m) -> "$env:${m.threadId}" }.toSet() else emptySet() }) { input, hidden -> input to hidden }
            .conflate()
            .map { (input, hidden) ->
                val (models, key, working) = input
                val now = System.currentTimeMillis()
                // The Orchestrator leaves the thread list: it sits pinned above it instead.
                val threads = models.flatMap { m -> m.groups.flatMap { it.threads } }.filter { it.key !in hidden }
                graph.inboxReturns.observe(if (working) threads else null, now)
                SidebarModel.build(models.flatMap { it.projects }, threads, key, now, workingShelf = working, returnedAt = graph.inboxReturns::returnedAt)
            }
            .flowOn(Dispatchers.Default)
            .collect { value = it }
    }
    val nextWake = data?.sections?.nextWakeAt ?: 0
    LaunchedEffect(nextWake) {
        if (nextWake <= 0) return@LaunchedEffect
        delay((nextWake - System.currentTimeMillis()).coerceAtLeast(0) + 50)
        wake.value = System.currentTimeMillis()
    }
    val settledOpen by graph.settledShelf.value.collectAsStateWithLifecycle()
    val snoozedOpen by graph.snoozedShelf.value.collectAsStateWithLifecycle()
    val workingOpen by graph.workingShelf.value.collectAsStateWithLifecycle()
    val foldedValue by graph.redesignFolded.value.collectAsStateWithLifecycle()
    val folded = remember(foldedValue) { foldedValue?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty() }
    val orchestrator by graph.orchestrator.ui.collectAsStateWithLifecycle()
    val orchestratorConfig by graph.orchestratorStore.config.collectAsStateWithLifecycle()
    val orchestratorNow by rememberUpdatedState(orchestrator)
    val handsOffNow by rememberUpdatedState(orchestratorConfig.handsOff)

    fun closeOnPhone() { scope.launch { drawer.close() } }

    // One actions object, so rows keep equal lambdas and skip recomposing when the list updates.
    // What changes between updates is read through these states.
    val mainNow by rememberUpdatedState(main)
    val actNow by rememberUpdatedState(act)
    val renameNow by rememberUpdatedState(onRename)
    val pickNow by rememberUpdatedState(onNewThreadIn)
    val dataNow by rememberUpdatedState(data)
    val scopedNow by rememberUpdatedState(scoped)
    val actions = remember(nav, drawer) {
        fun session(t: ThreadSummary) = graph.environments.session(t.envId)
        fun command(t: ThreadSummary, block: suspend (EnvironmentSession) -> Unit) { session(t)?.let { s -> actNow(s) { block(s) } } }
        fun offerUndo(action: String, inverse: suspend () -> Unit) {
            val last = undo
            undo = if (last != null && last.first.action == action) UndoNotice(action, last.first.count + 1, last.first.id) to (last.second + inverse)
            else UndoNotice(action, 1, System.nanoTime()) to listOf(inverse)
        }
        fun newThread(envId: String, projectId: String) {
            nav.reset(Screen.Main(mainNow.envId, mainNow.threadId))
            nav.push(Screen.NewThread(envId, projectId))
            closeOnPhone()
        }
        // Settle and snooze move on from the open thread, as T3 does, to the next card (planned before the park).
        fun park(t: ThreadSummary, action: String, command: JsonObject, inverse: JsonObject) {
            val next = dataNow?.sections?.let { SidebarModel.nextAfterPark(it, t.key) }
            val wasOpen = mainNow.envId == t.envId && mainNow.threadId == t.id
            command(t) { s ->
                s.dispatch(command)
                if (wasOpen) nav.reset(if (next != null) Screen.Main(next.envId, next.id) else Screen.Main(t.envId, null))
                offerUndo(action) { s.dispatch(inverse) }
            }
        }
        SidebarActions(
            openThread = { t ->
                Timeline.mark("thread.tap", t.id.take(8))
                nav.reset(Screen.Main(t.envId, t.id))
                closeOnPhone()
            },
            newThread = ::newThread,
            newThreadAnywhere = {
                val d = dataNow
                val m = mainNow
                val preferred = m.threadId?.let { id -> d?.sections?.all?.firstOrNull { it.envId == m.envId && it.id == id } }?.let { "${it.envId}:${it.projectId}" }
                    ?: d?.scope?.memberKeys?.firstOrNull()
                when (val target = NewThread.target(d?.groups.orEmpty(), preferred)) {
                    is NewThreadTarget.Start -> newThread(target.envId, target.projectId)
                    NewThreadTarget.None -> Unit
                    else -> pickNow(target)
                }
            },
            switchMachine = { id -> graph.machineScope.set(id) },
            pair = { nav.push(Screen.Pair()); closeOnPhone() },
            connect = { nav.push(Screen.Connect); closeOnPhone() },
            settings = { nav.push(Screen.Settings); closeOnPhone() },
            timing = { nav.push(Screen.Diagnostics); closeOnPhone() },
            retry = { envId, reason ->
                when (reason) {
                    BlockReason.SIGN_IN -> { nav.push(Screen.Connect); closeOnPhone() }
                    BlockReason.PAIR_AGAIN -> { nav.push(Screen.Pair()); closeOnPhone() }
                    else -> graph.environments.session(envId)?.wake()
                }
            },
            rename = { t -> renameNow(t) },
            toggleRead = { t ->
                command(t) { s -> if (t.unread) s.visit(t.id, t.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) else s.dispatch(Commands.markUnread(t.id)) }
            },
            archive = { t ->
                command(t) { s ->
                    s.dispatch(Commands.archive(t.id))
                    offerUndo("Archived") { s.dispatch(Commands.unarchive(t.id)) }
                }
                if (mainNow.envId == t.envId && mainNow.threadId == t.id) nav.reset(Screen.Main(t.envId, null))
            },
            unarchive = { t ->
                command(t) { s ->
                    s.dispatch(Commands.unarchive(t.id))
                    archived = archived.copy(model = archived.model?.let { m ->
                        m.copy(groups = m.groups.mapNotNull { g -> g.copy(threads = g.threads.filter { it.key != t.key }).takeIf { it.threads.isNotEmpty() } }, threadCount = m.threadCount - 1)
                    })
                }
            },
            settle = { t -> park(t, "Settled", Commands.settle(t.id), Commands.unsettle(t.id)) },
            unsettle = { t -> command(t) { s -> s.dispatch(Commands.unsettle(t.id)) } },
            snooze = { t, until -> park(t, "Snoozed", Commands.snooze(t.id, until), Commands.unsnooze(t.id)) },
            unsnooze = { t -> command(t) { s -> s.dispatch(Commands.unsnooze(t.id)) } },
            pin = { t -> command(t) { s -> s.dispatch(Commands.pin(t.id)) } },
            unpin = { t ->
                command(t) { s ->
                    s.dispatch(Commands.unpin(t.id))
                    offerUndo("Unpinned") { s.dispatch(Commands.pin(t.id)) }
                }
            },
            acknowledgeWoke = { t ->
                val at = t.wokeAtAt(System.currentTimeMillis())
                if (at > 0) command(t) { s -> s.visit(t.id, at) }
            },
            toggleArchived = {
                archived = if (archived.open) ArchivedList() else ArchivedList(open = true)
                if (archived.open) archivedLoads++
            },
            scopeProject = { key -> graph.projectScope.set(key) },
            toggleSettled = { graph.settledShelf.set(!graph.settledShelf.value.value) },
            toggleSnoozed = { graph.snoozedShelf.set(!graph.snoozedShelf.value.value) },
            toggleWorking = { graph.workingShelf.set(!graph.workingShelf.value.value) },
            toggleSection = { key ->
                val now = graph.redesignFolded.value.value?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
                graph.redesignFolded.set((if (key in now) now - key else now + key).joinToString("\n").ifEmpty { null })
            },
            undo = {
                val pending = undo
                undo = null
                if (pending != null) scope.launch { for (u in pending.second) runCatching { u() } }
            },
            refresh = { scopedNow.forEach { it.networkChanged() } },
            openOrchestrator = {
                nav.reset(Screen.Main(mainNow.envId, mainNow.threadId))
                nav.push(Screen.Orchestrator)
                closeOnPhone()
            },
            renameOrchestrator = onRenameOrchestrator,
            handsOff = { t -> if (orchestratorNow.active) t.key in handsOffNow else null },
            toggleHandsOff = { t -> graph.orchestrator.toggleHandsOff(t.key) },
        )
    }

    if (legacy) {
        val models = scoped.map { s -> key(s.id) { s.env.value.label to s.threads.collectAsStateWithLifecycle().value } }
        val model = remember(models) { ThreadListModel.merge(models) }
        LegacySidebarPanel(current, environments, model, openKey, now, actions, archived = archived, undo = undo?.first)
    } else {
        SidebarPanel(
            current, environments, data, openKey, now, actions,
            shelves = Shelves(snoozed = snoozedOpen, settled = settledOpen, working = workingOpen),
            folded = folded,
            archived = archived,
            undo = undo?.first,
            orchestrator = if (orchestrator.active) OrchestratorRowInfo(orchestrator.name, orchestrator.machine, orchestrator.bot, orchestrator.needs.size,
                orchestrator.working, open = nav.top == Screen.Orchestrator) else null,
        )
    }
}

@Composable
private fun sidebarEnvironment(s: EnvironmentSession): SidebarEnvironment {
    val env by s.env.collectAsStateWithLifecycle()
    val state by s.state.collectAsStateWithLifecycle()
    val threads by s.threads.collectAsStateWithLifecycle()
    val server by s.server.collectAsStateWithLifecycle()
    val waiting = remember(threads) { threads.groups.sumOf { g -> g.threads.count { it.pill == StatusPill.APPROVAL || it.pill == StatusPill.INPUT } } }
    val caps = remember(server) { MachineCaps(server.threadSettlement, server.threadSnooze, server.threadPinning, server.providers.associate { it.instanceId to it.driver }) }
    return SidebarEnvironment(s.id, env.label, env.kind, state, waiting, caps)
}

/** T3's `NoActiveThreadState`: the header and "Pick a thread to continue". */
@Composable
private fun NoThread(hasEnvironments: Boolean) {
    val c = Candy.colors
    val shell = LocalShell.current
    ArtEmphasisEffect(ArtEmphasis.HERO)
    Column(Modifier.fillMaxSize().page()) {
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
fun BoxScope.RenameSheet(current: String, onDismiss: () -> Unit, title: String = "Rename thread", onSave: (String) -> Unit) {
    val heading = title
    var title by remember { mutableStateOf(current) }
    val valid = title.isNotBlank() && title.trim() != current
    Sheet(
        onDismiss, heading,
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
