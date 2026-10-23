package org.anikodev.candywrapper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import org.anikodev.candywrapper.core.media.SvgLite
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.ProjectIconOverride
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.sidebar.SidebarModel
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.data.ProjectIconSource
import org.anikodev.candywrapper.data.ProjectIconStore

/**
 * Sample data for the screenshot tests: a fixed clock, two machines that share the t3code
 * repository, and threads in every status and section.
 */
object Fixtures {
    const val NOW = 1_792_000_000_000L
    private const val MIN = 60_000L
    private const val MAC = "e1"
    private const val MINI = "e2"

    private fun thread(
        id: String, project: String, title: String, pill: StatusPill = StatusPill.READY, ago: Long, unread: Boolean = false,
        env: String = MAC, section: Int = 1, branch: String? = null, snoozedFor: Long = 0, provider: String = "claudeAgent",
    ) = ThreadSummary(
        id, project, title, pill, unread, section = section, orderKey = null, sortTime = NOW - ago * MIN, activityAt = NOW - ago * MIN,
        workingSince = if (pill == StatusPill.WORKING) NOW - 4 * MIN else 0, envId = env, branch = branch, providerInstanceId = provider,
        snoozedUntil = if (snoozedFor != 0L) NOW + snoozedFor * MIN else 0, visitedAt = NOW - (ago + 60) * MIN,
        returnTime = NOW - ago * MIN, sendTime = NOW - ago * MIN,
    )

    private const val T3 = "github.com/pingdotgg/t3code"

    val projects = listOf(
        ProjectSummary("p1", "t3code", "/Users/anika/code/t3code", envId = MAC, repoKey = T3, repoShortName = "t3code"),
        ProjectSummary("p2", "CandyWrapper-Android", "/Users/anika/code/CandyWrapper-Android", envId = MAC),
        ProjectSummary("p3", "demo-repo", "/Users/anika/code/demo-repo", envId = MAC),
    )

    val miniProjects = listOf(
        ProjectSummary("q1", "t3code", "/home/anika/t3code", envId = MINI, repoKey = T3, repoShortName = "t3code"),
        ProjectSummary("q2", "homelab", "/home/anika/homelab", envId = MINI, icon = ProjectIconOverride(ProjectIconOverride.Kind.MONOGRAM, "HL", "emerald")),
    )

    /**
     * Icon files as the store would hold them: t3code's an SVG drawn through [SvgLite], the app's
     * a bitmap. demo-repo has none and keeps its monogram; homelab chose a monogram.
     */
    val icons = object : ProjectIconSource {
        private val t3 by lazy {
            ProjectIconStore.rasterize(SvgLite.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" fill="#0a0a0a"/>""" +
                    """<path d="M7 10h10v3h-3.5v10h-3V13H7z" fill="#fff"/>""" +
                    """<path d="M19.5 10.5h5l-3 4.5a4 4 0 1 1-3 6" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>""",
            )!!)!!.asImageBitmap()
        }
        private val app by lazy {
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(b)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFF7A3E6E.toInt(); canvas.drawRoundRect(0f, 0f, 96f, 96f, 20f, 20f, paint)
            paint.color = 0xFFFFD6EC.toInt(); canvas.drawCircle(48f, 48f, 24f, paint)
            b.asImageBitmap()
        }

        override fun peek(p: ProjectSummary) = when (p.title) {
            "t3code" -> t3
            "CandyWrapper-Android" -> app
            else -> null
        }

        override suspend fun refresh(p: ProjectSummary) = Unit
    }

    val threads = listOf(
        thread("t1", "p2", "Copy T3's sidebar into the phone app", StatusPill.WORKING, 1, branch = "feat/sidebar-v2"),
        thread("t2", "p2", "Approve the Gradle wrapper bump", StatusPill.APPROVAL, 6, section = 0),
        thread("t3", "p2", "Why does resume reconnect on Android?", ago = 42, unread = true, provider = "codex"),
        thread("t4", "p2", "Profile the thread list scroll", ago = 180, section = 3),
        thread("t5", "p1", "Pick a model for the composer", StatusPill.INPUT, 12, branch = "main"),
        thread("t6", "p1", "Fix flicker in HTML renders", ago = 60 * 26, section = 3),
        thread("t7", "p1", "Ship nightly signing", StatusPill.FAILED, 60 * 50),
        thread("t8", "p3", "Write a 700 word story about a lighthouse keeper", ago = 15),
        thread("t9", "p3", "Reply with markdown only, no tools", ago = 22, snoozedFor = 125),
        thread("t10", "p3", "Run exactly this shell command", ago = 60 * 3, snoozedFor = 60 * 20),
        thread("t11", "p3", "Create a file named approval-demo.txt", ago = 60 * 5, snoozedFor = -30),
        thread("t12", "p3", "Count the files in this repo", ago = 60 * 30, section = 3),
        thread("t13", "p3", "Explain git worktrees", ago = 60 * 49, section = 3),
        thread("t14", "p3", "Add notes and docstring", ago = 60 * 72, section = 3),
    )

    val miniThreads = listOf(
        thread("u1", "q1", "Bump Node on the mini", StatusPill.WORKING, 3, env = MINI, branch = "chore/node-24"),
        thread("u2", "q2", "Rotate the backup keys", ago = 30, unread = true, env = MINI, provider = "codex"),
        thread("u3", "q1", "Reproduce the relay timeout", ago = 60 * 8, env = MINI, section = 3),
    )

    /** One machine, as the legacy tree draws it. */
    val model = ThreadListModel.build(projects.associateBy { it.id }, threads, NOW)

    /** One machine in the new sidebar. */
    val data = SidebarModel.build(projects, threads, null, NOW)

    /** Both machines together. */
    val allData = SidebarModel.build(projects + miniProjects, threads + miniThreads, null, NOW)

    /** Both machines with Settings > Working section on. */
    val workingData = SidebarModel.build(projects + miniProjects, threads + miniThreads, null, NOW, workingShelf = true)

    val allModel = ThreadListModel.merge(listOf("anika’s MacBook Pro" to model, "mac-mini" to ThreadListModel.build(miniProjects.associateBy { it.id }, miniThreads, NOW)))

    val live = ConnectionState.Live(route = "tunnel", connectMs = 312)

    private val caps = MachineCaps(settle = true, snooze = true, pin = true, drivers = mapOf("claudeAgent" to "claudeAgent", "codex" to "codex"))

    val environments = listOf(
        SidebarEnvironment(MAC, "anika’s MacBook Pro", EnvironmentKind.CONNECT, live, waiting = 2, caps = caps),
        SidebarEnvironment(MINI, "mac-mini", EnvironmentKind.PAIRED, ConnectionState.Live(route = "lan", connectMs = 41), caps = caps),
    )

    val all = SidebarEnvironment.all(environments)
}
