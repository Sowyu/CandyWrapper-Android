package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(BottomInsets)) {
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

    Txt("Sign in with your T3 Connect account to reach the computers linked to it.", CandyType.ui, c.mutedForeground)
    val pending = challenge
    if (pending == null) {
        TextInput(email, { email = it }, "you@example.com", label = "Email", keyboard = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (usePassword) {
            TextInput(password, { password = it }, "Password", label = "Password",
                visual = PasswordVisualTransformation(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        Row(horizontalArrangement = ButtonRow) {
            CButton(busy ?: if (usePassword) "Sign in" else "Email me a code", kind = ButtonKind.PRIMARY,
                enabled = busy == null && email.isNotBlank() && (!usePassword || password.isNotEmpty()), onClick = {
                if (usePassword) run("Signing in") {
                    when (val step = graph.clerk.signIn(email, password)) {
                        is ClerkSignInStep.Complete -> finish()
                        is ClerkSignInStep.NeedsCode -> challenge = step.challenge
                    }
                } else run("Sending code") { challenge = graph.clerk.startEmailCode(email) }
            })
            CButton(if (usePassword) "Use an email code" else "Use a password", kind = ButtonKind.GHOST) { usePassword = !usePassword }
        }
    } else {
        Txt("We sent a code to ${pending.sentTo ?: email}.", CandyType.ui, c.foreground)
        TextInput(code, { code = it.filter(Char::isDigit) }, "123456", label = "Code", keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            style = CandyType.mono)
        Row(horizontalArrangement = ButtonRow) {
            CButton(busy ?: "Continue", kind = ButtonKind.PRIMARY, enabled = busy == null && code.length >= 6, onClick = {
                run("Checking") {
                    graph.clerk.verifyCode(code, pending)
                    finish()
                }
            })
            CButton("Send again", enabled = busy == null) { run("Sending") { graph.clerk.resendCode(pending) } }
            CButton("Back", kind = ButtonKind.GHOST) { challenge = null; code = "" }
        }
    }
    error?.let { Notice(it, c.errorForeground, c.errorSurface) }
}

@Composable
private fun Account(graph: AppGraph, nav: Navigator) {
    val account by graph.account.collectAsStateWithLifecycle()
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = Candy.colors
    Txt("Signed in as ${account?.identifier ?: "your account"}", CandyType.uiMedium, c.foreground)
    val linked = sessions.filter { it.env.value.kind == EnvironmentKind.CONNECT }
    Txt(if (linked.isEmpty()) "No linked computers yet. Turn on T3 Connect in T3 Code's settings on your computer." else "${linked.size} linked computers",
        CandyType.ui, c.mutedForeground)
    if (linked.isNotEmpty()) Card {
        for ((i, s) in linked.withIndex()) {
            if (i > 0) Divider()
            ListRow(s.env.value.label, icon = Icons.Laptop) { nav.reset(Screen.Main(s.id, null)) }
        }
    }
    Row(horizontalArrangement = ButtonRow) {
        CButton(if (busy) "Refreshing" else "Refresh computers", kind = ButtonKind.PRIMARY, enabled = !busy, onClick = {
            busy = true
            scope.launch {
                message = graph.refreshConnect() ?: "Up to date."
                busy = false
            }
        })
        CButton("Sign out", enabled = !busy, onClick = {
            busy = true
            scope.launch {
                graph.clerk.signOut()
                graph.environments.signedOut()
                graph.accountChanged()
                busy = false
            }
        })
    }
    message?.let { Txt(it, CandyType.small, c.mutedForeground) }
}
