package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

private fun kindLabel(kind: String?) = when (kind) {
    "command" -> "Run a command"
    "file-change" -> "Edit a file"
    "file-read" -> "Read a file"
    "permission" -> "Permission"
    "mcp-elicitation" -> "Tool input"
    else -> "Approval"
}

/** Takes the composer's place while the agent waits for an approval. */
@Composable
fun ApprovalPanel(request: PendingRequest, busy: Boolean, onDecide: (String) -> Unit) {
    val c = Candy.colors
    val row = request.row
    val kind = row.detail?.substringBefore(':')?.trim()
    PanelCard(Icons.ShieldCheck, "Approval needed", kindLabel(kind)) {
        val prompt = row.text ?: row.detail?.substringAfter(':', "")?.trim()
        if (!prompt.isNullOrBlank()) {
            if (kind == "command") CodeBox(prompt, maxLines = 12) else Txt(prompt, CandyType.ui, c.foreground)
        }
        val choices = row.approvalOptions?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHOICES
        for (choice in choices) {
            if (choice.warning != null) Txt(choice.warning!!, CandyType.meta, c.warning)
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            for ((i, choice) in choices.withIndex()) {
                val kindOf = when {
                    choice.decision == "decline" || choice.decision == "cancel" -> ButtonKind.SECONDARY
                    i == 0 -> ButtonKind.PRIMARY
                    else -> ButtonKind.SECONDARY
                }
                CButton(choice.label, Modifier.fillMaxWidth(), kind = kindOf, enabled = !busy) { onDecide(choice.decision) }
            }
        }
    }
}

/**
 * Takes the composer's place while the agent asks questions. Options are tap targets; a typed
 * answer replaces them, as in T3 (threadActivity.ts:396-423).
 */
@Composable
fun QuestionPanel(request: PendingRequest, busy: Boolean, onAnswer: (Map<String, Any>) -> Unit, onDismiss: () -> Unit) {
    val c = Candy.colors
    val questions = request.row.questions.orEmpty()
    val picks = remember(request.requestId) { mutableStateMapOf<String, List<String>>() }
    val typed = remember(request.requestId) { mutableStateMapOf<String, String>().apply { questions.forEach { q -> q.initialAnswer?.let { put(q.id, it) } } } }
    val answers = questions.associate { it.id to it.answer(picks[it.id].orEmpty(), typed[it.id]) }
    val complete = questions.isNotEmpty() && answers.values.all { it != null }
    PanelCard(Icons.MessageCircleQuestion, "The agent has a question", if (questions.size > 1) "${questions.size} questions" else null) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (q in questions) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (q.header.isNotBlank() && q.header != q.question) Txt(q.header, CandyType.label, c.mutedForeground)
                    Txt(q.question, CandyType.uiMedium, c.foreground)
                    for (o in q.options) {
                        val chosen = picks[q.id].orEmpty().contains(o.answerValue) && typed[q.id].isNullOrBlank()
                        OptionRow(o.label, o.description, chosen, q.multiSelect) {
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
                        TextInput(typed[q.id].orEmpty(), { typed[q.id] = it }, if (q.options.isEmpty()) "Your answer" else "Or type an answer",
                            singleLine = false, style = CandyType.ui)
                    }
                }
            }
        }
        Row(horizontalArrangement = ButtonRow) {
            CButton("Submit", kind = ButtonKind.PRIMARY, enabled = complete && !busy) {
                @Suppress("UNCHECKED_CAST")
                onAnswer(answers.filterValues { it != null } as Map<String, Any>)
            }
            CButton("Dismiss", kind = ButtonKind.GHOST, enabled = !busy, onClick = onDismiss)
        }
    }
}

@Composable
private fun OptionRow(label: String, description: String, chosen: Boolean, multi: Boolean, onClick: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Radius.md))
            .background(if (chosen) c.infoSurface else c.background)
            .border(1.dp, if (chosen) c.primary.copy(alpha = 0.5f) else c.border, RoundedCornerShape(Radius.md))
            .clickable(role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics { selected = chosen }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(16.dp).clip(if (multi) RoundedCornerShape(4.dp) else CircleShape)
                .border(1.5.dp, if (chosen) c.primary else c.input, if (multi) RoundedCornerShape(4.dp) else CircleShape)
                .background(if (chosen) c.primary else c.card),
            contentAlignment = Alignment.Center,
        ) { if (chosen) Icon(Icons.Check, c.primaryForeground, 11.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, CandyType.ui, c.foreground)
            if (description.isNotBlank()) Txt(description, CandyType.meta, c.mutedForeground)
        }
    }
}

@Composable
private fun PanelCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, content: @Composable () -> Unit) {
    val c = Candy.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 4.dp).clip(RoundedCornerShape(Radius.composer)).background(c.card)
            .border(1.dp, c.warning.copy(alpha = 0.45f), RoundedCornerShape(Radius.composer)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, c.warning, 16.dp)
            Spacer(Modifier.width(8.dp))
            Txt(title, CandyType.uiMedium, c.foreground, modifier = Modifier.weight(1f))
            if (detail != null) Txt(detail, CandyType.meta, c.mutedForeground)
        }
        content()
    }
}

/** Messages waiting behind the active run, each with Steer and cancel (protocol.md 8.6). */
@Composable
fun QueuedList(queued: List<QueuedMessage>, canSteer: Boolean, onSteer: (String) -> Unit, onCancel: (String) -> Unit) {
    val c = Candy.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(Radius.lg)).background(c.muted)
            .border(1.dp, c.border, RoundedCornerShape(Radius.lg)).padding(vertical = 2.dp),
    ) {
        for (q in queued) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Clock, c.mutedForeground, 14.dp)
                Spacer(Modifier.width(8.dp))
                Txt(q.text.ifBlank { "Queued message" }, CandyType.small, c.foreground, maxLines = 1, modifier = Modifier.weight(1f))
                if (canSteer) CButton("Steer", kind = ButtonKind.GHOST) { onSteer(q.runId) }
                IconButton(Icons.X, "Cancel queued message", size = 16.dp) { onCancel(q.runId) }
            }
        }
    }
}
