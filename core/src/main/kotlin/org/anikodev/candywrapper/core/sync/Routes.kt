package org.anikodev.candywrapper.core.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.anikodev.candywrapper.core.auth.originOf
import org.anikodev.candywrapper.core.model.DirectRoute

/**
 * One address an environment answers at. [verifyFirst] means its descriptor must name the right
 * environment before any credential goes there: every learned LAN or tailnet address (it may be
 * another machine on this network), and any plain-http address.
 */
data class Route(val httpBase: String, val wsBase: String, val label: String, val verifyFirst: Boolean, val learned: Boolean)

object Routes {
    /** The primary address first, then learned direct addresses, LAN before tailnet, minus duplicates and loopback. */
    fun build(primaryHttp: String, primaryWs: String, primaryLabel: String, learned: List<DirectRoute>): List<Route> {
        val primary = Route(primaryHttp, primaryWs, primaryLabel, !primaryHttp.startsWith("https"), learned = false)
        val seen = hashSetOf(normalize(primaryHttp))
        val direct = learned.sortedBy { if (it.kind == "lan") 0 else 1 }.mapNotNull { d ->
            val url = d.httpBaseUrl.toHttpUrlOrNull() ?: return@mapNotNull null
            if (isLoopback(url.host)) return@mapNotNull null
            val origin = originOf(url)
            if (!seen.add(origin)) return@mapNotNull null
            val ws = (if (url.isHttps) "wss" else "ws") + origin.removePrefix(url.scheme)
            Route(origin, ws, d.kind, verifyFirst = true, learned = true)
        }
        return listOf(primary) + direct
    }

    /** Loopback names whichever device opens it, so it is never a route to a remote host. */
    fun isLoopback(host: String) = host == "localhost" || host == "::1" || host.startsWith("127.") || host == "0.0.0.0"

    private fun normalize(base: String) = base.toHttpUrlOrNull()?.let(::originOf) ?: base

    /**
     * Runs every attempt at once and returns the first success; the others are cancelled, and a
     * success that lands after the winner goes to [discard]. When all fail, throws the first
     * attempt's error, which is the primary route's.
     */
    suspend fun <T> race(attempts: List<Pair<String, suspend () -> T>>, discard: (T) -> Unit): Pair<String, T> = coroutineScope {
        val winner = CompletableDeferred<Pair<String, T>>()
        val errors = arrayOfNulls<Throwable>(attempts.size)
        val failed = AtomicInteger(0)
        val jobs = attempts.mapIndexed { index, (name, block) ->
            launch {
                try {
                    val value = block()
                    if (!winner.complete(name to value)) discard(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    errors[index] = e
                    if (failed.incrementAndGet() == attempts.size) winner.completeExceptionally(errors.firstNotNullOf { it })
                }
            }
        }
        try {
            winner.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}
