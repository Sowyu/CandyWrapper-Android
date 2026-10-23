package org.anikodev.candywrapper.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.diff.DiffLine
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.MdSpan
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
    val thread: ThreadSession,
    val expanded: MutableMap<String, Boolean>,
    val details: MutableMap<String, ItemDetail>,
    val openDiff: (checkpointId: String, title: String) -> Unit,
) {
    fun isOpen(key: String) = expanded[key] == true

    /** Fetches a row's full item and reads it off the main thread. */
    suspend fun load(itemId: String): ItemDetail = try {
        val item = thread.fullItem(itemId)
        withContext(Dispatchers.Default) { ItemDetail.from(item) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ItemDetail("Could not load the output: ${e.message ?: "network error"}.", null)
    }
    fun toggle(key: String) { expanded[key] = !(expanded[key] ?: false) }
}

@Composable
fun RowView(item: TimelineRow, host: TimelineHost) {
    val row = item.row
    when {
        row.type == "checkpoint" -> CheckpointCard(row, host)
        row.kind == RowKind.USER -> UserBubble(row)
        row.kind == RowKind.ASSISTANT -> AssistantMessage(item)
        row.kind == RowKind.REASONING -> Collapsible(Icons.Brain, if (row.streaming) "Thinking" else "Thought", host.isOpen(row.id), { host.toggle(row.id) }) {
            Txt(row.text.orEmpty(), CandyType.small, Candy.colors.mutedForeground)
        }
        row.kind == RowKind.PLAN -> PlanCard(item)
        row.kind == RowKind.TOOL -> ToolLine(row, host)
        row.kind == RowKind.NOTICE -> Txt(
            listOfNotNull(row.title, row.text?.takeIf { it != row.title }).joinToString(" · "),
            CandyType.meta, Candy.colors.mutedForeground, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        else -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(Candy.colors.errorSurface).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.CircleAlert, Candy.colors.errorForeground, 16.dp, Modifier.padding(top = 1.dp))
            Txt(row.text ?: row.title, CandyType.small, Candy.colors.errorForeground)
        }
    }
}

@Composable
private fun UserBubble(row: TurnRow) {
    val c = Candy.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(Radius.composer)).background(c.messageSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!row.text.isNullOrEmpty()) SelectionContainer { Txt(row.text!!, CandyType.body, c.foreground) }
            if (row.attachmentCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Paperclip, c.mutedForeground, 13.dp)
                    Txt(if (row.attachmentCount == 1) "1 attachment" else "${row.attachmentCount} attachments", CandyType.meta, c.mutedForeground)
                }
            }
        }
        val time = clockTime(row.atMs)
        if (time.isNotEmpty()) Txt(time, CandyType.meta, c.mutedForeground, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
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
        // Text arrives as whole replacements (protocol.md 7.7), so "writing" is a fact, not an animation.
        val meta = if (row.streaming) "Writing" else clockTime(row.atMs)
        if (meta.isNotEmpty()) Txt(meta, CandyType.meta, c.mutedForeground, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PlanCard(item: TimelineRow) {
    val c = Candy.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.card).border(1.dp, c.border, RoundedCornerShape(Radius.lg)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.ListTodo, c.mutedForeground, 16.dp)
            Txt(item.row.title, CandyType.label, c.mutedForeground)
        }
        MarkdownBlocks(item.markdown ?: emptyList())
    }
}

/** The changed files of a run and the way into its diff (T3's "1 changed file +18 -0, Open diff"). */
@Composable
private fun CheckpointCard(row: TurnRow, host: TimelineHost) {
    val c = Candy.colors
    val files = remember(row.body) {
        row.body?.lines()?.filter { it.isNotBlank() }?.map { line ->
            val parts = line.split("  ")
            Triple(parts.first(), parts.getOrNull(1)?.split(' ')?.getOrNull(0).orEmpty(), parts.getOrNull(1)?.split(' ')?.getOrNull(1).orEmpty())
        }.orEmpty()
    }
    if (files.isEmpty()) {
        Txt(row.text ?: "No files changed", CandyType.meta, c.mutedForeground, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        return
    }
    val canOpen = row.checkpointId != null
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.card).border(1.dp, c.border, RoundedCornerShape(Radius.lg))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .let { if (canOpen) it.clickable(role = Role.Button, onClickLabel = "Open diff") { host.openDiff(row.checkpointId!!, row.text ?: "Changes") } else it }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(row.text ?: "Changes", CandyType.uiMedium, c.foreground, modifier = Modifier.weight(1f))
            if (canOpen) {
                Icon(Icons.FileDiff, c.mutedForeground, 15.dp)
                Spacer(Modifier.width(4.dp))
                Txt("Open diff", CandyType.small, c.mutedForeground)
            }
        }
        Divider()
        Column(Modifier.padding(vertical = 6.dp)) {
            for ((path, add, del) in files.take(8)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(path.substringAfterLast('/'), CandyType.monoSmall, c.foreground, maxLines = 1, modifier = Modifier.weight(1f))
                    Txt(add, CandyType.monoSmall, c.diffAdditionForeground)
                    Spacer(Modifier.width(8.dp))
                    Txt(del, CandyType.monoSmall, c.diffDeletionForeground)
                }
            }
            if (files.size > 8) Txt("${files.size - 8} more", CandyType.meta, c.mutedForeground, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
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

@Composable
fun ToolLine(row: TurnRow, host: TimelineHost) {
    val c = Candy.colors
    val open = host.isOpen(row.id)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(Radius.md))
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { host.toggle(row.id) }
                .semantics { stateDescription = if (row.failed) "Failed" else if (row.running) "Running" else "Done" }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(toolIcon(row.type), if (row.failed) c.errorForeground else c.iconMuted, 15.dp)
            Spacer(Modifier.width(10.dp))
            Txt(row.title, CandyType.small, c.foreground, maxLines = 1)
            if (row.detail != null) {
                Spacer(Modifier.width(8.dp))
                Txt(row.detail!!, CandyType.monoSmall, c.mutedForeground, maxLines = 1, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (row.running) Txt("running", CandyType.meta, c.infoForeground, modifier = Modifier.padding(start = 6.dp))
            Icon(if (open) Icons.ChevronDown else Icons.ChevronRight, c.mutedForeground, 14.dp, Modifier.padding(start = 4.dp))
        }
        if (open) ToolDetail(row, host)
    }
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
    Column(Modifier.padding(start = 25.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val summary = row.body ?: row.detail
        when {
            detail?.diff != null -> InlineDiff(detail.diff!!)
            detail?.text != null -> CodeBox(detail.text!!)
            summary != null -> CodeBox(summary)
        }
        if (fetchable && detail == null) Txt("Loading output", CandyType.meta, c.mutedForeground)
    }
}

/** Monospace text that scrolls sideways instead of wrapping, capped in height by its line count. */
@Composable
fun CodeBox(text: String, maxLines: Int = 40) {
    val c = Candy.colors
    val shown = remember(text, maxLines) {
        val lines = text.lines()
        if (lines.size <= maxLines) text else lines.take(maxLines).joinToString("\n") + "\n… ${lines.size - maxLines} more lines"
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.codeBackground).border(1.dp, c.border, RoundedCornerShape(Radius.md))
            .horizontalScroll(rememberScrollState()).padding(10.dp),
    ) {
        SelectionContainer { Txt(shown, CandyType.monoSmall, c.foreground) }
    }
}

/** A short diff inside a tool row; long ones stop after [maxLines] and point at the run's full diff. */
@Composable
fun InlineDiff(lines: List<DiffLine>, maxLines: Int = 120) {
    val c = Candy.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.codeBackground).border(1.dp, c.border, RoundedCornerShape(Radius.md))
            .padding(vertical = 6.dp),
    ) {
        for (line in lines.take(maxLines)) DiffLineView(line, wrap = true)
        if (lines.size > maxLines) Txt("${lines.size - maxLines} more lines in the run's diff", CandyType.meta, c.mutedForeground, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun Collapsible(icon: ImageVector, label: String, open: Boolean, toggle: () -> Unit, content: @Composable () -> Unit) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(Radius.md))
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = toggle).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, c.iconMuted, 15.dp)
            Spacer(Modifier.width(10.dp))
            Txt(label, CandyType.small, c.mutedForeground, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.ChevronDown else Icons.ChevronRight, c.mutedForeground, 14.dp)
        }
        if (open) Box(Modifier.padding(start = 25.dp, top = 2.dp, bottom = 6.dp)) { content() }
    }
}

/** Two or more tool calls in a row, folded into one line that names what they did. */
@Composable
fun ToolGroupView(group: ToolGroup, host: TimelineHost) {
    val c = Candy.colors
    val open = host.isOpen(group.key)
    val summary = remember(group) { groupSummary(group) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(Radius.md))
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { host.toggle(group.key) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Wrench, if (group.failed > 0) c.errorForeground else c.iconMuted, 15.dp)
            Spacer(Modifier.width(10.dp))
            Txt(summary, CandyType.small, c.mutedForeground, maxLines = 2, modifier = Modifier.weight(1f))
            if (group.running) Txt("running", CandyType.meta, c.infoForeground, modifier = Modifier.padding(start = 6.dp))
            Icon(if (open) Icons.ChevronDown else Icons.ChevronRight, c.mutedForeground, 14.dp, Modifier.padding(start = 4.dp))
        }
        if (open) Column(Modifier.padding(start = 12.dp)) { for (r in group.rows) ToolLine(r.row, host) }
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

/** The row under the newest message while a run is active. Static text; the minute clock moves it. */
@Composable
fun WorkingRow(since: Long, now: Long, queued: Int) {
    val c = Candy.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(c.infoForeground, size = 6.dp)
        Spacer(Modifier.width(10.dp))
        val time = if (since > 0) " for ${duration(now - since)}" else ""
        Txt("Working$time" + if (queued > 0) " · $queued queued" else "", CandyType.small, c.mutedForeground)
    }
}

@Composable
fun DiffLineView(line: DiffLine, wrap: Boolean, numbers: Boolean = false) {
    val c = Candy.colors
    val (bg, fg, mark) = when (line.kind) {
        org.anikodev.candywrapper.core.diff.LineKind.ADDED -> Triple(c.diffAdditionSurface, c.foreground, "+")
        org.anikodev.candywrapper.core.diff.LineKind.REMOVED -> Triple(c.diffDeletionSurface, c.foreground, "-")
        org.anikodev.candywrapper.core.diff.LineKind.HUNK -> Triple(c.infoSurface, c.mutedForeground, "@@")
        org.anikodev.candywrapper.core.diff.LineKind.NOTE -> Triple(Color.Transparent, c.mutedForeground, "\\")
        org.anikodev.candywrapper.core.diff.LineKind.CONTEXT -> Triple(Color.Transparent, c.foreground, " ")
    }
    val markColor = when (mark) { "+" -> c.diffAdditionForeground; "-" -> c.diffDeletionForeground; else -> c.mutedForeground }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp)) {
        if (numbers) {
            Txt(if (line.oldLine > 0) line.oldLine.toString() else "", CandyType.monoSmall, c.mutedForeground, align = TextAlign.End, modifier = Modifier.width(34.dp))
            Txt(if (line.newLine > 0) line.newLine.toString() else "", CandyType.monoSmall, c.mutedForeground, align = TextAlign.End, modifier = Modifier.width(34.dp))
            Spacer(Modifier.width(8.dp))
        }
        Txt(mark.take(1), CandyType.monoSmall, markColor, modifier = Modifier.width(12.dp))
        Txt(line.text.ifEmpty { " " }, CandyType.monoSmall, fg, maxLines = if (wrap) Int.MAX_VALUE else 1)
    }
}
