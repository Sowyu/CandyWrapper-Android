package org.anikodev.candywrapper.ui

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Redesign's glass: the thread scrolled back so its rows run under the Stash pill, the queue, a
 * composer holding two image pills and a file chip, and the machine row, all blurring what is behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RedesignGlassShotTest {
    @get:Rule val compose = createComposeRule()
    private val dir = Files.createTempDirectory("redesign").toFile()
    private val photo = redesignPhoto(dir)
    private val store = DitherArtStore(dir, dir, renderOnPeek = true)

    private val files = listOf(
        PickedFile(Uri.parse("file:///pastes/6019.png"), "6019.png", "image/png", 1),
        PickedFile(Uri.parse("file:///pastes/chart.png"), "chart.png", "image/png", 1),
        PickedFile(Uri.parse("file:///pastes/notes.pdf"), "notes.pdf", "application/pdf", 1),
    )

    private fun glass(dark: Boolean) {
        wallClock = { Fixtures.NOW }
        compose.setContent {
            CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, RedesignStyle(on = true, picture = photo)) {
                CompositionLocalProvider(LocalProjectIcons provides Fixtures.icons, LocalArtStore provides store, LocalImages provides ImageShotTest.FakeImages) {
                    AppShell(DrawerState(open = false), sidebar = {}) {
                        ThreadShotTest.ThreadScreenFixture(ThreadFixtures.state.copy(running = true, queued = ThreadFixtures.queued)) {
                            QueuedList(ThreadFixtures.queued, canSteer = true, onSteer = {}, onCancel = {})
                            Composer(
                                text = "Match this layout", onText = {}, settings = ComposerSettings(ThreadFixtures.model, "full-access", "default"),
                                server = ThreadFixtures.server, running = true, canStop = true, files = files, busy = null, error = null, connected = true,
                                onModel = {}, onRuntimeMode = {}, onTogglePlan = null, onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = {}, onSteer = {},
                                onStop = {}, onDismissError = {}, trayAbove = true, onStash = {}, stashCount = 3, onOpenStash = {}, onPaste = {},
                                footer = { DeviceRow("anika-mbp", true); WaitingPill("Waiting") { _, _ -> } },
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        // Back through the history, so the newest rows slide down under the stack.
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.55f, durationMillis = 400) }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        captureScreenRoboImage(
            "src/test/snapshots/redesign/glass-phone.${if (dark) "dark" else "light"}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        )
    }

    @Test fun glassLight() = glass(false)
    @Test fun glassDark() = glass(true)
}
