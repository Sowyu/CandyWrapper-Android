package org.anikodev.candywrapper.core.orchestrator

/** Whether the Orchestrator may prompt a thread. */
sealed interface Permission {
    data object Free : Permission
    /** You wrote the thread's last prompt this many whole minutes ago, inside the ask-first window. */
    data class AskFirst(val minutesAgo: Int) : Permission
    data object HandsOff : Permission

    /** The note an event line carries, if any. */
    val note: String?
        get() = when (this) {
            Free -> null
            is AskFirst -> if (minutesAgo == 0) "the user wrote there just now, so ask first" else "the user wrote there $minutesAgo min ago, so ask first"
            HandsOff -> "Hands off: report only, never prompt it"
        }

    companion object {
        /**
         * Hands off wins; then "ask first" while your own message is the thread's last prompt and is younger than the
         * window. A prompt from another thread after yours (`latestUserMessageAt` past `latestUserAuthoredMessageAt`)
         * means you already said go. So does a relayed prompt at or after yours: the server records those as yours
         * too, since every client command is `createdBy: "user"`.
         */
        fun of(t: OrchThread, handsOff: Boolean, windowMinutes: Int, nowMs: Long, relayedAtMs: Long? = null): Permission {
            if (handsOff) return HandsOff
            val authored = t.latestUserAuthoredMessageAt
            if (windowMinutes <= 0 || authored == 0L) return Free
            if (t.latestUserMessageAt > authored) return Free
            // ponytail: 5 s of clock skew between the phone and the thread's machine; compare message ids if a machine drifts more
            if (relayedAtMs != null && authored <= relayedAtMs + 5_000) return Free
            val age = nowMs - authored
            if (age >= windowMinutes * 60_000L) return Free
            return AskFirst(maxOf(0, (age / 60_000).toInt()))
        }
    }
}

/** Something a thread did that the Orchestrator should hear about. Equal when thread and kind match. */
class OrchestratorEvent(
    val envId: String,
    val threadId: String,
    val title: String,
    val kind: Kind,
    val permission: Permission,
    /** The machine's label when the thread isn't on the Orchestrator's own machine. */
    val machine: String? = null,
    /** The thread's `updatedAt` on its server, which decides who sends it when another app takes over. */
    val atMs: Long = 0,
) {
    enum class Kind { FINISHED, FAILED, NEEDS_YOU }

    val key: String get() = OrchestratorConfig.key(envId, threadId)

    override fun equals(other: Any?) = other is OrchestratorEvent && other.key == key && other.kind == kind
    override fun hashCode() = key.hashCode() * 31 + kind.hashCode()

    /** "Event: “Release notes” finished a turn (t3-thread://v1/abc). The user wrote there 3 min ago, so ask first." */
    val line: String
        get() {
            val what = when (kind) {
                Kind.FINISHED -> "finished a turn"
                Kind.FAILED -> "failed"
                Kind.NEEDS_YOU -> "is waiting on an approval or a question"
            }
            val note = permission.note?.let { " ${it.replaceFirstChar(Char::uppercaseChar)}." } ?: ""
            val on = machine?.let { " on $it (machine $envId)" } ?: ""
            return "Event: “$title”$on $what (t3-thread://v1/$threadId).$note"
        }

    companion object {
        /**
         * What changed between two shell states of a machine's threads. Skips the Orchestrator, threads not in the old
         * state, and threads with a parent: T3 wakes a parent when its own child finishes (orchestratorMcp.ts:222).
         */
        fun diff(
            old: Map<String, OrchThread>,
            new: Collection<OrchThread>,
            orchestratorKey: String?,
            handsOff: (String) -> Boolean,
            windowMinutes: Int,
            nowMs: Long,
            relayedAt: (String) -> Long? = { null },
            machine: String? = null,
        ): List<OrchestratorEvent> {
            val out = ArrayList<OrchestratorEvent>()
            for (t in new) {
                if (t.key == orchestratorKey || t.parentThreadId != null || t.subagent) continue
                val before = old[t.key] ?: continue
                val kinds = ArrayList<Kind>(2)
                if (t.status == "failed" && before.status != "failed") kinds += Kind.FAILED
                else if (before.running && !t.running && (t.status == "completed" || t.status == "idle")) kinds += Kind.FINISHED
                if (t.pendingRequestId != null && t.pendingRequestId != before.pendingRequestId) kinds += Kind.NEEDS_YOU
                if (kinds.isEmpty()) continue
                val permission = Permission.of(t, handsOff(t.key), windowMinutes, nowMs, relayedAt(t.key))
                for (k in kinds) out += OrchestratorEvent(t.envId, t.id, t.title, k, permission, machine, t.updatedAt)
            }
            return out
        }

        /** Whether a message is an event message the app sent. */
        fun isEventMessage(text: String): Boolean {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            return lines.isNotEmpty() && lines.all { it.startsWith("Event: ") }
        }
    }
}

/**
 * Events that land within one window go out as one message: a busy minute costs one turn. The first event opens the
 * window; everything until it closes joins that message. Nothing ticks between events: the caller arms one
 * one-shot timer for [deadline].
 */
class OrchestratorEventBatch(val windowMs: Long = 30_000) {
    private val _pending = ArrayList<OrchestratorEvent>()
    val pending: List<OrchestratorEvent> get() = _pending
    var deadline: Long? = null
        private set

    /** Adds events and returns the deadline to arm when this opened the window, else null (a timer is armed already). */
    fun add(events: List<OrchestratorEvent>, nowMs: Long): Long? {
        if (events.isEmpty()) return null
        for (e in events) {
            // A newer state of the same thread and kind replaces the older line.
            val i = _pending.indexOf(e)
            if (i >= 0) _pending[i] = e else _pending += e
        }
        if (deadline != null) return null
        return (nowMs + windowMs).also { deadline = it }
    }

    /**
     * The message to send once the window closed, else null. Empties the batch. [keep] drops events that went stale
     * inside the window, like a turn end of a thread the Orchestrator archived meanwhile. [force] sends before the
     * deadline, as when the app leaves the front.
     */
    fun flush(nowMs: Long, force: Boolean = false, keep: (OrchestratorEvent) -> Boolean = { true }): String? {
        val d = deadline ?: return null
        if (!force && nowMs < d) return null
        deadline = null
        val lines = _pending.filter(keep).map { it.line }
        _pending.clear()
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    fun reset() {
        _pending.clear()
        deadline = null
    }
}
