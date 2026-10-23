package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

    private fun settings(dark: Boolean) = redesignShot("settings-phone", dark) {
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
}
