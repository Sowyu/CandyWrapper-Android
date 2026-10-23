package org.anikodev.candywrapper.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/**
 * Recorded traffic from CandyWrapper's fixtures (a real `t3 serve`, tokens and paths scrubbed).
 * Each line is `{dir, kind, t, body}`; `ws` bodies are RPC messages.
 */
object Transcripts {
    data class Frame(val dir: String, val kind: String, val t: Long, val body: JsonObject)

    fun load(name: String): List<Frame> {
        val text = Transcripts::class.java.getResource("/transcripts/$name.jsonl")!!.readText()
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val o = WireJson.parseToJsonElement(line) as JsonObject
            Frame(o.str("dir")!!, o.str("kind")!!, (o["t"] as kotlinx.serialization.json.JsonPrimitive).content.toLong(), o.obj("body")!!)
        }.toList()
    }

    /** The request id the client used for [tag] in this transcript. */
    fun requestId(frames: List<Frame>, tag: String): String? =
        frames.firstOrNull { it.dir == "send" && it.kind == "ws" && it.body.str("tag") == tag }?.body?.str("id")

    /** Every chunk's values for one stream, in arrival order. */
    fun chunks(frames: List<Frame>, requestId: String): List<JsonArray> =
        frames.filter { it.dir == "recv" && it.kind == "ws" && it.body.str("_tag") == "Chunk" && it.body.str("requestId") == requestId }
            .map { it.body.arr("values")!! }
}
