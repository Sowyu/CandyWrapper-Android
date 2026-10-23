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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.connect.ClerkCodeChallenge
import org.anikodev.candywrapper.core.connect.ClerkSignInStep
import org.anikodev.candywrapper.core.model.EnvironmentKind

/**
 * T3 Connect: native Clerk sign-in (email code, or password), then the account's linked computers.
 * No webview and no URL scheme, as in CandyWrapper for macOS.
 */
@Composable
fun ConnectScreen(graph: AppGraph, nav: Navigator) {
    val account by graph.account.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("T3 Connect", onBack = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (account == null) SignIn(graph) else Account(graph, nav)
        }
    }
}

@Composable
private fun SignIn(graph: AppGraph) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<ClerkCodeChallenge?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = Candy.colors
    DisposableEffect(Unit) { onDispose { graph.clerk.cancelSignIn() } }

    fun run(label: String, block: suspend () -> Unit) {
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed. Try again."
            } finally {
                busy = null
            }
        }
    }

    suspend fun finish() {
        graph.accountChanged()
        graph.refreshConnect()?.let { error = it }
    }

    Text("Sign in with your T3 Connect account to reach the computers linked to it.", color = c.mutedForeground)
    val pending = challenge
    if (pending == null) {
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (usePassword) {
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        Row(horizontalArrangement = ButtonRow) {
            Button(enabled = busy == null && email.isNotBlank() && (!usePassword || password.isNotEmpty()), onClick = {
                if (usePassword) run("Signing in…") {
                    when (val step = graph.clerk.signIn(email, password)) {
                        is ClerkSignInStep.Complete -> finish()
                        is ClerkSignInStep.NeedsCode -> challenge = step.challenge
                    }
                } else run("Sending code…") { challenge = graph.clerk.startEmailCode(email) }
            }) { Text(busy ?: if (usePassword) "Sign in" else "Email me a code") }
            TextButton(onClick = { usePassword = !usePassword }) { Text(if (usePassword) "Use an email code" else "Use a password") }
        }
    } else {
        Text("We sent a code to ${pending.sentTo ?: email}.", color = c.foreground)
        OutlinedTextField(code, { code = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Row(horizontalArrangement = ButtonRow) {
            Button(enabled = busy == null && code.length >= 6, onClick = {
                run("Checking…") {
                    graph.clerk.verifyCode(code, pending)
                    finish()
                }
            }) { Text(busy ?: "Continue") }
            OutlinedButton(enabled = busy == null, onClick = { run("Sending…") { graph.clerk.resendCode(pending) } }) { Text("Send again") }
            TextButton(onClick = { challenge = null; code = "" }) { Text("Back") }
        }
    }
    error?.let { Text(it, color = c.error) }
}

@Composable
private fun Account(graph: AppGraph, nav: Navigator) {
    val account by graph.account.collectAsStateWithLifecycle()
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = Candy.colors
    Text("Signed in as ${account?.identifier ?: "your account"}", fontWeight = FontWeight.Medium, color = c.foreground)
    val linked = sessions.filter { it.env.value.kind == EnvironmentKind.CONNECT }
    Text(if (linked.isEmpty()) "No linked computers yet. Turn on T3 Connect in T3 Code's settings on your computer." else "${linked.size} linked computers",
        color = c.mutedForeground, fontSize = 14.sp)
    for (s in linked) {
        Text("• ${s.env.value.label}", color = c.foreground, modifier = Modifier.rowClick {
            graph.rememberEnvironment(s.id)
            nav.push(Screen.Threads(s.id))
        }.padding(vertical = 4.dp))
    }
    Row(horizontalArrangement = ButtonRow) {
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                message = graph.refreshConnect() ?: "Up to date."
                busy = false
            }
        }) { Text(if (busy) "Refreshing…" else "Refresh computers") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                graph.clerk.signOut()
                graph.environments.signedOut()
                graph.accountChanged()
                busy = false
            }
        }) { Text("Sign out") }
    }
    message?.let { Text(it, color = c.mutedForeground) }
}
