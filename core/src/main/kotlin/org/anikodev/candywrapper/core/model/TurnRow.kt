package org.anikodev.candywrapper.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/** How the thread view draws a row. Every tool type folds into [TOOL]. */
enum class RowKind { USER, ASSISTANT, REASONING, PLAN, TOOL, NOTICE, ERROR }

/**
 * One timeline row, reduced from a wire turn item (protocol.md 7.5) to what the read-only view
 * shows. It is also the cached form. Instances are reused while an item does not change, so the
 * list skips rows that did not change.
 */
@Serializable
data class TurnRow(
    val id: String,
    val type: String,
    val ordinal: Long,
    val status: String,
    val runId: String? = null,
    /** The item's `title`, or a label for the type. */
    val title: String,
    /** Markdown or plain text: the message, reasoning, plan or notice. */
    val text: String? = null,
    /** One-line summary for tools: the command, file, pattern or tool name. */
    val detail: String? = null,
    /** Longer tool body shown when the row is expanded. */
    val body: String? = null,
    val streaming: Boolean = false,
    val failed: Boolean = false,
    val inputIntent: String? = null,
    /** `local` for this thread's own rows, else `inherited` or `synthetic` from a fork source. */
    val visibility: String = "local",
    /** The `RuntimeRequestId` of an approval or question row, echoed exactly when answering. */
    val requestId: String? = null,
    /** An approval's buttons, when the server lists them. */
    val approvalOptions: List<ApprovalChoice>? = null,
    /** A question row's questions. */
    val questions: List<Question>? = null,
    /** A checkpoint row's id, the key for its run's diff. */
    val checkpointId: String? = null,
    /** When the item finished, or last changed while running. Epoch ms, 0 when unknown. */
    val atMs: Long = 0,
    /** Images and files attached to a user message. */
    val attachmentCount: Int = 0,
) {
    val kind: RowKind
        get() = when (type) {
            "user_message" -> RowKind.USER
            "assistant_message" -> RowKind.ASSISTANT
            "reasoning" -> RowKind.REASONING
            "proposed_plan", "todo_list" -> RowKind.PLAN
            "error" -> RowKind.ERROR
            "run_interrupt_request", "run_interrupt_result", "system_notice", "compaction", "handoff", "fork",
            "thread_created", "notification", "checkpoint" -> RowKind.NOTICE
            else -> RowKind.TOOL
        }

    val running: Boolean get() = status == "running" || status == "pending" || status == "waiting"

    companion object {
        private const val MAX_DETAIL = 400
        private const val MAX_BODY = 4_000

        /** Decodes one turn item. Unknown types keep their common fields and a generic label (protocol.md 1.4). */
        fun fromItem(o: JsonObject, visibility: String = "local"): TurnRow? {
            val id = o.str("id") ?: return null
            val type = o.str("type") ?: return null
            val title = o.str("title")
            var text: String? = null
            var detail: String? = null
            var body: String? = null
            var failed = o.str("status") == "failed"
            var approvalOptions: List<ApprovalChoice>? = null
            var questions: List<Question>? = null
            var label: String = title ?: TYPE_LABELS[type] ?: type.replace('_', ' ').replaceFirstChar { it.uppercase() }
            when (type) {
                "user_message", "assistant_message", "reasoning" -> text = o.str("text")
                "proposed_plan" -> text = o.str("markdown")
                "todo_list" -> text = o.arr("steps").orEmpty().mapNotNull { it as? JsonObject }.joinToString("\n") { s ->
                    val mark = when (s.str("status")) { "completed" -> "[x]"; "running" -> "[~]"; else -> "[ ]" }
                    "- $mark ${s.str("text").orEmpty()}"
                }.let { steps -> listOfNotNull(o.str("explanation"), steps.ifBlank { null }).joinToString("\n\n") }
                "command_execution" -> {
                    detail = o.str("input")
                    failed = failed || o.bool("outputIndicatesFailure") == true || (o.long("exitCode") ?: 0) != 0L
                    body = o.str("output")
                }
                "file_change" -> {
                    val name = o.str("fileName")?.substringAfterLast('/')
                    val add = o.long("additions")
                    val del = o.long("deletions")
                    detail = listOfNotNull(name, if (add != null || del != null) "+${add ?: 0} -${del ?: 0}" else null).joinToString("  ")
                    body = o.str("fileName")
                }
                "file_search" -> detail = o.str("pattern")
                "web_search" -> detail = o.arr("patterns")?.joinToString(", ") { (it as? JsonPrimitive)?.content.orEmpty() }
                "dynamic_tool" -> {
                    if (title == null) label = o.str("toolName") ?: label
                    detail = summarize(o["input"])
                    body = o["input"]?.takeIf { it !is JsonNull }?.toString()
                }
                "approval_request" -> {
                    detail = listOfNotNull(o.str("requestKind"), o.str("prompt")).joinToString(": ")
                    text = o.str("prompt")
                    approvalOptions = o.arr("options")?.mapNotNull { e ->
                        val c = e as? JsonObject ?: return@mapNotNull null
                        ApprovalChoice(c.str("decision") ?: return@mapNotNull null, c.str("label") ?: c.str("decision")!!, c.str("warning"))
                    }
                }
                "user_input_request" -> {
                    questions = o.arr("questions").orEmpty().mapNotNull { (it as? JsonObject)?.let(Question::from) }
                    detail = questions.firstOrNull()?.question
                }
                "checkpoint" -> {
                    val files = o.arr("files").orEmpty().mapNotNull { it as? JsonObject }
                    text = when (files.size) { 0 -> "No files changed"; 1 -> "1 file changed"; else -> "${files.size} files changed" }
                    body = files.joinToString("\n") { "${it.str("path")}  +${it.long("additions") ?: 0} -${it.long("deletions") ?: 0}" }
                }
                "run_interrupt_request", "run_interrupt_result", "system_notice" -> text = o.str("message")
                "error" -> {
                    text = o.obj("failure")?.str("message") ?: o.str("message")
                    failed = true
                }
                "compaction" -> text = o.str("summary") ?: "Context compacted"
                "notification" -> text = o.str("summary")
                "subagent" -> {
                    detail = o.str("prompt")
                    body = o.str("result") ?: o.str("progress")
                }
                "secret_request" -> detail = o.str("label")
            }
            return TurnRow(
                id = id,
                type = type,
                ordinal = o.long("ordinal") ?: 0,
                status = o.str("status") ?: "completed",
                runId = o.str("runId"),
                title = label,
                text = text,
                detail = detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_DETAIL),
                body = body?.take(MAX_BODY),
                streaming = o.bool("streaming") == true,
                failed = failed,
                inputIntent = o.str("inputIntent"),
                visibility = visibility,
                requestId = o.str("requestId"),
                approvalOptions = approvalOptions,
                questions = questions,
                checkpointId = o.str("checkpointId"),
                atMs = ThreadSummary.time(o.str("completedAt") ?: o.str("updatedAt") ?: o.str("startedAt")),
                attachmentCount = o.arr("attachments")?.size ?: 0,
            )
        }

        /** The first string value of a tool input, for a one-line label. */
        private fun summarize(e: JsonElement?): String? = when (e) {
            null, JsonNull -> null
            is JsonPrimitive -> e.content
            is JsonObject -> e.str("summary") ?: e.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonArray -> null
        }

        private val TYPE_LABELS = mapOf(
            "command_execution" to "Ran command",
            "file_change" to "Edited file",
            "file_search" to "Searched files",
            "web_search" to "Searched the web",
            "approval_request" to "Approval",
            "user_input_request" to "Question",
            "checkpoint" to "Checkpoint",
            "reasoning" to "Thinking",
            "proposed_plan" to "Plan",
            "todo_list" to "To-do",
            "subagent" to "Subagent",
            "error" to "Error",
            "secret_request" to "Secret request",
        )
    }
}

/** One approval button: the decision to send and its label (`approval_request.options`). */
@Serializable
data class ApprovalChoice(val decision: String, val label: String, val warning: String? = null)

@Serializable
data class QuestionOption(val label: String, val description: String = "", val value: String? = null) {
    /** What an answer carries for this option: its `value`, else its label (threadActivity.ts:364-377). */
    val answerValue: String get() = value ?: label.trim()
}

/** `OrchestrationV2UserInputQuestion` (contracts `orchestrationV2.ts:1242-1258`). */
@Serializable
data class Question(
    val id: String,
    val header: String,
    val question: String,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val allowCustomAnswer: Boolean = true,
    val initialAnswer: String? = null,
) {
    companion object {
        fun from(o: JsonObject): Question? = Question(
            id = o.str("id") ?: return null,
            header = o.str("header") ?: "",
            question = o.str("question") ?: return null,
            options = o.arr("options").orEmpty().mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                QuestionOption(c.str("label") ?: return@mapNotNull null, c.str("description").orEmpty(), c.str("value"))
            },
            multiSelect = o.bool("multiSelect") == true,
            allowCustomAnswer = o.bool("allowCustomAnswer") != false,
            initialAnswer = o.str("initialAnswer"),
        )
    }
}
