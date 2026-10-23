package org.anikodev.candywrapper.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.anikodev.candywrapper.BuildConfig
import org.anikodev.candywrapper.ui.ButtonRow
import org.anikodev.candywrapper.ui.Candy

/**
 * A card announcing a newer nightly, with its install button and progress. Draws nothing when this
 * build is current or the user chose Later. Place it over the bottom of the screen; it pads itself
 * above the navigation bar. Tapping the text shows the commit subjects.
 */
@Composable
fun UpdateBanner(updater: Updater, modifier: Modifier = Modifier) {
    val s by updater.state.collectAsStateWithLifecycle()
    val m = s.available ?: return
    if (s.step == UpdateStep.IDLE && s.installError == null && m.versionCode == s.dismissedCode) return
    var notes by rememberSaveable { mutableStateOf(false) }
    val c = Candy.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()
            .background(c.card, shape).border(1.dp, c.border, shape)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = ButtonRow) {
            Column(Modifier.weight(1f).clickable { notes = !notes }) {
                Text("Update ${m.versionName}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.foreground)
                val line = s.installError ?: when (s.step) {
                    UpdateStep.IDLE -> if (notes) "Hide changes" else "${m.notes.size} changes · tap to see them"
                    UpdateStep.NEEDS_PERMISSION -> "Allow installs from CandyWrapper, then come back"
                    UpdateStep.DOWNLOADING -> "Downloading ${s.percent}%"
                    UpdateStep.INSTALLING -> "Installing. Android closes the app when done; open it again."
                }
                Text(line, fontSize = 12.sp, color = if (s.installError != null) c.errorForeground else c.mutedForeground, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when (s.step) {
                UpdateStep.IDLE -> {
                    TextButton(onClick = updater::dismiss) { Text("Later", color = c.mutedForeground) }
                    Button(onClick = updater::install) { Text(if (s.installError != null) "Retry" else "Install") }
                }
                UpdateStep.NEEDS_PERMISSION -> Button(onClick = updater::install) { Text("Allow") }
                UpdateStep.DOWNLOADING, UpdateStep.INSTALLING -> Unit
            }
        }
        if (s.step == UpdateStep.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { s.percent / 100f }, color = c.primary, trackColor = c.muted,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
            )
        }
        if (notes) {
            m.notes.forEach { Text("· $it", fontSize = 12.sp, color = c.mutedForeground, modifier = Modifier.padding(top = 4.dp, end = 8.dp)) }
        }
    }
}

/** The installed version and commit, the last check's result, and a Check for updates button. */
@Composable
fun UpdateSettingsRow(updater: Updater, modifier: Modifier = Modifier) {
    val s by updater.state.collectAsStateWithLifecycle()
    val c = Candy.colors
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("CandyWrapper ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = c.foreground)
            val status = when {
                s.checking -> "Checking for updates"
                s.checkError != null -> "Check failed: ${s.checkError}"
                s.available != null -> "${s.available?.versionName} is available"
                s.checkedAtMs > 0 -> "Up to date"
                else -> "Not checked yet"
            }
            Text("Commit ${BuildConfig.COMMIT} · $status", fontSize = 12.sp, color = if (s.checkError != null) c.errorForeground else c.mutedForeground)
        }
        if (s.available != null && s.step == UpdateStep.IDLE) {
            TextButton(onClick = updater::install) { Text("Install", color = c.primary) }
        } else {
            TextButton(onClick = updater::checkNow, enabled = !s.checking) { Text("Check for updates", color = c.primary) }
        }
    }
}
