package org.anikodev.candywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.update.UpdateBanner

sealed interface Screen {
    /** The thread list of one environment, or the welcome screen when there is none. */
    data class Home(val envId: String?) : Screen
    data class Thread(val envId: String, val threadId: String) : Screen
    data class NewThread(val envId: String, val projectId: String) : Screen
    data class Diff(val envId: String, val threadId: String, val checkpointId: String, val title: String) : Screen
    data class Pair(val prefill: String? = null) : Screen
    data object Connect : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
}

/** A plain back stack. No transition animations: screens swap on the next frame. */
class Navigator(initial: List<Screen>, private val onPopped: (Screen) -> Unit = {}) {
    val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.size > 1) onPopped(stack.removeAt(stack.lastIndex)) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    /** Makes [s] the only screen, for switching environments. */
    fun reset(s: Screen) { stack.clear(); stack += s }
}

@Composable
fun App(graph: AppGraph, sharedText: String?, consumeShared: () -> Unit) {
    val nav = remember {
        // A cold start reopens the last environment's thread list, drawn from the cache on the first frame.
        val envs = graph.environments.sessions.value
        val last = graph.lastEnvironmentId?.takeIf { graph.environments.session(it) != null } ?: envs.firstOrNull()?.id
        Navigator(listOf(Screen.Home(last)))
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            nav.push(Screen.Pair(sharedText))
            consumeShared()
        }
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Keeps scroll positions and drafts of screens under the top one.
    val saveable = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Candy.colors.background)) {
        val s = nav.top
        saveable.SaveableStateProvider(s.toString()) {
            when (s) {
                is Screen.Home -> HomeScreen(graph, nav, s.envId)
                is Screen.Thread -> ThreadScreen(graph, nav, s.envId, s.threadId)
                is Screen.NewThread -> NewThreadScreen(graph, nav, s.envId, s.projectId)
                is Screen.Diff -> DiffScreen(graph, nav, s)
                is Screen.Pair -> PairScreen(graph, nav, s.prefill)
                Screen.Connect -> ConnectScreen(graph, nav)
                Screen.Settings -> SettingsScreen(graph, nav)
                Screen.Diagnostics -> DiagnosticsScreen(graph, nav)
            }
        }
        UpdateBanner(graph.updater, Modifier.align(Alignment.BottomCenter))
    }
}
