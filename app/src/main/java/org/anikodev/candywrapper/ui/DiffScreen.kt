package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.diff.DiffFile
import org.anikodev.candywrapper.core.diff.UnifiedDiff
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.timing.Timeline

/**
 * One run's changes as a native unified diff: a header per file, then its numbered lines. The
 * patch is fetched and parsed off the main thread; each line is its own list item.
 */
@Composable
fun DiffScreen(graph: AppGraph, nav: Navigator, screen: Screen.Diff) {
    val session = graph.environments.session(screen.envId)
    if (session == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val thread = remember(screen) { session.thread(screen.threadId) }
    val c = Candy.colors
    var files by remember { mutableStateOf<List<DiffFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    val collapsed = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(attempt) {
        error = null
        val start = Timeline.nowMs()
        try {
            val text = thread.checkpointDiff(screen.checkpointId)
            if (text == null) {
                error = "The computer has no diff for this run. Its checkpoint may be gone."
            } else {
                files = withContext(Dispatchers.Default) { UnifiedDiff.parse(text) }
                Timeline.span("diff.loaded", start, "${files!!.size} files, ${text.length} chars")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = EnvironmentSession.describe(e)
        }
    }
    val totals = remember(files) { files?.let { f -> f.sumOf { it.additions } to f.sumOf { it.deletions } } }
    Column(Modifier.fillMaxSize()) {
        TopBar(screen.title, onBack = { nav.pop() }, subtitle = totals?.let { "+${it.first} −${it.second} · ${files!!.size} files" }) {
            IconButton(Icons.TextWrap, if (wrap) "Stop wrapping lines" else "Wrap long lines", tint = if (wrap) c.foreground else c.iconMuted) { wrap = !wrap }
        }
        when {
            error != null -> Notice(error!!, c.foreground, c.muted, Modifier.padding(16.dp), action = "Retry") { attempt++ }
            files == null -> Txt("Loading the diff", CandyType.sm, c.mutedForeground, modifier = Modifier.padding(16.dp))
            files!!.isEmpty() -> EmptyState("No changes", "This run left the files as they were.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                files!!.forEachIndexed { index, file ->
                    item(key = "file:$index", contentType = "file") {
                        FileHeader(file, collapsed[index] == true) { collapsed[index] = !(collapsed[index] ?: false) }
                    }
                    if (collapsed[index] != true) {
                        if (file.binary) item(key = "bin:$index", contentType = "note") { Txt("Binary file", CandyType.xs, c.mutedForeground, modifier = Modifier.padding(16.dp)) }
                        items(file.lines.size, key = { "l:$index:$it" }, contentType = { "line" }) { i -> DiffLineView(file.lines[i], wrap, numbers = true) }
                    }
                }
                item(key = "nav", contentType = "spacer") { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
            }
        }
    }
}

@Composable
private fun FileHeader(file: DiffFile, collapsed: Boolean, onToggle: () -> Unit) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = if (collapsed) "Show lines" else "Hide lines", onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (collapsed) Icons.ChevronRight else Icons.ChevronDown, c.mutedForeground, 15.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Txt(file.path.substringAfterLast('/'), CandyType.smMedium, c.foreground, maxLines = 1)
                val dir = file.path.substringBeforeLast('/', "")
                val renamed = file.oldPath?.let { "from $it" }
                if (dir.isNotEmpty() || renamed != null) Txt(listOfNotNull(dir.ifEmpty { null }, renamed).joinToString(" · "), CandyType.xs, c.mutedForeground, maxLines = 1)
            }
            Txt("+${file.additions}", CandyType.monoSmall, c.diffAdditionForeground)
            Spacer(Modifier.width(8.dp))
            Txt("−${file.deletions}", CandyType.monoSmall, c.diffDeletionForeground)
        }
        Divider()
    }
}
