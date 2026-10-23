package org.anikodev.candywrapper.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.anikodev.candywrapper.core.auth.EnvironmentApi
import org.anikodev.candywrapper.core.auth.parsePairingInput
import org.anikodev.candywrapper.core.connect.ConnectAuthorizer
import org.anikodev.candywrapper.core.connect.RelayClient
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.model.SavedEnvironment
import org.anikodev.candywrapper.core.store.CachedShell
import org.anikodev.candywrapper.core.store.LocalCache
import org.anikodev.candywrapper.core.store.SecretStore
import org.anikodev.candywrapper.core.timing.Timeline

/**
 * Every saved environment and its session. The app restores them from the cache at start,
 * before any network, then starts them all at once.
 *
 * One record per environment id: pairing a computer that is also linked through T3 Connect turns
 * it into a paired environment, which skips the relay entirely.
 */
class Environments(
    private val cache: LocalCache,
    private val secrets: SecretStore,
    private val bearer: BearerCredentials,
    private val connect: ConnectCredentials,
    private val authorizer: ConnectAuthorizer,
    private val relay: RelayClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _sessions = MutableStateFlow<List<EnvironmentSession>>(emptyList())
    val sessions: StateFlow<List<EnvironmentSession>> = _sessions
    @Volatile private var foreground = true

    fun session(id: String): EnvironmentSession? = _sessions.value.firstOrNull { it.id == id }

    /** Builds sessions from cached rows. Call before the first frame; it does no I/O. */
    fun restore(envs: List<SavedEnvironment>, shells: Map<String, CachedShell?>) {
        _sessions.value = envs.map { newSession(it, shells[it.id]) }
    }

    fun startAll() = _sessions.value.forEach { it.start() }

    fun setForeground(value: Boolean) {
        foreground = value
        _sessions.value.forEach { it.setForeground(value) }
    }

    fun networkChanged() = _sessions.value.forEach { it.networkChanged() }

    /** Wakes T3 Connect sessions after a sign-in or sign-out so they retry or block at once. */
    fun wakeConnect() = _sessions.value.filter { it.env.value.kind == EnvironmentKind.CONNECT }.forEach { it.wake() }

    /** Redeems a pairing link from `npx t3 pair` and starts the environment. */
    suspend fun pair(input: String, manualCode: String? = null): EnvironmentSession = withContext(io) {
        val target = parsePairingInput(input, manualCode)
        val start = Timeline.nowMs()
        val descriptor = EnvironmentApi.descriptor(target.httpBase)
        val token = EnvironmentApi.exchange(target.httpBase, target.code)
        Timeline.span("pair.redeemed", start)
        val env = SavedEnvironment(descriptor.environmentId, target.label ?: descriptor.label, EnvironmentKind.PAIRED,
            target.httpBase, target.wsBase, emptyList(), nowMs())
        bearer.save(env.id, token)
        replace(env)
    }

    /**
     * Adds the computers linked to the signed-in T3 Connect account and drops linked ones that are
     * gone. Paired environments are left alone.
     */
    suspend fun refreshConnect(): Int = withContext(io) {
        val start = Timeline.nowMs()
        val listed = relay.listEnvironments()
        Timeline.span("connect.list", start, "${listed.size} environments")
        val current = _sessions.value.associateBy { it.id }
        for (e in listed) {
            val existing = current[e.environmentId]
            when {
                existing == null -> replace(SavedEnvironment(e.environmentId, e.label, EnvironmentKind.CONNECT,
                    e.endpoint.httpBaseUrl, e.endpoint.wsBaseUrl, emptyList(), nowMs()))
                existing.env.value.kind == EnvironmentKind.CONNECT && existing.env.value.label != e.label -> {
                    val updated = existing.env.value.copy(label = e.label)
                    cache.saveEnvironment(updated)
                    replace(updated)
                }
            }
        }
        val ids = listed.map { it.environmentId }.toSet()
        current.values.filter { it.env.value.kind == EnvironmentKind.CONNECT && it.id !in ids }.forEach { remove(it.id) }
        listed.size
    }

    /** Forgets an environment, its cache and its credentials. */
    suspend fun remove(id: String) = withContext(io) {
        val session = session(id) ?: return@withContext
        session.stop()
        _sessions.update { list -> list.filterNot { it.id == id } }
        bearer.forget(id)
        authorizer.forget(id)
        secrets.put("ticket.$id", null)
        cache.removeEnvironment(id)
    }

    /** After a T3 Connect sign-out: drop every linked environment's tokens and the linked environments themselves. */
    suspend fun signedOut() {
        val linked = _sessions.value.filter { it.env.value.kind == EnvironmentKind.CONNECT }
        authorizer.reset(linked.map { it.id })
        linked.forEach { remove(it.id) }
    }

    private fun replace(env: SavedEnvironment): EnvironmentSession {
        cache.saveEnvironment(env)
        val old = session(env.id)
        old?.stop()
        val session = newSession(env, cache.shell(env.id))
        _sessions.update { list -> if (old == null) list + session else list.map { if (it.id == env.id) session else it } }
        session.setForeground(foreground)
        session.start()
        return session
    }

    private fun newSession(env: SavedEnvironment, shell: CachedShell?) = EnvironmentSession(env, shell, cache, secrets,
        if (env.kind == EnvironmentKind.CONNECT) connect else bearer, scope, io)
}
