package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.model.StatusPill
import org.anikodev.candywrapper.core.orchestrator.MachineRef
import org.anikodev.candywrapper.core.orchestrator.OrchThread
import org.anikodev.candywrapper.core.orchestrator.OrchestratorBrief
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorEvent
import org.anikodev.candywrapper.core.orchestrator.OrchestratorEventBatch
import org.anikodev.candywrapper.core.orchestrator.OrchestratorModelRow
import org.anikodev.candywrapper.core.orchestrator.OrchestratorReply
import org.anikodev.candywrapper.core.orchestrator.Permission

// CandyWrapper's OrchestratorTests.swift, the parts that apply to the phone.

internal const val NOW = 1_800_000_000_000L
internal fun ago(minutes: Double) = NOW - (minutes * 60_000).toLong()

internal fun orchThread(id: String, env: String = "mini", change: OrchThread.() -> OrchThread = { this }) =
    OrchThread(id = id, envId = env, projectId = "p1", title = id, status = "idle", pill = StatusPill.NONE).change()

private fun event(id: String, kind: OrchestratorEvent.Kind = OrchestratorEvent.Kind.FINISHED) =
    OrchestratorEvent("mini", id, id, kind, Permission.Free)

class OrchestratorAskFirstTest {
    private fun of(t: OrchThread, handsOff: Boolean = false, window: Int = 10) = Permission.of(t, handsOff, window, NOW)

    @Test fun yourMessageInsideTheWindowAsksFirst() {
        assertEquals(Permission.AskFirst(3), of(orchThread("t") { copy(latestUserAuthoredMessageAt = ago(3.5), latestUserMessageAt = ago(3.5)) }))
    }

    @Test fun theWindowEndsAtTenMinutes() {
        assertEquals(Permission.Free, of(orchThread("t") { copy(latestUserAuthoredMessageAt = ago(10.0), latestUserMessageAt = ago(10.0)) }))
        assertEquals(Permission.AskFirst(9), of(orchThread("t") { copy(latestUserAuthoredMessageAt = ago(9.9), latestUserMessageAt = ago(9.9)) }))
    }

    @Test fun aLaterPromptFromAnotherThreadMeansYouAlreadySaidGo() {
        assertEquals(Permission.Free, of(orchThread("t") { copy(latestUserAuthoredMessageAt = ago(4.0), latestUserMessageAt = ago(1.0)) }))
    }

    @Test fun threadsYouNeverWroteInAreFree() {
        assertEquals(Permission.Free, of(orchThread("t") { copy(latestUserMessageAt = ago(1.0)) }))
    }

    @Test fun handsOffWinsAndAZeroWindowNeverAsks() {
        val t = orchThread("t") { copy(latestUserAuthoredMessageAt = ago(1.0), latestUserMessageAt = ago(1.0)) }
        assertEquals(Permission.HandsOff, of(t, handsOff = true))
        assertEquals(Permission.Free, of(t, window = 0))
    }

    @Test fun eventLinesCarryTheNote() {
        val e = OrchestratorEvent("env", "abc", "Release notes", OrchestratorEvent.Kind.FINISHED, Permission.AskFirst(3))
        assertEquals("Event: “Release notes” finished a turn (t3-thread://v1/abc). The user wrote there 3 min ago, so ask first.", e.line)
        val h = OrchestratorEvent("env", "abc", "X", OrchestratorEvent.Kind.NEEDS_YOU, Permission.HandsOff)
        assertTrue(h.line.endsWith("Hands off: report only, never prompt it."))
    }
}

class OrchestratorBatchTest {
    @Test fun eventsInsideThirtySecondsGoOutAsOneMessage() {
        val batch = OrchestratorEventBatch()
        assertEquals(NOW + 30_000, batch.add(listOf(event("a")), NOW))
        // Later events join the open window and arm nothing new.
        assertNull(batch.add(listOf(event("b", OrchestratorEvent.Kind.FAILED)), NOW + 12_000))
        assertNull(batch.add(listOf(event("c", OrchestratorEvent.Kind.NEEDS_YOU)), NOW + 29_000))
        assertNull(batch.flush(NOW + 29_900))
        val message = batch.flush(NOW + 30_000)!!
        assertEquals(3, message.split('\n').size)
        assertTrue(OrchestratorEvent.isEventMessage(message))
        assertTrue(batch.pending.isEmpty() && batch.deadline == null)
    }

    @Test fun anEventAfterTheWindowOpensANewOne() {
        val batch = OrchestratorEventBatch()
        batch.add(listOf(event("a")), NOW)
        batch.flush(NOW + 30_000)
        assertEquals(NOW + 75_000, batch.add(listOf(event("b")), NOW + 45_000))
        assertEquals(event("b").line, batch.flush(NOW + 75_000))
    }

    @Test fun aRepeatOfTheSameThreadAndKindSendsOneLine() {
        val batch = OrchestratorEventBatch()
        batch.add(listOf(event("a"), event("a")), NOW)
        batch.add(listOf(event("a"), event("a", OrchestratorEvent.Kind.NEEDS_YOU)), NOW + 5_000)
        assertEquals(2, batch.pending.size)
    }

    @Test fun eventsOfThreadsArchivedInsideTheWindowAreDropped() {
        val batch = OrchestratorEventBatch()
        batch.add(listOf(event("a"), event("b")), NOW)
        assertEquals(event("b").line, batch.flush(NOW + 30_000) { it.threadId == "b" })
        batch.add(listOf(event("a")), NOW + 40_000)
        assertNull(batch.flush(NOW + 70_000) { false })
        assertTrue(batch.deadline == null && batch.pending.isEmpty())
    }

    @Test fun nothingArmsWithoutEventsAndForceSendsEarly() {
        val batch = OrchestratorEventBatch()
        assertNull(batch.add(emptyList(), NOW))
        assertNull(batch.flush(NOW + 100_000))
        batch.add(listOf(event("a")), NOW)
        assertEquals(event("a").line, batch.flush(NOW + 1_000, force = true))
    }
}

class OrchestratorEventDiffTest {
    private fun diff(old: List<OrchThread>, new: List<OrchThread>, handsOff: Set<String> = emptySet()) =
        OrchestratorEvent.diff(old.associateBy { it.key }, new, "mini:orch", { it in handsOff }, 10, NOW)

    @Test fun aTurnEndingFailingOrAskingIsAnEvent() {
        val running = orchThread("t") { copy(status = "running") }
        assertEquals(listOf(OrchestratorEvent.Kind.FINISHED), diff(listOf(running), listOf(orchThread("t") { copy(status = "completed") })).map { it.kind })
        assertEquals(listOf(OrchestratorEvent.Kind.FAILED), diff(listOf(running), listOf(orchThread("t") { copy(status = "failed") })).map { it.kind })
        val asking = orchThread("t") { copy(status = "waiting", pendingRequestId = "r1", pendingKind = "command") }
        assertEquals(listOf(OrchestratorEvent.Kind.NEEDS_YOU), diff(listOf(running), listOf(asking)).map { it.kind })
        // The same request again is not new.
        assertTrue(diff(listOf(asking), listOf(asking)).isEmpty())
    }

    @Test fun theOrchestratorItsChildrenAndUnknownThreadsAreSkipped() {
        val old = listOf(orchThread("orch") { copy(status = "running") }, orchThread("child") { copy(status = "running", parentThreadId = "orch") })
        val new = listOf(orchThread("orch") { copy(status = "completed") }, orchThread("child") { copy(status = "completed", parentThreadId = "orch") },
            orchThread("fresh") { copy(status = "completed") })
        assertTrue(diff(old, new).isEmpty())
    }

    @Test fun eventsCarryHandsOffAndTheMachine() {
        val e = diff(listOf(orchThread("t") { copy(status = "running") }), listOf(orchThread("t") { copy(status = "completed") }), setOf("mini:t"))
        assertEquals(Permission.HandsOff, e.first().permission)
        val book = OrchestratorEvent("book", "w1", "Book thread", OrchestratorEvent.Kind.FINISHED, Permission.Free, machine = "MacBook")
        assertEquals("Event: “Book thread” on MacBook (machine book) finished a turn (t3-thread://v1/w1).", book.line)
    }
}

class OrchestratorBriefTest {
    private val config = OrchestratorConfig()

    private fun inputs(name: String = "Orchestrator", machines: List<MachineRef> = emptyList(), handsOff: List<String> = emptyList()) = OrchestratorBrief.Inputs(
        name, "mac-mini", listOf(OrchestratorBrief.Project("CandyWrapper", "p1", "/Users/me/CandyWrapper")), 4, config.models,
        mapOf("rules" to "Ask before starting more than two threads."), handsOff, 10, machines,
    )

    @Test fun placeholdersFillAndTheModelsTableLeadsWithTheDefault() {
        val brief = OrchestratorBrief.render(config.prompt, inputs(handsOff = listOf("“Release notes” (t3-thread://v1/abc)")))
        assertFalse(brief.contains("{{"))
        assertTrue(brief.contains("You are Orchestrator"))
        assertTrue(brief.contains("(mac-mini)"))
        assertTrue(brief.contains("- CandyWrapper (projectId p1): /Users/me/CandyWrapper"))
        assertTrue(brief.contains("Run at most 4 workers"))
        assertTrue(brief.contains("| Everyday work (default) | claudeAgent | claude-opus-5-5 | effort medium |"))
        assertTrue(brief.contains("| Audits | codex | gpt-6.1-sol | reasoningEffort medium |"))
        assertTrue(brief.contains("Ask before starting more than two threads."))
        assertTrue(brief.contains("- “Release notes” (t3-thread://v1/abc)"))
        assertTrue(brief.contains("check it with html_preview, then publish it with html_render before your reply"))
        assertTrue(brief.contains("AskUserQuestion on Claude, request_user_input on Codex"))
    }

    @Test fun briefsSplitBackOut() {
        assertEquals("B", OrchestratorBrief.split(OrchestratorBrief.first("B")).brief)
        val updated = OrchestratorBrief.split(OrchestratorBrief.updated("New brief\n\n# Limits\n- x", "Ship it"))
        assertEquals("New brief\n\n# Limits\n- x", updated.brief)
        assertTrue(updated.updated)
        assertEquals("Ship it", updated.message)
        assertNull(OrchestratorBrief.split("hello").brief)
    }

    /** The same FNV-1a 64 the Mac computes, so both apps fingerprint one brief alike. */
    @Test fun theDigestMatchesTheMac() {
        assertEquals("c0f185cfce8e7689", OrchestratorBrief.digest("Orchestrator"))
        assertNotEquals(OrchestratorBrief.digest("a"), OrchestratorBrief.digest("b"))
    }

    @Test fun configKeepsDefaultsForMissingKeysAndRoundTrips() {
        val c = WireJson.decodeFromString(OrchestratorConfig.serializer(), """{"enabled":true,"crewLimit":2,"futureKey":1}""")
        assertTrue(c.enabled)
        assertEquals(2, c.crewLimit)
        assertEquals(OrchestratorModelRow.defaults, c.models)
        assertEquals(30, c.closedCheckMinutes)
        assertEquals(10, c.askFirstMinutes)
        val off = c.copy(closedCheckMinutes = 0, botShape = 7)
        assertEquals(off, WireJson.decodeFromString(OrchestratorConfig.serializer(), Json.encodeToString(OrchestratorConfig.serializer(), off)))
    }

    @Test fun theDefaultRowsMatchTheSpec() {
        assertEquals(listOf("Heavy work", "Audits", "Everyday work", "Quick changes"), OrchestratorModelRow.defaults.map { it.label })
        assertEquals("Everyday work", OrchestratorConfig().defaultRow?.label)
        assertEquals(listOf(OptionValue("effort", "high")), OrchestratorModelRow.defaults[0].selection.options)
    }

    @Test fun anEmptyNameFallsBackAndARenameChangesTheFingerprint() {
        assertEquals("Orchestrator", OrchestratorConfig(name = "  ").displayName)
        assertEquals("Ada", OrchestratorConfig(name = " Ada ").displayName)
        assertTrue(OrchestratorBrief.render(config.prompt, inputs("Ada")).contains("You are Ada,"))
        assertNotEquals(OrchestratorBrief.fingerprint(config.prompt, inputs("Ada")), OrchestratorBrief.fingerprint(config.prompt, inputs()))
        // The notebook stays out of the fingerprint.
        assertEquals(OrchestratorBrief.fingerprint(config.prompt, inputs()), OrchestratorBrief.fingerprint(config.prompt, inputs().copy(notebook = emptyMap())))
    }

    @Test fun theBriefListsEveryMachineAndGoingOfflineSendsNothing() {
        val on = listOf(MachineRef("mini", "miniko", true), MachineRef("book", "MacBook", false))
        val off = listOf(on[0], on[1].copy(connected = false))
        val brief = OrchestratorBrief.render("You are {{name}}.", inputs(machines = off))
        assertTrue(brief.contains("- miniko (machine mini): you run here"))
        assertTrue(brief.contains("- MacBook (machine book), offline right now"))
        assertTrue(brief.contains("once 4 workers run across all machines"))
        assertFalse(OrchestratorBrief.render("x", inputs(machines = on.take(1))).contains("orchestrator-call"))
        assertEquals(OrchestratorBrief.fingerprint("x", inputs(machines = on)), OrchestratorBrief.fingerprint("x", inputs(machines = off)))
        assertNotEquals(OrchestratorBrief.fingerprint("x", inputs(machines = on)), OrchestratorBrief.fingerprint("x", inputs(machines = on.take(1))))
    }

    /** The relay honours the Hands off list in the thread's latest brief, whichever device wrote it. */
    @Test fun handsOffReadsBackOutOfABrief() {
        val brief = OrchestratorBrief.render("x", inputs(handsOff = listOf("“Notes” (t3-thread://v1/abc)", "“Site” (t3-thread://v1/mcp:9f) on MacBook (machine book)")))
        assertEquals(setOf(null to "abc", "book" to "mcp:9f"), OrchestratorBrief.handsOffIn(brief))
        assertTrue(OrchestratorBrief.handsOffIn(OrchestratorBrief.render("x", inputs())).isEmpty())
    }
}

class OrchestratorReplyTest {
    @Test fun planAndMemoryBlocksLeaveTheProse() {
        val reply = OrchestratorReply.parse(
            """
            Here's the plan.

            ```orchestrator-plan
            1. [x] Write the notes ([Scribe](t3-thread://v1/th-1)), Opus, 10 min
            2. Review them, Sol, 5 min
            ```

            ```orchestrator-memory
            note: rules
            Ask before starting threads.
            ```
            Say go.
            """.trimIndent(),
        )
        assertEquals("Here's the plan.\n\nSay go.", reply.text)
        val plan = reply.plan!!
        assertEquals(2, plan.size)
        assertTrue(plan[0].done)
        assertEquals("th-1", plan[0].threadId)
        assertEquals("Write the notes (Scribe), Opus, 10 min", plan[0].displayText)
        assertFalse(plan[1].done)
        assertNull(plan[1].threadId)
        assertEquals(listOf(OrchestratorReply.Memory("rules", "Ask before starting threads.")), reply.memories)
    }

    @Test fun anUnclosedBlockStaysHiddenAndOtherFencesStay() {
        val streaming = OrchestratorReply.parse("Plan:\n```orchestrator-plan\n1. Half")
        assertEquals("Plan:", streaming.text)
        assertNull(streaming.plan)
        assertEquals("```swift\nlet x = 1\n```", OrchestratorReply.parse("```swift\nlet x = 1\n```").text)
    }

    @Test fun digestAndUnknownNotes() {
        val r = OrchestratorReply.parse("```orchestrator-digest\nNeeds you: nothing\n```\n```orchestrator-memory\nnote: secrets\nx\n```")
        assertEquals("Needs you: nothing", r.digest)
        assertTrue(r.memories.isEmpty())
    }

    @Test fun planStepsLinkThreadsWithColonIds() {
        val plan = OrchestratorReply.parse("```orchestrator-plan\n1. Count ([Counter](t3-thread://v1/mcp:32a7))\n```").plan!!
        assertEquals("mcp:32a7", plan[0].threadId)
        assertEquals("Count (Counter)", plan[0].displayText)
    }
}
