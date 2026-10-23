package org.anikodev.candywrapper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.diff.DiffLine
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.diff.LineKind
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.MdSpan
import org.anikodev.candywrapper.core.media.Media
import org.anikodev.candywrapper.core.model.RowKind
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.anikodev.candywrapper.core.sync.ToolGroup

/**
 * What a timeline row can ask of the screen. Expanded state and fetched details live there, keyed
 * by row id, in snapshot maps, so a row recomposes only when its own entry changes.
 */
@Stable
class TimelineHost(
    private val loadItem: suspend (String) -> ItemDetail,
    val expanded: MutableMap<String, Boolean>,
    val details: MutableMap<String, ItemDetail>,
    val openDiff: (checkpointId: String, title: String) -> Unit,
) {
    fun isOpen(key: String) = expanded[key] == true

    suspend fun load(itemId: String): ItemDetail = loadItem(itemId)
    fun toggle(key: String) { expanded[key] = !(expanded[key] ?: false) }

    companion object {
        /** Fetches a row's full item through `orchestration.getTurnItem` and reads it off the main thread. */
        fun forThread(thread: ThreadSession, expanded: MutableMap<String, Boolean>, details: MutableMap<String, ItemDetail>, openDiff: (String, String) -> Unit) =
            TimelineHost({ id ->
                try {
                    val item = thread.fullItem(id)
                    withContext(Dispatchers.Default) { ItemDetail.from(item) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ItemDetail("Could not load the output: ${e.message ?: "network error"}.", null)
                }
            }, expanded, details, openDiff)
    }
}

/** Space above a row, by kind: T3 spaces messages apart and packs work-log lines together. */
fun rowGap(item: org.anikodev.candywrapper.core.sync.TimelineItem): Int = when (item) {
    is ToolGroup -> 2
    is TimelineRow -> when {
        item.row.type == "checkpoint" -> 12
        item.kind == RowKind.USER -> 24
        item.kind == RowKind.ASSISTANT -> 12
        item.kind == RowKind.NOTICE -> 12
        item.kind == RowKind.PLAN || item.kind == RowKind.ERROR -> 12
        else -> 2
    }
}

@Composable
fun RowView(item: TimelineRow, host: TimelineHost) {
    val row = item.row
    val c = Candy.colors
    when {
        row.type == "checkpoint" -> ChangedFilesCard(row, host)
        row.kind == RowKind.USER -> UserBubble(row)
        row.kind == RowKind.ASSISTANT -> AssistantMessage(item)
        row.kind == RowKind.REASONING -> {
            val open = host.isOpen(row.id)
            val text = row.text.orEmpty().trim()
            WorkLogLine(
                Icons.Brain, if (row.streaming && text.isEmpty()) "Thinking" else text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Thought",
                onClick = if (text.contains('\n') || text.length > 80) ({ host.toggle(row.id) }) else null, open = open,
            ) {
                Txt(text, CandyType.sm, c.mutedForeground)
            }
        }
        row.kind == RowKind.PLAN -> PlanCard(item)
        row.kind == RowKind.TOOL -> ToolLine(row, host)
        row.kind == RowKind.NOTICE -> SystemDivider(listOfNotNull(row.title, row.text?.takeIf { it != row.title }).joinToString(" · "))
        else -> Alert(row.text ?: row.title, NoticeTone.ERROR)
    }
}

/** `TimelineSystemDivider`: a centred label between two hairlines. */
@Composable
private fun SystemDivider(text: String) {
    val c = Candy.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(c.border.copy(alpha = c.border.alpha * 0.7f)))
        Txt(text, CandyType.xs, c.mutedForeground, maxLines = 2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.border.copy(alpha = c.border.alpha * 0.7f)))
    }
}

/**
 * T3's user message: `max-w-[80%] rounded-2xl bg-message p-3`, right-aligned, with its time under
 * it (T3 shows the time row on touch screens without hover).
 */
@Composable
private fun UserBubble(row: TurnRow) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.clip(RoundedCornerShape(Radius.xxl)).background(c.messageSurface).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (row.attachments.isNotEmpty()) MessageAttachments(row.attachments)
                if (!row.text.isNullOrEmpty()) SelectionContainer { Txt(row.text!!, CandyType.chat, c.foreground) }
            }
        }
        val time = clockTime(row.atMs)
        if (time.isNotEmpty()) Txt(time, CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), c.mutedForeground, modifier = Modifier.padding(top = 6.dp, end = 4.dp))
    }
}

@Composable
private fun AssistantMessage(item: TimelineRow) {
    val c = Candy.colors
    val row = item.row
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            MarkdownBlocks(item.markdown ?: listOf(MdBlock.Paragraph(listOf(MdSpan(row.text.orEmpty())))))
        }
        // Images and files the agent attached to its reply.
        if (row.attachments.isNotEmpty()) MessageAttachments(row.attachments, Modifier.padding(top = 10.dp))
        // Text arrives as whole replacements (protocol.md 7.7), so "writing" is a fact, not an animation.
        val meta = if (row.streaming) "Writing" else clockTime(row.atMs)
        if (meta.isNotEmpty()) Txt(meta, CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), c.mutedForeground, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A proposed plan or to-do list on T3's card. */
@Composable
private fun PlanCard(item: TimelineRow) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.ListTodo, c.mutedForeground, 16.dp)
            Txt(item.row.title, CandyType.smMedium, c.foreground)
        }
        MarkdownBlocks(item.markdown ?: emptyList())
    }
}

/**
 * The changed files of a run and the way into its diff (ChangedFilesTree.tsx): a `rounded-lg
 * bg-secondary` card, "N changed files +a -d" and Open diff on top, one row per file with its
 * icon, mono path and counts.
 */
@Composable
private fun ChangedFilesCard(row: TurnRow, host: TimelineHost) {
    val c = Candy.colors
    val files = remember(row.body) {
        row.body?.lines()?.filter { it.isNotBlank() }?.map { line ->
            val parts = line.split("  ")
            Triple(parts.first(), parts.getOrNull(1)?.split(' ')?.getOrNull(0).orEmpty(), parts.getOrNull(1)?.split(' ')?.getOrNull(1).orEmpty())
        }.orEmpty()
    }
    if (files.isEmpty()) {
        SystemDivider(row.text ?: "No files changed")
        return
    }
    val (adds, dels) = remember(files) {
        files.sumOf { it.second.removePrefix("+").toIntOrNull() ?: 0 } to files.sumOf { it.third.removePrefix("-").toIntOrNull() ?: 0 }
    }
    val canOpen = row.checkpointId != null
    val shape = RoundedCornerShape(Radius.lg)
    val fill = if (c.dark) c.input.copy(alpha = c.input.alpha * 0.2f) else c.secondary
    Column(Modifier.fillMaxWidth().clip(shape).background(fill).let { if (c.dark) it else it.border(1.dp, c.border.copy(alpha = 0.6f), shape) }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .let { if (canOpen) it.clickable(role = Role.Button, onClickLabel = "Open diff") { host.openDiff(row.checkpointId!!, row.text ?: "Changes") } else it }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt(if (files.size == 1) "1 changed file" else "${files.size} changed files", CandyType.smMedium, c.foreground, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Txt("+$adds", CandyType.xs.copy(fontFamily = FontFamily.Monospace), c.diffAdditionForeground)
                Txt("-$dels", CandyType.xs.copy(fontFamily = FontFamily.Monospace), c.diffDeletionForeground)
            }
            if (canOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.FileDiff, c.mutedForeground, 14.dp)
                    Txt("Open diff", CandyType.sm, c.mutedForeground)
                }
            }
        }
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
            for ((path, add, del) in files.take(8)) {
                Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(fileIcon(path), c.mutedForeground.copy(alpha = 0.75f), 14.dp)
                    Txt(path.substringAfterLast('/'), CandyType.xs.copy(fontFamily = FontFamily.Monospace), c.mutedForeground, maxLines = 1, modifier = Modifier.weight(1f))
                    Txt(add, CandyType.xxs.copy(fontFamily = FontFamily.Monospace), c.diffAdditionForeground)
                    Txt(del, CandyType.xxs.copy(fontFamily = FontFamily.Monospace), c.diffDeletionForeground)
                }
            }
            if (files.size > 8) Txt("${files.size - 8} more", CandyType.xs, c.mutedForeground, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

private fun fileIcon(path: String): ImageVector = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "txt", "rst" -> Icons.FileText
    "" -> Icons.File
    else -> Icons.FileCode2
}

private fun toolIcon(type: String): ImageVector = when (type) {
    "command_execution" -> Icons.Terminal
    "file_change" -> Icons.PenLine
    "file_search" -> Icons.FileSearch
    "web_search" -> Icons.Globe
    "approval_request" -> Icons.ShieldCheck
    "user_input_request" -> Icons.MessageCircleQuestion
    "subagent" -> Icons.Bot
    "todo_list" -> Icons.ListChecks
    else -> Icons.Wrench
}

/**
 * T3's work log line (WorkLog.tsx `WorkLogLine`): an icon in a 24dp slot and the label in
 * `text-sm text-secondary-label`, truncated; tapping a line with detail opens it beneath, indented
 * past the icon (`ms-7`).
 */
@Composable
private fun WorkLogLine(
    icon: ImageVector,
    label: String,
    detail: String? = null,
    iconTint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    open: Boolean = false,
    state: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(Radius.md))
                .let { if (onClick != null) it.clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onClick) else it }
                .let { m -> if (state != null) m.semantics { stateDescription = state } else m }
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(icon, iconTint ?: c.iconMuted, 16.dp) }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(label, CandyType.sm, c.secondaryLabel, maxLines = 1, modifier = Modifier.weight(1f, fill = detail == null))
                if (detail != null) Txt(detail, CandyType.sm, c.mutedForeground.copy(alpha = 0.65f), maxLines = 1, modifier = Modifier.weight(1f))
            }
            if (trailing != null) trailing()
            if (onClick != null) Chevron(open)
        }
        if (open && content != null) Box(Modifier.padding(start = 30.dp, top = 2.dp, bottom = 8.dp)) { content() }
    }
}

/** A disclosure chevron that turns a quarter over T3's 150 ms. */
@Composable
private fun Chevron(open: Boolean) {
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.fast(), label = "chevron")
    Icon(Icons.ChevronRight, Candy.colors.mutedForeground, 14.dp, Modifier.rotate(turn))
}

@Composable
fun ToolLine(row: TurnRow, host: TimelineHost) {
    val c = Candy.colors
    val open = host.isOpen(row.id)
    WorkLogLine(
        toolIcon(row.type), row.title, row.detail,
        iconTint = if (row.failed) c.toolErrorIcon else null,
        trailing = if (row.running) ({ Txt("Running", CandyType.xs, c.statusWorking) }) else null,
        onClick = { host.toggle(row.id) }, open = open,
        state = if (row.failed) "Failed" else if (row.running) "Running" else "Done",
    ) { ToolDetail(row, host) }
}

@Composable
private fun ToolDetail(row: TurnRow, host: TimelineHost) {
    val c = Candy.colors
    val fetchable = ItemDetail.worthFetching(row.type) && row.visibility == "local"
    val detail = host.details[row.id + ":" + row.status]
    if (fetchable && detail == null) {
        LaunchedEffect(row.id, row.status) {
            host.details[row.id + ":" + row.status] = host.load(row.id)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val images = LocalImages.current
        val threadId = images?.threadId
        if (threadId != null) {
            // The tool output's own images win; the file a read viewed shows only when the output has none.
            val refs = remember(row.id, detail?.images, row.viewedImagePath) {
                val count = detail?.images ?: 0
                when {
                    count > 0 -> (0 until count).map { Media.toolOutput(threadId, row.id, it) }
                    row.viewedImagePath != null && (detail != null || !fetchable) -> listOf(Media.mediaFile(threadId, row.viewedImagePath!!))
                    else -> emptyList()
                }
            }
            for ((i, ref) in refs.withIndex()) NaturalImage(ref, onClick = { images.open(refs, i) })
        }
        val summary = row.body ?: row.detail
        when {
            detail?.diff != null -> InlineDiff(detail.diff!!)
            detail?.text != null -> CodeBox(detail.text!!)
            summary != null -> CodeBox(summary)
        }
        if (fetchable && detail == null) Txt("Loading output", CandyType.xs, c.mutedForeground)
    }
}

/** Monospace output on the code surface that scrolls sideways instead of wrapping, capped by line count. */
@Composable
fun CodeBox(text: String, maxLines: Int = 40) {
    val c = Candy.colors
    val shown = remember(text, maxLines) {
        val lines = text.lines()
        if (lines.size <= maxLines) text else lines.take(maxLines).joinToString("\n") + "\n… ${lines.size - maxLines} more lines"
    }
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.codeBackground).border(1.dp, c.border, shape)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SelectionContainer { Txt(shown, CandyType.monoSmall, c.foreground) }
    }
}

/** A short diff inside a tool row; long ones stop after [maxLines] and point at the run's full diff. */
@Composable
fun InlineDiff(lines: List<DiffLine>, maxLines: Int = 120) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.codeBackground).border(1.dp, c.border, shape).padding(vertical = 4.dp)) {
        for (line in lines.take(maxLines)) DiffLineView(line, wrap = true)
        if (lines.size > maxLines) Txt("${lines.size - maxLines} more lines in the run's diff", CandyType.xs, c.mutedForeground, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/**
 * Two or more tool calls in a row, folded behind one line that names what they did, the way T3
 * folds a finished turn's work behind "Worked for".
 */
@Composable
fun ToolGroupView(group: ToolGroup, host: TimelineHost) {
    val c = Candy.colors
    val open = host.isOpen(group.key)
    val summary = remember(group) { groupSummary(group) }
    Column(Modifier.fillMaxWidth()) {
        WorkLogLine(
            Icons.Wrench, summary, iconTint = if (group.failed > 0) c.toolErrorIcon else null,
            trailing = if (group.running) ({ Txt("Running", CandyType.xs, c.statusWorking) }) else null,
            onClick = { host.toggle(group.key) }, open = open,
        )
        if (open) Column { for (r in group.rows) ToolLine(r.row, host) }
    }
}

/** "Ran 2 commands, edited 1 file and 3 other actions", after T3's work log summary. */
private fun groupSummary(group: ToolGroup): String {
    val counts = group.rows.groupingBy { it.row.type }.eachCount()
    fun n(count: Int, one: String, many: String) = if (count == 1) one else many.replace("#", count.toString())
    val parts = ArrayList<String>()
    counts["command_execution"]?.let { parts += n(it, "ran 1 command", "ran # commands") }
    counts["file_change"]?.let { parts += n(it, "edited 1 file", "edited # files") }
    val searches = (counts["file_search"] ?: 0) + (counts["web_search"] ?: 0)
    if (searches > 0) parts += n(searches, "searched once", "searched # times")
    val other = group.rows.size - (counts["command_execution"] ?: 0) - (counts["file_change"] ?: 0) - searches
    if (other > 0) parts += n(other, "1 other action", "# other actions")
    val text = when (parts.size) {
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }.replaceFirstChar { it.uppercase() }
    return if (group.failed > 0) "$text · ${group.failed} failed" else text
}

/**
 * The line under the newest message while a run is active: T3's "Working for 4s" over a hairline.
 * The seconds tick on the shared ticker while the row is on screen.
 */
@Composable
fun WorkingRow(since: Long, queued: Int) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val style = CandyType.sm.merge(TextStyle(fontFeatureSettings = "tnum"))
        val tail = if (queued > 0) " · $queued queued" else ""
        val padding = Modifier.padding(start = 2.dp, bottom = 8.dp)
        if (c.redesign) {
            // Redesign: Capy's dot grid before the line, the digits roll, and no hairline under it.
            Row(padding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DotGrid(c.mutedForeground)
                if (since > 0) TickingText(since, ::workingTimer, style, c.mutedForeground, prefix = "Working for ", suffix = tail, roll = true)
                else Txt("Working...$tail", style, c.mutedForeground)
            }
            return@Column
        }
        if (since > 0) TickingText(since, ::workingTimer, style, c.mutedForeground, prefix = "Working for ", suffix = tail, modifier = padding)
        else Txt("Working...$tail", style, c.mutedForeground, modifier = padding)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border.copy(alpha = c.border.alpha * 0.6f)))
    }
}

/** One diff line on pierre's colours: a tinted row, the +/- gutter mark, the text in mono. */
@Composable
fun DiffLineView(line: DiffLine, wrap: Boolean, numbers: Boolean = false) {
    val c = Candy.colors
    val (bg, fg, mark) = when (line.kind) {
        LineKind.ADDED -> Triple(c.diffAdditionSurface, c.foreground, "+")
        LineKind.REMOVED -> Triple(c.diffDeletionSurface, c.foreground, "-")
        LineKind.HUNK -> Triple(c.infoSurface, c.mutedForeground, "@@")
        LineKind.NOTE -> Triple(Color.Transparent, c.mutedForeground, "\\")
        LineKind.CONTEXT -> Triple(Color.Transparent, c.foreground, " ")
    }
    val markColor = when (mark) { "+" -> c.diffAdditionForeground; "-" -> c.diffDeletionForeground; else -> c.mutedForeground }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp)) {
        if (numbers) {
            Txt(if (line.oldLine > 0) line.oldLine.toString() else "", CandyType.monoSmall, c.mutedForeground.copy(alpha = 0.7f), align = TextAlign.End, modifier = Modifier.width(34.dp))
            Txt(if (line.newLine > 0) line.newLine.toString() else "", CandyType.monoSmall, c.mutedForeground.copy(alpha = 0.7f), align = TextAlign.End, modifier = Modifier.width(34.dp))
            Spacer(Modifier.width(8.dp))
        }
        Txt(mark.take(1), CandyType.monoSmall, markColor, modifier = Modifier.width(12.dp))
        Txt(line.text.ifEmpty { " " }, CandyType.monoSmall, fg, maxLines = if (wrap) Int.MAX_VALUE else 1)
    }
}
