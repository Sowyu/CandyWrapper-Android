package org.anikodev.candywrapper.core.connect

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.await
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.store.SecretStore
import org.anikodev.candywrapper.core.store.getJson
import org.anikodev.candywrapper.core.store.putJson

/** What Clerk needs to keep this device signed in. Stored encrypted; the session JWTs stay in memory. */
@Serializable
data class ClerkStoredSession(
    val clientToken: String,
    val clientId: String? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val identifier: String? = null,
)

data class ClerkAccount(val sessionId: String, val userId: String?, val identifier: String?)

/** An emailed code. Pass it back to [ClerkClient.verifyCode]. */
data class ClerkCodeChallenge(val signInId: String, val secondFactor: Boolean, val emailAddressId: String?, val sentTo: String?, val attempt: Int)

sealed interface ClerkSignInStep {
    data class Complete(val account: ClerkAccount) : ClerkSignInStep
    data class NeedsCode(val challenge: ClerkCodeChallenge) : ClerkSignInStep
}

/** A failed Clerk call with text the sign-in screen shows as is. Never holds a token, code or password. */
class ClerkError(val code: String?, message: String, val signedOut: Boolean = false) : IOException(message)

class SignedOut : IOException("Sign in to T3 Connect.")

/** Supplies `t3-relay` session JWTs and the account they belong to. [ClerkClient] in the app, a fake in tests. */
interface CloudTokens {
    /** The Clerk user id, known without a network call when signed in. Null when signed out. */
    fun accountId(): String?
    suspend fun relayJwt(): String
}

/**
 * A native Clerk Frontend API client for T3 Connect, ported from CandyWrapper's ClerkClient.swift
 * (which follows clerk-ios). Native mode: every URL carries `_is_native=true`, the client token
 * travels raw in `Authorization`, and no cookie is read or sent. Sign-in is an email code or a
 * password; Clerk may follow a password with an emailed code on a new device.
 *
 * Unverified on Android: the header set is the one CandyWrapper's macOS build uses against
 * clerk.t3.codes. T3's Clerk instance may change its native settings at any time.
 */
class ClerkClient(
    private val store: SecretStore,
    private val device: Device,
    private val baseUrl: String = PRODUCTION_URL,
    private val http: OkHttpClient = Http.client,
    private val locale: String = Locale.getDefault().toLanguageTag(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CloudTokens {
    data class Device(val osVersion: String, val appVersion: String, val bundleId: String, val model: String = "")

    private val lock = Mutex()
    @Volatile private var loaded = false
    @Volatile private var stored: ClerkStoredSession? = null
    private var cachedJwt: Pair<String, Long>? = null
    private var pendingMint: CompletableDeferred<String>? = null
    private var environment: JsonObject? = null
    private var attempt = 0
    /** Bumped on sign-in and sign-out, so a late answer can't touch a newer session. */
    private var generation = 0

    fun account(): ClerkAccount? = session()?.let { s -> s.sessionId?.let { ClerkAccount(it, s.userId, s.identifier) } }

    override fun accountId(): String? = session()?.takeIf { it.sessionId != null }?.let { it.userId ?: it.sessionId }

    // Sign-in

    /** Creates a sign-in and emails a code. */
    suspend fun startEmailCode(email: String): ClerkCodeChallenge {
        val owner = newAttempt()
        beginSignIn(owner) { it.emailCode }
        val (signIn, _) = call("POST", "/v1/client/sign_ins", mapOf("identifier" to email.trim(), "locale" to locale), owner).signIn()
        val factor = emailFactor(signIn, "supported_first_factors", fallbackToFirst = true)
            ?: throw ClerkError(null, "This account can't sign in with an email code.")
        val challenge = ClerkCodeChallenge(signIn.str("id")!!, false, factor.str("email_address_id"), factor.str("safe_identifier"), owner)
        resendCode(challenge)
        return challenge
    }

    suspend fun resendCode(challenge: ClerkCodeChallenge) {
        val step = if (challenge.secondFactor) "prepare_second_factor" else "prepare_first_factor"
        val fields = buildMap {
            put("strategy", "email_code")
            challenge.emailAddressId?.let { put("email_address_id", it) }
        }
        call("POST", "/v1/client/sign_ins/${seg(challenge.signInId)}/$step", fields, challenge.attempt)
    }

    suspend fun verifyCode(code: String, challenge: ClerkCodeChallenge): ClerkAccount {
        val step = if (challenge.secondFactor) "attempt_second_factor" else "attempt_first_factor"
        val (signIn, client) = call("POST", "/v1/client/sign_ins/${seg(challenge.signInId)}/$step",
            mapOf("code" to code.trim(), "strategy" to "email_code"), challenge.attempt).signIn()
        val next = advance(signIn, client, challenge.attempt)
        return (next as? ClerkSignInStep.Complete)?.account
            ?: throw ClerkError(null, "This account needs a sign-in step CandyWrapper does not support yet.")
    }

    suspend fun signIn(email: String, password: String): ClerkSignInStep {
        val owner = newAttempt()
        beginSignIn(owner) { it.password }
        val (signIn, client) = call("POST", "/v1/client/sign_ins",
            mapOf("identifier" to email.trim(), "locale" to locale, "password" to password), owner).signIn()
        if (signIn.str("status") == "needs_first_factor") {
            val (next, nextClient) = call("POST", "/v1/client/sign_ins/${seg(signIn.str("id")!!)}/attempt_first_factor",
                mapOf("password" to password, "strategy" to "password"), owner).signIn()
            return advance(next, nextClient, owner)
        }
        return advance(signIn, client, owner)
    }

    /** Ends the sign-in in progress. Its late answers throw CancellationException. */
    fun cancelSignIn() {
        attempt++
    }

    /** Removes the session on Clerk, best effort, and forgets it here. */
    suspend fun signOut() {
        val sessionId = session()?.sessionId
        generation++
        val started = generation
        if (sessionId != null) runCatching { call("POST", "/v1/client/sessions/${seg(sessionId)}/remove") }
        if (generation == started) clearLocal()
    }

    // Tokens

    /**
     * A `t3-relay` JWT from `POST /v1/client/sessions/{id}/tokens/t3-relay`, reused until 10 s
     * before its `exp`. Concurrent callers share one mint.
     */
    override suspend fun relayJwt(): String {
        val sessionId = session()?.sessionId ?: throw SignedOut()
        val (shared, owner) = lock.withLock {
            cachedJwt?.let { (jwt, exp) -> if (exp - nowMs() > JWT_MARGIN_MS) return jwt }
            pendingMint?.let { return@withLock it to false }
            CompletableDeferred<String>().also { pendingMint = it } to true
        }
        if (!owner) return shared.await()
        try {
            val jwt = mint(sessionId)
            shared.complete(jwt)
            return jwt
        } catch (e: Throwable) {
            shared.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { if (pendingMint === shared) pendingMint = null }
        }
    }

    private suspend fun mint(sessionId: String): String {
        val started = generation
        val body = try {
            call("POST", "/v1/client/sessions/${seg(sessionId)}/tokens/$TEMPLATE")
        } catch (e: ClerkError) {
            if (e.signedOut || e.code == "resource_not_found") {
                if (generation == started) clearLocal()
                throw SignedOut()
            }
            throw e
        }
        val jwt = (runCatching { WireJson.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.str("jwt")
        if (jwt.isNullOrEmpty()) {
            if (generation == started) clearLocal()
            throw SignedOut()
        }
        jwtExpirySeconds(jwt)?.let { if (generation == started) cachedJwt = jwt to it * 1000 }
        return jwt
    }

    // Sign-in steps

    private class Strategies(val emailCode: Boolean, val password: Boolean)

    private fun newAttempt(): Int = ++attempt

    private fun own(owner: Int) {
        if (owner != attempt) throw CancellationException("A newer sign-in replaced this one.")
    }

    private suspend fun beginSignIn(owner: Int, strategy: (Strategies) -> Boolean) {
        val env = environment ?: WireJson.parseToJsonElement(call("GET", "/v1/environment")).let { it as JsonObject }.also { environment = it }
        own(owner)
        val auth = env.obj("auth_config")
        val native = auth?.obj("native_settings")?.bool("api_enabled") == true
        val attributes = env.obj("user_settings")?.obj("attributes")
        val email = attributes?.obj("email_address")
        val strategies = Strategies(
            emailCode = email?.bool("enabled") == true && email.bool("used_for_first_factor") == true &&
                email.arr("first_factors")?.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "email_code" } == true,
            password = attributes?.obj("password")?.bool("enabled") == true,
        )
        if (!native || !strategy(strategies)) throw ClerkError(null, "This sign-in method is not available for T3 Connect.")
        // single_session_mode refuses a second sign-in on a client that has one (`session_exists`).
        if (auth?.bool("single_session_mode") == true && session()?.sessionId != null) signOut()
        own(owner)
    }

    private suspend fun advance(signIn: JsonObject, client: JsonObject?, owner: Int): ClerkSignInStep {
        when (signIn.str("status")) {
            "complete" -> return ClerkSignInStep.Complete(finish(signIn, client, owner))
            "needs_second_factor", "needs_client_trust" -> {
                val factors = signIn.arr("supported_second_factors")?.mapNotNull { it as? JsonObject }.orEmpty()
                if (factors.any { it.str("strategy") == "email_code" }) {
                    val factor = emailFactor(signIn, "supported_second_factors", fallbackToFirst = false)
                    val challenge = ClerkCodeChallenge(signIn.str("id")!!, true, factor?.str("email_address_id"),
                        (factor ?: factors.first { it.str("strategy") == "email_code" }).str("safe_identifier"), owner)
                    resendCode(challenge)
                    return ClerkSignInStep.NeedsCode(challenge)
                }
            }
        }
        throw ClerkError(null, "This account needs a sign-in step CandyWrapper does not support yet.")
    }

    private fun finish(signIn: JsonObject, client: JsonObject?, owner: Int): ClerkAccount {
        own(owner)
        val sessionId = signIn.str("created_session_id") ?: throw ClerkError(null, "T3 Connect sign-in sent an unexpected response.")
        val current = session() ?: throw ClerkError(null, "T3 Connect sign-in sent an unexpected response.")
        val clerkSession = client?.arr("sessions")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("id") == sessionId }
        val userId = clerkSession?.obj("user")?.str("id") ?: clerkSession?.obj("public_user_data")?.str("user_id")
        val identifier = clerkSession?.obj("public_user_data")?.str("identifier") ?: signIn.str("identifier")
        generation++
        cachedJwt = null
        persist(current.copy(sessionId = sessionId, userId = userId, identifier = identifier))
        return ClerkAccount(sessionId, userId, identifier)
    }

    /** The email factor whose `safe_identifier` matches the sign-in identifier, else the first with the strategy. */
    private fun emailFactor(signIn: JsonObject, key: String, fallbackToFirst: Boolean): JsonObject? {
        val matching = signIn.arr(key)?.mapNotNull { it as? JsonObject }?.filter { it.str("strategy") == "email_code" }.orEmpty()
        return matching.firstOrNull { it.str("safe_identifier") == signIn.str("identifier") } ?: if (fallbackToFirst) matching.firstOrNull() else null
    }

    private fun String.signIn(): Pair<JsonObject, JsonObject?> {
        val o = runCatching { WireJson.parseToJsonElement(this) as JsonObject }.getOrNull()
        val response = o?.obj("response") ?: throw ClerkError(null, "T3 Connect sign-in sent an unexpected response.")
        if (response.str("id") == null) throw ClerkError(null, "T3 Connect sign-in sent an unexpected response.")
        return response to o.obj("client")
    }

    // HTTP

    /**
     * One Frontend API request with the SDK's query item and headers. Applies a response
     * `Authorization` header to the stored client token, retries once on 408, 425 and 429 when
     * `Retry-After` is at most 5 s, and maps errors. With [owner], an answer for a replaced sign-in
     * attempt throws CancellationException.
     */
    private suspend fun call(method: String, path: String, fields: Map<String, String>? = null, owner: Int? = null): String {
        val started = generation
        var retried = false
        while (true) {
            val response = try {
                http.newCall(request(method, path, fields)).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ClerkError(null, "Can't reach T3 Connect sign-in. Check your connection and try again.")
            }
            val body = response.use { it.body.string() }
            val current = generation == started && (owner == null || owner == attempt)
            if (current) applyClientToken(response.header("Authorization"), body)
            if (owner != null && !current) throw CancellationException("A newer sign-in replaced this one.")
            if (response.isSuccessful) return body
            val retryAfter = response.header("Retry-After")?.trim()?.toDoubleOrNull()
            if (!retried && current && response.code in setOf(408, 425, 429) && (retryAfter ?: 0.5) <= 5) {
                retried = true
                delay(((retryAfter ?: 0.5).coerceIn(0.1, 5.0) * 1000).toLong())
                continue
            }
            throw error(response.code, body, retryAfter)
        }
    }

    private fun request(method: String, path: String, fields: Map<String, String>?): Request {
        val url = baseUrl.toHttpUrl().newBuilder().encodedPath(path).addQueryParameter("_is_native", "true").build()
        val s = session()
        val body = fields?.let { f ->
            FormBody.Builder().apply { f.toSortedMap().forEach { (k, v) -> add(k, v) } }.build()
        } ?: if (method == "GET") null else ByteArray(0).toRequestBody(null)
        return Request.Builder().url(url).method(method, body)
            .header("clerk-api-version", API_VERSION)
            .header("x-ios-sdk-version", SDK_VERSION)
            .header("x-mobile", "0")
            .header("x-device-type", "unspecified")
            .header("x-device-model", device.model)
            .header("x-os-version", device.osVersion)
            .header("x-app-version", device.appVersion)
            .header("x-bundle-id", device.bundleId)
            .header("x-is-sandbox", "false")
            .apply {
                s?.clientToken?.let { header("Authorization", it) }
                s?.clientId?.let { header("x-clerk-client-id", it) }
            }
            .build()
    }

    /** A value sets the token, an empty or bare `Bearer` value clears it, no header keeps it. */
    private fun applyClientToken(header: String?, body: String) {
        val clientId = runCatching { (WireJson.parseToJsonElement(body) as? JsonObject)?.obj("client")?.str("id") }.getOrNull()
        if (header != null) {
            val token = header.trim()
            if (token.isEmpty() || token.equals("Bearer", ignoreCase = true)) {
                clearLocal()
                return
            }
            val next = (session() ?: ClerkStoredSession(token)).copy(clientToken = token, clientId = clientId ?: session()?.clientId)
            if (next != stored) persist(next)
        } else if (clientId != null) {
            session()?.let { if (it.clientId != clientId) persist(it.copy(clientId = clientId)) }
        }
    }

    private fun error(status: Int, body: String, retryAfter: Double?): ClerkError {
        val item = runCatching { ((WireJson.parseToJsonElement(body) as JsonObject).arr("errors") as JsonArray).first() as JsonObject }.getOrNull()
        val code = item?.str("code")
        val wait = retryAfter?.let { kotlin.math.ceil(it).toInt() }
        val message = when (code) {
            "form_code_incorrect" -> "That code is incorrect. Check the email and try again."
            "verification_expired" -> "That code has expired. Send a new code."
            "verification_failed" -> "Too many incorrect codes. Start the sign-in again."
            "form_identifier_not_found" -> "No T3 Connect account uses that email."
            "form_password_incorrect" -> "That password is incorrect. Try again, or sign in with an email code."
            "form_password_compromised" -> "This password appeared in a data breach. Sign in with an email code instead."
            "user_locked" -> item.obj("meta")?.long("lockout_expires_in_seconds")
                ?.let { "Your account is locked. Try again in ${maxOf(1, (it + 59) / 60)} min." } ?: "Your account is locked. Try again later."
            "authentication_invalid", "signed_out" -> return ClerkError(code, "Sign in to T3 Connect.", signedOut = true)
            "too_many_requests", "verification_code_rate_limit_exceeded", "verification_code_too_many_requests",
            "verification_code_too_many_attempts" -> rateLimited(wait)
            else -> if (status == 429) rateLimited(wait) else
                (item?.str("long_message") ?: item?.str("message"))?.let { "Sign-in failed: $it" } ?: "Sign-in failed. Try again."
        }
        return ClerkError(code, message)
    }

    private fun rateLimited(seconds: Int?) =
        if (seconds != null) "Too many attempts. Try again in $seconds ${if (seconds == 1) "second" else "seconds"}." else "Too many attempts. Wait a moment and try again."

    // Storage

    private fun session(): ClerkStoredSession? {
        if (!loaded) {
            loaded = true
            stored = store.getJson(STORE_KEY, ClerkStoredSession.serializer())
        }
        return stored
    }

    private fun persist(s: ClerkStoredSession) {
        stored = s
        loaded = true
        store.putJson(STORE_KEY, ClerkStoredSession.serializer(), s)
    }

    private fun clearLocal() {
        generation++
        stored = null
        loaded = true
        cachedJwt = null
        store.put(STORE_KEY, null)
    }

    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** T3's production Frontend API, from its publishable key `pk_live_Y2xlcmsudDMuY29kZXMk`. */
        const val PRODUCTION_URL = "https://clerk.t3.codes"
        const val TEMPLATE = "t3-relay"
        const val SDK_VERSION = "1.6.1"
        const val API_VERSION = "2026-08-20"
        const val STORE_KEY = "clerk.session"
        private const val JWT_MARGIN_MS = 10_000L
    }
}
