package org.anikodev.candywrapper.ui

import androidx.compose.runtime.Composable
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Full screens: the thread with its composer, the approval and question trays, the sidebar open, and a tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ThreadShotTest {
    @Composable
    private fun Thread(
        state: ThreadSession.State = ThreadFixtures.state,
        bottom: @Composable () -> Unit = { ThreadComposer() },
    ) {
        ThreadView(
            title = state.title!!, project = "CandyWrapper-Android", subtitle = null, subtitleError = false,
            state = state, host = ThreadFixtures.host(), workingSince = Fixtures.NOW - 4 * 60_000, now = Fixtures.NOW, working = false,
            onLoadOlder = {},
        ) { bottom() }
    }

    @Composable
    private fun ThreadComposer(text: String = "", locked: String? = null, running: Boolean = false, tray: Boolean = false) = Composer(
        text = text, onText = {}, settings = ComposerSettings(ThreadFixtures.model, "full-access", "default"), server = ThreadFixtures.server,
        running = running, canStop = running, files = emptyList(), busy = null, error = null, connected = true,
        onModel = {}, onRuntimeMode = {}, onTogglePlan = {}, onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = null, onSteer = null,
        onStop = {}, onDismissError = {}, locked = locked, trayAbove = tray,
    )

    private fun thread(dark: Boolean) = shot("thread-phone", dark) { Thread() }
    @Test fun threadLight() = thread(false)
    @Test fun threadDark() = thread(true)

    private fun approval(dark: Boolean) = shot("approval-phone", dark) {
        Thread {
            ApprovalPanel(ThreadFixtures.approval, busy = false) {}
            ThreadComposer(locked = APPROVAL_LOCK, tray = true)
        }
    }
    @Test fun approvalLight() = approval(false)
    @Test fun approvalDark() = approval(true)

    private fun question(dark: Boolean) = shot("question-phone", dark) {
        Thread {
            QuestionPanel(ThreadFixtures.question, busy = false, onAnswer = {}, onDismiss = {})
            ThreadComposer(locked = "Answer the question above to continue", tray = true)
        }
    }
    @Test fun questionLight() = question(false)
    @Test fun questionDark() = question(true)

    private fun running(dark: Boolean) = shot("running-phone", dark) {
        Thread(ThreadFixtures.state.copy(running = true, queued = ThreadFixtures.queued)) {
            QueuedList(ThreadFixtures.queued, canSteer = true, onSteer = {}, onCancel = {})
            ThreadComposer(running = true, tray = true)
        }
    }
    @Test fun runningLight() = running(false)
    @Test fun runningDark() = running(true)

    private fun drawer(dark: Boolean) = shot("sidebar-open-phone", dark) {
        AppShell(
            DrawerState(open = true),
            sidebar = {
                SidebarPanel(Fixtures.environments[0], Fixtures.environments, Fixtures.model, "t1", Fixtures.NOW, SidebarActions())
            },
        ) { Thread() }
    }
    @Test fun drawerLight() = drawer(false)
    @Test fun drawerDark() = drawer(true)

    private fun tablet(dark: Boolean) = shot("thread-tablet", dark) {
        AppShell(
            DrawerState(open = false),
            sidebar = {
                SidebarPanel(Fixtures.environments[0], Fixtures.environments, Fixtures.model, "t1", Fixtures.NOW, SidebarActions())
            },
        ) { Thread() }
    }
    @Config(qualifiers = TABLET) @Test fun tabletLight() = tablet(false)
    @Config(qualifiers = TABLET) @Test fun tabletDark() = tablet(true)
}

