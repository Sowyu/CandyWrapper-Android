package org.anikodev.candywrapper.core.http

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.str

/**
 * The shared OkHttp clients. They share one connection pool and DNS cache, so a host resolved or
 * connected once is warm for every later request in the process.
 *
 * - [client] for Clerk and the relay (HTTP/2 where offered).
 * - [environment] for every request to a T3 environment. It is HTTP/1.1 only, matching what OkHttp
 *   uses for WebSockets, so the pooled connection the ticket POST leaves behind is the one the
 *   socket upgrade reuses: no second TCP and TLS handshake.
 * - [socket] for the WebSocket itself: same pool and address, no read timeout.
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val environment: OkHttpClient = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

    /** For descriptor checks on learned LAN and tailnet addresses, which fail fast when away from home. */
    val probe: OkHttpClient = environment.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    val socket: OkHttpClient = environment.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Resolves hosts now, in parallel, so the first real request skips the DNS wait. */
    fun prefetchDns(scope: CoroutineScope, hosts: Collection<String>) {
        for (host in hosts.toSet()) {
            scope.launch(Dispatchers.IO) { runCatching { InetAddress.getAllByName(host) } }
        }
    }
}

/** A non-2xx answer with the fields of T3's and the relay's tagged error bodies (protocol.md 4.6, 10.9). */
class HttpStatusError(
    val status: Int,
    val tag: String?,
    val code: String?,
    val reason: String?,
    val dpopFailureReason: String?,
    val serverMessage: String?,
    val url: String,
) : IOException("HTTP $status${tag?.let { " $it" } ?: ""}${reason?.let { " ($it)" } ?: ""}")

/** Runs the call without blocking a thread; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}

/** Executes [request] and returns the JSON object body of a 2xx, else throws [HttpStatusError]. */
suspend fun OkHttpClient.json(request: Request): JsonObject {
    val response = newCall(request).await()
    val body = response.use { it.body.string() }
    if (!response.isSuccessful) throw statusError(response.code, body, request.url.toString())
    return WireJson.parseToJsonElement(body) as? JsonObject ?: throw IOException("Expected a JSON object from ${request.url.encodedPath}")
}

internal fun statusError(status: Int, body: String, url: String): HttpStatusError {
    val o = runCatching { WireJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    // Strip the query: it can hold a ticket.
    return HttpStatusError(status, o?.str("_tag"), o?.str("code"), o?.str("reason"), o?.str("dpopFailureReason"),
        o?.str("message"), url.substringBefore('?'))
}

fun formBody(vararg fields: Pair<String, String?>): FormBody {
    val b = FormBody.Builder()
    for ((k, v) in fields) if (v != null) b.add(k, v)
    return b.build()
}
