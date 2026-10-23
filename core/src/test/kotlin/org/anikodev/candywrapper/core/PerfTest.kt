package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.rpc.RpcFrames
import org.anikodev.candywrapper.core.rpc.ServerMessage
import org.anikodev.candywrapper.core.store.CachedThread
import org.anikodev.candywrapper.core.sync.ShellProjection
import org.anikodev.candywrapper.core.sync.ThreadProjection

/**
 * Off-main-thread costs on the JVM, printed for docs/perf-budgets.md. The limits are loose so a
 * slow CI machine passes; a device is roughly 3 to 5 times slower than a desktop JVM.
 */
class PerfTest {
    private fun median(runs: Int = 30, block: () -> Unit): Double {
        repeat(10) { block() }
        val times = (1..runs).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun measures() {
        val frames = Transcripts.load("03-turn2")
        val snapshotFrame = frames.last { f ->
            f.dir == "recv" && f.kind == "ws" && f.body.toString().let { it.contains("\"visibleTurnItems\"") && it.contains("\"snapshotSequence\":198") }
        }.body.toString()
        val threadId = "c4ff4c86-9db4-4d8a-a846-a1493c31e49b"

        val decode = median { RpcFrames.parse(snapshotFrame) }
        val reduce = median {
            val chunk = RpcFrames.parse(snapshotFrame).single() as ServerMessage.Chunk
            ThreadProjection(threadId).apply { apply(chunk.values.first() as JsonObject) }.rows()
        }
        val projection = ThreadProjection(threadId).apply {
            apply((RpcFrames.parse(snapshotFrame).single() as ServerMessage.Chunk).values.first() as JsonObject)
        }
        val cached = projection.toCache()
        val encoded = WireJson.encodeToString(CachedThread.serializer(), cached)
        val cacheRead = median { ThreadProjection(threadId).restore(WireJson.decodeFromString(CachedThread.serializer(), encoded)) }

        val reply = buildString {
            repeat(40) { i ->
                append("## Step $i\n\nSome **bold** text with `code` and a [link](https://example.com/$i).\n\n")
                append("- item one\n- item two with *emphasis*\n\n```kotlin\nval x = $i\nprintln(x)\n```\n\n")
            }
        }
        val markdown = median { Markdown.parse(reply) }

        val threads = (1..300).joinToString(",") { i ->
            """{"id":"t$i","projectId":"p${i % 6}","title":"Thread $i","status":"completed","latestRunId":"r","activeProviderThreadId":null,
               "createdAt":"2026-10-09T09:12:47.701Z","updatedAt":"2026-10-09T09:12:47.701Z","latestRunCompletedAt":"2026-10-09T09:13:47.701Z",
               "lastVisitedAt":"2026-10-09T09:12:00.000Z","lineage":{"relationshipToParent":null},"pendingBackgroundTasks":[]}"""
        }
        val projects = (0..5).joinToString(",") { """{"id":"p$it","title":"Project $it","workspaceRoot":"/w/$it"}""" }
        val shellSnapshot = WireJson.parseToJsonElement(
            """{"kind":"snapshot","snapshot":{"schemaVersion":2,"snapshotSequence":900,"projects":[$projects],"threads":[$threads]}}""") as JsonObject
        val shell = median { ShellProjection().apply { apply(shellSnapshot) }.model() }
        val built = ShellProjection().apply { apply(shellSnapshot) }
        val rebuild = median { built.model() }
        val oneThread = WireJson.parseToJsonElement(threads.substringBefore("},{") + "}") as JsonObject
        val decodeRow = median(runs = 200) { org.anikodev.candywrapper.core.model.ThreadSummary.fromShell(oneThread) }

        println("PERF snapshot frame ${snapshotFrame.length / 1024} KiB, ${projection.rows().size} rows, cache blob ${encoded.length / 1024} KiB")
        println("PERF decode bounded thread snapshot: %.2f ms".format(decode))
        println("PERF decode + reduce thread snapshot: %.2f ms".format(reduce))
        println("PERF restore thread from cache JSON: %.2f ms".format(cacheRead))
        println("PERF markdown parse ${reply.length / 1024} KiB reply: %.2f ms".format(markdown))
        println("PERF shell snapshot of 300 threads to list model: %.2f ms".format(shell))
        println("PERF rebuild the 300-thread list model after a delta: %.3f ms".format(rebuild))
        println("PERF decode one thread.updated row: %.3f ms".format(decodeRow))
        assertTrue(reduce < 50 && cacheRead < 50 && markdown < 50 && shell < 100)
    }
}
