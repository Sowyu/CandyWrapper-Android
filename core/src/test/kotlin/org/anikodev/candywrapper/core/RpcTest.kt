package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.rpc.RpcConnection
import org.anikodev.candywrapper.core.rpc.RpcError
import org.anikodev.candywrapper.core.rpc.RpcFrames
import org.anikodev.candywrapper.core.rpc.RpcSocket
import org.anikodev.candywrapper.core.rpc.ServerMessage
import org.anikodev.candywrapper.core.rpc.SocketClosed

class FakeSocket : RpcSocket {
    val sent = mutableListOf<String>()
    var closed = false
    override fun send(text: String): Boolean { if (closed) return false; synchronized(sent) { sent += text }; return true }
    override fun close() { closed = true }
    fun sentObjects() = synchronized(sent) { sent.map { WireJson.parseToJsonElement(it) as JsonObject } }
}

class RpcTest {
    @Test
    fun parsesEveryRecordedServerFrame() {
        for (name in listOf("01-basic", "02-turn", "03-turn2", "21-future-server")) {
            for (f in Transcripts.load(name).filter { it.dir == "recv" && it.kind == "ws" }) {
                val messages = RpcFrames.parse(f.body.toString())
                // 21-future-server sends made-up message types on purpose; they are skipped.
                val known = f.body.str("_tag") in setOf("Chunk", "Exit", "Pong", "Defect", "ClientProtocolError")
                assertEquals(if (known) 1 else 0, messages.size, "$name t=${f.t}")
            }
        }
    }

    @Test
    fun readsTypedErrorsDefectsAndArrays() {
        val die = RpcFrames.parse("""{"_tag":"Exit","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Unknown request tag: projects.list"}]},"requestId":"6"}""").single()
        assertIs<ServerMessage.Exit>(die)
        assertEquals("Unknown request tag: projects.list", die.error!!.message)
        val fail = RpcFrames.parse("""{"_tag":"Exit","requestId":9,"exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationV2DispatchCommandError","message":"Run is not interruptible."}}]}}""").single() as ServerMessage.Exit
        assertEquals("9", fail.requestId)
        assertEquals("OrchestrationV2DispatchCommandError", fail.error!!.tag)
        val array = RpcFrames.parse("""[{"_tag":"Pong"},{"_tag":"Chunk","requestId":"1","values":[{"kind":"synchronized"}]}]""")
        assertEquals(2, array.size)
    }

    @Test
    fun pipelinesRequestsAndAcksEveryChunk() = runBlocking {
        val socket = FakeSocket()
        val conn = RpcConnection().also { it.attach(socket) }
        val seen = mutableListOf<String>()
        conn.subscribe("orchestration.subscribeShell", buildJsonObject { put("requestCompletionMarker", true) }) { values ->
            values.forEach { seen += (it as JsonObject).str("kind")!! }
        }
        val config = conn.call("server.getConfig")
        // Both requests are on the wire before any answer.
        assertEquals(listOf("orchestration.subscribeShell", "server.getConfig"), socket.sentObjects().map { it.str("tag") })

        conn.onText("""{"_tag":"Chunk","requestId":"1","values":[{"kind":"snapshot","snapshot":{}}]}""")
        conn.onText("""{"_tag":"Chunk","requestId":"1","values":[{"kind":"synchronized"}]}""")
        conn.onText("""{"_tag":"Exit","requestId":"2","exit":{"_tag":"Success","value":{"cwd":"/"}}}""")
        assertEquals(listOf("snapshot", "synchronized"), seen)
        assertEquals(2, socket.sentObjects().count { it.str("_tag") == "Ack" && it.str("requestId") == "1" })
        assertEquals("/", (config.await() as JsonObject).str("cwd"))
    }

    @Test
    fun failsPendingCallsWhenTheSocketCloses() = runBlocking {
        val conn = RpcConnection().also { it.attach(FakeSocket()) }
        val call = conn.call("server.probe")
        var ended: Throwable? = null
        conn.subscribe("orchestration.subscribeThread", JsonObject(emptyMap()), onEnd = { ended = it }) { }
        conn.onClosed(SocketClosed("gone"))
        assertFailsWith<SocketClosed> { call.await() }
        assertIs<SocketClosed>(ended)
        assertTrue(conn.isClosed)
    }

    @Test
    fun pingWaitsForPongAndInterruptStopsAStream() = runBlocking {
        val socket = FakeSocket()
        val conn = RpcConnection().also { it.attach(socket) }
        val sub = conn.subscribe("orchestration.subscribeThread", JsonObject(emptyMap())) { }
        sub.cancel()
        assertEquals("Interrupt", socket.sentObjects().last().str("_tag"))
        val pinger = async(Dispatchers.Default) { conn.ping(2_000) }
        while (socket.sentObjects().none { it.str("_tag") == "Ping" }) Thread.sleep(1)
        conn.onText("""{"_tag":"Pong"}""")
        assertTrue(pinger.await())
        assertEquals(false, conn.ping(50))
    }

    @Test
    fun typedFailureCompletesTheCallExceptionally() = runBlocking {
        val conn = RpcConnection().also { it.attach(FakeSocket()) }
        val call = conn.call("orchestration.dispatchCommand")
        conn.onText("""{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Interrupt","fiberId":3}]}}""")
        val e = assertFailsWith<RpcError> { call.await() }
        assertTrue(e.interrupted)
    }
}
