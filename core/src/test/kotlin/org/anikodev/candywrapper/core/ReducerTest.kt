package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.model.RowKind
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.model.ThreadSummary
import org.anikodev.candywrapper.core.store.MemoryCache
import org.anikodev.candywrapper.core.sync.ShellProjection
import org.anikodev.candywrapper.core.sync.ThreadProjection

class ReducerTest {
    private fun json(s: String) = WireJson.parseToJsonElement(s) as JsonObject

    @Test
    fun shellFollowsARecordedTurn() {
        val frames = Transcripts.load("02-turn")
        val shellId = Transcripts.requestId(frames, "orchestration.subscribeShell")!!
        val shell = ShellProjection()
        val pills = mutableListOf<StatusPill>()
        for (values in Transcripts.chunks(frames, shellId)) for (v in values) {
            shell.apply(v as JsonObject)
            shell.model().groups.firstOrNull()?.threads?.firstOrNull()?.pill?.let { if (pills.lastOrNull() != it) pills += it }
        }
        val group = shell.model().groups.single()
        assertEquals("demo-repo", group.project!!.title)
        val thread = group.threads.single()
        assertEquals("Count files", thread.title)
        assertTrue(shell.live)
        // idle, then working through the run, ready at the end (cancelled and interrupted show as ready).
        assertEquals(StatusPill.NONE, pills.first())
        assertTrue(StatusPill.WORKING in pills)
        assertEquals(StatusPill.READY, pills.last())
    }

    @Test
    fun shellIgnoresOldDeltasAndTracksWhatToWrite() {
        val shell = ShellProjection()
        shell.apply(json("""{"kind":"snapshot","snapshot":{"schemaVersion":2,"snapshotSequence":10,"projects":[{"id":"p","title":"P","workspaceRoot":"/p"}],"threads":[]}}"""))
        assertTrue(shell.takeWrite()!!.replaceAll)
        val thread = """{"id":"t","projectId":"p","title":"T","status":"idle","createdAt":"2026-10-09T09:12:47.701Z","updatedAt":"2026-10-09T09:12:47.701Z","latestRunId":null,"activeProviderThreadId":null,"lineage":{"relationshipToParent":null}}"""
        assertEquals(false, shell.apply(json("""{"kind":"thread.updated","sequence":9,"location":"active","thread":$thread}""")))
        assertTrue(shell.apply(json("""{"kind":"thread.updated","sequence":11,"location":"active","thread":$thread}""")))
        val write = shell.takeWrite()!!
        assertEquals(false, write.replaceAll)
        assertEquals(listOf("t"), write.threads.map { it.id })
        assertNull(shell.takeWrite())
        // Archived threads leave the active list.
        assertTrue(shell.apply(json("""{"kind":"thread.updated","sequence":12,"location":"archive","thread":$thread}""")))
        assertEquals(setOf("t"), shell.takeWrite()!!.removedThreads)
        assertEquals(12, shell.sequence)
        // An enrichment snapshot does not replace the list.
        assertEquals(false, shell.apply(json("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/p"],"snapshot":{"snapshotSequence":12,"projects":[],"threads":[]}}""")))
        assertEquals(0, shell.model().threadCount)
        // One that names a project's root patches its repository identity, and nothing else.
        assertTrue(shell.apply(json("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/p"],"snapshot":{"snapshotSequence":12,"projects":[{"id":"p","title":"P","workspaceRoot":"/p",
            "repositoryIdentity":{"canonicalKey":"github.com/x/p","locator":{"source":"git-remote","remoteName":"origin","remoteUrl":"u"},"name":"p","origin":{"canonicalKey":"github.com/up/p","displayName":"up/p"}}}],"threads":[]}}""")))
        val project = shell.model().projects.single()
        assertEquals("github.com/up/p", project.repoKey)
        assertEquals("up/p", project.repoName)
        assertEquals(listOf("p"), shell.takeWrite()!!.projects.map { it.id })
        assertEquals(12, shell.sequence)
        assertEquals(12, shell.sequence)
    }

    @Test
    fun threadFollowsARecordedTurnAndResumes() {
        val frames = Transcripts.load("02-turn")
        val threadId = Transcripts.requestId(frames, "orchestration.subscribeThread")!!
        val chunks = Transcripts.chunks(frames, threadId)
        val projection = ThreadProjection("14fc5e86-9f62-49ce-8f5d-42a343d29a8b")
        for (values in chunks) for (v in values) projection.apply(v as JsonObject)
        val rows = projection.rows()
        val kinds = rows.map { it.kind }
        assertEquals(RowKind.USER, kinds.first())
        assertTrue(rows.any { it.type == "command_execution" && it.detail == "ls -1 | wc -l" })
        assertEquals("3", rows.first { it.kind == RowKind.ASSISTANT }.text)
        assertTrue(rows.any { it.type == "run_interrupt_result" })
        assertEquals(rows.sortedBy { it.ordinal }.map { it.id }, rows.map { it.id })
        assertEquals("Count files", projection.title)

        // Restored from the cache, a replay of events it already has changes nothing.
        val restored = ThreadProjection(projection.threadId).apply { restore(projection.toCache()) }
        val before = restored.rows()
        for (values in chunks) for (v in values) {
            val o = v as JsonObject
            if (o["kind"].toString() == "\"event\"") assertEquals(ThreadProjection.Change.NONE, restored.apply(o))
        }
        assertSame(before, restored.rows())
    }

    @Test
    fun threadReadsABoundedSnapshot() {
        val frames = Transcripts.load("03-turn2")
        val snapshot = frames.filter { it.dir == "recv" && it.kind == "ws" }.flatMap { f ->
            (f.body["values"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { it as JsonObject }
        }.last { it["kind"].toString() == "\"snapshot\"" && it.containsKey("projection") }
        val projection = ThreadProjection("c4ff4c86-9db4-4d8a-a846-a1493c31e49b")
        projection.apply(snapshot)
        assertEquals(198, projection.sequence)
        assertEquals(19, projection.rows().size)
        val tools = projection.rows().filter { it.kind == RowKind.TOOL }
        assertTrue(tools.any { it.type == "dynamic_tool" && it.detail == "/Users/user/sandbox/demo-repo/hello.py" })
        assertTrue(projection.rows().any { it.type == "checkpoint" && it.text == "2 files changed" })
    }

    @Test
    fun unchangedRowsKeepTheirInstance() {
        val p = ThreadProjection("t")
        fun item(text: String, streaming: Boolean, seq: Int) = json("""{"kind":"event","sequence":$seq,"event":{"type":"turn-item.updated","threadId":"t","payload":
            {"id":"a","threadId":"t","type":"assistant_message","ordinal":1000002,"status":"running","text":"$text","streaming":$streaming,"messageId":"m"}}}""")
        p.apply(json("""{"kind":"event","sequence":1,"event":{"type":"turn-item.updated","threadId":"t","payload":{"id":"u","threadId":"t","type":"user_message","ordinal":1000001,"status":"completed","text":"hi","inputIntent":"turn_start"}}}"""))
        p.apply(item("Hel", true, 2))
        val first = p.rows()
        p.apply(item("Hello", true, 3))
        val second = p.rows()
        assertSame(first[0], second[0])
        assertEquals("Hello", second[1].text)
        // Events for another thread and stale sequences are ignored.
        assertEquals(ThreadProjection.Change.NONE, p.apply(item("x", false, 3)))
        assertEquals(ThreadProjection.Change.NONE, p.apply(json("""{"kind":"event","sequence":9,"event":{"type":"turn-item.updated","threadId":"other","payload":{"id":"z","type":"error","ordinal":1}}}""")))
        assertEquals(9, p.sequence)
    }

    @Test
    fun rolledBackRunsAndCancelledQueuedMessagesAreHidden() {
        val p = ThreadProjection("t")
        p.apply(json("""{"kind":"event","sequence":1,"event":{"type":"turn-item.updated","threadId":"t","payload":{"id":"q","threadId":"t","runId":"r2","type":"user_message","ordinal":2000001,"status":"completed","text":"later","inputIntent":"queued_turn"}}}"""))
        assertEquals(1, p.rows().size)
        p.apply(json("""{"kind":"event","sequence":2,"event":{"type":"run.updated","threadId":"t","payload":{"id":"r2","status":"cancelled"}}}"""))
        assertEquals(0, p.rows().size)
    }

    @Test
    fun forwardCompatibleServerDecodes() {
        val frames = Transcripts.load("21-future-server")
        val threadId = Transcripts.requestId(frames, "orchestration.subscribeThread")
        assertNotNull(threadId)
        val p = ThreadProjection("054ec2b1-808f-460b-a9b5-36dc18eb7594")
        for (values in Transcripts.chunks(frames, threadId)) for (v in values) p.apply(v as JsonObject)
        assertTrue(p.rows().isNotEmpty())
    }

    @Test
    fun pillFollowsTheSidebarRules() {
        fun pill(extra: String) = ThreadSummary.pill(json("""{"id":"t","latestRunId":"r",$extra}"""))
        assertEquals(StatusPill.APPROVAL, pill(""""status":"running","pendingRuntimeRequest":{"kind":"file-change"}"""))
        assertEquals(StatusPill.INPUT, pill(""""status":"running","pendingRuntimeRequest":{"kind":"user_input"}"""))
        assertEquals(StatusPill.WORKING, pill(""""status":"queued","activityRunStatus":"running""""))
        assertEquals(StatusPill.WAITING, pill(""""status":"completed","pendingBackgroundTasks":[{"kind":"subagent"}]"""))
        assertEquals(StatusPill.READY, pill(""""status":"completed","pendingBackgroundTasks":[{"kind":"command"}]"""))
        assertEquals(StatusPill.LIMITED, pill(""""status":"failed","lastErrorClass":"usage_limit""""))
        assertEquals(StatusPill.READY, pill(""""status":"interrupted""""))
    }

    @Test
    fun memoryCacheAppliesShellWrites() {
        val cache = MemoryCache()
        val shell = ShellProjection()
        shell.apply(json("""{"kind":"snapshot","snapshot":{"snapshotSequence":3,"projects":[{"id":"p","title":"P","workspaceRoot":"/p"}],"threads":[]}}"""))
        cache.saveShell("e", shell.takeWrite()!!)
        val restored = ShellProjection(cache.shell("e"))
        assertEquals(3, restored.sequence)
        assertTrue(restored.hasCursor)
    }

    @Test
    fun archivedSnapshotKeepsArchivedThreadsGroupedByProject() {
        val thread = """{"id":"t","projectId":"p","title":"Old work","status":"idle","createdAt":"2026-10-09T09:12:47.701Z","updatedAt":"2026-10-09T09:12:47.701Z","archivedAt":"2026-10-10T09:00:00.000Z","latestRunId":null,"activeProviderThreadId":null,"lineage":{"relationshipToParent":null}}"""
        val deleted = thread.replace("\"id\":\"t\"", "\"id\":\"d\"").replace("\"archivedAt\"", "\"deletedAt\":\"2026-10-11T00:00:00.000Z\",\"archivedAt\"")
        val model = ShellProjection.archivedModel(json("""{"schemaVersion":2,"snapshotSequence":3,"projects":[{"id":"p","title":"P","workspaceRoot":"/p"}],"threads":[$thread,$deleted]}"""))
        assertEquals(1, model.threadCount)
        assertEquals("P", model.groups.single().project!!.title)
        assertEquals("Old work", model.groups.single().threads.single().title)
        // The active shell still drops it.
        assertNull(ThreadSummary.fromShell(json(thread)))
    }
}
