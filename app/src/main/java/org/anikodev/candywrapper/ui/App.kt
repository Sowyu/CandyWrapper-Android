package org.anikodev.candywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.anikodev.candywrapper.AppGraph

sealed interface Screen {
    data object Environments : Screen
    data class Threads(val envId: String) : Screen
    data class Thread(val envId: String, val threadId: String) : Screen
    data class Pair(val prefill: String? = null) : Screen
    data object Connect : Screen
    data object Diagnostics : Screen
}

/** A plain back stack. No transition animations: screens swap on the next frame. */
class Navigator(initial: List<Screen>, private val onPopped: (Screen) -> Unit = {}) {
    val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.size > 1) onPopped(stack.removeAt(stack.lastIndex)) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
}

@Composable
fun App(graph: AppGraph, sharedText: String?, consumeShared: () -> Unit) {
    val nav = remember {
        // A cold start reopens the last thread list, drawn from the cache on the first frame.
        val last = graph.lastEnvironmentId?.takeIf { graph.environments.session(it) != null }
        Navigator(listOfNotNull(Screen.Environments, last?.let { Screen.Threads(it) })) { popped ->
            if (popped is Screen.Threads) graph.rememberEnvironment(null)
        }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            nav.push(Screen.Pair(sharedText))
            consumeShared()
        }
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    Box(Modifier.fillMaxSize().background(Candy.colors.background)) {
        when (val s = nav.top) {
            Screen.Environments -> EnvironmentsScreen(graph, nav)
            is Screen.Threads -> ThreadListScreen(graph, nav, s.envId)
            is Screen.Thread -> ThreadScreen(graph, nav, s.envId, s.threadId)
            is Screen.Pair -> PairScreen(graph, nav, s.prefill)
            Screen.Connect -> ConnectScreen(graph, nav)
            Screen.Diagnostics -> DiagnosticsScreen(graph, nav)
        }
    }
}
