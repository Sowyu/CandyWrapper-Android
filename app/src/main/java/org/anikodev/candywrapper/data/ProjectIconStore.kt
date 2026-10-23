package org.anikodev.candywrapper.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.await
import org.anikodev.candywrapper.core.media.ImageRef
import org.anikodev.candywrapper.core.media.ProjectIcons
import org.anikodev.candywrapper.core.media.SvgImage
import org.anikodev.candywrapper.core.media.SvgLite
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.timing.Timeline

/** What the UI asks of project icons; screenshot tests provide a fake. */
interface ProjectIconSource {
    /** The saved thumbnail for [p], if any. Reading it subscribes the caller to its arrival. */
    fun peek(p: ProjectSummary): ImageBitmap?

    /** Checks [p]'s icon against its computer; returns when done or failed. */
    suspend fun refresh(p: ProjectSummary)
}

/**
 * Project icons as client-runtime's `createProjectFaviconCache` keeps them: a 96px thumbnail per
 * icon, saved as a PNG under the app's files and read back on the boot thread, so a cold start
 * draws them on the first frame with no network. Each file is named after the icon's resource key
 * (`getProjectFaviconResourceKey`) and its revision, the signed URL's last segment, so a new
 * signature reuses it and a changed icon replaces it.
 *
 * An icon on screen asks its computer for the signed URL once the socket is live, at most every
 * five minutes (T3's asset URL stale time), and downloads only when the revision moved.
 */
class ProjectIconStore(filesDir: File, private val scope: CoroutineScope, private val sessions: (String) -> EnvironmentSession?) : ProjectIconSource {
    private class Entry(val revision: String, val image: ImageBitmap, val file: File)

    private val dir = File(filesDir, "project-icons")
    private val io = Dispatchers.IO.limitedParallelism(2)
    /** By hashed resource key. Snapshot state, so an icon that lands redraws the rows showing it. */
    private val entries = mutableStateMapOf<String, Entry>()
    private val hashes = ConcurrentHashMap<String, String>()
    private val checkedAt = ConcurrentHashMap<String, Long>()
    /** A revision that would not decode (an `.icns`, a broken file), so it is not fetched again until it changes. */
    private val undecodable = ConcurrentHashMap<String, String>()
    private val inflight = ConcurrentHashMap<String, Deferred<Unit>>()

    private val client = Http.environment.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    /** Reads every saved thumbnail. Runs on the boot thread before the first frame. */
    fun hydrate() {
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
        for (f in files.sortedBy { it.lastModified() }) {
            val (key, revision) = f.name.removeSuffix(".png").split('.').takeIf { it.size == 2 } ?: run { f.delete(); continue }
            val bitmap = BitmapFactory.decodeFile(f.path) ?: run { f.delete(); continue }
            entries.remove(key)?.file?.delete()
            entries[key] = Entry(revision, bitmap.asImageBitmap(), f)
        }
    }

    override fun peek(p: ProjectSummary): ImageBitmap? = entries[hash(ProjectIcons.resourceKey(p))]?.image

    /** Waits for the socket to be live, then checks. */
    override suspend fun refresh(p: ProjectSummary) {
        if (!ProjectIcons.wantsFile(p)) return
        val session = sessions(p.envId) ?: return
        val key = hash(ProjectIcons.resourceKey(p))
        checkedAt[key]?.let { if (System.currentTimeMillis() - it < RECHECK_MS) return }
        session.state.first { it is ConnectionState.Live }
        // Every row of the project asks at once; one check answers them all.
        val job = inflight.computeIfAbsent(key) {
            scope.async(io) {
                try {
                    check(session, p, key)
                } finally {
                    checkedAt[key] = System.currentTimeMillis()
                    inflight.remove(key)
                }
            }
        }
        job.await()
    }

    private suspend fun check(session: EnvironmentSession, p: ProjectSummary, key: String) {
        val ref = ImageRef.Asset("project-favicon:${p.workspaceRoot}\u0000${p.faviconPath.orEmpty()}", p.title, ProjectIcons.resource(p.workspaceRoot, p.faviconPath))
        val signed = try {
            session.assetUrls.get(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or refused: the last icon stays, as in T3.
            return
        }
        if (ProjectIcons.isMissing(signed.relativeUrl)) {
            withContext(Dispatchers.Main.immediate) { entries.remove(key) }?.file?.delete()
            return
        }
        val revision = hash(ProjectIcons.revision(signed.relativeUrl)).take(16)
        entries[key]?.takeIf { it.revision == revision }?.let { it.file.setLastModified(System.currentTimeMillis()); return }
        if (undecodable[key] == revision) return
        val start = Timeline.nowMs()
        val url = try { session.imageUrl(ref) } catch (e: IOException) { return }
        val (bytes, mime) = try {
            client.newCall(Request.Builder().url(url).build()).await().use { r ->
                if (!r.isSuccessful || r.body.contentLength() > ProjectIcons.MAX_SOURCE_BYTES) return
                val source = r.body.source()
                if (source.request(ProjectIcons.MAX_SOURCE_BYTES + 1)) return
                source.buffer.readByteArray() to ProjectIcons.mimeType(r.header("Content-Type"), signed.relativeUrl)
            }
        } catch (e: IOException) {
            return
        }
        val bitmap = thumbnail(bytes, mime) ?: run { undecodable[key] = revision; return }
        Timeline.span("icon.fetched", start, "${bytes.size / 1024} KiB")
        dir.mkdirs()
        val file = File(dir, "$key.$revision.png")
        val tmp = File(dir, "$key.$revision.part")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) return
        } catch (e: IOException) {
            // The icon still shows this session; it is fetched again on the next start.
        } finally {
            tmp.delete()
        }
        val old = withContext(Dispatchers.Main.immediate) { entries.put(key, Entry(revision, bitmap.asImageBitmap(), file)) }
        if (old != null && old.file != file) old.file.delete()
        trim()
    }

    /** `PROJECT_FAVICON_CACHE_MAX_ENTRIES`: the least recently checked icons go first. */
    private suspend fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
        if (files.size <= ProjectIcons.MAX_ENTRIES) return
        val drop = files.sortedBy { it.lastModified() }.take(files.size - ProjectIcons.MAX_ENTRIES)
        withContext(Dispatchers.Main.immediate) { for (f in drop) entries.remove(f.name.substringBefore('.')) }
        for (f in drop) f.delete()
    }

    private fun hash(s: String): String = hashes.getOrPut(s) {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RECHECK_MS = 5 * 60_000L
        /** SVG favicons are a few KiB; past this one is not worth parsing for 16dp. */
        private const val MAX_SVG_BYTES = 512 * 1024

        /** The icon at [ProjectIcons.THUMBNAIL_PX] on its long side, never enlarged; SVGs through [SvgLite]. */
        fun thumbnail(bytes: ByteArray, mime: String?): Bitmap? {
            if (mime == "image/svg+xml" || looksLikeSvg(bytes)) {
                if (bytes.size > MAX_SVG_BYTES) return null
                return SvgLite.parse(bytes.decodeToString())?.let(::rasterize)
            }
            val decoded = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    var sample = 1
                    while (maxOf(info.size.width, info.size.height) / (sample * 2) >= ProjectIcons.THUMBNAIL_PX) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            } catch (e: IOException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
            val scale = minOf(1f, ProjectIcons.THUMBNAIL_PX.toFloat() / maxOf(decoded.width, decoded.height))
            if (scale >= 1f) return decoded
            return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1), (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
        }

        private fun looksLikeSvg(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
            return head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in head)
        }

        /** Paints [svg]'s shapes into a bitmap with its view box's ratio, [ProjectIcons.THUMBNAIL_PX] on the long side. */
        fun rasterize(svg: SvgImage): Bitmap? {
            val px = ProjectIcons.THUMBNAIL_PX
            val scale = px / maxOf(svg.width, svg.height)
            val bitmap = Bitmap.createBitmap((svg.width * scale).roundToInt().coerceAtLeast(1), (svg.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-svg.minX, -svg.minY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val matrix = Matrix()
            var drawn = 0
            for (s in svg.shapes) {
                val path = try {
                    PathParser().parsePathString(s.path).toPath().asAndroidPath()
                } catch (e: RuntimeException) {
                    continue
                }
                val m = s.matrix
                matrix.setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f))
                path.transform(matrix)
                path.fillType = if (s.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                s.fill?.let { paint.style = Paint.Style.FILL; paint.color = it; canvas.drawPath(path, paint) }
                s.stroke?.let {
                    paint.style = Paint.Style.STROKE
                    paint.color = it
                    paint.strokeWidth = s.strokeWidth * sqrt(abs(m[0] * m[3] - m[1] * m[2]))
                    paint.strokeCap = if (s.roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
                    paint.strokeJoin = if (s.roundJoin) Paint.Join.ROUND else Paint.Join.MITER
                    canvas.drawPath(path, paint)
                }
                drawn++
            }
            return bitmap.takeIf { drawn > 0 }
        }
    }
}
