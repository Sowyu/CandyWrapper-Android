package org.anikodev.candywrapper.core.orchestrator

import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.Commands
import org.anikodev.candywrapper.core.sync.Delivery

/** One message of `orchestration.getThreadProjection` (contracts `OrchestrationV2ConversationMessage`). */
data class ProjectionMessage(
    val id: String,
    val role: String,
    val text: String,
    val streaming: Boolean,
    val createdAt: Long,
    val runId: String?,
    val workflowScript: Boolean = false,
) {
    companion object {
        fun list(projection: JsonObject?): List<ProjectionMessage> = projection?.arr("messages").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ProjectionMessage(
                id = o.str("id") ?: return@mapNotNull null,
                role = o.str("role") ?: return@mapNotNull null,
                text = o.str("text").orEmpty(),
                streaming = o.bool("streaming") == true,
                createdAt = ThreadSummary.time(o.str("createdAt")),
                runId = o.str("runId"),
                workflowScript = o.bool("workflowScript") == true,
            )
        }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }
}

/** What the relay needs from the app. Every call goes to [request] with the machine it names. */
interface RelayHost {
    fun machines(): List<MachineRef>
    /** A connected machine's projects and active threads; null while it has no live shell. */
    fun projects(machine: String): List<ProjectSummary>?
    fun threads(machine: String): List<OrchThread>?
    suspend fun request(machine: String, method: String, payload: JsonObject): JsonElement?
    fun config(): OrchestratorConfig
    fun update(change: (OrchestratorConfig) -> OrchestratorConfig)
    /** The Models rows the machine's catalog offers. */
    fun rows(machine: String): List<OrchestratorModelRow>
    fun runtimeMode(machine: String): String
    fun serverResolvesDelivery(machine: String): Boolean
    /** The Orchestrator's own thread as an `env:thread` key. */
    fun orchestratorKey(): String?
    /** Hands off threads the latest brief in the thread names, whichever device wrote it. */
    fun briefHandsOff(): Set<String> = emptySet()
    /** Workers the thread's own `Result:` lines say a relay launched, whichever device ran it. */
    fun resultWorkers(): Set<String> = emptySet()
    fun nowMs(): Long = System.currentTimeMillis()
    fun newId(): String = UUID.randomUUID().toString()
}

/** Runs a turn's calls and writes the "Result:" message (CandyWrapper OrchestratorRelay.swift). */
class OrchestratorRelay(private val host: RelayHost) {
    suspend fun run(calls: List<OrchestratorCall>): String? {
        if (calls.isEmpty()) return null
        val parts = ArrayList<String>()
        for ((i, call) in calls.withIndex()) {
            if (i >= MAX_CALLS) {
                parts += "${RESULT_PREFIX}skipped ${calls.size - MAX_CALLS} more calls. At most $MAX_CALLS run in one turn."
                break
            }
            parts += run(call)
        }
        return parts.joinToString("\n\n")
    }

    suspend fun run(call: OrchestratorCall): String {
        val named = host.machines()
        // Two machines can share a label, like two servers on one Mac: then the text names the id too.
        val machines = named.map { m ->
            if (named.any { it.id != m.id && it.label.equals(m.label, ignoreCase = true) }) m.copy(label = "${m.label} (machine ${m.id})") else m
        }
        val tool = call.tool ?: return refusal("couldn't read the call `${call.raw.take(200)}`. Write one JSON object per line with a \"tool\" of list, read, send, launch or archive.")
        if (tool == OrchestratorCall.Tool.LIST) {
            val name = call.machine ?: return list(named, call.title)
            val m = MachineRef.resolve(name, named) ?: return unknownMachine(name, named)
            return list(listOf(m), call.title)
        }
        val toolName = tool.name.lowercase()
        val name = call.machine ?: run {
            val hint = call.threadId?.let { id -> named.firstOrNull { m -> host.threads(m.id)?.any { it.id == id } == true } }
            return refusal("$toolName needs a \"machine\"." + (hint?.let { " Thread ${call.threadId} is on ${it.label} (machine ${it.id})." } ?: ""))
        }
        val target = MachineRef.resolve(name, named) ?: return unknownMachine(name, named)
        val machine = machines.first { it.id == target.id }
        val threads = host.threads(machine.id)
        if (!machine.connected || threads == null) return refusal("${machine.label} isn't connected right now.")
        if (tool == OrchestratorCall.Tool.LAUNCH) return launch(call, machine)
        val id = call.threadId ?: return refusal("$toolName needs a \"threadId\".")
        val thread = threads.firstOrNull { it.id == id } ?: return refusal("there is no thread $id on ${machine.label}. Call list to see its threads.")
        return when (tool) {
            OrchestratorCall.Tool.READ -> read(thread, machine, call.last)
            OrchestratorCall.Tool.SEND -> send(call, thread, machine)
            OrchestratorCall.Tool.ARCHIVE -> archive(thread, machine)
            else -> ""
        }
    }

    // Tools

    /** [title] keeps the threads whose title contains it, past the list's cap. */
    fun list(machines: List<MachineRef>, title: String? = null): String {
        val body = ArrayList<String>()
        var total = 0
        val config = host.config()
        val now = host.nowMs()
        val own = host.orchestratorKey()
        val workers = config.relayWorkers + host.resultWorkers()
        for (m in machines) {
            var head = "## ${m.label} (machine ${m.id})" + if (m.isHome) ", where you run" else ""
            val threads = host.threads(m.id)
            val projects = host.projects(m.id)
            if (!m.connected || threads == null || projects == null) {
                body += "$head: offline"
                continue
            }
            body += head
            val titles = projects.associate { it.id to it.title }
            body += "Projects: " + if (projects.isEmpty()) "none" else projects.joinToString(", ") { "${it.title} (projectId ${it.id})" }
            val shown = threads
                .filter { it.parentThreadId == null && !it.subagent && it.key != own }
                .filter { t -> title?.let { t.title.contains(it, ignoreCase = true) } ?: true }
                .sortedByDescending { it.updatedAt }
            total += shown.size
            for (t in shown.take(LIST_LIMIT)) {
                val permission = Permission.of(t, isHandsOff(config, t.key), config.askFirstMinutes, now, config.relayedAt[t.key])
                var line = "- “${t.title}” (threadId ${t.id}, project ${titles[t.projectId] ?: t.projectId}): ${state(t)}"
                if (t.key in workers) line += ", your worker"
                permission.note?.let { line += ". ${it.replaceFirstChar(Char::uppercaseChar)}" }
                body += line
            }
            if (shown.size > LIST_LIMIT) body += "- …and ${shown.size - LIST_LIMIT} older threads. Add \"title\" to find one."
            if (shown.isEmpty()) body += "- (no threads)"
        }
        val summary = "listed $total thread${if (total == 1) "" else "s"} on ${machines.size} machine${if (machines.size == 1) "" else "s"}"
        return RESULT_PREFIX + summary + "\n" + body.joinToString("\n")
    }

    suspend fun read(thread: OrchThread, machine: MachineRef, last: Int?): String {
        val count = (last ?: 5).coerceIn(1, 20)
        val messages = try {
            ProjectionMessage.list(host.request(machine.id, "orchestration.getThreadProjection", buildJsonObject { put("threadId", thread.id) }) as? JsonObject)
        } catch (e: Exception) {
            return refusal("couldn't read “${thread.title}” on ${machine.label}: ${describe(e)}")
        }.filter { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() && !it.workflowScript }.takeLast(count)
        val lines = mutableListOf("${RESULT_PREFIX}read “${thread.title}” on ${machine.label}", "Thread ${thread.id} on ${machine.label} (machine ${machine.id}): ${state(thread)}")
        for (m in messages) {
            var text = m.text.trim()
            if (text.length > MESSAGE_LIMIT) text = text.take(MESSAGE_LIMIT) + " …(cut)"
            lines += "[${m.role}] $text"
        }
        if (messages.isEmpty()) lines += "(no messages yet)"
        return lines.joinToString("\n")
    }

    suspend fun send(call: OrchestratorCall, thread: OrchThread, machine: MachineRef): String {
        val text = call.text ?: return refusal("send needs a \"text\".")
        val config = host.config()
        when (val p = Permission.of(thread, isHandsOff(config, thread.key), config.askFirstMinutes, host.nowMs(), config.relayedAt[thread.key])) {
            Permission.HandsOff -> return refusal("“${thread.title}” on ${machine.label} is Hands off. Report on it, never prompt it.")
            is Permission.AskFirst -> if (!call.confirmed) return refusal(
                "the user wrote in “${thread.title}” on ${machine.label} ${if (p.minutesAgo == 0) "just now" else "${p.minutesAgo} min ago"}. Ask the user first, then send again with \"confirmed\":true.",
            )
            Permission.Free -> Unit
        }
        val queue = thread.running
        val command = Commands.sendMessage(
            thread.id, text,
            activeRunId = if (queue) thread.latestRunId else null,
            delivery = if (queue) Delivery.QUEUE else Delivery.AUTO,
            serverResolvesDelivery = host.serverResolvesDelivery(machine.id),
            model = thread.model,
        )
        try {
            host.request(machine.id, "orchestration.dispatchCommand", command)
        } catch (e: Exception) {
            return refusal("couldn't send to “${thread.title}” on ${machine.label}: ${describe(e)}")
        }
        noteRelayed(thread.key)
        return RESULT_PREFIX + if (queue) "queued for “${thread.title}” on ${machine.label} behind its running turn" else "sent to “${thread.title}” on ${machine.label}"
    }

    suspend fun launch(call: OrchestratorCall, machine: MachineRef): String {
        val text = call.text ?: return refusal("launch needs a \"text\", the first prompt.")
        val projects = host.projects(machine.id).orEmpty()
        val project = call.projectId?.let { id -> projects.firstOrNull { it.id == id } } ?: run {
            val ids = projects.joinToString(", ") { "${it.title} (projectId ${it.id})" }
            return refusal("launch needs a \"projectId\" from ${machine.label}: ${ids.ifEmpty { "it has no projects" }}.")
        }
        val config = host.config()
        val running = runningWorkers()
        if (running >= config.crewLimit) return refusal("$running workers are running and the limit is ${config.crewLimit}. Wait for one to finish.")
        val rows = host.rows(machine.id)
        val selection: ModelSelection = if (call.model != null) {
            rows.firstOrNull { it.label.equals(call.model, ignoreCase = true) }?.selection
                ?: return refusal("no Models row “${call.model}” is available on ${machine.label}. Rows there: ${rows.joinToString(", ") { it.label }}.")
        } else {
            (rows.firstOrNull { it.isDefault } ?: rows.firstOrNull())?.selection ?: return refusal("no model from the Models list is available on ${machine.label}.")
        }
        val local = host.newId()
        val title = call.title ?: "Worker"
        val payload = buildJsonObject {
            put("commandId", host.newId())
            put("creationSource", Commands.SOURCE)
            put("threadId", local)
            put("projectId", project.id)
            put("title", title)
            // The Orchestrator names its workers; T3 would retitle them from the prompt.
            put("generateTitle", false)
            put("modelSelection", selection.toJson())
            put("runtimeMode", host.runtimeMode(machine.id))
            put("interactionMode", "default")
            put("workspaceStrategy", buildJsonObject {
                if (call.worktree != null) { put("type", "worktree"); put("baseRef", call.worktree) } else put("type", "root")
            })
            put("initialMessage", buildJsonObject {
                put("messageId", host.newId())
                put("text", text)
                put("attachments", buildJsonArray {})
            })
        }
        try {
            host.request(machine.id, "orchestration.launchThread", payload)
        } catch (e: Exception) {
            return refusal("couldn't launch “$title” on ${machine.label}: ${describe(e)}")
        }
        val key = OrchestratorConfig.key(machine.id, local)
        host.update { c -> c.copy(relayWorkers = (c.relayWorkers + key).let { if (it.size > 200) it.sorted().takeLast(200).toSet() else it }) }
        noteRelayed(key)
        return RESULT_PREFIX + "launched “$title” on ${machine.label} (machine ${machine.id}, threadId $local, project ${project.title})"
    }

    suspend fun archive(thread: OrchThread, machine: MachineRef): String {
        if (isHandsOff(host.config(), thread.key)) return refusal("“${thread.title}” on ${machine.label} is Hands off, so it stays.")
        if (thread.running) return refusal("“${thread.title}” on ${machine.label} is still running.")
        try {
            host.request(machine.id, "orchestration.dispatchCommand", Commands.archive(thread.id))
        } catch (e: Exception) {
            return refusal("couldn't archive “${thread.title}” on ${machine.label}: ${describe(e)}")
        }
        host.update { it.copy(relayWorkers = it.relayWorkers - thread.key) }
        return RESULT_PREFIX + "archived “${thread.title}” on ${machine.label}"
    }

    // Limits

    /** This phone's Hands off list plus the one the latest brief carries, so the Mac's marks hold here too. */
    private fun isHandsOff(config: OrchestratorConfig, key: String) = config.isHandsOff(key) || key in host.briefHandsOff()

    /** Running workers on every machine: threads a relay launched, plus the Orchestrator thread's own children. */
    // ponytail: top-level threads its own t3_thread_launch starts carry no parent, so they don't count; tag them if the cap slips
    fun runningWorkers(): Int {
        val config = host.config()
        val own = host.orchestratorKey()
        val workers = config.relayWorkers + host.resultWorkers()
        var count = 0
        for (m in host.machines()) for (t in host.threads(m.id).orEmpty()) {
            if (!t.running) continue
            val child = own != null && t.parentThreadId?.let { OrchestratorConfig.key(m.id, it) } == own
            if (child || t.key in workers) count++
        }
        return count
    }

    /**
     * The server records a relayed message as the user's (`createdBy: "user"`), so the relay notes its own send for
     * the ask-first rule. Entries older than a day go.
     */
    private fun noteRelayed(key: String) {
        val now = host.nowMs()
        host.update { c -> c.copy(relayedAt = (c.relayedAt + (key to now)).filterValues { now - it < 86_400_000 }) }
    }

    private fun refusal(why: String) = RESULT_PREFIX + "refused: " + why

    private fun unknownMachine(name: String, machines: List<MachineRef>) =
        refusal("no machine “$name”. Machines: " + machines.joinToString(", ") { "${it.label} (machine ${it.id})" } + ".")

    companion object {
        const val RESULT_PREFIX = "Result: "
        // ponytail: 10 calls a turn and 40 threads a machine in a list; raise them if the Orchestrator asks for more often
        const val MAX_CALLS = 10
        const val LIST_LIMIT = 40
        const val MESSAGE_LIMIT = 1500
        private val LAUNCHED = Regex("""^Result: launched “.*” on .* \(machine ([^,]+), threadId ([^,]+), project .*\)$""")

        fun isResultMessage(text: String) = text.startsWith(RESULT_PREFIX)

        /** The one-line summaries of a result message, for the chat. */
        fun summaries(text: String): List<String> = text.split('\n').filter { it.startsWith(RESULT_PREFIX) }.map { it.removePrefix(RESULT_PREFIX) }

        /** The `env:thread` keys a result message says were launched, so either device's workers count. */
        fun launchedIn(text: String): Set<String> = text.split('\n').mapNotNullTo(HashSet()) { line ->
            LAUNCHED.find(line)?.let { OrchestratorConfig.key(it.groupValues[1], it.groupValues[2]) }
        }

        fun state(t: OrchThread): String = when {
            t.pendingRequestId != null -> if (t.pendingKind == "user_input") "has a question for the user" else "waiting on an approval"
            t.running -> "working"
            t.status == "failed" -> "failed" + (t.lastError?.let { ": ${it.take(160)}" } ?: "")
            t.latestRunCompletedAt > 0 -> "finished"
            else -> "idle"
        }

        fun describe(e: Throwable): String = e.message ?: e::class.simpleName.orEmpty()
    }
}
