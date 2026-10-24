package org.anikodev.candywrapper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.anikodev.candywrapper.core.composer.SlashItem
import org.anikodev.candywrapper.core.composer.SlashMenu

/**
 * T3's composer command menu (ComposerCommandMenu.tsx) for `/`: a popover the composer's width that
 * sits on top of the composer, one row per command with its label, its description and, for a
 * skill, where it comes from. Placed inside the composer card, which it anchors to; [anchor] is the
 * card's bounds in the window, read only while the menu is open. The popup never
 * takes focus, so the keyboard stays up while the user types to filter; a tap outside it calls
 * [onDismiss]. [items] null hides it with T3's 150 ms fade.
 */
@Composable
fun SlashCommandMenu(items: List<SlashItem>?, anchor: () -> IntRect, onPick: (SlashItem) -> Unit, onDismiss: () -> Unit) {
    val c = Candy.colors
    val state = remember { MutableTransitionState(false) }
    state.targetState = items != null
    // The last list stays on screen while the menu fades out.
    val last = remember { arrayOf(emptyList<SlashItem>()) }
    if (items != null) last[0] = items
    if (!state.currentState && !state.targetState && state.isIdle) return
    // The popup window is 4dp wider on each side than the menu so the shadow is not cut.
    val inset = with(LocalDensity.current) { MenuShadowRoom.roundToPx() }
    val position = remember(inset) { AboveAnchor(inset) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        AnimatedVisibility(state, enter = fadeIn(Motion.fast()), exit = fadeOut(Motion.fast())) {
            val shape = RoundedCornerShape(Radius.lg)
            val shown = last[0]
            // As wide as the composer; T3's `max-h-72`, less when the composer sits high above the keyboard.
            val (width, maxHeight) = with(LocalDensity.current) { anchor().let { it.width.toDp() to minOf(288.dp, it.top.toDp() - 56.dp).coerceAtLeast(120.dp) } }
            LazyColumn(
                Modifier.width(width + MenuShadowRoom * 2).heightIn(max = maxHeight).padding(start = MenuShadowRoom, end = MenuShadowRoom, top = MenuShadowRoom, bottom = 6.dp)
                    .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = if (c.dark) 0.4f else 0.12f), spotColor = Color.Black.copy(alpha = if (c.dark) 0.6f else 0.3f))
                    // Redesign: the frosted popover, as its menus.
                    .let { if (c.redesign) it.frost(shape, c.popover.copy(alpha = 0.9f)).clip(shape) else it.clip(shape).background(c.popover) }
                    .border(1.dp, if (c.redesign) c.rim() else c.border, shape)
                    .testTag("slash-menu"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                if (shown.isEmpty()) item("empty") {
                    Txt("No matching command.", CandyType.xs, c.mutedForeground, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
                }
                items(shown, key = { it.id }) { item -> SlashRow(item, width) { onPick(item) } }
            }
        }
    }
}

/**
 * One command: `/name` in medium at most 45% of the row, the muted description after it, and a
 * skill's source on a badge. A skill reads `/skill:` muted and then its display name.
 */
@Composable
private fun SlashRow(item: SlashItem, width: Dp, onClick: () -> Unit) {
    val c = Candy.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Radius.sm))
            .background(if (pressed) c.accent else Color.Transparent)
            .clickable(source, null, role = Role.Button, onClickLabel = item.label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val label = if (item is SlashItem.Skill) buildAnnotatedString {
            withStyle(SpanStyle(color = c.mutedForeground)) { append("/skill:") }
            append(SlashMenu.displayName(item.skill))
        } else buildAnnotatedString { append(item.label) }
        BasicText(
            label, Modifier.widthIn(max = width * 0.45f),
            style = CandyType.smMedium.merge(TextStyle(color = c.foreground)), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Txt(item.description, CandyType.sm, c.mutedForeground, maxLines = 1, modifier = Modifier.weight(1f))
        if (item is SlashItem.Skill) {
            val badge = RoundedCornerShape(Radius.sm)
            Txt(SlashMenu.skillSource(item.skill), CandyType.xsMedium, c.foreground, maxLines = 1, modifier = Modifier.clip(badge).background(c.secondary).padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

private val MenuShadowRoom = 4.dp

/** Puts the popup's bottom on the anchor's top edge, its menu lined up with the anchor's left edge, kept inside the window. */
private class AboveAnchor(private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        (anchorBounds.left - inset).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
    )
}
