package org.anikodev.candywrapper.core.sidebar

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadOrder
import org.anikodev.candywrapper.core.model.ThreadSummary

/*
 * The new sidebar's logic (T3 `apps/web/src/components/Sidebar.logic.ts`, `Sidebar.tsx`
 * `partitionSidebarThreads`, client-runtime `projectGrouping.ts`, `threadSort.ts`,
 * `threadSettled.ts`), pure so it runs off the main thread and in JUnit.
 */

/** `SidebarSection`. [WORKING] is the Working shelf, a beta setting T3 ships off. */
enum class SidebarSection { PINNED, ACTIVE, WORKING, SNOOZED, SETTLED }

/** `resolveSidebarV2TopStatus`: what the card's status slot shows. Null shows the time instead. */
enum class TopStatus { APPROVAL, INPUT, WORKING, WAITING, FAILED, LIMITED, WOKE, DONE }

object SidebarStatus {
    fun top(pill: StatusPill, unread: Boolean, woke: Boolean): TopStatus? = when (pill) {
        StatusPill.WORKING -> TopStatus.WORKING
        StatusPill.WAITING -> TopStatus.WAITING
        StatusPill.APPROVAL -> TopStatus.APPROVAL
        StatusPill.INPUT -> TopStatus.INPUT
        StatusPill.FAILED -> TopStatus.FAILED
        StatusPill.LIMITED -> TopStatus.LIMITED
        StatusPill.READY, StatusPill.NONE -> when {
            woke -> TopStatus.WOKE
            unread -> TopStatus.DONE
            else -> null
        }
    }

    /**
     * `shouldRecedeSidebarThread`: rows with nothing for the user draw quieter. Running work
     * recedes; a finished or approval row recedes once seen; input, failures and limits never do.
     */
    fun recede(pill: StatusPill, unread: Boolean, woke: Boolean, active: Boolean): Boolean {
        if (active || pill == StatusPill.INPUT) return false
        return when (pill) {
            StatusPill.WORKING, StatusPill.WAITING -> true
            StatusPill.READY, StatusPill.NONE, StatusPill.APPROVAL -> !unread && !woke
            else -> false
        }
    }
}

/**
 * A logical project: the same repository on one or more computers (client-runtime
 * `ProjectGroup`, web `SidebarProjectSnapshot`). [members] holds one project per physical
 * folder; [memberKeys] every `env:project` the group covers, which is what the scope filter matches.
 */
data class SidebarProjectGroup(
    val key: String,
    val label: String,
    val representative: ProjectSummary,
    val members: List<ProjectSummary>,
    val memberKeys: Set<String>,
) {
    val envIds: Set<String> get() = members.mapTo(LinkedHashSet()) { it.envId }
}

object ProjectGrouping {
    /** shared `normalizeProjectPathForComparison`: trimmed, no trailing separator, Windows paths folded. */
    fun normalizePath(path: String): String {
        val v = path.trim()
        val root = v == "/" || v == "\\" || Regex("^[a-zA-Z]:[/\\\\]$").matches(v)
        var out = if (v.isEmpty() || root) v else if (v.startsWith("/")) v.trimEnd('/') else v.trimEnd('/', '\\')
        if (out.isEmpty()) out = v
        if (Regex("^[a-zA-Z]:$").matches(out)) out += "\\"
        val windows = Regex("^[a-zA-Z]:([/\\\\]|$)").containsMatchIn(out) || out.startsWith("\\\\")
        return if (windows) out.replace('/', '\\').lowercase() else out
    }

    private fun physicalKey(p: ProjectSummary) = "${p.envId}:${normalizePath(p.workspaceRoot)}"

    /** `deriveLogicalProjectKey` in the default `repository` mode. A NUL in the key reserves it, so it does not group. */
    private fun logicalKey(p: ProjectSummary): String = p.repoKey?.takeIf { '\u0000' !in it } ?: physicalKey(p)

    /** `deriveProjectGroupLabel`. */
    fun label(representative: ProjectSummary, members: List<ProjectSummary>): String {
        fun unique(values: List<String?>) = values.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        val titles = unique(members.map { it.title })
        val displayNames = unique(members.map { it.repoName })
        val repoNames = unique(members.map { it.repoShortName })
        val title = titles.firstOrNull()
        return when {
            titles.size == 1 && title != null && title !in displayNames && title !in repoNames -> title
            displayNames.size == 1 -> displayNames[0]
            repoNames.size == 1 -> repoNames[0]
            else -> representative.title
        }
    }

    /**
     * `buildProjectGroups`: one winner per physical folder (freshest, then highest id), grouped by
     * repository. The representative is the [preferredEnvId]'s member when it has one.
     */
    fun build(projects: List<ProjectSummary>, preferredEnvId: String? = null): List<SidebarProjectGroup> {
        val byPhysical = LinkedHashMap<String, MutableList<ProjectSummary>>()
        for (p in projects) byPhysical.getOrPut(physicalKey(p)) { ArrayList() } += p
        val logicalByPhysical = HashMap<String, String>()
        val members = LinkedHashMap<String, MutableList<ProjectSummary>>()
        for ((physical, list) in byPhysical) {
            val winner = list.reduce { cur, cand -> if (cand.updatedAt > cur.updatedAt || (cand.updatedAt == cur.updatedAt && cand.id > cur.id)) cand else cur }
            // A folder whose winner lost its identity still groups by the freshest member that has one.
            val identity = if (winner.repoKey != null) winner else list.filter { it.repoKey != null }.maxWithOrNull(compareBy<ProjectSummary> { it.updatedAt }.thenBy { it.id }) ?: winner
            val logical = logicalKey(identity)
            logicalByPhysical[physical] = logical
            members.getOrPut(logical) { ArrayList() } += winner
        }
        val refs = LinkedHashMap<String, LinkedHashSet<String>>()
        for (p in projects) refs.getOrPut(logicalByPhysical[physicalKey(p)] ?: logicalKey(p)) { LinkedHashSet() } += p.key
        return members.map { (key, list) ->
            val representative = preferredEnvId?.let { env -> list.firstOrNull { it.envId == env } } ?: list[0]
            SidebarProjectGroup(
                key = key,
                label = if (list.size > 1) label(representative, list) else representative.title,
                representative = representative,
                members = list,
                memberKeys = refs[key].orEmpty(),
            )
        }
    }

    /** `projectGroupsSpanEnvironments`. */
    fun spansEnvironments(groups: List<SidebarProjectGroup>): Boolean =
        groups.asSequence().flatMap { it.members.asSequence() }.map { it.envId }.distinct().take(2).count() > 1

    /**
     * `sortSidebarV2ProjectGroups` with T3's default `updated_at` order: the latest thread activity
     * in the group, else the project's own time; ties by label, then key. Subagent and archived
     * threads never reach [threads], so they cannot reorder projects.
     */
    fun sort(groups: List<SidebarProjectGroup>, threads: Collection<ThreadSummary>): List<SidebarProjectGroup> {
        val groupOf = HashMap<String, String>()
        for (g in groups) for (k in g.memberKeys) groupOf[k] = g.key
        val latest = HashMap<String, Long>()
        for (t in threads) {
            val g = groupOf["${t.envId}:${t.projectId}"] ?: continue
            latest[g] = maxOf(latest[g] ?: Long.MIN_VALUE, t.activityAt)
        }
        return groups.map { g -> g to (latest[g.key] ?: g.representative.updatedAt) }
            .sortedWith(compareByDescending<Pair<SidebarProjectGroup, Long>> { it.second }.thenBy { it.first.label }.thenBy { it.first.key })
            .map { it.first }
    }
}

/** The threads in each section, in list order, and when the earliest snooze ends (0 for none). */
data class SidebarSections(
    val pinned: List<ThreadSummary> = emptyList(),
    val active: List<ThreadSummary> = emptyList(),
    val snoozed: List<ThreadSummary> = emptyList(),
    val settled: List<ThreadSummary> = emptyList(),
    val nextWakeAt: Long = 0,
    /** The Working shelf; empty while the setting is off. */
    val working: List<ThreadSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && active.isEmpty() && working.isEmpty() && snoozed.isEmpty() && settled.isEmpty()
    val all: List<ThreadSummary> get() = pinned + active + working + snoozed + settled

    fun sectionOf(key: String): SidebarSection? = when {
        pinned.any { it.key == key } -> SidebarSection.PINNED
        active.any { it.key == key } -> SidebarSection.ACTIVE
        working.any { it.key == key } -> SidebarSection.WORKING
        snoozed.any { it.key == key } -> SidebarSection.SNOOZED
        settled.any { it.key == key } -> SidebarSection.SETTLED
        else -> null
    }
}

/**
 * Everything the new sidebar draws for one machine scope, built off the main thread. Unchanged
 * threads keep the instances the sessions published, so their rows skip recomposition.
 */
data class SidebarData(
    val groups: List<SidebarProjectGroup> = emptyList(),
    val sections: SidebarSections = SidebarSections(),
    /** Group label by `env:project`, the card's first line. */
    val projectLabel: Map<String, String> = emptyMap(),
    val projects: Map<String, ProjectSummary> = emptyMap(),
    /** True when projects come from more than one computer: rows then name theirs. */
    val spansMachines: Boolean = false,
    /** The scope filter in force: null for All projects. */
    val scope: SidebarProjectGroup? = null,
)

/** A Redesign project section: its group (null for threads whose project has no group yet) and its threads in list order. */
data class ProjectSection(val key: String, val group: SidebarProjectGroup?, val threads: List<ThreadSummary>)

object SidebarModel {
    /**
     * CandyWrapper's Redesign sections (`SidebarListModel.projectSections`): [threads] under one
     * section per project group, sections in [groups] order, threads kept in the order given. Threads
     * whose project has no group yet follow in their own sections, in thread order.
     */
    fun projectSections(threads: List<ThreadSummary>, groups: List<SidebarProjectGroup>): List<ProjectSection> {
        val groupOf = HashMap<String, SidebarProjectGroup>()
        for (g in groups) for (k in g.memberKeys) groupOf[k] = g
        val byKey = LinkedHashMap<String, MutableList<ThreadSummary>>()
        for (g in groups) byKey[g.key] = ArrayList()
        for (t in threads) {
            val pk = "${t.envId}:${t.projectId}"
            byKey.getOrPut(groupOf[pk]?.key ?: "project:$pk") { ArrayList() } += t
        }
        val byGroupKey = groups.associateBy { it.key }
        return byKey.mapNotNull { (k, list) -> if (list.isEmpty()) null else ProjectSection(k, byGroupKey[k], list) }
    }

    /**
     * `partitionSidebarThreads`: [threads] filtered to [scopeKeys] (`env:project`, null for all)
     * and split by section at [nowMs], each sorted as T3 sorts it. With [workingShelf] on, active
     * threads that are working fold into the Working shelf, newest send first, and the rest of the
     * inbox orders by when each came back to the user, [returnedAt] included (`InboxReturns`).
     */
    fun partition(
        threads: Collection<ThreadSummary>,
        scopeKeys: Set<String>?,
        nowMs: Long,
        workingShelf: Boolean = false,
        returnedAt: (String) -> Long = { 0 },
    ): SidebarSections {
        val order = ThreadOrder(nowMs)
        val pinned = ArrayList<ThreadSummary>()
        val active = ArrayList<ThreadSummary>()
        val working = ArrayList<ThreadSummary>()
        val snoozed = ArrayList<ThreadSummary>()
        val settled = ArrayList<ThreadSummary>()
        for (t in threads) {
            if (scopeKeys != null && "${t.envId}:${t.projectId}" !in scopeKeys) continue
            when (t.sectionAt(nowMs)) {
                0 -> pinned += t
                2 -> snoozed += t
                3 -> settled += t
                else -> if (workingShelf && t.working) working += t else active += t
            }
        }
        for (list in listOf(pinned, snoozed, settled)) list.sortWith(order)
        if (workingShelf) {
            active.sortWith(newestFirst { maxOf(it.returnTime, returnedAt(it.key)) })
            working.sortWith(newestFirst { it.sendTime })
        } else {
            active.sortWith(order)
        }
        return SidebarSections(pinned, active, snoozed, settled, snoozed.minOfOrNull { it.snoozedUntil } ?: 0, working)
    }

    /** threadInbox.ts `sortNewestFirst`: the time descending, then id, then computer. */
    private fun newestFirst(time: (ThreadSummary) -> Long): Comparator<ThreadSummary> =
        compareByDescending(time).thenBy { it.id }.thenBy { it.envId }

    /**
     * The whole model for [projects] and [threads] gathered from the scoped computers.
     * [scopeKey] is the persisted project filter; a key no group has any more shows All projects.
     */
    fun build(
        projects: List<ProjectSummary>,
        threads: List<ThreadSummary>,
        scopeKey: String?,
        nowMs: Long,
        preferredEnvId: String? = null,
        workingShelf: Boolean = false,
        returnedAt: (String) -> Long = { 0 },
    ): SidebarData {
        val groups = ProjectGrouping.sort(ProjectGrouping.build(projects, preferredEnvId), threads)
        val scope = scopeKey?.let { k -> groups.firstOrNull { it.key == k } }
        val label = HashMap<String, String>()
        for (g in groups) for (k in g.memberKeys) label[k] = g.label
        return SidebarData(
            groups = groups,
            sections = partition(threads, scope?.memberKeys, nowMs, workingShelf, returnedAt),
            projectLabel = label,
            projects = projects.associateBy { it.key },
            spansMachines = ProjectGrouping.spansEnvironments(groups),
            scope = scope,
        )
    }

    /**
     * `searchSidebarThreads` on titles: case-insensitive substring, in list order. T3 also
     * matches message content through a server search this client does not call.
     */
    fun search(threads: List<ThreadSummary>, query: String): List<ThreadSummary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return threads.filter { it.title.lowercase().contains(q) }
    }

    /** `SETTLED_TAIL_INITIAL_COUNT` and `SETTLED_TAIL_PAGE_COUNT`. */
    const val SETTLED_INITIAL = 10
    const val SETTLED_PAGE = 25

    /**
     * The settled rows the open shelf shows: the first [count], plus the open thread when it sits
     * past them. A closed shelf shows only the open thread, if it is settled.
     */
    fun visibleSettled(settled: List<ThreadSummary>, count: Int, expanded: Boolean, openKey: String?): List<ThreadSummary> {
        val head = if (settled.size <= count) settled else settled.take(count).let { head ->
            val open = settled.drop(count).firstOrNull { it.key == openKey }
            if (open != null) head + open else head
        }
        return if (expanded) head else head.filter { it.key == openKey }
    }

    /** A collapsed shelf keeps only the open thread, so it stays visible (`routeThreadOnly`). */
    fun visibleShelf(list: List<ThreadSummary>, expanded: Boolean, openKey: String?): List<ThreadSummary> =
        if (expanded) list else list.filter { it.key == openKey }

    /**
     * `planForwardNavigation`: after parking the open thread, the next pinned or active card after
     * it, wrapping round, else null. The caller plans before the park changes the sections.
     */
    fun nextAfterPark(sections: SidebarSections, threadKey: String, parking: Set<String> = emptySet()): ThreadSummary? {
        val cards = sections.pinned + sections.active
        val ordered = sections.pinned + sections.active + sections.working + sections.snoozed + sections.settled
        val i = ordered.indexOfFirst { it.key == threadKey }
        if (i < 0) return null
        val cardKeys = cards.mapTo(HashSet()) { it.key }
        return (ordered.drop(i + 1) + ordered.take(i)).firstOrNull { it.key in cardKeys && it.key != threadKey && it.key !in parking }
    }
}

/**
 * client-runtime `createInboxReturnTracker`: when this phone saw each thread leave the Working
 * shelf, so the inbox puts it on top as it comes back. The first [observe] only takes a baseline,
 * so turning the shelf on never reshuffles the inbox. Keep one for the app's life.
 */
class InboxReturns {
    private var lastWorking: Set<String>? = null
    private val returns = HashMap<String, Long>()

    /** Every thread on each rebuild, or null while the shelf is off, which resets it. */
    @Synchronized
    fun observe(threads: Collection<ThreadSummary>?, nowMs: Long) {
        if (threads == null) {
            lastWorking = null
            returns.clear()
            return
        }
        val present = threads.mapTo(HashSet()) { it.key }
        val working = threads.filter { it.working }.mapTo(HashSet()) { it.key }
        returns.keys.retainAll(present)
        for (key in lastWorking.orEmpty()) if (key in present && key !in working) returns[key] = nowMs
        lastWorking = working
    }

    @Synchronized
    fun returnedAt(key: String): Long = returns[key] ?: 0
}

/** What New thread does: start in one project, or ask which (T3's `new-thread-in` palette). */
sealed interface NewThreadTarget {
    data class Start(val envId: String, val projectId: String) : NewThreadTarget
    /** Pick a project; [preferred] (the open thread's or the filtered one) is listed first. */
    data class PickProject(val groups: List<SidebarProjectGroup>) : NewThreadTarget
    /** The project is on several computers: pick one. */
    data class PickMachine(val group: SidebarProjectGroup) : NewThreadTarget
    data object None : NewThreadTarget
}

object NewThread {
    /**
     * `handleNewThreadClick`: with one project it starts there (`shouldCreateNewThreadInCurrentProject`),
     * otherwise it asks, the preferred project first (`buildSidebarProjectPickerEntries`).
     */
    fun target(groups: List<SidebarProjectGroup>, preferredKey: String?): NewThreadTarget = when {
        groups.isEmpty() -> NewThreadTarget.None
        groups.size == 1 -> inGroup(groups[0], preferredKey)
        else -> {
            val i = groups.indexOfFirst { preferredKey != null && preferredKey in it.memberKeys }
            NewThreadTarget.PickProject(if (i <= 0) groups else listOf(groups[i]) + groups.take(i) + groups.drop(i + 1))
        }
    }

    /** A picked project: its only member, the preferred computer's member, or ask which computer. */
    fun inGroup(group: SidebarProjectGroup, preferredKey: String? = null): NewThreadTarget {
        val exact = group.members.firstOrNull { it.key == preferredKey }
        val only = group.members.singleOrNull()
        val pick = exact ?: only
        return if (pick != null) NewThreadTarget.Start(pick.envId, pick.id) else NewThreadTarget.PickMachine(group)
    }
}

/** client-runtime `resolveSnoozePresets` and `snoozeWakeLabel`. */
object Snooze {
    data class Preset(val id: String, val label: String, val whenLabel: String, val untilMs: Long)

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    fun presets(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<Preset> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val day = DateTimeFormatter.ofPattern("EEE", locale)
        fun at(base: ZonedDateTime, hour: Int) = base.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        val inHour = now.plusHours(1)
        val inThree = now.plusHours(3)
        val out = mutableListOf(
            Preset("hour", "In 1 hour", inHour.format(time), inHour.toInstant().toEpochMilli()),
            Preset("three-hours", "In 3 hours", inThree.format(time), inThree.toInstant().toEpochMilli()),
        )
        val evening = at(now, 18)
        if (evening.toInstant().toEpochMilli() - nowMs > HOUR) out += Preset("evening", "This evening", evening.format(time), evening.toInstant().toEpochMilli())
        // Calendar days, not 24 h steps, so a DST change does not skip a day.
        val tomorrow = at(now.plusDays(1), 9)
        out += Preset("tomorrow", "Tomorrow", tomorrow.format(time), tomorrow.toInstant().toEpochMilli())
        val nextWeek = at(now.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), 9)
        if (nextWeek != tomorrow) out += Preset("next-week", "Next week", "${nextWeek.format(day)} ${nextWeek.format(time)}", nextWeek.toInstant().toEpochMilli())
        return out
    }

    /** "now", minutes under an hour, hours under a day, then days; always rounded up. */
    fun wakeLabel(untilMs: Long, nowMs: Long): String {
        val left = untilMs - nowMs
        return when {
            left <= 0 -> "now"
            left < HOUR -> "${maxOf(1, ceilDiv(left, 60_000))}m"
            left < DAY -> "${ceilDiv(left, HOUR)}h"
            else -> "${ceilDiv(left, DAY)}d"
        }
    }

    /** `canSnooze`: never while the thread waits on the user. ponytail: queued-start check skipped; the server rejects those. */
    fun canSnooze(t: ThreadSummary): Boolean = t.pill != StatusPill.APPROVAL && t.pill != StatusPill.INPUT

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
}
