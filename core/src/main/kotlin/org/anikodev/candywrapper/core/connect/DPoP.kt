package org.anikodev.candywrapper.core.connect

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Unpadded base64url, RFC 4648 section 5. */
object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}

/** The public half of the proof key: exactly `{kty, crv, x, y}`, never `d` (protocol.md 10.5). */
data class Jwk(val x: String, val y: String) {
    /** RFC 7638: SHA-256 of the members in lexicographic order with no whitespace. */
    val thumbprint: String by lazy {
        Base64Url.encode(sha256("""{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""))
    }

    /** Header member order as T3 encodes it. */
    val headerJson: String get() = """{"kty":"EC","crv":"P-256","x":"$x","y":"$y"}"""

    companion object {
        fun of(key: ECPublicKey) = Jwk(Base64Url.encode(unsigned32(key.w.affineX)), Base64Url.encode(unsigned32(key.w.affineY)))
    }
}

/**
 * A P-256 key that signs proofs. One key per install, shared by the relay and every environment.
 * The app keeps it in Android Keystore; tests use [SoftwareDPoPKey].
 */
interface DPoPKey {
    val jwk: Jwk
    /** ES256 over [input] as raw 64-byte `r || s` (IEEE P1363), not DER. */
    fun sign(input: ByteArray): ByteArray
    val thumbprint: String get() = jwk.thumbprint
}

/** A JCA key in memory. [signature] lets the app pass a Keystore-backed private key through the same code. */
class SoftwareDPoPKey(private val pair: KeyPair = generate()) : DPoPKey {
    override val jwk: Jwk = Jwk.of(pair.public as ECPublicKey)
    override fun sign(input: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(input)
        derToRaw(sign())
    }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}

object DPoP {
    /**
     * `htu`: scheme, lower-case host, port when not the default, and path, without query and
     * fragment. The server compares it by exact string equality, so sign the URL the request goes to.
     */
    fun htu(url: HttpUrl): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://$host$port${url.encodedPath}"
    }

    fun accessTokenHash(accessToken: String): String = Base64Url.encode(sha256(accessToken))

    /**
     * A compact JWS proof for one request. Pass [accessToken] whenever the request carries one;
     * omit it on `/oauth/token` and the relay's `/v1/client/dpop-token`. Every proof gets a fresh
     * `jti`, because the environment and the relay reject a reused one.
     */
    fun proof(method: String, url: HttpUrl, key: DPoPKey, accessToken: String? = null,
              nowSeconds: Long = System.currentTimeMillis() / 1000, jti: String = UUID.randomUUID().toString()): String {
        val header = """{"typ":"dpop+jwt","alg":"ES256","jwk":${key.jwk.headerJson}}"""
        val payload = buildString {
            append("""{"htm":""").append(JsonPrimitive(method.uppercase()))
            append(""","htu":""").append(JsonPrimitive(htu(url)))
            append(""","jti":""").append(JsonPrimitive(jti))
            append(""","iat":""").append(nowSeconds)
            if (accessToken != null) append(""","ath":"""").append(accessTokenHash(accessToken)).append('"')
            append('}')
        }
        val signingInput = Base64Url.encode(header.toByteArray()) + "." + Base64Url.encode(payload.toByteArray())
        return signingInput + "." + Base64Url.encode(key.sign(signingInput.toByteArray(Charsets.US_ASCII)))
    }

    fun proof(method: String, url: String, key: DPoPKey, accessToken: String? = null) = proof(method, url.toHttpUrl(), key, accessToken)
}

/** The unverified `exp` (epoch seconds) of a JWT, only to decide how long to reuse it. */
fun jwtExpirySeconds(token: String): Long? = jwtClaims(token)?.get("exp")?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() }

/** The unverified `sub` of a JWT. */
fun jwtSubject(token: String): String? = jwtClaims(token)?.str("sub")

private fun jwtClaims(token: String): JsonObject? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    return runCatching { WireJson.parseToJsonElement(String(Base64Url.decode(parts[1]))) as? JsonObject }.getOrNull()
}

internal fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

/** A non-negative integer as exactly 32 big-endian bytes. */
internal fun unsigned32(n: BigInteger): ByteArray {
    val b = n.toByteArray()
    return when {
        b.size == 32 -> b
        b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
        else -> ByteArray(32 - b.size) + b
    }
}

/** ASN.1 `SEQUENCE { INTEGER r, INTEGER s }` from JCA to the raw 64-byte form JWS wants. */
fun derToRaw(der: ByteArray): ByteArray {
    var i = 0
    require(der[i++].toInt() == 0x30) { "not a DER sequence" }
    i += if (der[i].toInt() and 0x80 != 0) 1 + (der[i].toInt() and 0x7f) else 1
    fun integer(): ByteArray {
        require(der[i++].toInt() == 0x02) { "not a DER integer" }
        val len = der[i++].toInt() and 0xff
        val v = der.copyOfRange(i, i + len)
        i += len
        return unsigned32(BigInteger(1, v))
    }
    return integer() + integer()
}

/** The reverse of [derToRaw], for verifying in tests. */
fun rawToDer(raw: ByteArray): ByteArray {
    fun integer(bytes: ByteArray): ByteArray {
        val v = BigInteger(1, bytes).toByteArray()
        return byteArrayOf(0x02, v.size.toByte()) + v
    }
    val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
    return byteArrayOf(0x30, body.size.toByte()) + body
}
