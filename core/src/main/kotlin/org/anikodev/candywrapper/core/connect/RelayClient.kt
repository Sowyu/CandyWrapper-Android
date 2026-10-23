package org.anikodev.candywrapper.core.connect

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.HttpStatusError
import org.anikodev.candywrapper.core.http.formBody
import org.anikodev.candywrapper.core.http.json
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.store.SecretStore
import org.anikodev.candywrapper.core.store.getJson
import org.anikodev.candywrapper.core.store.putJson

/** Where a linked environment answers: `{httpBaseUrl, wsBaseUrl, providerKind}` (protocol.md 10.3). */
@Serializable
data class RelayEndpoint(val httpBaseUrl: String, val wsBaseUrl: String, val providerKind: String = "manual")

data class RelayEnvironment(val environmentId: String, val label: String, val endpoint: RelayEndpoint)

data class RelayConnectResult(val environmentId: String, val endpoint: RelayEndpoint, val credential: String)

/** A relay DPoP access token: 30 minutes, bound to our key. Persisted so a cold start skips the Clerk mint and exchange. */
@Serializable
data class RelayAccessToken(val accountId: String, val scopes: List<String>, val token: String, val expiresAtMs: Long, val thumbprint: String)

/**
 * The T3 Connect relay's client API (protocol.md 10.3), ported from CandyWrapper's RelayClient.swift.
 * Relay access tokens are kept per account and key, reused for any request whose scopes they cover
 * until 5 s before expiry, and dropped and re-exchanged once when the relay refuses one.
 */
class RelayClient(
    val key: DPoPKey,
    private val tokens: CloudTokens,
    private val store: SecretStore,
    val origin: String = PRODUCTION_URL,
    private val http: OkHttpClient = Http.client,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var cache: MutableList<RelayAccessToken>? = null
    private val pending = HashMap<String, CompletableDeferred<RelayAccessToken>>()

    /** `GET /v1/environments` with the Clerk JWT as Bearer. */
    suspend fun listEnvironments(): List<RelayEnvironment> {
        val request = Request.Builder().url("$origin/v1/environments")
            .header("Authorization", "Bearer ${tokens.relayJwt()}")
            .header("Accept", "application/json").build()
        val o = perform(request)
        return o.arr("environments").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { e ->
            val endpoint = e.obj("endpoint") ?: return@mapNotNull null
            RelayEnvironment(
                e.str("environmentId") ?: return@mapNotNull null,
                e.str("label") ?: "T3 Code",
                RelayEndpoint(endpoint.str("httpBaseUrl") ?: return@mapNotNull null, endpoint.str("wsBaseUrl") ?: return@mapNotNull null,
                    endpoint.str("providerKind") ?: "manual"),
            )
        }
    }

    /**
     * `POST /v1/environments/:id/connect`: a two-minute, single-use environment credential bound to
     * our key. Redeem it at once with the same key.
     */
    suspend fun connect(environmentId: String): RelayConnectResult {
        val body = buildJsonObject { put("clientKeyThumbprint", key.thumbprint) }
        val o = dpopRequest("/v1/environments/${seg(environmentId)}/connect", body, listOf(SCOPE_CONNECT))
        val endpoint = o.obj("endpoint") ?: throw IOException("The relay answer has no endpoint.")
        return RelayConnectResult(
            o.str("environmentId") ?: throw IOException("The relay answer has no environmentId."),
            RelayEndpoint(endpoint.str("httpBaseUrl")!!, endpoint.str("wsBaseUrl")!!, endpoint.str("providerKind") ?: "manual"),
            o.str("credential") ?: throw IOException("The relay answer has no credential."),
        )
    }

    /** True when a relay token for [scopes] is cached, so [connect] needs no Clerk call first. */
    suspend fun hasToken(scopes: List<String> = listOf(SCOPE_CONNECT)): Boolean = lock.withLock { cached(scopes) != null }

    /** Forgets every relay token (sign-out, account switch). */
    suspend fun reset() = lock.withLock {
        cache = mutableListOf()
        pending.clear()
        save()
    }

    private suspend fun dpopRequest(path: String, body: JsonObject, scopes: List<String>): JsonObject {
        var retried = false
        while (true) {
            val token = accessToken(scopes)
            val url = "$origin$path"
            val request = Request.Builder().url(url)
                .header("Authorization", "DPoP ${token.token}")
                .header("DPoP", DPoP.proof("POST", url, key, token.token))
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                return perform(request)
            } catch (e: RelayError) {
                if (retried || !e.rejectedAccessToken) throw e
                retried = true
                lock.withLock { cache?.removeAll { it.token == token.token }; save() }
            }
        }
    }

    private suspend fun accessToken(scopes: List<String>): RelayAccessToken {
        val (shared, owner) = lock.withLock {
            cached(scopes)?.let { return it }
            val k = scopes.sorted().joinToString(" ")
            pending[k]?.let { return@withLock it to false }
            CompletableDeferred<RelayAccessToken>().also { pending[k] = it } to true
        }
        if (!owner) return shared.await()
        try {
            val token = exchange(scopes)
            lock.withLock {
                val list = load()
                list.removeAll { it.accountId == token.accountId && it.scopes.toSet() == token.scopes.toSet() }
                list += token
                save()
            }
            shared.complete(token)
            return token
        } catch (e: Throwable) {
            shared.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { pending.remove(scopes.sorted().joinToString(" ")) }
        }
    }

    /** `POST /v1/client/dpop-token`: Clerk `t3-relay` JWT to a 30-minute DPoP relay token. */
    private suspend fun exchange(scopes: List<String>): RelayAccessToken {
        val jwt = tokens.relayJwt()
        val url = "$origin/v1/client/dpop-token"
        val request = Request.Builder().url(url)
            .header("DPoP", DPoP.proof("POST", url, key))
            .header("Accept", "application/json")
            .post(formBody(
                "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token" to jwt,
                "subject_token_type" to "urn:ietf:params:oauth:token-type:jwt",
                "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token",
                "resource" to origin,
                "scope" to scopes.joinToString(" "),
                "client_id" to CLIENT_ID,
            ))
            .build()
        val issuedAt = nowMs()
        val o = perform(request)
        val granted = o.str("scope").orEmpty().split(' ').filter { it.isNotEmpty() }
        if (granted.toSet() != scopes.toSet()) throw RelayError(200, null, null, "The relay granted unexpected scopes.")
        return RelayAccessToken(jwtSubject(jwt) ?: tokens.accountId().orEmpty(), scopes,
            o.str("access_token") ?: throw IOException("The relay answer has no access_token."),
            issuedAt + (o.long("expires_in") ?: 1800) * 1000, key.thumbprint)
    }

    private fun cached(scopes: List<String>): RelayAccessToken? {
        val account = tokens.accountId() ?: return null
        val list = load()
        list.removeAll { it.expiresAtMs - nowMs() <= CACHE_SLACK_MS }
        return list.firstOrNull { it.accountId == account && it.thumbprint == key.thumbprint && it.scopes.containsAll(scopes) }
    }

    private fun load(): MutableList<RelayAccessToken> =
        cache ?: (store.getJson(STORE_KEY, ListSerializer(RelayAccessToken.serializer()))?.toMutableList() ?: mutableListOf()).also { cache = it }

    private fun save() = store.putJson(STORE_KEY, ListSerializer(RelayAccessToken.serializer()), cache.orEmpty())

    private suspend fun perform(request: Request): JsonObject = try {
        http.json(request)
    } catch (e: HttpStatusError) {
        throw RelayError(e.status, e.tag, e.reason, relayMessage(e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw RelayError(null, null, null, "Can't reach T3 Connect. Check your connection. (${e.message ?: e::class.simpleName})")
    }

    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val PRODUCTION_URL = "https://relay.t3.codes"
        /** Web and desktop clients present as `t3-web`; CandyWrapper does the same, as approved for the macOS app. */
        const val CLIENT_ID = "t3-web"
        const val SCOPE_CONNECT = "environment:connect"
        const val STORE_KEY = "relay.tokens"
        private const val CACHE_SLACK_MS = 5_000L
        private val JSON = "application/json".toMediaType()
    }
}

/** A relay failure with T3's presentation text (protocol.md 10.9). */
class RelayError(val status: Int?, val tag: String?, val reason: String?, message: String) : IOException(message) {
    /** The relay refused our access token, so a fresh exchange may work. */
    val rejectedAccessToken: Boolean
        get() = status == 401 && (tag == null || (tag == "RelayAuthInvalidError" && reason in setOf("invalid_bearer", "invalid_dpop", "missing_bearer")))

    /** No retry helps until the user acts (sign in again, relink the host). */
    val blocked: Boolean get() = status == 401 || status == 403 || status == 400
}

private fun relayMessage(e: HttpStatusError): String = when {
    e.reason == "environment_link_not_found" ->
        "This computer is no longer linked to T3 Connect. Open T3 Code on it to link it again."
    e.tag == "RelayEnvironmentConnectNotAuthorizedError" -> "T3 Connect refused this connection (${e.reason ?: "not authorized"})."
    e.status == 401 && e.dpopFailureReason == "time_window" -> "T3 Connect refused the request. Check that this phone's clock is right."
    e.status == 401 -> "T3 Connect sign-in expired. Sign in again."
    e.status == 502 || e.tag == "RelayEnvironmentEndpointUnavailableError" ->
        "The computer did not answer through T3 Connect. Make sure T3 Code is running on it."
    e.status == 504 -> "The computer took too long to answer through T3 Connect."
    else -> e.serverMessage ?: "T3 Connect failed (HTTP ${e.status})."
}
