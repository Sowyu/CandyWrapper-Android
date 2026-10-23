package org.anikodev.candywrapper.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.media.ChatAttachment
import org.anikodev.candywrapper.core.media.ImageRef
import org.anikodev.candywrapper.core.media.Media
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.data.ImageStore

/**
 * What the thread's image views ask of the screen: decoded bitmaps at a drawn size, natural sizes
 * for reserving a box before the bytes arrive, the original file for save and share, and opening
 * the full-screen viewer. Screenshot tests provide a fake.
 */
@Stable
interface ThreadImages {
    val threadId: String?
    /** A bitmap already in memory, so a row scrolled back into view draws on its first frame. */
    fun cached(ref: ImageRef, px: Int): ImageBitmap?
    fun size(ref: ImageRef): IntSize?
    /** The natural size, asking the computer for the signed URL (which carries it) when not known yet. */
    suspend fun measure(ref: ImageRef): IntSize?
    suspend fun load(ref: ImageRef, px: Int): ImageBitmap?
    suspend fun file(ref: ImageRef): File?
    fun open(items: List<ImageRef>, index: Int)
}

val LocalImages = compositionLocalOf<ThreadImages?> { null }

/** [ThreadImages] for one thread on one computer, on the app's [ImageStore]. */
class SessionImages(
    private val store: ImageStore,
    private val session: EnvironmentSession,
    override val threadId: String?,
    private val onOpen: (List<ImageRef>, Int) -> Unit,
) : ThreadImages {
    override fun cached(ref: ImageRef, px: Int) = store.cached(ref.key, px)?.asImageBitmap()

    override fun size(ref: ImageRef): IntSize? =
        (store.size(ref.key) ?: (ref as? ImageRef.Asset)?.let { session.assetUrls.size(it.key) })?.let { (w, h) -> IntSize(w, h) }

    override suspend fun measure(ref: ImageRef): IntSize? {
        size(ref)?.let { return it }
        if (ref !is ImageRef.Asset) return null
        val signed = guard { session.assetUrls.get(ref) } ?: return null
        return if (signed.width > 0 && signed.height > 0) IntSize(signed.width, signed.height) else null
    }

    override suspend fun load(ref: ImageRef, px: Int) = guard { store.load(ref, px) { session.imageUrl(ref as ImageRef.Asset) } }?.asImageBitmap()

    override suspend fun file(ref: ImageRef) = guard { store.file(ref) { session.imageUrl(ref as ImageRef.Asset) } }

    override fun open(items: List<ImageRef>, index: Int) = onOpen(items, index)

    /** Offline, an expired route or a refused asset all draw as "Image unavailable"; cancellation still cancels. */
    private suspend fun <T> guard(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data object Failed : Loaded
    data class Ready(val bitmap: ImageBitmap) : Loaded
}

/**
 * One image filling [modifier]'s box: the placeholder until it decodes, then the picture, or
 * "Image unavailable", which a tap retries. Leaving the screen cancels the load.
 */
@Composable
fun RemoteImage(ref: ImageRef, px: Int, modifier: Modifier = Modifier, crop: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Candy.colors
    val images = LocalImages.current
    var attempt by remember(ref.key) { mutableIntStateOf(0) }
    val state by produceState<Loaded>(images?.cached(ref, px)?.let { Loaded.Ready(it) } ?: Loaded.Loading, images, ref.key, px, attempt) {
        if (value is Loaded.Ready) return@produceState
        value = Loaded.Loading
        value = images?.load(ref, px)?.let { Loaded.Ready(it) } ?: Loaded.Failed
    }
    Box(
        modifier
            .semantics { contentDescription = ref.name.ifBlank { "Image" } }
            .let { m ->
                when {
                    state is Loaded.Failed -> m.clickable(role = Role.Button, onClickLabel = "Retry") { attempt++ }
                    onClick != null -> m.clickable(role = Role.Image, onClickLabel = "Open image", onClick = onClick)
                    else -> m
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is Loaded.Ready -> Image(s.bitmap, null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
            Loaded.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Image, c.mutedForeground, 16.dp)
                Txt("Image unavailable", CandyType.xs, c.mutedForeground, maxLines = 1)
            }
            Loaded.Loading -> Unit
        }
    }
}

/** T3's chat image limit, `max-h-[30rem] max-w-[min(100%,30rem)]`. */
private val ImageMax = 480.dp

/**
 * An image at its natural size, scaled down to the width and [ImageMax], ratio kept. The box is
 * sized before the bytes arrive: from the size seen before, or the one the server read from the
 * file's header; until then it takes T3's 16:9 loading slot. [framed] draws workspace files in
 * T3's `rounded-lg border border-border/40`.
 */
@Composable
fun NaturalImage(ref: ImageRef, onClick: () -> Unit, modifier: Modifier = Modifier, framed: Boolean = true) {
    val c = Candy.colors
    val images = LocalImages.current
    val density = LocalDensity.current
    val natural by produceState(images?.size(ref), images, ref.key) {
        if (value == null) value = images?.measure(ref)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxW = minOf(maxWidth, ImageMax)
        val (w, h) = with(density) {
            val (pw, ph) = Media.fit(natural?.width ?: 0, natural?.height ?: 0, maxW.toPx(), ImageMax.toPx())
            pw.toDp() to ph.toDp()
        }
        val px = with(density) { maxOf(w, h).roundToPx() }
        val shape = RoundedCornerShape(Radius.lg)
        RemoteImage(
            ref, px,
            Modifier.width(w).height(h).clip(shape).background(c.muted.copy(alpha = c.muted.alpha * 0.5f))
                .let { if (framed) it.border(1.dp, c.border.copy(alpha = c.border.alpha * 0.4f), shape) else it },
            onClick = onClick,
        )
    }
}

/**
 * A message's attachments (MessagesTimeline.tsx:2402-2490): images in a `max-w-[210px]` two-column
 * grid of 4:3 cells, `rounded-lg border border-border/80 bg-background/70`, then the other files as
 * rows with their name. A tap opens the images in the viewer.
 */
@Composable
fun MessageAttachments(attachments: List<ChatAttachment>, modifier: Modifier = Modifier) {
    val c = Candy.colors
    val images = LocalImages.current
    // Preview annotations are inputs for the agent, not something the user attached.
    val refs = remember(attachments) { attachments.filter { it.isImage && !it.name.startsWith("preview-annotation-") }.map(Media::attachment) }
    val files = remember(attachments) { attachments.filter { !it.isImage } }
    val density = LocalDensity.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (refs.isNotEmpty()) {
            val shape = RoundedCornerShape(Radius.lg)
            Column(Modifier.widthIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pair in refs.withIndex().chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((i, ref) in pair) {
                            val px = with(density) { 210.dp.roundToPx() }
                            RemoteImage(
                                ref, px,
                                Modifier.width(101.dp).aspectRatio(4f / 3f).clip(shape).background(c.background.copy(alpha = 0.7f))
                                    .border(1.dp, c.border.copy(alpha = c.border.alpha * 0.8f), shape),
                                crop = true,
                                onClick = { images?.open(refs, i) },
                            )
                        }
                    }
                }
            }
        }
        for (f in files) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(min = 28.dp)) {
                Icon(Icons.File, c.mutedForeground, 14.dp)
                Txt(f.name.ifBlank { "File" }, CandyType.sm, c.foreground, maxLines = 1)
            }
        }
    }
}

/** An open viewer: the images it pages through and the one it opened on. */
data class ViewerState(val items: List<ImageRef>, val index: Int)

/** The full-screen viewer in its own window, over the system bars. Back or a swipe down closes it. */
@Composable
fun ImageViewerDialog(state: ViewerState, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImageViewer(state, onClose)
    }
}

/**
 * A black page per image: pinch to zoom up to 5x, drag to pan while zoomed, double tap to zoom in or
 * out, swipe sideways between images and down to close. Save puts the original in Pictures/
 * CandyWrapper; Share hands it to another app. The image decodes at the screen's size.
 */
@Composable
fun ImageViewer(state: ViewerState, onClose: () -> Unit) {
    val images = LocalImages.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(state.index) { state.items.size }
    var zoomed by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val px = minOf(2048, maxOf(constraints.maxWidth, constraints.maxHeight))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - (abs(drag) / heightPx).coerceIn(0f, 0.6f) }.background(Color.Black))
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { state.items[it].key }) { page ->
            ZoomablePage(
                state.items[page], px,
                onZoom = { if (page == pager.currentPage) zoomed = it },
                onDrag = { drag = it },
                onDismiss = onClose,
            )
        }
        val ref = state.items[pager.currentPage]
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp)
                .graphicsLayer { alpha = 1f - (abs(drag) / heightPx * 3f).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Icons.X, "Close", tint = Color.White, size = 20.dp, box = 44.dp, onClick = onClose)
            Txt(
                if (state.items.size > 1) "${pager.currentPage + 1} of ${state.items.size}" else ref.name,
                CandyType.smMedium, Color.White, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                IconButton(Icons.Download, "Save to Pictures", tint = Color.White, size = 20.dp, box = 44.dp) {
                    scope.launch {
                        val file = images?.file(ref)
                        val saved = file != null && saveToPictures(context, file, ref.name)
                        Toast.makeText(context, if (saved) "Saved to Pictures" else "Could not save the image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            IconButton(Icons.Upload, "Share", tint = Color.White, size = 20.dp, box = 44.dp) {
                scope.launch {
                    val file = images?.file(ref)
                    if (file == null) Toast.makeText(context, "The image is not available", Toast.LENGTH_SHORT).show()
                    else share(context, file, ref.name)
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(ref: ImageRef, px: Int, onZoom: (Boolean) -> Unit, onDrag: (Float) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val dismissPx = with(density) { 120.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float) = Offset(
            o.x.coerceIn(-w * (s - 1) / 2, w * (s - 1) / 2),
            o.y.coerceIn(-h * (s - 1) / 2, h * (s - 1) / 2),
        )
        fun setScale(s: Float, o: Offset) {
            scale = s
            offset = clamp(o, s)
            onZoom(s > 1.01f)
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1.01f) setScale(1f, Offset.Zero)
                        else {
                            // Zoom 2.5x on the tapped point.
                            val center = Offset(w / 2, h / 2)
                            setScale(2.5f, (center - at) * 1.5f)
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zooming = false
                        var dismissing = false
                        var total = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.count { it.pressed } >= 2) {
                                zooming = true
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid() - Offset(w / 2, h / 2)
                                val next = (scale * zoom).coerceIn(1f, 5f)
                                val factor = next / scale
                                setScale(next, (offset - centroid) * factor + centroid + event.calculatePan())
                                event.changes.forEach { it.consume() }
                            } else if (!zooming) {
                                val change = event.changes.first { it.pressed }
                                val delta = change.positionChange()
                                if (scale > 1.01f) {
                                    setScale(scale, offset + delta)
                                    change.consume()
                                } else {
                                    total += delta
                                    if (!dismissing && abs(total.y) > viewConfiguration.touchSlop && abs(total.y) > abs(total.x) * 1.2f) dismissing = true
                                    if (dismissing) {
                                        dragY += delta.y
                                        onDrag(dragY)
                                        change.consume()
                                    }
                                }
                            }
                        }
                        if (dismissing) {
                            if (abs(dragY) > dismissPx) onDismiss()
                            else scope.launch {
                                animate(dragY, 0f, animationSpec = Motion.sheet()) { v, _ -> dragY = v; onDrag(v) }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + dragY
                },
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(ref, px, Modifier.fillMaxSize())
        }
    }
}

/** Copies the original into Pictures/CandyWrapper (Android 10 and later, no permission needed). */
private suspend fun saveToPictures(context: Context, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
    val mime = ImageStore.mimeType(file)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName(name, mime))
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CandyWrapper")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
    try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Shares a copy named after the image, through the app's FileProvider. */
private suspend fun share(context: Context, file: File, name: String) {
    val mime = withContext(Dispatchers.IO) { ImageStore.mimeType(file) }
    val copy = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, fileName(name, mime)).also { file.copyTo(it, overwrite = true) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A safe file name with the extension of its real type. */
private fun fileName(name: String, mime: String): String {
    val ext = when (mime) { "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "png" }
    val base = name.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "image" }.take(80)
    return "$base.$ext"
}
