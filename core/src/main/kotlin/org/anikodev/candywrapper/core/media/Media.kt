package org.anikodev.candywrapper.core.media

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/** contracts `ChatAttachment` (chatAttachment.ts:149-218): an image or file on a message. */
@Serializable
data class ChatAttachment(val type: String, val id: String, val name: String, val mimeType: String, val sizeBytes: Long = 0) {
    /** Drawn as a picture: an image, or a file whose type is an image (T3 previews both). */
    val isImage: Boolean get() = type == "image" || Media.isRasterMime(mimeType)

    companion object {
        fun from(o: JsonObject): ChatAttachment? = ChatAttachment(
            type = o.str("type") ?: return null,
            id = o.str("id") ?: return null,
            name = o.str("name").orEmpty(),
            mimeType = o.str("mimeType").orEmpty(),
            sizeBytes = o.long("sizeBytes") ?: 0,
        )

        fun list(o: JsonObject): List<ChatAttachment> = o.arr("attachments").orEmpty().mapNotNull { (it as? JsonObject)?.let(::from) }
    }
}

/**
 * An image the thread shows. [key] is the image's identity for the caches, never the signed URL,
 * which changes every hour. An [Asset] is fetched through a signed `assets.createUrl` URL
 * (protocol.md 9.7); a [Direct] one is a plain http(s) or `data:` URL from Markdown. A [Local] one
 * is on the phone, a picked, pasted or stashed image waiting in the composer, read through its
 * `content:` or `file:` [uri].
 */
sealed interface ImageRef {
    val key: String
    val name: String

    data class Asset(override val key: String, override val name: String, val resource: JsonObject) : ImageRef
    data class Direct(override val key: String, override val name: String, val url: String) : ImageRef
    data class Local(override val key: String, override val name: String, val uri: String) : ImageRef
}

/** A signed asset URL, relative to the environment's HTTP base, and the image size the server read from its header. */
data class AssetUrl(val relativeUrl: String, val expiresAtMs: Long, val width: Int = 0, val height: Int = 0) {
    companion object {
        fun from(o: JsonObject): AssetUrl? = AssetUrl(
            relativeUrl = o.str("relativeUrl") ?: return null,
            expiresAtMs = o.long("expiresAt") ?: 0,
            width = o.obj("imageDimensions")?.long("width")?.toInt() ?: 0,
            height = o.obj("imageDimensions")?.long("height")?.toInt() ?: 0,
        )
    }
}

/**
 * Signed URLs by image key, reused until five minutes before they expire, so scrolling back to an
 * image does not ask the computer again. [mint] runs `assets.createUrl`.
 */
class AssetUrls(private val now: () -> Long = System::currentTimeMillis, private val mint: suspend (JsonObject) -> AssetUrl) {
    private val cache = ConcurrentHashMap<String, AssetUrl>()

    suspend fun get(ref: ImageRef.Asset): AssetUrl {
        cache[ref.key]?.takeIf { it.expiresAtMs - now() > MARGIN_MS }?.let { return it }
        return mint(ref.resource).also { cache[ref.key] = it }
    }

    /** The server's size for an image already minted, for sizing its box before the bytes arrive. */
    fun size(key: String): Pair<Int, Int>? = cache[key]?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    private companion object {
        const val MARGIN_MS = 5 * 60_000L
    }
}

object Media {
    /** contracts `PROVIDER_SEND_TURN_SUPPORTED_IMAGE_MIME_TYPES`, the raster types T3 previews inline. */
    private val RASTER = setOf("image/gif", "image/jpeg", "image/png", "image/webp")
    private val RASTER_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

    fun isRasterMime(mime: String) = mime.lowercase() in RASTER

    fun isRasterPath(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS

    /** A message attachment, served inline with its real name and type. */
    fun attachment(a: ChatAttachment) = ImageRef.Asset("attachment:${a.id}", a.name, buildJsonObject {
        put("_tag", "attachment")
        put("attachmentId", a.id)
        if (a.name.isNotBlank()) put("fileName", a.name.take(255))
        if (a.mimeType.isNotBlank()) put("mimeType", a.mimeType.take(100))
        put("disposition", "inline")
    })

    /** A file on the computer: absolute, or relative to the thread's workspace (contracts `media-file`). */
    fun mediaFile(threadId: String, path: String) = ImageRef.Asset("media-file:$threadId:$path", path.substringAfterLast('/'), buildJsonObject {
        put("_tag", "media-file")
        put("threadId", threadId)
        put("path", path)
    })

    /** The [index]th image of a tool's stored output (contracts `tool-output-image`). */
    fun toolOutput(threadId: String, itemId: String, index: Int) = ImageRef.Asset("tool-output-image:$threadId:$itemId:$index", "Tool output ${index + 1}", buildJsonObject {
        put("_tag", "tool-output-image")
        put("threadId", threadId)
        put("itemId", itemId)
        put("index", index)
    })

    /**
     * Where a Markdown image's bytes come from (client-runtime `classifyMarkdownImageSource`):
     * http(s) and `data:` load directly, host paths become `media-file` assets, anything else is
     * blocked and returns null. Relative paths resolve against the thread's workspace on the server.
     */
    fun markdownImage(src: String, alt: String, threadId: String?): ImageRef? {
        var source = src.trim()
        if (source.startsWith("<") && source.endsWith(">")) source = source.substring(1, source.length - 1)
        if (source.isEmpty() || source.startsWith("#") || source.startsWith("?")) return null
        val lower = source.lowercase()
        val name = alt.ifBlank { source.substringAfterLast('/').substringBefore('?') }
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("data:image/")) return ImageRef.Direct(source, name, source)
        if (source.startsWith("//")) return ImageRef.Direct(source, name, "https:$source")
        threadId ?: return null
        var path = if (lower.startsWith("file://")) source.substring(7) else source
        path = path.substringBefore('?').substringBefore('#')
        path = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        if (Regex("^/[A-Za-z]:[/\\\\]").containsMatchIn(path)) path = path.drop(1)
        if (path.isEmpty()) return null
        val absolute = path.startsWith("/") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path)
        if (!absolute && (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(path) || path.startsWith("~/") || path.startsWith("~\\"))) return null
        return mediaFile(threadId, path)
    }

    /**
     * shared `toolOutputImages`: the readable images of a tool's output (a block, a list of blocks,
     * or an MCP result's `content`), at most 8. Their order is the asset index.
     */
    fun toolOutputImageCount(output: JsonElement?): Int {
        val blocks: List<JsonElement> = when (output) {
            is JsonArray -> output
            is JsonObject -> output.arr("content") ?: listOf(output)
            else -> return 0
        }
        return minOf(8, blocks.count { (it as? JsonObject)?.let(::isReadableImage) == true })
    }

    /** `readToolOutputImage`: an MCP `{type: image, data, mimeType}` or Anthropic base64 `source` of a raster type. */
    fun isReadableImage(block: JsonObject): Boolean {
        if (block.str("type") != "image") return false
        val source = block.obj("source")
        if (source != null && source.str("type") != "base64") return false
        val mime = (if (source != null) source.str("media_type") else block.str("mimeType")) ?: return false
        return isRasterMime(mime)
    }

    /**
     * The box an image takes before its bytes arrive: its natural size scaled to fit [maxW] by
     * [maxH], ratio kept, never enlarged. Unknown sizes take T3's 16:9 loading slot.
     */
    fun fit(width: Int, height: Int, maxW: Float, maxH: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return maxW to maxW * 9f / 16f
        val scale = minOf(1f, maxW / width, maxH / height)
        return width * scale to height * scale
    }
}
