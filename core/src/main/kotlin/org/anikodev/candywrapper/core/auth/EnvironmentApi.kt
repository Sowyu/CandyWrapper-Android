package org.anikodev.candywrapper.core.auth

import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.http.formBody
import org.anikodev.candywrapper.core.http.json
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/** Who we are on the wire. The app fills it in at start. Values must stay inside the server's enums (protocol.md 2.2, 4.3). */
object ClientIdentity {
    @Volatile var appVersion: String = "0"
    @Volatile var deviceModel: String = ""
    @Volatile var osMajorVersion: String = ""

    /** Shown in the host's list of authorized clients. */
    val label: String get() = if (deviceModel.isBlank()) "CandyWrapper for Android" else "CandyWrapper on $deviceModel"
}

/** `GET /.well-known/t3/environment` (protocol.md 1.3), trimmed to what the client checks. */
data class Descriptor(
    val environmentId: String,
    val label: String,
    val protocolVersion: Int,
    val serverVersion: String?,
)

data class AccessToken(val token: String, val type: String, val expiresAtMs: Long)

data class Ticket(val ticket: String, val expiresAtMs: Long)

class IncompatibleEnvironment(version: Int) :
    Exception("This computer speaks orchestration protocol $version. Update T3 Code on it.")

class EnvironmentMismatch(expected: String, actual: String) :
    Exception("The address answered as environment $actual, not $expected.")

/** HTTP calls to a T3 environment (protocol.md 4 and 5). Every call goes through [Http.environment]. */
object EnvironmentApi {
    const val PROTOCOL = 2

    /** The base URL with its path replaced, like T3's `environmentEndpointUrl`. */
    fun url(httpBase: String, path: String): HttpUrl = httpBase.toHttpUrl().newBuilder()
        .encodedPath(path).query(null).fragment(null).build()

    suspend fun descriptor(httpBase: String, client: OkHttpClient = Http.environment): Descriptor {
        val o = client.json(Request.Builder().url(url(httpBase, "/.well-known/t3/environment")).build())
        val d = Descriptor(
            environmentId = o.str("environmentId") ?: throw IllegalStateException("The descriptor has no environmentId."),
            label = o.str("label") ?: "T3 Code",
            protocolVersion = o.long("orchestrationProtocolVersion")?.toInt() ?: 1,
            serverVersion = o.str("serverVersion"),
        )
        if (d.protocolVersion != PROTOCOL) throw IncompatibleEnvironment(d.protocolVersion)
        return d
    }

    /**
     * `POST /oauth/token`: a pairing code or a relay-minted credential for an access token. Pass
     * [dpopProof] for a T3 Connect credential; the answer is then a one-hour DPoP token.
     */
    suspend fun exchange(httpBase: String, credential: String, dpopProof: String? = null, nowMs: Long = System.currentTimeMillis()): AccessToken {
        val body = formBody(
            "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
            "subject_token" to credential,
            "subject_token_type" to "urn:t3:params:oauth:token-type:environment-bootstrap",
            "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token",
            "client_label" to ClientIdentity.label,
            "client_device_type" to "mobile",
            "client_os" to "Android",
        )
        val request = Request.Builder().url(url(httpBase, "/oauth/token")).post(body)
            .header("Accept", "application/json")
            .apply { if (dpopProof != null) header("DPoP", dpopProof) }
            .build()
        val o = Http.environment.json(request)
        return AccessToken(
            token = o.str("access_token") ?: throw IllegalStateException("The token answer has no access_token."),
            type = o.str("token_type") ?: "Bearer",
            expiresAtMs = nowMs + (o.long("expires_in") ?: 3600) * 1000,
        )
    }

    /** `POST /api/auth/websocket-ticket`. The ticket lives 5 minutes and can be used more than once (protocol.md 4.4). */
    suspend fun ticket(url: HttpUrl, headers: Map<String, String>): Ticket {
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null))
            .header("Accept", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val o = Http.environment.json(request)
        val expires = o.str("expiresAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return Ticket(o.str("ticket") ?: throw IllegalStateException("The ticket answer has no ticket."),
            expires ?: (System.currentTimeMillis() + 4 * 60_000))
    }

    fun ticketUrl(httpBase: String) = url(httpBase, "/api/auth/websocket-ticket")

    /**
     * `wsBase` with path `/ws` when it has none, then the ticket and client parameters
     * (protocol.md 2.2). [connectionMethod] is `relay` for every T3 Connect route, direct ones included.
     */
    fun socketUrl(wsBase: String, ticket: String, connectionMethod: String): String {
        val httpForm = wsBase.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
        val base = httpForm.toHttpUrl()
        val b = base.newBuilder()
        if (base.encodedPath == "/") b.encodedPath("/ws")
        b.removeAllQueryParameters("wsTicket")
            .addQueryParameter("wsTicket", ticket)
            .addQueryParameter("clientSurface", "mobile")
            .addQueryParameter("clientAppVersion", ClientIdentity.appVersion.take(64))
            .addQueryParameter("clientDeviceType", "phone")
            .addQueryParameter("clientOs", "Android")
            .addQueryParameter("connectionMethod", connectionMethod)
            .addQueryParameter("orchestrationProtocol", PROTOCOL.toString())
        if (ClientIdentity.osMajorVersion.isNotBlank()) b.addQueryParameter("clientOsMajorVersion", ClientIdentity.osMajorVersion)
        if (ClientIdentity.deviceModel.isNotBlank()) b.addQueryParameter("clientDeviceModel", ClientIdentity.deviceModel.take(64))
        val http = b.build().toString()
        return if (base.isHttps) "wss" + http.removePrefix("https") else "ws" + http.removePrefix("http")
    }
}
