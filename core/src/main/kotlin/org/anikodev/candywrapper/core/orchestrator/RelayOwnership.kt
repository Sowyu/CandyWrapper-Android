package org.anikodev.candywrapper.core.orchestrator

import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ThreadSummary

/**
 * A "while closed" scheduled task as the relay reads it (contracts `ScheduledTask`). [updatedAt] is the server's ISO
 * string, compared exactly: every `scheduledTasks.upsert` and every real `setEnabled` writes a new one.
 */
data class CheckTask(
    val id: String,
    val enabled: Boolean,
    val updatedAt: String,
    val threadId: String?,
    val projectId: String,
    val title: String,
    val prompt: String,
) {
    val updatedAtMs: Long get() = ThreadSummary.time(updatedAt)

    /** Whether this is a CandyWrapper "while closed" check, from the Mac or this phone. */
    val isCheck: Boolean get() = prompt.trim() == OrchestratorBrief.scheduledCheckPrompt

    companion object {
        fun from(o: JsonObject): CheckTask? = CheckTask(
            id = o.str("id") ?: return null,
            enabled = o.bool("enabled") ?: return null,
            updatedAt = o.str("updatedAt") ?: return null,
            threadId = o.str("threadId"),
            projectId = o.str("projectId") ?: return null,
            title = o.str("title").orEmpty(),
            prompt = o.str("prompt").orEmpty(),
        )

        /** `scheduledTasks.list` and every `scheduledTasks.subscribe` value: `{tasks: [...]}`. */
        fun list(result: JsonObject?): List<CheckTask> = result?.arr("tasks").orEmpty().mapNotNull { (it as? JsonObject)?.let(::from) }

        /** A mutation's `{task}`. */
        fun fromResult(result: JsonObject?): CheckTask? = result?.obj("task")?.let(::from)

        /**
         * The check task of the Orchestrator thread [threadId]: the newest check naming it. The Mac makes one per
         * machine and names it "<name> check"; a task that lost its id on the Mac can leave an older one behind.
         */
        fun forThread(tasks: List<CheckTask>, threadId: String): CheckTask? =
            tasks.filter { it.isCheck && it.threadId == threadId }.maxByOrNull { it.updatedAtMs }
    }
}

/**
 * Who relays for one Orchestrator: this phone or another CandyWrapper. Only existing server state decides it, the
 * Orchestrator's "while closed" task on its machine (docs/orchestrator.md, "Two apps, one Orchestrator"):
 *
 * - The Mac turns its task off when it opens (an upsert, which always writes a new `updatedAt`) and back on when it
 *   quits. So a task that is on means no CandyWrapper is relaying.
 * - This phone takes over only from a task that is on, with `scheduledTasks.setEnabled(false)`, and remembers the
 *   `updatedAt` its own write produced. While the task is off with that exact `updatedAt`, nobody else wrote, so
 *   the phone still holds it. Any other `updatedAt` means another app wrote: the phone stops at once.
 * - Leaving the front, the phone turns the task back on only while it still holds it.
 */
object RelayOwnership {
    enum class Reason {
        /** The task list hasn't arrived yet. */
        WAITING,
        /** The app isn't in front. */
        BACKGROUND,
        /** The toggle is off or Redesign isn't the theme. */
        OFF,
        /** No "while closed" task names the thread, so nothing says whether the Mac is open. */
        NO_TASK,
        /** The task is off and this phone didn't turn it off: CandyWrapper on the Mac (or another phone) relays. */
        OTHER_APP,
    }

    sealed interface Action {
        data object None : Action
        /** `scheduledTasks.setEnabled(false)`, then remember the result's `updatedAt`. */
        data class Claim(val taskId: String) : Action
        /** `scheduledTasks.setEnabled(true)`, then forget the claim. */
        data class Release(val taskId: String) : Action
        /** A thread this phone started has no task yet: create one, off, and hold it. */
        data object Create : Action
    }

    /** [relaying] says whether this phone runs calls and sends events now; [forgetClaim] drops a stale claim. */
    data class Decision(val relaying: Boolean, val reason: Reason?, val action: Action, val forgetClaim: Boolean = false)

    fun holds(task: CheckTask?, claim: ScheduleClaim?): Boolean =
        task != null && claim != null && !task.enabled && claim.taskId == task.id && claim.updatedAt == task.updatedAt

    /**
     * @param tasksKnown the machine's task list arrived on this connection.
     * @param startedHere this phone started the thread, so the Mac never relays for it and the phone may create its task.
     * @param closedCheck the phone's "while closed" setting is on; it decides only for a task this phone created.
     */
    fun decide(active: Boolean, foreground: Boolean, tasksKnown: Boolean, task: CheckTask?, claim: ScheduleClaim?, startedHere: Boolean, closedCheck: Boolean): Decision {
        if (!tasksKnown) return Decision(false, Reason.WAITING, Action.None)
        val held = holds(task, claim)
        if (!active || !foreground) {
            val reason = if (!active) Reason.OFF else Reason.BACKGROUND
            if (!held) return Decision(false, reason, Action.None, forgetClaim = claim != null)
            // A task this phone made with the check off stays off, and stays held for the next return.
            if (startedHere && !closedCheck) return Decision(false, reason, Action.None)
            return Decision(false, reason, Action.Release(task!!.id))
        }
        if (held) return Decision(true, null, Action.None)
        if (task == null) {
            return if (startedHere) Decision(false, Reason.WAITING, Action.Create, forgetClaim = claim != null)
            else Decision(false, Reason.NO_TASK, Action.None, forgetClaim = claim != null)
        }
        if (task.enabled) return Decision(false, Reason.WAITING, Action.Claim(task.id), forgetClaim = claim != null)
        return Decision(false, Reason.OTHER_APP, Action.None, forgetClaim = claim != null)
    }

    /**
     * Another app took over at [takeoverAtMs] (its write's `updatedAt`). Events the phone saw from before then were
     * in that app's first state, so it won't send them: the phone does. Later ones are that app's to send.
     */
    fun keepAtHandoff(event: OrchestratorEvent, takeoverAtMs: Long): Boolean = event.atMs in 1 until takeoverAtMs

    /**
     * Call-level dedupe: a `Result:` message created at or after the turn's last reply means another app already ran
     * the turn's calls.
     */
    fun alreadyAnswered(messages: List<ProjectionMessage>, replies: List<ProjectionMessage>): Boolean {
        val last = replies.maxOfOrNull { it.createdAt } ?: return false
        return messages.any { it.role == "user" && OrchestratorRelay.isResultMessage(it.text) && it.createdAt >= last }
    }

    /** The finished replies of [runId] (every finished reply when null) not handled yet, oldest first. */
    fun replies(messages: List<ProjectionMessage>, runId: String?, handled: Set<String>): List<ProjectionMessage> =
        messages.filter { it.role == "assistant" && !it.streaming && (runId == null || it.runId == runId) && it.id !in handled }
}

/**
 * Notices the Orchestrator's turn ending (CandyWrapper OrchestratorRemote.orchestratorChanged): a run that ends while
 * the phone watches it, on one connection, has its calls run. A turn that ended while the phone was away never does.
 */
class TurnWatch {
    private var generation = -1L
    private var running = false

    fun reset() { generation = -1 }

    /** True when this state ends a turn seen running in the same [generation]. */
    fun ended(generation: Long, running: Boolean): Boolean {
        val ended = this.generation == generation && this.running && !running
        this.generation = generation
        this.running = running
        return ended
    }
}
