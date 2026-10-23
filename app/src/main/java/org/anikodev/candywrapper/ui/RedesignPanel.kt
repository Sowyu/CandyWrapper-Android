package org.anikodev.candywrapper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    val band = c.sidebar
    val blend = with(density) { (WindowInsets.statusBars.getTop(this) + 52.dp.roundToPx()).toFloat() }
    Box(
        Modifier.fillMaxSize().background(c.background)
            .onSizeChanged { s ->
                panelSize = s
                if (s.width == tallest.first) { if (s.height > tallest.second) tallest = s.width to s.height } else tallest = s.width to s.height
            }
            .onGloballyPositioned { host.origin = it.positionInRoot() },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer().drawBehind {
                val art = host.art
                if (art != null) {
                    drawImage(
                        art.image, dstSize = IntSize(art.image.width * host.dotPx, art.image.height * host.dotPx),
                        alpha = reveal.value, filterQuality = FilterQuality.None,
                    )
                }
                val scrim = host.scrim.value
                if (scrim > 0f) drawRect(c.background, alpha = scrim)
                // The band's colour over the panel's top, all of it at the edge and falling off at once ((1 - t)^2).
                val stops = Array(11) { i -> val t = i / 10f; t to band.copy(alpha = (1 - t) * (1 - t)) }
                drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = blend), size = androidx.compose.ui.geometry.Size(size.width, blend))
            },
        )
        CompositionLocalProvider(LocalArtHost provides host) { content() }
    }
}

/**
 * Redesign's frosted pane: the pre-blurred art lined up under the pane, the page's scrim over it,
 * then [tint]. A fixed texture draw, never a live blur. Without art (another theme, a test without a
 * panel, the art still rendering) it fills with [tint] over the background.
 */
@Composable
fun Modifier.frost(shape: Shape, tint: Color = Candy.colors.paneTint()): Modifier {
    val host = LocalArtHost.current
    val bg = Candy.colors.background
    if (host == null) return background(tint.over(bg), shape)
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

private fun Color.toArgbInt(): Int = (alpha * 255).roundToInt() shl 24 or ((red * 255).roundToInt() shl 16) or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt()

/** A page's ground: clear over Redesign's art, T3's background elsewhere. */
@Composable
fun Modifier.page(): Modifier = if (LocalArtHost.current != null) this else background(Candy.colors.background)
