package org.anikodev.candywrapper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.nio.file.Files
import java.time.ZoneOffset
import org.anikodev.candywrapper.core.model.HtmlPage
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.orchestrator.BotColor
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.BotMood
import org.anikodev.candywrapper.core.orchestrator.BotShape
import org.anikodev.candywrapper.core.orchestrator.CrewCard
import org.anikodev.candywrapper.core.orchestrator.OrchestratorBrief
import org.anikodev.candywrapper.core.orchestrator.OrchestratorChat
import org.anikodev.candywrapper.core.orchestrator.OrchestratorNeed
import org.anikodev.candywrapper.orchestrator.OrchestratorUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Orchestrator in Redesign, dark and light: the pinned sidebar row, the chat, the Overview and Needs you. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class OrchestratorShotTest {
    private val dir = Files.createTempDirectory("orchestrator").toFile()
    private val photo = redesignPhoto(dir)
    private val store = DitherArtStore(dir, dir, renderOnPeek = true)
    private val bot = BotIdentity.of(BotShape.FLOWER, BotColor.VIOLET)

    private fun redesignShot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        wallClock = { Fixtures.NOW }
        // Bots work out their poses off the main thread; here they are ready before the first frame.
        for (mood in BotMood.entries) for (px in listOf(24f, 28f, 30f).map { it * 3f }) warmBotPoses(bot, mood, px)
        captureRoboImage(
            "src/test/snapshots/redesign/$name.${if (dark) "dark" else "light"}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        ) {
            CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, RedesignStyle(on = true, picture = photo)) {
                CompositionLocalProvider(LocalProjectIcons provides Fixtures.icons, LocalArtStore provides store, content = content)
            }
        }
    }

    private val needs = listOf(
        OrchestratorNeed("e1", "t2", "Fix the flaky sidebar test", "r1", "Command approval", isApproval = true),
        OrchestratorNeed("e1", "t3", "Release notes", "r2", "Has a question for you", isApproval = false),
    )

    private val crew = listOf(
        CrewCard("e1", "w1", "Fixer: repairs the sidebar test", "CandyWrapper", "Working", StatusPill.WORKING, "Running scripts/test --filter Sidebar", null, CrewCard.Group.RUNNING),
        CrewCard("e1", "t2", "Fix the flaky sidebar test", "CandyWrapper", "Needs you", StatusPill.APPROVAL, "Wants to run ./gradlew :core:test", "You wrote 3 min ago, so it asks first", CrewCard.Group.RUNNING),
        CrewCard("e1", "w2", "Scribe: release notes", "Website", "Unread", StatusPill.READY, "STATUS done. CHANGED docs/release.md", null, CrewCard.Group.FINISHED),
        CrewCard("e1", "t5", "Try the new relay", "CandyWrapper", "Done", StatusPill.READY, "Merged into main.", "Hands off", CrewCard.Group.FINISHED),
        CrewCard("e1", "t6", "Profile cold start", "CandyWrapper-Android", "Failed", StatusPill.FAILED, "Usage limit reached for gpt-6.1-sol", null, CrewCard.Group.STOPPED),
    )

    private val ui = OrchestratorUi(
        active = true, name = "Orchestrator", bot = bot, envId = "e1", machine = "anika-mbp", connected = true, threadId = "orch",
        working = true, needs = needs, crew = crew, relaying = true,
    )

    private fun row(id: String, type: String, text: String, minutes: Int, run: String? = null, title: String = type, page: HtmlPage? = null) =
        TurnRow(id, type, 0, "completed", run, title, text, atMs = Fixtures.NOW - 60 * 60_000 + minutes * 60_000L, htmlPage = page)

    private val rows = listOf(
        row("m0", "user_message", OrchestratorBrief.first("You are Orchestrator, the user's manager for every agent thread on this T3 server (anika-mbp)."), 0),
        row("m1", "user_message", "Get the sidebar test green and write the release notes.", 1),
        row("m2", "user_message", "Ask before you merge anything.", 1),
        row("t1", "dynamic_tool", "", 2, run = "r1", title = "t3_thread_list"),
        row("t2", "dynamic_tool", "", 2, run = "r1", title = "html_render", page = HtmlPage("p1", "Flaky test runs this week")),
        row("m3", "assistant_message", "Two steps. I'll start both, they touch different files.\n\n```orchestrator-plan\n1. [x] Find the flake ([Fixer](t3-thread://v1/w1)), Everyday work, 10 min\n2. Release notes ([Scribe](t3-thread://v1/w2)), Quick changes, 5 min\n```\n```orchestrator-memory\nnote: rules\nAsk before merging anything.\n```", 3, run = "r1"),
        row("m4", "user_message", "Event: “Scribe: release notes” finished a turn (t3-thread://v1/w2).\nEvent: “Fix the flaky sidebar test” is waiting on an approval or a question (t3-thread://v1/t2). The user wrote there 3 min ago, so ask first.", 40),
        row("m5", "user_message", "Result: read “Book thread” on MacBook", 41),
        row("m6", "assistant_message", "Scribe is done: **docs/release.md** has the notes. *Fix the flaky sidebar test* wants to run `./gradlew :core:test`. You wrote there 3 minutes ago, so it's yours to allow.", 42, run = "r2"),
    )

    private fun pane(name: String, tab: PaneTab, dark: Boolean) = redesignShot(name, dark) {
        AppShell(DrawerState(open = false), sidebar = {}) {
            val items = OrchestratorChat.items(rows, running = true, nowMs = Fixtures.NOW, zone = ZoneOffset.UTC)
            OrchestratorPanel(OrchestratorPaneState(ui, items, undoable = setOf("m3"), draft = ""), tab, {}, PaneActions())
        }
    }

    @Test fun chatLight() = pane("orchestrator-chat", PaneTab.CHAT, false)
    @Test fun chatDark() = pane("orchestrator-chat", PaneTab.CHAT, true)
    @Test fun overviewLight() = pane("orchestrator-overview", PaneTab.OVERVIEW, false)
    @Test fun overviewDark() = pane("orchestrator-overview", PaneTab.OVERVIEW, true)
    @Test fun needsLight() = pane("orchestrator-needs", PaneTab.NEEDS, false)
    @Test fun needsDark() = pane("orchestrator-needs", PaneTab.NEEDS, true)

    private fun sidebar(dark: Boolean) = redesignShot("orchestrator-sidebar", dark) {
        AppShell(
            DrawerState(open = true),
            sidebar = {
                SidebarPanel(Fixtures.environments[0], Fixtures.environments, Fixtures.data, "e1:t1", Fixtures.NOW, SidebarActions(),
                    orchestrator = OrchestratorRowInfo("Orchestrator", "anika-mbp", bot, needsYou = 2, working = false))
            },
        ) { ThreadShotTest.ThreadScreenFixture() }
    }
    @Test fun sidebarLight() = sidebar(false)
    @Test fun sidebarDark() = sidebar(true)
}
