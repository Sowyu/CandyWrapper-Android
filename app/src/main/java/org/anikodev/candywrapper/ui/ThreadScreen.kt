package org.anikodev.candywrapper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.sync.Commands
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.Delivery
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.anikodev.candywrapper.core.sync.TimelineItem
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.anikodev.candywrapper.core.sync.ToolGroup
import org.anikodev.candywrapper.core.sync.UploadedAttachment
import org.anikodev.candywrapper.core.timing.Timeline

private sealed interface ThreadSheet {
    data object Actions : ThreadSheet
    data object Rename : ThreadSheet
    data object SendOptions : ThreadSheet
    data class Pick(val picker: Picker) : ThreadSheet
}

/** The most images one message takes from the picker; T3's limit is 100, a phone needs fewer. */
private const val MAX_PICKS = 6
private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

/** Picked images, uploaded on send (protocol.md 5.3). */
suspend fun uploadAll(context: android.content.Context, session: EnvironmentSession, files: List<PickedFile>, progress: (String) -> Unit): List<UploadedAttachment> =
    files.mapIndexed { i, f ->
        progress(if (files.size == 1) "Uploading ${f.name}" else "Uploading ${i + 1} of ${files.size}")
        val bytes = readPick(context, f)
        if (bytes.size > MAX_IMAGE_BYTES) throw java.io.IOException("${f.name} is over 10 MB, the limit for images.")
        session.upload(f.name, f.mimeType, bytes)
    }

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
    val server by session.server.collectAsStateWithLifecycle()
    val (summary, project) = remember(shell, threadId) {
        shell.groups.firstNotNullOfOrNull { g -> g.threads.firstOrNull { it.id == threadId }?.let { it to g.project } } ?: (null to null)
    }
    val now = rememberMinuteClock()
    val c = Candy.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live = connection is ConnectionState.Live

    var sheet by remember { mutableStateOf<ThreadSheet?>(null) }
    var draft by rememberSaveable(threadId) { mutableStateOf("") }
    val files = remember(threadId) { mutableStateListOf<PickedFile>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val expanded = remember(threadId) { mutableStateMapOf<String, Boolean>() }
    val details = remember(threadId) { mutableStateMapOf<String, ItemDetail>() }
    val host = remember(thread) {
        TimelineHost(thread, expanded, details) { checkpointId, title -> nav.push(Screen.Diff(envId, threadId, checkpointId, title)) }
    }

    fun act(label: String?, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label ?: ""
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = EnvironmentSession.describe(e)
            } finally {
                busy = null
            }
        }
    }

    fun send(delivery: Delivery) {
        val text = draft.trim()
        val picked = files.toList()
        act("Sending") {
            val uploaded = uploadAll(context, session, picked) { busy = it }
            busy = "Sending"
            thread.send(text, uploaded, delivery)
            draft = ""
            files.clear()
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKS)) { uris ->
        for (uri in uris) if (files.size < MAX_PICKS) describePick(context, uri)?.let { if (files.none { f -> f.uri == uri }) files += it }
    }

    // "Catching up" only when it takes long enough to notice, so a quick resume does not flash it.
    var slowSync by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        slowSync = false
        if (state.phase == ThreadSession.Phase.SYNCING || state.phase == ThreadSession.Phase.LOADING) {
            delay(700)
            slowSync = true
        }
    }

    // Read receipts (protocol.md 7.9): at once for an unseen completion, else after 10 s of quiet.
    var visitSent by remember { mutableStateOf(0L) }
    LaunchedEffect(summary?.updatedAt, summary?.visitedAt, summary?.unread, live) {
        val s = summary ?: return@LaunchedEffect
        if (!live || s.updatedAt <= 0 || s.updatedAt <= maxOf(s.visitedAt, visitSent)) return@LaunchedEffect
        if (!s.unread) delay(10_000)
        runCatching { session.dispatch(Commands.visit(threadId, s.updatedAt)) }.onSuccess { visitSent = s.updatedAt }
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

    val subtitle = when {
        state.phase == ThreadSession.Phase.DELETED -> "Deleted on the computer"
        !live && state.items.isNotEmpty() -> "Saved copy · ${connection.label()}"
        !live -> connection.label()
        slowSync -> "Catching up"
        else -> project?.title
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(BottomInsets)) {
            TopBar(
                state.title ?: summary?.title ?: "Thread", onBack = { nav.pop() }, subtitle = subtitle,
                subtitleColor = if (!live && state.items.isEmpty()) c.error else c.mutedForeground,
                onTitleClick = { sheet = ThreadSheet.Actions },
            ) {
                IconButton(Icons.Ellipsis, "Thread actions") { sheet = ThreadSheet.Actions }
            }
            Box(Modifier.weight(1f)) {
                if (state.items.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        when {
                            state.phase == ThreadSession.Phase.LOADING && !slowSync -> Unit
                            live -> Txt("Loading the thread", CandyType.ui, c.mutedForeground)
                            else -> Notice("This thread is not saved on the phone yet. It loads when the computer answers.", c.foreground, c.muted,
                                action = "Retry") { session.wake() }
                        }
                    }
                } else {
                    Timeline(state, host, summary?.workingSince ?: 0, now, summary?.pill == StatusPill.WORKING) { scope.launch { thread.loadOlder() } }
                }
            }
            val pending = state.pending.firstOrNull()
            when {
                state.phase == ThreadSession.Phase.DELETED -> Unit
                pending != null && pending.isQuestion -> QuestionPanel(pending, busy != null || !live,
                    onAnswer = { answers -> act(null) { thread.answer(pending.requestId, answers) } },
                    onDismiss = { act(null) { thread.dismissQuestion(pending.requestId) } })
                pending != null -> ApprovalPanel(pending, busy != null || !live) { decision -> act(null) { thread.approve(pending.requestId, decision) } }
                else -> {
                    if (state.queued.isNotEmpty()) {
                        QueuedList(state.queued, canSteer = state.stoppableRunId != null,
                            onSteer = { runId -> act(null) { thread.steerQueued(runId) } },
                            onCancel = { runId -> act(null) { thread.cancelQueued(runId) } })
                    }
                    val provider = state.meta.model?.instanceId?.let(server::provider)
                    Composer(
                        text = draft, onText = { draft = it },
                        settings = ComposerSettings(state.meta.model, state.meta.runtimeMode, state.meta.interactionMode),
                        server = server,
                        running = state.running,
                        canStop = state.stoppableRunId != null,
                        files = files,
                        busy = busy?.takeIf { it.isNotEmpty() },
                        error = error,
                        connected = live,
                        onPicker = { sheet = ThreadSheet.Pick(it) },
                        onTogglePlan = if (provider?.showInteractionModeToggle != false) {
                            { act(null) { thread.setInteractionMode(if (state.meta.interactionMode == "plan") "default" else "plan") } }
                        } else null,
                        onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveFile = { files.remove(it) },
                        onSend = { send(Delivery.AUTO) },
                        onSendOptions = if (state.running) ({ sheet = ThreadSheet.SendOptions }) else null,
                        onStop = { act("Stopping") { thread.stop() } },
                        onDismissError = { error = null },
                    )
                }
            }
        }

        when (val s = sheet) {
            ThreadSheet.Actions -> Sheet({ sheet = null }, state.title ?: summary?.title) {
                ListRow("Rename", icon = Icons.Pencil, enabled = live) { sheet = ThreadSheet.Rename }
                ListRow("Refresh", icon = Icons.RefreshCw, detail = "Subscribe again from the last event seen") { sheet = null; thread.resubscribe() }
                if (summary?.unread == false && summary.pill == StatusPill.READY) {
                    ListRow("Mark as unread", icon = Icons.Eye, enabled = live) {
                        sheet = null
                        act(null) { session.dispatch(Commands.markUnread(threadId)); nav.pop() }
                    }
                }
                ListRow("Archive", icon = Icons.Archive, enabled = live) {
                    sheet = null
                    act(null) { session.dispatch(Commands.archive(threadId)); nav.pop() }
                }
            }
            ThreadSheet.Rename -> RenameSheet(state.title ?: summary?.title.orEmpty(), onDismiss = { sheet = null }) { title ->
                sheet = null
                act(null) { thread.rename(title) }
            }
            ThreadSheet.SendOptions -> Sheet({ sheet = null }, "Send during this run") {
                ListRow("Queue after this run", icon = Icons.Clock, detail = "Starts when the current run finishes") { sheet = null; send(Delivery.QUEUE) }
                ListRow("Steer now", icon = Icons.Zap, detail = "Redirects the run that is going") { sheet = null; send(Delivery.STEER) }
            }
            is ThreadSheet.Pick -> ComposerPickerSheet(
                s.picker, server, ComposerSettings(state.meta.model, state.meta.runtimeMode, state.meta.interactionMode),
                onModel = { m -> act(null) { thread.setModel(m) } },
                onRuntimeMode = { m -> act(null) { thread.setRuntimeMode(m) } },
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun Timeline(state: ThreadSession.State, host: TimelineHost, workingSince: Long, now: Long, working: Boolean, loadOlder: () -> Unit) {
    val c = Candy.colors
    val listState = rememberLazyListState()
    // Newest at the bottom with no scroll work: the list is reversed and laid out from the end.
    val reversed = remember(state.items) { state.items.asReversed() }
    val showWorking = state.running || working

    // A new newest row while reading the newest ones: keep it in view.
    val newest = reversed.firstOrNull()?.key
    LaunchedEffect(newest) {
        if (listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0)
    }
    // Reaching the oldest loaded row pages in older history. After a failure it waits for Retry.
    val pageable by rememberUpdatedState(state.hasMoreHistory && state.olderError == null)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= listState.layoutInfo.totalItemsCount - 3 }
            .collect { if (pageable) loadOlder() }
    }
    val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showWorking) item(key = "working", contentType = "working") { WorkingRow(workingSince, now, state.queued.size) }
            items(reversed, key = { it.key }, contentType = { contentType(it) }) { item ->
                when (item) {
                    is ToolGroup -> ToolGroupView(item, host)
                    is TimelineRow -> RowView(item, host)
                }
            }
            if (state.hasMoreHistory || state.olderError != null) {
                item(key = "older", contentType = "older") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loadingOlder -> Txt("Loading earlier messages", CandyType.meta, c.mutedForeground)
                            state.olderError != null -> Notice("Could not load earlier messages: ${state.olderError}", c.foreground, c.muted, action = "Retry", onAction = loadOlder)
                            else -> CButton("Load earlier messages", kind = ButtonKind.GHOST, onClick = loadOlder)
                        }
                    }
                }
            }
        }
        if (scrolledUp) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(Radius.lg)).background(c.card)
                    .border(1.dp, c.border, RoundedCornerShape(Radius.lg))
                    .clickable(role = Role.Button, onClickLabel = "Jump to the newest message") { scope.launch { listState.scrollToItem(0) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.ChevronDown, c.foreground, 15.dp)
                Spacer(Modifier.width(6.dp))
                Txt("Newest", CandyType.small, c.foreground)
            }
        }
    }
}

private fun contentType(item: TimelineItem): Any = when (item) {
    is ToolGroup -> "group"
    is TimelineRow -> if (item.row.type == "checkpoint") "checkpoint" else item.kind
}

/**
 * A draft for a new thread in one project. The first send creates the thread with the picked
 * model and modes (`thread.create`, then `message.dispatch` with a title seed), then opens it.
 */
@Composable
fun NewThreadScreen(graph: AppGraph, nav: Navigator, envId: String, projectId: String) {
    val session = graph.environments.session(envId)
    if (session == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val server by session.server.collectAsStateWithLifecycle()
    val connection by session.state.collectAsStateWithLifecycle()
    val shell by session.threads.collectAsStateWithLifecycle()
    val project = remember(shell, projectId) { shell.groups.firstOrNull { it.project?.id == projectId }?.project }
    val live = connection is ConnectionState.Live
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Candy.colors
    var draft by rememberSaveable { mutableStateOf("") }
    var model by remember { mutableStateOf(server.modelForNewThread(null)) }
    var runtimeMode by rememberSaveable { mutableStateOf<String?>(null) }
    var interactionMode by rememberSaveable { mutableStateOf("default") }
    val files = remember { mutableStateListOf<PickedFile>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    LaunchedEffect(server) { if (model == null) model = server.modelForNewThread(null) }
    val settings = ComposerSettings(model, runtimeMode ?: server.defaultRuntimeMode, interactionMode)
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKS)) { uris ->
        for (uri in uris) if (files.size < MAX_PICKS) describePick(context, uri)?.let { if (files.none { f -> f.uri == uri }) files += it }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(BottomInsets)) {
            TopBar("New thread", onBack = { nav.pop() }, subtitle = if (live) project?.title else connection.label())
            Column(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Monogram(project?.title ?: "?")
                    Spacer(Modifier.width(10.dp))
                    Txt(project?.title ?: "Project", CandyType.uiMedium, c.mutedForeground)
                }
                Spacer(Modifier.height(10.dp))
                Txt("What should we work on?", CandyType.heading, c.foreground)
                if (project?.workspaceRoot?.isNotBlank() == true) {
                    Spacer(Modifier.height(6.dp))
                    Txt(project.workspaceRoot, CandyType.monoSmall, c.mutedForeground, maxLines = 2)
                }
            }
            Composer(
                text = draft, onText = { draft = it }, settings = settings, server = server,
                running = false, canStop = false, files = files, busy = busy, error = error, connected = live && model != null,
                onPicker = { picker = it },
                onTogglePlan = { interactionMode = if (interactionMode == "plan") "default" else "plan" },
                onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveFile = { files.remove(it) },
                onSend = {
                    val m = model ?: return@Composer
                    val text = draft.trim()
                    busy = "Starting"
                    error = null
                    scope.launch {
                        try {
                            val uploaded = uploadAll(context, session, files.toList()) { busy = it }
                            busy = "Starting"
                            val threadId = Commands.newId()
                            session.dispatch(Commands.createThread(threadId, projectId, "New thread", m, settings.runtimeMode, settings.interactionMode))
                            session.dispatch(Commands.sendMessage(threadId, text, uploaded,
                                titleSeed = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)))
                            Timeline.mark("thread.created", threadId.take(8))
                            nav.replaceTop(Screen.Thread(envId, threadId))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = EnvironmentSession.describe(e)
                        } finally {
                            busy = null
                        }
                    }
                },
                onSendOptions = null,
                onStop = {},
                onDismissError = { error = null },
            )
        }
        picker?.let { p ->
            ComposerPickerSheet(p, server, settings, onModel = { model = it }, onRuntimeMode = { runtimeMode = it }, onDismiss = { picker = null })
        }
    }
}
