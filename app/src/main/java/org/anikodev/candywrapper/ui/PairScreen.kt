package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.sync.EnvironmentSession

@Composable
fun PairScreen(graph: AppGraph, nav: Navigator, prefill: String?) {
    var link by remember { mutableStateOf(prefill.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val c = Candy.colors
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Pair a computer", onBack = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("On the computer running T3 Code, run `npx t3 pair` and paste the link it prints. The phone must reach that address, on the same network or over Tailscale.",
                color = c.mutedForeground)
            OutlinedTextField(link, { link = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Pairing link or host") },
                minLines = 2, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(code, { code = it.uppercase(); error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Code, if the link has none") })
            Row(horizontalArrangement = ButtonRow) {
                Button(enabled = !busy && link.isNotBlank(), onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val session = graph.environments.pair(link, code.ifBlank { null })
                            graph.rememberEnvironment(session.id)
                            nav.replaceTop(Screen.Threads(session.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = EnvironmentSession.describe(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Pairing…" else "Pair") }
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) { Text("Paste") }
            }
            error?.let { Text(it, color = c.error) }
        }
    }
}
