package org.anikodev.candywrapper.core.orchestrator

import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.sync.Commands

/**
 * The Orchestrator's settings shared by every CandyWrapper client, the Mac app and this one, through one disabled
 * scheduled task on the Orchestrator's machine whose prompt holds them as JSON (CandyWrapper docs/orchestrator.md,
 * "Settings on every device"; the Mac's `OrchestratorSettingsSync`). Each setting carries when it was last set and by
 * which device, and the newer one wins, setting by setting, the same way on every side. Device-only state (the
 * toggle, each machine's thread, brief digest and claim, relay bookkeeping) stays on the phone.
 */
object OrchestratorSettingsSync {
    /** How a client finds the task: by this exact title, with the document as its prompt. */
    const val TITLE = "CandyWrapper Orchestrator settings"
    const val FORMAT = "candywrapper.orchestrator-settings"
    const val VERSION = 1
    /** The task is always off, so it never runs; the interval only has to be one T3 accepts. */
    const val EVERY_MS = 86_400_000L

    /** Every shared setting, by its key in the document. */
    val keys = listOf("name", "prompt", "environmentId", "homeProjectId", "crewLimit", "models", "closedCheckMinutes",
        "askFirstMinutes", "handsOff", "botShape", "botColor") + Notebook.names.map { "notebook.$it" }

    /**
     * Each stashed prompt is a key of its own, `stash.<id>`, so two devices stashing at once both keep theirs. A
     * restored or deleted one stays as null for [STASH_TOMBSTONE_MS], so devices that still list it drop it too, then goes.
     */
    const val STASH_PREFIX = "stash."
    const val STASH_TOMBSTONE_MS = 30L * 86_400_000

    private fun isStash(key: String) = key.startsWith(STASH_PREFIX)

    /** When a setting was last set and by which device. The later one wins; on a tie the larger device id does. */
    @Serializable
    data class Stamp(val at: Long, val by: String) {
        fun beats(other: Stamp) = if (at != other.at) at > other.at else by > other.by
    }

    data class Entry(val value: JsonElement, val at: Long, val by: String) {
        constructor(value: JsonElement, stamp: Stamp) : this(value, stamp.at, stamp.by)
        val stamp get() = Stamp(at, by)
    }

    /** What the phone keeps next to its config: its device id and a stamp per setting it knows. */
    @Serializable
    data class State(val device: String, val stamps: Map<String, Stamp> = emptyMap())

    /** The task's prompt. Keys this version doesn't know are kept as they are, so a newer client's settings survive. */
    data class Document(val settings: Map<String, Entry> = emptyMap(), val version: Int = VERSION) {
        /** Compact JSON with sorted keys, as the Mac's `JSONEncoder` with `.sortedKeys` writes it. */
        val prompt: String get() = sorted(JsonObject(mapOf(
            "format" to JsonPrimitive(FORMAT),
            "version" to JsonPrimitive(version),
            "settings" to JsonObject(settings.mapValues { (_, e) ->
                JsonObject(mapOf("value" to e.value, "at" to JsonPrimitive(e.at), "by" to JsonPrimitive(e.by)))
            }),
        ))).toString()

        companion object {
            /** Null for a prompt that isn't a settings document. Like the Mac's decoder, one malformed entry rejects it. */
            fun parse(prompt: String): Document? {
                val o = runCatching { Json.parseToJsonElement(prompt) }.getOrNull() as? JsonObject ?: return null
                if (o.str("format") != FORMAT) return null
                val version = o["version"]?.let(::int) ?: return null
                val raw = o["settings"] as? JsonObject ?: return null
                val settings = raw.mapValues { (_, e) ->
                    val entry = e as? JsonObject ?: return null
                    Entry(entry["value"] ?: return null, entry["at"]?.let(::long) ?: return null, entry.str("by") ?: return null)
                }
                return Document(settings, version)
            }
        }
    }

    /**
     * Every shared setting's value, as the Mac's `OrchestratorConfig` encodes it. [machine] stands in for a null
     * `environmentId`, which means a different machine on each device, so the document always names the machine.
     */
    fun values(config: OrchestratorConfig, machine: String? = null): Map<String, JsonElement> = buildMap {
        put("name", JsonPrimitive(config.name))
        put("prompt", JsonPrimitive(config.prompt))
        put("environmentId", JsonPrimitive(config.environmentId ?: machine))
        put("homeProjectId", JsonPrimitive(config.homeProjectId))
        put("crewLimit", JsonPrimitive(config.crewLimit))
        put("models", JsonArray(config.models.map(::rowJson)))
        // The phone keeps "off" as 0; the Mac and the document as null.
        put("closedCheckMinutes", if (config.closedCheckMinutes > 0) JsonPrimitive(config.closedCheckMinutes) else JsonNull)
        put("askFirstMinutes", JsonPrimitive(config.askFirstMinutes))
        put("handsOff", JsonArray(config.handsOff.sorted().map(::JsonPrimitive)))
        put("botShape", JsonPrimitive(config.botShape))
        put("botColor", JsonPrimitive(config.botColor))
        for (n in Notebook.names) put("notebook.$n", JsonPrimitive(config.note(n)))
        for (e in config.stash) put(STASH_PREFIX + e.id, JsonObject(mapOf("prompt" to JsonPrimitive(e.prompt), "createdAt" to JsonPrimitive(e.createdAt))))
    }

    /**
     * The config with one setting replaced; unchanged when the value has the wrong type or is out of range. A null
     * value means the default, as the Mac's decoder reads a null.
     */
    fun applying(key: String, value: JsonElement, config: OrchestratorConfig): OrchestratorConfig {
        val d = OrchestratorConfig()
        val isNull = value is JsonNull
        if (isStash(key)) {
            val id = key.removePrefix(STASH_PREFIX)
            val rest = config.stash.filterNot { it.id == id }
            if (isNull) return config.copy(stash = rest)
            val o = value as? JsonObject ?: return config
            val prompt = string(o["prompt"]) ?: return config
            val createdAt = string(o["createdAt"]) ?: return config
            if (id.isEmpty()) return config
            return config.copy(stash = OrchestratorStashEntry.sorted(rest + OrchestratorStashEntry(id, prompt, createdAt)))
        }
        if (key.startsWith("notebook.")) {
            val text = string(value) ?: return config
            return config.withNote(key.removePrefix("notebook."), text)
        }
        // A server value is another client's input: keep counts the controls and the scheduler accept.
        return when (key) {
            "name" -> if (isNull) config.copy(name = d.name) else string(value)?.let { config.copy(name = it) }
            "prompt" -> if (isNull) config.copy(prompt = d.prompt) else string(value)?.let { config.copy(prompt = it) }
            "environmentId" -> if (isNull) config.copy(environmentId = null) else string(value)?.let { config.copy(environmentId = it) }
            "homeProjectId" -> if (isNull) config.copy(homeProjectId = null) else string(value)?.let { config.copy(homeProjectId = it) }
            "crewLimit" -> if (isNull) config.copy(crewLimit = d.crewLimit) else int(value)?.takeIf { it >= 1 }?.let { config.copy(crewLimit = it) }
            "askFirstMinutes" -> if (isNull) config.copy(askFirstMinutes = d.askFirstMinutes) else int(value)?.takeIf { it >= 0 }?.let { config.copy(askFirstMinutes = it) }
            "closedCheckMinutes" -> if (isNull) config.copy(closedCheckMinutes = 0) else int(value)?.takeIf { it >= 1 }?.let { config.copy(closedCheckMinutes = it) }
            "models" -> if (isNull) config.copy(models = d.models) else rows(value)?.let { config.copy(models = it) }
            "handsOff" -> if (isNull) config.copy(handsOff = emptySet()) else strings(value)?.let { config.copy(handsOff = it.toSet()) }
            "botShape" -> if (isNull) config.copy(botShape = null) else int(value)?.let { config.copy(botShape = it) }
            "botColor" -> if (isNull) config.copy(botColor = null) else int(value)?.let { config.copy(botColor = it) }
            else -> null
        } ?: config
    }

    /** Stamps for what changed between two configs, as a local edit at [now]. */
    fun stamps(old: OrchestratorConfig, new: OrchestratorConfig, into: Map<String, Stamp>, now: Long, device: String): Map<String, Stamp> {
        val before = values(old)
        val after = values(new)
        // A stashed prompt that went is a change too: its key reads null from now on.
        val changed = (keys + (before.keys + after.keys).filter(::isStash)).filter { before[it] != after[it] }
        if (changed.isEmpty()) return into
        return into + changed.associateWith { Stamp(now, device) }
    }

    /**
     * The first sync on a device: every setting changed from its default counts as set now, so it goes up and
     * nothing you set is lost. Settings still at their default stay unstamped and take the document's value.
     */
    fun migrationStamps(config: OrchestratorConfig, now: Long, device: String): Map<String, Stamp> =
        stamps(OrchestratorConfig(), config, emptyMap(), now, device)

    /** [merged] is the document after the merge; [write] the same when it has to go up, else null. */
    data class Merge(val config: OrchestratorConfig, val stamps: Map<String, Stamp>, val write: Document?, val merged: Document)

    /**
     * Merges the documents found on a machine with this device's settings: per setting, the newer stamp wins.
     * [machine] is the machine the documents live on, which a null `environmentId` in them means. [home] is the
     * Orchestrator's machine on this device, which its null `environmentId` means; the same machine unless it just moved.
     */
    fun merge(
        config: OrchestratorConfig, stamps: Map<String, Stamp>, documents: List<Document>, machine: String, home: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Merge {
        val remote = LinkedHashMap<String, Entry>()
        for (doc in documents) for ((key, entry) in doc.settings) {
            val seen = remote[key]
            if (seen == null || entry.stamp.beats(seen.stamp)) remote[key] = entry
        }
        var next = config
        val nextStamps = stamps.toMutableMap()
        val out = LinkedHashMap(remote)
        val mine = values(config, home ?: machine)
        val stashKeys = (remote.keys + stamps.keys + mine.keys).filter(::isStash).toSortedSet()
        for (key in keys + stashKeys) {
            val local = stamps[key]
            val entry = remote[key]
            if (entry != null && (local == null || entry.stamp.beats(local))) {
                val value = if (key == "environmentId" && entry.value is JsonNull) JsonPrimitive(machine) else entry.value
                next = applying(key, value, next)
                nextStamps[key] = entry.stamp
            } else if (local != null && (entry == null || local.beats(entry.stamp))) {
                out[key] = Entry(mine[key] ?: JsonNull, local)
            }
        }
        // Removed stashed prompts are forgotten once every device had a month to see them go.
        for (key in stashKeys) {
            val e = out[key] ?: continue
            if (e.value is JsonNull && now - e.at > STASH_TOMBSTONE_MS) {
                out.remove(key)
                nextStamps.remove(key)
            }
        }
        val merged = Document(out)
        // Several documents (two devices made one at once) become one.
        val current = documents.size == 1 && documents[0].settings == out
        return Merge(next, nextStamps, if (current || (out.isEmpty() && documents.isEmpty())) null else merged, merged)
    }

    /** A settings task as `scheduledTasks.list` returns it, with what an update must send back unchanged. */
    data class SettingsTask(
        val id: String,
        val prompt: String,
        val projectId: String,
        val workspaceStrategy: JsonElement,
        val modelSelection: JsonElement,
        val runtimeMode: String,
        val interactionMode: String,
    ) {
        val document: Document? get() = Document.parse(prompt)
    }

    /** Every settings task in a `{tasks}` list, smallest id first, which is the one every client keeps. */
    fun tasks(result: JsonObject?): List<SettingsTask> = result?.arr("tasks").orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        if (o.str("title") != TITLE) return@mapNotNull null
        SettingsTask(
            id = o.str("id") ?: return@mapNotNull null,
            prompt = o.str("prompt").orEmpty(),
            projectId = o.str("projectId") ?: return@mapNotNull null,
            workspaceStrategy = o["workspaceStrategy"] as? JsonObject ?: return@mapNotNull null,
            modelSelection = o["modelSelection"] as? JsonObject ?: return@mapNotNull null,
            runtimeMode = o.str("runtimeMode") ?: return@mapNotNull null,
            interactionMode = o.str("interactionMode") ?: return@mapNotNull null,
        )
    }.sortedBy { it.id }

    /** Where a new task goes when the machine has none: the home project, else its first, on the default model row. */
    data class NewTask(val projectId: String, val modelSelection: ModelSelection)

    /** What to send: one `scheduledTasks.upsert`, then a `scheduledTasks.delete` per extra task. */
    data class Plan(val upsert: JsonObject?, val delete: List<String>)

    /**
     * The writes for [merge] against the machine's [tasks]: the result goes to the readable task with the smallest id
     * when it differs from the document or when more than one task was read, and the other readable ones are deleted.
     * A task this client can't read may come from a newer version, so it is never overwritten or deleted, and with
     * only unreadable tasks nothing is written. With no task at all, [newTask] says where to make one.
     */
    fun plan(merge: Merge, tasks: List<SettingsTask>, newTask: NewTask?): Plan {
        val none = Plan(null, emptyList())
        val readable = tasks.filter { it.document != null }
        if (readable.isEmpty() && tasks.isNotEmpty()) return none
        val doc = merge.write ?: merge.merged.takeIf { readable.size > 1 && it.settings.isNotEmpty() } ?: return none
        val keep = readable.firstOrNull()
        val upsert = if (keep != null) {
            Commands.upsertScheduledTask(keep.id, TITLE, doc.prompt, false, EVERY_MS, keep.projectId, keep.workspaceStrategy,
                keep.modelSelection, keep.runtimeMode, keep.interactionMode, CREATION_SOURCE)
        } else {
            newTask ?: return none
            Commands.upsertScheduledTask(null, TITLE, doc.prompt, false, EVERY_MS, newTask.projectId, buildJsonObject { put("type", "root") },
                newTask.modelSelection.toJson(), "approval-required", "default", CREATION_SOURCE)
        }
        return Plan(upsert, readable.drop(1).map { it.id })
    }

    /** The spec's `creationSource`, the same on every client so the task looks the same wherever it was made. */
    private const val CREATION_SOURCE = "web"

    // JSON, read as strictly as the Mac's Codable decoders.

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(e.map(::sorted))
        else -> e
    }

    private fun string(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    private fun long(v: JsonElement): Long? {
        val p = (v as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
        return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && abs(it) < 9.0e15 }?.toLong()
    }

    private fun int(v: JsonElement): Int? = long(v)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun strings(v: JsonElement): List<String>? = (v as? JsonArray)?.map { string(it) ?: return null }

    private fun rowJson(r: OrchestratorModelRow) = JsonObject(mapOf(
        "id" to JsonPrimitive(r.id),
        "label" to JsonPrimitive(r.label),
        "instanceId" to JsonPrimitive(r.instanceId),
        "model" to JsonPrimitive(r.model),
        // An option with neither a string nor a flag isn't one the Mac can read.
        "options" to JsonArray(r.options.mapNotNull { o ->
            val value = o.flag?.let(::JsonPrimitive) ?: o.text?.let(::JsonPrimitive) ?: return@mapNotNull null
            JsonObject(mapOf("id" to JsonPrimitive(o.id), "value" to value))
        }),
        "useWhen" to JsonPrimitive(r.useWhen),
        "notes" to JsonPrimitive(r.notes),
        "isDefault" to JsonPrimitive(r.isDefault),
    ))

    /** Every key is required, as in the Mac's synthesized decoder; one bad row rejects the list. */
    private fun rows(v: JsonElement): List<OrchestratorModelRow>? = (v as? JsonArray)?.map { e ->
        val o = e as? JsonObject ?: return null
        val options = (o["options"] as? JsonArray)?.map { opt ->
            val p = opt as? JsonObject ?: return null
            val id = string(p["id"]) ?: return null
            val value = p["value"]
            bool(value)?.let { OptionValue(id, flag = it) } ?: string(value)?.let { OptionValue(id, text = it) } ?: return null
        } ?: return null
        OrchestratorModelRow(
            id = string(o["id"]) ?: return null,
            label = string(o["label"]) ?: return null,
            instanceId = string(o["instanceId"]) ?: return null,
            model = string(o["model"]) ?: return null,
            options = options,
            useWhen = string(o["useWhen"]) ?: return null,
            notes = string(o["notes"]) ?: return null,
            isDefault = bool(o["isDefault"]) ?: return null,
        )
    }
}
