package org.anikodev.candywrapper.core

import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.anikodev.candywrapper.core.connect.Base64Url
import org.anikodev.candywrapper.core.connect.DPoP
import org.anikodev.candywrapper.core.connect.Jwk
import org.anikodev.candywrapper.core.connect.SoftwareDPoPKey
import org.anikodev.candywrapper.core.connect.derToRaw
import org.anikodev.candywrapper.core.connect.rawToDer
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

class DPoPTest {
    @Test
    fun matchesRfc9449Vectors() {
        // RFC 9449 section 4.1 key and section 6.1 thumbprint; section 7.1 access token hash.
        val jwk = Jwk("l8tFrhx-34tV3hRICRDY9zCkDlpBhF42UQUfWVAWBFs", "9VE4jf_Ok_o64zbTTlcuNJajHmt6v9TDVrU0CdvGRDA")
        assertEquals("0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I", jwk.thumbprint)
        assertEquals("fUHyO2r2Z3DZ53EsNrWBb0xWXoaNy59IiKCAqksmQEo", DPoP.accessTokenHash("Kz~8mXK1EalYznwH-LC-1fBAo.4Ljp~zsPE_NeO.gxU"))
    }

    @Test
    fun htuDropsQueryFragmentAndDefaultPort() {
        assertEquals("https://host.example/api/auth/websocket-ticket", DPoP.htu("https://HOST.example:443/api/auth/websocket-ticket?x=1#y".toHttpUrl()))
        assertEquals("http://10.0.0.5:3773/oauth/token", DPoP.htu("http://10.0.0.5:3773/oauth/token".toHttpUrl()))
        assertEquals("http://[fd7a:115c::1]:3773/", DPoP.htu("http://[fd7a:115c::1]:3773".toHttpUrl()))
    }

    @Test
    fun proofVerifiesLikeTheServer() {
        val key = SoftwareDPoPKey()
        val proof = DPoP.proof("post", "https://relay.t3.codes/v1/environments/e/connect".toHttpUrl(), key, accessToken = "tok", nowSeconds = 1_700_000_000, jti = "j")
        val (h, p, s) = proof.split('.')
        val header = WireJson.parseToJsonElement(String(Base64Url.decode(h))) as JsonObject
        val payload = WireJson.parseToJsonElement(String(Base64Url.decode(p))) as JsonObject
        assertEquals("dpop+jwt", header.str("typ"))
        assertEquals(setOf("kty", "crv", "x", "y"), header.obj("jwk")!!.keys)
        assertEquals("POST", payload.str("htm"))
        assertEquals("https://relay.t3.codes/v1/environments/e/connect", payload.str("htu"))
        assertEquals(1_700_000_000, payload.long("iat"))
        assertEquals(DPoP.accessTokenHash("tok"), payload.str("ath"))
        val raw = Base64Url.decode(s)
        assertEquals(64, raw.size)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(
                java.math.BigInteger(1, Base64Url.decode(key.jwk.x)), java.math.BigInteger(1, Base64Url.decode(key.jwk.y))),
                (SoftwareDPoPKey.generate().public as java.security.interfaces.ECPublicKey).params)))
        verifier.update("$h.$p".toByteArray())
        assertTrue(verifier.verify(rawToDer(raw)))
        // No ath without a token.
        val bare = DPoP.proof("POST", "https://relay.t3.codes/v1/client/dpop-token", key)
        val bareClaims = WireJson.parseToJsonElement(String(Base64Url.decode(bare.split('.')[1]))) as JsonObject
        assertEquals(null, bareClaims["ath"])
    }

    @Test
    fun derRoundTripsWithShortIntegers() {
        val raw = ByteArray(64) { if (it == 0 || it == 32) 0 else (it + 1).toByte() }
        assertTrue(raw.contentEquals(derToRaw(rawToDer(raw))))
    }
}
