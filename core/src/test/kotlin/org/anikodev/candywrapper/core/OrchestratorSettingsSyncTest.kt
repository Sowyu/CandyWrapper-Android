package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.long
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.orchestrator.CheckTask
import org.anikodev.candywrapper.core.orchestrator.OrchestratorBrief
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorMachine
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync as Sync
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync.Entry
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync.Stamp

/**
 * The shared settings document (CandyWrapper docs/orchestrator.md, "Settings on every device"), ported from the Mac's
 * `OrchestratorSettingsSyncTests`: read, write, migration, two devices editing at once, duplicate tasks and a move.
 */
class OrchestratorSettingsSyncTest {
    private fun doc(json: String) = assertNotNull(Sync.Document.parse(json))

    @Test
    fun readsTheDocumentIntoTheSettings() {
        val d = doc("""
            {"format":"candywrapper.orchestrator-settings","version":1,"settings":{
              "name":{"value":"Ada","at":2000,"by":"phone"},
              "crewLimit":{"value":6,"at":2000,"by":"phone"},
              "closedCheckMinutes":{"value":null,"at":2000,"by":"phone"},
              "botShape":{"value":3,"at":2000,"by":"phone"},
              "handsOff":{"value":["env-a:t2","env-a:t1"],"at":2000,"by":"phone"},
              "notebook.rules":{"value":"Ask before merging.","at":2000,"by":"phone"},
              "models":{"value":[{"id":"cheap","label":"Cheap","instanceId":"claudeAgent","model":"claude-sonnet-5-5",
                "options":[{"id":"effort","value":"medium"}],"useWhen":"Docs","notes":"","isDefault":true}],"at":2000,"by":"phone"}
            }}
        """.trimIndent())
        val m = Sync.merge(OrchestratorConfig(), emptyMap(), listOf(d), "env-a")
        assertEquals("Ada", m.config.name)
        assertEquals(6, m.config.crewLimit)
        assertEquals(0, m.config.closedCheckMinutes)
        assertEquals(3, m.config.botShape)
        assertEquals(setOf("env-a:t1", "env-a:t2"), m.config.handsOff)
        assertEquals("Ask before merging.", m.config.note("rules"))
        assertEquals(listOf("claude-sonnet-5-5"), m.config.models.map { it.model })
        assertEquals(OptionValue("effort", text = "medium"), m.config.models.single().options.single())
        assertEquals(Stamp(2000, "phone"), m.stamps["name"])
        // Nothing of this phone's is newer, so nothing goes back.
        assertNull(m.write)
    }

    /** A null machine is the machine the document lives on, never this device's primary. */
    @Test
    fun aNullMachineIsTheDocumentsOwn() {
        val d = doc("""{"format":"candywrapper.orchestrator-settings","version":1,"settings":{"environmentId":{"value":null,"at":5,"by":"mac"}}}""")
        assertEquals("env-b", Sync.merge(OrchestratorConfig(), emptyMap(), listOf(d), "env-b").config.environmentId)
    }

    @Test
    fun wrongTypesAndBadCountsAreIgnored() {
        val d = doc("""
            {"format":"candywrapper.orchestrator-settings","version":1,"settings":{
              "name":{"value":7,"at":9,"by":"mac"},"crewLimit":{"value":0,"at":9,"by":"mac"},
              "notebook.plan":{"value":["x"],"at":9,"by":"mac"},"askFirstMinutes":{"value":15,"at":9,"by":"mac"},
              "closedCheckMinutes":{"value":0,"at":9,"by":"mac"},"botColor":{"value":"5","at":9,"by":"mac"},
              "models":{"value":[{"id":"x","label":"X","instanceId":"codex","model":"m"}],"at":9,"by":"mac"},
              "handsOff":{"value":["env-a:t1",3],"at":9,"by":"mac"}
            }}
        """.trimIndent())
        val m = Sync.merge(OrchestratorConfig(), emptyMap(), listOf(d), "env-a")
        assertEquals("Orchestrator", m.config.name)
        assertEquals(4, m.config.crewLimit)
        assertEquals("", m.config.note("plan"))
        assertEquals(15, m.config.askFirstMinutes)
        assertEquals(30, m.config.closedCheckMinutes)
        assertNull(m.config.botColor)
        assertEquals(OrchestratorConfig().models, m.config.models)
        assertEquals(emptySet(), m.config.handsOff)
    }

    @Test
    fun badPromptsAreNotDocuments() {
        assertNull(Sync.Document.parse("""{"format":"something-else","version":1,"settings":{}}"""))
        assertNull(Sync.Document.parse(OrchestratorBrief.scheduledCheckPrompt))
        assertNull(Sync.Document.parse("{"))
        assertNull(Sync.Document.parse("""{"format":"candywrapper.orchestrator-settings","settings":{}}"""))
        // One entry without a stamp rejects the document, as the Mac's decoder does.
        assertNull(Sync.Document.parse("""{"format":"candywrapper.orchestrator-settings","version":1,"settings":{"name":{"value":"A","by":"x"}}}"""))
        assertNull(Sync.Document.parse("""{"format":"candywrapper.orchestrator-settings","version":1,"settings":{"name":{"at":1,"by":"x"}}}"""))
    }

    /** A null for a setting with a default means the default, as the Mac's decoder reads it. */
    @Test
    fun aNullMeansTheDefault() {
        val start = OrchestratorConfig(name = "Ada", crewLimit = 2, botShape = 4)
        val d = doc("""{"format":"candywrapper.orchestrator-settings","version":1,"settings":{
            "name":{"value":null,"at":9,"by":"mac"},"crewLimit":{"value":null,"at":9,"by":"mac"},"botShape":{"value":null,"at":9,"by":"mac"}}}""")
        val m = Sync.merge(start, emptyMap(), listOf(d), "env-a")
        assertEquals(OrchestratorConfig.DEFAULT_NAME, m.config.name)
        assertEquals(4, m.config.crewLimit)
        assertNull(m.config.botShape)
    }

    @Test
    fun aLocalEditIsStampedAndWritten() {
        val old = OrchestratorConfig()
        val c = old.copy(name = "Ada").withNote("plan", "1. Ship")
        val stamps = Sync.stamps(old, c, emptyMap(), 3000, "phone")
        assertEquals(setOf("name", "notebook.plan"), stamps.keys)
        val m = Sync.merge(c, stamps, emptyList(), "env-a")
        val d = assertNotNull(m.write)
        assertEquals(Entry(JsonPrimitive("Ada"), Stamp(3000, "phone")), d.settings["name"])
        assertEquals(JsonPrimitive("1. Ship"), d.settings["notebook.plan"]?.value)
        assertNull(d.settings["crewLimit"])
        // Written the way the Mac's JSONEncoder with sorted keys writes it.
        assertEquals(
            """{"format":"candywrapper.orchestrator-settings","settings":{"name":{"at":3000,"by":"phone","value":"Ada"},""" +
                """"notebook.plan":{"at":3000,"by":"phone","value":"1. Ship"}},"version":1}""",
            d.prompt,
        )
        // What it writes reads back the same on the other side.
        val back = doc(d.prompt)
        assertEquals(d, back)
        assertEquals("Ada", Sync.merge(OrchestratorConfig(), emptyMap(), listOf(back), "env-a").config.name)
    }

    /** Relay bookkeeping, claims and the toggle stay on the device. */
    @Test
    fun deviceStateIsNeverStamped() {
        val old = OrchestratorConfig()
        val c = old.copy(
            enabled = true, relayedAt = mapOf("env-a:t1" to 5L), machines = mapOf("env-a" to OrchestratorMachine("t1", "d")),
            savedMemoryMessages = listOf("m1"), relayWorkers = setOf("env-a:w"),
        )
        assertTrue(Sync.stamps(old, c, emptyMap(), 1, "phone").isEmpty())
    }

    /** A null machine goes up as the machine it means here, since "the primary" differs per device. */
    @Test
    fun theMachineGoesUpByName() {
        val old = OrchestratorConfig(environmentId = "env-b")
        val c = old.copy(environmentId = null)
        val stamps = Sync.stamps(old, c, emptyMap(), 10, "phone")
        val d = assertNotNull(Sync.merge(c, stamps, emptyList(), "env-b", home = "env-a").write)
        assertEquals(JsonPrimitive("env-a"), d.settings["environmentId"]?.value)
        // Without a separate home, the machine the document lives on.
        assertEquals(JsonPrimitive("env-b"), Sync.merge(c, stamps, emptyList(), "env-b").write!!.settings["environmentId"]?.value)
    }

    @Test
    fun migrationUploadsOnlyWhatYouChanged() {
        val c = OrchestratorConfig(enabled = true, name = "Ada", crewLimit = 2, botColor = 5, machines = mapOf("env-a" to OrchestratorMachine("t", "d")))
        val stamps = Sync.migrationStamps(c, 4000, "phone")
        assertEquals(setOf("name", "crewLimit", "botColor"), stamps.keys)
        // The Mac already set the prompt and the worker limit earlier: your newer values win, its prompt stays.
        val mac = doc("""
            {"format":"candywrapper.orchestrator-settings","version":1,"settings":{
              "prompt":{"value":"Be brief.","at":1000,"by":"mac"},"crewLimit":{"value":8,"at":1000,"by":"mac"}
            }}
        """.trimIndent())
        val m = Sync.merge(c, stamps, listOf(mac), "env-a")
        assertEquals("Be brief.", m.config.prompt)
        assertEquals(2, m.config.crewLimit)
        assertEquals("Ada", m.config.name)
        val d = assertNotNull(m.write)
        assertEquals(JsonPrimitive(2), d.settings["crewLimit"]?.value)
        assertEquals(JsonPrimitive("Be brief."), d.settings["prompt"]?.value)
        assertEquals(JsonPrimitive(5), d.settings["botColor"]?.value)
        // A device with nothing changed takes everything and writes nothing.
        val fresh = Sync.merge(OrchestratorConfig(), Sync.migrationStamps(OrchestratorConfig(), 9, "phone2"), listOf(d), "env-a")
        assertEquals("Ada", fresh.config.name)
        assertNull(fresh.write)
        // The phone's "off" (0) differs from the default 30, and goes up as null.
        val off = Sync.migrationStamps(OrchestratorConfig(closedCheckMinutes = 0), 9, "phone")
        assertEquals(setOf("closedCheckMinutes"), off.keys)
        assertEquals(JsonNull, Sync.merge(OrchestratorConfig(closedCheckMinutes = 0), off, emptyList(), "env-a").write!!.settings["closedCheckMinutes"]?.value)
    }

    /**
     * Two devices edit at once, each before reading the other's write: the later edit wins per setting, other settings
     * both survive, and both end up the same whichever document they read last.
     */
    @Test
    fun twoDevicesEditingAtOnceAgree() {
        val base = Sync.merge(OrchestratorConfig(), emptyMap(), emptyList(), "env-a")
        assertNull(base.write)
        val mac = base.config.copy(name = "Mac name", crewLimit = 3)
        val phone = base.config.copy(name = "Phone name", askFirstMinutes = 30)
        val macStamps = Sync.stamps(base.config, mac, emptyMap(), 5000, "mac")
        val phoneStamps = Sync.stamps(base.config, phone, emptyMap(), 5001, "phone")
        val macDoc = assertNotNull(Sync.merge(mac, macStamps, emptyList(), "env-a").write)
        val phoneDoc = assertNotNull(Sync.merge(phone, phoneStamps, emptyList(), "env-a").write)
        // The Mac reads the phone's document: it takes the newer name, keeps its worker limit and writes that back.
        val macAfter = Sync.merge(mac, macStamps, listOf(phoneDoc), "env-a")
        assertEquals("Phone name", macAfter.config.name)
        assertEquals(30, macAfter.config.askFirstMinutes)
        assertEquals(3, macAfter.config.crewLimit)
        val merged = assertNotNull(macAfter.write)
        // The phone reads that and has nothing to add.
        val phoneAfter = Sync.merge(phone, phoneStamps, listOf(merged), "env-a")
        assertEquals(macAfter.config, phoneAfter.config)
        assertNull(phoneAfter.write)
        // Read the other way round, the result is the same.
        assertEquals(macAfter.config, Sync.merge(phone, phoneStamps, listOf(macDoc), "env-a").config)
        // A tie goes to the larger device id on both sides, whichever order the documents come in.
        val a = Entry(JsonPrimitive("A"), Stamp(7, "aaa"))
        val b = Entry(JsonPrimitive("B"), Stamp(7, "bbb"))
        val docs = listOf(Sync.Document(mapOf("name" to a)), Sync.Document(mapOf("name" to b)))
        assertEquals("B", Sync.merge(OrchestratorConfig(), emptyMap(), docs, "env-a").config.name)
        assertEquals("B", Sync.merge(OrchestratorConfig(), emptyMap(), docs.reversed(), "env-a").config.name)
        // A local stamp in the tie loses to the larger id too.
        assertEquals("B", Sync.merge(OrchestratorConfig(name = "A"), mapOf("name" to Stamp(7, "aaa")), docs.takeLast(1), "env-a").config.name)
        val mine = Sync.merge(OrchestratorConfig(name = "C"), mapOf("name" to Stamp(7, "ccc")), docs.takeLast(1), "env-a")
        assertEquals("C", mine.config.name)
        assertEquals(JsonPrimitive("C"), mine.write!!.settings["name"]?.value)
    }

    /** Two documents on one machine become one; keys a newer client wrote survive. */
    @Test
    fun duplicatesMergeAndUnknownKeysSurvive() {
        val one = Sync.Document(mapOf("name" to Entry(JsonPrimitive("Old"), Stamp(1, "mac")), "futureThing" to Entry(JsonPrimitive(true), Stamp(1, "phone"))))
        val two = Sync.Document(mapOf("name" to Entry(JsonPrimitive("New"), Stamp(2, "phone"))))
        val m = Sync.merge(OrchestratorConfig(), emptyMap(), listOf(one, two), "env-a")
        assertEquals("New", m.config.name)
        val d = assertNotNull(m.write)
        assertEquals(JsonPrimitive(true), d.settings["futureThing"]?.value)
        assertEquals(JsonPrimitive("New"), d.settings["name"]?.value)
    }

    @Test
    fun writingKeepsUnknownKeys() {
        val mac = doc("""{"format":"candywrapper.orchestrator-settings","version":1,"settings":{"futureThing":{"value":{"b":1,"a":[true]},"at":1,"by":"mac"}}}""")
        val c = OrchestratorConfig(crewLimit = 7)
        val d = assertNotNull(Sync.merge(c, mapOf("crewLimit" to Stamp(5, "phone")), listOf(mac), "env-a").write)
        assertEquals(mac.settings["futureThing"], d.settings["futureThing"])
        assertTrue(d.prompt.contains(""""futureThing":{"at":1,"by":"mac","value":{"a":[true],"b":1}}"""))
    }

    // Tasks

    private fun task(id: String, prompt: String, title: String = Sync.TITLE) = buildJsonObject {
        put("id", id); put("title", title); put("prompt", prompt); put("enabled", false)
        put("schedule", buildJsonObject { put("type", "interval"); put("everyMs", 86_400_000) })
        put("projectId", "p-$id"); put("threadId", JsonNull)
        put("workspaceStrategy", buildJsonObject { put("type", "existing_worktree"); put("worktreePath", "/w/$id") })
        put("modelSelection", buildJsonObject { put("instanceId", "codex"); put("model", "gpt-6.1-sol") })
        put("runtimeMode", "full-access"); put("interactionMode", "plan")
        put("createdBy", "user"); put("creationSource", "web")
        put("createdAt", "2026-10-24T00:00:00.000Z"); put("updatedAt", "2026-10-24T00:00:00.000Z")
        put("nextRunAt", JsonNull); put("lastRunAt", JsonNull); put("lastRunStatus", "never"); put("lastRunError", JsonNull); put("runCount", 0)
    }

    private fun list(vararg tasks: JsonObject) = buildJsonObject { put("tasks", JsonArray(tasks.toList())) }

    /**
     * Several tasks on one machine: the merge goes to the smallest readable id and the other readable ones are
     * deleted. A task the phone can't read may be a newer version's, so it is never overwritten or deleted.
     */
    @Test
    fun duplicateTasksKeepTheSmallestId() {
        val older = Sync.Document(mapOf("name" to Entry(JsonPrimitive("Old"), Stamp(1, "mac")))).prompt
        val newer = Sync.Document(mapOf("name" to Entry(JsonPrimitive("New"), Stamp(2, "phone")))).prompt
        val json = list(
            task("c", newer), task("a", older),
            task("check", OrchestratorBrief.scheduledCheckPrompt, title = "Orchestrator check"),
            task("b", "edited by hand in T3"),
        )
        val tasks = Sync.tasks(json)
        assertEquals(listOf("a", "b", "c"), tasks.map { it.id })
        val m = Sync.merge(OrchestratorConfig(), emptyMap(), tasks.mapNotNull { it.document }, "env-a")
        assertEquals("New", m.config.name)
        val plan = Sync.plan(m, tasks, null)
        val up = assertNotNull(plan.upsert)
        assertEquals("a", up.str("id"))
        assertEquals(listOf("c"), plan.delete)
        // An update keeps the task's own placement.
        assertEquals("p-a", up.str("projectId"))
        assertEquals("/w/a", up.obj("workspaceStrategy")!!.str("worktreePath"))
        assertEquals("gpt-6.1-sol", up.obj("modelSelection")!!.str("model"))
        assertEquals("full-access", up.str("runtimeMode"))
        assertEquals("plan", up.str("interactionMode"))
        assertEquals("New", Sync.Document.parse(up.str("prompt")!!)!!.settings["name"]!!.value.let { (it as JsonPrimitive).content })
        // An unreadable task with a smaller id beside one current document: both are left alone.
        val same = Sync.Document(mapOf("name" to Entry(JsonPrimitive("Ada"), Stamp(3, "mac")))).prompt
        val two = Sync.tasks(list(task("b", same), task("a", "garbage")))
        val m2 = Sync.merge(OrchestratorConfig(), emptyMap(), two.mapNotNull { it.document }, "env-a")
        assertNull(m2.write)
        assertEquals(Sync.Plan(null, emptyList()), Sync.plan(m2, two, null))
        // A local edit still lands on the readable task, never on the unreadable one.
        val edited = Sync.merge(OrchestratorConfig(name = "Bea"), mapOf("name" to Stamp(9, "phone")), two.mapNotNull { it.document }, "env-a")
        val plan3 = Sync.plan(edited, two, Sync.NewTask("p1", ModelSelection("codex", "gpt-6.1-sol")))
        assertEquals("b", plan3.upsert?.str("id"))
        assertEquals(emptyList<String>(), plan3.delete)
        // Only unreadable tasks: nothing is written, and no second task is made beside them.
        val unreadable = Sync.tasks(list(task("a", "garbage"), task("b", """{"format":"candywrapper.orchestrator-settings","version":2}""")))
        val m3 = Sync.merge(OrchestratorConfig(name = "Bea"), mapOf("name" to Stamp(9, "phone")), emptyList(), "env-a")
        assertEquals(Sync.Plan(null, emptyList()), Sync.plan(m3, unreadable, Sync.NewTask("p1", ModelSelection("codex", "gpt-6.1-sol"))))
        // One current task: nothing to send.
        val one = Sync.tasks(list(task("a", same)))
        assertEquals(Sync.Plan(null, emptyList()), Sync.plan(Sync.merge(OrchestratorConfig(), emptyMap(), one.mapNotNull { it.document }, "env-a"), one, null))
    }

    /** The upsert matches `ScheduledTaskUpsertInput`: off, a day's interval, no thread, the spec's defaults for a new one. */
    @Test
    fun aNewTaskMatchesTheContract() {
        val stamps = mapOf("crewLimit" to Stamp(5, "phone"))
        val m = Sync.merge(OrchestratorConfig(crewLimit = 2), stamps, emptyList(), "env-a")
        val selection = ModelSelection("claudeAgent", "claude-opus-5-5", listOf(OptionValue("effort", text = "medium")))
        assertEquals(Sync.Plan(null, emptyList()), Sync.plan(m, emptyList(), null))
        val up = assertNotNull(Sync.plan(m, emptyList(), Sync.NewTask("p1", selection)).upsert)
        val allowed = setOf("id", "requireExisting", "commandId", "title", "prompt", "enabled", "schedule", "projectId", "threadId",
            "workspaceStrategy", "modelSelection", "runtimeMode", "interactionMode", "createdBy", "creationSource")
        assertTrue(allowed.containsAll(up.keys), "${up.keys - allowed}")
        assertNull(up["id"])
        assertNull(up["threadId"])
        assertEquals(Sync.TITLE, up.str("title"))
        assertEquals(JsonPrimitive(false), up["enabled"])
        assertEquals("interval", up.obj("schedule")!!.str("type"))
        assertEquals(86_400_000L, up.obj("schedule")!!.long("everyMs"))
        assertEquals("p1", up.str("projectId"))
        assertEquals(buildJsonObject { put("type", "root") }, up["workspaceStrategy"])
        assertEquals(selection.toJson(), up["modelSelection"])
        assertEquals("approval-required", up.str("runtimeMode"))
        assertEquals("default", up.str("interactionMode"))
        assertEquals("web", up.str("creationSource"))
        assertEquals(buildJsonObject { put("id", "a") }, org.anikodev.candywrapper.core.sync.Commands.deleteScheduledTask("a"))
    }

    /**
     * After a move the old machine gets the document too, naming the new machine, so devices still reading there
     * follow it. With no task there it makes one.
     */
    @Test
    fun aMoveWritesTheOldMachine() {
        val old = OrchestratorConfig(environmentId = "env-a", name = "Ada")
        val moved = old.copy(environmentId = "env-b")
        val stamps = Sync.stamps(old, moved, mapOf("name" to Stamp(1, "mac")), 50, "phone")
        assertEquals(Stamp(50, "phone"), stamps["environmentId"])
        val there = Sync.Document(mapOf(
            "name" to Entry(JsonPrimitive("Ada"), Stamp(1, "mac")),
            "environmentId" to Entry(JsonPrimitive("env-a"), Stamp(1, "mac")),
        ))
        val tasks = Sync.tasks(list(task("t1", there.prompt)))
        val m = Sync.merge(moved, stamps, tasks.mapNotNull { it.document }, "env-a", home = "env-b")
        assertEquals("env-b", m.config.environmentId)
        val up = assertNotNull(Sync.plan(m, tasks, null).upsert)
        assertEquals("t1", up.str("id"))
        assertEquals(JsonPrimitive("env-b"), Sync.Document.parse(up.str("prompt")!!)!!.settings["environmentId"]?.value)
        // The Mac reading the old machine follows the move.
        val mac = Sync.merge(old, mapOf("name" to Stamp(1, "mac"), "environmentId" to Stamp(1, "mac")), listOf(Sync.Document.parse(up.str("prompt")!!)!!), "env-a")
        assertEquals("env-b", mac.config.environmentId)
    }

    /** The settings task is never the Mac's "while closed" signal, even if it named a thread. */
    @Test
    fun theSettingsTaskIsNotACheck() {
        val settings = task("s", Sync.Document(mapOf("name" to Entry(JsonPrimitive("Ada"), Stamp(1, "mac")))).prompt)
        val named = JsonObject(settings + ("threadId" to JsonPrimitive("t1")) + ("id" to JsonPrimitive("s2")))
        val tasks = CheckTask.list(list(settings, named))
        assertEquals(2, tasks.size)
        assertTrue(tasks.none { it.isCheck })
        assertNull(CheckTask.forThread(tasks, "t1"))
    }

    /**
     * A document exactly as the Mac writes it (sorted keys, compact, every kind of value plus a key this version
     * doesn't know) decodes and encodes back byte for byte, and the phone's own encoding of each value it reads
     * equals the Mac's.
     */
    @Test
    fun theMacsDocumentRoundTrips() {
        val by = "4c1fe0a2-3b7d-4c56-9a8e-0d2f1b6c7e90"
        fun e(value: String) = """{"at":1792800000000,"by":"$by","value":$value}"""
        val mac = """{"format":"candywrapper.orchestrator-settings","settings":{""" +
            """"askFirstMinutes":${e("0")},""" +
            """"botColor":${e("5")},""" +
            """"botShape":${e("null")},""" +
            """"closedCheckMinutes":${e("null")},""" +
            """"crewLimit":${e("6")},""" +
            """"environmentId":${e("\"env-a\"")},""" +
            """"futureThing":{"at":1792800000001,"by":"ffffffff-0000-4000-8000-000000000000","value":{"a":[1,true,null],"b":"x"}},""" +
            """"handsOff":${e("[\"env-a:t1\",\"env-b:t2\"]")},""" +
            """"homeProjectId":${e("null")},""" +
            """"models":${e("""[{"id":"cheap","instanceId":"claudeAgent","isDefault":true,"label":"Cheap","model":"claude-sonnet-5-5","notes":"Fast / cheap","options":[{"id":"effort","value":"medium"},{"id":"fastMode","value":true}],"useWhen":"Docs and \"quotes\""},{"id":"heavy","instanceId":"codex","isDefault":false,"label":"Heavy","model":"gpt-6.1-sol","notes":"","options":[],"useWhen":""}]""")},""" +
            """"name":${e("\"Ada\"")},""" +
            """"notebook.crew-rules":${e("\"\"")},""" +
            """"notebook.digest":${e("\"\"")},""" +
            """"notebook.plan":${e("\"1. Ship\\n2. Rest\"")},""" +
            """"notebook.rules":${e("\"Ask before merging. Ünïcode ✓\"")},""" +
            """"prompt":${e("\"You are {{name}} on {{machine}}.\\n\\tTabs\"")}""" +
            """},"version":1}"""
        val d = doc(mac)
        assertEquals(mac, d.prompt)
        // The phone takes every value.
        val m = Sync.merge(OrchestratorConfig(), emptyMap(), listOf(d), "env-a")
        assertNull(m.write)
        val c = m.config
        assertEquals("Ada", c.name)
        assertEquals(0, c.askFirstMinutes)
        assertEquals(0, c.closedCheckMinutes)
        assertEquals(5, c.botColor)
        assertEquals(listOf(OptionValue("effort", text = "medium"), OptionValue("fastMode", flag = true)), c.models[0].options)
        assertEquals("1. Ship\n2. Rest", c.note("plan"))
        // Its own encoding of what it read is the Mac's, value for value.
        val mine = Sync.values(c, "env-a")
        for (key in Sync.keys) assertEquals(d.settings[key]?.value, mine[key], key)
        // A later edit on the phone writes the same document with only that entry changed.
        val edited = c.copy(crewLimit = 9)
        val stamps = Sync.stamps(c, edited, m.stamps, 1792800000500, "0a0a0a0a-0000-4000-8000-000000000000")
        val back = assertNotNull(Sync.merge(edited, stamps, listOf(d), "env-a").write)
        assertEquals(
            mac.replace(""""crewLimit":${e("6")}""", """"crewLimit":{"at":1792800000500,"by":"0a0a0a0a-0000-4000-8000-000000000000","value":9}"""),
            back.prompt,
        )
    }

    @Test
    fun stateKeepsTheDeviceAndStamps() {
        val s = Sync.State("4c1fe0a2-3b7d-4c56-9a8e-0d2f1b6c7e90", mapOf("name" to Stamp(1, "x")))
        val json = org.anikodev.candywrapper.core.json.WireJson.encodeToString(Sync.State.serializer(), s)
        assertEquals(s, org.anikodev.candywrapper.core.json.WireJson.decodeFromString(Sync.State.serializer(), json))
    }
}
