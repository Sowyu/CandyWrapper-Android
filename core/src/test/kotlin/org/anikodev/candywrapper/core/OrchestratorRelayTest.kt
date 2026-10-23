package org.anikodev.candywrapper.core

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.media.ChatAttachment
import org.anikodev.candywrapper.core.model.HtmlPage
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.orchestrator.ChatItem
import org.anikodev.candywrapper.core.orchestrator.CheckTask
import org.anikodev.candywrapper.core.orchestrator.MachineRef
import org.anikodev.candywrapper.core.orchestrator.OrchThread
import org.anikodev.candywrapper.core.orchestrator.OrchestratorBrief
import org.anikodev.candywrapper.core.orchestrator.OrchestratorCall
import org.anikodev.candywrapper.core.orchestrator.OrchestratorChat
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorEvent
import org.anikodev.candywrapper.core.orchestrator.OrchestratorModelRow
import org.anikodev.candywrapper.core.orchestrator.OrchestratorOverview
import org.anikodev.candywrapper.core.orchestrator.OrchestratorRelay
import org.anikodev.candywrapper.core.orchestrator.OrchestratorReply
import org.anikodev.candywrapper.core.orchestrator.Permission
import org.anikodev.candywrapper.core.orchestrator.ProjectionMessage
import org.anikodev.candywrapper.core.orchestrator.RelayHost
import org.anikodev.candywrapper.core.orchestrator.RelayOwnership
import org.anikodev.candywrapper.core.orchestrator.RelayOwnership.Action
import org.anikodev.candywrapper.core.orchestrator.RelayOwnership.Reason
import org.anikodev.candywrapper.core.orchestrator.ScheduleClaim
import org.anikodev.candywrapper.core.orchestrator.TurnWatch

// CandyWrapper's OrchestratorRelayTests.swift: calls go to the machine they name, and the limits hold there too.

/** Two machines: "mini", where the Orchestrator runs, and "book", another Mac. */
private class FakeMachines : RelayHost {
    var config = OrchestratorConfig(
        machines = mapOf("mini" to org.anikodev.candywrapper.core.orchestrator.OrchestratorMachine("orch", "")),
        models = listOf(OrchestratorModelRow("e", "Everyday work", "claudeAgent", "claude-haiku-5-5", isDefault = true)),
    )
    val threads = mutableMapOf(
        "mini" to mutableListOf(orchThread("orch") { copy(title = "Orchestrator") }, orchThread("a1") { copy(title = "Mini thread", projectId = "p1") }),
        "book" to mutableListOf(orchThread("w1", "book") { copy(title = "Book thread", projectId = "p2") }),
    )
    val projects = mapOf("mini" to listOf(ProjectSummary("p1", "CandyWrapper", "/a", "mini")), "book" to listOf(ProjectSummary("p2", "Website", "/b", "book")))
    val connected = mutableSetOf("mini", "book")
    var labels = mapOf("mini" to "miniko", "book" to "MacBook")
    val requests = ArrayList<Triple<String, String, JsonObject>>()
    var briefHandsOff = emptySet<String>()
    var ids = 0
    private val projection = """{"messages":[{"id":"m1","threadId":"w1","role":"user","text":"Fix the test","streaming":false,"createdAt":"2027-01-15T08:00:00.000Z","attachments":[]},{"id":"m2","threadId":"w1","role":"assistant","text":"STATUS done","streaming":false,"createdAt":"2027-01-15T08:01:00.000Z","attachments":[]}]}"""

    fun edit(machine: String, id: String, change: OrchThread.() -> OrchThread) {
        val list = threads.getValue(machine)
        val i = list.indexOfFirst { it.id == id }
        list[i] = list[i].change()
    }

    override fun machines() = listOf(MachineRef("mini", labels.getValue("mini"), true, "mini" in connected), MachineRef("book", labels.getValue("book"), false, "book" in connected))
    override fun projects(machine: String) = projects[machine]
    override fun threads(machine: String): List<OrchThread>? = threads[machine]
    override suspend fun request(machine: String, method: String, payload: JsonObject): JsonElement? {
        requests += Triple(machine, method, payload)
        return if (method == "orchestration.getThreadProjection") WireJson.parseToJsonElement(projection) else buildJsonObject { put("sequence", 2) }
    }
    override fun config() = config
    override fun update(change: (OrchestratorConfig) -> OrchestratorConfig) { config = change(config) }
    override fun rows(machine: String) = config.models
    override fun runtimeMode(machine: String) = "full-access"
    override fun serverResolvesDelivery(machine: String) = true
    override fun orchestratorKey() = "mini:orch"
    override fun briefHandsOff() = briefHandsOff
    override fun nowMs() = NOW
    override fun newId() = "id-${++ids}"

    fun run(line: String): String = runBlocking { OrchestratorRelay(this@FakeMachines).run(OrchestratorCall.parse(line)).orEmpty() }
}

class OrchestratorCallParsingTest {
    @Test fun oneCallPerLineAndTheBlockLeavesTheProse() {
        val reply = OrchestratorReply.parse("Checking the other machines.\n\n```orchestrator-call\n{\"tool\":\"list\"}\n{\"tool\":\"read\",\"machine\":\"book\",\"threadId\":\"w1\",\"last\":3}\nnot json\n```")
        assertEquals("Checking the other machines.", reply.text)
        assertEquals(listOf(OrchestratorCall.Tool.LIST, OrchestratorCall.Tool.READ, null), reply.calls.map { it.tool })
        assertEquals("book", reply.calls[1].machine)
        assertEquals("w1", reply.calls[1].threadId)
        assertEquals(3, reply.calls[1].last)
        assertEquals("not json", reply.calls[2].raw)
    }

    @Test fun aPrettyPrintedObjectOrArrayAndT3ToolNamesParse() {
        val o = OrchestratorCall.parse("{\n  \"tool\": \"send\",\n  \"machine\": \"book\",\n  \"threadId\": \"w1\",\n  \"text\": \"go\",\n  \"confirmed\": true\n}").single()
        assertEquals(OrchestratorCall.Tool.SEND, o.tool)
        assertTrue(o.confirmed)
        assertEquals("go", o.text)
        assertEquals(listOf(OrchestratorCall.Tool.LIST, OrchestratorCall.Tool.ARCHIVE),
            OrchestratorCall.parse("""[{"tool":"thread_list"},{"tool":"archive","machine":"book","threadId":"w1"}]""").map { it.tool })
    }

    @Test fun machinesResolveByIdOrLabel() {
        val machines = listOf(MachineRef("env-1", "MacBook", false), MachineRef("env-2", "mini", true), MachineRef("env-3", "Mini", false))
        assertEquals("env-1", MachineRef.resolve("env-1", machines)?.id)
        assertEquals("env-1", MachineRef.resolve("macbook", machines)?.id)
        // Two machines share the label, so only the id names one.
        assertNull(MachineRef.resolve("mini", machines))
        assertEquals("Mini", MachineRef.resolve("env-3", machines)?.label)
    }
}

class OrchestratorRelayRoutingTest {
    @Test fun listShowsEveryMachineLabelledAndSkipsTheOrchestrator() {
        val fake = FakeMachines()
        val result = fake.run("""{"tool":"list"}""")
        assertTrue(result.startsWith("Result: listed 2 threads on 2 machines"), result)
        assertTrue(result.contains("## miniko (machine mini), where you run"))
        assertTrue(result.contains("## MacBook (machine book)"))
        assertTrue(result.contains("“Mini thread” (threadId a1, project CandyWrapper)"))
        assertTrue(result.contains("“Book thread” (threadId w1, project Website)"))
        assertTrue(result.contains("Website (projectId p2)"))
        assertFalse(result.contains("“Orchestrator”"))
        assertTrue(fake.requests.isEmpty())
        val one = fake.run("""{"tool":"list","machine":"MacBook"}""")
        assertTrue(one.contains("MacBook") && !one.contains("miniko"))
    }

    @Test fun readGoesToTheNamedMachine() {
        val fake = FakeMachines()
        val result = fake.run("""{"tool":"read","machine":"MacBook","threadId":"w1","last":1}""")
        assertEquals(listOf("book"), fake.requests.map { it.first })
        assertEquals("orchestration.getThreadProjection", fake.requests[0].second)
        assertEquals("w1", fake.requests[0].third.str("threadId"))
        assertTrue(result.startsWith("Result: read “Book thread” on MacBook"))
        assertTrue(result.contains("[assistant] STATUS done") && !result.contains("Fix the test"))
    }

    @Test fun sendGoesToTheNamedMachineAndQueuesBehindARunningTurn() {
        val fake = FakeMachines()
        assertEquals("Result: sent to “Book thread” on MacBook", fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"Next step"}"""))
        val p = fake.requests[0].third
        assertEquals("book" to "orchestration.dispatchCommand", fake.requests[0].first to fake.requests[0].second)
        assertEquals("message.dispatch", p.str("type"))
        assertEquals("w1", p.str("threadId"))
        assertEquals("Next step", p.str("text"))
        assertEquals("start_immediately", p.obj("dispatchMode")?.str("type"))
        fake.edit("book", "w1") { copy(status = "running", latestRunId = "run-1") }
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"Then this"}""").contains("queued"))
        assertEquals("queue_after_active", fake.requests[1].third.obj("dispatchMode")?.str("type"))
    }

    @Test fun aThreadOnAnotherMachineNeedsItsMachine() {
        val fake = FakeMachines()
        val missing = fake.run("""{"tool":"send","threadId":"w1","text":"x"}""")
        assertTrue(missing.contains("needs a \"machine\"") && missing.contains("on MacBook (machine book)"))
        assertTrue(fake.run("""{"tool":"read","machine":"mini","threadId":"w1"}""").contains("there is no thread w1 on miniko"))
        assertTrue(fake.run("""{"tool":"read","machine":"studio","threadId":"w1"}""").contains("no machine “studio”"))
        fake.connected.remove("book")
        assertTrue(fake.run("""{"tool":"read","machine":"book","threadId":"w1"}""").contains("MacBook isn't connected"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun launchGoesToTheNamedMachineWithTheModelsRowAndWorktree() {
        val fake = FakeMachines()
        val result = fake.run("""{"tool":"launch","machine":"book","projectId":"p2","title":"Fixer","text":"Fix it","model":"everyday work","worktree":"main"}""")
        assertTrue(result.startsWith("Result: launched “Fixer” on MacBook (machine book, threadId "), result)
        assertEquals("book" to "orchestration.launchThread", fake.requests[0].first to fake.requests[0].second)
        val p = fake.requests[0].third
        assertEquals("p2", p.str("projectId"))
        assertEquals("Fixer", p.str("title"))
        assertEquals("claude-haiku-5-5", p.obj("modelSelection")?.str("model"))
        assertEquals("worktree", p.obj("workspaceStrategy")?.str("type"))
        assertEquals("main", p.obj("workspaceStrategy")?.str("baseRef"))
        assertEquals("Fix it", p.obj("initialMessage")?.str("text"))
        assertEquals("false", p["generateTitle"]?.jsonPrimitive?.content)
        val id = p.str("threadId")!!
        assertEquals(setOf("book:$id"), fake.config.relayWorkers)
        // Either device's launches count: the Result line names the worker.
        assertEquals(setOf("book:$id"), OrchestratorRelay.launchedIn(result))
        assertTrue(fake.run("""{"tool":"launch","machine":"book","projectId":"p1","text":"x"}""").contains("needs a \"projectId\" from MacBook: Website (projectId p2)"))
        assertTrue(fake.run("""{"tool":"launch","machine":"book","projectId":"p2","text":"x","model":"Huge"}""").contains("no Models row “Huge”"))
    }

    @Test fun archiveGoesToTheNamedMachine() {
        val fake = FakeMachines()
        assertEquals("Result: archived “Book thread” on MacBook", fake.run("""{"tool":"archive","machine":"book","threadId":"w1"}"""))
        assertEquals("thread.archive", fake.requests[0].third.str("type"))
    }

    @Test fun eachCallGetsItsOwnResultAndTenAtMostRun() {
        val fake = FakeMachines()
        val result = fake.run(List(12) { """{"tool":"list","machine":"book"}""" }.joinToString("\n"))
        assertEquals(11, OrchestratorRelay.summaries(result).size)
        assertTrue(result.contains("skipped 2 more calls"))
        assertTrue(OrchestratorRelay.isResultMessage(result))
    }

    @Test fun sharedLabelsGetTheirMachineIdAndListFindsByTitle() {
        val fake = FakeMachines()
        fake.labels = mapOf("mini" to "miniko", "book" to "miniko")
        assertTrue(fake.run("""{"tool":"read","machine":"miniko","threadId":"w1"}""").contains("no machine “miniko”"))
        assertTrue(fake.run("""{"tool":"read","machine":"mini","threadId":"w1"}""").contains("there is no thread w1 on miniko (machine mini)"))
        assertEquals("Result: sent to “Book thread” on miniko (machine book)", fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"x"}"""))
        val found = fake.run("""{"tool":"list","title":"book"}""")
        assertTrue(found.startsWith("Result: listed 1 thread on 2 machines") && found.contains("“Book thread”") && !found.contains("“Mini thread”"))
    }
}

class OrchestratorRelayLimitTest {
    @Test fun handsOffIsNeverPromptedOrArchivedFromEitherDevice() {
        val fake = FakeMachines()
        fake.config = fake.config.copy(handsOff = setOf("book:w1"))
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"x","confirmed":true}""").contains("is Hands off"))
        assertTrue(fake.run("""{"tool":"archive","machine":"book","threadId":"w1"}""").contains("is Hands off"))
        assertTrue(fake.run("""{"tool":"list"}""").contains("Hands off: report only, never prompt it"))
        // The Mac's mark, read from the latest brief, holds here too.
        fake.config = fake.config.copy(handsOff = emptySet())
        fake.briefHandsOff = setOf("book:w1")
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"x"}""").contains("is Hands off"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun askFirstRefusesUntilConfirmed() {
        val fake = FakeMachines()
        fake.edit("book", "w1") { copy(latestUserAuthoredMessageAt = ago(3.0), latestUserMessageAt = ago(3.0)) }
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"x"}""").contains("the user wrote in “Book thread” on MacBook 3 min ago. Ask the user first"))
        assertTrue(fake.requests.isEmpty())
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"x","confirmed":true}""").startsWith("Result: sent"))
        assertEquals(1, fake.requests.size)
    }

    @Test fun aRelayedPromptDoesNotStartTheAskFirstWindow() {
        val fake = FakeMachines()
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"x"}""").startsWith("Result: sent"))
        // The server records the relayed prompt as the user's, a moment after the relay noted it.
        fake.edit("book", "w1") { copy(latestUserAuthoredMessageAt = NOW + 1_000, latestUserMessageAt = NOW + 1_000) }
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"y"}""").startsWith("Result: sent"))
        // You writing there later still asks first.
        fake.edit("book", "w1") { copy(latestUserAuthoredMessageAt = NOW + 60_000, latestUserMessageAt = NOW + 60_000) }
        assertTrue(fake.run("""{"tool":"send","machine":"book","threadId":"w1","text":"z"}""").contains("Ask the user first"))
    }

    @Test fun launchStopsAtTheWorkerLimitAcrossMachines() {
        val fake = FakeMachines()
        fake.config = fake.config.copy(crewLimit = 2, relayWorkers = setOf("book:w1"))
        // One child of the Orchestrator on miniko and one relayed worker on the MacBook are running.
        fake.edit("mini", "a1") { copy(status = "running", parentThreadId = "orch") }
        fake.edit("book", "w1") { copy(status = "running") }
        assertTrue(fake.run("""{"tool":"launch","machine":"book","projectId":"p2","text":"x"}""").contains("2 workers are running and the limit is 2"))
        assertTrue(fake.requests.isEmpty())
        fake.edit("book", "w1") { copy(status = "completed") }
        assertTrue(fake.run("""{"tool":"launch","machine":"book","projectId":"p2","text":"x"}""").startsWith("Result: launched"))
    }
}

class OrchestratorChatTest {
    private fun row(id: String, type: String, text: String, minutes: Double, run: String? = null, attachments: List<ChatAttachment> = emptyList(), page: HtmlPage? = null, title: String = type) =
        TurnRow(id = id, type = type, ordinal = 0, status = "completed", runId = run, title = title, text = text, atMs = NOW + (minutes * 60_000).toLong(), attachments = attachments, htmlPage = page)

    private fun kinds(items: List<ChatItem>) = items.map {
        when (it) {
            is ChatItem.Time -> "time"
            is ChatItem.Brief -> "brief"
            is ChatItem.Line -> "line:${it.text}:${it.threadId.orEmpty()}"
            is ChatItem.Bubble -> (if (it.fromUser) "me" else "bot") + (if (it.first) "^" else "") + (if (it.last) "$" else "")
            is ChatItem.Plan -> "plan:${it.steps.first().threadId}"
            is ChatItem.Memory -> "memory"
            ChatItem.Read -> "read"
            ChatItem.Typing -> "typing"
            is ChatItem.Image -> "image"
            is ChatItem.Html -> "html:${it.page.title}"
        }
    }

    @Test fun stacksTimesReadAndTyping() {
        val rows = listOf(
            row("m0", "user_message", OrchestratorBrief.first("You are the Orchestrator."), 0.0),
            row("m1", "user_message", "Ship the notes", 1.0),
            row("m2", "user_message", "Then review them", 1.5),
            row("m3", "assistant_message", "On it.", 2.0),
            row("m4", "assistant_message", "```orchestrator-plan\n1. Write ([Scribe](t3-thread://v1/w1))\n```", 2.1),
            row("m5", "user_message", "Event: “Scribe” finished a turn (t3-thread://v1/w1).", 40.0),
        )
        assertEquals(listOf("time", "brief", "me^", "me$", "read", "bot^$", "plan:w1", "time", "line:“Scribe” finished a turn:w1", "typing"),
            kinds(OrchestratorChat.items(rows, running = true, nowMs = NOW, zone = ZoneOffset.UTC)))
    }

    @Test fun noReadBeforeTheTurnStarts() {
        val items = OrchestratorChat.items(listOf(row("m1", "user_message", "Hi", 0.0)), running = false, nowMs = NOW)
        assertFalse(ChatItem.Read in items)
        assertFalse(ChatItem.Typing in items)
    }

    @Test fun updatedBriefsResultsAndScheduledChecksDrawAsCardsAndLines() {
        val rows = listOf(
            row("m1", "user_message", OrchestratorBrief.updated("New brief", "Go"), 0.0),
            row("m2", "user_message", "Result: sent to “X” on MacBook\n\nResult: refused: no machine “studio”.", 1.0),
            row("m3", "user_message", OrchestratorBrief.scheduledCheckPrompt, 2.0),
        )
        val items = OrchestratorChat.items(rows, running = false, nowMs = NOW)
        assertTrue(ChatItem.Brief("brief-m1", "New brief", true) in items)
        assertTrue(items.any { it is ChatItem.Bubble && it.fromUser && it.text == "Go" })
        assertTrue(items.any { it is ChatItem.Line && it.text == "Sent to “X” on MacBook" })
        assertTrue(items.any { it is ChatItem.Line && it.text == "Scheduled check while CandyWrapper was closed" })
    }

    @Test fun eventLinesNameThreadsWithColonIds() {
        val (text, thread) = OrchestratorChat.eventLine("Event: “Counter” finished a turn (t3-thread://v1/mcp:32a7b575-7401). Hands off: report only, never prompt it.")
        assertEquals("“Counter” finished a turn", text)
        assertEquals("mcp:32a7b575-7401", thread)
    }

    @Test fun imagesToolsPagesAndMemoryShowInPlace() {
        val image = ChatAttachment("image", "att-1", "shot.png", "image/png", 10)
        val rows = listOf(
            row("m1", "user_message", OrchestratorChat.ATTACHMENT_ONLY_PROMPT, 0.0, attachments = listOf(image)),
            row("t1", "dynamic_tool", "", 0.5, run = "r1", title = "t3_thread_launch"),
            row("t2", "dynamic_tool", "", 0.6, run = "r1", page = HtmlPage("att-2", "Chart"), title = "html_render"),
            row("m2", "assistant_message", "Saved.\n```orchestrator-memory\nnote: plan\n1. Ship\n```", 1.0, run = "r1"),
        )
        val items = OrchestratorChat.items(rows, running = false, nowMs = NOW)
        assertEquals(listOf("time", "image", "read", "line:Used 2 tools: t3_thread_launch, html_render:", "html:Chart", "bot^$", "memory"), kinds(items))
        assertEquals(listOf("plan"), (items.last() as ChatItem.Memory).notes)
    }

    @Test fun htmlRenderOutputsBecomePages() {
        val item = WireJson.parseToJsonElement(
            """{"id":"i1","type":"dynamic_tool","status":"completed","toolName":"mcp__t3-code__html_render","output":{"content":[{"type":"text","text":"{\"htmlRender\":{\"attachmentId\":\"att-9\",\"title\":\" Sales \",\"height\":480}}"}]}}""",
        ) as JsonObject
        assertEquals(HtmlPage("att-9", "Sales"), TurnRow.fromItem(item)?.htmlPage)
        val failed = WireJson.parseToJsonElement("""{"id":"i2","type":"dynamic_tool","status":"completed","toolName":"html_render","output":{"isError":true,"structuredContent":{"htmlRender":{"attachmentId":"a","title":"x","height":1}}}}""") as JsonObject
        assertNull(TurnRow.fromItem(failed)?.htmlPage)
    }
}

class OrchestratorOverviewTest {
    @Test fun needsAndCrewCardsGroupLikeTheMac() {
        val threads = listOf(
            orchThread("orch") { copy(status = "running", pill = org.anikodev.candywrapper.core.model.StatusPill.WORKING) },
            orchThread("ask") { copy(pendingRequestId = "r1", pendingKind = "command", pendingCreatedAt = 2, pill = org.anikodev.candywrapper.core.model.StatusPill.APPROVAL) },
            orchThread("q") { copy(pendingRequestId = "r2", pendingKind = "user_input", pendingCreatedAt = 1, pill = org.anikodev.candywrapper.core.model.StatusPill.INPUT) },
            orchThread("done") { copy(pill = org.anikodev.candywrapper.core.model.StatusPill.READY, latestRunCompletedAt = 5) },
            orchThread("never") { copy(pill = org.anikodev.candywrapper.core.model.StatusPill.NONE) },
            orchThread("child") { copy(parentThreadId = "orch") },
        )
        val needs = OrchestratorOverview.needs(threads)
        assertEquals(listOf("Command approval" to true, "Has a question for you" to false), needs.map { it.label to it.isApproval })
        val crew = OrchestratorOverview.crew(threads, emptyList(), OrchestratorConfig(handsOff = setOf("mini:done")), "mini:orch", NOW)
        assertEquals(listOf("ask", "q", "done", "never"), crew.map { it.threadId })
        assertEquals("Done", crew[2].state)
        assertEquals("Hands off", crew[2].note)
        assertEquals(listOf(org.anikodev.candywrapper.core.orchestrator.CrewCard.Group.RUNNING, org.anikodev.candywrapper.core.orchestrator.CrewCard.Group.RUNNING,
            org.anikodev.candywrapper.core.orchestrator.CrewCard.Group.FINISHED, org.anikodev.candywrapper.core.orchestrator.CrewCard.Group.STOPPED), crew.map { it.group })
    }
}

/** The protocol in docs/orchestrator.md, "Two apps, one Orchestrator". */
class RelayOwnershipTest {
    private fun task(enabled: Boolean, updatedAt: String = "2026-10-24T10:00:00.000Z") =
        CheckTask("task-1", enabled, updatedAt, "orch", "p1", "Orchestrator check", OrchestratorBrief.scheduledCheckPrompt)
    private val mine = ScheduleClaim("task-1", "2026-10-24T10:05:00.000Z")

    private fun decide(task: CheckTask?, claim: ScheduleClaim? = null, foreground: Boolean = true, active: Boolean = true, startedHere: Boolean = false, closedCheck: Boolean = true, known: Boolean = true) =
        RelayOwnership.decide(active, foreground, known, task, claim, startedHere, closedCheck)

    @Test fun aTaskThatIsOnMeansNobodyRelaysSoThePhoneTakesOver() {
        val d = decide(task(enabled = true))
        assertFalse(d.relaying)
        assertEquals(Action.Claim("task-1"), d.action)
    }

    @Test fun thePhoneRelaysOnlyWhileTheTaskCarriesItsOwnWrite() {
        assertTrue(decide(task(false, mine.updatedAt), mine).relaying)
        // The Mac opened: its upsert wrote a new updatedAt, so the phone stops and forgets its hold.
        val mac = decide(task(false, "2026-10-24T10:06:00.000Z"), mine)
        assertFalse(mac.relaying)
        assertEquals(Reason.OTHER_APP, mac.reason)
        assertTrue(mac.forgetClaim)
        assertEquals(Action.None, mac.action)
    }

    @Test fun aTaskTheMacTurnedOffIsTheMacsAndNoTaskMeansNoSignal() {
        assertEquals(Reason.OTHER_APP, decide(task(false)).reason)
        assertEquals(Reason.NO_TASK, decide(null).reason)
        assertEquals(Reason.WAITING, decide(task(true), known = false).reason)
        assertEquals(Action.None, decide(task(true), known = false).action)
    }

    @Test fun leavingTheFrontHandsTheTaskBackOnlyWhenTheHoldIsStillMine() {
        val held = decide(task(false, mine.updatedAt), mine, foreground = false)
        assertEquals(Action.Release("task-1"), held.action)
        assertFalse(held.relaying)
        // Someone else wrote meanwhile: leave their task alone.
        val lost = decide(task(false, "2026-10-24T11:00:00.000Z"), mine, foreground = false)
        assertEquals(Action.None, lost.action)
        assertTrue(lost.forgetClaim)
        assertEquals(Action.Release("task-1"), decide(task(false, mine.updatedAt), mine, active = false).action)
    }

    @Test fun aThreadThisPhoneStartedGetsItsOwnTask() {
        assertEquals(Action.Create, decide(null, startedHere = true).action)
        // With the check off, its task stays off and stays held across the background.
        val off = decide(task(false, mine.updatedAt), mine, foreground = false, startedHere = true, closedCheck = false)
        assertEquals(Action.None, off.action)
        assertFalse(off.forgetClaim)
        assertTrue(decide(task(false, mine.updatedAt), mine, startedHere = true, closedCheck = false).relaying)
    }

    @Test fun theCheckTaskIsFoundByItsPromptAndThread() {
        val tasks = CheckTask.list(WireJson.parseToJsonElement(
            """{"tasks":[{"id":"a","enabled":false,"updatedAt":"2026-10-24T09:00:00.000Z","threadId":"orch","projectId":"p1","title":"Orchestrator check","prompt":"${OrchestratorBrief.scheduledCheckPrompt}"},
               {"id":"b","enabled":true,"updatedAt":"2026-10-24T10:00:00.000Z","threadId":"orch","projectId":"p1","title":"Orchestrator check","prompt":"${OrchestratorBrief.scheduledCheckPrompt}"},
               {"id":"c","enabled":true,"updatedAt":"2026-10-24T11:00:00.000Z","threadId":"orch","projectId":"p1","title":"Nightly","prompt":"Run the tests"}]}""",
        ) as JsonObject)
        assertEquals("b", CheckTask.forThread(tasks, "orch")?.id)
        assertNull(CheckTask.forThread(tasks, "other"))
    }

    @Test fun atAHandoffThePhoneSendsOnlyWhatTheOtherAppNeverSaw() {
        fun e(at: Long) = OrchestratorEvent("mini", "t$at", "t", OrchestratorEvent.Kind.FINISHED, Permission.Free, atMs = at)
        assertTrue(RelayOwnership.keepAtHandoff(e(999), 1_000))
        assertFalse(RelayOwnership.keepAtHandoff(e(1_000), 1_000))
        assertFalse(RelayOwnership.keepAtHandoff(e(0), 1_000))
    }

    @Test fun aTurnAnotherAppAnsweredRunsNothingHere() {
        val reply = ProjectionMessage("a1", "assistant", "```orchestrator-call\n{\"tool\":\"list\"}\n```", false, 100, "r1")
        val result = ProjectionMessage("u2", "user", "Result: listed 3 threads on 2 machines", false, 140, null)
        val older = ProjectionMessage("u0", "user", "Result: archived “X” on mini", false, 50, null)
        assertTrue(RelayOwnership.alreadyAnswered(listOf(older, reply, result), listOf(reply)))
        assertFalse(RelayOwnership.alreadyAnswered(listOf(older, reply), listOf(reply)))
        assertEquals(listOf(reply), RelayOwnership.replies(listOf(older, reply, result), "r1", emptySet()))
        assertTrue(RelayOwnership.replies(listOf(reply), "r1", setOf("a1")).isEmpty())
    }

    @Test fun onlyATurnSeenRunningOnOneConnectionEnds() {
        val w = TurnWatch()
        assertFalse(w.ended(1, false))
        assertFalse(w.ended(1, true))
        assertTrue(w.ended(1, false))
        assertFalse(w.ended(1, false))
        // A new socket: a turn that was running before it doesn't count.
        assertFalse(w.ended(1, true))
        assertFalse(w.ended(2, false))
    }
}

/** The shell keeps the relay's fields only while the Orchestrator is on, and only a snapshot makes them complete. */
class OrchestratorShellTrackingTest {
    private fun item(json: String) = WireJson.parseToJsonElement(json) as JsonObject
    private val snapshot = item("""{"kind":"snapshot","snapshot":{"snapshotSequence":5,"projects":[],"threads":[
        {"id":"t1","projectId":"p1","title":"One","status":"running","latestRunId":"r1","lineage":{"parentThreadId":null}},
        {"id":"s1","projectId":"p1","title":"Sub","status":"running","lineage":{"parentThreadId":"t1","relationshipToParent":"subagent"}}]}}""")

    @Test fun snapshotsFillTheRelayViewAndDeltasKeepItCurrent() {
        val shell = org.anikodev.candywrapper.core.sync.ShellProjection(envId = "mini")
        shell.apply(snapshot)
        assertTrue(shell.raw.isEmpty() && !shell.rawComplete)
        shell.trackRaw = true
        shell.apply(snapshot)
        assertTrue(shell.rawComplete)
        assertEquals(setOf("t1", "s1"), shell.raw.keys)
        assertEquals("r1", shell.raw.getValue("t1").latestRunId)
        assertTrue(shell.raw.getValue("s1").subagent)
        val generation = shell.generation
        assertTrue(shell.takeRawChange())
        shell.apply(item("""{"kind":"thread.updated","sequence":6,"location":"active","thread":{"id":"t1","projectId":"p1","title":"One","status":"completed"}}"""))
        assertEquals("completed", shell.raw.getValue("t1").status)
        assertTrue(shell.takeRawChange())
        shell.apply(item("""{"kind":"thread.updated","sequence":7,"location":"archived","thread":{"id":"t1","projectId":"p1","title":"One","status":"completed","archivedAt":"2026-10-24T10:00:00.000Z"}}"""))
        assertFalse("t1" in shell.raw)
        assertEquals(generation, shell.generation)
        shell.trackRaw = false
        assertTrue(shell.raw.isEmpty() && !shell.rawComplete)
    }
}
