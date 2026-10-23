package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.InteractionModeChoice
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.model.ServerInfo
import org.anikodev.candywrapper.core.model.UsageWindow
import org.anikodev.candywrapper.core.model.answer
import org.anikodev.candywrapper.core.sync.Commands
import org.anikodev.candywrapper.core.sync.Delivery
import org.anikodev.candywrapper.core.sync.ThreadProjection
import org.anikodev.candywrapper.core.sync.UploadedAttachment

class ComposerTest {
    private fun json(s: String) = WireJson.parseToJsonElement(s) as JsonObject

    /** The fields a recorded command had, minus the ids each action mints. */
    private fun shape(o: JsonObject) = o.filterKeys { it != "commandId" && it != "messageId" && it != "threadId" }

    @Test
    fun sendMatchesTheRecordedCommand() {
        val c = Commands.sendMessage("t", "Use your shell tool", titleSeed = "Count files")
        assertEquals("message.dispatch", c.str("type"))
        assertEquals("user", c.str("createdBy"))
        assertEquals("mobile", c.str("creationSource"))
        assertEquals(JsonArray(emptyList()), c["attachments"])
        assertEquals("start_immediately", c.obj("dispatchMode")!!.str("type"))
        assertEquals("Count files", c.str("titleSeed"))
        assertNull(c["deliveryIntent"])
        assertEquals(36, c.str("commandId")!!.length)
        // A fresh id per action.
        assertTrue(Commands.sendMessage("t", "x").str("commandId") != c.str("commandId"))
    }

    @Test
    fun sendDuringARunQueuesOrSteers() {
        // An older server: the client picks the mode.
        assertEquals("queue_after_active", Commands.sendMessage("t", "x", activeRunId = "r").obj("dispatchMode")!!.str("type"))
        val steer = Commands.sendMessage("t", "x", activeRunId = "r", delivery = Delivery.STEER).obj("dispatchMode")!!
        assertEquals("steer_active", steer.str("type"))
        assertEquals("r", steer.str("targetRunId"))
        // A server that resolves delivery itself gets the intent.
        val auto = Commands.sendMessage("t", "x", activeRunId = "r", serverResolvesDelivery = true)
        assertEquals("start_immediately", auto.obj("dispatchMode")!!.str("type"))
        assertEquals("auto", auto.str("deliveryIntent"))
        val queue = Commands.sendMessage("t", "x", activeRunId = "r", delivery = Delivery.QUEUE, serverResolvesDelivery = true)
        assertEquals("queue_after_active", queue.obj("dispatchMode")!!.str("type"))
        assertNull(queue["deliveryIntent"])
    }

    @Test
    fun attachmentsAndModelEncode() {
        val c = Commands.sendMessage("t", "look", listOf(UploadedAttachment("att-1", "a.png", "image/png", 12)),
            model = ModelSelection("claudeAgent", "claude-opus-5-5", listOf(OptionValue("effort", text = "high"), OptionValue("fastMode", flag = true))))
        val a = (c["attachments"] as JsonArray).single() as JsonObject
        assertEquals("image", a.str("type"))
        assertEquals("att-1", a.str("id"))
        assertEquals(JsonPrimitive(12), a["sizeBytes"])
        assertEquals(
            json("""{"instanceId":"claudeAgent","model":"claude-opus-5-5","options":[{"id":"effort","value":"high"},{"id":"fastMode","value":true}]}"""),
            c.obj("modelSelection"),
        )
    }

    @Test
    fun otherCommandsMatchTheContract() {
        assertEquals(json("""{"type":"run.interrupt","runId":"r","holdQueue":true}"""), JsonObject(shape(Commands.interrupt("t", "r"))))
        assertEquals(json("""{"type":"runtime-request.respond","requestId":"q","decision":"accept"}"""), JsonObject(shape(Commands.approve("t", "q", "accept"))))
        assertEquals(json("""{"type":"runtime-request.respond","requestId":"q","answers":{"a":"Yes","b":["x","y"]}}"""),
            JsonObject(shape(Commands.answer("t", "q", mapOf("a" to "Yes", "b" to listOf("x", "y"))))))
        assertEquals(json("""{"type":"queued-run.cancel","runId":"r"}"""), JsonObject(shape(Commands.cancelQueued("t", "r"))))
        assertEquals(json("""{"type":"thread.visit","visitedAt":"2026-10-09T09:12:47.701Z"}"""), JsonObject(shape(Commands.visit("t", 1791537167701))))
        assertEquals(json("""{"type":"thread.metadata.update","title":"New name"}"""), JsonObject(shape(Commands.rename("t", "  New name "))))
        assertEquals(json("""{"type":"thread.archive"}"""), JsonObject(shape(Commands.archive("t"))))
        // contracts orchestrationV2.ts thread.settle / unsettle / snooze / unsnooze / pin / unpin.
        assertEquals(json("""{"type":"thread.settle"}"""), JsonObject(shape(Commands.settle("t"))))
        assertEquals(json("""{"type":"thread.unsettle","reason":"user"}"""), JsonObject(shape(Commands.unsettle("t"))))
        assertEquals(json("""{"type":"thread.snooze","snoozedUntil":"2026-10-09T09:12:47.701Z"}"""), JsonObject(shape(Commands.snooze("t", 1791537167701))))
        assertEquals(json("""{"type":"thread.unsnooze","reason":"user"}"""), JsonObject(shape(Commands.unsnooze("t"))))
        assertEquals(json("""{"type":"thread.pin"}"""), JsonObject(shape(Commands.pin("t"))))
        assertEquals(json("""{"type":"thread.unpin"}"""), JsonObject(shape(Commands.unpin("t"))))
        val create = Commands.createThread("t", "p", "New thread", ModelSelection("claudeAgent", "claude-haiku-5-5"), "approval-required", "default")
        assertEquals(json("""{"type":"thread.create","createdBy":"user","creationSource":"mobile","projectId":"p","title":"New thread",
            "modelSelection":{"instanceId":"claudeAgent","model":"claude-haiku-5-5"},"runtimeMode":"approval-required","interactionMode":"default",
            "branch":null,"worktreePath":null}"""), JsonObject(shape(create)))
        // A provider change is a switch unless the server resolves it.
        assertEquals("provider.switch", Commands.setModel("t", "codex", ModelSelection("claudeAgent", "m"), false).str("type"))
        assertEquals("thread.model-selection.set", Commands.setModel("t", "claudeAgent", ModelSelection("claudeAgent", "m"), false).str("type"))
        assertEquals("thread.model-selection.set", Commands.setModel("t", "codex", ModelSelection("claudeAgent", "m"), true).str("type"))
    }

    @Test
    fun planModeIsOfferedOnlyWhenTheSettingAndTheProviderAllowIt() {
        val claude = ServerInfo.parse(json("""{"providers":[{"instanceId":"c","driver":"claudeAgent","models":[]},{"instanceId":"x","showInteractionModeToggle":false,"models":[]}]}"""))
        assertEquals(InteractionModeChoice(true, "plan"), InteractionModeChoice.resolve(true, claude.provider("c"), "plan"))
        // Off by default: a thread left in plan mode sends its next turn in default mode.
        assertEquals(InteractionModeChoice(false, "default"), InteractionModeChoice.resolve(false, claude.provider("c"), "plan"))
        assertEquals(InteractionModeChoice(false, "default"), InteractionModeChoice.resolve(true, claude.provider("x"), "plan"))
        assertEquals(InteractionModeChoice(false, "default"), InteractionModeChoice.resolve(true, null, "plan"))
    }

    @Test
    fun serverInfoReadsTheRecordedConfig() {
        val frames = Transcripts.load("01-basic")
        val id = Transcripts.requestId(frames, "server.getConfig")!!
        val exit = frames.first { it.dir == "recv" && it.body.str("_tag") == "Exit" && it.body.str("requestId") == id }
        val info = ServerInfo.parse(exit.body.obj("exit")!!.obj("value")!!)
        assertTrue(info.serverResolvesDelivery)
        assertTrue(info.threadSettlement && info.threadSnooze && info.threadPinning)
        assertFalse(ServerInfo.parse(json("{}")).threadSnooze)
        val claude = assertNotNull(info.provider("claudeAgent"))
        val model = claude.models.first()
        val effort = model.options.first { it.id == "effort" }
        assertEquals("select", effort.type)
        assertTrue(effort.choices.any { it.isDefault })
        assertNotNull(info.modelForNewThread(null))
    }

    @Test
    fun modelSelectionReadsBothOptionForms() {
        val array = ModelSelection.from(json("""{"instanceId":"c","model":"m","options":[{"id":"effort","value":"max"},{"id":"fast","value":true}]}"""))!!
        assertEquals("max", array.option("effort")!!.text)
        assertEquals(true, array.option("fast")!!.flag)
        val legacy = ModelSelection.from(json("""{"provider":"c","model":"m","options":{"effort":"low"}}"""))!!
        assertEquals("c", legacy.instanceId)
        assertEquals("low", legacy.option("effort")!!.text)
        assertEquals("high", array.withOption(OptionValue("effort", text = "high")).option("effort")!!.text)
    }

    @Test
    fun recordedQueueShowsThenCancels() {
        val frames = Transcripts.load("02-turn")
        val stream = Transcripts.requestId(frames, "orchestration.subscribeThread")!!
        val p = ThreadProjection("14fc5e86-9f62-49ce-8f5d-42a343d29a8b")
        var sawQueued = false
        var sawStoppable = false
        for (values in Transcripts.chunks(frames, stream)) for (v in values) {
            p.apply(v as JsonObject)
            val q = p.queued()
            // run.created lands before the message.updated that carries the text.
            if (q.isNotEmpty() && q.single().text.isNotEmpty()) {
                sawQueued = true
                assertEquals("Then say done.", q.single().text)
                assertEquals(1, q.single().position)
            }
            if (p.interruptibleRunId() != null) sawStoppable = true
        }
        assertTrue(sawQueued)
        assertTrue(sawStoppable)
        // Cancelled, then interrupted: nothing queued, nothing to stop.
        assertTrue(p.queued().isEmpty())
        assertNull(p.interruptibleRunId())
        assertEquals("claudeAgent", p.meta.model?.instanceId)
    }

    @Test
    fun recordedApprovalIsPendingUntilAnswered() {
        val frames = Transcripts.load("03-turn2")
        val stream = Transcripts.requestId(frames, "orchestration.subscribeThread")!!
        val p = ThreadProjection("c4ff4c86-9db4-4d8a-a846-a1493c31e49b")
        val seen = mutableListOf<String>()
        for (values in Transcripts.chunks(frames, stream)) for (v in values) {
            p.apply(v as JsonObject)
            p.pending().forEach { if (it.requestId !in seen) seen += it.requestId }
        }
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.startsWith("runtime-request:") })
        assertTrue(p.pending().isEmpty())
    }

    @Test
    fun questionsAndUnresumableRequests() {
        val p = ThreadProjection("t")
        p.apply(json("""{"kind":"event","sequence":1,"event":{"type":"turn-item.updated","threadId":"t","payload":{"id":"i","threadId":"t","type":"user_input_request",
            "ordinal":1000003,"status":"waiting","requestId":"rq","questions":[{"id":"q1","header":"Pick","question":"Which?","options":[{"label":"A","description":"first"},{"label":"B","description":"second","value":"b"}],"multiSelect":true}]}}}"""))
        val pending = p.pending().single()
        assertTrue(pending.isQuestion)
        val q = pending.row.questions!!.single()
        assertEquals(listOf("A", "b"), q.options.map { it.answerValue })
        assertTrue(q.multiSelect)
        // After a server restart the request cannot be answered, so it is not offered.
        p.apply(json("""{"kind":"event","sequence":2,"event":{"type":"runtime-request.updated","threadId":"t","payload":{"id":"rq","status":"pending","responseCapability":{"type":"not_resumable","reason":"restart"}}}}"""))
        assertTrue(p.pending().isEmpty())
        // The cache keeps it hidden.
        assertTrue(ThreadProjection("t").apply { restore(p.toCache()) }.pending().isEmpty())
    }

    @Test
    fun answersFollowT3sRules() {
        val q = org.anikodev.candywrapper.core.model.Question("q", "", "Which?", multiSelect = false)
        assertNull(q.answer(emptyList(), null))
        assertEquals("a", q.answer(listOf("a", "b"), null))
        assertEquals("typed", q.answer(listOf("a"), "typed"))
        assertEquals(listOf("a", "b"), q.copy(multiSelect = true).answer(listOf("a", "b"), "  "))
        assertEquals("a", q.copy(allowCustomAnswer = false).answer(listOf("a"), "typed"))
        // An editable initial answer can be sent empty.
        assertEquals("", q.copy(initialAnswer = "").answer(emptyList(), null))
    }

    @Test
    fun olderPagesMergeOnce() {
        val p = ThreadProjection("t")
        p.apply(json("""{"kind":"snapshot","snapshotSequence":5,"hasMoreHistory":true,"historyCursor":"c1","projection":{"thread":{"title":"T","modelSelection":{"instanceId":"codex","model":"gpt"},"runtimeMode":"auto","interactionMode":"plan"},
            "runs":[],"messages":[],"runtimeRequests":[],"checkpoints":[{"id":"ck","appRunOrdinal":3}],
            "visibleTurnItems":[{"visibility":"local","sourceThreadId":"t","item":{"id":"u3","threadId":"t","type":"user_message","ordinal":3000001,"status":"completed","text":"third"}}]}}"""))
        assertEquals("plan", p.meta.interactionMode)
        assertEquals(3, p.checkpointTurn("ck"))
        val page = json("""{"snapshotSequence":5,"nextCursor":"c0","hasMoreHistory":true,"items":[
            {"visibility":"local","sourceThreadId":"t","item":{"id":"u1","threadId":"t","type":"user_message","ordinal":1000001,"status":"completed","text":"first"}},
            {"visibility":"local","sourceThreadId":"t","item":{"id":"u3","threadId":"t","type":"user_message","ordinal":3000001,"status":"completed","text":"stale"}}]}""")
        // A page for a cursor that moved on is dropped.
        assertFalse(p.prependHistory("old", page))
        assertTrue(p.prependHistory("c1", page))
        assertEquals(listOf("first", "third"), p.rows().map { it.text })
        assertEquals("c0", p.historyCursor)
        // The cache keeps the settings; a cache without them asks for a snapshot.
        assertEquals(5, ThreadProjection("t").apply { restore(p.toCache()) }.sequence)
        assertEquals(0, ThreadProjection("t").apply { restore(p.toCache().copy(meta = null)) }.sequence)
        assertTrue(p.hasMoreHistory)
        assertTrue(p.prependHistory("c0", json("""{"items":[],"nextCursor":null,"hasMoreHistory":false}""")))
        assertFalse(p.hasMoreHistory)
    }

    @Test
    fun providerCardReadsStatusAccountAndTheMostUsedWindow() {
        val p = ServerInfo.parse(json("""{"providers":[{"instanceId":"c","driver":"claudeAgent","status":"warning","message":"Update ready","models":[],
            "auth":{"status":"authenticated","email":" a@b.c ","label":"Claude Max"},
            "usageLimits":{"checkedAt":"2026-10-23T00:00:00.000Z","windows":[{"id":"5h","kind":"session","label":"5-hour","usedPercent":12.4},{"id":"w","kind":"weekly","label":"Weekly","usedPercent":61.6}]}}]}""")).providers.single()
        assertEquals("warning", p.status)
        assertEquals("a@b.c", p.authEmail)
        assertEquals("Claude Max", p.authLabel)
        assertEquals(UsageWindow("Weekly", 62), p.usage)
        val bare = ServerInfo.parse(json("""{"providers":[{"instanceId":"x","models":[],"usageLimits":{"windows":[{"label":"5-hour","usedPercent":5}],"unavailable":{"reason":"unsupported"}}}]}""")).providers.single()
        assertEquals(null, bare.usage)
        assertEquals("ready", bare.status)
    }
}
