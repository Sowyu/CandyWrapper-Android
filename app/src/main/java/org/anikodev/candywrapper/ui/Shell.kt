package org.anikodev.candywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Window width from which the sidebar stays beside the thread, as on desktop (Material's expanded class). */
val WideBreakpoint = 840.dp

/** T3's `--sidebar-width` (16rem), used when the sidebar sits beside the thread. */
val SidebarWidth = 256.dp

/**
 * The phone sidebar's position: 0 closed, 1 open. It moves with the finger and settles over T3's
 * 200 ms sheet timing; nothing animates while it rests.
 */
@Stable
class DrawerState(open: Boolean) {
    internal val fraction = Animatable(if (open) 1f else 0f)
    /** True while any part of the panel is on screen. */
    val visible: Boolean get() = fraction.value > 0f
    val isOpen: Boolean get() = fraction.targetValue == 1f

    // Finger moves reach [dragBy] through launched coroutines, so one can run after the release.
    // Its snapTo would cancel the settle animation and leave the panel stuck part way; moves are
    // only applied between [beginDrag] and the next settle, open or close.
    private var dragging = false

    suspend fun open() { dragging = false; fraction.animateTo(1f, Motion.sheet()) }
    suspend fun close() { dragging = false; fraction.animateTo(0f, Motion.sheet()) }

    /** Call synchronously from the gesture, before launching its moves. */
    internal fun beginDrag() { dragging = true }

    internal suspend fun dragBy(px: Float, widthPx: Float) {
        if (dragging) fraction.snapTo((fraction.value + px / widthPx).coerceIn(0f, 1f))
    }

    /** A fling past 400 px/s goes its way; a slow release settles on the nearer side. */
    internal suspend fun settle(velocity: Float) {
        dragging = false
        val open = when {
            velocity > 400f -> true
            velocity < -400f -> false
            else -> fraction.value > 0.5f
        }
        if (open) open() else close()
    }
}

/** What the thread header needs from the shell: whether to show the sidebar button, and opening it. */
class ShellControls(val wide: Boolean, val openSidebar: () -> Unit)

val LocalShell = staticCompositionLocalOf { ShellControls(wide = true) {} }

/**
 * The app frame: T3's sidebar beside the content on a wide window, and on a phone a full-height
 * drawer over it that opens from the header button or a swipe from the left edge.
 *
 * The phone sidebar is composed shortly after the first frame, so the cold start pays nothing for
 * it and the first swipe finds it ready. While closed it stays composed but is not placed, so it
 * draws nothing.
 */
@Composable
fun AppShell(drawer: DrawerState, sidebar: @Composable () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Candy.colors.background)) {
        val wide = maxWidth >= WideBreakpoint
        if (wide) {
            CompositionProvidesShell(ShellControls(wide = true) {}) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(SidebarWidth).fillMaxHeight()) { sidebar() }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Candy.colors.sidebarBorder))
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
            }
        } else {
            PhoneShell(drawer, maxWidth, sidebar, content)
        }
    }
}

@Composable
private fun CompositionProvidesShell(controls: ShellControls, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalShell provides controls, content = content)
}

@Composable
private fun PhoneShell(drawer: DrawerState, windowWidth: Dp, sidebar: @Composable () -> Unit, content: @Composable () -> Unit) {
    val c = Candy.colors
    val scope = rememberCoroutineScope()
    // T3's mobile sidebar is `100vw - 12px`; this keeps a strip of the thread to tap closed.
    val panelWidth = minOf(windowWidth - 56.dp, 360.dp)
    val widthPx = with(LocalDensity.current) { panelWidth.toPx() }
    val exclusionHalf = with(LocalDensity.current) { 100.dp.toPx() }
    val edgePx = with(LocalDensity.current) { 24.dp.toPx() }
    val drag = rememberDraggableState { delta ->
        drawer.beginDrag()
        scope.launch { drawer.dragBy(delta, widthPx) }
    }
    val showing by remember { derivedStateOf { drawer.visible } }
    var warm by remember { mutableStateOf(drawer.isOpen) }
    LaunchedEffect(Unit) {
        delay(600)
        warm = true
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    CompositionProvidesShell(remember(drawer) { ShellControls(wide = false) { scope.launch { drawer.open() } } }) {
        Box(Modifier.fillMaxSize()) {
            // A swipe from the left edge opens the sidebar. The detector watches the content's
            // touches before it does and claims one only once it is a rightward drag from the edge,
            // so taps near the edge still reach the content.
            Box(Modifier.fillMaxSize().edgeSwipe(edgePx, widthPx, drawer, scope)) { content() }
            // On gesture navigation the system keeps the edge for Back, except the middle 200dp handed to the app here.
            if (!showing) {
                Box(
                    Modifier.align(Alignment.CenterStart).width(20.dp).fillMaxHeight()
                        .systemGestureExclusion { coords ->
                            val mid = coords.size.height / 2f
                            Rect(0f, mid - exclusionHalf, coords.size.width.toFloat(), mid + exclusionHalf)
                        },
                )
            }
            if (showing) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = drawer.fraction.value }.background(c.scrim)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClickLabel = "Close the sidebar") {
                            scope.launch { drawer.close() }
                        }
                        .draggable(drag, Orientation.Horizontal, onDragStopped = { v -> drawer.settle(v) }),
                )
            }
            if (warm || showing) {
                Layout(
                    content = {
                        Box(
                            Modifier.width(panelWidth).fillMaxHeight()
                                .graphicsLayer {
                                    translationX = -(1f - drawer.fraction.value) * size.width
                                    shadowElevation = if (drawer.fraction.value > 0f) 16.dp.toPx() else 0f
                                }
                                .background(c.sidebar)
                                .draggable(drag, Orientation.Horizontal, onDragStopped = { v -> drawer.settle(v) }),
                        ) {
                            sidebar()
                            Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(c.sidebarBorder))
                        }
                    },
                ) { measurables, constraints ->
                    val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        if (showing) placeables.forEach { it.place(0, 0) }
                    }
                }
            }
        }
    }
}

/**
 * Opens the drawer with a rightward drag that starts within [edgePx] of the left edge while it is
 * closed. It stays attached for the whole gesture: the drawer becoming visible mid-drag must not
 * cancel the drag that is opening it.
 */
private fun Modifier.edgeSwipe(edgePx: Float, widthPx: Float, drawer: DrawerState, scope: kotlinx.coroutines.CoroutineScope): Modifier =
    pointerInput(edgePx, widthPx, drawer) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgePx || drawer.visible) return@awaitEachGesture
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var dragging = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                velocity.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!dragging) {
                    if (dx > viewConfiguration.touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        drawer.beginDrag()
                    }
                    else if (abs(dy) > viewConfiguration.touchSlop || dx < -viewConfiguration.touchSlop) break
                }
                if (dragging) {
                    val step = change.position.x - change.previousPosition.x
                    scope.launch { drawer.dragBy(step, widthPx) }
                    change.consume()
                }
            }
            if (dragging) scope.launch { drawer.settle(velocity.calculateVelocity().x) }
        }
    }
