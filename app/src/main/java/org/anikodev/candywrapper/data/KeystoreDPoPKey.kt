package org.anikodev.candywrapper.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.anikodev.candywrapper.core.connect.DPoPKey
import org.anikodev.candywrapper.core.connect.Jwk
import org.anikodev.candywrapper.core.connect.derToRaw

/**
 * The install's DPoP key: P-256 in Android Keystore, non-exportable. Only the public JWK and
 * signatures leave it. Created on first use; one key serves the relay and every environment.
 */
class KeystoreDPoPKey private constructor(private val privateKey: PrivateKey, override val jwk: Jwk) : DPoPKey {
    override fun sign(input: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(input)
        derToRaw(sign())
    }

    companion object {
        private const val ALIAS = "candywrapper.dpop"

        fun loadOrCreate(): KeystoreDPoPKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
                return KeystoreDPoPKey(it.privateKey, Jwk.of(it.certificate.publicKey as ECPublicKey))
            }
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKeyPair()
            return KeystoreDPoPKey(pair.private, Jwk.of(pair.public as ECPublicKey))
        }
    }
}

/** Loads the Keystore key on first use, so a paired-only install never touches it. */
class LazyDPoPKey(load: () -> DPoPKey) : DPoPKey {
    private val key by lazy(load)
    override val jwk: Jwk get() = key.jwk
    override fun sign(input: ByteArray) = key.sign(input)
}
