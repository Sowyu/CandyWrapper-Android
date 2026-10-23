package org.anikodev.candywrapper.core.sync

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import org.anikodev.candywrapper.core.auth.AccessToken
import org.anikodev.candywrapper.core.connect.ConnectAuthorizer
import org.anikodev.candywrapper.core.connect.EnvironmentToken
import org.anikodev.candywrapper.core.connect.RelayEndpoint
import org.anikodev.candywrapper.core.connect.SignedOut
import org.anikodev.candywrapper.core.model.SavedEnvironment
import org.anikodev.candywrapper.core.store.SecretStore
import org.anikodev.candywrapper.core.store.getJson
import org.anikodev.candywrapper.core.store.putJson

/** A credential ready for one connect attempt. */
class Authorization(
    val token: String,
    /** After this the server closes sockets opened with it, so tickets from it stop being worth reusing. */
    val usableUntilMs: Long,
    /** The tunnel endpoint for T3 Connect; null for a paired environment. */
    val endpoint: RelayEndpoint?,
    private val sign: (String, HttpUrl) -> Map<String, String>,
) {
    /** Headers for one request; T3 Connect makes a fresh DPoP proof per call. */
    fun headers(method: String, url: HttpUrl) = sign(method, url)
}

enum class BlockReason { PAIR_AGAIN, SIGN_IN, UPDATE, HOST }

/** No retry helps until the user acts. The session waits for [EnvironmentSession.wake]. */
class Blocked(val reason: BlockReason, message: String) : Exception(message)

/** How one kind of environment authorizes. */
interface Credentials {
    /** `connectionMethod` on the socket URL. */
    val connectionMethod: String
    suspend fun authorize(env: SavedEnvironment, rejected: String?, tracer: Tracer): Authorization
    /** When the stored credential stops being usable, without any network. 0 when there is none. */
    fun cachedUntil(env: SavedEnvironment): Long
}

@Serializable
data class StoredBearer(val token: String, val expiresAtMs: Long)

/** Paired environments: the 30-day bearer token from pairing. There is no refresh; renew by pairing again. */
class BearerCredentials(private val secrets: SecretStore) : Credentials {
    override val connectionMethod = "direct"

    fun save(envId: String, token: AccessToken) =
        secrets.putJson(key(envId), StoredBearer.serializer(), StoredBearer(token.token, token.expiresAtMs))

    fun forget(envId: String) = secrets.put(key(envId), null)

    override fun cachedUntil(env: SavedEnvironment) = stored(env.id)?.expiresAtMs ?: 0

    override suspend fun authorize(env: SavedEnvironment, rejected: String?, tracer: Tracer): Authorization {
        val bearer = stored(env.id) ?: throw Blocked(BlockReason.PAIR_AGAIN, "Pair this computer again with `npx t3 pair`.")
        if (bearer.token == rejected) {
            forget(env.id)
            throw Blocked(BlockReason.PAIR_AGAIN, "The computer no longer accepts this phone. Pair it again with `npx t3 pair`.")
        }
        if (bearer.expiresAtMs < System.currentTimeMillis()) {
            throw Blocked(BlockReason.PAIR_AGAIN, "This phone's access expired. Pair it again with `npx t3 pair`.")
        }
        return Authorization(bearer.token, bearer.expiresAtMs, null) { _, _ -> mapOf("Authorization" to "Bearer ${bearer.token}") }
    }

    private fun stored(envId: String) = secrets.getJson(key(envId), StoredBearer.serializer())
    private fun key(envId: String) = "bearer.$envId"
}

/** T3 Connect environments: a one-hour DPoP token from the relay sequence, cached and persisted. */
class ConnectCredentials(private val authorizer: ConnectAuthorizer) : Credentials {
    // A learned direct route still says `relay` (authorization/service.ts:217-245).
    override val connectionMethod = "relay"

    override fun cachedUntil(env: SavedEnvironment) =
        authorizer.cached(env.id)?.let { it.expiresAtMs - EnvironmentToken.REFRESH_SKEW_MS } ?: 0

    override suspend fun authorize(env: SavedEnvironment, rejected: String?, tracer: Tracer): Authorization {
        val token = try {
            authorizer.cached(env.id)?.takeIf { it.token != rejected }
                ?: tracer.step("authorize.relay") { authorizer.token(env.id, RelayEndpoint(env.httpBase, env.wsBase), rejected, tracer) }
        } catch (e: SignedOut) {
            throw Blocked(BlockReason.SIGN_IN, "Sign in to T3 Connect to reach this computer.")
        }
        return Authorization(token.token, token.expiresAtMs - EnvironmentToken.REFRESH_SKEW_MS, token.endpoint) { method, url ->
            authorizer.headers(token, method, url)
        }
    }
}
