package org.anikodev.candywrapper.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.anikodev.candywrapper.core.store.SecretStore

/**
 * Encrypted secrets in [CacheDb]. Values are AES-GCM under a random data key; the data key is
 * itself encrypted under an Android Keystore key that never leaves the device. So a process does
 * one Keystore operation, the first time it needs a secret, and every later read is in memory.
 *
 * If the Keystore key is gone (a reinstall over restored data), the stored secrets can't be read
 * and are dropped; the user pairs or signs in again.
 */
class SecretBox(private val db: CacheDb) : SecretStore {
    private val memory = ConcurrentHashMap<String, String>()
    private val absent = ConcurrentHashMap.newKeySet<String>()
    private val dataKey: SecretKey by lazy(::loadDataKey)
    private val random = SecureRandom()

    override fun get(name: String): String? {
        memory[name]?.let { return it }
        if (name in absent) return null
        val value = db.secret(name)?.let { blob -> runCatching { decrypt(dataKey, blob).decodeToString() }.getOrNull() }
        if (value == null) absent += name else memory[name] = value
        return value
    }

    override fun put(name: String, value: String?) {
        if (value == null) {
            memory.remove(name)
            absent += name
            db.putSecret(name, null)
        } else {
            memory[name] = value
            absent -= name
            db.putSecret(name, encrypt(dataKey, value.encodeToByteArray()))
        }
    }

    /** Touches the data key early, off the main thread, so the first real read is fast. */
    fun warm() {
        runCatching { dataKey }
    }

    private fun loadDataKey(): SecretKey {
        val wrapping = keystoreKey()
        db.secret(DATA_KEY)?.let { blob ->
            runCatching { return SecretKeySpec(decrypt(wrapping, blob), "AES") }
            // Unreadable: the Keystore key changed. Everything under the old data key is lost.
            db.clearSecrets()
        }
        val raw = ByteArray(32).also(random::nextBytes)
        db.putSecret(DATA_KEY, encrypt(wrapping, raw))
        return SecretKeySpec(raw, "AES")
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    /** `iv (12 bytes) || ciphertext`. A Keystore key picks its own IV. */
    private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        if (key is SecretKeySpec) cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12).also(random::nextBytes)))
        else cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val ALIAS = "candywrapper.secrets"
        const val DATA_KEY = "__data_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
