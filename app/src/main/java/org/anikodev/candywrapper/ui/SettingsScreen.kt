package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.BuildConfig
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.update.UpdateSettingsRow

/** Connections, the T3 Connect account, the theme, and the way to the timing report. */
@Composable
fun SettingsScreen(graph: AppGraph, nav: Navigator) {
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    val account by graph.account.collectAsStateWithLifecycle()
    val theme by graph.theme.collectAsStateWithLifecycle()
    val c = Candy.colors
    var selected by remember { mutableStateOf<EnvironmentSession?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Settings", onBack = if (nav.stack.size > 1) ({ nav.pop() }) else null)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                GroupLabel("Computers")
                Card {
                    for ((i, s) in sessions.withIndex()) {
                        if (i > 0) Divider()
                        ConnectionRow(s) { selected = s; confirmRemove = false }
                    }
                    if (sessions.isNotEmpty()) Divider()
                    ListRow("Pair a computer", icon = Icons.Plus, detail = "Paste the link from `npx t3 pair`") { nav.push(Screen.Pair()) }
                }

                GroupLabel("T3 Connect")
                Card {
                    ListRow(
                        if (account == null) "Sign in" else account?.identifier ?: "Signed in",
                        icon = Icons.Cloud,
                        detail = if (account == null) "Reach the computers linked to your account" else "Manage linked computers or sign out",
                    ) { nav.push(Screen.Connect) }
                }

                GroupLabel("Appearance")
                Card {
                    for ((i, choice) in ThemeChoice.entries.withIndex()) {
                        if (i > 0) Divider()
                        ListRow(
                            when (choice) { ThemeChoice.SYSTEM -> "Match the system"; ThemeChoice.LIGHT -> "Light"; ThemeChoice.DARK -> "Dark" },
                            icon = when (choice) { ThemeChoice.SYSTEM -> Icons.Smartphone; ThemeChoice.LIGHT -> Icons.Sun; ThemeChoice.DARK -> Icons.Moon },
                            selected = choice == theme,
                        ) { graph.setTheme(choice) }
                    }
                }

                GroupLabel("App")
                Card {
                    ListRow("Timing", icon = Icons.Activity, detail = "Start, connect and thread-open steps in milliseconds") { nav.push(Screen.Diagnostics) }
                    Divider()
                    Box(Modifier.padding(horizontal = 4.dp)) { UpdateSettingsRow(graph.updater) }
                }
                Txt("CandyWrapper ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", CandyType.meta, c.mutedForeground,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp))
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp))
            }
        }

        selected?.let { s ->
            val env = s.env.value
            Sheet({ selected = null }, env.label) {
                ListRow("Open threads", icon = Icons.ChevronRight) { selected = null; nav.reset(Screen.Home(s.id)) }
                ListRow("Reconnect now", icon = Icons.RefreshCw) { selected = null; s.wake(); s.networkChanged() }
                if (confirmRemove) {
                    Txt(
                        if (env.kind == EnvironmentKind.CONNECT) "This forgets the computer and its saved threads on this phone. It comes back while you stay signed in to T3 Connect."
                        else "This forgets the computer, its saved threads and this phone's access. Pair it again to come back.",
                        CandyType.small, c.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = ButtonRow) {
                        CButton("Remove", kind = ButtonKind.DESTRUCTIVE) {
                            selected = null
                            scope.launch { graph.environments.remove(s.id) }
                        }
                        CButton("Keep", kind = ButtonKind.GHOST) { confirmRemove = false }
                    }
                } else {
                    ListRow("Remove from this phone", icon = Icons.Trash2, labelColor = c.error) { confirmRemove = true }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(session: EnvironmentSession, onClick: () -> Unit) {
    val env by session.env.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val routes = env.directRoutes.joinToString { it.kind }
    ListRow(
        env.label,
        icon = Icons.Laptop,
        detail = listOfNotNull(
            if (env.kind == EnvironmentKind.CONNECT) "T3 Connect" else "Paired",
            state.label(),
            routes.ifEmpty { null }?.let { "direct: $it" },
        ).joinToString(" · "),
        trailing = { StatusDot(state.color()) },
        onClick = onClick,
    )
}
