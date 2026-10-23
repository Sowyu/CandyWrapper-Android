package org.anikodev.candywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier.Node
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The app's own controls, built from the tokens in Theme.kt. Nothing here animates: a press
// shows a flat overlay while the finger is down, and that is the only feedback.

/** A pressed control draws [color] over itself, with no ripple and no animation. */
class PressIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color)
    override fun equals(other: Any?) = other is PressIndication && other.color == color
    override fun hashCode() = color.hashCode()

    private class PressNode(private val source: InteractionSource, private val color: Color) : Node(), DrawModifierNode {
        private var pressed = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                        else -> return@collect
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed > 0) drawRect(color)
        }
    }
}

/** Text in the theme's foreground unless told otherwise. */
@Composable
fun Txt(
    text: String,
    style: TextStyle = CandyType.ui,
    color: Color = Candy.colors.foreground,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    weight: FontWeight? = null,
    align: TextAlign? = null,
) {
    BasicText(
        text,
        modifier,
        style = style.merge(TextStyle(color = color, fontWeight = weight ?: style.fontWeight, textAlign = align ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun Icon(icon: ImageVector, tint: Color = Candy.colors.iconMuted, size: Dp = 18.dp, modifier: Modifier = Modifier, description: String? = null) {
    Image(
        rememberVectorPainter(icon), description, modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** A 44dp-tall row or control that reacts to taps. */
fun Modifier.rowClick(enabled: Boolean = true, label: String? = null, onClick: () -> Unit) =
    clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)

/** A square icon button with a 44dp touch target. [description] is read by TalkBack. */
@Composable
fun IconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = Candy.colors.iconMuted,
    enabled: Boolean = true,
    size: Dp = 20.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(Radius.lg)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (enabled) tint else tint.copy(alpha = 0.4f), size)
    }
}

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DESTRUCTIVE }

/** T3's button: 36dp tall inside a 44dp target, radius 8, solid or outlined. */
@Composable
fun CButton(
    label: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    val (fill, text, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(c.primary, c.primaryForeground, Color.Transparent)
        ButtonKind.SECONDARY -> Triple(c.card, c.foreground, c.input)
        ButtonKind.GHOST -> Triple(Color.Transparent, c.foreground, Color.Transparent)
        ButtonKind.DESTRUCTIVE -> Triple(c.errorSurface, c.error, Color.Transparent)
    }
    Box(modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(36.dp).clip(shape).background(fill).border(1.dp, border, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp)
                .let { if (enabled) it else it.background(c.background.copy(alpha = 0.4f)) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, text, 16.dp)
            Txt(label, CandyType.uiMedium, if (enabled) text else text.copy(alpha = 0.5f), maxLines = 1)
        }
    }
}

/** A picker trigger: label, optional leading icon and a chevron, in a 32dp control. */
@Composable
fun SelectChip(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, description: String = label, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Candy.colors
    Box(modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(Radius.md)).clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, c.mutedForeground, 15.dp)
            Txt(label, CandyType.small, if (enabled) c.foreground else c.mutedForeground, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
            Icon(Icons.ChevronDown, c.mutedForeground, 13.dp)
        }
    }
}

/** A single- or multi-line field with T3's input border. */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    style: TextStyle = CandyType.body,
    label: String? = null,
) {
    val c = Candy.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Txt(label, CandyType.label, c.foreground)
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Radius.md)).background(c.card)
                .border(1.dp, c.input, RoundedCornerShape(Radius.md)).semantics { contentDescription = label ?: placeholder },
            textStyle = style.merge(TextStyle(color = c.foreground)),
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboard,
            keyboardActions = actions,
            visualTransformation = visual,
            cursorBrush = SolidColor(c.primary),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Txt(placeholder, style, c.mutedForeground, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                    inner()
                }
            },
        )
    }
}

/** Screen header: back chevron, title with an optional subtitle, actions on the right. No bottom shadow, one hairline. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = Candy.colors.mutedForeground,
    onTitleClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                onBack != null -> IconButton(Icons.ChevronLeft, "Back", tint = c.foreground, size = 22.dp, onClick = onBack)
                leading != null -> leading()
                else -> Spacer(Modifier.width(12.dp))
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.md))
                    .let { if (onTitleClick != null) it.clickable(role = Role.Button, onClickLabel = "Thread actions", onClick = onTitleClick) else it }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Txt(title, CandyType.title, c.foreground, maxLines = 1, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Txt(subtitle, CandyType.meta, subtitleColor, maxLines = 1)
            }
            actions()
        }
        Divider()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Candy.colors.border) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** A small label with a tinted background, for statuses. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Txt(
        text, CandyType.label, color, maxLines = 1,
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.sm)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** An inline notice: what happened, and the action that fixes it. */
@Composable
fun Notice(text: String, color: Color, surface: Color, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(surface).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, CandyType.small, color, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (action != null) CButton(action, kind = ButtonKind.GHOST, onClick = onAction)
    }
}

/** A group of settings rows on a card with T3's border and radius. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Candy.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.card).border(1.dp, c.border, RoundedCornerShape(Radius.lg)),
        content = content,
    )
}

/** One tappable line in a [Card] or a [Sheet]: icon, label, detail and a trailing slot. */
@Composable
fun ListRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    labelColor: Color = Candy.colors.foreground,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Candy.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .let { if (onClick != null) it.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, if (enabled) c.iconMuted else c.iconMuted.copy(alpha = 0.4f), 18.dp)
        Column(Modifier.weight(1f)) {
            Txt(label, CandyType.ui, if (enabled) labelColor else c.mutedForeground, maxLines = 2)
            if (detail != null) Txt(detail, CandyType.meta, c.mutedForeground, maxLines = 3)
        }
        if (trailing != null) trailing()
        if (selected) Icon(Icons.Check, c.primary, 18.dp)
    }
}

/**
 * A bottom sheet drawn over the screen: scrim, then a card from the bottom edge. Back and a tap on
 * the scrim close it. No slide animation; it appears on the next frame.
 */
@Composable
fun BoxScope.Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Candy.colors
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.matchParentSize().background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
    )
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp)
            .clip(RoundedCornerShape(topStart = Radius.composer, topEnd = Radius.composer)).background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(topStart = Radius.composer, topEnd = Radius.composer))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(BottomInsets).padding(bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(c.border, CircleShape))
        }
        if (title != null) {
            Txt(title, CandyType.uiMedium, c.mutedForeground, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
        }
        Column(Modifier.verticalScroll(rememberScrollState()), content = content)
    }
}

/** A centred message for an empty screen, with an optional action. */
@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Candy.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(title, CandyType.heading, c.foreground)
        if (body != null) Txt(body, CandyType.ui, c.mutedForeground)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = ButtonRow, content = actions)
    }
}

/** A sentence-case label over a group of settings. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text, CandyType.uiMedium, Candy.colors.mutedForeground, modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() })
}

/** A monogram for a project: up to two letters on a muted square, like T3's `ProjectMonogram`. */
@Composable
fun Monogram(title: String, modifier: Modifier = Modifier) {
    val c = Candy.colors
    val letters = remember(title) {
        val words = title.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}"
            words.size == 1 -> words[0].take(2)
            else -> "?"
        }.uppercase()
    }
    Box(modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp).clip(RoundedCornerShape(5.dp)).background(c.warningSurface), contentAlignment = Alignment.Center) {
        Txt(letters, CandyType.meta.copy(fontSize = CandyType.meta.fontSize * 0.75f), c.warning, weight = FontWeight.Bold)
    }
}

val ButtonRow = Arrangement.spacedBy(8.dp)

/** The keyboard when it is up, else the navigation bar: what a bottom-anchored screen pads by. */
val BottomInsets: WindowInsets
    @Composable get() = WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)

val ScreenPadding = PaddingValues(horizontal = 16.dp)
