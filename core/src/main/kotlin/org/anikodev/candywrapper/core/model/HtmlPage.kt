package org.anikodev.candywrapper.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.str

/**
 * A page an agent published with T3's `html_render` tool (CandyWrapper ThreadWebContent.swift, T3 toolOutput.ts
 * `htmlRenderFromToolItem`). The page itself is an attachment the app opens through a signed `assets.createUrl`.
 */
@Serializable
data class HtmlPage(val attachmentId: String, val title: String) {
    /** `htmlRenderFileName`. */
    val fileName: String
        get() {
            val name = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), " ").replace(Regex("""\s+"""), " ").trim()
            return name.take(120).trim().ifEmpty { "Page" } + ".html"
        }

    companion object {
        /** A completed, not failed `dynamic_tool` item of T3's `html_render`, else null. */
        fun fromItem(o: JsonObject): HtmlPage? {
            if (o.str("type") != "dynamic_tool" || o.str("status") != "completed") return null
            if (toolName(o.str("toolName").orEmpty()) != "html_render") return null
            val budget = intArrayOf(128)
            val (data, failed) = read(o["output"], 0, budget)
            if (failed) return null
            val ref = data?.get("htmlRender") as? JsonObject ?: return null
            val id = ref.str("attachmentId")?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
            val title = ref.str("title") ?: return null
            if ((ref["height"] as? JsonPrimitive)?.doubleOrNull?.isFinite() != true) return null
            return HtmlPage(id, title.trim().take(200).ifEmpty { "HTML" })
        }

        /** `resolveT3McpToolName` for this one tool: the bare name in any provider's spelling. */
        fun toolName(value: String): String? {
            val label = value.trim()
            Regex("""mcp__(.+?)__(.+)""", RegexOption.IGNORE_CASE).matchEntire(label)?.let { m ->
                return if (m.groupValues[1].lowercase() in setOf("t3-code", "t3_code", "t3code")) m.groupValues[2] else null
            }
            Regex("""(?:t3-code|t3_code|t3code)(?:[.:/]|\s*·\s*)(.+)""", RegexOption.IGNORE_CASE).matchEntire(label)?.let { return it.groupValues[1] }
            if (label == "html_render" || label.endsWith("_html_render") && label.lowercase().startsWith("t3-code-")) return "html_render"
            return label
        }

        /** `compactDynamicToolOutput`'s envelope read: JSON text parses, content blocks give their first data. */
        private fun read(value: JsonElement?, depth: Int, budget: IntArray): Pair<JsonObject?, Boolean> {
            budget[0]--
            if (depth > 4 || budget[0] < 0 || value == null || value is JsonNull) return null to false
            return when (value) {
                is JsonPrimitive -> {
                    if (!value.isString || value.content.length > 16_384) return null to false
                    val parsed = runCatching { WireJson.parseToJsonElement(value.content) }.getOrNull() ?: return null to false
                    read(parsed, depth + 1, budget)
                }
                is JsonArray -> {
                    if (value.size > 32) return null to false
                    var data: JsonObject? = null
                    var failed = false
                    for (b in value) {
                        val text = (b as? JsonObject)?.get("text")
                        val r = read((text as? JsonObject)?.get("text") ?: text, depth + 1, budget)
                        data = data ?: r.first
                        failed = failed || r.second
                    }
                    data to failed
                }
                is JsonObject -> {
                    val failed = (value["isError"] as? JsonPrimitive)?.booleanOrNull == true || (value["is_error"] as? JsonPrimitive)?.booleanOrNull == true ||
                        value.str("_tag") == "OrchestratorMcpFailure" || (value["error"] != null && value["error"] !is JsonNull)
                    val content = value["structuredContent"] ?: value["content"]
                    if (content != null) {
                        val nested = read(content, depth + 1, budget)
                        nested.first to (failed || nested.second)
                    } else value to failed
                }
            }
        }
    }
}
