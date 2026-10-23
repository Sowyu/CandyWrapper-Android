package org.anikodev.candywrapper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import java.io.File
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.media.ChatAttachment
import org.anikodev.candywrapper.core.media.ImageRef
import org.anikodev.candywrapper.core.media.Media
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Images in a thread (attachments, a Markdown image, a tool's output image) and the full-screen viewer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ImageShotTest {
    /** Synthetic pictures, drawn at once, sized by their key so boxes show real ratios. */
    object FakeImages : ThreadImages {
        override val threadId = "t1"
        private fun sizeOf(ref: ImageRef) = when {
            "chart" in ref.key -> IntSize(1600, 900)
            "tall" in ref.key -> IntSize(900, 1600)
            else -> IntSize(1200, 900)
        }
        private fun picture(ref: ImageRef): ImageBitmap {
            val s = sizeOf(ref)
            val w = s.width / 4
            val h = s.height / 4
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hue = (ref.key.hashCode() and 0xff) / 255f
            val a = android.graphics.Color.HSVToColor(floatArrayOf(hue * 360f, 0.45f, 0.85f))
            val b = android.graphics.Color.HSVToColor(floatArrayOf((hue * 360f + 60f) % 360f, 0.55f, 0.55f))
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
            canvas.drawCircle(w * 0.7f, h * 0.35f, minOf(w, h) * 0.16f, Paint().apply { color = android.graphics.Color.argb(200, 255, 255, 255); isAntiAlias = true })
            canvas.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), Paint().apply { color = android.graphics.Color.argb(90, 0, 0, 0) })
            return bitmap.asImageBitmap()
        }
        override fun cached(ref: ImageRef, px: Int) = if ("missing" in ref.key) null else picture(ref)
        override fun size(ref: ImageRef) = sizeOf(ref)
        override suspend fun measure(ref: ImageRef) = sizeOf(ref)
        override suspend fun load(ref: ImageRef, px: Int) = cached(ref, px)
        override suspend fun file(ref: ImageRef): File? = null
        override fun open(items: List<ImageRef>, index: Int) = Unit
    }

    private val user = TurnRow(
        "u1", "user_message", 1, "completed", "run1", "You",
        text = "The sidebar card overlaps the status bar on my phone. Screenshots attached.",
        atMs = Fixtures.NOW - 6 * 60_000,
        attachments = listOf(
            ChatAttachment("image", "shot-1", "sidebar.png", "image/png", 1),
            ChatAttachment("image", "shot-2-tall", "status-bar.png", "image/png", 1),
            ChatAttachment("image", "shot-3", "settings.png", "image/png", 1),
            ChatAttachment("file", "log", "logcat.txt", "text/plain", 1),
        ),
    )
    private val tool = TurnRow("v1", "dynamic_tool", 2, "completed", "run1", "view_image", detail = "app/screenshots/before.png", atMs = Fixtures.NOW - 5 * 60_000)
    private val answer = TurnRow(
        "a1", "assistant_message", 3, "completed", "run1", "Assistant",
        text = "The card's top inset was applied twice. Here is the layout after the fix:\n\n![Sidebar after the fix](docs/chart-after.png)\n\nThe status bar now clears the first card.",
        atMs = Fixtures.NOW - 3 * 60_000,
    )

    private val state = ThreadSession.State(
        items = listOf(
            TimelineRow(user, null),
            TimelineRow(answer, Markdown.parse(answer.text!!)),
        ),
        title = "Fix the sidebar inset", phase = ThreadSession.Phase.LIVE, meta = ThreadFixtures.state.meta,
    )

    private val host = TimelineHost(
        { ItemDetail(null, null, images = 1) },
        mutableStateMapOf("v1" to true),
        mutableStateMapOf("v1:completed" to ItemDetail("Output\n[image]", null, images = 1)),
    ) { _, _ -> }

    @Composable
    private fun WithImages(content: @Composable () -> Unit) = CompositionLocalProvider(LocalImages provides FakeImages, content = content)

    /** A tool's output image, open under its work log line. */
    private fun tool(dark: Boolean) = shot("thread-tool-image-phone", dark) {
        WithImages {
            ThreadView(
                title = state.title!!, project = Fixtures.projects[1], subtitle = null, subtitleError = false,
                state = state.copy(items = listOf(TimelineRow(tool, null))), host = host, workingSince = 0, working = false, onLoadOlder = {},
            ) { ThreadShotTest.ThreadComposer() }
        }
    }
    @Test fun toolLight() = tool(false)

    private fun thread(dark: Boolean) = shot("thread-images-phone", dark) {
        WithImages {
            ThreadView(
                title = state.title!!, project = Fixtures.projects[1], subtitle = null, subtitleError = false,
                state = state, host = host, workingSince = 0, working = false, onLoadOlder = {},
            ) { ThreadShotTest.ThreadComposer() }
        }
    }
    @Test fun threadLight() = thread(false)
    @Test fun threadDark() = thread(true)

    private fun viewer(dark: Boolean) = shot("image-viewer-phone", dark) {
        WithImages {
            ImageViewer(ViewerState(user.attachments.take(3).map(Media::attachment), 1)) {}
        }
    }
    @Test fun viewerLight() = viewer(false)
    @Test fun viewerDark() = viewer(true)
}
