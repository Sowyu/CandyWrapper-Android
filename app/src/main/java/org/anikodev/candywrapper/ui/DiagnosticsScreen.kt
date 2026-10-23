package org.anikodev.candywrapper.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.BuildConfig
import org.anikodev.candywrapper.core.timing.Timeline

/**
 * The timing marks since process start, and each environment's state, as text the user can copy
 * into a bug report. Marks also go to logcat with tag CWTiming.
 */
@Composable
fun DiagnosticsScreen(graph: AppGraph, nav: Navigator) {
    val version by Timeline.version.collectAsStateWithLifecycle()
    val marks = remember(version) { Timeline.snapshot() }
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val c = Candy.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Timing", onBack = { nav.pop() }, subtitle = "ms since process start") {
            BarAction("Copy") {
                val report = buildString {
                    appendLine("CandyWrapper ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                    sessions.forEach { appendLine("${it.env.value.label} [${it.env.value.kind}]: ${it.state.value.label()}, routes: ${it.env.value.directRoutes.joinToString { r -> r.kind }.ifEmpty { "none" }}") }
                    appendLine()
                    marks.forEach { appendLine(it.format()) }
                }
                clipboard.setText(AnnotatedString(report))
            }
            BarAction("Clear") { Timeline.clear() }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp)) {
            items(sessions, key = { "env:" + it.id }) { s ->
                Text("${s.env.value.label}: ${s.state.value.label()}", fontSize = 12.sp, color = c.foreground, modifier = Modifier.padding(vertical = 4.dp))
            }
            items(marks.size, key = { "mark:$it:${marks[it].atMs}" }) { i ->
                Text(marks[i].format(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.mutedForeground,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
