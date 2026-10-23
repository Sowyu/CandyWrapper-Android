package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.anikodev.candywrapper.core.model.ApprovalChoice
import org.anikodev.candywrapper.core.model.answer
import org.anikodev.candywrapper.core.sync.PendingRequest
import org.anikodev.candywrapper.core.sync.QueuedMessage

/** The buttons T3 shows when the request lists none (ComposerPendingApprovalActions.tsx). */
private val DEFAULT_CHOICES = listOf(
    ApprovalChoice("accept", "Approve"),
    ApprovalChoice("acceptForSession", "Approve for session"),
    ApprovalChoice("decline", "Decline"),
)

/** "File change approval", T3's title for each kind of request. */
private fun approvalTitle(kind: String?) = when (kind) {
    "command" -> "Command approval"
    "file-change" -> "File change approval"
    "file-read" -> "File read approval"
    "permission" -> "Permission approval"
    "mcp-elicitation" -> "Tool input"
    else -> "Approval needed"
}

/** The placeholder T3 puts in the composer while a request waits. */
const val APPROVAL_LOCK = "Resolve this approval request to continue"

/**
 * The tray T3 attaches to the top of the composer (ComposerBannerStack): inset from the
 * composer's sides, rounded on top, open at the bottom where the composer's edge closes it.
 */
@Composable
fun ComposerTray(modifier: Modifier = Modifier, border: Color = Candy.colors.border, fill: Color = Candy.colors.card, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = Radius.xl + 2.dp, topEnd = Radius.xl + 2.dp)
    val background = Candy.colors.card
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp).clip(shape).background(Candy.colors.card).background(fill)
            .border(1.dp, border, shape)
            // Top and sides only: the composer's top edge closes the tray, so its own bottom line is painted over.
            .drawWithContent {
                drawContent()
                val w = 1.dp.toPx()
                drawRect(background, Offset(w, size.height - w), Size(size.width - 2 * w, w))
                drawRect(fill, Offset(w, size.height - w), Size(size.width - 2 * w, w))
            }
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        content = content,
    )
}

/**
 * An approval (05-approval-request): the warning tray with a shield, "File change approval" in the
 * warning colour, the subject in mono, Decline and Approve, and the remaining choices under ⋯.
 */
@Composable
fun ApprovalPanel(request: PendingRequest, busy: Boolean, onDecide: (String) -> Unit) {
    val c = Candy.colors
    val row = request.row
    val kind = row.detail?.substringBefore(':')?.trim()
    val subject = (row.text ?: row.detail?.substringAfter(':', "")?.trim())?.takeIf { it.isNotBlank() }
    val choices = row.approvalOptions?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHOICES
    val decline = choices.firstOrNull { it.decision == "decline" } ?: choices.firstOrNull { it.decision == "cancel" }
    val approve = choices.firstOrNull { it != decline }
    val more = choices.filter { it != decline && it != approve }
    var menu by remember { mutableStateOf(false) }
    ComposerTray(border = c.warning.copy(alpha = 0.4f), fill = c.warningSurface) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.ShieldCheck, c.warning, 18.dp, Modifier.padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(approvalTitle(kind), CandyType.smMedium, c.warningForeground)
                if (subject != null) {
                    Txt(subject, CandyType.mono, c.foreground, modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
                }
                for (choice in choices) if (choice.warning != null) Txt(choice.warning!!, CandyType.xs, c.warningForeground)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (decline != null) CButton(decline.label, kind = ButtonKind.SECONDARY, size = ButtonSize.SM, enabled = !busy) { onDecide(decline.decision) }
            if (approve != null) CButton(approve.label, kind = ButtonKind.PRIMARY, size = ButtonSize.SM, enabled = !busy) { onDecide(approve.decision) }
            if (more.isNotEmpty()) {
                Box {
                    IconButton(Icons.Ellipsis, "More choices", box = 32.dp, enabled = !busy) { menu = true }
                    Menu(menu, { menu = false }, side = MenuSide.ABOVE, alignEnd = true) {
                        for (choice in more) MenuItem(choice.label) { menu = false; onDecide(choice.decision) }
                    }
                }
            }
        }
    }
}

/**
 * The agent's questions (06-user-input-request), one at a time: the header, "1/2", the question,
 * numbered options, and a typed answer that replaces them (threadActivity.ts:396-423).
 */
@Composable
fun QuestionPanel(request: PendingRequest, busy: Boolean, onAnswer: (Map<String, Any>) -> Unit, onDismiss: () -> Unit) {
    val c = Candy.colors
    val questions = request.row.questions.orEmpty()
    val picks = remember(request.requestId) { mutableStateMapOf<String, List<String>>() }
    val typed = remember(request.requestId) { mutableStateMapOf<String, String>().apply { questions.forEach { q -> q.initialAnswer?.let { put(q.id, it) } } } }
    var page by remember(request.requestId) { mutableIntStateOf(0) }
    val answers = questions.associate { it.id to it.answer(picks[it.id].orEmpty(), typed[it.id]) }
    val complete = questions.isNotEmpty() && answers.values.all { it != null }
    val q = questions.getOrNull(page.coerceIn(0, (questions.size - 1).coerceAtLeast(0)))
    ComposerTray {
        if (q == null) {
            Txt("The agent asked a question this app cannot show.", CandyType.sm, c.mutedForeground)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                CButton("Dismiss", kind = ButtonKind.GHOST, size = ButtonSize.SM, enabled = !busy, onClick = onDismiss)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(q.header.ifBlank { "Question" }, CandyType.xsMedium, c.mutedForeground, maxLines = 1, modifier = Modifier.weight(1f))
                if (questions.size > 1) Txt("${page + 1}/${questions.size}", CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), c.mutedForeground, modifier = Modifier.padding(end = 6.dp))
            }
            Txt(q.question, CandyType.base, c.foreground, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp, end = 6.dp))
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                for ((i, o) in q.options.withIndex()) {
                    val chosen = picks[q.id].orEmpty().contains(o.answerValue) && typed[q.id].isNullOrBlank()
                    OptionRow(i + 1, o.label, o.description, chosen, q.multiSelect) {
                        typed.remove(q.id)
                        val current = picks[q.id].orEmpty()
                        picks[q.id] = when {
                            !q.multiSelect -> listOf(o.answerValue)
                            o.answerValue in current -> current - o.answerValue
                            else -> current + o.answerValue
                        }
                    }
                }
                if (q.allowCustomAnswer) {
                    TextInput(
                        typed[q.id].orEmpty(), { typed[q.id] = it },
                        if (q.options.isEmpty()) "Type your answer" else "Type your own answer, or leave this blank to use the selected option",
                        Modifier.padding(top = 6.dp, end = 6.dp), singleLine = false, style = CandyType.sm,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                CButton("Dismiss", kind = ButtonKind.GHOST, size = ButtonSize.SM, enabled = !busy, onClick = onDismiss)
                if (page > 0) CButton("Back", size = ButtonSize.SM, enabled = !busy) { page-- }
                val last = page >= questions.size - 1
                val answered = answers[q.id] != null
                CButton(if (last) "Submit" else "Next", kind = ButtonKind.PRIMARY, size = ButtonSize.SM, enabled = !busy && if (last) complete else answered) {
                    if (last) {
                        @Suppress("UNCHECKED_CAST")
                        onAnswer(answers.filterValues { it != null } as Map<String, Any>)
                    } else {
                        page++
                    }
                }
            }
        }
    }
}

/** An option row: label in `font-medium`, its description muted, the number key T3 binds to it on the right. */
@Composable
private fun OptionRow(number: Int, label: String, description: String, chosen: Boolean, multi: Boolean, onClick: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Radius.lg))
            .background(if (chosen) c.accent else Color.Transparent)
            .clickable(role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics { selected = chosen }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, CandyType.smMedium, c.foreground)
            if (description.isNotBlank()) Txt(description, CandyType.xs, c.mutedForeground)
        }
        if (chosen) Icon(Icons.Check, c.foreground, 16.dp) else Txt("$number", CandyType.xs, c.mutedForeground)
    }
}

/** Messages waiting behind the active run (29-queued-messages): "Queued N", each with Steer and remove. */
@Composable
fun QueuedList(queued: List<QueuedMessage>, canSteer: Boolean, onSteer: (String) -> Unit, onCancel: (String) -> Unit) {
    val c = Candy.colors
    var open by remember { mutableStateOf(true) }
    ComposerTray(Modifier) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.ListTodo, c.mutedForeground, 15.dp)
            Box(Modifier.width(10.dp))
            Txt("Queued", CandyType.sm, c.mutedForeground, modifier = Modifier.weight(1f))
            Txt("${queued.size}", CandyType.xs, c.mutedForeground, modifier = Modifier.padding(end = 8.dp))
            Icon(if (open) Icons.ChevronDown else Icons.ChevronUp, c.mutedForeground, 14.dp, Modifier.padding(end = 6.dp))
        }
        if (open) for (q in queued) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(q.text.ifBlank { "Queued message" }, CandyType.sm, c.foreground, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 25.dp))
                if (canSteer) CButton("Steer", kind = ButtonKind.GHOST, size = ButtonSize.XS, icon = Icons.Undo2) { onSteer(q.runId) }
                IconButton(Icons.X, "Remove queued message", size = 15.dp, box = 28.dp) { onCancel(q.runId) }
            }
        }
    }
}
