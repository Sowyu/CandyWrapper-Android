package org.anikodev.candywrapper.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.anikodev.candywrapper.BuildConfig
import org.anikodev.candywrapper.ui.ButtonKind
import org.anikodev.candywrapper.ui.ButtonSize
import org.anikodev.candywrapper.ui.CButton
import org.anikodev.candywrapper.ui.Candy
import org.anikodev.candywrapper.ui.CandyType
import org.anikodev.candywrapper.ui.Icon
import org.anikodev.candywrapper.ui.Icons
import org.anikodev.candywrapper.ui.Radius
import org.anikodev.candywrapper.ui.Txt

/**
 * A card announcing a newer nightly, after T3's update toast: a popover card with the version,
 * what it changes on tap, and Install. Draws nothing when this build is current or the user chose
 * Later. Place it over the bottom of the screen; it pads itself above the navigation bar.
 */
@Composable
fun UpdateBanner(updater: Updater, modifier: Modifier = Modifier) {
    val s by updater.state.collectAsStateWithLifecycle()
    val m = s.available ?: return
    if (s.step == UpdateStep.IDLE && s.installError == null && m.versionCode == s.dismissedCode) return
    var notes by rememberSaveable { mutableStateOf(false) }
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 480.dp).fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape).background(c.popover).border(1.dp, c.border, shape)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.ArrowDown, c.infoForeground, 16.dp)
            Column(Modifier.weight(1f).padding(start = 4.dp).clickable { notes = !notes }.padding(vertical = 6.dp)) {
                Txt("Update ${m.versionName}", CandyType.smMedium, c.foreground, maxLines = 1)
                val line = s.installError ?: when (s.step) {
                    UpdateStep.IDLE -> if (notes) "Hide changes" else "${m.notes.size} changes · tap to see them"
                    UpdateStep.NEEDS_PERMISSION -> "Allow installs from CandyWrapper, then come back"
                    UpdateStep.DOWNLOADING -> "Downloading ${s.percent}%"
                    UpdateStep.INSTALLING -> "Installing. Android closes the app when done; open it again."
                }
                Txt(line, CandyType.xs, if (s.installError != null) c.errorForeground else c.mutedForeground, maxLines = 3)
            }
            when (s.step) {
                UpdateStep.IDLE -> {
                    CButton("Later", kind = ButtonKind.GHOST, size = ButtonSize.SM, onClick = updater::dismiss)
                    CButton(if (s.installError != null) "Retry" else "Install", kind = ButtonKind.PRIMARY, size = ButtonSize.SM, onClick = updater::install)
                }
                UpdateStep.NEEDS_PERMISSION -> CButton("Allow", kind = ButtonKind.PRIMARY, size = ButtonSize.SM, onClick = updater::install)
                UpdateStep.DOWNLOADING, UpdateStep.INSTALLING -> Unit
            }
        }
        if (s.step == UpdateStep.DOWNLOADING) {
            // Real download progress, redrawn only when the percentage changes.
            Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp, end = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent)) {
                Box(Modifier.fillMaxWidth(s.percent / 100f).fillMaxHeight().background(c.primary))
            }
        }
        if (notes) {
            m.notes.forEach { Txt("· $it", CandyType.xs, c.mutedForeground, modifier = Modifier.padding(bottom = 4.dp, end = 8.dp)) }
        }
    }
}

/** The installed version and commit, the last check's result, and a Check for updates button. */
@Composable
fun UpdateSettingsRow(updater: Updater, modifier: Modifier = Modifier) {
    val s by updater.state.collectAsStateWithLifecycle()
    val c = Candy.colors
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt("CandyWrapper ${BuildConfig.VERSION_NAME}", CandyType.base, c.foreground)
            val status = when {
                s.checking -> "Checking for updates"
                s.checkError != null -> "Check failed: ${s.checkError}"
                s.available != null -> "${s.available?.versionName} is available"
                s.checkedAtMs > 0 -> "Up to date"
                else -> "Not checked yet"
            }
            Txt("Commit ${BuildConfig.COMMIT} · $status", CandyType.sm, if (s.checkError != null) c.errorForeground else c.mutedForeground)
        }
        if (s.available != null && s.step == UpdateStep.IDLE) {
            CButton("Install", kind = ButtonKind.PRIMARY, size = ButtonSize.SM, onClick = updater::install)
        } else {
            CButton("Check for updates", size = ButtonSize.SM, enabled = !s.checking, onClick = updater::checkNow)
        }
    }
}
