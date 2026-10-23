package org.anikodev.candywrapper.core.model

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/** The sidebar pill (protocol.md 7.9). [NONE] is a thread that never ran. */
enum class StatusPill { WORKING, WAITING, APPROVAL, INPUT, FAILED, LIMITED, READY, NONE }

/**
 * One thread-list row, reduced from `OrchestrationV2ThreadShell` to what the list draws and sorts
 * by. Times are epoch milliseconds, 0 when absent. It is also the cached row, so a cold start
 * draws the list without decoding JSON.
 */
data class ThreadSummary(
    val id: String,
    val projectId: String,
    val title: String,
    val pill: StatusPill,
    val unread: Boolean,
    /** 0 pinned, 1 active, 2 snoozed, 3 settled. */
    val section: Int,
    /** `pinOrderKey` or `activeOrderKey` for the section, fractional-index strings compared with `<`. */
    val orderKey: String?,
    /** What the section sorts by when there is no key (see [ThreadOrder]). */
    val sortTime: Long,
    /** The row's time label: `latestUserMessageAt ?: updatedAt`. */
    val activityAt: Long,
    /** `activityRunStartedAt` while working, for the "working 3m" label. */
    val workingSince: Long,
    /** `updatedAt`, the watermark `thread.visit` sends (protocol.md 7.9 Unread). */
    val updatedAt: Long = 0,
    /** `lastVisitedAt`, 0 when never visited or the server predates visit tracking. */
    val visitedAt: Long = 0,
) {
    companion object {
        /**
         * Decodes a shell thread. Null for rows the lists never show: subagent threads, archived or
         * deleted ones, and anything without an id.
         */
        fun fromShell(o: JsonObject, nowMs: Long = System.currentTimeMillis()): ThreadSummary? {
            val id = o.str("id") ?: return null
            val projectId = o.str("projectId") ?: return null
            if (o.obj("lineage")?.str("relationshipToParent") == "subagent") return null
            if (o.str("archivedAt") != null || o.str("deletedAt") != null) return null
            val pill = pill(o)
            val createdAt = time(o.str("createdAt"))
            val updatedAt = time(o.str("updatedAt"))
            val latestUser = time(o.str("latestUserMessageAt"))
            val pinned = o.str("pinnedAt") != null
            val settled = o.str("settledOverride") == "settled"
            val snoozedUntil = time(o.str("snoozedUntil"))
            val raisedHand = pill == StatusPill.APPROVAL || pill == StatusPill.INPUT || pill == StatusPill.FAILED
            val section = when {
                snoozedUntil > nowMs && !raisedHand -> 2
                settled -> 3
                pinned -> 0
                else -> 1
            }
            val sortTime = when (section) {
                0 -> createdAt
                1 -> maxOf(createdAt, time(o.str("unsettledAt")))
                2 -> snoozedUntil
                else -> time(o.str("settledAt")).takeIf { it > 0 }
                    ?: maxOf(latestUser, time(o.str("latestRunCompletedAt")), time(o.str("latestRunStartedAt"))).takeIf { it > 0 }
                    ?: updatedAt
            }
            return ThreadSummary(
                id = id,
                projectId = projectId,
                title = o.str("title").orEmpty().ifBlank { "Untitled thread" },
                pill = pill,
                unread = unread(o, pill, updatedAt),
                section = section,
                orderKey = when (section) { 0 -> o.str("pinOrderKey"); 1 -> o.str("activeOrderKey"); else -> null },
                sortTime = sortTime,
                activityAt = if (latestUser > 0) latestUser else updatedAt,
                workingSince = if (pill == StatusPill.WORKING) time(o.str("activityRunStartedAt")) else 0,
                updatedAt = updatedAt,
                visitedAt = time(o.str("lastVisitedAt")),
            )
        }

        /** `resolveSidebarThreadStatus` (apps/web/src/components/Sidebar.logic.ts:983-1013). */
        fun pill(o: JsonObject): StatusPill {
            val pending = o.obj("pendingRuntimeRequest")?.str("kind")
            if (pending == "user_input") return StatusPill.INPUT
            if (pending != null && pending != "auth_refresh") return StatusPill.APPROVAL
            return when (runtimeStatus(o)) {
                null -> StatusPill.NONE
                "preparing", "queued", "starting", "running", "waiting" -> StatusPill.WORKING
                "idle" -> StatusPill.WAITING
                "failed" -> if (o.str("lastErrorClass") == "usage_limit") StatusPill.LIMITED else StatusPill.FAILED
                else -> StatusPill.READY
            }
        }

        /** client-runtime `state/models.ts:174-199`: subagents, monitors and unknown kinds hold completion; `command` does not. */
        private fun runtimeStatus(o: JsonObject): String? {
            val holds = o.arr("pendingBackgroundTasks").orEmpty().any { (it as? JsonObject)?.str("kind") != "command" }
            if (o.str("latestRunId") == null && o.str("activeProviderThreadId") == null && !holds) return null
            val status = o.str("status") ?: "idle"
            return if (holds && status != "failed") "idle" else o.str("activityRunStatus") ?: status
        }

        /** Finished after the last visit. A thread never visited is not unread; an unreadable visit time is. */
        private fun unread(o: JsonObject, pill: StatusPill, updatedAt: Long): Boolean {
            val visited = o.str("lastVisitedAt") ?: return false
            val visitedAt = time(visited).takeIf { it > 0 } ?: return true
            val completed = time(o.str("latestRunCompletedAt")).takeIf { it > 0 } ?: if (pill == StatusPill.READY) updatedAt else 0
            return completed > visitedAt
        }

        fun time(iso: String?): Long = if (iso == null) 0 else runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
    }
}

/** Section order (protocol.md 7.9, client-runtime `threadSort.ts`). */
object ThreadOrder : Comparator<ThreadSummary> {
    override fun compare(a: ThreadSummary, b: ThreadSummary): Int {
        if (a.section != b.section) return a.section - b.section
        return when (a.section) {
            0 -> keyedFirst(a, b) { x, y -> y.sortTime.compareTo(x.sortTime) }
            1 -> keylessFirst(a, b)
            2 -> a.sortTime.compareTo(b.sortTime)
            else -> b.sortTime.compareTo(a.sortTime)
        }.let { if (it != 0) it else a.id.compareTo(b.id) }
    }

    private fun keyedFirst(a: ThreadSummary, b: ThreadSummary, keyless: (ThreadSummary, ThreadSummary) -> Int): Int = when {
        a.orderKey != null && b.orderKey != null -> a.orderKey.compareTo(b.orderKey)
        a.orderKey != null -> -1
        b.orderKey != null -> 1
        else -> keyless(a, b)
    }

    private fun keylessFirst(a: ThreadSummary, b: ThreadSummary): Int = when {
        a.orderKey == null && b.orderKey == null -> b.sortTime.compareTo(a.sortTime)
        a.orderKey == null -> -1
        b.orderKey == null -> 1
        else -> a.orderKey.compareTo(b.orderKey)
    }
}

/** A project and its threads, in list order. */
data class ProjectGroup(val project: ProjectSummary?, val threads: List<ThreadSummary>)

data class ThreadListModel(val groups: List<ProjectGroup> = emptyList(), val threadCount: Int = 0) {
    companion object {
        /** Projects with recent activity first; threads in [ThreadOrder]. Threads of unknown projects go last. */
        fun build(projects: Map<String, ProjectSummary>, threads: Collection<ThreadSummary>): ThreadListModel {
            val byProject = threads.groupBy { it.projectId }
            val groups = byProject.map { (projectId, list) -> ProjectGroup(projects[projectId], list.sortedWith(ThreadOrder)) }
                .sortedWith(compareBy<ProjectGroup> { it.project == null }.thenByDescending { g -> g.threads.maxOf { it.activityAt } })
            return ThreadListModel(groups, threads.size)
        }
    }
}
