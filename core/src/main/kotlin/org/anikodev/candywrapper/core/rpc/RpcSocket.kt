package org.anikodev.candywrapper.core.rpc

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The text-frame socket [RpcConnection] runs on: OkHttp in the app, a fake in tests. */
interface RpcSocket {
    fun send(text: String): Boolean
    fun close()
}

/** Receives frames on the socket's reader thread, in order. */
interface RpcSocketListener {
    fun onText(text: String)
    fun onClosed(error: Throwable)
}

/** The upgrade was answered with an HTTP status instead of 101: 401 for a bad ticket, 426 for the protocol. */
class WebSocketRejected(val status: Int?, message: String) : IOException(message)

class SocketClosed(message: String) : IOException(message)

object OkHttpRpcSocket {
    /**
     * Opens the WebSocket and suspends until the upgrade completes. [client] should share the
     * connection pool and protocols of the client that minted the ticket, so the upgrade reuses
     * that warm TCP and TLS connection (see Http.socket). Cancelling the caller cancels the socket.
     */
    suspend fun open(client: OkHttpClient, url: String, listener: RpcSocketListener): RpcSocket =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url).build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                @Volatile var opened = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    if (cont.isActive) cont.resume(Wrapper(webSocket)) else webSocket.cancel()
                }

                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(SocketClosed("Closed by the server ($code${if (reason.isBlank()) "" else ": $reason"})"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened) {
                        val error = if (response != null) {
                            WebSocketRejected(response.code, "The server refused the WebSocket (HTTP ${response.code}).")
                        } else {
                            t
                        }
                        if (cont.isActive) cont.resumeWithException(error)
                    } else {
                        listener.onClosed(t)
                    }
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }

    private class Wrapper(private val ws: WebSocket) : RpcSocket {
        override fun send(text: String) = ws.send(text)
        override fun close() {
            ws.close(1000, null)
        }
    }
}
