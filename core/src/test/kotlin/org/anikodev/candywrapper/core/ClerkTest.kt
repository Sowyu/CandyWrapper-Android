package org.anikodev.candywrapper.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.anikodev.candywrapper.core.connect.Base64Url
import org.anikodev.candywrapper.core.connect.ClerkClient
import org.anikodev.candywrapper.core.connect.ClerkError
import org.anikodev.candywrapper.core.connect.ClerkSignInStep
import org.anikodev.candywrapper.core.connect.SignedOut
import org.anikodev.candywrapper.core.store.MemorySecretStore

/** The email-code and password flows against a fake Clerk Frontend API shaped like clerk-ios expects. */
class ClerkTest {
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var tokenStatus = 200
    private val server = MockWebServer().also {
        it.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val signIn = """{"id":"sia_1","status":"%s","identifier":"a@b.c","created_session_id":%s,
                    "supported_first_factors":[{"strategy":"email_code","safe_identifier":"a@b.c","email_address_id":"idn_1"}],
                    "supported_second_factors":[{"strategy":"email_code","safe_identifier":"a***@b.c","email_address_id":"idn_1"}]}"""
                val client = """{"id":"client_1","sessions":[{"id":"sess_1","status":"active","user":{"id":"user_1"},"public_user_data":{"identifier":"a@b.c"}}]}"""
                fun ok(body: String) = MockResponse.Builder().addHeader("Authorization", "client-token").body(body).build()
                val form = request.body?.utf8().orEmpty()
                return when (request.url.encodedPath) {
                    "/v1/environment" -> ok("""{"auth_config":{"single_session_mode":true,"native_settings":{"api_enabled":true}},
                        "user_settings":{"attributes":{"email_address":{"enabled":true,"used_for_first_factor":true,"first_factors":["email_code"]},"password":{"enabled":true}}}}""")
                    "/v1/client/sign_ins" -> if (form.contains("password=")) ok("""{"response":${signIn.format("needs_client_trust", "null")},"client":$client}""")
                        else ok("""{"response":${signIn.format("needs_first_factor", "null")},"client":$client}""")
                    "/v1/client/sign_ins/sia_1/prepare_first_factor", "/v1/client/sign_ins/sia_1/prepare_second_factor" ->
                        ok("""{"response":${signIn.format("needs_first_factor", "null")},"client":$client}""")
                    "/v1/client/sign_ins/sia_1/attempt_first_factor", "/v1/client/sign_ins/sia_1/attempt_second_factor" ->
                        if (form.contains("code=000000")) MockResponse.Builder().code(422).body("""{"errors":[{"code":"form_code_incorrect","message":"Incorrect code"}]}""").build()
                        else ok("""{"response":${signIn.format("complete", "\"sess_1\"")},"client":$client}""")
                    "/v1/client/sessions/sess_1/tokens/t3-relay" -> if (tokenStatus != 200) {
                        MockResponse.Builder().code(tokenStatus).body("""{"errors":[{"code":"resource_not_found"}]}""").build()
                    } else {
                        ok("""{"jwt":"e30.${Base64Url.encode("""{"sub":"user_1","exp":9999999999}""".toByteArray())}.sig"}""")
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        it.start()
    }

    @AfterTest
    fun close() = server.close()

    private fun client(store: MemorySecretStore) =
        ClerkClient(store, ClerkClient.Device("16", "0.1.0", "org.anikodev.candywrapper"), baseUrl = server.url("/").toString().removeSuffix("/"))

    @Test
    fun emailCodeSignInThenMintsAndCachesTheRelayJwt() = runBlocking {
        val store = MemorySecretStore()
        val clerk = client(store)
        val challenge = clerk.startEmailCode(" a@b.c ")
        assertEquals("idn_1", challenge.emailAddressId)
        val prepare = requests.first { it.url.encodedPath.endsWith("prepare_first_factor") }
        assertEquals("email_address_id=idn_1&strategy=email_code", prepare.body!!.utf8())
        assertEquals("client-token", prepare.headers["Authorization"])
        assertEquals("true", prepare.url.queryParameter("_is_native"))

        assertIs<ClerkError>(assertFailsWith<ClerkError> { clerk.verifyCode("000000", challenge) })
        val account = clerk.verifyCode("123456", challenge)
        assertEquals("sess_1", account.sessionId)
        assertEquals("user_1", clerk.accountId())

        val jwt = clerk.relayJwt()
        assertEquals(jwt, clerk.relayJwt())
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("/tokens/t3-relay") })
        // The session survives a restart.
        assertEquals("sess_1", client(store).account()?.sessionId)
    }

    @Test
    fun passwordOnANewDeviceAsksForACodeAndAGoneSessionSignsOut() = runBlocking {
        val clerk = client(MemorySecretStore())
        val step = clerk.signIn("a@b.c", "pw")
        val challenge = assertIs<ClerkSignInStep.NeedsCode>(step).challenge
        assertEquals(true, challenge.secondFactor)
        assertEquals("sess_1", clerk.verifyCode("123456", challenge).sessionId)
        tokenStatus = 404
        assertFailsWith<SignedOut> { clerk.relayJwt() }
        assertEquals(null, clerk.account())
    }
}
