package org.anikodev.candywrapper.ui

import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sync.ConnectionState

/** Sample data for the screenshot tests: a fixed clock, three projects and threads in every status. */
object Fixtures {
    const val NOW = 1_792_000_000_000L
    private const val MIN = 60_000L

    private fun thread(id: String, project: String, title: String, pill: StatusPill = StatusPill.READY, ago: Long, unread: Boolean = false) =
        ThreadSummary(id, project, title, pill, unread, section = 1, orderKey = null, sortTime = NOW - ago * MIN, activityAt = NOW - ago * MIN,
            workingSince = if (pill == StatusPill.WORKING) NOW - 4 * MIN else 0)

    val projects = listOf(
        ProjectSummary("p1", "t3code", "/Users/anika/code/t3code"),
        ProjectSummary("p2", "CandyWrapper-Android", "/Users/anika/code/CandyWrapper-Android"),
        ProjectSummary("p3", "demo-repo", "/Users/anika/code/demo-repo"),
    )

    val threads = listOf(
        thread("t1", "p2", "Copy T3's sidebar into the phone app", StatusPill.WORKING, 1),
        thread("t2", "p2", "Approve the Gradle wrapper bump", StatusPill.APPROVAL, 6),
        thread("t3", "p2", "Why does resume reconnect on Android?", ago = 42, unread = true),
        thread("t4", "p2", "Profile the thread list scroll", ago = 180),
        thread("t5", "p1", "Pick a model for the composer", StatusPill.INPUT, 12),
        thread("t6", "p1", "Fix flicker in HTML renders", ago = 60 * 26),
        thread("t7", "p1", "Ship nightly signing", StatusPill.FAILED, 60 * 50),
        thread("t8", "p3", "Write a 700 word story about a lighthouse keeper", ago = 15),
        thread("t9", "p3", "Reply with markdown only, no tools", ago = 22),
        thread("t10", "p3", "Run exactly this shell command", ago = 60 * 3),
        thread("t11", "p3", "Create a file named approval-demo.txt", ago = 60 * 5),
        thread("t12", "p3", "Count the files in this repo", ago = 60 * 30),
        thread("t13", "p3", "Explain git worktrees", ago = 60 * 49),
        thread("t14", "p3", "Add notes and docstring", ago = 60 * 72),
    )

    val model = ThreadListModel.build(projects.associateBy { it.id }, threads)

    val live = ConnectionState.Live(route = "tunnel", connectMs = 312)

    val environments = listOf(
        SidebarEnvironment("e1", "anika’s MacBook Pro", EnvironmentKind.CONNECT, live, waiting = 2),
        SidebarEnvironment("e2", "mac-mini", EnvironmentKind.PAIRED, ConnectionState.Offline("Offline · retrying in 8 s", null)),
    )
}
