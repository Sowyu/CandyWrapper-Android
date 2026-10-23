package org.anikodev.candywrapper.core.orchestrator

import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.StatusPill

/** An approval or a question waiting in some thread. Approvals get Allow and Deny; questions open their thread. */
data class OrchestratorNeed(val envId: String, val threadId: String, val threadTitle: String, val requestId: String, val label: String, val isApproval: Boolean) {
    val key: String get() = "$envId:$threadId:$requestId"
}

/** One thread on the Overview, built from shell pushes only, so it costs no tokens. */
data class CrewCard(
    val envId: String,
    val threadId: String,
    val title: String,
    val project: String,
    val state: String,
    val pill: StatusPill,
    val lastLine: String?,
    /** "Hands off", or "You wrote 3 min ago, so it asks first". */
    val note: String?,
    val group: Group,
) {
    /** Work under way, work done, or work that stopped short (failed, a usage limit, never run). */
    enum class Group { RUNNING, FINISHED, STOPPED }

    val key: String get() = "$envId:$threadId"
}

object OrchestratorOverview {
    /** ComposerPendingApprovalPanel.tsx:16-25. */
    fun approvalLabel(kind: String?): String = when (kind) {
        "mcp_elicitation" -> "App access approval"
        "command" -> "Command approval"
        "file_read" -> "File read approval"
        "permission" -> "App permission approval"
        else -> "File change approval"
    }

    /** Every approval and question on [threads], newest first. */
    fun needs(threads: Collection<OrchThread>): List<OrchestratorNeed> = threads
        .filter { it.pendingRequestId != null && it.pendingKind != "auth_refresh" }
        .sortedByDescending { it.pendingCreatedAt }
        .map { t ->
            val question = t.pendingKind == "user_input"
            OrchestratorNeed(t.envId, t.id, t.title, t.pendingRequestId!!, if (question) "Has a question for you" else approvalLabel(t.pendingKind), !question)
        }

    fun group(pill: StatusPill, completedTurn: Boolean): CrewCard.Group = when (pill) {
        StatusPill.WORKING, StatusPill.WAITING, StatusPill.APPROVAL, StatusPill.INPUT -> CrewCard.Group.RUNNING
        StatusPill.FAILED, StatusPill.LIMITED -> CrewCard.Group.STOPPED
        StatusPill.READY, StatusPill.NONE -> if (completedTurn) CrewCard.Group.FINISHED else CrewCard.Group.STOPPED
    }

    /**
     * The Overview's cards: every top-level thread but the Orchestrator and settled ones, newest first, every running
     * one, the 20 most recent finished and 10 stopped.
     */
    // ponytail: 20 finished and 10 stopped; page the Overview if a machine keeps more
    fun crew(threads: Collection<OrchThread>, projects: Collection<ProjectSummary>, config: OrchestratorConfig, orchestratorKey: String?, nowMs: Long, handsOff: (String) -> Boolean = config::isHandsOff): List<CrewCard> {
        val titles = projects.associate { "${it.envId}:${it.id}" to it.title }
        val cards = threads
            .filter { it.parentThreadId == null && !it.subagent && it.key != orchestratorKey && !it.settled }
            .sortedByDescending { it.updatedAt }
            .map { t ->
                val state = when (t.pill) {
                    StatusPill.APPROVAL, StatusPill.INPUT -> "Needs you"
                    StatusPill.WORKING, StatusPill.WAITING -> "Working"
                    StatusPill.FAILED -> "Failed"
                    StatusPill.LIMITED -> "Usage limit"
                    else -> if (t.unread) "Unread" else if (t.latestRunCompletedAt > 0) "Done" else "Idle"
                }
                val note = when (val p = Permission.of(t, handsOff(t.key), config.askFirstMinutes, nowMs)) {
                    Permission.HandsOff -> "Hands off"
                    is Permission.AskFirst -> "You wrote ${if (p.minutesAgo == 0) "just now" else "${p.minutesAgo} min ago"}, so it asks first"
                    Permission.Free -> null
                }
                CrewCard(t.envId, t.id, t.title, titles["${t.envId}:${t.projectId}"].orEmpty(), state, t.pill,
                    (t.lastError ?: t.lastLine)?.take(160), note, group(t.pill, t.latestRunCompletedAt > 0))
            }
        return cards.filter { it.group == CrewCard.Group.RUNNING } +
            cards.filter { it.group == CrewCard.Group.FINISHED }.take(20) +
            cards.filter { it.group == CrewCard.Group.STOPPED }.take(10)
    }
}
