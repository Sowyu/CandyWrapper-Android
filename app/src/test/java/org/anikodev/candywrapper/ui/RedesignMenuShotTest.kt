package org.anikodev.candywrapper.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.nio.file.Files
import org.anikodev.candywrapper.core.model.UsageWindow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Redesign's model picker with the provider card, over a thread. Popups draw in their own window, so these capture the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RedesignMenuShotTest {
    @get:Rule val compose = createComposeRule()
    private val dir = Files.createTempDirectory("redesign").toFile()
    private val store = DitherArtStore(dir, dir, renderOnPeek = true)

    private val server = ThreadFixtures.server.copy(
        providers = ThreadFixtures.server.providers.mapIndexed { i, p ->
            if (i == 0) p.copy(authEmail = "anika@example.com", authLabel = "Claude Max", usage = UsageWindow("Weekly", 62)) else p
        },
    )

    private fun picker(dark: Boolean, name: String = "model-picker-phone") {
        wallClock = { Fixtures.NOW }
        compose.setContent {
            CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, RedesignStyle(on = true)) {
                CompositionLocalProvider(LocalProjectIcons provides Fixtures.icons, LocalArtStore provides store) {
                    AppShell(DrawerState(open = false), sidebar = {}) {
                        ThreadShotTest.ThreadScreenFixture {
                            Composer(
                                text = "", onText = {}, settings = ComposerSettings(ThreadFixtures.model, "full-access", "default"), server = server,
                                running = false, canStop = false, files = emptyList(), busy = null, error = null, connected = true,
                                onModel = {}, onRuntimeMode = {}, onTogglePlan = null, onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = null, onSteer = null,
                                onStop = {}, onDismissError = {}, initialMenu = Picker.MODEL, onStash = {},
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        captureScreenRoboImage(
            "src/test/snapshots/redesign/$name.${if (dark) "dark" else "light"}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        )
    }

    @Test fun pickerLight() = picker(false)
    @Test fun pickerDark() = picker(true)
    @Config(qualifiers = TABLET) @Test fun pickerTabletLight() = picker(false, "model-picker-tablet")
    @Config(qualifiers = TABLET) @Test fun pickerTabletDark() = picker(true, "model-picker-tablet")
}
