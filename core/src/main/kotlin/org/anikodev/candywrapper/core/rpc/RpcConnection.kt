package org.anikodev.candywrapper.core.rpc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.timing.Timeline

/**
 * One RPC session over one socket (protocol.md 2.3 to 2.5).
 *
 * Requests go out the moment they are made, so callers pipeline by calling [call] and [subscribe]
 * back to back without awaiting. Stream chunks are handed to the subscriber on the socket's reader
 * thread and acked right after, once per chunk; the server waits for that ack before it sends the
 * next chunk of the stream. Nothing here pings on a timer: callers use [ping] or a `server.probe`
 * call when they want to know the socket still answers.
 *
 * Create it, open the socket with this as the listener, then [attach] the socket.
 */
class RpcConnection : RpcSocketListener {
    fun interface StreamHandler {
        /** Runs on the reader thread. Keep it short; the ack goes out when it returns. */
        fun onValues(values: JsonArray)
    }

    inner class Subscription internal constructor(val requestId: String) {
        /** Stops the stream with an `Interrupt`. Idempotent. */
        fun cancel() {
            if (streams.remove(requestId) != null) socket?.send(RpcFrames.interrupt(requestId))
        }
    }

    private class Stream(val handler: StreamHandler, val onEnd: (Throwable?) -> Unit)

    @Volatile private var socket: RpcSocket? = null
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val streams = ConcurrentHashMap<String, Stream>()
    private var pongWaiter: CompletableDeferred<Unit>? = null
    private val closed = CompletableDeferred<Throwable>()

    /** When the last frame arrived, from System.nanoTime. */
    @Volatile var lastFrameNanos: Long = System.nanoTime()
        private set

    val isClosed: Boolean get() = closed.isCompleted

    fun attach(socket: RpcSocket) {
        this.socket = socket
    }

    /** Suspends until the socket closes and returns why. */
    suspend fun awaitClose(): Throwable = closed.await()

    /** Sends a unary request now. The result is the `Success` value; failures throw [RpcError]. */
    fun call(tag: String, payload: JsonObject = EMPTY): Deferred<JsonElement?> {
        val id = nextId.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement?>()
        pending[id] = result
        if (!send(RpcFrames.request(id, tag, payload))) {
            pending.remove(id)
            result.completeExceptionally(SocketClosed("The connection is closed."))
        }
        return result
    }

    suspend fun request(tag: String, payload: JsonObject = EMPTY, timeoutMs: Long = 15_000): JsonElement? =
        withTimeout(timeoutMs) { call(tag, payload).await() }

    /** Starts a stream now. [onEnd] runs once: null when the server completed it, else the error. */
    fun subscribe(tag: String, payload: JsonObject, onEnd: (Throwable?) -> Unit = {}, handler: StreamHandler): Subscription {
        val id = nextId.incrementAndGet().toString()
        streams[id] = Stream(handler, onEnd)
        if (!send(RpcFrames.request(id, tag, payload))) {
            streams.remove(id)
            onEnd(SocketClosed("The connection is closed."))
        }
        return Subscription(id)
    }

    /** Sends a `Ping` and waits for the `Pong`. False when none came in time or the socket closed. */
    suspend fun ping(timeoutMs: Long): Boolean {
        val waiter = synchronized(this) {
            pongWaiter ?: CompletableDeferred<Unit>().also {
                pongWaiter = it
                if (!send(RpcFrames.PING)) {
                    pongWaiter = null
                    return false
                }
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() } != null
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        socket?.close()
        onClosed(SocketClosed("Closed by the app."))
    }

    private fun send(text: String): Boolean = !isClosed && socket?.send(text) == true

    override fun onText(text: String) {
        lastFrameNanos = System.nanoTime()
        val messages = try {
            RpcFrames.parse(text)
        } catch (e: Exception) {
            Timeline.mark("rpc.frame.unreadable", e.message?.take(120))
            return
        }
        for (message in messages) {
            when (message) {
                is ServerMessage.Chunk -> {
                    val stream = streams[message.requestId]
                    if (stream != null) {
                        try {
                            stream.handler.onValues(message.values)
                        } catch (e: Exception) {
                            Timeline.mark("rpc.stream.handler.failed", "${e::class.simpleName}: ${e.message?.take(120)}")
                        }
                    }
                    send(RpcFrames.ack(message.requestId))
                }
                is ServerMessage.Exit -> {
                    pending.remove(message.requestId)?.let { d ->
                        if (message.error != null) d.completeExceptionally(message.error) else d.complete(message.value)
                    }
                    streams.remove(message.requestId)?.onEnd?.invoke(message.error)
                }
                ServerMessage.Pong -> takePong()?.complete(Unit)
                is ServerMessage.Defect -> Timeline.mark("rpc.defect", message.detail.take(160))
                is ServerMessage.ProtocolError -> Timeline.mark("rpc.protocol.error", message.detail.take(160))
            }
        }
    }

    override fun onClosed(error: Throwable) {
        if (!closed.complete(error)) return
        for (d in pending.values) d.completeExceptionally(error)
        pending.clear()
        val ended = streams.values.toList()
        streams.clear()
        for (s in ended) s.onEnd(error)
        takePong()?.completeExceptionally(error)
    }

    private fun takePong() = synchronized(this) { pongWaiter.also { pongWaiter = null } }

    companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}
