package org.anikodev.candywrapper.ui

import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.stash.Restored
import org.anikodev.candywrapper.core.stash.NewStashImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
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
import org.anikodev.candywrapper.core.model.InteractionModeChoice
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.Delivery
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.anikodev.candywrapper.core.sync.TimelineItem
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.anikodev.candywrapper.core.sync.ToolGroup
import org.anikodev.candywrapper.core.sync.UploadedAttachment
import org.anikodev.candywrapper.core.timing.Timeline

/** The most images one message takes from the picker; T3's limit is 100, a phone needs fewer. */
private const val MAX_PICKS = 6
private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

/** T3's `--chat-content-max-width` (46rem): the lane messages and the composer keep to on a wide screen. */
val ChatLaneWidth = 736.dp

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
        LaunchedEffect(Unit) { nav.reset(Screen.Main(null, null)) }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live = connection is ConnectionState.Live

    var renaming by remember { mutableStateOf(false) }
    var draft by rememberSaveable(threadId) { mutableStateOf("") }
    val files = remember(threadId) { mutableStateListOf<PickedFile>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val expanded = remember(threadId) { mutableStateMapOf<String, Boolean>() }
    val details = remember(threadId) { mutableStateMapOf<String, ItemDetail>() }
    val host = remember(thread) {
        TimelineHost.forThread(thread, expanded, details) { checkpointId, title -> nav.push(Screen.Diff(envId, threadId, checkpointId, title)) }
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

    // Plan mode is a legacy setting, off by default; off, the thread's next turn runs in default mode.
    val planMode by graph.planMode.value.collectAsStateWithLifecycle()
    val provider = state.meta.model?.instanceId?.let(server::provider)
    val modeChoice = InteractionModeChoice.resolve(planMode, provider, state.meta.interactionMode)

    fun send(delivery: Delivery) {
        val text = draft.trim()
        val picked = files.toList()
        val mode = modeChoice.mode
        act("Sending") {
            val uploaded = uploadAll(context, session, picked) { busy = it }
            busy = "Sending"
            thread.send(text, uploaded, delivery, mode)
            draft = ""
            files.clear()
        }
    }

    // The prompt stash (T3's composer stash): the draft goes in with one tap; a restore puts it back.
    val stashed by graph.stash.entries.collectAsStateWithLifecycle()
    var stashOpen by remember { mutableStateOf(false) }

    fun takeIn(restored: Restored, replace: Boolean) {
        draft = when {
            replace || draft.isBlank() -> restored.prompt
            restored.prompt.isEmpty() -> draft
            else -> draft.trimEnd() + "\n\n" + restored.prompt
        }
        if (replace) files.clear()
        for (img in restored.images) if (files.size < MAX_PICKS) files += PickedFile(Uri.fromFile(img.file), img.name, img.mimeType, img.sizeBytes)
    }

    suspend fun draftImages(picked: List<PickedFile>) = picked.map { NewStashImage(it.name, it.mimeType, readPick(context, it)) }

    fun stashDraft() {
        val text = draft.trim()
        val picked = files.toList()
        if (text.isEmpty() && picked.isEmpty()) return
        act(null) {
            withContext(Dispatchers.IO) { graph.stash.stash(text, draftImages(picked)) }
            // Cleared only once the entry is on disk, so a failed write keeps the prompt where it was.
            draft = ""
            files.clear()
        }
    }

    fun restoreStashed(id: String, swap: Boolean) {
        val text = draft.trim()
        val picked = files.toList()
        stashOpen = false
        act(null) {
            val restored = withContext(Dispatchers.IO) {
                if (swap) graph.stash.swap(id, text, draftImages(picked)) else graph.stash.restore(id)
            } ?: return@act
            takeIn(restored, replace = swap)
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
        else -> null
    }
    val settings = ComposerSettings(state.meta.model, state.meta.runtimeMode, modeChoice.mode)

    var viewing by remember(threadId) { mutableStateOf<ViewerState?>(null) }
    val images = remember(session, threadId) { SessionImages(graph.images, session, threadId) { items, index -> viewing = ViewerState(items, index) } }

    CompositionLocalProvider(LocalImages provides images) { Box(Modifier.fillMaxSize()) {
        ThreadView(
            title = state.title ?: summary?.title ?: "Thread",
            project = project,
            subtitle = subtitle,
            subtitleError = !live && state.items.isEmpty(),
            state = state,
            host = host,
            workingSince = summary?.workingSince ?: 0,
            working = summary?.pill == StatusPill.WORKING,
            onLoadOlder = { scope.launch { thread.loadOlder() } },
            empty = {
                when {
                    state.phase == ThreadSession.Phase.LOADING && !slowSync -> Unit
                    live -> Txt("Loading the thread", CandyType.sm, Candy.colors.mutedForeground)
                    else -> Alert("This thread is not saved on the phone yet. It loads when the computer answers.", NoticeTone.NEUTRAL, action = "Retry") { session.wake() }
                }
            },
            onDiff = { d -> nav.push(Screen.Diff(envId, threadId, d.checkpointId, d.title)) },
            menu = { close ->
                MenuItem("Rename thread", Icons.Pencil, enabled = live) { close(); renaming = true }
                MenuItem("Refresh", Icons.RefreshCw, detail = "Subscribe again from the last event seen") { close(); thread.resubscribe() }
                if (summary?.unread == false && summary.pill == StatusPill.READY) {
                    MenuItem("Mark unread", Icons.EyeOff, enabled = live) {
                        close()
                        act(null) { session.dispatch(Commands.markUnread(threadId)); nav.reset(Screen.Main(envId, null)) }
                    }
                }
                MenuSeparator()
                MenuItem("Archive", Icons.Archive, enabled = live && summary?.pill != StatusPill.WORKING) {
                    close()
                    act(null) { session.dispatch(Commands.archive(threadId)); nav.reset(Screen.Main(envId, null)) }
                }
            },
        ) {
            val pending = state.pending.firstOrNull()
            if (state.phase == ThreadSession.Phase.DELETED) return@ThreadView
            // Stash rides at the top of the stack; a request or the queue sits right on the composer (CandyWrapper f8ddfbf5).
            if (stashOpen && pending == null) {
                StashList(
                    stashed, System.currentTimeMillis(), canSwap = draft.isNotBlank() || files.isNotEmpty(),
                    onRestore = { restoreStashed(it.id, swap = false) },
                    onSwap = { restoreStashed(it.id, swap = true) },
                    onDelete = { e -> act(null) { withContext(Dispatchers.IO) { graph.stash.delete(e.id) } } },
                    onClose = { stashOpen = false },
                )
            } else if (pending == null) {
                StashTab(stashed.size) { stashOpen = true }
            }
            when {
                pending != null && pending.isQuestion -> QuestionPanel(pending, busy != null || !live,
                    onAnswer = { answers -> act(null) { thread.answer(pending.requestId, answers) } },
                    onDismiss = { act(null) { thread.dismissQuestion(pending.requestId) } })
                pending != null -> ApprovalPanel(pending, busy != null || !live) { decision -> act(null) { thread.approve(pending.requestId, decision) } }
                state.queued.isNotEmpty() -> QueuedList(state.queued, canSteer = state.stoppableRunId != null,
                    onSteer = { runId -> act(null) { thread.steerQueued(runId) } },
                    onCancel = { runId -> act(null) { thread.cancelQueued(runId) } })
            }
            Composer(
                text = draft, onText = { draft = it },
                settings = settings,
                server = server,
                running = state.running,
                canStop = state.stoppableRunId != null,
                files = files,
                busy = busy?.takeIf { it.isNotEmpty() },
                error = error,
                connected = live,
                onModel = { m -> act(null) { thread.setModel(m) } },
                onRuntimeMode = { m -> act(null) { thread.setRuntimeMode(m) } },
                onTogglePlan = if (modeChoice.enabled) {
                    { act(null) { thread.setInteractionMode(if (state.meta.interactionMode == "plan") "default" else "plan") } }
                } else null,
                onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveFile = { files.remove(it) },
                onSend = { send(Delivery.AUTO) },
                onQueue = if (state.running) ({ send(Delivery.QUEUE) }) else null,
                onSteer = if (state.running) ({ send(Delivery.STEER) }) else null,
                onStop = { act("Stopping") { thread.stop() } },
                onDismissError = { error = null },
                locked = when {
                    pending?.isQuestion == true -> "Answer the question above to continue"
                    pending != null -> APPROVAL_LOCK
                    else -> null
                },
                trayAbove = pending != null || state.queued.isNotEmpty() || stashOpen || stashed.isNotEmpty(),
                onStash = ::stashDraft,
                onPaste = { uris -> scope.receivePasted(context, uris, MAX_PICKS - files.size) { if (files.size < MAX_PICKS) files += it } },
                footer = {
                    val env by session.env.collectAsStateWithLifecycle()
                    DeviceRow(env.label, live)
                    if (summary?.pill == StatusPill.WAITING) {
                        WaitingPill("Waiting") { open, close ->
                            Menu(open, close, side = MenuSide.ABOVE, alignEnd = true, minWidth = 220.dp) {
                                MenuLabel("Waiting on background work")
                                MenuItem("Stop", Icons.Square, enabled = live && state.stoppableRunId != null) { close(); act("Stopping") { thread.stop() } }
                            }
                        }
                    }
                },
            )
        }
        if (renaming) {
            RenameSheet(state.title ?: summary?.title.orEmpty(), onDismiss = { renaming = false }) { title ->
                renaming = false
                act(null) { thread.rename(title) }
            }
        }
        viewing?.let { v -> ImageViewerDialog(v) { viewing = null } }
    } }
}

/**
 * The thread as T3's chat view draws it, from plain state so the screenshot tests can show it:
 * the header with the sidebar button, project monogram, title and the ⋯ menu; the timeline in
 * T3's 46rem lane; and [bottom], the trays and the composer.
 */
@Composable
fun ThreadView(
    title: String,
    project: ProjectSummary?,
    subtitle: String?,
    subtitleError: Boolean,
    state: ThreadSession.State,
    host: TimelineHost,
    workingSince: Long,
    working: Boolean,
    onLoadOlder: () -> Unit,
    empty: @Composable () -> Unit = {},
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit = {},
    menuOpen: Boolean = false,
    onDiff: (DiffTotals) -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val c = Candy.colors
    val shell = LocalShell.current
    var actions by remember { mutableStateOf(menuOpen) }
    ArtEmphasisEffect(ArtEmphasis.THREAD)
    Column(Modifier.fillMaxSize().page().windowInsetsPadding(BottomInsets)) {
        TopBar(
            title, subtitle = subtitle,
            subtitleColor = if (subtitleError) c.errorForeground else c.mutedForeground,
            leading = if (!shell.wide) ({ IconButton(Icons.PanelLeft, "Open the sidebar", tint = c.foreground, onClick = shell.openSidebar) }) else null,
            titleLead = project?.let { p -> { ProjectIcon(p, p.title) } },
            onTitleClick = { actions = true },
        ) {
            val actionsButton: @Composable () -> Unit = {
                Box {
                    if (c.redesign) PillIcon(Icons.Ellipsis, "Thread actions") { actions = true }
                    else IconButton(Icons.Ellipsis, "Thread actions", tint = c.foreground) { actions = true }
                    Menu(actions, { actions = false }, alignEnd = true, minWidth = 220.dp) { menu { actions = false } }
                }
            }
            if (c.redesign) {
                val diff = remember(state.items) { latestDiff(state.items) }
                HeaderPill(diff, onDiff, actionsButton)
            } else actionsButton()
        }
        // The thread runs on under the composer and its pills; the list pads its end by their height
        // so the newest row can still scroll clear of them.
        var bottomPx by remember { mutableIntStateOf(0) }
        val bottomInset = with(LocalDensity.current) { bottomPx.toDp() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = maxOf(16.dp, (maxWidth - ChatLaneWidth) / 2)
                if (state.items.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 16.dp, bottom = 16.dp + bottomInset)) { empty() }
                } else {
                    Timeline(state, host, workingSince, working, side, bottomInset, onLoadOlder)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }, contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.widthIn(max = ChatLaneWidth + 24.dp).fillMaxWidth(), content = bottom)
            }
        }
    }
}

/** How far above the screen's bottom edge the thread starts fading out. */
private val TimelineEndFade = 48.dp

@Composable
private fun Timeline(state: ThreadSession.State, host: TimelineHost, workingSince: Long, working: Boolean, side: Dp, bottomInset: Dp, loadOlder: () -> Unit) {
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

    // Redesign's frosted composer and pills blur the thread as it scrolls under them.
    Box(Modifier.fillMaxSize().backdropSource()) {
        LazyColumn(
            Modifier.fillMaxSize()
                // Rows fade out over the last stretch above the screen's edge instead of being cut.
                // The offscreen layer only redraws when the list does.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = TimelineEndFade.toPx()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - fade, endY = size.height),
                        blendMode = BlendMode.DstIn,
                    )
                },
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 16.dp + bottomInset),
        ) {
            if (showWorking) item(key = "working", contentType = "working") { WorkingRow(workingSince, state.queued.size) }
            items(reversed, key = { it.key }, contentType = { contentType(it) }) { item ->
                Box(Modifier.padding(top = rowGap(item).dp)) {
                    when (item) {
                        is ToolGroup -> ToolGroupView(item, host)
                        is TimelineRow -> RowView(item, host)
                    }
                }
            }
            if (state.hasMoreHistory || state.olderError != null) {
                item(key = "older", contentType = "older") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loadingOlder -> Txt("Loading earlier messages", CandyType.xs, c.mutedForeground)
                            state.olderError != null -> Alert("Could not load earlier messages: ${state.olderError}", NoticeTone.NEUTRAL, action = "Retry", onAction = loadOlder)
                            else -> CButton("Load earlier messages", kind = ButtonKind.GHOST, size = ButtonSize.SM, onClick = loadOlder)
                        }
                    }
                }
            }
        }
        if (scrolledUp) {
            // T3's scroll-to-bottom pill.
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp + bottomInset).clip(RoundedCornerShape(50)).background(c.popover)
                    .border(1.dp, c.border, RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "Jump to the newest message") { scope.launch { listState.scrollToItem(0) } }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.ArrowDown, c.foreground, 14.dp)
                Spacer(Modifier.width(6.dp))
                Txt("Scroll to bottom", CandyType.xs, c.foreground)
            }
        }
    }
}

private fun contentType(item: TimelineItem): Any = when (item) {
    is ToolGroup -> "group"
    is TimelineRow -> if (item.row.type == "checkpoint") "checkpoint" else item.kind
}

/**
 * A draft for a new thread in one project (T3's new-thread draft, 04-new-thread-draft). The first
 * send creates the thread with the picked model and modes (`thread.create`, then
 * `message.dispatch` with a title seed), then opens it.
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
    LaunchedEffect(server) { if (model == null) model = server.modelForNewThread(null) }
    val planMode by graph.planMode.value.collectAsStateWithLifecycle()
    val modeChoice = InteractionModeChoice.resolve(planMode, model?.instanceId?.let(server::provider), interactionMode)
    val settings = ComposerSettings(model, runtimeMode ?: server.defaultRuntimeMode, modeChoice.mode)
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKS)) { uris ->
        for (uri in uris) if (files.size < MAX_PICKS) describePick(context, uri)?.let { if (files.none { f -> f.uri == uri }) files += it }
    }

    var viewing by remember { mutableStateOf<ViewerState?>(null) }
    val images = remember(session) { SessionImages(graph.images, session, null) { items, index -> viewing = ViewerState(items, index) } }

    ArtEmphasisEffect(ArtEmphasis.HERO)
    CompositionLocalProvider(LocalImages provides images) { Column(Modifier.fillMaxSize().page().windowInsetsPadding(BottomInsets)) {
        TopBar("New thread", onBack = { nav.pop() }, subtitle = if (live) null else connection.label(), titleLead = project?.let { p -> { ProjectIcon(p, p.title) } })
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectIcon(project, project?.title ?: "?", size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Txt(project?.title ?: "Project", CandyType.smMedium, c.mutedForeground)
            }
            Spacer(Modifier.height(12.dp))
            Txt("What should we work on?", CandyType.xxl, c.foreground)
            if (project?.workspaceRoot?.isNotBlank() == true) {
                Spacer(Modifier.height(6.dp))
                Txt(project.workspaceRoot, CandyType.monoSmall, c.mutedForeground, maxLines = 2)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.widthIn(max = ChatLaneWidth + 24.dp).fillMaxWidth()) {
                Composer(
                    text = draft, onText = { draft = it }, settings = settings, server = server,
                    running = false, canStop = false, files = files, busy = busy, error = error, connected = live && model != null,
                    onModel = { model = it },
                    onRuntimeMode = { runtimeMode = it },
                    onTogglePlan = if (modeChoice.enabled) ({ interactionMode = if (interactionMode == "plan") "default" else "plan" }) else null,
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
                                nav.reset(Screen.Main(envId, threadId))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = EnvironmentSession.describe(e)
                            } finally {
                                busy = null
                            }
                        }
                    },
                    onQueue = null,
                    onSteer = null,
                    onStop = {},
                    onDismissError = { error = null },
                    onPaste = { uris -> scope.receivePasted(context, uris, MAX_PICKS - files.size) { if (files.size < MAX_PICKS) files += it } },
                )
            }
        }
    }
    viewing?.let { v -> ImageViewerDialog(v) { viewing = null } }
    }
}
