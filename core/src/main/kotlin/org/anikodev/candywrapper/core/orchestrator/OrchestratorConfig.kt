package org.anikodev.candywrapper.core.orchestrator

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.stash.StashEntry

// The Orchestrator (CandyWrapper docs/orchestrator.md, this repo's docs/orchestrator.md): one ordinary T3 thread per
// machine that manages every thread through T3's own thread tools, plus what this phone relays. Everything here
// stays on the phone: the server sees normal threads, messages and one scheduled task.

/** One row of the Models list: a model from the live catalog and when the Orchestrator should pick it. */
@Serializable
data class OrchestratorModelRow(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val instanceId: String,
    val model: String,
    val options: List<OptionValue> = emptyList(),
    val useWhen: String = "",
    val notes: String = "",
    val isDefault: Boolean = false,
) {
    val selection: ModelSelection get() = ModelSelection(instanceId, model, options)

    companion object {
        /** The spec's four rows. A row whose model the live catalog lacks shows a warning and stays out of the brief. */
        val defaults = listOf(
            OrchestratorModelRow("heavy", "Heavy work", "claudeAgent", "claude-opus-5-5", listOf(OptionValue("effort", "high")),
                "Hard tasks: tricky bugs, concurrency, security, migrations, big changes across many files."),
            OrchestratorModelRow("audits", "Audits", "codex", "gpt-6.1-sol", listOf(OptionValue("reasoningEffort", "medium")),
                "Reviewing code or a branch, checking someone else's work, finding problems without changing anything."),
            OrchestratorModelRow("everyday", "Everyday work", "claudeAgent", "claude-opus-5-5", listOf(OptionValue("effort", "medium")),
                "Simpler tasks that still need good judgement: normal features, fixes and edits.", isDefault = true),
            OrchestratorModelRow("quick", "Quick changes", "codex", "gpt-6.1-sol", listOf(OptionValue("reasoningEffort", "high")),
                "Fast, quick changes that don't need the best quality code, like renames, config values and small one-file fixes."),
        )
    }
}

/**
 * A prompt saved from the Orchestrator's composer to send later (the Mac's `OrchestratorStashEntry`). Text only, since
 * it goes to every device through the settings document as `stash.<id>` (CandyWrapper docs/orchestrator.md,
 * "Settings on every device"). The thread composers' stash is a different one and stays on the phone.
 */
@Serializable
data class OrchestratorStashEntry(val id: String, val prompt: String, val createdAt: String) {
    /** As a thread stash entry, so the composer's stash list draws it. An unreadable `createdAt` reads as long ago. */
    fun asStashEntry() = StashEntry(id, runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L), prompt)

    companion object {
        /** `MAX_STASH_ENTRIES`, as the thread composers' stash. */
        const val LIMIT = 20

        /** `createdAt` as the Mac's `T3Timestamp` writes it: UTC with milliseconds. */
        private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun timestamp(ms: Long): String = format.format(Instant.ofEpochMilli(ms))

        /** Newest first by `createdAt`, then by id, so every device lists them the same way. */
        fun sorted(entries: List<OrchestratorStashEntry>): List<OrchestratorStashEntry> =
            entries.sortedWith(compareByDescending<OrchestratorStashEntry> { it.createdAt }.thenByDescending { it.id })
    }
}

/** The four notes the Orchestrator keeps. An empty note is absent. */
object Notebook {
    val names = listOf("rules", "crew-rules", "plan", "digest")
}

/**
 * This phone's hold on a machine's "while closed" task: the task and the `updatedAt` the server gave this phone's
 * own write. A later write by anyone else changes `updatedAt`, which is how the phone learns it lost the hold.
 */
@Serializable
data class ScheduleClaim(val taskId: String, val updatedAt: String)

/** What the phone remembers about the Orchestrator on one machine. */
@Serializable
data class OrchestratorMachine(
    /** The Orchestrator thread, by the id that machine's server knows. */
    val threadId: String,
    /** The fingerprint of the brief the thread last got from this phone; a different one goes out as "Updated brief". */
    val briefDigest: String,
    /** True when this phone started the thread. The Mac never adopts such a thread, so this phone owns its schedule. */
    val startedHere: Boolean = false,
    val claim: ScheduleClaim? = null,
)

/** The Orchestrator's settings, kept in the app's files and never in T3's settings. */
@Serializable
data class OrchestratorConfig(
    /** "Experimental Orchestrator features", Redesign only. */
    val enabled: Boolean = false,
    val name: String = DEFAULT_NAME,
    val prompt: String = OrchestratorBrief.defaultPrompt,
    /** The machine it runs on; null picks the machine where an Orchestrator thread was found, else the first. */
    val environmentId: String? = null,
    /** The project its thread lives in when this phone starts one; null is the machine's first project. */
    val homeProjectId: String? = null,
    val crewLimit: Int = 4,
    val models: List<OrchestratorModelRow> = OrchestratorModelRow.defaults,
    val notebook: Map<String, String> = emptyMap(),
    /** How often the scheduled task wakes it while no CandyWrapper is open; 0 is off. Used for a task this phone creates. */
    val closedCheckMinutes: Int = 30,
    /** How recently you must have written in a thread for it to ask before prompting there. */
    val askFirstMinutes: Int = 10,
    /** `env:thread` keys marked Hands off. */
    val handsOff: Set<String> = emptySet(),
    /** By environment id. */
    val machines: Map<String, OrchestratorMachine> = emptyMap(),
    /** Assistant rows whose memory blocks were saved already, newest last, so a reload saves nothing twice. */
    val savedMemoryMessages: List<String> = emptyList(),
    /** Its bot's shape and colour ordinals; null is the violet flower. */
    val botShape: Int? = null,
    val botColor: Int? = null,
    /** When the relay last prompted each `env:thread`, epoch ms, for the ask-first rule. */
    val relayedAt: Map<String, Long> = emptyMap(),
    /** `env:thread` keys of the threads the relay launched, which count toward the worker limit. */
    val relayWorkers: Set<String> = emptySet(),
    /** Its composer's stashed prompts, newest first, at most [OrchestratorStashEntry.LIMIT], shared with the other devices. */
    val stash: List<OrchestratorStashEntry> = emptyList(),
) {
    /** The name it goes by: what you typed, else "Orchestrator". */
    val displayName: String get() = name.trim().ifEmpty { DEFAULT_NAME }

    /** The row the Orchestrator itself runs on: the first default row, else the first row. */
    val defaultRow: OrchestratorModelRow? get() = models.firstOrNull { it.isDefault } ?: models.firstOrNull()

    fun isHandsOff(key: String) = key in handsOff

    /** Whether `envId`/`threadId` is an Orchestrator thread this phone knows. */
    fun isOrchestrator(envId: String, threadId: String) = machines[envId]?.threadId == threadId

    fun note(name: String): String = notebook[name].orEmpty()

    /**
     * [prompt] stashed on top. A 21st pushes the oldest out; the settings sync writes null to its key like any
     * removal, so every device drops it.
     */
    fun stashing(prompt: String, nowMs: Long, id: String = UUID.randomUUID().toString().lowercase()) =
        copy(stash = OrchestratorStashEntry.sorted(stash + OrchestratorStashEntry(id, prompt, OrchestratorStashEntry.timestamp(nowMs)))
            .take(OrchestratorStashEntry.LIMIT))

    /** Restored or deleted: gone here, and null in the document once it syncs. */
    fun unstashing(id: String) = copy(stash = stash.filterNot { it.id == id })

    fun withNote(name: String, text: String) = copy(notebook = if (text.isEmpty()) notebook - name else notebook + (name to text))

    companion object {
        const val DEFAULT_NAME = "Orchestrator"

        fun key(envId: String, threadId: String) = "$envId:$threadId"
    }
}
