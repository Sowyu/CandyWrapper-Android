package org.anikodev.candywrapper.core.sync

import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.store.CachedShell
import org.anikodev.candywrapper.core.store.ShellWrite

/**
 * The shell reducer (protocol.md 7.6, 7.8). Not thread-safe; the session calls it under its lock.
 * It tracks what changed since [takeWrite] so the cache rewrites only those rows.
 */
class ShellProjection(cached: CachedShell? = null) {
    var sequence: Long = cached?.sequence ?: 0
        private set
    var live = false
        private set
    private val projects = LinkedHashMap<String, ProjectSummary>()
    private val threads = HashMap<String, ThreadSummary>()

    private var replaced = false
    private val dirtyProjects = HashSet<String>()
    private val removedProjects = HashSet<String>()
    private val dirtyThreads = HashSet<String>()
    private val removedThreads = HashSet<String>()

    init {
        cached?.projects?.forEach { projects[it.id] = it }
        cached?.threads?.forEach { threads[it.id] = it }
    }

    /** True when there is something to resume from. */
    val hasCursor: Boolean get() = sequence > 0

    /** A new socket starts not live until its `synchronized` marker. */
    fun resetLive() { live = false }

    fun model() = ThreadListModel.build(projects, threads.values)

    /** Applies one stream item. Returns true when the visible list changed. */
    fun apply(item: JsonObject, nowMs: Long = System.currentTimeMillis()): Boolean {
        when (item.str("kind")) {
            "snapshot" -> {
                val snap = item.obj("snapshot") ?: return false
                // A snapshot that names resolved roots only patches project metadata, which this client does not show.
                if (item.containsKey("resolvedRepositoryIdentityRoots")) return false
                projects.clear()
                threads.clear()
                snap.arr("projects")?.forEach { (it as? JsonObject)?.let(::project)?.let { p -> projects[p.id] = p } }
                snap.arr("threads")?.forEach { (it as? JsonObject)?.let { t -> ThreadSummary.fromShell(t, nowMs) }?.let { t -> threads[t.id] = t } }
                sequence = snap.long("snapshotSequence") ?: 0
                replaced = true
                dirtyProjects.clear(); removedProjects.clear(); dirtyThreads.clear(); removedThreads.clear()
                return true
            }
            "synchronized" -> {
                live = true
                return false
            }
        }
        val seq = item.long("sequence") ?: return false
        if (seq <= sequence) return false
        sequence = seq
        return when (item.str("kind")) {
            "project.updated" -> item.obj("project")?.let(::project)?.let { p ->
                if (projects[p.id] == p) return false
                projects[p.id] = p
                dirtyProjects += p.id
                removedProjects -= p.id
                true
            } ?: false
            "project.removed" -> item.str("projectId")?.let { id ->
                removedProjects += id
                dirtyProjects -= id
                projects.remove(id) != null
            } ?: false
            "thread.updated" -> {
                val o = item.obj("thread") ?: return false
                val id = o.str("id") ?: return false
                val summary = if (item.str("location") == "active" || item.str("location") == null) ThreadSummary.fromShell(o, nowMs) else null
                if (summary == null) removeThread(id) else {
                    if (threads[id] == summary) return false
                    threads[id] = summary
                    dirtyThreads += id
                    removedThreads -= id
                    true
                }
            }
            "thread.removed" -> item.str("threadId")?.let(::removeThread) ?: false
            // Unknown kinds still advance the cursor; they are skipped either way.
            else -> false
        }
    }

    private fun removeThread(id: String): Boolean {
        removedThreads += id
        dirtyThreads -= id
        return threads.remove(id) != null
    }

    /** What to write to the cache, then forgets it. Null when nothing changed. */
    fun takeWrite(): ShellWrite? {
        if (!replaced && dirtyProjects.isEmpty() && removedProjects.isEmpty() && dirtyThreads.isEmpty() && removedThreads.isEmpty()) return null
        val write = ShellWrite(
            sequence = sequence,
            replaceAll = replaced,
            projects = if (replaced) projects.values.toList() else dirtyProjects.mapNotNull(projects::get),
            removedProjects = removedProjects.toSet(),
            threads = if (replaced) threads.values.toList() else dirtyThreads.mapNotNull(threads::get),
            removedThreads = removedThreads.toSet(),
        )
        replaced = false
        dirtyProjects.clear(); removedProjects.clear(); dirtyThreads.clear(); removedThreads.clear()
        return write
    }

    /** Sequence moved without row changes: still worth saving the cursor. */
    fun cursorWrite() = ShellWrite(sequence, false, emptyList(), emptySet(), emptyList(), emptySet())

    private fun project(o: JsonObject): ProjectSummary? {
        val id = o.str("id") ?: return null
        if (o.str("deletedAt") != null) return null
        return ProjectSummary(id, o.str("title") ?: "Project", o.str("workspaceRoot").orEmpty())
    }
}
