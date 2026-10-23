package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Popups draw in their own window, so these capture the whole screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class MenuShotTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT) {
                Box(Modifier.fillMaxSize().background(Candy.colors.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        captureScreenRoboImage(
            "src/test/snapshots/$name.${if (dark) "dark" else "light"}.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        )
    }

    @Composable
    private fun ComposerWith(menu: Picker) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Composer(
            text = "", onText = {}, settings = ComposerSettings(ThreadFixtures.model, "full-access", "default"), server = ThreadFixtures.server,
            running = false, canStop = false, files = emptyList(), busy = null, error = null, connected = true,
            onModel = {}, onRuntimeMode = {}, onTogglePlan = {}, onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = null, onSteer = null,
            onStop = {}, onDismissError = {}, initialMenu = menu,
        )
    }

    @Test fun accessMenuLight() = screen("menu-access-phone", false) { ComposerWith(Picker.ACCESS) }
    @Test fun accessMenuDark() = screen("menu-access-phone", true) { ComposerWith(Picker.ACCESS) }
    @Test fun modelMenuLight() = screen("menu-model-phone", false) { ComposerWith(Picker.MODEL) }
    @Test fun modelMenuDark() = screen("menu-model-phone", true) { ComposerWith(Picker.MODEL) }

    @Composable
    private fun Actions() = Column(Modifier.padding(top = 60.dp, start = 140.dp)) {
        Menu(true, {}, minWidth = 220.dp) {
            MenuItem("Rename thread", Icons.Pencil) {}
            MenuItem("Refresh", Icons.RefreshCw, detail = "Subscribe again from the last event seen") {}
            MenuItem("Mark unread", Icons.EyeOff) {}
            MenuSeparator()
            MenuItem("Archive", Icons.Archive) {}
            MenuItem("Delete", Icons.Trash2, destructive = true) {}
        }
    }

    @Test fun actionsLight() = screen("menu-thread-actions-phone", false) { Actions() }
    @Test fun actionsDark() = screen("menu-thread-actions-phone", true) { Actions() }

    private fun rename(dark: Boolean) = screen("dialog-rename-phone", dark) {
        Box(Modifier.fillMaxSize()) { RenameSheet("Copy T3's sidebar into the phone app", {}) {} }
    }
    @Test fun renameLight() = rename(false)
    @Test fun renameDark() = rename(true)
}
