package org.anikodev.candywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Modifier.Node
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.text.Normalizer
import org.anikodev.candywrapper.core.media.ProjectIcons
import org.anikodev.candywrapper.core.model.ProjectIconOverride
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.data.ProjectIconSource
import kotlinx.coroutines.launch

// T3's controls (apps/web/src/components/ui/*), built from the tokens in Theme.kt. Sizes are T3's
// below its `sm` breakpoint, the ones it uses on touch screens. Motion is T3's: 150 ms colour and
// press-scale transitions, popups that fade and scale in from their trigger. Nothing loops.

/** A pressed control draws [color] over itself, with no ripple: T3's `:active` fill. */
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
    style: TextStyle = CandyType.sm,
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

/** A lucide icon in [tint]; `Color.Unspecified` keeps a brand glyph's own colours. */
@Composable
fun Icon(icon: ImageVector, tint: Color = Candy.colors.iconMuted, size: Dp = 16.dp, modifier: Modifier = Modifier, description: String? = null) {
    // Redesign draws the Hugeicons glyph where one is mapped, at its own size centred in the box.
    val huge = if (Candy.redesign) remember(icon.name, size) { HugeIcons.of(icon.name, HugeIcons.glyph(size.value)) } else null
    Image(
        rememberVectorPainter(huge ?: icon), description, modifier.size(size),
        contentScale = if (huge != null) ContentScale.None else ContentScale.Fit,
        colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
    )
}

/** A row or control that reacts to taps. */
fun Modifier.rowClick(enabled: Boolean = true, label: String? = null, onClick: () -> Unit) =
    clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)

/** T3's `active:scale-[0.97]`, over 150 ms, while [source] is pressed. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.fast(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * T3's ghost icon button (`size="icon"`): 36dp, the icon at 18dp (`size-4.5` on touch), an accent
 * fill while pressed, inside a 44dp touch target. [description] is read by TalkBack.
 */
@Composable
fun IconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = Candy.colors.iconMuted,
    enabled: Boolean = true,
    size: Dp = 18.dp,
    box: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(maxOf(box, 44.dp)).clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val pressed by source.collectIsPressedAsState()
        Box(
            Modifier.size(box).pressScale(source).clip(RoundedCornerShape(Radius.md)).background(if (pressed) c.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, if (enabled) tint else tint.copy(alpha = 0.64f), size)
        }
    }
}

/** T3's button variants (button.tsx). [SECONDARY] is `outline`, the default for plain actions. */
enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DESTRUCTIVE, DESTRUCTIVE_OUTLINE, WARNING_OUTLINE }

/** T3's button sizes on touch: `default` h-9, `sm` h-8, `xs` h-7. */
enum class ButtonSize(val height: Dp, val padding: Dp, val icon: Dp) { DEFAULT(36.dp, 11.dp, 18.dp), SM(32.dp, 9.dp, 16.dp), XS(28.dp, 7.dp, 16.dp) }

/**
 * T3's button: `rounded-[--control-radius]` (8), a 1px border, `font-medium text-base` on touch
 * (`text-sm` at xs), scale 0.97 while pressed, at least a 44dp touch target.
 */
@Composable
fun CButton(
    label: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.DEFAULT,
    onClick: () -> Unit,
) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    val outlineFill = if (c.dark) c.input.copy(alpha = c.input.alpha * 0.32f) else c.popover
    val (fill, text, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(c.primary, c.primaryForeground, c.primary)
        ButtonKind.SECONDARY -> Triple(outlineFill, c.foreground, c.input)
        ButtonKind.GHOST -> Triple(Color.Transparent, c.foreground, Color.Transparent)
        ButtonKind.DESTRUCTIVE -> Triple(c.error, Color.White, c.error)
        ButtonKind.DESTRUCTIVE_OUTLINE -> Triple(outlineFill, c.errorForeground, c.input)
        ButtonKind.WARNING_OUTLINE -> Triple(c.warningSurface, c.warningForeground, c.warning.copy(alpha = 0.32f))
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressedFill = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.DESTRUCTIVE -> fill.copy(alpha = 0.9f)
        ButtonKind.GHOST, ButtonKind.SECONDARY, ButtonKind.DESTRUCTIVE_OUTLINE -> c.accent
        ButtonKind.WARNING_OUTLINE -> c.warning.copy(alpha = if (c.dark) 0.24f else 0.16f)
    }
    Box(
        modifier.heightIn(min = 44.dp).clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(size.height).pressScale(source).graphicsLayer { alpha = if (enabled) 1f else 0.64f }
                .clip(shape).background(fill).let { if (pressed) it.background(pressedFill) else it }
                .border(1.dp, border, shape).padding(horizontal = size.padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (size == ButtonSize.DEFAULT) 8.dp else 6.dp),
        ) {
            if (icon != null) Icon(icon, text, size.icon)
            Txt(label, if (size == ButtonSize.XS) CandyType.smMedium else CandyType.baseMedium, text, maxLines = 1)
        }
    }
}

/**
 * A composer or toolbar picker trigger (ComposerControl.tsx): a ghost control with a leading icon,
 * the label in `text-muted-foreground` that turns foreground while open, and a chevron.
 */
@Composable
fun SelectChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Candy.colors.mutedForeground,
    description: String = label,
    enabled: Boolean = true,
    open: Boolean = false,
    showLabel: Boolean = true,
    labelMax: Dp = 160.dp,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(modifier.heightIn(min = 44.dp).clickable(source, null, enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(Radius.md)).background(if (pressed || open) c.accent else Color.Transparent)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, iconTint, 16.dp)
            if (showLabel) Txt(label, CandyType.sm, if (!enabled) c.mutedForeground.copy(alpha = 0.64f) else if (open) c.foreground else c.mutedForeground, maxLines = 1, modifier = Modifier.widthIn(max = labelMax))
            if (chevron) Icon(Icons.ChevronDown, c.mutedForeground.copy(alpha = 0.8f), 14.dp)
        }
    }
}

/** T3's input (input.tsx): `rounded-lg border-input bg-background`, 36dp, `text-base` on touch. */
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
    style: TextStyle = CandyType.base,
    label: String? = null,
    leading: ImageVector? = null,
) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Txt(label, CandyType.smMedium, c.foreground)
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(shape)
                .background(if (c.dark) c.input.copy(alpha = c.input.alpha * 0.32f) else c.background)
                .border(1.dp, c.input, shape).semantics { contentDescription = label ?: placeholder },
            textStyle = style.merge(TextStyle(color = c.foreground)),
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboard,
            keyboardActions = actions,
            visualTransformation = visual,
            cursorBrush = SolidColor(c.primary),
            decorationBox = { inner ->
                Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) {
                        Icon(leading, c.mutedForeground, 16.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Txt(placeholder, style, c.placeholder, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                        inner()
                    }
                }
            },
        )
    }
}

/**
 * A screen header after T3's chat header (ChatHeader.tsx): 52dp, the leading control, the title in
 * `text-sm font-medium` a step up for a phone, an optional muted line under it, actions at the end,
 * one hairline under it.
 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = Candy.colors.mutedForeground,
    onTitleClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    titleLead: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Candy.colors
    Column(Modifier.fillMaxWidth().page().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                onBack != null -> IconButton(Icons.ChevronLeft, "Back", tint = c.foreground, size = 20.dp, onClick = onBack)
                leading != null -> leading()
                else -> Spacer(Modifier.width(12.dp))
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.md))
                    .let { if (onTitleClick != null) it.clickable(role = Role.Button, onClickLabel = "Thread actions", onClick = onTitleClick) else it }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (titleLead != null) {
                    titleLead()
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Txt(title, CandyType.baseMedium.copy(fontSize = 15.sp, lineHeight = 20.sp), c.foreground, maxLines = 1, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) Txt(subtitle, CandyType.xs, subtitleColor, maxLines = 1)
                }
            }
            actions()
        }
        // Redesign has no header bars: the art runs up under the header (the user on the Mac, 2026-10-21).
        if (!c.redesign) Divider()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Candy.colors.border) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** T3's badge (badge.tsx): `rounded-sm text-xs font-medium` on a tint of its colour. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(Radius.sm)).background(color.copy(alpha = color.alpha * 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, color, 12.dp)
        Txt(text, CandyType.xsMedium, color, maxLines = 1)
    }
}

enum class NoticeTone { NEUTRAL, INFO, WARNING, ERROR, SUCCESS }

/**
 * T3's alert (alert.tsx): `rounded-xl border px-3.5 py-3 text-sm`, an icon in the tone's colour,
 * and the action that fixes it.
 */
@Composable
fun Notice(text: String, color: Color, surface: Color, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val c = Candy.colors
    val tone = when (color) {
        c.errorForeground, c.error -> NoticeTone.ERROR
        c.warningForeground, c.warning -> NoticeTone.WARNING
        c.infoForeground, c.info -> NoticeTone.INFO
        c.successForeground, c.success -> NoticeTone.SUCCESS
        else -> NoticeTone.NEUTRAL
    }
    Alert(text, tone, modifier, action = action, onAction = onAction)
}

@Composable
fun Alert(text: String, tone: NoticeTone, modifier: Modifier = Modifier, title: String? = null, action: String? = null, onAction: () -> Unit = {}, onDismiss: (() -> Unit)? = null) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.xl)
    val (border, fill, fg, icon, iconTint) = when (tone) {
        NoticeTone.NEUTRAL -> AlertLook(c.border, if (c.dark) c.input.copy(alpha = c.input.alpha * 0.32f) else Color.Transparent, c.foreground, Icons.Info, c.mutedForeground)
        NoticeTone.INFO -> AlertLook(c.info.copy(alpha = 0.32f), c.info.copy(alpha = 0.04f), c.foreground, Icons.Info, c.info)
        NoticeTone.WARNING -> AlertLook(c.warning.copy(alpha = 0.32f), c.warningSurface, c.warningForeground, Icons.TriangleAlert, c.warning)
        NoticeTone.ERROR -> AlertLook(c.error.copy(alpha = 0.32f), c.errorSurface, c.errorForeground, Icons.CircleAlert, c.error)
        NoticeTone.SUCCESS -> AlertLook(c.success.copy(alpha = 0.32f), c.success.copy(alpha = 0.04f), c.foreground, Icons.CircleCheck, c.success)
    }
    Row(
        modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, border, shape).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, iconTint, 16.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (title != null) Txt(title, CandyType.smMedium, fg)
            Txt(text, CandyType.sm, if (title != null) fg.copy(alpha = 0.8f) else fg)
        }
        if (action != null) CButton(action, kind = ButtonKind.GHOST, size = ButtonSize.SM, onClick = onAction)
        if (onDismiss != null) IconButton(Icons.X, "Dismiss", tint = c.mutedForeground, size = 14.dp, box = 32.dp, onClick = onDismiss)
    }
}

private data class AlertLook(val border: Color, val fill: Color, val fg: Color, val icon: ImageVector, val iconTint: Color)

/**
 * A group of rows on T3's card (`rounded-xl border bg-card`). Over Redesign's art it is Capy's
 * settings card, #222223 at 86% with a faint rim.
 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Candy.colors
    val onArt = LocalArtHost.current != null
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (onArt) c.settingsCard() else c.card).border(1.dp, if (onArt) c.rim() else c.border, shape),
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
    leading: (@Composable () -> Unit)? = null,
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, if (enabled) c.mutedForeground else c.mutedForeground.copy(alpha = 0.64f), 18.dp)
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Txt(label, CandyType.base, if (enabled) labelColor else c.mutedForeground, maxLines = 2)
            if (detail != null) Txt(detail, CandyType.sm, c.mutedForeground, maxLines = 3)
        }
        if (trailing != null) trailing()
        if (selected) Icon(Icons.Check, c.foreground, 18.dp)
    }
}

/**
 * T3's `Switch` at its touch size: a 38x22dp track, `bg-primary` when on and `bg-input` when off,
 * a 18dp thumb that slides over 150 ms. The whole [ListRow] it sits in toggles it.
 */
@Composable
fun Switch(on: Boolean, modifier: Modifier = Modifier) {
    val c = Candy.colors
    val x by animateFloatAsState(if (on) 1f else 0f, Motion.fast(), label = "switch")
    Box(
        modifier.size(38.dp, 22.dp).clip(CircleShape).background(if (on) c.primary else c.input).padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.graphicsLayer { translationX = x * 16.dp.toPx() }.size(18.dp).clip(CircleShape)
                .background(if (!on && c.dark) c.mutedForeground else c.background),
        )
    }
}

/** A setting that is on or off: the [ListRow] toggles a trailing [Switch]. */
@Composable
fun SwitchRow(label: String, on: Boolean, detail: String? = null, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    ListRow(
        label, Modifier.semantics { stateDescription = if (on) "On" else "Off" }, detail = detail, icon = icon,
        trailing = { Switch(on) },
    ) { onChange(!on) }
}

/**
 * T3's dialog as it sits on a phone (dialog.tsx `bottomStickOnMobile`): pinned to the bottom edge,
 * `rounded-t-2xl`, popover fill, over the `dialog-backdrop`. It rises in over 200 ms; Back and a tap
 * on the backdrop close it.
 */
@Composable
fun BoxScope.Sheet(
    onDismiss: () -> Unit,
    title: String? = null,
    description: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Candy.colors
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = fadeIn(Motion.sheet()), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(c.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        )
    }
    val shape = RoundedCornerShape(topStart = Radius.xxl, topEnd = Radius.xxl)
    AnimatedVisibility(
        shown, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(Motion.sheet()) { it / 3 } + fadeIn(Motion.sheet()),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(shape).background(c.popover).border(1.dp, c.border, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(BottomInsets),
        ) {
            if (title != null || description != null) {
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (title != null) Txt(title, CandyType.xl, c.foreground, maxLines = 2, modifier = Modifier.semantics { heading() })
                    if (description != null) Txt(description, CandyType.sm, c.mutedForeground)
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            if (footer != null) {
                Divider()
                Row(
                    Modifier.fillMaxWidth().background(c.muted.copy(alpha = c.muted.alpha * 0.72f)).padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Where a [Menu] opens against its trigger. */
enum class MenuSide { BELOW, ABOVE }

/**
 * T3's menu popup (menu.tsx): `rounded-lg border bg-popover p-1` with its soft drop shadow, opening
 * from the trigger it is placed next to. It fades and scales in from 95% over 150 ms, flips to the
 * other side when there is no room, and keeps 8dp from the screen edges.
 */
@Composable
fun Menu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    side: MenuSide = MenuSide.BELOW,
    alignEnd: Boolean = false,
    minWidth: Dp = 200.dp,
    maxWidth: Dp = 320.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Candy.colors
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState && state.isIdle) return
    val provider = remember(side, alignEnd) { MenuPosition(side, alignEnd) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(Radius.lg)
        val origin = TransformOrigin(if (alignEnd) 1f else 0f, if (provider.flipped xor (side == MenuSide.ABOVE)) 1f else 0f)
        AnimatedVisibility(
            state,
            enter = fadeIn(Motion.fast()) + scaleIn(Motion.fast(), initialScale = 0.95f, transformOrigin = origin),
            exit = fadeOut(Motion.fast()) + scaleOut(Motion.fast(), targetScale = 0.95f, transformOrigin = origin),
        ) {
            Column(
                Modifier.padding(4.dp).widthIn(min = minWidth, max = maxWidth)
                    .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = if (c.dark) 0.4f else 0.12f), spotColor = Color.Black.copy(alpha = if (c.dark) 0.6f else 0.3f))
                    // Redesign's menus are glass: the popover at 90% over the blurred art (PopupSurface's frosted pane).
                    .let { if (c.redesign) it.frost(shape, c.popover.copy(alpha = 0.9f)).clip(shape) else it.clip(shape).background(c.popover) }
                    .border(1.dp, if (c.redesign) c.rim() else c.border, shape)
                    .heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(4.dp),
                content = content,
            )
        }
    }
}

private class MenuPosition(private val side: MenuSide, private val alignEnd: Boolean) : PopupPositionProvider {
    var flipped = false

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = (windowSize.width / 50).coerceIn(8, 24)
        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val fitsBelow = below + popupContentSize.height <= windowSize.height - margin
        val fitsAbove = above >= margin
        val y = when (side) {
            MenuSide.BELOW -> if (fitsBelow || !fitsAbove) below.also { flipped = false } else above.also { flipped = true }
            MenuSide.ABOVE -> if (fitsAbove || !fitsBelow) above.also { flipped = false } else below.also { flipped = true }
        }
        val x = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(
            x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/**
 * A menu item (menu.tsx): `min-h-8 rounded-sm px-2 text-base` on touch with an 18dp muted icon. The
 * pressed item and the checked one of a radio group take `bg-accent`, as in T3's pickers. [detail]
 * adds the muted second line the access picker uses. Redesign marks the checked one with Capy's
 * check at the right instead of a fill.
 */
@Composable
fun MenuItem(
    label: String,
    icon: ImageVector? = null,
    detail: String? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    iconTint: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fg = if (destructive) c.errorForeground else c.foreground
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.sm))
            .background(if (pressed || (selected && !c.redesign)) c.accent else Color.Transparent)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.64f }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = if (detail != null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, iconTint ?: if (destructive) fg else c.mutedForeground.copy(alpha = 0.8f), 18.dp, Modifier.padding(top = if (detail != null) 3.dp else 0.dp))
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Txt(label, CandyType.base, fg, maxLines = 2)
            if (detail != null) Txt(detail, CandyType.sm, c.mutedForeground)
        }
        if (trailing != null) trailing()
        if (selected && c.redesign) Icon(Icons.Check, c.foreground, 16.dp, Modifier.padding(top = if (detail != null) 3.dp else 0.dp))
    }
}

/** T3's "Default" tag in the reasoning menu: an outlined `text-xs` badge. */
@Composable
fun DefaultTag() {
    val c = Candy.colors
    Txt(
        "Default", CandyType.xsMedium, c.foreground, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(Radius.sm)).border(1.dp, c.input, RoundedCornerShape(Radius.sm)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** `MenuSeparator`: `-mx-1 my-1 h-px bg-border`. */
@Composable
fun MenuSeparator() {
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Candy.colors.border))
}

/** `MenuGroupLabel`: `px-2 py-1.5 text-xs font-medium text-muted-foreground`. */
@Composable
fun MenuLabel(text: String) {
    Txt(text, CandyType.xsMedium, Candy.colors.mutedForeground, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}

/** T3's empty state (empty.tsx): a bordered icon tile, a title and a muted line, centred. */
@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier, icon: ImageVector? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Candy.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier.padding(bottom = 8.dp).size(36.dp).clip(RoundedCornerShape(Radius.md)).background(c.card).border(1.dp, c.border, RoundedCornerShape(Radius.md)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, c.foreground, 18.dp) }
        }
        Txt(title, CandyType.baseMedium.copy(fontSize = 17.sp), c.foreground, align = TextAlign.Center)
        if (body != null) Txt(body, CandyType.sm, c.mutedForeground, align = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = ButtonRow, content = actions)
    }
}

/**
 * A sentence-case label over a group of settings (SettingsSection's `text-xs font-medium`). Redesign
 * titles a section as Capy does, 15 regular in the foreground.
 */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    val c = Candy.colors
    Txt(
        text, if (c.redesign) CandyType.base.copy(fontSize = 15.sp, lineHeight = 20.sp) else CandyType.xsMedium, if (c.redesign) c.foreground else c.mutedForeground,
        modifier = modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** Tailwind's -600 and -400 shades for the 18 colours `projectIdentity.ts` picks from. */
private val MonogramColors = listOf(
    0xFF4A5565 to 0xFF99A1AF, 0xFFE7000B to 0xFFFF6467, 0xFFF54A00 to 0xFFFF8904, 0xFFE17100 to 0xFFFFBA00,
    0xFFD08700 to 0xFFFDC700, 0xFF5EA500 to 0xFF9AE600, 0xFF00A63E to 0xFF05DF72, 0xFF009966 to 0xFF00D492,
    0xFF009689 to 0xFF00D5BE, 0xFF0092B8 to 0xFF00D3F3, 0xFF0084D1 to 0xFF00BCFF, 0xFF155DFC to 0xFF51A2FF,
    0xFF4F39F6 to 0xFF7C86FF, 0xFF8022FE to 0xFFA684FF, 0xFF9810FA to 0xFFC27AFF, 0xFFC800DE to 0xFFED6BFF,
    0xFFE60076 to 0xFFFB64B6, 0xFFEC003F to 0xFFFF637E,
)

/** `projectIdentity.ts`: two letters and a colour index for a project title. */
fun projectIdentity(title: String): Pair<String, Int> {
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
    val words = normalized.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    var monogram = "PR"
    words.firstOrNull()?.let { first ->
        val head = first.substring(0, first.offsetByCodePoints(0, 1))
        val rest = first.substring(head.length)
        val digit = rest.firstOrNull { it.isDigit() }?.toString()
        val second = digit ?: if (words.size > 1) words.last().take(1) else first.takeLast(1)
        monogram = (head + second).uppercase().take(2)
    }
    val seed = normalized.lowercase().ifEmpty { "project" }
    var index = 0
    seed.codePoints().forEach { index = (index * 31 + it) % MonogramColors.size }
    return monogram to index
}

/**
 * `ProjectMonogram` (ProjectMonogram.tsx): a 16dp tile at 25% radius, the project's colour at 14%
 * behind bold monospace letters squeezed to 6 or 12 units of the 16.
 */
@Composable
fun Monogram(title: String, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val (letters, index) = remember(title) { projectIdentity(title) }
    Monogram(letters, index, modifier, size)
}

/** A monogram with chosen [letters] in the colour at [index] of `ProjectIconColor`. */
@Composable
fun Monogram(letters: String, index: Int, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val c = Candy.colors
    val color = Color(if (c.dark) MonogramColors[index].second else MonogramColors[index].first)
    val measurer = rememberTextMeasurer(cacheSize = 4)
    // 8.25px in the 16px tile whatever the system font scale, since the tile does not grow either.
    val fontSize = with(LocalDensity.current) { 8.25.dp.toSp() }
    val style = remember(color, fontSize) { TextStyle(color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = fontSize) }
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 16f
        drawRoundRect(color.copy(alpha = 0.14f), size = this.size, cornerRadius = CornerRadius(4 * unit))
        val layout = measurer.measure(letters, style)
        val target = (if (letters.length == 1) 6f else 12f) * unit
        val sx = if (layout.size.width > 0) target / layout.size.width else 1f
        val sy = this.size.width / 16.dp.toPx()
        scale(sx, sy, pivot = Offset(this.size.width / 2, this.size.height / 2)) {
            drawText(layout, topLeft = Offset((this.size.width - layout.size.width) / 2f, (this.size.height - layout.size.height) / 2f))
        }
    }
}

/** The app's project icons; null in screenshot tests that draw monograms only. */
val LocalProjectIcons = staticCompositionLocalOf<ProjectIconSource?> { null }

/**
 * `ProjectFavicon`: the project's chosen icon, else its icon file, else the monogram for [label].
 * The file is a thumbnail drawn at [size] with T3's `rounded-[25%] object-contain`; the monogram
 * holds the same box until it lands, so nothing moves. A lucide choice draws the monogram in the
 * chosen colour, as T3's mobile app does without the lucide set.
 */
@Composable
fun ProjectIcon(project: ProjectSummary?, label: String, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val icon = project?.icon
    when (icon?.kind) {
        ProjectIconOverride.Kind.MONOGRAM -> Monogram(icon.text, ProjectIcons.COLORS.indexOf(icon.color).coerceAtLeast(0), modifier, size)
        ProjectIconOverride.Kind.LUCIDE -> Monogram(remember(label) { projectIdentity(label).first }, ProjectIcons.COLORS.indexOf(icon.color).coerceAtLeast(0), modifier, size)
        ProjectIconOverride.Kind.EMOJI -> Box(modifier.size(size), contentAlignment = Alignment.Center) {
            // `text-[length:80cqh]`: the emoji fills 80% of the box whatever the font scale.
            val fontSize = with(LocalDensity.current) { (size * 0.8f).toSp() }
            BasicText(icon.text, style = TextStyle(fontSize = fontSize, lineHeight = fontSize), maxLines = 1)
        }
        null -> {
            val icons = LocalProjectIcons.current
            val wanted = project?.takeIf { icons != null && ProjectIcons.wantsFile(it) }
            if (icons != null && wanted != null) LaunchedEffect(icons, wanted) { icons.refresh(wanted) }
            val image = if (icons != null && wanted != null) icons.peek(wanted) else null
            if (image != null) Image(image, null, modifier.size(size).clip(RoundedCornerShape(percent = 25)), contentScale = ContentScale.Fit)
            else Monogram(label, modifier, size)
        }
    }
}

val ButtonRow = Arrangement.spacedBy(8.dp)

/** The keyboard when it is up, else the navigation bar: what a bottom-anchored screen pads by. */
val BottomInsets: WindowInsets
    @Composable get() = WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)

val ScreenPadding = PaddingValues(horizontal = 16.dp)
