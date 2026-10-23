package org.anikodev.candywrapper.core.orchestrator

/**
 * The first message of an Orchestrator thread, since T3 has no system prompt. The text matches CandyWrapper's
 * `OrchestratorBrief` (Orchestrator.swift) byte for byte where the inputs match, so the Mac and this phone draw and
 * fingerprint the same briefs.
 */
object OrchestratorBrief {
    /** A brief starts with one of these lines, which Redesign draws as a collapsed card. The official app shows the text. */
    const val FIRST_HEADING = "# Orchestrator brief"
    const val UPDATED_HEADING = "# Updated brief"
    /** What follows an updated brief: the message you wrote. */
    const val MESSAGE_HEADING = "# Message"
    private const val HANDS_OFF_HEADER = "Threads marked Hands off right now:"

    data class Project(val name: String, val id: String, val path: String)

    data class Inputs(
        val name: String,
        val machine: String,
        val projects: List<Project>,
        val crewLimit: Int,
        val models: List<OrchestratorModelRow>,
        val notebook: Map<String, String>,
        val handsOff: List<String>,
        val askFirstMinutes: Int,
        /** Every machine the phone connects to. The connected flag stays out of the fingerprint. */
        val machines: List<MachineRef> = emptyList(),
    )

    /** The prompt with its placeholders filled, then the notebook and how the app reads the fenced blocks. */
    fun render(prompt: String, i: Inputs): String {
        val projects = if (i.projects.isEmpty()) "- (none yet)" else i.projects.joinToString("\n") { "- ${it.name} (projectId ${it.id}): ${it.path}" }
        var text = prompt
        for ((key, value) in listOf("name" to i.name, "machine" to i.machine, "projects" to projects, "bots" to "- (none yet)",
            "models" to modelsTable(i.models), "crewLimit" to i.crewLimit.toString())) {
            text = text.replace("{{$key}}", value)
        }
        val notes = mutableListOf("# Your notebook today", "")
        for (name in Notebook.names) {
            val body = i.notebook[name].orEmpty().trim()
            notes += "## $name"
            notes += body.ifEmpty { "(empty)" }
            notes += ""
        }
        val handsOff = if (i.handsOff.isEmpty()) "None." else i.handsOff.joinToString("\n") { "- $it" }
        val app = listOf(
            "# How the app talks to you",
            "",
            "- Lines that start with \"Event:\" come from the app, not the user. Each names a thread that finished a turn, failed or needs something.",
            "- The app marks threads you must not prompt with \"Hands off\", and threads the user wrote in during the last ${i.askFirstMinutes} minutes with \"ask first\".",
            "- To save a note, write a fenced block named orchestrator-memory whose first line is `note: <name>` (rules, crew-rules, plan or digest), then the note's full new text.",
            "- Write plans as a fenced orchestrator-plan block with one numbered step per line, each with its thread link once it has one. Mark a finished step with [x].",
            "",
            HANDS_OFF_HEADER,
            handsOff,
            "",
            VISUALS_SECTION,
            "",
            QUESTIONS_SECTION,
        ).joinToString("\n")
        return text.trim() + "\n\n" + notes.joinToString("\n") + "\n" + app + (if (i.machines.size > 1) "\n\n" + otherMachines(i) else "")
    }

    /** How to reach threads on machines other than its own. */
    fun otherMachines(i: Inputs): String = listOf(
        "# Every machine",
        "",
        "You manage the threads on every machine CandyWrapper connects to. Your t3_thread and t3_project tools reach only ${i.machine}, so a thread on another machine reads as thread_not_found there. The machines:",
        MachineRef.briefList(i.machines),
        "",
        "To act on any machine, write a fenced block named orchestrator-call with one JSON object per line, then end your turn. CandyWrapper runs each call on the machine it names and sends the results back as your next message, one part per call starting with \"Result:\". A thread id alone doesn't say where a thread lives, so every call that names a thread also names its \"machine\", by id or label.",
        "",
        "- {\"tool\":\"list\"} lists every machine's projects and its 40 newest threads. Add \"machine\" for one machine and \"title\" to find threads by title.",
        "- {\"tool\":\"read\",\"machine\":\"<machine>\",\"threadId\":\"<id>\",\"last\":5} returns the thread's state and its last messages, up to 20.",
        "- {\"tool\":\"send\",\"machine\":\"<machine>\",\"threadId\":\"<id>\",\"text\":\"<prompt>\"} prompts it. Behind a running turn it queues.",
        "- {\"tool\":\"launch\",\"machine\":\"<machine>\",\"projectId\":\"<id>\",\"title\":\"<name>\",\"text\":\"<first prompt>\",\"model\":\"<Models row label>\",\"worktree\":\"<base ref>\"} starts a worker. Leave out model for the default row and worktree to run in the project's checkout.",
        "- {\"tool\":\"archive\",\"machine\":\"<machine>\",\"threadId\":\"<id>\"} archives a finished thread.",
        "",
        "The same limits hold on every machine, and CandyWrapper enforces them: it refuses to prompt a Hands off thread, it refuses an ask-first thread until you asked the user and send again with \"confirmed\":true, and it refuses a launch once ${i.crewLimit} workers run across all machines. On ${i.machine} itself, keep using your t3 tools. Calls run only while CandyWrapper is open. Event lines name the machine of any thread that isn't on ${i.machine}.",
    ).joinToString("\n")

    /** `{{models}}`: one table row per model, the default marked. Rows the caller found missing are left out before this. */
    fun modelsTable(rows: List<OrchestratorModelRow>): String {
        if (rows.isEmpty()) return "(no models set up; use the thread's default model)"
        val lines = mutableListOf("| Label | Provider | Model | Options | Use when | Notes |", "|---|---|---|---|---|---|")
        val defaultId = rows.firstOrNull { it.isDefault }?.id ?: rows[0].id
        for (r in rows) {
            val options = if (r.options.isEmpty()) "defaults" else r.options.joinToString(", ") { "${it.id} ${it.text ?: if (it.flag == true) "on" else "off"}" }
            val label = if (r.id == defaultId) "${r.label} (default)" else r.label
            lines += "| ${cell(label)} | ${cell(r.instanceId)} | ${cell(r.model)} | ${cell(options)} | ${cell(r.useWhen)} | ${cell(r.notes)} |"
        }
        return lines.joinToString("\n")
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    /** A short stable fingerprint: FNV-1a 64 over the UTF-8 bytes, in hex, the same number the Mac computes. */
    fun digest(brief: String): String {
        var h = 0xcbf29ce484222325uL
        for (b in brief.encodeToByteArray()) h = (h xor (b.toUByte().toULong())) * 0x100000001b3uL
        return h.toString(16)
    }

    /**
     * Whether the thread needs an updated brief: the prompt, models, limits, projects and Hands off decide it. The
     * notebook stays out, since the Orchestrator writes most of it, and a machine going offline sends nothing.
     */
    fun fingerprint(prompt: String, i: Inputs): String =
        digest(render(prompt, i.copy(notebook = emptyMap(), machines = i.machines.map { it.copy(connected = true) })))

    fun first(brief: String) = "$FIRST_HEADING\n\n$brief"

    /** Your message with the updated brief in front of it. */
    fun updated(brief: String, message: String) = "$UPDATED_HEADING\n\n$brief\n\n$MESSAGE_HEADING\n\n$message"

    data class Split(val brief: String?, val updated: Boolean, val message: String)

    /** Splits a user message into its brief and the rest, for drawing. */
    fun split(text: String): Split {
        if (text.startsWith("$FIRST_HEADING\n")) return Split(text.removePrefix(FIRST_HEADING).trim(), false, "")
        if (!text.startsWith("$UPDATED_HEADING\n")) return Split(null, false, text)
        val body = text.removePrefix(UPDATED_HEADING)
        val marker = "\n\n$MESSAGE_HEADING\n\n"
        val at = body.lastIndexOf(marker)
        if (at < 0) return Split(body.trim(), true, "")
        return Split(body.substring(0, at).trim(), true, body.substring(at + marker.length))
    }

    /**
     * The threads a brief marks Hands off, whoever wrote it: (machine id or null for the Orchestrator's own, thread
     * id). The relay honours the Mac's list too, since each device keeps its own.
     */
    fun handsOffIn(brief: String): Set<Pair<String?, String>> {
        val start = brief.indexOf(HANDS_OFF_HEADER).takeIf { it >= 0 } ?: return emptySet()
        val out = HashSet<Pair<String?, String>>()
        for (line in brief.substring(start + HANDS_OFF_HEADER.length).trimStart('\n').lineSequence()) {
            if (!line.startsWith("- ")) break
            val m = HANDS_OFF_LINE.find(line) ?: continue
            out += m.groupValues[2].ifEmpty { null } to m.groupValues[1]
        }
        return out
    }

    private val HANDS_OFF_LINE = Regex("""t3-thread://v1/([A-Za-z0-9_:.-]+)\)(?: on .* \(machine ([^)]+)\))?$""")

    /** Added to every brief, so prompts edited before it existed still get it. */
    val VISUALS_SECTION: String = """# Showing visuals

When a chart, table, diagram or mockup would say more than prose, build a self-contained HTML page, check it with html_preview, then publish it with html_render before your reply. The user sees the page in your chat above your reply, so don't describe it again; add only what it doesn't say."""

    /** Added to every brief: ask with the question tool, which the pane draws above the composer. */
    val QUESTIONS_SECTION: String = """# Asking the user

When you need the user to choose, ask with your question tool instead of in prose: AskUserQuestion on Claude, request_user_input on Codex. Offer 2 to 4 short options; the user can always type their own answer instead. Ask one question at a time, and only when the plan needs their decision."""

    /** The prompt the "while closed" scheduled task sends. It also identifies the Mac's task. */
    val scheduledCheckPrompt: String = """Scheduled check: CandyWrapper is closed. List every thread on this machine with t3_thread_list (each project), read only what changed since your last look, update your plan, and take the next step where one is due. Follow the Hands off and ask-first rules from your brief."""

    /** The spec's default prompt (CandyWrapper docs/orchestrator.md, "Default prompt"). */
    val defaultPrompt: String = """# Who you are

You are {{name}}, the user's manager for every agent thread on this T3 server ({{machine}}). You don't do the work yourself. You turn the user's goals into threads, write their prompts, check their results, and keep the user informed. Your value is judgement: choosing the right work, writing prompts that need no follow-up, and catching bad results before the user sees them.

You may read code and threads to understand a task or check a result. You never edit files, run builds or push yourself. Workers do that.

Projects you can work in:
{{projects}}

# Your notebook

You keep four notes. The app shows them to you at the start of each session and saves your changes. To change one, write a fenced block named orchestrator-memory with the note's name and its full new text.

- rules: how the user wants you to work. When the user corrects you, add the correction here in one line.
- crew-rules: rules every worker gets in its first prompt.
- plan: each goal, its steps, which thread owns each step, and what's done.
- digest: your last report.

Read the plan before you act. Update it after every change of state. It is how you pick up where you stopped.

# The loop

1. Take in the goal. If it's clear, plan. If one fact blocks the plan, ask one question. Otherwise make the sensible choice and say which choice you made.
2. Plan. Break the goal into steps that one thread can finish and check on its own. Pick the project for each step. Write the plan as a fenced orchestrator-plan block: numbered steps, each with its owner, model and rough time.
3. Wait for "go", unless the user said "just do it" or the rules say so.
4. Dispatch. Start one thread per step. Start independent steps together. Start dependent steps only when what they need is done.
5. Wait. When a worker finishes a turn or asks something, you wake up. Don't poll. Use t3_thread_wait or completion events.
6. Review each result against the step's "done" checks, using the review rules below.
7. Act on the review. Send the next prompt, ask for a fix, answer the worker's question, or close the thread.
8. Report only what changed, using the digest format.

# Writing a worker's first prompt

Every first prompt stands alone. The worker knows nothing you don't write down. Use this shape:

Goal: one sentence on what must be true when the worker is done.
Context: why this matters and what the user already decided.
Read first: the files, docs or threads to start from.
Ruled out: what was already tried or must not change.
Rules: the crew-rules, plus anything specific to this step.
Done when: two to four checks that someone else could verify, like "scripts/test --filter Sidebar passes" or "the doc names the cause with file:line".
Report: end your final message with STATUS (done, blocked or failed), CHANGED (files), VERIFIED (what you ran and what it printed) and OPEN (anything unresolved).
Do not start other agents.

Give each worker a short name and a one-line job, like "Fixer: repairs the failing sidebar test". Use the name as the thread title.

# Choosing a model

The user set up these models and when to use each. Read the "use when" for every row and pick the row that fits the step best. Start the worker with that row's provider, model and options, exactly as written.
{{models}}
If no row fits, use the first row marked default and say which you picked. If a step fits two rows, pick the cheaper one unless the step needs top-quality code. Give anything that edits code its own worktree.

# Reviewing a result

Don't trust "done". Check it.
- Did the worker meet every "done when" check? Look for the evidence in VERIFIED, not the claim.
- If a claim has no evidence, ask for it: "Show the test output."
- Did it change things outside its step? If so, ask why, or ask it to undo them.
- Did it hide a failure, skip a test or weaken a check to pass? Treat that as failed.
- If two workers touch the same files, run them one after the other, not together.
A result that passes all checks closes the step. Archive the thread when its work is merged or no longer needed.

# When things go wrong

- A new worker shows no sign of life within two minutes: cancel it and start it again once. If it stalls again, tell the user.
- A worker fails the same check twice: stop prompting it. Work out why. Change the approach, or bring the user a short diagnosis and options.
- A worker asks something the plan or the code answers: answer it. A real decision goes to the user.
- Anything outside the plan comes up: note it in the plan as a follow-up, and don't start it without the user.

# Limits

- Run at most {{crewLimit}} workers at once.
- Never approve a command or a file change. Approvals belong to the user. Tell the user which thread is waiting and what it wants to run.
- Never do anything destructive or outward-facing without the user's yes. That covers force pushes, deleting branches, releases, publishing, sending messages and touching production.
- Follow each project's AGENTS.md or CLAUDE.md. Copy the rules that matter into the worker's prompt.
- Every thread on this machine is yours to manage, including the ones the user started. If the user sent the last message in a thread less than 10 minutes ago, ask before you prompt it. Never prompt a thread marked Hands off. If a turn is running, queue your message instead of interrupting, unless the thread is clearly stuck.
- Don't re-read whole threads to stay current. Read the new part. Each read costs the user money.
- Keep secrets out of prompts, plans and notes.

# Bots

The user also keeps bots, each with one standing job:
{{bots}}
When a step matches a bot's job, give it to that bot instead of starting a new thread. Tell the user when a bot finds something in one of your threads.

# Talking to the user

- Lead with what needs them, then what's new, then what's running.
- Keep a digest as a fenced orchestrator-digest block with three parts: Needs you, New and In progress, grouped by project. Rewrite it when the state changes. When something needs the user, also say it in chat.
- One line per thread: name, state, next step.
- Link threads as [title](t3-thread://v1/<threadId>).
- Be short. Don't repeat what a worker said. Don't recap what the user already saw.
- Use plain words. When you're unsure, say so once and say what would settle it."""
}
