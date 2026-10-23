package org.anikodev.candywrapper.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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

/** The composer's menus. */
enum class Picker { MODEL, OPTIONS, ACCESS, SEND }

/** T3's runtime mode labels and descriptions (the access mode picker). */
fun runtimeModeLabel(mode: String) = when (mode) {
    "approval-required" -> "Supervised"
    "auto-accept-edits" -> "Auto-accept edits"
    "auto" -> "Auto"
    else -> "Full access"
}

private fun runtimeModeDetail(mode: String) = when (mode) {
    "approval-required" -> "Ask before commands and file changes."
    "auto-accept-edits" -> "Auto-approve edits, ask before other actions."
    "auto" -> "Supported providers approve routine actions; others still ask."
    else -> "Allow commands and edits without prompts."
}

private fun runtimeModeIcon(mode: String): ImageVector = when (mode) {
    "approval-required" -> Icons.Lock
    "auto-accept-edits" -> Icons.Pencil
    "auto" -> Icons.Sparkles
    else -> Icons.LockOpen
}

fun modelLabel(server: ServerInfo, model: ModelSelection?): String {
    model ?: return "Model"
    return server.provider(model.instanceId)?.model(model.model)?.name ?: model.model
}

/** The provider's brand glyph and its tint: Claude's orange mark as drawn, OpenAI's knot in the foreground. */
@Composable
fun providerIcon(driver: String?): Pair<ImageVector, Color> = when (driver) {
    null -> Icons.Bot to Candy.colors.mutedForeground
    "codex", "openai" -> Icons.BrandOpenAI to Candy.colors.foreground
    else -> Icons.BrandClaudeAI to Color.Unspecified
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
 * T3's composer (ChatComposer.tsx): a `rounded-[20px]` card on `--card` with the prompt on top
 * and the run controls under it: model with its provider glyph, effort, access, plan mode, then
 * attach and the round send button. Each control opens T3's menu above it. While a run is active
 * an empty prompt turns send into a red Stop; a typed message queues or steers (long press).
 *
 * [locked] keeps the card but disables it, with T3's "Resolve this approval request to continue".
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
    onModel: (ModelSelection) -> Unit,
    onRuntimeMode: (String) -> Unit,
    onTogglePlan: (() -> Unit)?,
    onAttach: () -> Unit,
    onRemoveFile: (PickedFile) -> Unit,
    onSend: () -> Unit,
    onQueue: (() -> Unit)?,
    onSteer: (() -> Unit)?,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    locked: String? = null,
    initialMenu: Picker? = null,
    trayAbove: Boolean = false,
    onStash: (() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Candy.colors
    var menu by remember { mutableStateOf(initialMenu) }
    val canSend = connected && busy == null && locked == null && (text.isNotBlank() || files.isNotEmpty())
    val canStash = busy == null && locked == null && (text.isNotBlank() || files.isNotEmpty())
    val provider = settings.model?.let { server.provider(it.instanceId) }
    val shape = RoundedCornerShape(Radius.composer)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = if (trayAbove) 0.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (error != null) Alert(error, NoticeTone.ERROR, action = "Dismiss", onAction = onDismissError)
        Column(
            Modifier.fillMaxWidth()
                .let {
                    when {
                        // Redesign: Capy's prompt bar, frosted over the art with the pills' rim and shadow.
                        c.redesign -> it.floating(shape)
                        c.dark -> it
                        else -> it.shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.04f), spotColor = Color.Black.copy(alpha = 0.1f))
                    }
                }
                .clip(shape).let { if (c.redesign) it else it.background(c.card).border(1.dp, c.border, shape) },
        ) {
            if (files.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in files) AttachmentChip(f) { onRemoveFile(f) }
                }
            }
            BasicTextField(
                text, onText,
                Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
                    .semantics { contentDescription = "Message" },
                enabled = locked == null,
                textStyle = CandyType.chat.merge(TextStyle(color = c.foreground)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Txt(
                            when {
                                locked != null -> locked
                                !connected -> "Waiting for the computer"
                                running -> "Ask for follow-up changes"
                                settings.interactionMode == "plan" -> "Describe what to plan"
                                else -> "Ask anything"
                            },
                            CandyType.chat, c.placeholder, maxLines = 2,
                        )
                        inner()
                    }
                },
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            // T3's resting strip shows every control that fits; on a phone mode and access move into ⋯ (CompactComposerControlsMenu).
            val compact = maxWidth < 520.dp
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val (glyph, brandTint) = providerIcon(provider?.driver)
                        // Redesign draws Claude's spark white, like the rest of its chips.
                        val glyphTint = if (c.redesign && brandTint == Color.Unspecified) c.foreground else brandTint
                        SelectChip(
                            modelLabel(server, settings.model), icon = glyph, iconTint = glyphTint, description = "Model", enabled = server.providers.isNotEmpty() && locked == null,
                            open = menu == Picker.MODEL, labelMax = if (compact) 112.dp else 160.dp, chevron = !compact,
                        ) { menu = Picker.MODEL }
                        ModelMenu(menu == Picker.MODEL, server, settings, onModel) { menu = null }
                    }
                    optionLabel(server, settings.model)?.let { label ->
                        if (!compact) ControlDivider()
                        Box {
                            SelectChip(label, icon = Icons.Brain, description = "Model options", enabled = locked == null, open = menu == Picker.OPTIONS, chevron = !compact) { menu = Picker.OPTIONS }
                            OptionsMenu(menu == Picker.OPTIONS, server, settings, onModel) { menu = null }
                        }
                    }
                    if (compact) {
                        Box {
                            IconButton(Icons.Ellipsis, "More composer controls", enabled = locked == null, size = 16.dp, box = 32.dp) { menu = Picker.ACCESS }
                            Menu(menu == Picker.ACCESS, { menu = null }, side = MenuSide.ABOVE, minWidth = 260.dp) {
                                // On a phone the stash button would push ⋯ out of the strip, so Stash lives here.
                                if (onStash != null) {
                                    MenuItem("Stash prompt", Icons.Bookmark, detail = "Keep it for later and clear the composer", enabled = canStash) { menu = null; onStash() }
                                    MenuSeparator()
                                }
                                if (onTogglePlan != null) {
                                    MenuLabel("Mode")
                                    MenuItem("Chat", selected = settings.interactionMode != "plan") { menu = null; if (settings.interactionMode == "plan") onTogglePlan() }
                                    MenuItem("Plan", selected = settings.interactionMode == "plan") { menu = null; if (settings.interactionMode != "plan") onTogglePlan() }
                                    MenuSeparator()
                                }
                                AccessItems(server, settings, plain = true) { mode -> menu = null; onRuntimeMode(mode) }
                            }
                        }
                        if (settings.interactionMode == "plan" && onTogglePlan != null) PlanToggle(true, locked == null, onTogglePlan)
                    } else {
                        ControlDivider()
                        Box {
                            SelectChip(
                                runtimeModeLabel(settings.runtimeMode), icon = runtimeModeIcon(settings.runtimeMode), description = "Access",
                                enabled = locked == null, open = menu == Picker.ACCESS,
                            ) { menu = Picker.ACCESS }
                            AccessMenu(menu == Picker.ACCESS, server, settings, onRuntimeMode) { menu = null }
                        }
                        if (onTogglePlan != null) PlanToggle(settings.interactionMode == "plan", locked == null, onTogglePlan)
                    }
                }
                if (onStash != null && !compact) IconButton(Icons.Bookmark, "Stash prompt", enabled = canStash, size = 18.dp, onClick = onStash)
                IconButton(Icons.Paperclip, "Attach images", enabled = connected && busy == null && locked == null, size = 18.dp, onClick = onAttach)
                val stop = running && canStop && text.isBlank() && files.isEmpty()
                Box {
                    SendButton(stop, canSend, onClick = if (stop) onStop else onSend, onLongClick = if (onQueue != null && !stop && canSend) ({ menu = Picker.SEND }) else null)
                    Menu(menu == Picker.SEND, { menu = null }, side = MenuSide.ABOVE, alignEnd = true, minWidth = 240.dp) {
                        MenuLabel("Send during this run")
                        onQueue?.let { MenuItem("Queue after this run", Icons.Clock, detail = "Starts when the current run finishes") { menu = null; it() } }
                        onSteer?.let { MenuItem("Steer now", Icons.Zap, detail = "Redirects the run that is going") { menu = null; it() } }
                    }
                }
            }
            }
        }
        // Redesign: the machine row and the waiting pill float under the composer, inside its corners.
        if (footer != null && c.redesign) Row(Modifier.fillMaxWidth().padding(horizontal = Pill.inset), verticalAlignment = Alignment.CenterVertically, content = footer)
        // Real progress only: "Uploading 2 of 3", "Sending", "Stopping".
        if (busy != null) Txt(busy, CandyType.xs, c.mutedForeground, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The 1px rule T3 draws between composer controls. */
@Composable
private fun ControlDivider() {
    // Capy's chips have no dividers.
    if (Candy.redesign) return
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(16.dp).background(Candy.colors.border))
}

@Composable
private fun AttachmentChip(f: PickedFile, onRemove: () -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.clip(RoundedCornerShape(Radius.md)).background(c.accent).border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Image, c.mutedForeground, 14.dp)
        Spacer(Modifier.width(6.dp))
        Txt(f.name, CandyType.xs, c.foreground, maxLines = 1, modifier = Modifier.width(110.dp))
        IconButton(Icons.X, "Remove ${f.name}", size = 14.dp, box = 28.dp, onClick = onRemove)
    }
}

/**
 * T3's send button: a 36dp circle in the send colour with a white arrow, muted while there is
 * nothing to send, and a red circle with a white square while Stop is what it does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(stop: Boolean, canSend: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).combinedClickable(
            source, null,
            enabled = stop || canSend, role = Role.Button,
            onClickLabel = if (stop) "Stop" else "Send",
            onLongClickLabel = if (onLongClick != null) "Queue or steer" else null,
            onLongClick = onLongClick,
            onClick = onClick,
        ).semantics { contentDescription = if (stop) "Stop the run" else "Send" },
        contentAlignment = Alignment.Center,
    ) {
        val fill = when {
            stop -> c.error
            canSend -> c.send
            else -> c.send.copy(alpha = 0.4f)
        }
        // Redesign: Capy's rounded square in the accent, its glyph in the accent's own text colour.
        val shape = if (c.redesign) RoundedCornerShape(Radius.md) else CircleShape
        val glyph = if (c.redesign && !stop) c.primaryForeground else Color.White
        Box(Modifier.size(if (c.redesign) 34.dp else 36.dp).pressScale(source).clip(shape).background(fill), contentAlignment = Alignment.Center) {
            if (stop) Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
            else Icon(Icons.ArrowUp, glyph, 18.dp)
        }
    }
}

@Composable
private fun PlanToggle(on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(Radius.md)).background(if (on) c.infoSurface else Color.Transparent)
                .combinedClickable(source, null, enabled = enabled, role = Role.Switch, onClickLabel = if (on) "Turn off plan mode" else "Plan first", onClick = onToggle)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.ListTodo, if (on) c.infoForeground else c.mutedForeground, 16.dp)
            Txt("Plan", CandyType.sm, if (on) c.infoForeground else c.mutedForeground)
        }
    }
}

/** T3's model picker (13-model-picker): models grouped by provider, each with its glyph. */
@Composable
private fun ModelMenu(open: Boolean, server: ServerInfo, settings: ComposerSettings, onModel: (ModelSelection) -> Unit, onDismiss: () -> Unit) {
    if (Candy.redesign) return RedesignModelPicker(open, server, settings, onModel, onDismiss)
    Menu(open, onDismiss, side = MenuSide.ABOVE, minWidth = 260.dp) {
        if (server.providers.isEmpty()) MenuLabel("The computer has not listed its models yet.")
        for ((i, p) in server.providers.withIndex()) {
            if (i > 0) MenuSeparator()
            MenuLabel(p.name)
            val (glyph, glyphTint) = providerIcon(p.driver)
            for (m in p.models) {
                val selected = settings.model?.instanceId == p.instanceId && settings.model.model == m.slug
                MenuItem(m.name, glyph, iconTint = glyphTint, detail = if (!p.ready) "Not ready on the computer" else null, selected = selected, enabled = p.ready) {
                    // Keep option picks the new model also offers.
                    val kept = settings.model?.options.orEmpty().filter { o -> m.options.any { it.id == o.id } }
                    onModel(ModelSelection(p.instanceId, m.slug, kept))
                    onDismiss()
                }
            }
        }
    }
}

/** The traits menu (41-traits-picker): each of the model's options, effort first. */
@Composable
private fun OptionsMenu(open: Boolean, server: ServerInfo, settings: ComposerSettings, onModel: (ModelSelection) -> Unit, onDismiss: () -> Unit) {
    val model = settings.model
    val info = model?.let { server.provider(it.instanceId)?.model(it.model) }
    Menu(open, onDismiss, side = MenuSide.ABOVE, minWidth = 220.dp) {
        for ((i, d) in info?.options.orEmpty().withIndex()) {
            if (i > 0) MenuSeparator()
            MenuLabel(d.label)
            if (d.type == "boolean") {
                val on = model!!.option(d.id)?.flag == true
                MenuItem(if (on) "On" else "Off", trailing = { Switch(on) }) { onModel(model.withOption(OptionValue(d.id, flag = !on))) }
            } else {
                val current = model!!.option(d.id)?.text ?: d.defaultChoice?.id
                for (choice in d.choices) {
                    MenuItem(choice.label, selected = choice.id == current, trailing = if (choice.isDefault) ({ DefaultTag() }) else null) {
                        onModel(model.withOption(OptionValue(d.id, text = choice.id)))
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** The access mode picker (42-access-mode-picker), in T3's words. */
@Composable
private fun AccessMenu(open: Boolean, server: ServerInfo, settings: ComposerSettings, onRuntimeMode: (String) -> Unit, onDismiss: () -> Unit) {
    Menu(open, onDismiss, side = MenuSide.ABOVE, minWidth = 280.dp) {
        AccessItems(server, settings) { mode -> onRuntimeMode(mode); onDismiss() }
    }
}

@Composable
private fun AccessItems(server: ServerInfo, settings: ComposerSettings, plain: Boolean = false, onPick: (String) -> Unit) {
    val supported = settings.model?.let { server.provider(it.instanceId)?.runtimeModes }.orEmpty()
    MenuLabel("Access")
    for (mode in ServerInfo.RUNTIME_MODES.filter { supported.isEmpty() || it in supported }) {
        if (plain) MenuItem(runtimeModeLabel(mode), selected = mode == settings.runtimeMode) { onPick(mode) }
        else MenuItem(runtimeModeLabel(mode), runtimeModeIcon(mode), detail = runtimeModeDetail(mode), selected = mode == settings.runtimeMode) { onPick(mode) }
    }
}

/**
 * T3's switch (switch.tsx): 36x20, the thumb inset 2dp. Read-only; the row around it takes the tap.
 * Redesign measured Capy's: the accent track, off on Capy's `--muted` #353537 in dark, and a solid
 * #131314 knob in dark (white in light) with no shadow.
 */
@Composable
fun Switch(on: Boolean) {
    val c = Candy.colors
    val off = if (c.redesign && c.dark) Color(0xFF353537) else c.input
    val knob = if (c.redesign && c.dark) Color(0xFF131314) else Color.White
    Box(
        Modifier.size(width = 36.dp, height = 20.dp).clip(CircleShape).background(if (on) c.primary else off).padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(knob))
    }
}

/**
 * Redesign's model picker, after Capy's (ModelPickerView under Dither): a slim rail of providers with
 * the one shown on a rounded fill, then that provider's card and its models, one line each with the
 * check at the right. The Mac's search field is left out: a phone lists a handful of models and
 * popups here do not host text fields.
 */
@Composable
private fun RedesignModelPicker(open: Boolean, server: ServerInfo, settings: ComposerSettings, onModel: (ModelSelection) -> Unit, onDismiss: () -> Unit) {
    val c = Candy.colors
    var rail by remember(open) { mutableStateOf(settings.model?.instanceId ?: server.providers.firstOrNull()?.instanceId) }
    Menu(open, onDismiss, side = MenuSide.ABOVE, minWidth = 320.dp, maxWidth = 380.dp) {
        if (server.providers.isEmpty()) {
            MenuLabel("The computer has not listed its models yet.")
            return@Menu
        }
        val p = server.providers.firstOrNull { it.instanceId == rail } ?: server.providers.first()
        Row {
            if (server.providers.size > 1) {
                Column(Modifier.padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (q in server.providers) {
                        val (glyph, tint) = providerMark(q.driver)
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(Radius.md)).background(if (q.instanceId == p.instanceId) c.accent else Color.Transparent)
                                .clickable(role = Role.Tab, onClickLabel = q.name) { rail = q.instanceId }
                                .semantics { contentDescription = q.name },
                            contentAlignment = Alignment.Center,
                        ) { Icon(glyph, if (q.instanceId == p.instanceId) tint else c.mutedForeground, 18.dp) }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                ProviderCard(p)
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
                Spacer(Modifier.height(4.dp))
                for (m in p.models) {
                    val selected = settings.model?.instanceId == p.instanceId && settings.model.model == m.slug
                    MenuItem(m.name, detail = if (!p.ready) "Not ready on the computer" else null, selected = selected, enabled = p.ready) {
                        val kept = settings.model?.options.orEmpty().filter { o -> m.options.any { it.id == o.id } }
                        onModel(ModelSelection(p.instanceId, m.slug, kept))
                        onDismiss()
                    }
                }
            }
        }
    }
}
