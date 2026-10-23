package org.anikodev.candywrapper.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sidebar.InboxReturns
import org.anikodev.candywrapper.core.sidebar.NewThread
import org.anikodev.candywrapper.core.sidebar.NewThreadTarget
import org.anikodev.candywrapper.core.sidebar.ProjectGrouping
import org.anikodev.candywrapper.core.sidebar.SidebarModel
import org.anikodev.candywrapper.core.sidebar.SidebarStatus
import org.anikodev.candywrapper.core.sidebar.Snooze
import org.anikodev.candywrapper.core.sidebar.TopStatus
import org.anikodev.candywrapper.core.sync.ShellProjection

/** The new sidebar's logic, case for case after T3's `Sidebar.logic.test.ts` and `threadSnoozed.test.ts` where they apply. */
class SidebarLogicTest {
    private fun json(s: String) = WireJson.parseToJsonElement(s) as JsonObject
    private val now = ThreadSummary.time("2026-09-12T10:00:00.000Z")

    /** A shell thread with the fields every test needs; [extra] adds or overrides. */
    private fun shell(id: String, extra: String = "", project: String = "p", env: String = "local"): ThreadSummary? = ThreadSummary.fromShell(json(
        """{"id":"$id","projectId":"$project","title":"Thread $id","status":"idle","latestRunId":null,"activeProviderThreadId":null,
        "createdAt":"2026-09-12T08:00:00.000Z","updatedAt":"2026-09-12T08:00:00.000Z","lineage":{"relationshipToParent":null}${if (extra.isEmpty()) "" else ",$extra"}}""",
    ), now, envId = env)

    private fun t(id: String, extra: String = "", project: String = "p", env: String = "local") = shell(id, extra, project, env)!!

    // resolveSidebarThreadStatus / resolveSidebarV2TopStatus / shouldRecedeSidebarThread

    @Test
    fun usageLimitStopsStayLimitedAndVisible() {
        val limited = t("a", """"latestRunId":"r","status":"failed","lastErrorClass":"usage_limit"""")
        assertEquals(StatusPill.LIMITED, limited.pill)
        assertEquals(TopStatus.LIMITED, SidebarStatus.top(limited.pill, unread = false, woke = false))
        assertFalse(SidebarStatus.recede(limited.pill, unread = false, woke = false, active = false))
    }

    @Test
    fun inputOutranksARunningRuntimeBelowApproval() {
        assertEquals(StatusPill.INPUT, t("a", """"latestRunId":"r","status":"running","pendingRuntimeRequest":{"kind":"user_input"}""").pill)
        assertEquals(StatusPill.APPROVAL, t("a", """"latestRunId":"r","status":"running","pendingRuntimeRequest":{"kind":"command"}""").pill)
        assertEquals(StatusPill.WAITING, t("a", """"latestRunId":"r","status":"idle"""").pill)
    }

    @Test
    fun unseenCompletionWithBackgroundWork() {
        // A command roster does not hold completion: Done, not receded. A monitor does: Waiting, receded.
        for ((kind, top, receded) in listOf(Triple("command", TopStatus.DONE, false), Triple("monitor", TopStatus.WAITING, true))) {
            val thread = t("a", """"latestRunId":"r","status":"completed","latestRunCompletedAt":"2026-06-20T01:00:00.000Z",
                "lastVisitedAt":"2026-06-20T00:59:00.000Z","pendingBackgroundTasks":[{"taskId":"w","kind":"$kind"}]""")
            assertTrue(thread.unread, kind)
            assertEquals(top, SidebarStatus.top(thread.pill, thread.unread, woke = false), kind)
            assertEquals(receded, SidebarStatus.recede(thread.pill, thread.unread, woke = false, active = false), kind)
        }
    }

    @Test
    fun readRowsRecedeAndTheOpenRowNever() {
        assertTrue(SidebarStatus.recede(StatusPill.READY, unread = false, woke = false, active = false))
        assertFalse(SidebarStatus.recede(StatusPill.READY, unread = true, woke = false, active = false))
        assertFalse(SidebarStatus.recede(StatusPill.WORKING, unread = false, woke = false, active = true))
        assertFalse(SidebarStatus.recede(StatusPill.INPUT, unread = false, woke = false, active = false))
        assertNull(SidebarStatus.top(StatusPill.READY, unread = false, woke = false))
        assertEquals(TopStatus.WOKE, SidebarStatus.top(StatusPill.READY, unread = true, woke = true))
    }

    // searchSidebarThreads

    @Test
    fun searchMatchesTitlesInListOrder() {
        val threads = listOf(t("1").copy(title = "Fix workspace search"), t("2").copy(title = "Review providers"), t("3").copy(title = "WORKTREE cleanup"))
        assertEquals(listOf("1", "3"), SidebarModel.search(threads, " work ").map { it.id })
        assertEquals(emptyList(), SidebarModel.search(threads, "  "))
    }

    // partition, sortPinnedThreadsForSidebar, sortSettledThreads

    @Test
    fun pinnedKeyedThreadsLeadKeylessOnesInCreationOrder() {
        val sections = SidebarModel.partition(listOf(
            t("keyless-old", """"pinnedAt":"2026-09-12T09:00:00.000Z","createdAt":"2026-03-09T08:00:00.000Z""""),
            t("second", """"pinnedAt":"2026-09-12T09:00:00.000Z","pinOrderKey":"t","createdAt":"2026-03-09T09:00:00.000Z""""),
            t("keyless-new", """"pinnedAt":"2026-09-12T09:00:00.000Z","createdAt":"2026-03-09T12:00:00.000Z""""),
            t("first", """"pinnedAt":"2026-09-12T09:00:00.000Z","pinOrderKey":"g","createdAt":"2026-03-09T07:00:00.000Z""""),
        ), null, now)
        assertEquals(listOf("first", "second", "keyless-new", "keyless-old"), sections.pinned.map { it.id })
    }

    @Test
    fun equalPinKeysBreakByIdThenComputer() {
        val pinned = """"pinnedAt":"2026-09-12T09:00:00.000Z","pinOrderKey":"m""""
        val sections = SidebarModel.partition(listOf(t("b", pinned), t("a", pinned, env = "z"), t("a", pinned, env = "y")), null, now)
        assertEquals(listOf("y:a", "z:a", "local:b"), sections.pinned.map { it.key })
    }

    @Test
    fun sectionsFollowSnoozeSettleAndPin() {
        val sections = SidebarModel.partition(listOf(
            t("active"),
            t("pinned", """"pinnedAt":"2026-09-12T09:00:00.000Z""""),
            t("settled", """"settledOverride":"settled","settledAt":"2026-09-12T09:30:00.000Z""""),
            t("settled-older", """"settledOverride":"settled","settledAt":"2026-09-12T09:00:00.000Z""""),
            t("snoozed", """"snoozedUntil":"2026-09-12T12:00:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z""""),
            t("snoozed-sooner", """"snoozedUntil":"2026-09-12T11:00:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z""""),
            // A snooze that has ended puts the thread back in its section.
            t("woke", """"snoozedUntil":"2026-09-12T09:30:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z""""),
        ), null, now)
        assertEquals(listOf("pinned"), sections.pinned.map { it.id })
        assertEquals(setOf("active", "woke"), sections.active.map { it.id }.toSet())
        assertEquals(listOf("snoozed-sooner", "snoozed"), sections.snoozed.map { it.id })
        assertEquals(listOf("settled", "settled-older"), sections.settled.map { it.id })
        assertEquals(ThreadSummary.time("2026-09-12T11:00:00.000Z"), sections.nextWakeAt)
        // An hour later the sooner snooze has woken.
        assertEquals(listOf("snoozed"), SidebarModel.partition(sections.all, null, now + 3_600_000).snoozed.map { it.id })
    }

    @Test
    fun scopeKeepsOnlyTheGroupsProjects() {
        val threads = listOf(t("1", project = "a"), t("2", project = "b"), t("3", project = "a", env = "remote"))
        assertEquals(setOf("local:1", "remote:3"), SidebarModel.partition(threads, setOf("local:a", "remote:a"), now).active.map { it.key }.toSet())
    }

    // threadSnoozed.test.ts: effectiveSnoozed, threadWokeAt

    @Test
    fun snoozeWakesEarlyWhenTheAgentIsBlockedOnTheUser() {
        val blocked = t("a", """"snoozedUntil":"2026-09-12T12:00:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z",
            "updatedAt":"2026-09-12T09:45:00.000Z","latestRunId":"r","status":"running","pendingRuntimeRequest":{"kind":"user_input"}""")
        assertEquals(1, blocked.sectionAt(now))
        assertEquals(ThreadSummary.time("2026-09-12T09:45:00.000Z"), blocked.wokeAtAt(now))
        assertTrue(blocked.isWoke(now))
    }

    @Test
    fun snoozeHoldsWhileTheSessionKeepsWorking() {
        val working = t("a", """"snoozedUntil":"2026-09-12T12:00:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z","latestRunId":"r","status":"running"""")
        assertEquals(2, working.sectionAt(now))
        assertEquals(0, working.wokeAtAt(now))
    }

    @Test
    fun aFailureBeforeTheSnoozeDoesNotWakeIt() {
        val old = t("a", """"snoozedUntil":"2026-09-12T12:00:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z",
            "latestRunId":"r","status":"failed","latestRunCompletedAt":"2026-09-12T08:30:00.000Z"""")
        assertEquals(2, old.sectionAt(now))
        val fresh = t("a", """"snoozedUntil":"2026-09-12T12:00:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z",
            "latestRunId":"r","status":"failed","latestRunCompletedAt":"2026-09-12T09:30:00.000Z"""")
        assertEquals(ThreadSummary.time("2026-09-12T09:30:00.000Z"), fresh.wokeAtAt(now))
    }

    @Test
    fun aTimerWakeIsWokeUntilVisited() {
        val woke = t("a", """"snoozedUntil":"2026-09-12T09:30:00.000Z","snoozedAt":"2026-09-12T09:00:00.000Z"""")
        assertTrue(woke.isWoke(now))
        assertFalse(woke.copy(visitedAt = ThreadSummary.time("2026-09-12T09:30:00.000Z")).isWoke(now))
        assertTrue(woke.copy(visitedAt = ThreadSummary.time("2026-09-12T09:29:00.000Z")).isWoke(now))
        // Settling clears it.
        assertFalse(woke.copy(section = 3).isWoke(now))
    }

    // resolveSnoozePresets, snoozeWakeLabel

    @Test
    fun snoozePresetsDropEveningWhenItIsNear() {
        val zone = ZoneId.of("Europe/Berlin")
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
        assertEquals(listOf("hour", "three-hours", "tomorrow", "next-week"), Snooze.presets(at(2026, 4, 8, 17, 30), zone, Locale.US).map { it.id })
        assertEquals(listOf("hour", "three-hours", "evening", "tomorrow", "next-week"), Snooze.presets(at(2026, 4, 8, 9, 0), zone, Locale.US).map { it.id })
        // On a Sunday next week is tomorrow, so it is dropped.
        val sunday = Snooze.presets(at(2026, 4, 12, 10, 0), zone, Locale.US)
        assertEquals(listOf("hour", "three-hours", "evening", "tomorrow"), sunday.map { it.id })
        assertEquals(at(2026, 4, 13, 9, 0), sunday.last().untilMs)
    }

    @Test
    fun wakeLabelsRoundUp() {
        assertEquals("now", Snooze.wakeLabel(now - 1, now))
        assertEquals("1m", Snooze.wakeLabel(now + 10_000, now))
        assertEquals("2h", Snooze.wakeLabel(now + 61 * 60_000, now))
        assertEquals("2d", Snooze.wakeLabel(now + 25 * 3_600_000L, now))
    }

    // planForwardNavigation

    @Test
    fun parkingMovesToTheNextCardWrappingRound() {
        val sections = SidebarModel.partition(listOf(
            t("a", """"createdAt":"2026-09-12T08:03:00.000Z""""),
            t("b", """"createdAt":"2026-09-12T08:02:00.000Z""""),
            t("c", """"createdAt":"2026-09-12T08:01:00.000Z""""),
            t("s", """"settledOverride":"settled""""),
        ), null, now)
        assertEquals(listOf("a", "b", "c"), sections.active.map { it.id })
        assertEquals("c", SidebarModel.nextAfterPark(sections, "local:b")?.id)
        assertEquals("a", SidebarModel.nextAfterPark(sections, "local:c")?.id)
        assertEquals("a", SidebarModel.nextAfterPark(sections, "local:b", parking = setOf("local:c"))?.id)
        assertNull(SidebarModel.nextAfterPark(SidebarModel.partition(listOf(t("a")), null, now), "local:a"))
    }

    @Test
    fun settledTailKeepsTheOpenThread() {
        val settled = (1..15).map { t("s$it", """"settledOverride":"settled"""") }
        assertEquals(10, SidebarModel.visibleSettled(settled, 10, expanded = true, openKey = null).size)
        assertEquals(11, SidebarModel.visibleSettled(settled, 10, expanded = true, openKey = "local:s14").size)
        assertEquals(listOf("s14"), SidebarModel.visibleSettled(settled, 10, expanded = false, openKey = "local:s14").map { it.id })
        assertEquals(emptyList(), SidebarModel.visibleSettled(settled, 10, expanded = false, openKey = null))
    }

    // projectGrouping.ts, sortScopedProjectsForSidebar, sortSidebarV2ProjectGroups

    private fun project(env: String, id: String, title: String, root: String = "/code/$id", repo: String? = null, updated: Long = 0) =
        ProjectSummary(id, title, root, envId = env, repoKey = repo, repoShortName = repo?.substringAfterLast('/'), updatedAt = updated)

    @Test
    fun theSameRepositoryOnTwoComputersIsOneProject() {
        val groups = ProjectGrouping.build(listOf(
            project("mac", "p1", "t3code", repo = "github.com/pingdotgg/t3code"),
            project("mini", "p9", "t3code", root = "/Users/a/t3code/", repo = "github.com/pingdotgg/t3code"),
            project("mini", "p2", "scratch"),
        ), preferredEnvId = "mini")
        assertEquals(2, groups.size)
        val t3 = groups.first { it.label == "t3code" }
        assertEquals(setOf("mac:p1", "mini:p9"), t3.memberKeys)
        assertEquals("mini", t3.representative.envId)
        assertTrue(ProjectGrouping.spansEnvironments(groups))
        assertFalse(ProjectGrouping.spansEnvironments(groups.filter { it.label == "scratch" }))
    }

    @Test
    fun identicalProjectIdsOnDifferentComputersStaySeparate() {
        val groups = ProjectGrouping.build(listOf(project("local", "shared", "Local project"), project("remote", "shared", "Remote project")))
        val threads = listOf(t("1", """"latestUserMessageAt":"2026-03-09T10:02:00.000Z"""", project = "shared"),
            t("2", """"latestUserMessageAt":"2026-03-09T10:10:00.000Z"""", project = "shared", env = "remote"))
        assertEquals(listOf("Remote project", "Local project"), ProjectGrouping.sort(groups, threads).map { it.label })
    }

    @Test
    fun projectsSortByTheirLatestUserMessage() {
        val groups = ProjectGrouping.build(listOf(project("local", "project-1", "Older project"), project("local", "project-2", "Newer project")))
        val threads = listOf(
            t("1", """"updatedAt":"2026-03-09T10:20:00.000Z","latestUserMessageAt":"2026-03-09T10:01:00.000Z"""", project = "project-1"),
            t("2", """"updatedAt":"2026-03-09T10:05:00.000Z","latestUserMessageAt":"2026-03-09T10:05:00.000Z"""", project = "project-2"),
        )
        assertEquals(listOf("Newer project", "Older project"), ProjectGrouping.sort(groups, threads).map { it.label })
    }

    @Test
    fun aHiddenSubagentThreadDoesNotReorderProjects() {
        val subagent = shell("sub", """"lineage":{"relationshipToParent":"subagent"},"latestUserMessageAt":"2026-03-09T10:10:00.000Z"""", project = "older")
        assertNull(subagent)
        val groups = ProjectGrouping.build(listOf(project("local", "older", "A older project"), project("local", "newer", "Z newer project")))
        val threads = listOf(t("1", """"latestUserMessageAt":"2026-03-09T10:01:00.000Z"""", project = "older"),
            t("2", """"latestUserMessageAt":"2026-03-09T10:05:00.000Z"""", project = "newer"))
        assertEquals(listOf("Z newer project", "A older project"), ProjectGrouping.sort(groups, threads).map { it.label })
    }

    @Test
    fun groupLabelsPreferASharedTitle() {
        val a = project("a", "1", "Web", repo = "github.com/x/web")
        val b = project("b", "2", "Web", repo = "github.com/x/web")
        assertEquals("Web", ProjectGrouping.label(a, listOf(a, b)))
        assertEquals("web", ProjectGrouping.label(a, listOf(a, b.copy(title = "Frontend"))))
    }

    @Test
    fun pathsCompareAsT3ComparesThem() {
        assertEquals("/a/b", ProjectGrouping.normalizePath(" /a/b/ "))
        assertEquals("/", ProjectGrouping.normalizePath("/"))
        assertEquals("c:\\users\\a", ProjectGrouping.normalizePath("C:/Users/a/"))
    }

    // shouldCreateNewThreadInCurrentProject, buildSidebarProjectPickerEntries

    @Test
    fun newThreadStartsDirectlyOnlyWhenThereIsOneChoice() {
        val one = ProjectGrouping.build(listOf(project("mac", "p1", "t3code")))
        assertEquals(NewThreadTarget.Start("mac", "p1"), NewThread.target(one, null))
        val shared = ProjectGrouping.build(listOf(project("mac", "p1", "t3code", repo = "r"), project("mini", "p2", "t3code", repo = "r")))
        assertIs<NewThreadTarget.PickMachine>(NewThread.target(shared, null))
        assertEquals(NewThreadTarget.Start("mini", "p2"), NewThread.target(shared, "mini:p2"))
        val many = ProjectGrouping.build(listOf(project("mac", "p1", "a"), project("mac", "p2", "b"), project("mac", "p3", "c")))
        val pick = assertIs<NewThreadTarget.PickProject>(NewThread.target(many, "mac:p3"))
        assertEquals(listOf("c", "a", "b"), pick.groups.map { it.label })
        assertEquals(NewThreadTarget.None, NewThread.target(emptyList(), null))
    }

    // The model across computers, as "All machines" builds it.

    @Test
    fun allMachinesMergesShellsAndKeysThreadsByComputer() {
        val mac = ShellProjection(envId = "mac").apply {
            apply(json("""{"kind":"snapshot","snapshot":{"snapshotSequence":1,"projects":[{"id":"p","title":"t3code","workspaceRoot":"/a",
                "repositoryIdentity":{"canonicalKey":"github.com/pingdotgg/t3code","locator":{"source":"git-remote","remoteName":"origin","remoteUrl":"x"},"name":"t3code"}}],
                "threads":[{"id":"t","projectId":"p","title":"On the Mac","status":"idle","createdAt":"2026-09-12T08:00:00.000Z","updatedAt":"2026-09-12T08:00:00.000Z","lineage":{}}]}}"""), now)
        }.model(now)
        val mini = ShellProjection(envId = "mini").apply {
            apply(json("""{"kind":"snapshot","snapshot":{"snapshotSequence":1,"projects":[{"id":"p","title":"t3code","workspaceRoot":"/b",
                "repositoryIdentity":{"canonicalKey":"github.com/pingdotgg/t3code","locator":{"source":"git-remote","remoteName":"origin","remoteUrl":"x"},"name":"t3code"}}],
                "threads":[{"id":"t","projectId":"p","title":"On the mini","status":"idle","createdAt":"2026-09-12T09:00:00.000Z","updatedAt":"2026-09-12T09:00:00.000Z","lineage":{}}]}}"""), now)
        }.model(now)
        val data = SidebarModel.build(mac.projects + mini.projects, (mac.groups + mini.groups).flatMap { it.threads }, scopeKey = null, nowMs = now)
        assertEquals(1, data.groups.size)
        assertTrue(data.spansMachines)
        assertEquals(listOf("mini:t", "mac:t"), data.sections.active.map { it.key })
        assertEquals("t3code", data.projectLabel["mini:p"])
        // A filter by that project keeps both; a stale filter shows everything.
        assertEquals(2, SidebarModel.build(mac.projects + mini.projects, data.sections.all, "github.com/pingdotgg/t3code", now).sections.active.size)
        assertNull(SidebarModel.build(mac.projects + mini.projects, data.sections.all, "gone", now).scope)
    }

    // The Working shelf: threadInbox.ts isThreadWorking, sortWorkingThreadsBySend, sortInboxThreadsByReturn, createInboxReturnTracker

    private val running = """"latestRunId":"r","status":"running","activeRunId":"r""""

    @Test
    fun workingShelfTakesBusyThreadsAndLeavesTheUsersOnes() {
        val threads = listOf(
            t("run", running),
            t("parked", """"latestRunId":"r","status":"completed","pendingBackgroundTasks":[{"kind":"monitor"}]"""),
            t("approval", """$running,"pendingRuntimeRequest":{"kind":"command"}"""),
            t("failed", """"latestRunId":"r","status":"failed""""),
            t("done", """"latestRunId":"r","status":"completed""""),
            t("pinned", """$running,"pinnedAt":"2026-09-12T08:00:00.000Z""""),
        )
        val on = SidebarModel.partition(threads, null, now, workingShelf = true)
        assertEquals(setOf("run", "parked"), on.working.map { it.id }.toSet())
        assertEquals(setOf("approval", "failed", "done"), on.active.map { it.id }.toSet())
        // Pinned threads stay pinned while they work.
        assertEquals(listOf("pinned"), on.pinned.map { it.id })
        assertEquals(on.working.map { it.key }.toSet(), on.all.filter { on.sectionOf(it.key) == org.anikodev.candywrapper.core.sidebar.SidebarSection.WORKING }.map { it.key }.toSet())

        // Off, as T3 ships it: no shelf, and the inbox keeps its order keys.
        val off = SidebarModel.partition(threads, null, now)
        assertTrue(off.working.isEmpty())
        assertEquals(5, off.active.size)
    }

    @Test
    fun workingSortsByLastSendAndTheInboxByReturn() {
        val sentLate = t("a", """$running,"latestUserAuthoredMessageAt":"2026-09-12T09:30:00.000Z","latestRunRequestedAt":"2026-09-12T09:00:00.000Z"""")
        // No authored stamp on an older server: the run request stands in.
        val requestedMid = t("b", """$running,"latestRunRequestedAt":"2026-09-12T09:10:00.000Z"""")
        // An authored stamp of null does not fall back.
        val neverSent = t("c", """$running,"latestUserAuthoredMessageAt":null,"latestRunRequestedAt":"2026-09-12T09:50:00.000Z"""")
        assertEquals(listOf("a", "b", "c"), SidebarModel.partition(listOf(neverSent, requestedMid, sentLate), null, now, workingShelf = true).working.map { it.id })

        val completedLate = t("x", """"latestRunId":"r","status":"completed","latestRunCompletedAt":"2026-09-12T09:40:00.000Z"""")
        val unsettled = t("y", """"latestRunId":"r","status":"completed","latestRunCompletedAt":"2026-09-12T09:00:00.000Z","unsettledAt":"2026-09-12T09:45:00.000Z"""")
        // Without the completed stamp, a finished run completes at updatedAt.
        val legacy = t("z", """"latestRunId":"r","status":"completed","updatedAt":"2026-09-12T09:20:00.000Z"""")
        val keyed = t("k", """"activeOrderKey":"a0"""")
        assertEquals(listOf("y", "x", "z", "k"), SidebarModel.partition(listOf(keyed, legacy, completedLate, unsettled), null, now, workingShelf = true).active.map { it.id })
        // A return this phone saw lifts a thread above the stamps.
        assertEquals(listOf("k", "y", "x", "z"), SidebarModel.partition(listOf(keyed, legacy, completedLate, unsettled), null, now, workingShelf = true) { if (it == "local:k") now else 0 }.active.map { it.id })
    }

    @Test
    fun inboxReturnsTakeABaselineThenMarkThreadsThatStopWorking() {
        val returns = InboxReturns()
        val busy = t("a", running)
        val idle = t("a", """"latestRunId":"r","status":"completed"""")
        // Turning the shelf on while a thread already finished does not count as a return.
        returns.observe(listOf(idle), now)
        assertEquals(0, returns.returnedAt("local:a"))
        returns.observe(listOf(busy), now + 1_000)
        assertEquals(0, returns.returnedAt("local:a"))
        returns.observe(listOf(idle), now + 2_000)
        assertEquals(now + 2_000, returns.returnedAt("local:a"))
        // A thread that leaves the list is forgotten; turning the shelf off resets everything.
        returns.observe(emptyList(), now + 3_000)
        assertEquals(0, returns.returnedAt("local:a"))
        returns.observe(listOf(busy), now + 4_000)
        returns.observe(null, now + 5_000)
        returns.observe(listOf(idle), now + 6_000)
        assertEquals(0, returns.returnedAt("local:a"))
    }

    @Test
    fun aClosedWorkingShelfKeepsTheOpenThread() {
        val sections = SidebarModel.partition(listOf(t("a", running), t("b", running)), null, now, workingShelf = true)
        assertEquals(listOf("local:b"), SidebarModel.visibleShelf(sections.working, expanded = false, openKey = "local:b").map { it.key })
        assertEquals(2, SidebarModel.visibleShelf(sections.working, expanded = true, openKey = null).size)
    }
}
