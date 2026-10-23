package org.anikodev.candywrapper.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.model.ServerInfo

/** The thread's model and modes as the composer shows them. */
data class ComposerSettings(val model: ModelSelection?, val runtimeMode: String, val interactionMode: String)

/** An image picked for the next message, read when it is sent. */
data class PickedFile(val uri: Uri, val name: String, val mimeType: String, val sizeBytes: Long)

enum class Picker { MODEL, OPTIONS, ACCESS }

fun runtimeModeLabel(mode: String) = when (mode) {
    "approval-required" -> "Supervised"
    "auto-accept-edits" -> "Auto-accept edits"
    "auto" -> "Auto"
    else -> "Full access"
}

private fun runtimeModeDetail(mode: String) = when (mode) {
    "approval-required" -> "Asks before running commands and editing files."
    "auto-accept-edits" -> "Edits files without asking; asks before commands."
    "auto" -> "The agent decides when to ask."
    else -> "Runs commands and edits files without asking."
}

fun modelLabel(server: ServerInfo, model: ModelSelection?): String {
    model ?: return "Model"
    return server.provider(model.instanceId)?.model(model.model)?.name ?: model.model
}

/** The current choice of the model's first select option, usually reasoning effort. */
private fun optionLabel(server: ServerInfo, model: ModelSelection?): String? {
    model ?: return null
    val descriptor = server.provider(model.instanceId)?.model(model.model)?.options?.firstOrNull { it.type == "select" } ?: return null
    val id = model.option(descriptor.id)?.text ?: descriptor.defaultChoice?.id
    return descriptor.choices.firstOrNull { it.id == id }?.label ?: descriptor.label
}

/** Reads an attachment's display name and size without loading it. */
fun describePick(context: Context, uri: Uri): PickedFile? {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment ?: "image"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    val mime = resolver.getType(uri) ?: "image/jpeg"
    return PickedFile(uri, name, mime, size)
}

suspend fun readPick(context: Context, file: PickedFile): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: throw java.io.IOException("Could not read ${file.name}.")
}

/**
 * T3's composer: a rounded card with the prompt on top and the run controls under it (model,
 * effort, access, plan mode, attach, send). While a run is active an empty prompt turns the send
 * button into Stop, and a typed message queues or steers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    text: String,
    onText: (String) -> Unit,
    settings: ComposerSettings,
    server: ServerInfo,
    running: Boolean,
    canStop: Boolean,
    files: List<PickedFile>,
    busy: String?,
    error: String?,
    connected: Boolean,
    onPicker: (Picker) -> Unit,
    onTogglePlan: (() -> Unit)?,
    onAttach: () -> Unit,
    onRemoveFile: (PickedFile) -> Unit,
    onSend: () -> Unit,
    onSendOptions: (() -> Unit)?,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
) {
    val c = Candy.colors
    val canSend = connected && busy == null && (text.isNotBlank() || files.isNotEmpty())
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (error != null) Notice(error, c.error, c.errorSurface, action = "Dismiss", onAction = onDismissError)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.composer)).background(c.card)
                .border(1.dp, c.border, RoundedCornerShape(Radius.composer)),
        ) {
            if (files.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in files) {
                        Row(
                            Modifier.clip(RoundedCornerShape(Radius.md)).background(c.muted).padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Image, c.mutedForeground, 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Txt(f.name, CandyType.meta, c.foreground, maxLines = 1, modifier = Modifier.width(110.dp))
                            IconButton(Icons.X, "Remove ${f.name}", size = 14.dp) { onRemoveFile(f) }
                        }
                    }
                }
            }
            BasicTextField(
                text, onText,
                Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 200.dp).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                    .semantics { contentDescription = "Message" },
                textStyle = CandyType.body.merge(TextStyle(color = c.foreground)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Txt(
                            when {
                                !connected -> "Waiting for the computer"
                                running -> "Send a follow-up"
                                settings.interactionMode == "plan" -> "Describe what to plan"
                                else -> "Ask anything"
                            },
                            CandyType.body, c.mutedForeground, maxLines = 1,
                        )
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    SelectChip(modelLabel(server, settings.model), description = "Model", enabled = server.providers.isNotEmpty()) { onPicker(Picker.MODEL) }
                    optionLabel(server, settings.model)?.let { SelectChip(it, icon = Icons.Brain, description = "Model options") { onPicker(Picker.OPTIONS) } }
                    SelectChip(
                        runtimeModeLabel(settings.runtimeMode),
                        icon = if (settings.runtimeMode == "full-access") Icons.LockOpen else Icons.Lock,
                        description = "Access",
                    ) { onPicker(Picker.ACCESS) }
                    if (onTogglePlan != null) PlanToggle(settings.interactionMode == "plan", onTogglePlan)
                }
                IconButton(Icons.Paperclip, "Attach images", enabled = connected && busy == null, size = 19.dp, onClick = onAttach)
                val stop = running && canStop && text.isBlank() && files.isEmpty()
                Box(
                    Modifier.size(44.dp).combinedClickable(
                        enabled = stop || canSend, role = Role.Button,
                        onClickLabel = if (stop) "Stop" else "Send",
                        onLongClickLabel = if (onSendOptions != null && !stop) "Queue or steer" else null,
                        onLongClick = if (onSendOptions != null && !stop && canSend) onSendOptions else null,
                        onClick = if (stop) onStop else onSend,
                    ).semantics { contentDescription = if (stop) "Stop the run" else "Send" },
                    contentAlignment = Alignment.Center,
                ) {
                    val fill = when {
                        stop -> c.foreground
                        canSend -> c.send
                        else -> c.muted
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                        if (stop) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(c.background))
                        else Icon(Icons.ArrowUp, if (canSend) Color.White else c.mutedForeground, 18.dp)
                    }
                }
            }
        }
        if (busy != null) Txt(busy, CandyType.meta, c.mutedForeground, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PlanToggle(on: Boolean, onToggle: () -> Unit) {
    val c = Candy.colors
    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(Radius.md)).background(if (on) c.infoSurface else Color.Transparent)
                .combinedClickable(role = Role.Switch, onClickLabel = if (on) "Turn off plan mode" else "Plan first", onClick = onToggle)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.ListTodo, if (on) c.info else c.mutedForeground, 15.dp)
            Txt("Plan", CandyType.small, if (on) c.info else c.foreground)
        }
    }
}

/** The sheet behind each composer chip. */
@Composable
fun BoxScope.ComposerPickerSheet(
    picker: Picker,
    server: ServerInfo,
    settings: ComposerSettings,
    onModel: (ModelSelection) -> Unit,
    onRuntimeMode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Candy.colors
    when (picker) {
        Picker.MODEL -> Sheet(onDismiss, "Model") {
            if (server.providers.isEmpty()) Txt("The computer has not listed its models yet.", CandyType.ui, c.mutedForeground, modifier = Modifier.padding(16.dp))
            for (p in server.providers) {
                Txt(p.name, CandyType.label, c.mutedForeground, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp))
                for (m in p.models) {
                    val selected = settings.model?.instanceId == p.instanceId && settings.model.model == m.slug
                    ListRow(m.name, detail = if (!p.ready) "Not ready on the computer" else null, selected = selected, enabled = p.ready) {
                        // Keep option picks the new model also offers.
                        val kept = settings.model?.options.orEmpty().filter { o -> m.options.any { it.id == o.id } }
                        onModel(ModelSelection(p.instanceId, m.slug, kept))
                        onDismiss()
                    }
                }
            }
        }
        Picker.OPTIONS -> Sheet(onDismiss, "Model options") {
            val model = settings.model
            val info = model?.let { server.provider(it.instanceId)?.model(it.model) }
            for (d in info?.options.orEmpty()) {
                Txt(d.label, CandyType.label, c.mutedForeground, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp))
                if (d.type == "boolean") {
                    val on = model!!.option(d.id)?.flag == true
                    ListRow(if (on) "On" else "Off", selected = false, trailing = { Switch(on) }) {
                        onModel(model.withOption(OptionValue(d.id, flag = !on)))
                    }
                } else {
                    val current = model!!.option(d.id)?.text ?: d.defaultChoice?.id
                    for (choice in d.choices) {
                        ListRow(choice.label, selected = choice.id == current) {
                            onModel(model.withOption(OptionValue(d.id, text = choice.id)))
                            onDismiss()
                        }
                    }
                }
            }
        }
        Picker.ACCESS -> Sheet(onDismiss, "Access") {
            val supported = settings.model?.let { server.provider(it.instanceId)?.runtimeModes }.orEmpty()
            for (mode in ServerInfo.RUNTIME_MODES.filter { supported.isEmpty() || it in supported }) {
                ListRow(runtimeModeLabel(mode), detail = runtimeModeDetail(mode), selected = mode == settings.runtimeMode) {
                    onRuntimeMode(mode)
                    onDismiss()
                }
            }
        }
    }
}

/** A read-only switch drawn from tokens; the row around it takes the tap. */
@Composable
fun Switch(on: Boolean) {
    val c = Candy.colors
    Box(
        Modifier.size(width = 36.dp, height = 20.dp).clip(CircleShape).background(if (on) c.primary else c.input).padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
    }
}
