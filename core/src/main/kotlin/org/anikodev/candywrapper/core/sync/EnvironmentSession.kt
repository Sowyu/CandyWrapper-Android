package org.anikodev.candywrapper.core.sync

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.auth.EnvironmentApi
import org.anikodev.candywrapper.core.auth.EnvironmentMismatch
import org.anikodev.candywrapper.core.auth.IncompatibleEnvironment
import org.anikodev.candywrapper.core.connect.RelayError
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.HttpStatusError
import org.anikodev.candywrapper.core.http.await
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.http.json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.DirectRoute
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.SavedEnvironment
import org.anikodev.candywrapper.core.model.ServerInfo
import org.anikodev.candywrapper.core.rpc.OkHttpRpcSocket
import org.anikodev.candywrapper.core.rpc.RpcConnection
import org.anikodev.candywrapper.core.rpc.RpcError
import org.anikodev.candywrapper.core.rpc.WebSocketRejected
import org.anikodev.candywrapper.core.store.CachedShell
import org.anikodev.candywrapper.core.store.LocalCache
import org.anikodev.candywrapper.core.store.SecretStore
import org.anikodev.candywrapper.core.store.getJson
import org.anikodev.candywrapper.core.store.putJson
import org.anikodev.candywrapper.core.timing.Timeline

/** A command or request needs the socket, and it is not open. */
class NotConnected : IOException("Not connected to the computer. Try again when it is back.")

sealed interface ConnectionState {
    data object Idle : ConnectionState
    /** [step] is the real step in progress, for example `ticket` or `relay.connect`. */
    data class Connecting(val step: String) : ConnectionState
    data class Live(val route: String, val connectMs: Long) : ConnectionState
    data class Offline(val message: String, val retryAtMs: Long?) : ConnectionState
    data class Blocked(val message: String, val reason: BlockReason) : ConnectionState
}

@Serializable
private data class StoredTicket(val ticket: String, val expiresAtMs: Long, val usableUntilMs: Long)

/**
 * One environment: its connection, its shell and its open threads.
 *
 * Connecting takes the fewest round trips the stored state allows:
 * 1. A ticket from the last five minutes opens the socket directly (tickets are verified, not
 *    consumed; protocol.md 4.4).
 * 2. Otherwise a stored credential mints a ticket, and the socket reuses that request's warm
 *    connection.
 * 3. Otherwise the credential is renewed first (T3 Connect: [ConnectCredentials]).
 *
 * Each attempt races the primary address against the LAN and tailnet addresses the host reported
 * last time and keeps the first socket that opens.
 *
 * Once open, the shell and thread subscriptions and `server.getConfig` go out back to back without
 * waiting, the shell resuming from the cached `afterSequence`. Nothing pings on a timer except on
 * a relay tunnel while the app is in front; returning to the app sends `server.probe` instead of
 * reconnecting.
 */
class EnvironmentSession(
    initial: SavedEnvironment,
    cachedShell: CachedShell?,
    private val cache: LocalCache,
    private val secrets: SecretStore,
    private val credentials: Credentials,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _env = MutableStateFlow(initial)
    val env: StateFlow<SavedEnvironment> = _env
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val shellLock = Any()
    private val shell = ShellProjection(cachedShell)
    private val _threads = MutableStateFlow(shell.model())
    val threads: StateFlow<ThreadListModel> = _threads

    private val _server = MutableStateFlow(ServerInfo())
    /** Providers, models and new-thread defaults from the last `server.getConfig`. */
    val server: StateFlow<ServerInfo> = _server

    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var connection: RpcConnection? = null
    @Volatile private var route: Route? = null
    @Volatile private var foreground = true
    private var job: Job? = null
    private var pingJob: Job? = null
    private var shellSaveJob: Job? = null
    private val threadSessions = LinkedHashMap<String, ThreadSession>(16, 0.75f, true)

    val id: String get() = _env.value.id
    private val trace: String get() = "env." + _env.value.label.replace(Regex("\\s+"), "_")

    fun start() {
        if (job != null) return
        job = scope.launch(io) { supervise() }
    }

    /** Stops for good (the environment was removed). */
    fun stop() {
        job?.cancel()
        pingJob?.cancel()
        connection?.close()
    }

    /** Skips any backoff wait: the user retried, signed in, or the network came back. */
    fun wake() {
        wakeSignal.trySend(Unit)
    }

    /** From the app's process lifecycle. Coming to the front probes the socket or reconnects at once. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) scope.launch(io) { probeOrReconnect() } else pingJob?.cancel()
    }

    /** The network changed: check the socket still answers. */
    fun networkChanged() {
        scope.launch(io) { probeOrReconnect() }
    }

    fun thread(threadId: String): ThreadSession = synchronized(threadSessions) {
        threadSessions.getOrPut(threadId) { ThreadSession(this, threadId, "$trace.thread.${threadId.take(8)}", cache, scope, io, ::liveConnection) }.also {
            // Keep the threads in use plus the last few closed ones in memory for instant reopen.
            if (threadSessions.size > RETAINED_THREADS) {
                threadSessions.entries.filter { !it.value.held && it.key != threadId }
                    .take(threadSessions.size - RETAINED_THREADS).forEach { e -> threadSessions.remove(e.key) }
            }
        }
    }

    /** The socket if it is open, for thread sessions that start after it did. */
    fun liveConnection(): RpcConnection? = connection?.takeUnless { it.isClosed }

    // Commands and requests

    /** A unary RPC on the open socket. Throws [NotConnected] while offline. */
    suspend fun request(tag: String, payload: JsonObject = RpcConnection.EMPTY, timeoutMs: Long = 15_000): JsonElement? {
        val conn = liveConnection() ?: throw NotConnected()
        return conn.request(tag, payload, timeoutMs)
    }

    /**
     * The archived threads, grouped by project (`orchestration.getArchivedShellSnapshot`, input `{}`).
     * Read when the sidebar's archived list opens; nothing is cached.
     */
    suspend fun archivedThreads(): ThreadListModel {
        val start = Timeline.nowMs()
        val result = request("orchestration.getArchivedShellSnapshot") as? JsonObject ?: return ThreadListModel()
        // Hundreds of archived threads take a moment to read and sort; keep it off the caller's thread.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ShellProjection.archivedModel(result) }.also { Timeline.span("$trace.archived.loaded", start, "${it.threadCount} threads") }
    }

    /**
     * Sends one `orchestration.dispatchCommand` and returns the sequence of its last event
     * (protocol.md 7.2). Failures carry the server's user-facing message.
     */
    suspend fun dispatch(command: JsonObject): Long {
        val start = Timeline.nowMs()
        val result = request("orchestration.dispatchCommand", command) as? JsonObject
        Timeline.span("$trace.command.${command.str("type")}", start)
        return result?.long("sequence") ?: 0
    }

    /** An authorized GET on the route the socket uses, with the orchestration protocol header (protocol.md 5.1). */
    suspend fun getJson(path: String, query: Map<String, String>): JsonObject {
        val env = _env.value
        val base = route?.httpBase ?: env.httpBase
        val url = base.toHttpUrl().newBuilder().encodedPath(path).query(null)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val auth = credentials.authorize(env, null, Tracer("$trace.http"))
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("x-t3-orchestration-protocol", EnvironmentApi.PROTOCOL.toString())
            .apply { auth.headers("GET", url).forEach { (k, v) -> header(k, v) } }
            .build()
        return Http.environment.json(request)
    }

    /**
     * Uploads an attachment (protocol.md 5.3): a signed upload URL over RPC, then the bytes. The
     * signed path is the credential, so the POST carries no auth header.
     */
    suspend fun upload(name: String, mimeType: String, bytes: ByteArray): UploadedAttachment {
        val type = if (mimeType.startsWith("image/")) "image" else "file"
        val minted = request("attachments.createUploadUrl", buildJsonObject {
            put("type", type)
            put("name", name.take(255))
            put("mimeType", mimeType)
            put("sizeBytes", bytes.size)
        }) as? JsonObject ?: throw IOException("The computer did not return an upload address.")
        val id = minted.str("attachmentId") ?: throw IOException("The upload answer has no attachmentId.")
        val relative = minted.str("relativeUrl") ?: throw IOException("The upload answer has no relativeUrl.")
        val base = (route?.httpBase ?: _env.value.httpBase).toHttpUrl()
        val url = base.resolve(relative) ?: throw IOException("Bad upload address.")
        val response = Http.environment.newCall(Request.Builder().url(url).post(bytes.toRequestBody(mimeType.toMediaTypeOrNull())).build())
            .await()
        response.use { if (!it.isSuccessful) throw HttpStatusError(it.code, null, null, null, null, null, url.encodedPath) }
        return UploadedAttachment(id, name, mimeType, bytes.size.toLong())
    }

    // Supervision

    private suspend fun supervise() {
        var failures = 0
        while (scope.isActive) {
            if (!foreground && connection == null) {
                // In the background nothing reconnects until the app comes back.
                wakeSignal.receive()
            }
            val started = System.currentTimeMillis()
            var message: String
            try {
                val (conn, r) = connectOnce()
                onOpen(conn, r, started)
                val error = conn.awaitClose()
                pingJob?.cancel()
                connection = null
                if (System.currentTimeMillis() - started > BACKOFF_RESET_MS) failures = 0
                message = "Disconnected: ${describe(error)}"
                Timeline.mark("$trace.socket.closed", error.message?.take(120))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Blocked) {
                _state.value = ConnectionState.Blocked(e.message.orEmpty(), e.reason)
                Timeline.mark("$trace.blocked", e.reason.name)
                wakeSignal.receive()
                failures = 0
                continue
            } catch (e: Throwable) {
                message = describe(e)
                Timeline.mark("$trace.connect.failed", "${e::class.simpleName}: ${message.take(120)}")
            }
            val delayMs = backoff(failures++)
            _state.value = ConnectionState.Offline(message, if (foreground) System.currentTimeMillis() + delayMs else null)
            if (foreground) withTimeoutOrNull(delayMs) { wakeSignal.receive() } else wakeSignal.receive()
        }
    }

    private suspend fun probeOrReconnect() {
        val conn = connection
        if (conn == null || conn.isClosed) {
            wake()
            return
        }
        val start = Timeline.nowMs()
        val alive = try {
            withTimeout(PROBE_TIMEOUT_MS) { conn.call("server.probe").await() }
            true
        } catch (e: RpcError) {
            true // An old server without server.probe still answered.
        } catch (e: Exception) {
            false
        }
        Timeline.span("$trace.probe", start, if (alive) "alive" else "dead, reconnecting")
        if (alive) {
            startPings(conn)
        } else {
            conn.close()
            wake()
        }
    }

    // Connecting

    private suspend fun connectOnce(): Pair<RpcConnection, Route> {
        val tracer = Tracer("connect.${trace.removePrefix("env.")}") { step -> _state.value = ConnectionState.Connecting(step) }
        val env = _env.value
        tracer.mark("start", if (env.kind == EnvironmentKind.CONNECT) "t3-connect" else "paired")
        val stored = secrets.getJson(ticketKey(env.id), StoredTicket.serializer())
        val now = System.currentTimeMillis()
        if (stored != null && minOf(stored.expiresAtMs, stored.usableUntilMs, credentials.cachedUntil(env)) - now > TICKET_MARGIN_MS) {
            try {
                return open(routes(env, null), tracer) { stored.ticket }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tracer.mark("ticket.cached.refused", e.message?.take(80))
                if (e is WebSocketRejected && e.status == 401) secrets.put(ticketKey(env.id), null)
                if (e is EnvironmentMismatch) throw e
            }
        }
        var rejected: String? = null
        while (true) {
            val auth = credentials.authorize(env, rejected, tracer)
            auth.endpoint?.let { endpoint ->
                if (endpoint.httpBaseUrl != env.httpBase || endpoint.wsBaseUrl != env.wsBase) {
                    updateEnv { it.copy(httpBase = endpoint.httpBaseUrl, wsBase = endpoint.wsBaseUrl) }
                }
            }
            try {
                return open(routes(_env.value, auth), tracer) { r ->
                    val url = EnvironmentApi.ticketUrl(r.httpBase)
                    val ticket = tracer.step("ticket") { EnvironmentApi.ticket(url, auth.headers("POST", url)) }
                    secrets.putJson(ticketKey(env.id), StoredTicket.serializer(), StoredTicket(ticket.ticket, ticket.expiresAtMs, auth.usableUntilMs))
                    ticket.ticket
                }
            } catch (e: HttpStatusError) {
                // One renewal and retry on a refused credential (environmentHttpAuth.ts:148-207).
                if (e.status != 401 || rejected != null) throw e
                rejected = auth.token
            }
        }
    }

    private fun routes(env: SavedEnvironment, auth: Authorization?): List<Route> {
        val label = if (env.kind == EnvironmentKind.CONNECT) "tunnel" else "direct"
        val http = auth?.endpoint?.httpBaseUrl ?: env.httpBase
        val ws = auth?.endpoint?.wsBaseUrl ?: env.wsBase
        return Routes.build(http, ws, label, env.directRoutes)
    }

    private suspend fun open(routes: List<Route>, tracer: Tracer, ticketFor: suspend (Route) -> String): Pair<RpcConnection, Route> {
        val envId = _env.value.id
        val (_, opened) = Routes.race(routes.map { r ->
            r.label to suspend {
                if (r.verifyFirst) {
                    val d = tracer.step("descriptor[${r.label}]") {
                        EnvironmentApi.descriptor(r.httpBase, if (r.learned) Http.probe else Http.environment)
                    }
                    if (d.environmentId != envId) throw EnvironmentMismatch(envId, d.environmentId)
                }
                val ticket = ticketFor(r)
                val conn = RpcConnection()
                val socket = tracer.step("socket[${r.label}]") {
                    OkHttpRpcSocket.open(Http.socket, EnvironmentApi.socketUrl(r.wsBase, ticket, credentials.connectionMethod), conn)
                }
                conn.attach(socket)
                conn to r
            }
        }, discard = { it.first.close() })
        return opened
    }

    /** Sends the first requests back to back, without waiting for any answer. */
    private fun onOpen(conn: RpcConnection, r: Route, startedAtMs: Long) {
        connection = conn
        route = r
        val connectMs = System.currentTimeMillis() - startedAtMs
        _state.value = ConnectionState.Live(r.label, connectMs)
        Timeline.mark("$trace.socket.open", "${r.label}, $connectMs ms")
        subscribeShell(conn)
        synchronized(threadSessions) { threadSessions.values.toList() }.forEach { it.attach(conn) }
        val config = conn.call("server.getConfig")
        scope.launch(io) {
            val value = runCatching { config.await() as? JsonObject }.getOrNull() ?: return@launch
            runCatching { learnRoutes(value) }
            runCatching { _server.value = ServerInfo.parse(value) }
        }
        startPings(conn)
    }

    private fun subscribeShell(conn: RpcConnection, attempt: Int = 0) {
        val payload = buildJsonObject {
            synchronized(shellLock) {
                shell.resetLive()
                if (shell.hasCursor) put("afterSequence", shell.sequence)
            }
            put("requestCompletionMarker", true)
        }
        val start = Timeline.nowMs()
        var first = true
        conn.subscribe("orchestration.subscribeShell", payload, onEnd = { error ->
            // The server ends the stream when its live buffer overflows; resume from the cursor. If it
            // keeps failing, drop the socket and let the backoff reconnect.
            if (error != null && !conn.isClosed) {
                Timeline.mark("$trace.shell.stream.ended", error.message?.take(120))
                if (attempt < 2) subscribeShell(conn, attempt + 1) else conn.close()
            }
        }) { values ->
            if (first) {
                first = false
                Timeline.span("$trace.shell.first", start)
            }
            onShellValues(values, start)
        }
    }

    private fun onShellValues(values: JsonArray, subscribedAt: Double) {
        var changed = false
        var live = false
        synchronized(shellLock) {
            for (v in values) {
                val o = v as? JsonObject ?: continue
                if (o.str("kind") == "synchronized") live = true
                changed = shell.apply(o) || changed
            }
            if (changed) _threads.value = shell.model()
        }
        if (live) {
            Timeline.span("$trace.shell.live", subscribedAt, "${_threads.value.threadCount} threads")
            saveShell(0)
        } else if (changed) {
            saveShell(SHELL_SAVE_DEBOUNCE_MS)
        }
    }

    private fun saveShell(delayMs: Long) {
        synchronized(shellLock) {
            if (shellSaveJob?.isActive == true && delayMs > 0) return
            shellSaveJob?.cancel()
            shellSaveJob = scope.launch(io) {
                if (delayMs > 0) delay(delayMs)
                val write = synchronized(shellLock) { shell.takeWrite() ?: shell.cursorWrite() }
                runCatching { cache.saveShell(id, write) }
            }
        }
    }

    private fun learnRoutes(config: JsonObject?) {
        config ?: return
        val reported = config.arr("directEndpoints")?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.str("httpBaseUrl") ?: return@mapNotNull null
            DirectRoute(o.str("kind") ?: "lan", url)
        } ?: return
        if (reported != _env.value.directRoutes) {
            Timeline.mark("$trace.routes.learned", reported.joinToString { it.kind })
            updateEnv { it.copy(directRoutes = reported) }
        }
    }

    private fun updateEnv(change: (SavedEnvironment) -> SavedEnvironment) {
        val next = change(_env.value)
        _env.value = next
        scope.launch(io) { runCatching { cache.saveEnvironment(next) } }
    }

    /** Tunnels drop idle sockets, so a relay tunnel gets a ping while the app is in front. Direct routes never ping. */
    private fun startPings(conn: RpcConnection) {
        if (route?.label != "tunnel" || !foreground) return
        pingJob?.cancel()
        pingJob = scope.launch(io) {
            while (isActive && !conn.isClosed && foreground) {
                delay(RELAY_PING_INTERVAL_MS)
                if (!conn.ping(PING_TIMEOUT_MS)) {
                    Timeline.mark("$trace.ping.lost")
                    conn.close()
                }
            }
        }
    }

    private fun ticketKey(envId: String) = "ticket.$envId"

    companion object {
        const val RETAINED_THREADS = 4
        const val PROBE_TIMEOUT_MS = 3_000L
        const val PING_TIMEOUT_MS = 10_000L
        const val RELAY_PING_INTERVAL_MS = 60_000L
        const val TICKET_MARGIN_MS = 20_000L
        const val SHELL_SAVE_DEBOUNCE_MS = 2_000L
        const val BACKOFF_RESET_MS = 30_000L

        /** protocol.md 10.8: ceiling `min(300 s, 2^(n+1) s)`, delay in its upper half. */
        fun backoff(failures: Int, random: Random = Random.Default): Long {
            val ceiling = minOf(300_000.0, 1000.0 * Math.pow(2.0, (failures + 1).toDouble()))
            return (ceiling / 2 + ceiling / 2 * random.nextDouble()).toLong()
        }

        /** Text for the connection row. Never includes a token or ticket. */
        fun describe(e: Throwable): String = when (e) {
            is Blocked -> e.message.orEmpty()
            is RelayError -> e.message.orEmpty()
            is IncompatibleEnvironment, is EnvironmentMismatch -> e.message.orEmpty()
            is WebSocketRejected -> when (e.status) {
                426 -> "This computer runs a T3 Code version this app does not support."
                401 -> "The computer refused the connection ticket."
                else -> e.message.orEmpty()
            }
            is HttpStatusError -> when (e.status) {
                401 -> "The computer refused this phone's credential."
                else -> "The computer answered HTTP ${e.status}${e.reason?.let { " ($it)" } ?: ""}."
            }
            is UnknownHostException -> "Can't find the computer's address. Check the network."
            is ConnectException, is SocketTimeoutException -> "Can't reach the computer. Check that it is on and on this network."
            is IOException -> e.message ?: "Network error."
            else -> e.message ?: e::class.simpleName.orEmpty()
        }
    }
}
