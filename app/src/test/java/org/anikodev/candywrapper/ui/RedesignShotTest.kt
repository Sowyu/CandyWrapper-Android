package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Redesign (Experimental) in dark and light: the thread, the sidebar, the composer, Settings and the model picker. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RedesignShotTest {
    private val dir = Files.createTempDirectory("redesign").toFile()
    private val store = DitherArtStore(dir, dir, renderOnPeek = true)

    private fun redesignShot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        wallClock = { Fixtures.NOW }
        captureRoboImage(
            "src/test/snapshots/redesign/$name.${if (dark) "dark" else "light"}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        ) {
            CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, RedesignStyle(on = true)) {
                CompositionLocalProvider(LocalProjectIcons provides Fixtures.icons, LocalArtStore provides store, content = content)
            }
        }
    }

    private fun thread(dark: Boolean) = redesignShot("thread-phone", dark) {
        AppShell(DrawerState(open = false), sidebar = {}) { ThreadShotTest.ThreadScreenFixture() }
    }
    @Test fun threadLight() = thread(false)
    @Test fun threadDark() = thread(true)

    /** A run going: the working row, the queue as a floating card, the stash count and the machine row with Waiting. */
    private fun running(dark: Boolean) = redesignShot("running-phone", dark) {
        AppShell(DrawerState(open = false), sidebar = {}) {
            ThreadShotTest.ThreadScreenFixture(ThreadFixtures.state.copy(running = true, queued = ThreadFixtures.queued)) {
                QueuedList(ThreadFixtures.queued, canSteer = true, onSteer = {}, onCancel = {})
                Composer(
                    text = "", onText = {}, settings = ComposerSettings(ThreadFixtures.model, "full-access", "default"), server = ThreadFixtures.server,
                    running = true, canStop = true, files = emptyList(), busy = null, error = null, connected = true,
                    onModel = {}, onRuntimeMode = {}, onTogglePlan = null, onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = null, onSteer = null,
                    onStop = {}, onDismissError = {}, trayAbove = true, onStash = {}, stashCount = 3, onOpenStash = {},
                    footer = { DeviceRow("anika-mbp", true); WaitingPill("Waiting") { _, _ -> } },
                )
            }
        }
    }
    /** The new-thread composer over the hero art: attach, ⋯ and send, the model pill under it. */
    private fun newThread(dark: Boolean) = redesignShot("composer-new-thread-phone", dark) {
        AppShell(DrawerState(open = false), sidebar = {}) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { ThreadShotTest.NewThreadComposer() }
        }
    }
    @Test fun newThreadLight() = newThread(false)
    @Test fun newThreadDark() = newThread(true)

    @Test fun runningLight() = running(false)
    @Test fun runningDark() = running(true)

    private fun sidebar(dark: Boolean) = redesignShot("sidebar-phone", dark) {
        AppShell(
            DrawerState(open = true),
            sidebar = { SidebarPanel(Fixtures.environments[0], Fixtures.environments, Fixtures.data, "e1:t1", Fixtures.NOW, SidebarActions()) },
        ) { ThreadShotTest.ThreadScreenFixture() }
    }
    @Test fun sidebarLight() = sidebar(false)
    @Test fun sidebarDark() = sidebar(true)

    private fun tablet(dark: Boolean) = redesignShot("thread-tablet", dark) {
        AppShell(
            DrawerState(open = false),
            sidebar = { SidebarPanel(Fixtures.environments[0], Fixtures.environments, Fixtures.data, "e1:t1", Fixtures.NOW, SidebarActions()) },
        ) { ThreadShotTest.ThreadScreenFixture() }
    }
    @Config(qualifiers = TABLET) @Test fun tabletLight() = tablet(false)
    @Config(qualifiers = TABLET) @Test fun tabletDark() = tablet(true)

    private fun settings(dark: Boolean, name: String = "settings-phone") = redesignShot(name, dark) {
        AppShell(DrawerState(open = false), sidebar = {}) {
            ArtEmphasisEffect(ArtEmphasis.SETTINGS)
            Column(Modifier.fillMaxSize()) {
                TopBar("Settings", onBack = {})
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    GroupLabel("Appearance")
                    AppearanceSettings(ThemeChoice.SYSTEM, RedesignStyle(on = true), {}, {}, {}, {}, {}, {})
                    GroupLabel("Sidebar")
                    SidebarSettings(true) {}
                }
            }
        }
    }
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
    @Config(qualifiers = TABLET) @Test fun settingsTabletLight() = settings(false, "settings-tablet")
    @Config(qualifiers = TABLET) @Test fun settingsTabletDark() = settings(true, "settings-tablet")
}
