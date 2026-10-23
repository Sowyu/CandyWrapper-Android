package org.anikodev.candywrapper.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Base64
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.await
import org.anikodev.candywrapper.core.media.ImageRef
import org.anikodev.candywrapper.core.timing.Timeline

/**
 * The thread's images: original bytes in a small disk cache under the app's cache directory, and
 * bitmaps decoded at the size they are drawn in a memory cache bounded by bytes. Downloads and
 * decodes run on [Dispatchers.IO], four at a time; cancelling the caller cancels the request.
 * Both caches key on the image's identity ([ImageRef.key]), not its signed URL. A composer's
 * [ImageRef.Local] image reads from its file, or is copied once from [open] (the content resolver).
 */
class ImageStore(cacheDir: File, private val open: ((String) -> java.io.InputStream?)? = null) {
    private val dir = File(cacheDir, "img").apply { mkdirs() }
    private val io = Dispatchers.IO.limitedParallelism(4)

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Natural pixel sizes by image key, from the server's header read or a decode. */
    // ponytail: grows by a few bytes per image seen this process; cap it if a session sees thousands
    private val sizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val downloads = Http.environment.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** A decoded bitmap at least [px] on its long side, if one is in memory. */
    fun cached(key: String, px: Int): Bitmap? = memory.get(memoryKey(key, px))

    fun size(key: String): Pair<Int, Int>? = sizes[key]

    fun recordSize(key: String, width: Int, height: Int) {
        if (width > 0 && height > 0) sizes[key] = width to height
    }

    /**
     * The image decoded to fit [px] on its long side, never larger than the original. [url]
     * resolves where the bytes live; it is called only when the disk cache misses.
     */
    suspend fun load(ref: ImageRef, px: Int, url: suspend () -> HttpUrl): Bitmap? = withContext(io) {
        val mk = memoryKey(ref.key, px)
        memory.get(mk)?.let { return@withContext it }
        val file = file(ref, url) ?: return@withContext null
        ensureActive()
        decode(file, ref.key, bucket(px))?.also { memory.put(mk, it) }
    }

    /** The original bytes on disk, downloading them first when needed. Null when the image cannot be read. */
    suspend fun file(ref: ImageRef, url: suspend () -> HttpUrl): File? = withContext(io) {
        if (ref is ImageRef.Local && ref.uri.startsWith("file:")) {
            return@withContext runCatching { File(java.net.URI(ref.uri)) }.getOrNull()?.takeIf { it.length() > 0 }
        }
        val file = File(dir, hash(ref.key))
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        val tmp = File(dir, file.name + ".part")
        try {
            if (ref is ImageRef.Local) {
                val input = runCatching { open?.invoke(ref.uri) }.getOrNull() ?: return@withContext null
                input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            } else if (ref is ImageRef.Direct && ref.url.startsWith("data:", ignoreCase = true)) {
                val comma = ref.url.indexOf(',')
                if (comma < 0 || !ref.url.substring(0, comma).endsWith(";base64", ignoreCase = true)) return@withContext null
                tmp.writeBytes(Base64.decode(ref.url.substring(comma + 1), Base64.DEFAULT))
            } else {
                val start = Timeline.nowMs()
                val target = if (ref is ImageRef.Direct) ref.url.toHttpUrlOrNull() ?: return@withContext null else url()
                val response = downloads.newCall(Request.Builder().url(target).build()).await()
                response.use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val length = r.body.contentLength()
                    if (length > MAX_BYTES) return@withContext null
                    r.body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) return@withContext null
                                ensureActive()
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                Timeline.span("image.fetched", start, "${tmp.length() / 1024} KiB")
            }
            // The rename is the commit: a half-written download never looks complete.
            if (!tmp.renameTo(file)) return@withContext null
            trim()
            file
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            tmp.delete()
        }
    }

    private fun decode(file: File, key: String, px: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            recordSize(key, w, h)
            // Power-of-two downsampling while the long side stays at or above the drawn size.
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= px) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // ponytail: trims by listing the directory after each download; keep an index if the cache grows past a few hundred files
    private fun trim() {
        val files = dir.listFiles { f -> !f.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun memoryKey(key: String, px: Int) = "$key#${bucket(px)}"

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** The image's MIME type from its header, for saving and sharing. */
        fun mimeType(file: File): String =
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outMimeType ?: "image/png"

        private const val MEMORY_BYTES = 32 * 1024 * 1024
        private const val DISK_BYTES = 64L * 1024 * 1024
        /** T3 caps message images at 10 MiB and files at 50 MiB; nothing drawn inline needs more. */
        private const val MAX_BYTES = 25L * 1024 * 1024

        /** Drawn sizes round up to a few steps, so nearby sizes share one decode. */
        fun bucket(px: Int): Int = when {
            px <= 256 -> 256
            px <= 512 -> 512
            px <= 1024 -> 1024
            else -> 2048
        }
    }
}
