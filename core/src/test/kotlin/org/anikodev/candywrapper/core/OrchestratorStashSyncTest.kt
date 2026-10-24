package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorStashEntry
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync as Sync
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync.Stamp

/**
 * The Orchestrator's stash in the settings document (CandyWrapper docs/orchestrator.md, "Settings on every device"),
 * after the Mac's `OrchestratorSettingsSyncTests`: one `stash.<id>` key per prompt, null once it goes, pruned after 30 days.
 */
class OrchestratorStashSyncTest {
    private val day = 86_400_000L
    private val t0 = 1_792_800_000_000L

    /** A local edit, stamped the way `OrchestratorStore.update` stamps it. */
    private fun edit(config: OrchestratorConfig, stamps: Map<String, Stamp>, now: Long, device: String, change: (OrchestratorConfig) -> OrchestratorConfig) =
        change(config).let { it to Sync.stamps(config, it, stamps, now, device) }

    @Test
    fun createdAtIsTheMacsTimestamp() {
        assertEquals("2026-10-24T10:01:00.000Z", OrchestratorStashEntry.timestamp(1_792_836_060_000L))
    }

    @Test
    fun twoDevicesStashingAtOnceBothKeepTheirs() {
        val (phone, phoneStamps) = edit(OrchestratorConfig(), emptyMap(), t0, "phone") { it.stashing("Ship the notes", t0, id = "a") }
        val (mac, macStamps) = edit(OrchestratorConfig(), emptyMap(), t0 + 1, "mac") { it.stashing("Fix the test", t0 + 60_000, id = "b") }
        assertEquals(setOf("stash.a"), phoneStamps.keys)

        val fromPhone = assertNotNull(Sync.merge(phone, phoneStamps, emptyList(), "env-a", now = t0).write)
        assertEquals(
            JsonObject(mapOf("prompt" to JsonPrimitive("Ship the notes"), "createdAt" to JsonPrimitive("2026-10-24T00:00:00.000Z"))),
            fromPhone.settings["stash.a"]?.value,
        )
        val both = assertNotNull(Sync.merge(mac, macStamps, listOf(fromPhone), "env-a", now = t0).write)
        val phoneAfter = Sync.merge(phone, phoneStamps, listOf(both), "env-a", now = t0)
        // Newest first by createdAt.
        assertEquals(listOf("b", "a"), phoneAfter.config.stash.map { it.id })
        assertNull(phoneAfter.write)
    }

    @Test
    fun aRestoreWritesNullThatTheOtherDeviceFollowsAndAMonthLaterItGoes() {
        val (phone, phoneStamps) = edit(OrchestratorConfig(), emptyMap(), t0, "phone") { it.stashing("Ship the notes", t0, id = "a").stashing("Later", t0 + 1, id = "b") }
        val doc = assertNotNull(Sync.merge(phone, phoneStamps, emptyList(), "env-a", now = t0).write)
        val mac = Sync.merge(OrchestratorConfig(), emptyMap(), listOf(doc), "env-a", now = t0)
        assertEquals(listOf("b", "a"), mac.config.stash.map { it.id })

        // The Mac restores A: its key turns null with a new stamp, and the phone drops it.
        val (restored, restoredStamps) = edit(mac.config, mac.stamps, t0 + 5, "mac") { it.unstashing("a") }
        assertEquals(Stamp(t0 + 5, "mac"), restoredStamps["stash.a"])
        val afterRestore = assertNotNull(Sync.merge(restored, restoredStamps, listOf(doc), "env-a", now = t0 + 5).write)
        assertEquals(JsonNull, afterRestore.settings["stash.a"]?.value)
        val phoneSees = Sync.merge(phone, phoneStamps, listOf(afterRestore), "env-a", now = t0 + 6)
        assertEquals(listOf("b"), phoneSees.config.stash.map { it.id })

        // Within 30 days the null stays; after them it goes from the document and the stamps.
        val stillThere = Sync.merge(phoneSees.config, phoneSees.stamps, listOf(afterRestore), "env-a", now = t0 + 5 + 30 * day)
        assertNull(stillThere.write)
        assertEquals(Stamp(t0 + 5, "mac"), stillThere.stamps["stash.a"])
        val pruned = Sync.merge(phoneSees.config, phoneSees.stamps, listOf(afterRestore), "env-a", now = t0 + 6 + 30 * day)
        val written = assertNotNull(pruned.write)
        assertNull(written.settings["stash.a"])
        assertNull(pruned.stamps["stash.a"])
        assertNotNull(written.settings["stash.b"])
    }

    @Test
    fun theTwentyFirstPushesTheOldestOutAsNull() {
        var config = OrchestratorConfig()
        var stamps = emptyMap<String, Stamp>()
        repeat(21) { i ->
            val (c, s) = edit(config, stamps, t0 + i, "phone") { it.stashing("prompt $i", t0 + i * 1000L, id = "id$i") }
            config = c; stamps = s
        }
        assertEquals(OrchestratorStashEntry.LIMIT, config.stash.size)
        assertEquals("id20", config.stash.first().id)
        assertEquals("id1", config.stash.last().id)
        val doc = assertNotNull(Sync.merge(config, stamps, emptyList(), "env-a", now = t0 + 21).write)
        assertEquals(JsonNull, doc.settings["stash.id0"]?.value)
        assertEquals(20, doc.settings.count { (k, e) -> k.startsWith("stash.") && e.value !is JsonNull })
    }

    @Test
    fun aMalformedStashValueIsSkippedAndAnEqualCreatedAtSortsById() {
        val doc = assertNotNull(Sync.Document.parse("""
            {"format":"candywrapper.orchestrator-settings","version":1,"settings":{
              "stash.x":{"value":{"prompt":7},"at":5,"by":"phone"},
              "stash.y":{"value":"just text","at":5,"by":"phone"},
              "stash.z":{"value":{"prompt":"Fine","createdAt":"2026-10-24T10:00:00.000Z"},"at":5,"by":"phone"},
              "stash.w":{"value":{"prompt":"Also","createdAt":"2026-10-24T10:00:00.000Z"},"at":5,"by":"phone"}
            }}
        """.trimIndent()))
        assertEquals(listOf("z", "w"), Sync.merge(OrchestratorConfig(), emptyMap(), listOf(doc), "env-a", now = 6).config.stash.map { it.id })
    }
}
