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
                }
                "user_input_request" -> detail = o.arr("questions")?.firstOrNull()?.let { (it as? JsonObject)?.str("question") }
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
