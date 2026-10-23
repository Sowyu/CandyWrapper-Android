package org.anikodev.candywrapper.core.sync

import java.util.TreeMap
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.store.CachedThread

/**
 * The thread reducer for the read-only view (protocol.md 7.7, 7.8), keeping only what the
 * timeline needs: rows, run statuses for visibility, the title and the resume cursor. Not
 * thread-safe; the thread session calls it under its lock.
 *
 * Rows keep their instance while the item does not change, so the list redraws only what did.
 */
class ThreadProjection(val threadId: String) {
    enum class Change { NONE, ROWS, LIVE }

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

    /** Fork-source and synthetic rows, in server order before the local ones. */
    private val inherited = ArrayList<TurnRow>()
    private val local = TreeMap<LocalKey, TurnRow>()
    private val localKeys = HashMap<String, LocalKey>()
    private val runs = HashMap<String, String>()
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
        runs.clear(); runs.putAll(cached.runs)
        inherited.clear(); local.clear(); localKeys.clear()
        for (row in cached.rows) if (row.visibility == "local") putLocal(row) else inherited += row
        rowsVersion++
    }

    fun toCache() = CachedThread(sequence, title, inherited + local.values, HashMap(runs), hasMoreHistory)

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

    private fun snapshot(item: JsonObject) {
        sequence = item.long("snapshotSequence") ?: sequence
        hasMoreHistory = item.bool("hasMoreHistory") == true
        val projection = item.obj("projection") ?: return
        projection.obj("thread")?.str("title")?.let { title = it }
        runs.clear()
        projection.arr("runs")?.forEach { r -> (r as? JsonObject)?.let { o -> o.str("id")?.let { id -> runs[id] = o.str("status") ?: "" } } }
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
                val id = payload.str("id") ?: return Change.NONE
                val status = payload.str("status") ?: ""
                if (runs.put(id, status) == status) Change.NONE else { rowsVersion++; Change.ROWS }
            }
            type == "thread.deleted" -> { deleted = true; Change.ROWS }
            type.startsWith("thread.") -> {
                val t = payload.str("title")
                if (t != null && t != title) { title = t; rowsVersion++; Change.ROWS } else Change.NONE
            }
            else -> Change.NONE
        }
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
}
