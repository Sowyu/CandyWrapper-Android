package org.anikodev.candywrapper.core.orchestrator

import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadSummary

/**
 * The fields of a shell thread (`OrchestrationV2ThreadShell`) the relay, Overview and Needs you read, which the
 * sidebar's [ThreadSummary] drops. Kept only while the Orchestrator is on. Times are epoch ms, 0 when absent.
 */
data class OrchThread(
    val id: String,
    val envId: String,
    val projectId: String,
    val title: String,
    /** The thread's `status`, as the relay reads it (the Mac's `ThreadShell.status`). */
    val status: String,
    val pill: StatusPill,
    val parentThreadId: String? = null,
    val subagent: Boolean = false,
    val latestUserAuthoredMessageAt: Long = 0,
    val latestUserMessageAt: Long = 0,
    val pendingRequestId: String? = null,
    val pendingKind: String? = null,
    val pendingCreatedAt: Long = 0,
    val latestRunId: String? = null,
    val latestRunCompletedAt: Long = 0,
    val lastError: String? = null,
    val lastLine: String? = null,
    val updatedAt: Long = 0,
    val unread: Boolean = false,
    val settled: Boolean = false,
    val model: ModelSelection? = null,
) {
    val key: String get() = OrchestratorConfig.key(envId, id)
    val running: Boolean get() = status in RUNNING

    companion object {
        /** The Mac's `OrchestratorEvent.running`. */
        val RUNNING = setOf("preparing", "queued", "starting", "running", "waiting")

        /** Null for archived or deleted threads. */
        fun from(o: JsonObject, envId: String): OrchThread? {
            val id = o.str("id") ?: return null
            val projectId = o.str("projectId") ?: return null
            if (o.str("archivedAt") != null || o.str("deletedAt") != null) return null
            val lineage = o.obj("lineage")
            val pending = o.obj("pendingRuntimeRequest")
            val summary = ThreadSummary.fromShell(o, envId = envId)
            return OrchThread(
                id = id,
                envId = envId,
                projectId = projectId,
                title = o.str("title").orEmpty().ifBlank { "Untitled thread" },
                status = o.str("status") ?: "idle",
                pill = ThreadSummary.pill(o),
                parentThreadId = lineage?.str("parentThreadId"),
                subagent = lineage?.str("relationshipToParent") == "subagent",
                latestUserAuthoredMessageAt = time(o, "latestUserAuthoredMessageAt"),
                latestUserMessageAt = time(o, "latestUserMessageAt"),
                pendingRequestId = pending?.str("id"),
                pendingKind = pending?.str("kind"),
                pendingCreatedAt = pending?.let { time(it, "createdAt") } ?: 0,
                latestRunId = o.str("latestRunId"),
                latestRunCompletedAt = time(o, "latestRunCompletedAt"),
                lastError = o.str("lastError"),
                lastLine = o.obj("latestVisibleMessage")?.str("text"),
                updatedAt = time(o, "updatedAt"),
                unread = summary?.unread == true,
                settled = o.str("settledAt") != null || o.str("settledOverride") == "settled",
                model = ModelSelection.from(o.obj("modelSelection")),
            )
        }

        private fun time(o: JsonObject, key: String) = ThreadSummary.time(o.str(key))
    }
}

/** A machine the phone connects to, as the brief and the relay name it. */
data class MachineRef(val id: String, val label: String, val isHome: Boolean, val connected: Boolean = true) {
    companion object {
        /** The machine `name` means: its id, else its label ignoring case. An unknown or ambiguous label is null. */
        fun resolve(name: String, machines: List<MachineRef>): MachineRef? {
            machines.firstOrNull { it.id == name }?.let { return it }
            val matches = machines.filter { it.label.equals(name, ignoreCase = true) }
            return matches.singleOrNull()
        }

        fun briefList(machines: List<MachineRef>): String {
            if (machines.isEmpty()) return "- (none)"
            return machines.joinToString("\n") { m ->
                "- ${m.label} (machine ${m.id})" + (if (m.isHome) ": you run here" else "") + (if (m.connected) "" else ", offline right now")
            }
        }
    }
}
