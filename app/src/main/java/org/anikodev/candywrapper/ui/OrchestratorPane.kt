package org.anikodev.candywrapper.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.HtmlPage
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.orchestrator.BotMood
import org.anikodev.candywrapper.core.orchestrator.ChatItem
import org.anikodev.candywrapper.core.orchestrator.CrewCard
import org.anikodev.candywrapper.core.orchestrator.OrchestratorChat
import org.anikodev.candywrapper.core.orchestrator.OrchestratorNeed
import org.anikodev.candywrapper.core.stash.NewStashImage
import org.anikodev.candywrapper.core.stash.StashEntry
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.sync.PendingRequest
import org.anikodev.candywrapper.core.sync.TimelineItem
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.anikodev.candywrapper.core.sync.ToolGroup
import org.anikodev.candywrapper.orchestrator.OrchestratorUi

/** Images waiting to go with your next message to the Orchestrator, kept while you leave the pane. */
// ponytail: kept in memory only, as on the Mac; a relaunch drops unsent images, persist them like the stash if that bites
private val pendingImages = mutableStateListOf<PickedFile>()

/** The pane's tabs: the chat, the Overview of every thread, and what needs you. */
enum class PaneTab { CHAT, OVERVIEW, NEEDS }

/** Everything the pane draws, as plain values, so screenshots draw it without a server. */
data class OrchestratorPaneState(
    val ui: OrchestratorUi,
    val items: List<ChatItem> = emptyList(),
    val undoable: Set<String> = emptySet(),
    /** A question or approval the Orchestrator's own thread waits on. */
    val pending: PendingRequest? = null,
    val draft: String = "",
    val files: List<PickedFile> = emptyList(),
    val busy: String? = null,
    val error: String? = null,
    /** The prompt stash, shared with every thread's composer, and whether its list is open over the composer. */
    val stashed: List<StashEntry> = emptyList(),
    val stashOpen: Boolean = false,
)

/** What the pane's controls do. */
class PaneActions(
    val back: () -> Unit = {},
    val draft: (String) -> Unit = {},
    val send: () -> Unit = {},
    val attach: () -> Unit = {},
    val removeFile: (PickedFile) -> Unit = {},
    val paste: ((List<Uri>) -> Unit)? = null,
    val start: () -> Unit = {},
    val openThread: (envId: String?, threadId: String) -> Unit = { _, _ -> },
    val respond: (OrchestratorNeed, allow: Boolean) -> Unit = { _, _ -> },
    val undoMemory: (String) -> Unit = {},
    val openPage: (HtmlPage) -> Unit = {},
    val answer: (Map<String, Any>) -> Unit = {},
    val dismissQuestion: () -> Unit = {},
    val approve: (String) -> Unit = {},
    val settings: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val stash: () -> Unit = {},
    val toggleStash: () -> Unit = {},
    val restoreStashed: (StashEntry, swap: Boolean) -> Unit = { _, _ -> },
    val deleteStashed: (StashEntry) -> Unit = {},
)

/** Redesign's Orchestrator pane on a phone: the sidebar's pinned row opens it full screen. */
@Composable
fun OrchestratorScreen(graph: AppGraph, nav: Navigator) {
    val controller = graph.orchestrator
    val ui by controller.ui.collectAsStateWithLifecycle()
    if (!ui.active) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val session = ui.envId?.let(graph.environments::session)
    val thread = remember(session, ui.threadId) { ui.threadId?.let { session?.thread(it) } }
    DisposableEffect(thread) {
        thread?.acquire()
        onDispose { thread?.release() }
    }
    val threadState = thread?.state?.collectAsStateWithLifecycle()?.value
    val undoable by controller.undoable.collectAsStateWithLifecycle()
    // The chat is built off the main thread from the thread's rows; unchanged bubbles keep their parsed Markdown.
    val items by produceState(emptyList<ChatItem>(), threadState?.items, threadState?.running) {
        val rows = threadState?.items ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val flat = flatten(rows)
            controller.paneRows(flat)
            OrchestratorChat.items(flat, threadState.running, System.currentTimeMillis(), previous = value.associateBy { it.key })
        }
    }
    val draft by graph.orchestratorDraft.value.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(PaneTab.CHAT) }

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

    // The prompt stash, the same one the thread composers use (ThreadScreen.kt). It stays on this phone.
    // TODO(sync): the Mac keeps the composer draft per device (orchestrator.md, "Settings on every device"); if it
    // syncs an Orchestrator stash later, follow the format it documents there.
    val stashed by graph.stash.entries.collectAsStateWithLifecycle()
    var stashOpen by remember { mutableStateOf(false) }

    suspend fun draftImages(picked: List<PickedFile>) = picked.map { NewStashImage(it.name, it.mimeType, readPick(context, it)) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        for (uri in uris) if (pendingImages.size < MAX_IMAGES) describePick(context, uri)?.let { if (pendingImages.none { f -> f.uri == uri }) pendingImages += it }
    }
    val actions = remember(nav, controller) {
        PaneActions(
            back = { nav.pop() },
            draft = { graph.orchestratorDraft.set(it.ifEmpty { null }) },
            send = {
                val text = graph.orchestratorDraft.value.value.orEmpty().trim()
                val files = pendingImages.toList()
                val s = controller.ui.value.envId?.let(graph.environments::session)
                if ((text.isNotEmpty() || files.isNotEmpty()) && s != null) act("Sending") {
                    val uploaded = uploadAll(context, s, files) { busy = it }
                    busy = "Sending"
                    controller.send(text, uploaded)
                    graph.orchestratorDraft.set(null)
                    pendingImages.removeAll(files)
                }
            },
            attach = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            removeFile = { pendingImages.remove(it) },
            paste = { uris -> scope.receivePasted(context, uris, MAX_IMAGES - pendingImages.size) { if (pendingImages.size < MAX_IMAGES) pendingImages += it } },
            start = { act("Starting") { controller.startHere() } },
            openThread = { env, id -> nav.reset(Screen.Main(env ?: controller.locate(id), id)) },
            respond = { need, allow -> act(null) { controller.respond(need, allow) } },
            undoMemory = { controller.undoMemory(it) },
            openPage = { page ->
                act(null) {
                    val url = controller.pageUrl(page.attachmentId, page.fileName)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            answer = { answers -> act(null) { thread?.state?.value?.pending?.firstOrNull()?.let { p -> thread.answer(p.requestId, answers) } } },
            dismissQuestion = { act(null) { thread?.state?.value?.pending?.firstOrNull()?.let { p -> thread.dismissQuestion(p.requestId) } } },
            approve = { decision -> act(null) { thread?.state?.value?.pending?.firstOrNull()?.let { p -> thread.approve(p.requestId, decision) } } },
            settings = { nav.push(Screen.OrchestratorSettings) },
            dismissError = { error = null },
            stash = {
                val text = graph.orchestratorDraft.value.value.orEmpty().trim()
                val files = pendingImages.toList()
                if (text.isNotEmpty() || files.isNotEmpty()) act(null) {
                    withContext(Dispatchers.IO) { graph.stash.stash(text, draftImages(files)) }
                    // Cleared only once the entry is on disk, so a failed write keeps the draft.
                    graph.orchestratorDraft.set(null)
                    pendingImages.removeAll(files)
                }
            },
            toggleStash = { stashOpen = !stashOpen },
            restoreStashed = { entry, swap ->
                val text = graph.orchestratorDraft.value.value.orEmpty().trim()
                val files = pendingImages.toList()
                stashOpen = false
                act(null) {
                    val restored = withContext(Dispatchers.IO) {
                        if (swap) graph.stash.swap(entry.id, text, draftImages(files)) else graph.stash.restore(entry.id)
                    } ?: return@act
                    graph.orchestratorDraft.set(restored.into(text, swap).ifEmpty { null })
                    if (swap) pendingImages.removeAll(files)
                    for (img in restored.images) if (pendingImages.size < MAX_IMAGES) pendingImages += PickedFile(Uri.fromFile(img.file), img.name, img.mimeType, img.sizeBytes)
                }
            },
            deleteStashed = { e -> act(null) { withContext(Dispatchers.IO) { graph.stash.delete(e.id) } } },
        )
    }
    val connection = session?.state?.collectAsStateWithLifecycle()?.value
    val images = remember(session, ui.threadId) { session?.let { s -> ui.threadId?.let { SessionImages(graph.images, s, it) { _, _ -> } } } }
    CompositionLocalProvider(LocalImages provides images) {
        OrchestratorPanel(
            OrchestratorPaneState(
                ui = ui.copy(connected = connection is ConnectionState.Live),
                items = items, undoable = undoable, pending = threadState?.pending?.firstOrNull(),
                draft = draft.orEmpty(), files = pendingImages.toList(), busy = busy, error = error,
                stashed = stashed, stashOpen = stashOpen,
            ),
            tab, { tab = it }, actions,
        )
    }
}

private const val MAX_IMAGES = 6

/** The thread's rows in order, tool groups unfolded. */
private fun flatten(items: List<TimelineItem>): List<TurnRow> = buildList {
    for (i in items) when (i) {
        is TimelineRow -> add(i.row)
        is ToolGroup -> i.rows.forEach { add(it.row) }
    }
}

@Composable
fun OrchestratorPanel(state: OrchestratorPaneState, tab: PaneTab, onTab: (PaneTab) -> Unit, actions: PaneActions) {
    val c = Candy.colors
    val ui = state.ui
    ArtEmphasisEffect(ArtEmphasis.THREAD)
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(
            ui.name,
            onBack = actions.back,
            subtitle = when {
                ui.working -> "Working on ${ui.machine}"
                !ui.connected -> "${ui.machine} · not connected"
                else -> ui.machine
            },
            titleLead = { Bot(ui.bot, if (ui.needs.isNotEmpty()) BotMood.NEEDS_YOU else if (ui.working) BotMood.WORKING else BotMood.IDLE, 28.dp) },
            actions = { IconButton(Icons.Settings, "Orchestrator settings", tint = c.foreground, onClick = actions.settings) },
        )
        Tabs(tab, ui.needs.size, onTab)
        ui.relayNote?.let { Txt(it, CandyType.xs, c.mutedForeground, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PaneTab.CHAT -> Chat(state, actions)
                PaneTab.OVERVIEW -> Overview(ui, actions)
                PaneTab.NEEDS -> NeedsList(ui.needs, state.busy != null, actions, Modifier.fillMaxSize())
            }
        }
    }
}

/** A segmented control under the header: Chat, Overview, Needs you with its count. */
@Composable
private fun Tabs(tab: PaneTab, needs: Int, onTab: (PaneTab) -> Unit) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(36.dp).floating(shape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (t in PaneTab.entries) {
            val on = t == tab
            Row(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(if (on) c.foreground.copy(alpha = if (c.dark) 0.12f else 0.08f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onTab(t) }.semantics { selected = on },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(when (t) { PaneTab.CHAT -> "Chat"; PaneTab.OVERVIEW -> "Overview"; PaneTab.NEEDS -> "Needs you" }, CandyType.smMedium, if (on) c.foreground else c.mutedForeground, maxLines = 1)
                if (t == PaneTab.NEEDS && needs > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(CircleShape).background(NeedsYouAmber).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Txt(if (needs > 9) "9+" else "$needs", CandyType.xsMedium, Color.Black)
                    }
                }
            }
        }
    }
}

// Chat

@Composable
private fun BoxScope.Chat(state: OrchestratorPaneState, actions: PaneActions) {
    val c = Candy.colors
    val ui = state.ui
    if (ui.threadId == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (ui.discovering) EmptyState("Looking for the Orchestrator", "Checking ${ui.machine} for the thread CandyWrapper on your Mac started.")
            else EmptyState(
                "No Orchestrator on ${ui.machine} yet",
                "Start it on your Mac if you use CandyWrapper there too: the Mac starts its own and doesn't pick up one this phone started.",
            ) { CButton("Start on ${ui.machine}", kind = ButtonKind.PRIMARY, enabled = ui.connected && state.busy == null, onClick = actions.start) }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        val reversed = remember(state.items) { state.items.asReversed() }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().backdropSource(),
            reverseLayout = true,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        ) {
            items(reversed, key = { it.key }, contentType = { it::class }) { item -> ChatRow(item, ui, state.undoable, actions) }
        }
        val pending = state.pending
        when {
            pending != null && pending.isQuestion -> QuestionPanel(pending, state.busy != null, onAnswer = actions.answer, onDismiss = actions.dismissQuestion)
            pending != null -> ApprovalPanel(pending, state.busy != null, actions.approve)
        }
        state.error?.let { Alert(it, NoticeTone.ERROR, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), action = "Dismiss", onAction = actions.dismissError) }
        if (state.stashOpen && pending == null) StashList(
            state.stashed, wallClock(), canSwap = state.draft.isNotBlank() || state.files.isNotEmpty(),
            onRestore = { actions.restoreStashed(it, false) },
            onSwap = { actions.restoreStashed(it, true) },
            onDelete = actions.deleteStashed,
            onClose = actions.toggleStash,
        )
        ChatComposer(state, actions)
    }
}

@Composable
private fun ChatRow(item: ChatItem, ui: OrchestratorUi, undoable: Set<String>, actions: PaneActions) {
    val c = Candy.colors
    when (item) {
        is ChatItem.Time -> Txt(item.label, CandyType.xxs, c.mutedForeground, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))
        is ChatItem.Brief -> BriefCard(item)
        is ChatItem.Line -> Txt(
            item.text, CandyType.xs, if (item.threadId != null) c.secondaryLabel else c.mutedForeground, align = TextAlign.Center, maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 24.dp)
                .let { m -> item.threadId?.let { id -> m.clip(RoundedCornerShape(Radius.sm)).clickable(role = Role.Button) { actions.openThread(null, id) } } ?: m },
        )
        is ChatItem.Image -> Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.CenterEnd) { MessageAttachments(listOf(item.attachment)) }
        is ChatItem.Html -> PageCard(item.page, actions)
        is ChatItem.Bubble -> Bubble(item, ui)
        is ChatItem.Plan -> PlanCard(item, actions)
        is ChatItem.Memory -> MemoryChip(item, item.messageId in undoable, actions)
        ChatItem.Read -> Txt("Read", CandyType.xxs, c.mutedForeground, align = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 6.dp, top = 2.dp))
        ChatItem.Typing -> Row(Modifier.padding(start = 34.dp, top = 6.dp)) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.messageSurface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                DotGrid(c.mutedForeground, small = true)
            }
        }
    }
}

/**
 * A card inside the chat. The chat list is the frost's backdrop, so nothing in it may be glass: a pane that records
 * its own backdrop recurses. Solid message fill and the 8% rim instead.
 */
@Composable
private fun Modifier.inChat(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = Candy.colors
    return background(c.messageSurface, shape).border(1.dp, c.rim(), shape)
}

/** iMessage stacks: corners between bubbles of one sender tighten, and only the last gets a tail. */
@Composable
private fun Bubble(item: ChatItem.Bubble, ui: OrchestratorUi) {
    val c = Candy.colors
    val big = 18.dp
    val small = 5.dp
    val shape = if (item.fromUser) RoundedCornerShape(topStart = big, topEnd = if (item.first) big else small, bottomEnd = if (item.last) 4.dp else small, bottomStart = big)
    else RoundedCornerShape(topStart = if (item.first) big else small, topEnd = big, bottomEnd = big, bottomStart = if (item.last) 4.dp else small)
    Row(
        Modifier.fillMaxWidth().padding(top = if (item.first) 6.dp else 2.dp),
        horizontalArrangement = if (item.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!item.fromUser) {
            // Its bot sits at the bottom of each of its stacks.
            Box(Modifier.width(30.dp)) { if (item.last) Bot(ui.bot, BotMood.IDLE, 24.dp) }
        } else Spacer(Modifier.width(48.dp))
        Column(
            Modifier.weight(1f, fill = false).clip(shape).background(if (item.fromUser) c.primary else c.messageSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val md = item.markdown
            if (md != null) MarkdownBlocks(md, style = CandyType.chat)
            else Txt(item.text, CandyType.chat, if (item.fromUser) c.primaryForeground else c.foreground)
        }
        if (!item.fromUser) Spacer(Modifier.width(40.dp))
    }
}

@Composable
private fun BriefCard(item: ChatItem.Brief) {
    val c = Candy.colors
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp).clip(shape).inChat(shape)
            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Show the brief") { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.BookOpen, c.mutedForeground, 16.dp)
            Txt(if (item.updated) "Updated brief" else "Brief", CandyType.smMedium, c.foreground, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.ChevronUp else Icons.ChevronDown, c.mutedForeground, 14.dp)
        }
        if (open) Txt(item.text, CandyType.monoSmall, c.secondaryLabel, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PlanCard(item: ChatItem.Plan, actions: PaneActions) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier.padding(start = 30.dp, end = 40.dp, top = 4.dp).fillMaxWidth().clip(shape).background(c.messageSurface).border(1.dp, c.rim(), shape)
            .padding(vertical = 6.dp),
    ) {
        Txt("Plan", CandyType.xsMedium, c.mutedForeground, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        for (step in item.steps) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp)
                    .let { m -> step.threadId?.let { id -> m.clickable(role = Role.Button) { actions.openThread(null, id) } } ?: m }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (step.done) Icons.CircleCheck else Icons.Circle, if (step.done) c.success else c.mutedForeground, 16.dp, Modifier.padding(top = 2.dp))
                Txt(step.displayText, CandyType.sm, if (step.done) c.mutedForeground else c.foreground, modifier = Modifier.weight(1f))
                if (step.threadId != null) Icon(Icons.ChevronRight, c.mutedForeground, 14.dp, Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun MemoryChip(item: ChatItem.Memory, undoable: Boolean, actions: PaneActions) {
    val c = Candy.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape).inChat(CircleShape).padding(start = 10.dp, end = if (undoable) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Bookmark, c.mutedForeground, 14.dp)
            Txt("Saved to memory: ${item.notes.joinToString(", ")}", CandyType.xs, c.secondaryLabel, maxLines = 1)
            if (undoable) Txt("Undo", CandyType.xsMedium, c.foreground,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { actions.undoMemory(item.messageId) }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** A page the Orchestrator published. The app has no web view, so it opens in the browser. */
@Composable
private fun PageCard(page: HtmlPage, actions: PaneActions) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier.padding(start = 30.dp, end = 40.dp, top = 4.dp).fillMaxWidth().clip(shape).background(c.messageSurface).border(1.dp, c.rim(), shape)
            .clickable(role = Role.Button, onClickLabel = "Open the page") { actions.openPage(page) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.FileCode2, c.mutedForeground, 18.dp)
        Column(Modifier.weight(1f)) {
            Txt(page.title, CandyType.smMedium, c.foreground, maxLines = 2)
            Txt("HTML page", CandyType.xs, c.mutedForeground)
        }
        Icon(Icons.ExternalLink, c.mutedForeground, 16.dp)
    }
}

/** A rounded one-line field with a round send button, the + for images, and image pills above it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatComposer(state: OrchestratorPaneState, actions: PaneActions) {
    val c = Candy.colors
    val locked = state.pending != null
    val canSend = !locked && state.busy == null && state.ui.connected && (state.draft.isNotBlank() || state.files.isNotEmpty())
    Column(Modifier.fillMaxWidth().windowInsetsPadding(BottomInsets).padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 4.dp)) {
        if (state.files.isNotEmpty()) Row(Modifier.padding(start = 44.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in state.files) Row(
                Modifier.clip(RoundedCornerShape(Radius.md)).floating(RoundedCornerShape(Radius.md)).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Image, c.mutedForeground, 14.dp)
                Txt(f.name, CandyType.xs, c.foreground, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp).padding(start = 4.dp))
                IconButton(Icons.X, "Remove ${f.name}", box = 28.dp, size = 14.dp) { actions.removeFile(f) }
            }
        }
        state.busy?.takeIf { it.isNotEmpty() }?.let { Txt(it, CandyType.xs, c.mutedForeground, modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(Icons.Plus, "Attach images", enabled = !locked, box = 36.dp, onClick = actions.attach)
            val canStash = !locked && state.busy == null && (state.draft.isNotBlank() || state.files.isNotEmpty())
            StashButton(state.stashed.size, canStash, !locked, actions.stash, actions.toggleStash)
            val shape = RoundedCornerShape(20.dp)
            val paste = actions.paste
            BasicTextField(
                state.draft, actions.draft,
                Modifier.weight(1f).heightIn(min = 40.dp).floating(shape)
                    .let { m -> if (paste == null || locked) m else m.contentReceiver { content ->
                        if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                        val uris = ArrayList<Uri>()
                        val rest = content.consume { item -> item.uri?.let { uris += it } != null }
                        if (uris.isNotEmpty()) paste(uris)
                        rest
                    } }
                    .semantics { contentDescription = "Message ${state.ui.name}" },
                enabled = !locked,
                textStyle = CandyType.chat.merge(TextStyle(color = c.foreground)),
                cursorBrush = SolidColor(c.primary),
                maxLines = 6,
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                        if (state.draft.isEmpty()) Txt(if (locked) "Answer above to continue" else "Message ${state.ui.name}", CandyType.chat, c.placeholder, maxLines = 1)
                        inner()
                    }
                },
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (canSend) c.send else c.foreground.copy(alpha = 0.1f))
                    .clickable(enabled = canSend, role = Role.Button, onClickLabel = "Send", onClick = actions.send)
                    .semantics { contentDescription = "Send" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.ArrowUp, if (canSend) Color.White else c.mutedForeground, 18.dp) }
        }
    }
}

// Overview and Needs you

@Composable
private fun Overview(ui: OrchestratorUi, actions: PaneActions) {
    val c = Candy.colors
    val groups = remember(ui.crew) { ui.crew.groupBy { it.group } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.needs.isNotEmpty()) {
            item(key = "h:needs") { SectionTitle("Needs you") }
            items(ui.needs.take(5), key = { "n:" + it.key }) { NeedCard(it, false, actions) }
        }
        for ((group, title) in listOf(CrewCard.Group.RUNNING to "Running", CrewCard.Group.FINISHED to "Finished", CrewCard.Group.STOPPED to "Stopped")) {
            val cards = groups[group].orEmpty()
            if (cards.isEmpty()) continue
            item(key = "h:$group") { SectionTitle(title) }
            items(cards, key = { it.key }) { CrewCardView(it, actions) }
        }
        if (ui.crew.isEmpty() && ui.needs.isEmpty()) item(key = "empty") {
            EmptyState("No threads on ${ui.machine} yet", "Every thread on the machine shows here as it works, finishes or stops.")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Txt(text, CandyType.smMedium, Candy.colors.mutedForeground, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun CrewCardView(card: CrewCard, actions: PaneActions) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.lg)
    val dot = when (card.pill) {
        StatusPill.WORKING, StatusPill.WAITING -> c.statusWorkingDot
        StatusPill.APPROVAL -> c.statusApprovalDot
        StatusPill.INPUT -> c.statusInputDot
        StatusPill.FAILED, StatusPill.LIMITED -> c.error
        StatusPill.READY -> if (card.state == "Unread") c.info else c.statusDoneDot
        StatusPill.NONE -> c.mutedForeground
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).floating(shape).clickable(role = Role.Button) { actions.openThread(card.envId, card.threadId) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(card.title, CandyType.smMedium, c.foreground, maxLines = 1, modifier = Modifier.weight(1f))
            StatusDot(dot, size = 7.dp)
            Txt(card.state, CandyType.xs, c.secondaryLabel, maxLines = 1)
        }
        if (card.project.isNotEmpty()) Txt(card.project, CandyType.xs, c.mutedForeground, maxLines = 1)
        card.lastLine?.let { Txt(it.replace('\n', ' '), CandyType.xs, c.secondaryLabel, maxLines = 2) }
        card.note?.let { Txt(it, CandyType.xs, c.warningForeground, maxLines = 1) }
    }
}

@Composable
fun NeedsList(needs: List<OrchestratorNeed>, busy: Boolean, actions: PaneActions, modifier: Modifier = Modifier) {
    if (needs.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            EmptyState("Nothing needs you", "Approvals and questions from every thread on the machine show here. The Orchestrator never approves for you.")
        }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(needs, key = { it.key }) { NeedCard(it, busy, actions) }
    }
}

/** An approval with T3's Allow and Deny, or a question that opens its thread. */
@Composable
private fun NeedCard(need: OrchestratorNeed, busy: Boolean, actions: PaneActions) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier.fillMaxWidth().clip(shape).floating(shape).border(1.dp, NeedsYouAmber.copy(alpha = 0.35f), shape)
            .clickable(role = Role.Button, onClickLabel = "Open the thread") { actions.openThread(need.envId, need.threadId) }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Txt(need.threadTitle, CandyType.smMedium, c.foreground, maxLines = 1)
            Txt(need.label, CandyType.xs, c.secondaryLabel, maxLines = 1)
        }
        if (need.isApproval) {
            CButton("Deny", kind = ButtonKind.GHOST, size = ButtonSize.SM, enabled = !busy) { actions.respond(need, false) }
            CButton("Allow", kind = ButtonKind.PRIMARY, size = ButtonSize.SM, enabled = !busy) { actions.respond(need, true) }
        } else {
            CButton("Answer", size = ButtonSize.SM) { actions.openThread(need.envId, need.threadId) }
        }
    }
}
