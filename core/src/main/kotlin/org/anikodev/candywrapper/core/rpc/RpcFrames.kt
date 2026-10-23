package org.anikodev.candywrapper.core.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/** A message from the server (protocol.md 2.3). */
sealed interface ServerMessage {
    /** One or more stream items. The server sends the next chunk of this stream only after our Ack. */
    data class Chunk(val requestId: String, val values: JsonArray) : ServerMessage

    /** The end of a unary call or a stream. */
    data class Exit(val requestId: String, val value: JsonElement?, val error: RpcError?) : ServerMessage

    data object Pong : ServerMessage

    data class Defect(val detail: String) : ServerMessage

    data class ProtocolError(val detail: String) : ServerMessage
}

/**
 * A failed request. [tag] is the typed error `_tag` when the cause was a `Fail`; [interrupted] is
 * true when the server ended the request because we interrupted it (protocol.md 2.6).
 */
class RpcError(message: String, val tag: String? = null, val interrupted: Boolean = false) : Exception(message)

/** Effect RPC JSON framing: builds client frames and splits server frames into messages. */
object RpcFrames {
    const val PING = """{"_tag":"Ping"}"""

    fun request(id: String, tag: String, payload: JsonObject): String = buildJsonObject {
        put("_tag", "Request")
        put("id", id)
        put("tag", tag)
        put("payload", payload)
        put("headers", JsonArray(emptyList()))
    }.toString()

    // Request ids are our own decimal strings, so they need no escaping.
    fun ack(requestId: String) = """{"_tag":"Ack","requestId":"$requestId"}"""

    fun interrupt(requestId: String) = """{"_tag":"Interrupt","requestId":"$requestId"}"""

    /** A text frame holds one message or an array of messages. Unknown `_tag`s are skipped. */
    fun parse(text: String): List<ServerMessage> = when (val root = WireJson.parseToJsonElement(text)) {
        is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let(::message) }
        is JsonObject -> listOfNotNull(message(root))
        else -> emptyList()
    }

    private fun message(o: JsonObject): ServerMessage? = when (o.str("_tag")) {
        "Chunk" -> ServerMessage.Chunk(id(o["requestId"]), o.arr("values") ?: JsonArray(emptyList()))
        "Exit" -> exit(id(o["requestId"]), o.obj("exit"))
        "Pong" -> ServerMessage.Pong
        "Defect" -> ServerMessage.Defect(o["defect"].describe())
        "ClientProtocolError" -> ServerMessage.ProtocolError(o["error"].describe())
        else -> null
    }

    // The server echoes the id we sent; accept a number too.
    private fun id(e: JsonElement?): String = (e as? JsonPrimitive)?.content ?: ""

    private fun exit(requestId: String, exit: JsonObject?): ServerMessage.Exit {
        if (exit?.str("_tag") == "Success") return ServerMessage.Exit(requestId, exit["value"] ?: JsonNull, null)
        return ServerMessage.Exit(requestId, null, failure(exit?.arr("cause")))
    }

    /** The first useful cause: a typed error's message, a defect, or an interrupt. */
    fun failure(cause: JsonArray?): RpcError {
        val nodes = cause?.mapNotNull { it as? JsonObject }.orEmpty()
        nodes.firstOrNull { it.str("_tag") == "Fail" }?.obj("error")?.let { error ->
            val message = error.str("message") ?: error.str("detail") ?: error.str("_tag") ?: "Request failed"
            return RpcError(message, tag = error.str("_tag"))
        }
        nodes.firstOrNull { it.str("_tag") == "Die" }?.let { return RpcError(it["defect"].describe()) }
        if (nodes.any { it.str("_tag") == "Interrupt" }) return RpcError("Interrupted", interrupted = true)
        return RpcError("Request failed")
    }

    private fun JsonElement?.describe(): String = when (this) {
        null, JsonNull -> "unknown"
        is JsonPrimitive -> content
        is JsonObject -> str("message") ?: toString()
        else -> toString()
    }.take(500)
}
