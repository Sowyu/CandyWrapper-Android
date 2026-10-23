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
    /** 0 pinned, 1 active, 3 settled. A snooze is on top of this; see [sectionAt]. */
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
    /** The computer the thread lives on. Thread ids are unique only within one. */
    val envId: String = "",
    /** `snoozedUntil`, 0 when not snoozed or when the thread raised its hand while snoozed. */
    val snoozedUntil: Long = 0,
    /** When a snoozed thread raised its hand (client-runtime `threadWokeAt`), else 0. */
    val wokeAt: Long = 0,
    /** The thread's git branch, the card's third line. */
    val branch: String? = null,
    /** `providerInstanceId`, for the provider glyph. */
    val providerInstanceId: String? = null,
) {
    /** `env:thread`, unique across computers. */
    val key: String get() = "$envId:$id"

    /** The section at [nowMs]: 2 while the snooze runs (client-runtime `effectiveSnoozed`). */
    fun sectionAt(nowMs: Long): Int = if (snoozedUntil > nowMs) 2 else section

    /** `threadWokeAt`: a hand raised during the snooze, or the wake time once it has passed. 0 when it has not woken. */
    fun wokeAtAt(nowMs: Long): Long = when {
        wokeAt > 0 -> wokeAt
        snoozedUntil in 1..nowMs -> snoozedUntil
        else -> 0
    }

    /** Woke after the last visit and not settled since (SidebarThreadRow `isWoke`). */
    fun isWoke(nowMs: Long): Boolean {
        val woke = wokeAtAt(nowMs)
        return woke > 0 && (visitedAt == 0L || visitedAt < woke) && section != 3
    }

    companion object {
        /**
         * Decodes a shell thread. Null for rows the lists never show: subagent threads, archived or
         * deleted ones, and anything without an id. The archived list passes [archived] to keep its rows.
         */
        fun fromShell(o: JsonObject, nowMs: Long = System.currentTimeMillis(), archived: Boolean = false, envId: String = ""): ThreadSummary? {
            val id = o.str("id") ?: return null
            val projectId = o.str("projectId") ?: return null
            if (o.obj("lineage")?.str("relationshipToParent") == "subagent") return null
            if ((o.str("archivedAt") != null && !archived) || o.str("deletedAt") != null) return null
            val pill = pill(o)
            val createdAt = time(o.str("createdAt"))
            val updatedAt = time(o.str("updatedAt"))
            val latestUser = time(o.str("latestUserMessageAt"))
            val pinned = o.str("pinnedAt") != null
            val settled = o.str("settledOverride") == "settled"
            val section = when {
                settled -> 3
                pinned -> 0
                else -> 1
            }
            val sortTime = when (section) {
                0 -> createdAt
                1 -> maxOf(createdAt, time(o.str("unsettledAt")))
                else -> time(o.str("settledAt")).takeIf { it > 0 }
                    ?: maxOf(latestUser, time(o.str("latestRunRequestedAt")), time(o.str("latestRunStartedAt")), time(o.str("latestRunCompletedAt"))).takeIf { it > 0 }
                    ?: updatedAt
            }
            val snoozedUntil = time(o.str("snoozedUntil"))
            val wokeEarly = if (snoozedUntil > 0) raisedHandAt(o, pill) else 0
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
                envId = envId,
                snoozedUntil = if (wokeEarly != 0L) 0 else snoozedUntil,
                wokeAt = wokeEarly,
                branch = o.str("branch"),
                providerInstanceId = o.str("providerInstanceId") ?: o.obj("modelSelection")?.str("instanceId"),
            )
        }

        /**
         * client-runtime `threadRaisedHandWhileSnoozed` and the early branch of `threadWokeAt`: a
         * request for the user, or a failure after the snooze began, ends it early. Returns when, or
         * 0 when the hand is down. A raised hand with no usable time reports 1, still "woken".
         */
        private fun raisedHandAt(o: JsonObject, pill: StatusPill): Long {
            val snoozedAt = time(o.str("snoozedAt"))
            val failedAt = if (pill == StatusPill.FAILED || pill == StatusPill.LIMITED) {
                time(o.str("latestRunCompletedAt")).takeIf { it > 0 } ?: updatedAtOf(o)
            } else 0
            val failedSince = failedAt > 0 && (snoozedAt == 0L || failedAt > snoozedAt)
            if (failedSince) return failedAt
            if (o.obj("pendingRuntimeRequest") == null) return 0
            return updatedAtOf(o).takeIf { it > 0 } ?: snoozedAt.takeIf { it > 0 } ?: 1
        }

        private fun updatedAtOf(o: JsonObject) = time(o.str("updatedAt"))

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

/**
 * Section order at [nowMs] (protocol.md 7.9, client-runtime `threadSort.ts`): pinned by key then
 * newest, active keyless-newest then by key, snoozed by wake time, settled newest first. Ties go by
 * id, then computer, so every client draws equal keys the same way.
 */
class ThreadOrder(private val nowMs: Long) : Comparator<ThreadSummary> {
    override fun compare(a: ThreadSummary, b: ThreadSummary): Int {
        val sa = a.sectionAt(nowMs)
        val sb = b.sectionAt(nowMs)
        if (sa != sb) return sa - sb
        return when (sa) {
            0 -> keyedFirst(a, b) { x, y -> y.sortTime.compareTo(x.sortTime) }
            1 -> keylessFirst(a, b)
            2 -> a.snoozedUntil.compareTo(b.snoozedUntil)
            else -> b.sortTime.compareTo(a.sortTime)
        }.let { if (it != 0) it else a.id.compareTo(b.id) }.let { if (it != 0) it else a.envId.compareTo(b.envId) }
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

/**
 * A project and its threads, in list order. [machine] names the computer when the list shows
 * several and another computer has a project of the same title.
 */
data class ProjectGroup(val project: ProjectSummary?, val threads: List<ThreadSummary>, val machine: String? = null) {
    val key: String get() = project?.key ?: "${threads.firstOrNull()?.envId.orEmpty()}:none"
}

/**
 * One computer's thread list: projects with their threads for the legacy tree, and every known
 * project (with or without threads) for the new sidebar's project filter and new-thread picker.
 */
data class ThreadListModel(
    val groups: List<ProjectGroup> = emptyList(),
    val threadCount: Int = 0,
    val projects: List<ProjectSummary> = emptyList(),
) {
    companion object {
        /** Projects with recent activity first; threads in [ThreadOrder]. Threads of unknown projects go last. */
        fun build(projects: Map<String, ProjectSummary>, threads: Collection<ThreadSummary>, nowMs: Long = System.currentTimeMillis()): ThreadListModel {
            val order = ThreadOrder(nowMs)
            val byProject = threads.groupBy { it.projectId }
            val groups = byProject.map { (projectId, list) -> ProjectGroup(projects[projectId], list.sortedWith(order)) }
                .sortedWith(compareBy<ProjectGroup> { it.project == null }.thenByDescending { g -> g.threads.maxOf { it.activityAt } })
            return ThreadListModel(groups, threads.size, projects.values.toList())
        }

        /**
         * Every computer's tree in one, for "All machines" in the legacy sidebar: projects by recent
         * activity across computers, each tagged with its computer's label when another computer
         * has a project of the same title.
         */
        fun merge(models: List<Pair<String, ThreadListModel>>): ThreadListModel {
            if (models.size == 1) return models[0].second
            val titles = HashMap<String, MutableSet<String>>()
            for ((label, m) in models) for (g in m.groups) titles.getOrPut(g.project?.title ?: "") { HashSet() } += label
            val groups = models.flatMap { (label, m) ->
                m.groups.map { g -> if ((titles[g.project?.title ?: ""]?.size ?: 0) > 1) g.copy(machine = label) else g }
            }.sortedWith(compareBy<ProjectGroup> { it.project == null }.thenByDescending { g -> g.threads.maxOf { it.activityAt } })
            return ThreadListModel(groups, models.sumOf { it.second.threadCount }, models.flatMap { it.second.projects })
        }
    }
}
