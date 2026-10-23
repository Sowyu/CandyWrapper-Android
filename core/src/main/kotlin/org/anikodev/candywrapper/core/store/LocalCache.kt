package org.anikodev.candywrapper.core.store

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.SavedEnvironment
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.model.TurnRow

/** The shell as last seen: the resume cursor plus the rows the list draws. */
data class CachedShell(val sequence: Long, val projects: List<ProjectSummary>, val threads: List<ThreadSummary>)

/** What changed in a shell since the last write. [replaceAll] means a full snapshot replaced it. */
data class ShellWrite(
    val sequence: Long,
    val replaceAll: Boolean,
    val projects: List<ProjectSummary>,
    val removedProjects: Set<String>,
    val threads: List<ThreadSummary>,
    val removedThreads: Set<String>,
)

/** A thread as last seen. [sequence] seeds `afterSequence` on the next subscribe. */
@Serializable
data class CachedThread(
    val sequence: Long,
    val title: String? = null,
    val rows: List<TurnRow>,
    /** runId to status, for the visibility rules. */
    val runs: Map<String, String> = emptyMap(),
    val hasMoreHistory: Boolean = false,
)

/**
 * Local persistence. The app implements it with SQLite; every call may block, so callers run it
 * off the main thread. Reads at cold start come before any network.
 */
interface LocalCache {
    fun environments(): List<SavedEnvironment>
    fun saveEnvironment(env: SavedEnvironment)
    fun removeEnvironment(id: String)
    fun shell(envId: String): CachedShell?
    fun saveShell(envId: String, write: ShellWrite)
    fun thread(envId: String, threadId: String): CachedThread?
    fun saveThread(envId: String, threadId: String, thread: CachedThread)
}

/** For tests. */
class MemoryCache : LocalCache {
    val envs = ConcurrentHashMap<String, SavedEnvironment>()
    val shells = ConcurrentHashMap<String, CachedShell>()
    val threads = ConcurrentHashMap<String, CachedThread>()

    override fun environments() = envs.values.sortedBy { it.addedAtMs }
    override fun saveEnvironment(env: SavedEnvironment) { envs[env.id] = env }
    override fun removeEnvironment(id: String) { envs.remove(id); shells.remove(id) }
    override fun shell(envId: String) = shells[envId]
    override fun saveShell(envId: String, write: ShellWrite) {
        val old = shells[envId]?.takeUnless { write.replaceAll }
        val projects = (old?.projects.orEmpty().associateBy { it.id } - write.removedProjects + write.projects.associateBy { it.id }).values
        val threads = (old?.threads.orEmpty().associateBy { it.id } - write.removedThreads + write.threads.associateBy { it.id }).values
        shells[envId] = CachedShell(write.sequence, projects.toList(), threads.toList())
    }
    override fun thread(envId: String, threadId: String) = threads["$envId/$threadId"]
    override fun saveThread(envId: String, threadId: String, thread: CachedThread) { threads["$envId/$threadId"] = thread }
}
