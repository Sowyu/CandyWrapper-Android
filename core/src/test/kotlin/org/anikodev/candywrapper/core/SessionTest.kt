package org.anikodev.candywrapper.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.anikodev.candywrapper.core.auth.AccessToken
import org.anikodev.candywrapper.core.connect.Base64Url
import org.anikodev.candywrapper.core.connect.CloudTokens
import org.anikodev.candywrapper.core.connect.ConnectAuthorizer
import org.anikodev.candywrapper.core.connect.DPoP
import org.anikodev.candywrapper.core.connect.RelayClient
import org.anikodev.candywrapper.core.connect.SoftwareDPoPKey
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.SavedEnvironment
import org.anikodev.candywrapper.core.store.MemoryCache
import org.anikodev.candywrapper.core.store.MemorySecretStore
import org.anikodev.candywrapper.core.sync.BearerCredentials
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.sync.Tracer

class SessionTest {
    private val fake = FakeT3()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fake.server.close()
    }

    @Test
    fun pairedConnectPipelinesThenResumesWithTheCachedTicket() = runBlocking {
        val cache = MemoryCache()
        val secrets = MemorySecretStore()
        val bearer = BearerCredentials(secrets).apply { save(fake.envId, AccessToken("tok", "Bearer", Long.MAX_VALUE)) }
        val env = SavedEnvironment(fake.envId, "Mac", EnvironmentKind.PAIRED, fake.base, fake.wsBase)

        val first = EnvironmentSession(env, null, cache, secrets, bearer, scope).also { it.start() }
        withTimeout(10_000) { first.state.first { it is ConnectionState.Live } }
        withTimeout(10_000) { first.threads.first { it.threadCount == 1 } }
        // A plain-http address is verified before the credential goes there; then ticket and socket.
        assertEquals(listOf("GET /.well-known/t3/environment", "POST /api/auth/websocket-ticket", "GET /ws"), fake.paths())
        withTimeout(10_000) { while (fake.frames.none { it.str("_tag") == "Ack" && it.str("requestId") == "1" } || cache.shell(fake.envId) == null) Thread.sleep(5) }
        val requests = fake.frames.filter { it.str("_tag") == "Request" }.map { it.str("tag") }
        assertEquals(listOf("orchestration.subscribeShell", "server.getConfig"), requests.take(2))
        assertNull(fake.frames.first().obj("payload")!!["afterSequence"])
        assertEquals("Hello", first.threads.value.groups.single().threads.single().title)
        first.stop()

        // A second start inside the ticket's lifetime skips the ticket request and resumes the shell.
        fake.requests.clear(); fake.frames.clear()
        val second = EnvironmentSession(env, cache.shell(fake.envId), cache, secrets, bearer, scope)
        assertEquals(1, second.threads.value.threadCount) // drawn from the cache before any network
        second.start()
        withTimeout(10_000) { second.state.first { it is ConnectionState.Live } }
        assertEquals(listOf("GET /.well-known/t3/environment", "GET /ws"), fake.paths())
        withTimeout(10_000) { while (fake.frames.isEmpty()) Thread.sleep(5) }
        assertEquals(7, fake.frames.first().obj("payload")!!.long("afterSequence"))
        assertEquals(1, fake.ticketsMinted)
        second.stop()
    }

    @Test
    fun connectSequenceIsPersistedAndReused() = runBlocking {
        val secrets = MemorySecretStore()
        val key = SoftwareDPoPKey()
        val claims = Base64Url.encode("""{"sub":"user_1","exp":9999999999}""".toByteArray())
        val tokens = object : CloudTokens {
            var mints = 0
            override fun accountId() = "user_1"
            override suspend fun relayJwt(): String { mints++; return "e30.$claims.sig" }
        }
        val authorizer = ConnectAuthorizer(RelayClient(key, tokens, secrets, origin = fake.origin), tokens, secrets)
        val token = authorizer.token(fake.envId, null, null, Tracer("test"))
        assertEquals("env-token", token.token)
        assertEquals(fake.base, token.endpoint.httpBaseUrl)
        val paths = fake.paths()
        assertTrue(paths.containsAll(listOf("POST /v1/client/dpop-token", "POST /v1/environments/${fake.envId}/connect",
            "GET /.well-known/t3/environment", "POST /oauth/token")), paths.toString())
        assertTrue(paths.indexOf("POST /v1/client/dpop-token") < paths.indexOf("POST /v1/environments/${fake.envId}/connect"))

        // The relay exchange carries a proof without ath; connect and the token exchange carry fresh proofs.
        fun claimsOf(path: String) = fake.requests.first { it.url.encodedPath == path }.headers["DPoP"]!!.split('.')[1]
            .let { WireJson.parseToJsonElement(String(Base64Url.decode(it))) as kotlinx.serialization.json.JsonObject }
        assertNull(claimsOf("/v1/client/dpop-token")["ath"])
        assertEquals(DPoP.accessTokenHash("relay-token"), claimsOf("/v1/environments/${fake.envId}/connect").str("ath"))
        assertEquals("${fake.origin}/oauth/token", claimsOf("/oauth/token").str("htu"))
        assertEquals("DPoP relay-token", fake.requests.first { it.url.encodedPath.endsWith("/connect") }.headers["Authorization"])

        // In memory and after a restart: no network, no Clerk mint.
        val count = fake.requests.size
        assertEquals(token, authorizer.token(fake.envId, null, null, Tracer("test")))
        val restarted = ConnectAuthorizer(RelayClient(key, tokens, secrets, origin = fake.origin), tokens, secrets)
        assertNotNull(restarted.cached(fake.envId))
        assertTrue(restarted.relay.hasToken())
        assertEquals(count, fake.requests.size)
        assertEquals(1, tokens.mints)

        // A refused token is replaced through the relay with the cached relay token, without Clerk.
        val renewed = restarted.token(fake.envId, token.endpoint, rejected = token.token, Tracer("test"))
        assertEquals("env-token", renewed.token)
        assertEquals(1, tokens.mints)
        assertTrue(fake.paths().drop(count).none { it == "POST /v1/client/dpop-token" })
    }
}
