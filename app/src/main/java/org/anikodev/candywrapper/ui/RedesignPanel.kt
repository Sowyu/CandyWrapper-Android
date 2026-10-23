package org.anikodev.candywrapper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.timing.Timeline

/** How much of the art a page shows (DitherArtView.Emphasis): the new thread, an open thread, Settings, any other page. */
enum class ArtEmphasis { HERO, THREAD, SETTINGS, PAGE }

/**
 * The rendered art: one pixel per dot, and its pre-blurred copy for frosted panes. Rendered once per
 * picture, canvas size and background off the main thread, then cached as a PNG in the app's cache.
 */
class DitherArtStore(private val filesDir: File, cacheDir: File, private val renderOnPeek: Boolean = false) {
    data class Key(val picture: String, val width: Int, val height: Int, val background: Int)

    class Art(val key: Key, val image: ImageBitmap, val frost: ImageBitmap, val frostFactor: Int)

    private val dir = File(cacheDir, "redesign-art")
    @Volatile private var last: Art? = null

    /** The art for [key] if it is in memory. A store made with `renderOnPeek` (screenshot tests) renders it here. */
    fun peek(key: Key): Art? = last?.takeIf { it.key == key } ?: if (renderOnPeek) loadBlocking(key) else null

    suspend fun load(key: Key): Art? = peek(key) ?: withContext(Dispatchers.Default) { loadBlocking(key) }

    /** Reads the cached canvas or renders it. Tests call it directly. */
    fun loadBlocking(key: Key): Art? {
        last?.takeIf { it.key == key }?.let { return it }
        val start = Timeline.nowMs()
        val file = File(dir, name(key))
        var cached = false
        val px = file.takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.takeIf { it.width == key.width && it.height == key.height }?.let { b ->
                IntArray(key.width * key.height).also { b.getPixels(it, 0, key.width, 0, 0, key.width, key.height); b.recycle() }
            }
        }?.also { cached = true } ?: DitherPictures.pixels(key.picture, filesDir, key.width, key.height)?.also { p ->
            DitherArt.dither(p, key.width, key.height, key.background)
            save(file, p, key.width, key.height)
        } ?: return null
        val (blur, bw, bh) = DitherArt.blurred(px, key.width, key.height)
        val art = Art(
            key,
            Bitmap.createBitmap(px, key.width, key.height, Bitmap.Config.ARGB_8888).asImageBitmap(),
            Bitmap.createBitmap(blur, bw, bh, Bitmap.Config.ARGB_8888).asImageBitmap(),
            key.width / bw,
        )
        last = art
        Timeline.span("redesign.art", start, "${key.width}x${key.height} ${if (cached) "cached" else "rendered"}")
        return art
    }

    private fun save(file: File, px: IntArray, w: Int, h: Int) = runCatching {
        dir.mkdirs()
        // A handful of sizes and both appearances; older canvases go.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        val tmp = File(dir, file.name + ".tmp")
        tmp.outputStream().use { Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(file)
    }

    private fun name(key: Key) =
        "v1-${key.picture.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${key.width}x${key.height}-${Integer.toHexString(key.background)}.png"
}

/** The art behind the main panel; null outside Redesign. */
val LocalArtStore = staticCompositionLocalOf<DitherArtStore?> { null }

/** What frosted panes and pages read from the panel they sit on. */
@Stable
class ArtHost {
    var art by mutableStateOf<DitherArtStore.Art?>(null)
    /** The panel's top left in the root, so a pane can line the blurred art up under itself. */
    var origin by mutableStateOf(Offset.Zero)
    var dotPx by mutableStateOf(1)
    internal val emphases = mutableStateListOf<ArtEmphasis>()
    val emphasis: ArtEmphasis get() = emphases.lastOrNull() ?: ArtEmphasis.PAGE
    /** The background's share over the art now, animated; read it while drawing. */
    var scrim: State<Float> = mutableStateOf(1f)
        internal set

    // What frosted panes repaint under themselves (Android 12 and later): the panel's own paint and the
    // content layers registered with [backdropSource]. Plain fields, set while composing or laying out.
    internal var reveal: State<Float> = mutableStateOf(1f)
    internal var background = Color.Black
    internal var band = Color.Black
    internal var blend = 0f
    internal var panelSize = Size.Zero
    internal var view: View? = null
    /** The panel's top left on screen; panes in menus live in other windows, so everything lines up in screen pixels. */
    internal var screenOrigin = Offset.Zero
    internal val sources = mutableListOf<BackdropSource>()
    internal val panes = LinkedHashSet<FrostPane>()

    /** The panel's paint at its own origin: the art, the page's scrim and the band fading down from the top. */
    internal fun drawPanel(scope: DrawScope, opaque: Boolean) = with(scope) {
        val size = panelSize
        if (opaque) drawRect(background, size = size)
        art?.let { a ->
            drawImage(a.image, dstSize = IntSize(a.image.width * dotPx, a.image.height * dotPx), alpha = reveal.value, filterQuality = FilterQuality.None)
        }
        val s = scrim.value
        if (s > 0f) drawRect(background, alpha = s, size = size)
        // The band's colour over the panel's top, all of it at the edge and falling off at once ((1 - t)^2).
        val stops = Array(11) { i -> val t = i / 10f; t to band.copy(alpha = (1 - t) * (1 - t)) }
        drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = blend), size = Size(size.width, blend))
    }

    /** Something behind the panes drew or moved: each pane re-records its blurred copy, in this frame. */
    internal fun changed() = Snapshot.withoutReadObservation { for (p in panes) p.record() }
}

/** A content layer frosted panes blur, placed at [at] on screen. */
class BackdropSource internal constructor(val layer: GraphicsLayer) {
    internal var at = Offset.Zero
}

/**
 * Marks content that scrolls under Redesign's frosted panes, such as the thread under the composer.
 * It draws through a graphics layer the panes replay under themselves; on Android 11 and older, or
 * outside Redesign, it is a no-op.
 */
@Composable
fun Modifier.backdropSource(): Modifier {
    val host = LocalArtHost.current
    if (host == null || Build.VERSION.SDK_INT < 31) return this
    val layer = rememberGraphicsLayer()
    val source = remember(host, layer) { BackdropSource(layer) }
    DisposableEffect(host, source) {
        host.sources += source
        onDispose { host.sources -= source; host.changed() }
    }
    return onGloballyPositioned { val p = it.positionOnScreen(); if (p != source.at) { source.at = p; host.changed() } }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            host.changed()
        }
}

/**
 * One frosted pane's blurred copy of what is behind it. The copy is recorded at a quarter of the
 * pane's pixels plus a margin, so the blur runs on a small offscreen buffer and samples real content
 * at the edges, then draws scaled up under the pane's tint. It re-records only when the pane moves
 * or resizes or something behind it draws; an idle screen records nothing. A pane in a menu lives in
 * another window and repaints the panel itself, since a window must not draw another's layers.
 */
@RequiresApi(31)
internal class FrostPane(private val host: ArtHost, private val layer: GraphicsLayer, private val sameWindow: Boolean) {
    var at = Offset.Zero
    var size = Size.Zero
    var density: Density? = null
    var layoutDirection = LayoutDirection.Ltr
    private var effectDensity = 0f

    fun record() {
        val d = density ?: return
        if (size.width <= 0f || size.height <= 0f) return
        val m = with(d) { Frost.margin.toPx() }
        if (effectDensity != d.density) {
            effectDensity = d.density
            layer.renderEffect = Frost.effect(with(d) { Frost.radius.toPx() } / Frost.SCALE)
        }
        val px = IntSize(ceil((size.width + 2 * m) / Frost.SCALE).toInt(), ceil((size.height + 2 * m) / Frost.SCALE).toInt())
        layer.record(d, layoutDirection, px) {
            scale(1f / Frost.SCALE, 1f / Frost.SCALE, pivot = Offset.Zero) {
                // From here on, screen pixels shifted so the pane's top left minus the margin is the origin.
                translate(m - at.x, m - at.y) {
                    translate(host.screenOrigin.x, host.screenOrigin.y) { host.drawPanel(this, opaque = true) }
                    if (sameWindow) for (s in host.sources) translate(s.at.x, s.at.y) { drawLayer(s.layer) }
                }
            }
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        val m = Frost.margin.toPx()
        translate(-m, -m) { scale(Frost.SCALE, Frost.SCALE, pivot = Offset.Zero) { drawLayer(layer) } }
    }
}

/** Redesign's glass, after CandyWrapper's BackdropBlurView: a 12 point blur radius and 1.14 saturation. */
internal object Frost {
    /** The radius the Mac hands its backdrop's gaussian blur (`glassBlur`), as Android's blur radius. */
    val radius = 12.dp
    /** How far past the pane's edge the copy reaches, so the blur does not fade in from its border. */
    val margin = 24.dp
    /** The copy is a quarter of the pane's pixels on each side; the blur hides the difference. */
    const val SCALE = 4f
    private const val SATURATION = 1.14f

    @RequiresApi(31)
    fun effect(radiusPx: Float): RenderEffect {
        val radius = radiusPx.coerceAtLeast(1f)
        val saturate = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(SATURATION) })
        val blur = android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createColorFilterEffect(saturate, blur).asComposeRenderEffect()
    }
}

val LocalArtHost = staticCompositionLocalOf<ArtHost?> { null }

/** The page shows the art at [emphasis] while it is composed. */
@Composable
fun ArtEmphasisEffect(emphasis: ArtEmphasis) {
    val host = LocalArtHost.current ?: return
    DisposableEffect(host, emphasis) {
        host.emphases += emphasis
        onDispose { host.emphases.remove(emphasis) }
    }
}

/**
 * The main panel under Redesign: the solid background at once, the art fading in over it once
 * rendered, the page's scrim and the band colour fading down from the top edge. The art sits in its
 * own layer, so scrolling content over it never redraws it. Outside Redesign this is [content] alone.
 */
@Composable
fun RedesignPanel(content: @Composable BoxScope.() -> Unit) {
    if (!Candy.redesign) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }
    val c = Candy.colors
    val style = Candy.style
    val store = LocalArtStore.current
    val host = remember { ArtHost() }
    val density = LocalDensity.current
    host.dotPx = max(1, (DitherArt.DOT_DP * density.density).roundToInt())
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    // A keyboard or a shorter page never re-renders: the canvas keeps the tallest height seen at this width.
    var tallest by remember { mutableStateOf(0 to 0) }
    val key = if (panelSize.width > 0 && panelSize.height > 0) {
        val w = ceil(panelSize.width / host.dotPx.toFloat()).toInt()
        val h = ceil(maxOf(panelSize.height, if (tallest.first == panelSize.width) tallest.second else 0) / host.dotPx.toFloat()).toInt()
        DitherArtStore.Key(style.picture, w, h, c.background.toArgbInt())
    } else null
    val reveal = remember { Animatable(if (key != null && store?.peek(key) != null) 1f else 0f) }
    LaunchedEffect(key, store) {
        if (key == null || store == null) return@LaunchedEffect
        store.peek(key)?.let { host.art = it; reveal.snapTo(1f); return@LaunchedEffect }
        // Another picture or appearance: the old art goes at once and the new one fades in. A new size keeps it meanwhile.
        host.art?.key?.let { old -> if (old.picture != key.picture || old.background != key.background) { host.art = null; reveal.snapTo(0f) } }
        // A rotation or split-screen drag lands here every frame; the last size renders.
        if (host.art != null) delay(150)
        val art = store.load(key) ?: return@LaunchedEffect
        host.art = art
        if (reveal.value < 1f) reveal.animateTo(1f, tween(Motion.FAST, easing = Motion.Ease))
    }
    val target = when (host.emphasis) {
        ArtEmphasis.HERO -> 0f
        ArtEmphasis.THREAD -> style.dim.coerceIn(0, 90) / 100f
        ArtEmphasis.SETTINGS -> DitherArt.SETTINGS_SCRIM
        ArtEmphasis.PAGE -> DitherArt.PAGE_SCRIM
    }
    host.scrim = animateFloatAsState(target, tween(Motion.SHEET, easing = Motion.Ease), label = "scrim")
    host.reveal = reveal.asState()
    host.background = c.background
    host.band = c.sidebar
    host.blend = with(density) { (WindowInsets.statusBars.getTop(this) + 52.dp.roundToPx()).toFloat() }
    host.view = LocalView.current
    Box(
        Modifier.fillMaxSize().background(c.background)
            .onSizeChanged { s ->
                panelSize = s
                host.panelSize = Size(s.width.toFloat(), s.height.toFloat())
                if (s.width == tallest.first) { if (s.height > tallest.second) tallest = s.width to s.height } else tallest = s.width to s.height
            }
            .onGloballyPositioned {
                host.origin = it.positionInRoot()
                val screen = it.positionOnScreen()
                if (screen != host.screenOrigin) { host.screenOrigin = screen; host.changed() }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer().drawBehind {
                host.drawPanel(this, opaque = false)
                // A reveal, a scrim change or new art repaints the panes over it too.
                host.changed()
            },
        )
        CompositionLocalProvider(LocalArtHost provides host) { content() }
    }
}

/**
 * Redesign's frosted pane over the panel. From Android 12 it is glass: what is behind it, the art and
 * any [backdropSource] content such as the thread, blurred and saturated, then [tint]. Older versions
 * draw the pre-blurred art lined up under the pane with the page's scrim, then [tint]. Without a
 * panel (another theme, the sidebar, a test without a panel) it fills with [tint] over the background.
 */
@Composable
fun Modifier.frost(shape: Shape, tint: Color = Candy.colors.paneTint()): Modifier {
    val host = LocalArtHost.current
    val bg = Candy.colors.background
    if (host == null) return background(tint.over(bg), shape)
    if (Build.VERSION.SDK_INT >= 31) return glass(host, shape, tint)
    val at = remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { at.value = it.positionInRoot() }.drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            clipPath(path) {
                val art = host.art
                if (art == null) drawRect(bg) else {
                    val o = host.origin - at.value
                    val scale = art.frostFactor * host.dotPx
                    drawImage(
                        art.frost, dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                        dstSize = IntSize(art.frost.width * scale, art.frost.height * scale), filterQuality = FilterQuality.Low,
                    )
                    drawRect(bg, alpha = host.scrim.value)
                }
                drawRect(tint)
            }
        }
    }
}

@RequiresApi(31)
@Composable
private fun Modifier.glass(host: ArtHost, shape: Shape, tint: Color): Modifier {
    val layer = rememberGraphicsLayer()
    val view = LocalView.current
    val pane = remember(host, layer, view) { FrostPane(host, layer, sameWindow = view === host.view) }
    DisposableEffect(host, pane) {
        host.panes += pane
        onDispose { host.panes -= pane }
    }
    return onGloballyPositioned { val p = it.positionOnScreen(); if (p != pane.at) { pane.at = p; pane.record() } }.drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        if (size != pane.size || pane.density?.density != density) {
            pane.size = size
            pane.density = Density(density, fontScale)
            pane.layoutDirection = layoutDirection
            pane.record()
        }
        onDrawBehind {
            clipPath(path) {
                pane.draw(this)
                drawRect(tint)
            }
        }
    }
}

private fun Color.toArgbInt(): Int = (alpha * 255).roundToInt() shl 24 or ((red * 255).roundToInt() shl 16) or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt()

/** A page's ground: clear over Redesign's art, T3's background elsewhere. */
@Composable
fun Modifier.page(): Modifier = if (LocalArtHost.current != null) this else background(Candy.colors.background)
