package org.anikodev.candywrapper.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.model.RowKind
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.rpc.RpcConnection
import org.anikodev.candywrapper.core.store.LocalCache
import org.anikodev.candywrapper.core.timing.Timeline

/** A row ready to draw: the turn row plus its parsed Markdown. The same instance comes back while the row does not change. */
data class TimelineRow(val row: TurnRow, val markdown: List<MdBlock>?) {
    val key: String get() = row.id
    val kind: RowKind get() = row.kind
}

/**
 * One open thread: paints from the local cache first, then subscribes with `afterSequence` from
 * that cache so the server replays only what changed (protocol.md 7.7, 8.8). All decoding,
 * reducing and Markdown parsing happen off the main thread; [state] carries immutable lists.
 *
 * The environment session owns it. Screens call [acquire] when they show it and [release] when
 * they stop; the subscription lives only while someone holds it.
 */
class ThreadSession internal constructor(
    val envId: String,
    val threadId: String,
    private val traceName: String,
    private val cache: LocalCache,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    enum class Phase { LOADING, CACHED, SYNCING, LIVE, DELETED }

    data class State(val rows: List<TimelineRow> = emptyList(), val title: String? = null, val phase: Phase = Phase.LOADING)

    private val lock = Any()
    private val projection = ThreadProjection(threadId)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val loaded = CompletableDeferred<Unit>()
    private var loadStarted = false
    private var refs = 0
    private var subscription: RpcConnection.Subscription? = null
    private var subscribedOn: RpcConnection? = null
    private var rendered = HashMap<String, TimelineRow>()
    private var saveJob: Job? = null
    private var openedAtMs = 0.0
    private var reportedLive = false

    val held: Boolean get() = synchronized(lock) { refs > 0 }

    /** Starts showing the thread. [connection] is the environment's live socket, if any. */
    fun acquire(connection: RpcConnection?) {
        val first = synchronized(lock) {
            refs++
            if (refs == 1) {
                openedAtMs = Timeline.nowMs()
                reportedLive = false
            }
            refs == 1
        }
        if (!first) return
        Timeline.mark("$traceName.open")
        val needsLoad = synchronized(lock) { !loadStarted.also { loadStarted = true } }
        scope.launch(io) {
            if (needsLoad) load()
            loaded.await()
            connection?.let(::attach)
        }
    }

    fun release() {
        val last = synchronized(lock) {
            refs = (refs - 1).coerceAtLeast(0)
            if (refs == 0) {
                subscription?.cancel()
                subscription = null
                subscribedOn = null
            }
            refs == 0
        }
        if (last) save(0)
    }

    /** Subscribes on [connection] if the thread is shown and not already subscribed there. */
    fun attach(connection: RpcConnection) {
        if (!loaded.isCompleted) return
        synchronized(lock) {
            if (refs == 0 || (subscribedOn === connection && subscription != null) || connection.isClosed) return
            subscription?.cancel()
            projection.resetLive()
            val payload: JsonObject = buildJsonObject {
                put("threadId", threadId)
                if (projection.sequence > 0) put("afterSequence", projection.sequence)
                put("requestCompletionMarker", true)
                put("acceptBoundedSnapshot", true)
                put("acceptCompactTurnItems", true)
            }
            subscribedOn = connection
            subscription = connection.subscribe("orchestration.subscribeThread", payload, onEnd = { onEnded(connection, it) }) { values ->
                onValues(values.mapNotNull { it as? JsonObject })
            }
        }
        publish(Phase.SYNCING)
    }

    private fun load() {
        val start = Timeline.nowMs()
        val cached = runCatching { cache.thread(envId, threadId) }.getOrNull()
        if (cached != null) {
            synchronized(lock) { projection.restore(cached) }
            publish(Phase.CACHED)
            Timeline.span("$traceName.cache.loaded", start, "${cached.rows.size} rows")
        } else {
            Timeline.span("$traceName.cache.miss", start)
        }
        loaded.complete(Unit)
    }

    private fun onValues(values: List<JsonObject>) {
        var changed = false
        var live = false
        synchronized(lock) {
            for (v in values) when (projection.apply(v)) {
                ThreadProjection.Change.ROWS -> changed = true
                ThreadProjection.Change.LIVE -> live = true
                ThreadProjection.Change.NONE -> Unit
            }
        }
        if (!changed && !live) return
        publish(if (projection.live) Phase.LIVE else Phase.SYNCING)
        if (live && !reportedLive) {
            reportedLive = true
            Timeline.span("$traceName.live", openedAtMs, "since open")
            save(0)
        } else if (changed && projection.live) {
            save(SAVE_DEBOUNCE_MS)
        }
    }

    private fun onEnded(connection: RpcConnection, error: Throwable?) {
        synchronized(lock) {
            if (subscribedOn !== connection) return
            subscription = null
            subscribedOn = null
        }
        // A stream that failed on a live socket (the server's live buffer filled) resumes from the cursor.
        if (error != null && !connection.isClosed) {
            Timeline.mark("$traceName.stream.ended", error.message?.take(120))
            attach(connection)
        }
    }

    private fun publish(phase: Phase) {
        val next = synchronized(lock) {
            val rows = projection.rows()
            val nextRendered = HashMap<String, TimelineRow>(rows.size * 2)
            val out = rows.map { row ->
                val prev = rendered[row.id]
                val r = if (prev != null && prev.row == row) prev else TimelineRow(row, markdownFor(row))
                nextRendered[row.id] = r
                r
            }
            rendered = nextRendered
            State(out, projection.title, if (projection.deleted) Phase.DELETED else phase)
        }
        _state.value = next
    }

    private fun markdownFor(row: TurnRow): List<MdBlock>? = when (row.kind) {
        RowKind.ASSISTANT, RowKind.PLAN -> row.text?.let(Markdown::parse)
        else -> null
    }

    /** Writes the cache after [delayMs], folding writes that land inside it. */
    private fun save(delayMs: Long) {
        synchronized(lock) {
            if (saveJob?.isActive == true && delayMs > 0) return
            saveJob?.cancel()
            saveJob = scope.launch(io) {
                if (delayMs > 0) delay(delayMs)
                val snapshot = synchronized(lock) { if (projection.sequence > 0) projection.toCache() else null } ?: return@launch
                runCatching { cache.saveThread(envId, threadId, snapshot) }
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 5_000L
    }
}
