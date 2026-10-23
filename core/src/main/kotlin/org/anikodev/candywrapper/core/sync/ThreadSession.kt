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

/** What the thread view draws, one entry per list item. */
sealed interface TimelineItem {
    val key: String
}

/** A row ready to draw: the turn row plus its parsed Markdown. The same instance comes back while the row does not change. */
data class TimelineRow(val row: TurnRow, val markdown: List<MdBlock>?) : TimelineItem {
    override val key: String get() = row.id
    val kind: RowKind get() = row.kind
}

/** Two or more tool calls in a row, drawn as one collapsed line. */
data class ToolGroup(override val key: String, val rows: List<TimelineRow>) : TimelineItem {
    val running: Boolean get() = rows.any { it.row.running }
    val failed: Int get() = rows.count { it.row.failed }
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
    /** The environment's open socket, if any, read when the cache load finishes. */
    private val liveConnection: () -> RpcConnection?,
) {
    enum class Phase { LOADING, CACHED, SYNCING, LIVE, DELETED }

    data class State(val items: List<TimelineItem> = emptyList(), val title: String? = null, val phase: Phase = Phase.LOADING)

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
    /** Stream failures on one socket since the last good item; past [MAX_RESUBSCRIBES] it stops retrying there. */
    private var failures = 0

    val held: Boolean get() = synchronized(lock) { refs > 0 }

    /**
     * Starts showing the thread: loads the cache, then subscribes on the open socket. A socket that
     * opens later attaches through [attach].
     */
    fun acquire() {
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
            liveConnection()?.let(::attach)
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
            if (subscribedOn !== connection) failures = 0
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
            failures = 0
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
        val retry = synchronized(lock) {
            if (subscribedOn !== connection) return
            subscription = null
            failures++
            failures <= MAX_RESUBSCRIBES
        }
        // A stream that failed on a live socket (the server's live buffer filled) resumes from the cursor.
        // One that keeps failing (the thread is gone) stops, and the next socket tries again.
        if (error != null && !connection.isClosed) {
            Timeline.mark("$traceName.stream.ended", error.message?.take(120))
            if (retry) {
                synchronized(lock) { subscribedOn = null }
                attach(connection)
            }
        }
    }

    private fun publish(phase: Phase) {
        val next = synchronized(lock) {
            // List keys must be unique; drop a repeated id rather than crash the view.
            val seen = HashSet<String>()
            val rows = projection.rows().filter { seen.add(it.id) }
            val nextRendered = HashMap<String, TimelineRow>(rows.size * 2)
            val out = rows.map { row ->
                val prev = rendered[row.id]
                val r = if (prev != null && prev.row == row) prev else TimelineRow(row, markdownFor(row))
                nextRendered[row.id] = r
                r
            }
            rendered = nextRendered
            State(group(out), projection.title, if (projection.deleted) Phase.DELETED else phase)
        }
        _state.value = next
    }

    /** Folds runs of two or more tool rows into a [ToolGroup] keyed by its first row. */
    private fun group(rows: List<TimelineRow>): List<TimelineItem> {
        val out = ArrayList<TimelineItem>(rows.size)
        var i = 0
        while (i < rows.size) {
            var j = i
            while (j < rows.size && rows[j].kind == RowKind.TOOL) j++
            when {
                j - i >= 2 -> { out += ToolGroup("tools:" + rows[i].key, rows.subList(i, j).toList()); i = j }
                else -> { out += rows[i]; i++ }
            }
        }
        return out
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
        const val MAX_RESUBSCRIBES = 2
    }
}
