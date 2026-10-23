package org.anikodev.candywrapper.ui

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage

/** Window sizes the screenshots are taken at: a Pixel 7 class phone and a landscape tablet. */
const val PHONE = "w411dp-h891dp-xxhdpi"
const val TABLET = "w1280dp-h800dp-xhdpi"

/** Golden images live in app/src/test/snapshots so they show on GitHub; `recordRoborazziDebug` rewrites them. */
fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    captureRoboImage(
        "src/test/snapshots/$name.${if (dark) "dark" else "light"}.png",
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
    ) {
        CandyTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT) { content() }
    }
}
