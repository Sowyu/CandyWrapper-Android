package org.anikodev.candywrapper.core.media

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ProjectIconOverride
import org.anikodev.candywrapper.core.model.ProjectSummary

/**
 * A project's icon file, as T3 finds it: `assets.createUrl` with a `project-favicon` resource
 * (contracts assets.ts), then the signed URL (client-runtime state/assets.ts
 * `createProjectFaviconUrlAtomFamily`). The keys follow shared `projectFavicon.ts`.
 */
object ProjectIcons {
    /** client-runtime `PROJECT_FAVICON_THUMBNAIL_SIZE`: the long side, in pixels, of what is kept. */
    const val THUMBNAIL_PX = 96

    /** `PROJECT_FAVICON_MAX_SOURCE_BYTES`: larger files are not worth decoding for an icon. */
    const val MAX_SOURCE_BYTES = 4L * 1024 * 1024

    /** `PROJECT_FAVICON_CACHE_MAX_ENTRIES`. */
    const val MAX_ENTRIES = 128

    /** shared `PROJECT_FAVICON_FALLBACK_MARKER`: the file name the server signs when a workspace has no icon. */
    const val MISSING_MARKER = "project-favicon-missing"

    /** contracts `ProjectIconColor`, in the order `projectIdentity.ts` picks from. */
    val COLORS = listOf(
        "gray", "red", "orange", "amber", "yellow", "lime", "green", "emerald", "teal",
        "cyan", "sky", "blue", "indigo", "violet", "purple", "fuchsia", "pink", "rose",
    )

    /** The icon file is only asked for when the user has not chosen an icon, as T3 draws the override first. */
    fun wantsFile(p: ProjectSummary) = p.icon == null && p.workspaceRoot.isNotBlank() && p.envId.isNotEmpty()

    /** The `project-favicon` resource. `path` is only a cache hint; the server reads the real one from the project. */
    fun resource(cwd: String, faviconPath: String?): JsonObject = buildJsonObject {
        put("_tag", "project-favicon")
        put("cwd", cwd)
        if (!faviconPath.isNullOrEmpty()) put("path", faviconPath)
    }

    /** `getProjectFaviconResourceKey`: which icon this is, whatever its contents. */
    fun resourceKey(envId: String, cwd: String, faviconPath: String?): String = buildJsonArray {
        add(JsonPrimitive(envId)); add(JsonPrimitive(cwd)); add(if (faviconPath.isNullOrEmpty()) JsonNull else JsonPrimitive(faviconPath))
    }.toString()

    fun resourceKey(p: ProjectSummary) = resourceKey(p.envId, p.workspaceRoot, p.faviconPath)

    /**
     * The revision part of `getProjectFaviconCacheKey`: the signed URL's last path segment. The
     * server names it after the file's SHA-256 and base name, so it holds while the signature
     * changes every hour and moves when the icon's bytes do.
     */
    fun revision(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        return path.substringAfterLast('/').ifEmpty { url }
    }

    /** `isProjectFaviconFallbackUrl`: the server looked and found no icon. */
    fun isMissing(url: String) = revision(url) == MISSING_MARKER

    /**
     * `ReceivedProjectIcon`, read as CandyWrapper's `ProjectIconSource.override` does: unknown kinds
     * mean no override, and a lucide icon carrying `monogramText` or `monogram` is a monogram from
     * an older server.
     */
    fun override(o: JsonObject?): ProjectIconOverride? {
        o ?: return null
        val color = o.str("color")?.takeIf { it in COLORS } ?: "gray"
        return when (o.str("kind")) {
            "monogram" -> o.str("text")?.let { ProjectIconOverride(ProjectIconOverride.Kind.MONOGRAM, it, color) }
            "emoji" -> o.str("emoji")?.let { ProjectIconOverride(ProjectIconOverride.Kind.EMOJI, it) }
            "lucide" -> (o.str("monogramText") ?: o.str("monogram"))?.let { ProjectIconOverride(ProjectIconOverride.Kind.MONOGRAM, it, color) }
                ?: o.str("name")?.let { ProjectIconOverride(ProjectIconOverride.Kind.LUCIDE, it, color) }
            else -> null
        }
    }

    /** What the bytes are, from the response's type, else the URL's extension. Null when neither says image. */
    fun mimeType(contentType: String?, url: String): String? {
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.startsWith("image/") }?.let { return it }
        return when (revision(url).substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "icns" -> "image/icns"
            else -> null
        }
    }
}
