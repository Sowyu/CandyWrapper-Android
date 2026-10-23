package org.anikodev.candywrapper.core.store

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import org.anikodev.candywrapper.core.json.WireJson

/**
 * Small encrypted values: tokens, tickets, the Clerk client. The app stores them in SQLite under an
 * Android Keystore key. Reads come from memory after the first one. Never log a value.
 */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String?)
}

fun <T> SecretStore.getJson(name: String, serializer: KSerializer<T>): T? =
    get(name)?.let { runCatching { WireJson.decodeFromString(serializer, it) }.getOrNull() }

fun <T> SecretStore.putJson(name: String, serializer: KSerializer<T>, value: T?) =
    put(name, value?.let { WireJson.encodeToString(serializer, it) })

/** For tests and for running the core without a device. */
class MemorySecretStore : SecretStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(name: String) = map[name]
    override fun put(name: String, value: String?) {
        if (value == null) map.remove(name) else map[name] = value
    }
}
