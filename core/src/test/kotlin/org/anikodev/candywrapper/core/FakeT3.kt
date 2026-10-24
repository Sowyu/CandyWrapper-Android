package org.anikodev.candywrapper.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.str

/**
 * A T3 server and relay on MockWebServer: the descriptor, token exchange, ticket and an RPC socket
 * that answers the shell subscription like the recorded server (snapshot, then `synchronized`
 * after our ack), plus the relay's token exchange and connect.
 */
class FakeT3(val envId: String = "env-1") : Dispatcher() {
    val server = MockWebServer().also { it.dispatcher = this; it.start() }
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val frames = CopyOnWriteArrayList<JsonObject>()
    @Volatile var ticketsMinted = 0
    /** Fields added to the snapshot's one thread, after a comma. */
    @Volatile var threadExtra = ""

    val base: String get() = server.url("/").toString()
    val origin: String get() = base.removeSuffix("/")
    val wsBase: String get() = "ws://${server.hostName}:${server.port}/"

    fun paths() = requests.map { "${it.method} ${it.url.encodedPath}" }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        return when (request.url.encodedPath) {
            "/.well-known/t3/environment" -> json("""{"environmentId":"$envId","label":"Mac","orchestrationProtocolVersion":2,"serverVersion":"0.0.46"}""")
            "/oauth/token" -> json("""{"access_token":"env-token","token_type":"${if (request.headers["DPoP"] != null) "DPoP" else "Bearer"}","expires_in":3600,"scope":"orchestration:read"}""")
            "/api/auth/websocket-ticket" -> {
                if (request.headers["Authorization"] == null) MockResponse.Builder().code(401).body("""{"_tag":"EnvironmentAuthInvalidError"}""").build() else {
                    ticketsMinted++
                    json("""{"ticket":"ticket-$ticketsMinted","expiresAt":"2999-01-01T00:00:00.000Z"}""")
                }
            }
            "/ws" -> MockResponse.Builder().webSocketUpgrade(Rpc()).build()
            "/health" -> json("""{"ok":true}""")
            "/v1/client/dpop-token" -> json("""{"access_token":"relay-token","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","token_type":"DPoP","expires_in":1800,"scope":"environment:connect"}""")
            "/v1/environments/$envId/connect" -> json("""{"environmentId":"$envId","endpoint":{"httpBaseUrl":"$base","wsBaseUrl":"$wsBase","providerKind":"cloudflare_tunnel"},"credential":"one-time","expiresAt":"2999-01-01T00:00:00.000Z"}""")
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private inner class Rpc : WebSocketListener() {
        private var shellId: String? = null
        private var synced = false

        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = WireJson.parseToJsonElement(text) as JsonObject
            frames += o
            val id = o.str("id")
            when (o.str("_tag")) {
                "Request" -> when (o.str("tag")) {
                    "orchestration.subscribeShell" -> {
                        shellId = id
                        webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"schemaVersion":2,"snapshotSequence":7,
                            "archivedThreads":[],"projects":[{"id":"p","title":"demo","workspaceRoot":"/w"}],
                            "threads":[{"id":"t","projectId":"p","title":"Hello","status":"completed","latestRunId":"r","activeProviderThreadId":null,
                            "createdAt":"2026-10-09T09:12:47.701Z","updatedAt":"2026-10-09T09:12:47.701Z","lineage":{"relationshipToParent":null}$threadExtra}]}}]}""")
                    }
                    "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"directEndpoints":[]}}}""")
                    "orchestration.dispatchCommand" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"sequence":8}}}""")
                    "server.probe" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{}}}""")
                }
                "Ack" -> if (o.str("requestId") == shellId && !synced) {
                    synced = true
                    webSocket.send("""{"_tag":"Chunk","requestId":"$shellId","values":[{"kind":"synchronized"}]}""")
                }
            }
        }
    }
}
