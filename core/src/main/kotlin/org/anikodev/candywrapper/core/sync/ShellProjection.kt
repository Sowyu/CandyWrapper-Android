package org.anikodev.candywrapper.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.media.ProjectIcons
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.orchestrator.OrchThread
import org.anikodev.candywrapper.core.store.CachedShell
import org.anikodev.candywrapper.core.store.ShellWrite

/**
 * The shell reducer (protocol.md 7.6, 7.8). Not thread-safe; the session calls it under its lock.
 * It tracks what changed since [takeWrite] so the cache rewrites only those rows.
 */
class ShellProjection(cached: CachedShell? = null, private val envId: String = "") {
    var sequence: Long = cached?.sequence ?: 0
        private set
    var live = false
        private set
    private val projects = LinkedHashMap<String, ProjectSummary>()
    private val threads = HashMap<String, ThreadSummary>()

    /**
     * The Orchestrator's view of every active thread, subagents included, only while [trackRaw] is on. The cache
     * keeps none of it, so it is complete only after a full snapshot on this process ([rawComplete]).
     */
    var trackRaw = false
        set(value) {
            field = value
            if (!value) { raw.clear(); rawComplete = false }
        }
    val raw = HashMap<String, OrchThread>()
    var rawComplete = false
        private set
    /** Set when [raw] changed since [takeRawChange]. */
    private var rawChanged = false
    /** Bumps on every snapshot and new socket: the relay's baseline starts over (CandyWrapper's per-session baseline). */
    var generation = 0L
        private set

    fun newGeneration() { generation++; rawChanged = true }

    fun takeRawChange(): Boolean = rawChanged.also { rawChanged = false }

    fun projects(): List<ProjectSummary> = projects.values.toList()

    private var replaced = false
    private val dirtyProjects = HashSet<String>()
    private val removedProjects = HashSet<String>()
    private val dirtyThreads = HashSet<String>()
    private val removedThreads = HashSet<String>()

    init {
        cached?.projects?.forEach { projects[it.id] = if (it.envId == envId) it else it.copy(envId = envId) }
        cached?.threads?.forEach { threads[it.id] = if (it.envId == envId) it else it.copy(envId = envId) }
    }

    /** True when there is something to resume from. */
    val hasCursor: Boolean get() = sequence > 0

    /** A new socket starts not live until its `synchronized` marker. */
    fun resetLive() { live = false }

    fun model(nowMs: Long = System.currentTimeMillis()) = ThreadListModel.build(projects, threads.values, nowMs)

    /** Applies one stream item. Returns true when the visible list changed. */
    fun apply(item: JsonObject, nowMs: Long = System.currentTimeMillis()): Boolean {
        when (item.str("kind")) {
            "snapshot" -> {
                val snap = item.obj("snapshot") ?: return false
                // A snapshot that names resolved roots only patches repository identities (client-runtime `mergeShellSnapshotProjects`).
                item.arr("resolvedRepositoryIdentityRoots")?.let { roots -> return enrich(snap, roots.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()) }
                projects.clear()
                threads.clear()
                snap.arr("projects")?.forEach { (it as? JsonObject)?.let { p -> project(p, envId) }?.let { p -> projects[p.id] = p } }
                snap.arr("threads")?.forEach { (it as? JsonObject)?.let { t -> ThreadSummary.fromShell(t, nowMs, envId = envId) }?.let { t -> threads[t.id] = t } }
                sequence = snap.long("snapshotSequence") ?: 0
                if (trackRaw) {
                    raw.clear()
                    snap.arr("threads")?.forEach { (it as? JsonObject)?.let { t -> OrchThread.from(t, envId) }?.let { t -> raw[t.id] = t } }
                    rawComplete = true
                    newGeneration()
                }
                replaced = true
                dirtyProjects.clear(); removedProjects.clear(); dirtyThreads.clear(); removedThreads.clear()
                return true
            }
            "synchronized" -> {
                live = true
                if (trackRaw) rawChanged = true
                return false
            }
        }
        val seq = item.long("sequence") ?: return false
        if (seq <= sequence) return false
        sequence = seq
        return when (item.str("kind")) {
            "project.updated" -> item.obj("project")?.let { project(it, envId) }?.let { p ->
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
                if (trackRaw) {
                    val t = if (item.str("location") == "active" || item.str("location") == null) OrchThread.from(o, envId) else null
                    if (t == null) { if (raw.remove(id) != null) rawChanged = true } else if (raw.put(id, t) != t) rawChanged = true
                }
                val summary = if (item.str("location") == "active" || item.str("location") == null) ThreadSummary.fromShell(o, nowMs, envId = envId) else null
                if (summary == null) removeThread(id) else {
                    if (threads[id] == summary) return false
                    threads[id] = summary
                    dirtyThreads += id
                    removedThreads -= id
                    true
                }
            }
            "thread.removed" -> item.str("threadId")?.let { id ->
                if (trackRaw && raw.remove(id) != null) rawChanged = true
                removeThread(id)
            } ?: false
            // Unknown kinds still advance the cursor; they are skipped either way.
            else -> false
        }
    }

    /**
     * An enrichment snapshot: a project whose root is listed takes the new identity exactly, any
     * other project only gains one it lacked. Threads and the cursor stay as they are.
     */
    private fun enrich(snap: JsonObject, roots: Set<String>): Boolean {
        var changed = false
        for (o in snap.arr("projects").orEmpty()) {
            val candidate = (o as? JsonObject)?.let { project(it, envId) } ?: continue
            val current = projects[candidate.id] ?: continue
            if (current.workspaceRoot != candidate.workspaceRoot) continue
            val next = when {
                current.workspaceRoot in roots -> current.copy(repoKey = candidate.repoKey, repoName = candidate.repoName, repoShortName = candidate.repoShortName)
                current.repoKey == null && candidate.repoKey != null -> current.copy(repoKey = candidate.repoKey, repoName = candidate.repoName, repoShortName = candidate.repoShortName)
                else -> current
            }
            if (next != current) {
                projects[next.id] = next
                dirtyProjects += next.id
                changed = true
            }
        }
        return changed
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

    companion object {
        /** contracts `OrchestrationProjectShell`; the repository fields follow `repositoryGroupingKeyOf` and `repositoryGroupingDisplayNameOf`. */
        fun project(o: JsonObject, envId: String = ""): ProjectSummary? {
            val id = o.str("id") ?: return null
            if (o.str("deletedAt") != null) return null
            val identity = o.obj("repositoryIdentity")
            val origin = identity?.obj("origin")
            return ProjectSummary(
                id = id,
                title = o.str("title") ?: "Project",
                workspaceRoot = o.str("workspaceRoot").orEmpty(),
                envId = envId,
                repoKey = origin?.str("canonicalKey") ?: identity?.str("canonicalKey"),
                repoName = if (origin != null) origin.str("displayName") ?: origin.str("canonicalKey") else identity?.str("displayName"),
                repoShortName = identity?.str("name"),
                updatedAt = ThreadSummary.time(o.str("updatedAt")).takeIf { it > 0 } ?: ThreadSummary.time(o.str("createdAt")),
                faviconPath = o.str("faviconPath"),
                icon = ProjectIcons.override(o.obj("projectIcon")),
            )
        }

        /**
         * The archived threads from `orchestration.getArchivedShellSnapshot` (contracts
         * `OrchestrationV2ArchivedShellSnapshot`: projects and threads, as in the active shell),
         * grouped like the active list. Archived projects' titles come from the same snapshot.
         */
        fun archivedModel(snapshot: JsonObject, nowMs: Long = System.currentTimeMillis(), envId: String = ""): ThreadListModel {
            val projects = snapshot.arr("projects").orEmpty().mapNotNull { (it as? JsonObject)?.let { p -> project(p, envId) } }.associateBy { it.id }
            val threads = snapshot.arr("threads").orEmpty().mapNotNull { (it as? JsonObject)?.let { t -> ThreadSummary.fromShell(t, nowMs, archived = true, envId = envId) } }
            return ThreadListModel.build(projects, threads, nowMs)
        }
    }
}
