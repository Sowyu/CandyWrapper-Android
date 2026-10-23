package org.anikodev.candywrapper.core.connect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Request
import org.anikodev.candywrapper.core.auth.Descriptor
import org.anikodev.candywrapper.core.auth.EnvironmentApi
import org.anikodev.candywrapper.core.auth.EnvironmentMismatch
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.await
import org.anikodev.candywrapper.core.store.SecretStore
import org.anikodev.candywrapper.core.store.getJson
import org.anikodev.candywrapper.core.store.putJson
import org.anikodev.candywrapper.core.sync.Tracer

/** An environment DPoP access token from the T3 Connect sequence. One hour; renewed 60 s before it ends. */
@Serializable
data class EnvironmentToken(
    val environmentId: String,
    val token: String,
    val expiresAtMs: Long,
    val accountId: String,
    val thumbprint: String,
    val endpoint: RelayEndpoint,
) {
    fun usable(nowMs: Long) = nowMs < expiresAtMs - REFRESH_SKEW_MS

    companion object {
        /** DPOP_ACCESS_TOKEN_REFRESH_SKEW_MS (client-runtime/src/connection/model.ts:108). */
        const val REFRESH_SKEW_MS = 60_000L
    }
}

/**
 * The T3 Connect connect sequence (protocol.md 10.4), built to skip and overlap round trips:
 *
 * - The environment token (1 h) and the relay token (30 min) are persisted, so a reopen inside
 *   their lifetime goes straight to the WebSocket ticket.
 * - When a relay connect is needed, the tunnel descriptor GET runs alongside it. That checks the
 *   environment id and leaves a warm TLS connection for `/oauth/token`, the ticket and the socket.
 * - When the relay token is missing too, a request to the relay warms its connection while Clerk
 *   mints the `t3-relay` JWT.
 *
 * Concurrent callers for one environment share one exchange.
 */
class ConnectAuthorizer(
    val relay: RelayClient,
    private val tokens: CloudTokens,
    private val store: SecretStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val pending = HashMap<String, CompletableDeferred<EnvironmentToken>>()
    private val memory = HashMap<String, EnvironmentToken>()

    val key: DPoPKey get() = relay.key

    /** A cached token that is still good for this account and key, without any network. */
    fun cached(environmentId: String): EnvironmentToken? {
        val t = synchronized(memory) { memory[environmentId] } ?: store.getJson(storeKey(environmentId), EnvironmentToken.serializer())
            ?.also { synchronized(memory) { memory[environmentId] = it } }
        val account = tokens.accountId() ?: return null
        return t?.takeIf { it.usable(nowMs()) && it.accountId == account && it.thumbprint == key.thumbprint }
    }

    /**
     * A token that is not due for renewal. [rejected] names a token the environment refused, so it
     * is replaced even if it looks valid. [knownEndpoint] is where the tunnel answered last time.
     */
    suspend fun token(environmentId: String, knownEndpoint: RelayEndpoint?, rejected: String?, tracer: Tracer): EnvironmentToken {
        cached(environmentId)?.takeIf { it.token != rejected }?.let { return it }
        val (shared, owner) = lock.withLock {
            pending[environmentId]?.let { return@withLock it to false }
            CompletableDeferred<EnvironmentToken>().also { pending[environmentId] = it } to true
        }
        if (!owner) return shared.await()
        try {
            val fresh = exchange(environmentId, knownEndpoint, tracer)
            synchronized(memory) { memory[environmentId] = fresh }
            store.putJson(storeKey(environmentId), EnvironmentToken.serializer(), fresh)
            shared.complete(fresh)
            return fresh
        } catch (e: Throwable) {
            shared.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { pending.remove(environmentId) }
        }
    }

    /** `Authorization` and `DPoP` headers for one request to the environment. */
    fun headers(token: EnvironmentToken, method: String, url: okhttp3.HttpUrl): Map<String, String> =
        mapOf("Authorization" to "DPoP ${token.token}", "DPoP" to DPoP.proof(method, url, key, token.token))

    fun forget(environmentId: String) {
        synchronized(memory) { memory.remove(environmentId) }
        store.put(storeKey(environmentId), null)
    }

    suspend fun reset(environmentIds: Collection<String>) {
        environmentIds.forEach(::forget)
        relay.reset()
    }

    private suspend fun exchange(environmentId: String, knownEndpoint: RelayEndpoint?, tracer: Tracer): EnvironmentToken = coroutineScope {
        val account = tokens.accountId() ?: throw SignedOut()
        // Without a relay token Clerk mints the JWT first; meanwhile open the relay connection the exchange will use.
        val warmRelay = if (relay.hasToken()) null else launch { warm("${relay.origin}/health") }
        val warmDescriptor = knownEndpoint?.let { e -> async { runCatching { EnvironmentApi.descriptor(e.httpBaseUrl) } } }
        val bootstrap = tracer.step("relay.connect") { relay.connect(environmentId) }
        if (bootstrap.environmentId != environmentId) throw EnvironmentMismatch(environmentId, bootstrap.environmentId)
        val descriptor = if (warmDescriptor != null && bootstrap.endpoint.httpBaseUrl == knownEndpoint.httpBaseUrl) {
            warmDescriptor
        } else {
            warmDescriptor?.cancel()
            async { runCatching { EnvironmentApi.descriptor(bootstrap.endpoint.httpBaseUrl) } }
        }
        // The credential lives two minutes and works once: redeem it now, with the same key. It is
        // bound to our key, so sending it before the descriptor check leaks nothing usable.
        val oauthUrl = EnvironmentApi.url(bootstrap.endpoint.httpBaseUrl, "/oauth/token")
        val issuedAt = nowMs()
        val access = tracer.step("oauth.token") {
            EnvironmentApi.exchange(bootstrap.endpoint.httpBaseUrl, bootstrap.credential, DPoP.proof("POST", oauthUrl, key), issuedAt)
        }
        val d: Descriptor = tracer.step("descriptor") { descriptor.await().getOrThrow() }
        if (d.environmentId != environmentId) throw EnvironmentMismatch(environmentId, d.environmentId)
        warmRelay?.cancel()
        EnvironmentToken(environmentId, access.token, access.expiresAtMs, account, key.thumbprint, bootstrap.endpoint)
    }

    private suspend fun warm(url: String) {
        runCatching { Http.client.newCall(Request.Builder().url(url).build()).await().close() }
    }

    private fun storeKey(environmentId: String) = "connect.token.$environmentId"
}
