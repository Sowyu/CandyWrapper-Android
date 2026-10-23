package org.anikodev.candywrapper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.sync.EnvironmentSession

@Composable
fun EnvironmentsScreen(graph: AppGraph, nav: Navigator) {
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    val account by graph.account.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopBar("CandyWrapper") {
            BarAction("Pair") { nav.push(Screen.Pair()) }
            BarAction(if (account == null) "T3 Connect" else "Account") { nav.push(Screen.Connect) }
            BarAction("Timing") { nav.push(Screen.Diagnostics) }
        }
        if (sessions.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No computers yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Candy.colors.foreground)
                Text("Run `npx t3 pair` on the computer running T3 Code and paste the link, or sign in to T3 Connect to see your linked computers.",
                    color = Candy.colors.mutedForeground)
                Row(horizontalArrangement = ButtonRow) {
                    Button(onClick = { nav.push(Screen.Pair()) }) { Text("Pair a computer") }
                    OutlinedButton(onClick = { nav.push(Screen.Connect) }) { Text("T3 Connect") }
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(sessions, key = { it.id }) { session ->
                EnvironmentRow(graph, session) {
                    graph.rememberEnvironment(session.id)
                    nav.push(Screen.Threads(session.id))
                }
                HorizontalDivider(color = Candy.colors.border)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EnvironmentRow(graph: AppGraph, session: EnvironmentSession, onOpen: () -> Unit) {
    val env by session.env.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val threads by session.threads.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = Candy.colors
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state.color())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(env.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.label(), fontSize = 12.sp, color = c.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (env.kind == EnvironmentKind.CONNECT) "T3 Connect" else "Paired", fontSize = 11.sp, color = c.mutedForeground)
                Text("${threads.threadCount} threads", fontSize = 12.sp, color = c.mutedForeground)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Reconnect now") }, onClick = { menu = false; session.wake() })
            DropdownMenuItem(text = { Text("Remove from this phone") }, onClick = {
                menu = false
                scope.launch { graph.environments.remove(session.id) }
            })
        }
    }
}
