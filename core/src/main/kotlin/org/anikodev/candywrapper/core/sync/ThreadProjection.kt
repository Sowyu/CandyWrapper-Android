package org.anikodev.candywrapper.core.sync

import java.util.TreeMap
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.store.CachedThread
import org.anikodev.candywrapper.core.store.QueuedRun
import org.anikodev.candywrapper.core.store.ThreadMeta

/** A message waiting behind the active run (protocol.md 8.6). */
data class QueuedMessage(val runId: String, val position: Int, val text: String)

/** An approval or question waiting for the user, from its turn item (protocol.md 8.3). */
data class PendingRequest(val row: TurnRow) {
    val requestId: String get() = row.requestId!!
    val isQuestion: Boolean get() = row.type == "user_input_request"
}

/**
 * The thread reducer (protocol.md 7.7, 7.8), keeping what the thread view and its composer need:
 * rows, run statuses for visibility and Stop, the queue, pending requests, checkpoints for diffs,
 * the thread's settings, the title and the resume and history cursors. Not thread-safe; the thread
 * session calls it under its lock.
 *
 * Rows keep their instance while the item does not change, so the list redraws only what did.
 */
class ThreadProjection(val threadId: String) {
    /** [ROWS] when the timeline changed, [META] when only the composer's inputs did. */
    enum class Change { NONE, ROWS, META, LIVE }

    var sequence: Long = 0
        private set
    var title: String? = null
        private set
    var live = false
        private set
    var deleted = false
        private set
    var hasMoreHistory = false
        private set
    var historyCursor: String? = null
        private set
    var meta = ThreadMeta()
        private set

    /** Fork-source and synthetic rows, in server order before the local ones. */
    private val inherited = ArrayList<TurnRow>()
    private val local = TreeMap<LocalKey, TurnRow>()
    private val localKeys = HashMap<String, LocalKey>()
    /** Run statuses in the order the runs appeared, so the last active one is the run to stop. */
    private val runs = LinkedHashMap<String, String>()
    private val queue = HashMap<String, QueuedRun>()
    private val messages = HashMap<String, String>()
    private val requests = HashMap<String, String>()
    private val checkpoints = HashMap<String, Int>()
    private var rowsVersion = 0
    private var builtVersion = -1
    private var built: List<TurnRow> = emptyList()

    private data class LocalKey(val ordinal: Long, val id: String) : Comparable<LocalKey> {
        override fun compareTo(other: LocalKey) = compareValuesBy(this, other, LocalKey::ordinal, LocalKey::id)
    }

    fun restore(cached: CachedThread) {
        sequence = cached.sequence
        title = cached.title
        hasMoreHistory = cached.hasMoreHistory
        historyCursor = cached.historyCursor
        cached.meta?.let { meta = it }
        runs.clear(); runs.putAll(cached.runs)
        queue.clear(); queue.putAll(cached.queue)
        messages.clear(); messages.putAll(cached.queuedText)
        requests.clear(); requests.putAll(cached.requests)
        checkpoints.clear(); checkpoints.putAll(cached.checkpoints)
        inherited.clear(); local.clear(); localKeys.clear()
        for (row in cached.rows) if (row.visibility == "local") putLocal(row) else inherited += row
        rowsVersion++
    }

    fun toCache() = CachedThread(
        sequence, title, inherited + local.values, LinkedHashMap(runs), hasMoreHistory, historyCursor, meta,
        HashMap(queue), queue.values.mapNotNull { q -> messages[q.messageId]?.let { q.messageId to it } }.toMap(),
        requests.filterValues { it == "pending" || it == NOT_RESUMABLE }, HashMap(checkpoints),
    )

    /** A new subscription is not live until its `synchronized` marker. */
    fun resetLive() { live = false }

    /** The rows to draw, oldest first, with the visibility rules applied. */
    fun rows(): List<TurnRow> {
        if (builtVersion == rowsVersion) return built
        val out = ArrayList<TurnRow>(inherited.size + local.size)
        out.addAll(inherited)
        for (row in local.values) if (visible(row)) out += row
        built = out
        builtVersion = rowsVersion
        return out
    }

    /** The run Stop interrupts: the newest one that is preparing, starting or running (protocol.md 8.5). */
    fun interruptibleRunId(): String? = runs.entries.lastOrNull { it.value in INTERRUPTIBLE }?.key

    /** The run a message sent now would land behind, including one waiting on its checkpoint. */
    fun activeRunId(): String? = runs.entries.lastOrNull { it.value in ACTIVE }?.key

    fun queued(): List<QueuedMessage> = queue.entries
        .filter { runs[it.key] == "queued" }
        .sortedBy { it.value.position }
        .map { (runId, q) -> QueuedMessage(runId, q.position, messages[q.messageId].orEmpty()) }

    /** Approvals and questions still waiting, oldest first. A request the server cannot resume is left out. */
    fun pending(): List<PendingRequest> = local.values.filter { row ->
        (row.type == "approval_request" || row.type == "user_input_request") && row.requestId != null && row.running &&
            requests[row.requestId].let { it == null || it == "pending" }
    }.map(::PendingRequest)

    /** The app run ordinal of a checkpoint, for `getTurnDiff`. */
    fun checkpointTurn(checkpointId: String): Int? = checkpoints[checkpointId]

    fun apply(item: JsonObject): Change = when (item.str("kind")) {
        "snapshot" -> { snapshot(item); Change.ROWS }
        "synchronized" -> { live = true; Change.LIVE }
        else -> {
            val seq = item.long("sequence")
            if (seq == null || seq <= sequence) Change.NONE else {
                sequence = seq
                if (item.str("kind") == "event") item.obj("event")?.let(::event) ?: Change.NONE else Change.NONE
            }
        }
    }

    /**
     * Merges an older page from `/history` (protocol.md 7.7, `threadHistoryMerge.ts:62-149`): rows
     * already present keep their live values, the rest slot in by ordinal. Returns false when the
     * page answered a cursor that is no longer current.
     */
    fun prependHistory(requestedCursor: String, page: JsonObject): Boolean {
        if (requestedCursor != historyCursor) return false
        val older = ArrayList<TurnRow>()
        for (entry in page.arr("items").orEmpty()) {
            val e = entry as? JsonObject ?: continue
            val visibility = e.str("visibility") ?: "local"
            val isLocal = visibility == "local" || e.str("sourceThreadId") == threadId
            val row = e.obj("item")?.let { TurnRow.fromItem(it, if (isLocal) "local" else visibility) } ?: continue
            if (isLocal) { if (row.id !in localKeys) putLocal(row) } else if (inherited.none { it.id == row.id }) older += row
        }
        if (older.isNotEmpty()) inherited.addAll(0, older)
        historyCursor = page.str("nextCursor")
        hasMoreHistory = page.bool("hasMoreHistory") == true && historyCursor != null
        rowsVersion++
        return true
    }

    private fun snapshot(item: JsonObject) {
        sequence = item.long("snapshotSequence") ?: sequence
        hasMoreHistory = item.bool("hasMoreHistory") == true
        historyCursor = item.str("historyCursor")
        val projection = item.obj("projection") ?: return
        projection.obj("thread")?.let(::thread)
        runs.clear(); queue.clear(); messages.clear(); requests.clear(); checkpoints.clear()
        projection.arr("runs")?.forEach { (it as? JsonObject)?.let(::run) }
        projection.arr("messages")?.forEach { (it as? JsonObject)?.let(::message) }
        projection.arr("runtimeRequests")?.forEach { (it as? JsonObject)?.let(::request) }
        projection.arr("checkpoints")?.forEach { (it as? JsonObject)?.let(::checkpoint) }
        inherited.clear(); local.clear(); localKeys.clear()
        for (entry in projection.arr("visibleTurnItems").orEmpty()) {
            val e = entry as? JsonObject ?: continue
            val visibility = e.str("visibility") ?: "local"
            val isLocal = visibility == "local" || e.str("sourceThreadId") == threadId
            val row = e.obj("item")?.let { TurnRow.fromItem(it, if (isLocal) "local" else visibility) } ?: continue
            if (isLocal) putLocal(row) else inherited += row
        }
        rowsVersion++
    }

    private fun event(e: JsonObject): Change {
        if (e.str("threadId") != threadId) return Change.NONE
        val payload = e.obj("payload") ?: return Change.NONE
        val type = e.str("type") ?: return Change.NONE
        return when {
            type == "turn-item.updated" -> upsert(payload)
            type == "run.created" || type == "run.updated" -> {
                val before = runs[payload.str("id")]
                val queuedBefore = queue[payload.str("id")]
                run(payload)
                val id = payload.str("id")
                when {
                    runs[id] != before -> { rowsVersion++; Change.ROWS }
                    queue[id] != queuedBefore -> Change.META
                    else -> Change.NONE
                }
            }
            type == "message.updated" -> if (message(payload)) Change.META else Change.NONE
            type == "runtime-request.updated" -> if (request(payload)) Change.META else Change.NONE
            type == "checkpoint.captured" -> { checkpoint(payload); Change.NONE }
            type == "thread.deleted" -> { deleted = true; Change.ROWS }
            type.startsWith("thread.") -> {
                val before = meta
                val t = payload.str("title")
                thread(payload)
                if (t != null && t != title) { title = t; rowsVersion++; Change.ROWS } else if (meta != before) Change.META else Change.NONE
            }
            else -> Change.NONE
        }
    }

    private fun thread(o: JsonObject) {
        o.str("title")?.let { title = it }
        meta = ThreadMeta(
            providerInstanceId = o.str("providerInstanceId") ?: meta.providerInstanceId,
            model = ModelSelection.from(o.obj("modelSelection")) ?: meta.model,
            runtimeMode = o.str("runtimeMode") ?: meta.runtimeMode,
            interactionMode = o.str("interactionMode") ?: meta.interactionMode,
            projectId = o.str("projectId") ?: meta.projectId,
        )
    }

    private fun run(o: JsonObject) {
        val id = o.str("id") ?: return
        val status = o.str("status") ?: ""
        runs[id] = status
        val position = o.long("queuePosition")
        val messageId = o.str("userMessageId")
        if (status == "queued" && position != null && messageId != null) queue[id] = QueuedRun(position.toInt(), messageId) else queue.remove(id)
    }

    /** Keeps user message text, which is where a queued run's text lives. */
    private fun message(o: JsonObject): Boolean {
        if (o.str("role") != "user") return false
        val id = o.str("id") ?: return false
        val text = o.str("text") ?: return false
        return messages.put(id, text) != text && queue.values.any { it.messageId == id }
    }

    private fun request(o: JsonObject): Boolean {
        val id = o.str("id") ?: return false
        val status = o.str("status") ?: return false
        val value = if (status == "pending" && o.obj("responseCapability")?.str("type") == "not_resumable") NOT_RESUMABLE else status
        return requests.put(id, value) != value
    }

    private fun checkpoint(o: JsonObject) {
        val id = o.str("id") ?: return
        o.long("appRunOrdinal")?.let { checkpoints[id] = it.toInt() }
    }

    private fun upsert(payload: JsonObject): Change {
        if (payload.str("threadId") != null && payload.str("threadId") != threadId) return Change.NONE
        val row = TurnRow.fromItem(payload) ?: return Change.NONE
        val existing = localKeys[row.id]?.let(local::get)
        if (existing == row) return Change.NONE
        // A bounded window drops updates for items older than it (orchestrationV2Projection.ts:89-106).
        if (existing == null && hasMoreHistory && local.isNotEmpty() && row.ordinal < local.firstKey().ordinal) return Change.NONE
        putLocal(row)
        rowsVersion++
        return Change.ROWS
    }

    private fun putLocal(row: TurnRow) {
        localKeys.remove(row.id)?.let(local::remove)
        val key = LocalKey(row.ordinal, row.id)
        local[key] = row
        localKeys[row.id] = key
    }

    /** packages/shared/src/orchestrationV2Timeline.ts:42-112, minus the superseded-attempt rule. */
    // ponytail: no run attempts kept, so a run_interrupt_result on a superseded attempt still shows; keep attempts if users see stray "Interrupted" rows.
    private fun visible(row: TurnRow): Boolean {
        val run = row.runId?.let(runs::get) ?: return true
        if (run == "rolled_back") return false
        if (row.type == "user_message" && row.inputIntent == "queued_turn" && run == "cancelled") return false
        return true
    }

    companion object {
        const val NOT_RESUMABLE = "not_resumable"
        private val INTERRUPTIBLE = setOf("preparing", "starting", "running")
        private val ACTIVE = setOf("preparing", "starting", "running", "waiting")
    }
}
