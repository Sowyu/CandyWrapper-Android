package org.anikodev.candywrapper.core.orchestrator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.anikodev.candywrapper.core.json.WireJson

/**
 * The fenced blocks Redesign takes out of the Orchestrator's replies (CandyWrapper OrchestratorBlocks.swift). To the
 * official app they stay plain text.
 */
data class OrchestratorReply(
    /** The reply without its blocks. */
    val text: String,
    val plan: List<PlanStep>? = null,
    val memories: List<Memory> = emptyList(),
    val digest: String? = null,
    /** Calls for other machines, from `orchestrator-call` blocks. */
    val calls: List<OrchestratorCall> = emptyList(),
) {
    /** [threadId] is the local thread id a `t3-thread://v1/<id>` link names. */
    data class PlanStep(val text: String, val done: Boolean, val threadId: String?) {
        /** `[Fixer](t3-thread://v1/x)` reads "Fixer". */
        val displayText: String
            get() = text.replace(LINK, "$1").replace(BARE_LINK, "").trim()
    }

    /** [note] is one of [Notebook.names]. */
    data class Memory(val note: String, val text: String)

    companion object {
        private val KINDS = setOf("orchestrator-plan", "orchestrator-memory", "orchestrator-digest", "orchestrator-call")
        private val STEP_MARK = Regex("""^(\d+[.)]|[-*+])\s+""")
        private val CHECK = Regex("""^\[( |x|X)\]\s*""")
        private val THREAD = Regex("""t3-thread://v1/([A-Za-z0-9_:.-]+)""")
        private val LINK = Regex("""\[([^\]]+)\]\(t3-thread://v1/[^)]+\)""")
        private val BARE_LINK = Regex("""\(?t3-thread://v1/[A-Za-z0-9_:.-]+\)?""")

        /**
         * Splits [text] into prose and blocks. An unclosed block (a reply still streaming) stays out of the prose until
         * it closes, so it never flashes as code.
         */
        fun parse(text: String): OrchestratorReply {
            val prose = ArrayList<String>()
            var plan: List<PlanStep>? = null
            val memories = ArrayList<Memory>()
            var digest: String? = null
            val calls = ArrayList<OrchestratorCall>()
            var kind: String? = null
            var fence = ""
            val lines = ArrayList<String>()
            for (line in text.split('\n')) {
                val trimmed = line.trimStart(' ')
                if (kind != null) {
                    if (trimmed.startsWith(fence) && trimmed.trimStart('`', '~').isBlank()) {
                        val body = lines.joinToString("\n")
                        when (kind) {
                            "orchestrator-plan" -> plan = plan.orEmpty() + steps(body)
                            "orchestrator-memory" -> memory(body)?.let(memories::add)
                            "orchestrator-call" -> calls += OrchestratorCall.parse(body)
                            else -> digest = body.trim()
                        }
                        kind = null
                        lines.clear()
                    } else {
                        lines += line
                    }
                    continue
                }
                val f = trimmed.takeWhile { it == '`' || it == '~' }
                if (f.length >= 3) {
                    val info = trimmed.drop(f.length).trim().lowercase()
                    if (info in KINDS) {
                        kind = info
                        fence = f
                        continue
                    }
                }
                prose += line
            }
            val joined = prose.joinToString("\n").replace(Regex("\n\n\n+"), "\n\n")
            return OrchestratorReply(joined.trim(), plan, memories, digest, calls)
        }

        /** Numbered or bulleted lines become steps; `[x]`, `✓` or "(done)" marks one done. */
        fun steps(body: String): List<PlanStep> = body.split('\n').mapNotNull { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val mark = STEP_MARK.find(line) ?: return@mapNotNull null
            line = line.substring(mark.range.last + 1)
            var done = false
            CHECK.find(line)?.let { m ->
                done = m.value.contains('x', ignoreCase = true)
                line = line.substring(m.range.last + 1)
            }
            if (line.startsWith("✓") || line.startsWith("✅")) {
                done = true
                line = line.dropWhile { !it.isLetterOrDigit() }
            }
            if (line.contains("(done)", ignoreCase = true)) done = true
            PlanStep(line, done, THREAD.find(line)?.groupValues?.get(1))
        }

        /** `note: plan` (or a bare `plan`, or `# plan`) on the first line, then the note's full text. */
        fun memory(body: String): Memory? {
            val lines = body.split('\n').dropWhile { it.isBlank() }
            val first = lines.firstOrNull() ?: return null
            var name = first.trim().lowercase()
            for (prefix in listOf("note:", "name:", "#")) if (name.startsWith(prefix)) name = name.removePrefix(prefix).trim()
            name = name.replace(" ", "-").replace("crew_rules", "crew-rules")
            if (name !in Notebook.names) return null
            return Memory(name, lines.drop(1).joinToString("\n").trim())
        }
    }
}

/** One call from an `orchestrator-call` block: one JSON object per line. */
data class OrchestratorCall(
    /** Null when the line didn't parse or named no known tool; [raw] then says what came in. */
    val tool: Tool?,
    val raw: String = "",
    val machine: String? = null,
    val threadId: String? = null,
    val text: String? = null,
    val last: Int? = null,
    val projectId: String? = null,
    val title: String? = null,
    /** A Models row label. */
    val model: String? = null,
    /** A base ref; set, the thread gets its own worktree from it. */
    val worktree: String? = null,
    val confirmed: Boolean = false,
) {
    enum class Tool { LIST, READ, SEND, LAUNCH, ARCHIVE }

    companion object {
        /** The calls in a block's body: a JSON object per line, or one object or array spread over several lines. */
        fun parse(body: String): List<OrchestratorCall> {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return emptyList()
            when (val whole = json(trimmed)) {
                is JsonArray -> return whole.map { call(it, trimmed) }
                is JsonObject -> return listOf(call(whole, trimmed))
                else -> Unit
            }
            return trimmed.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                json(line)?.let { call(it, line) } ?: OrchestratorCall(null, line)
            }
        }

        private fun json(s: String): JsonElement? = runCatching { WireJson.parseToJsonElement(s) }.getOrNull()

        private fun call(v: JsonElement, raw: String): OrchestratorCall {
            val o = v as? JsonObject ?: return OrchestratorCall(null, raw)
            fun s(k: String): String? {
                val p = o[k] as? JsonPrimitive ?: return null
                if (p.isString) return p.content.takeIf { it.isNotBlank() }
                return p.doubleOrNull?.toLong()?.toString()
            }
            val lastP = o["last"] as? JsonPrimitive
            val last = lastP?.let { if (it.isString) it.content.toIntOrNull() else it.doubleOrNull?.toInt() }
            val confirmed = (o["confirmed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
            val tool = s("tool")?.lowercase()?.replace("thread_", "")?.let { name -> Tool.entries.firstOrNull { it.name.lowercase() == name } }
            return OrchestratorCall(tool, raw, s("machine"), s("threadId"), s("text"), last, s("projectId"), s("title"), s("model"), s("worktree"), confirmed)
        }
    }
}
