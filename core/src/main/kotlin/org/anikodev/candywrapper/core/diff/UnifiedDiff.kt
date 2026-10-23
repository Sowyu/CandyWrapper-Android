package org.anikodev.candywrapper.core.diff

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.media.Media

enum class LineKind { CONTEXT, ADDED, REMOVED, HUNK, NOTE }

/** One drawn line of a diff. Line numbers are 0 where the side has none. */
data class DiffLine(val kind: LineKind, val text: String, val oldLine: Int, val newLine: Int)

data class DiffFile(val path: String, val oldPath: String?, val additions: Int, val deletions: Int, val lines: List<DiffLine>, val binary: Boolean)

/**
 * Parses `git diff` output (what `getTurnDiff` and `getFullThreadDiff` return, protocol.md 8.4)
 * into files and numbered lines. Runs off the main thread; the view only draws [DiffLine]s.
 */
object UnifiedDiff {
    private val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$")

    fun parse(text: String): List<DiffFile> {
        val files = ArrayList<DiffFile>()
        var path: String? = null
        var oldPath: String? = null
        var lines = ArrayList<DiffLine>()
        var add = 0
        var del = 0
        var binary = false
        var oldNo = 0
        var newNo = 0
        fun flush() {
            val p = path ?: return
            files += DiffFile(p, oldPath?.takeIf { it != p }, add, del, lines, binary)
            path = null; oldPath = null; lines = ArrayList(); add = 0; del = 0; binary = false
        }
        for (raw in text.split('\n')) {
            when {
                raw.startsWith("diff --git ") -> {
                    flush()
                    val parts = raw.removePrefix("diff --git ").split(" b/", limit = 2)
                    oldPath = parts.getOrNull(0)?.removePrefix("a/")
                    path = parts.getOrNull(1) ?: oldPath
                }
                raw.startsWith("--- ") && lines.isEmpty() -> {
                    val p = raw.removePrefix("--- ")
                    if (p != "/dev/null") oldPath = p.removePrefix("a/")
                    if (path == null) path = oldPath
                }
                raw.startsWith("+++ ") && lines.isEmpty() -> {
                    val p = raw.removePrefix("+++ ")
                    if (p != "/dev/null") path = p.removePrefix("b/")
                }
                raw.startsWith("Binary files") -> binary = true
                raw.startsWith("@@") -> {
                    if (path == null) path = "diff"
                    val m = hunk.find(raw)
                    oldNo = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    newNo = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    lines += DiffLine(LineKind.HUNK, m?.groupValues?.get(3)?.trim().orEmpty(), 0, 0)
                }
                lines.isEmpty() && path != null -> Unit // index, mode and rename headers
                raw.startsWith("+") -> { lines += DiffLine(LineKind.ADDED, raw.substring(1), 0, newNo++); add++ }
                raw.startsWith("-") -> { lines += DiffLine(LineKind.REMOVED, raw.substring(1), oldNo++, 0); del++ }
                raw.startsWith(" ") -> lines += DiffLine(LineKind.CONTEXT, raw.substring(1), oldNo++, newNo++)
                raw.startsWith("\\") -> lines += DiffLine(LineKind.NOTE, raw.removePrefix("\\ "), 0, 0)
                raw.isEmpty() && path != null && lines.isNotEmpty() -> Unit
            }
        }
        flush()
        return files
    }

    /**
     * A line diff of two texts, for edits that carry `oldStr` and `newStr` but no patch. Plain LCS,
     * so it is capped: past [MAX_LINES] lines per side it shows the old text removed and the new added.
     */
    // ponytail: O(n*m) LCS capped at 400 lines a side; switch to Myers if edits that big show up inline.
    fun lines(old: String, new: String): List<DiffLine> {
        val a = old.split('\n')
        val b = new.split('\n')
        if (a.size > MAX_LINES || b.size > MAX_LINES) {
            return a.mapIndexed { i, t -> DiffLine(LineKind.REMOVED, t, i + 1, 0) } + b.mapIndexed { i, t -> DiffLine(LineKind.ADDED, t, 0, i + 1) }
        }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<DiffLine>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { out += DiffLine(LineKind.CONTEXT, a[i], i + 1, j + 1); i++; j++ }
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += DiffLine(LineKind.REMOVED, a[i], i + 1, 0); i++ }
                else -> { out += DiffLine(LineKind.ADDED, b[j], 0, j + 1); j++ }
            }
        }
        return out
    }

    const val MAX_LINES = 400
}

/**
 * What an expanded tool row shows beyond its summary, read from `orchestration.getTurnItem`
 * (protocol.md 7.5): a command's output, a tool's input and output, or an edit's diff.
 */
/** A row's fetched detail: text, a diff, and how many images its tool output holds (served as `tool-output-image` assets). */
data class ItemDetail(val text: String?, val diff: List<DiffLine>?, val images: Int = 0) {
    companion object {
        private const val MAX_TEXT = 16 * 1024

        fun from(item: JsonObject?): ItemDetail {
            item ?: return ItemDetail("Output is no longer available.", null)
            return when (item.str("type")) {
                "command_execution" -> ItemDetail(item.str("output")?.take(MAX_TEXT) ?: "No output.", null)
                "file_change" -> {
                    val patch = item.str("diffStr")
                    val diff = when {
                        patch != null -> UnifiedDiff.parse(patch).flatMap { it.lines }.ifEmpty { null }
                        item.str("oldStr") != null || item.str("newStr") != null -> UnifiedDiff.lines(item.str("oldStr").orEmpty(), item.str("newStr").orEmpty())
                        else -> null
                    }
                    val files = item.arr("changes")?.mapNotNull { (it as? JsonObject)?.let { c -> listOfNotNull(c.str("operation"), c.str("path")).joinToString(" ") } }
                    ItemDetail(if (diff == null) files?.joinToString("\n") ?: item.str("fileName") else null, diff)
                }
                "dynamic_tool" -> ItemDetail(
                    listOfNotNull(
                        item["input"]?.let { "Input\n" + pretty(it) },
                        item["output"]?.takeIf { it !is JsonNull }?.let { "Output\n" + pretty(withoutImageData(it)) },
                    ).joinToString("\n\n").take(MAX_TEXT).ifEmpty { null },
                    null,
                    images = if (item.bool("outputOmitted") == true) 0 else Media.toolOutputImageCount(item["output"]),
                )
                "subagent" -> ItemDetail(item.str("result") ?: item.str("progress") ?: item.str("prompt"), null)
                else -> ItemDetail(null, null)
            }
        }

        /** True when the stream left something out that a fetch would add (client-runtime itemDetail.ts:140-149). */
        fun worthFetching(type: String): Boolean = type == "command_execution" || type == "file_change" || type == "dynamic_tool" || type == "subagent"

        /** The output with each image's base64 swapped for a marker; the images draw under the text. */
        private fun withoutImageData(e: JsonElement): JsonElement = when {
            e is JsonObject && e.str("type") == "image" -> JsonPrimitive("[image]")
            e is JsonObject -> JsonObject(e.mapValues { withoutImageData(it.value) })
            e is JsonArray -> JsonArray(e.map(::withoutImageData))
            else -> e
        }

        private fun pretty(e: JsonElement): String = when (e) {
            JsonNull -> ""
            is JsonPrimitive -> e.content
            is JsonObject, is JsonArray -> if (e is JsonObject && e.bool("truncated") == true) e.str("summary") ?: e.toString()
            else runCatching { prettyJson.encodeToString(JsonElement.serializer(), e) }.getOrDefault(e.toString())
        }

        private val prettyJson = kotlinx.serialization.json.Json(from = WireJson) { prettyPrint = true; prettyPrintIndent = "  " }
    }
}
