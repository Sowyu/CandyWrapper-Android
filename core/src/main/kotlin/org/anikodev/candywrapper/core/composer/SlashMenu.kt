package org.anikodev.candywrapper.core.composer

import org.anikodev.candywrapper.core.model.ProviderInfo
import org.anikodev.candywrapper.core.model.ProviderSkill
import org.anikodev.candywrapper.core.model.SlashCommand
import org.anikodev.candywrapper.core.model.TurnRow

/** A `/word` under the caret: [query] is the word without its slash, [start] to [end] the text it replaces. */
data class SlashTrigger(val query: String, val start: Int, val end: Int) {
    /** T3's `useComposerTriggerState`: a dismissed menu stays shut while the caret stays in the same word. */
    fun sameWord(other: SlashTrigger?) = other != null && other.start == start
}

/** One row of the slash menu (web `ComposerCommandItem`'s slash kinds). */
sealed interface SlashItem {
    val id: String
    val label: String
    val description: String

    /** T3's own `/model`, `/plan` and `/default`. */
    data class BuiltIn(val command: String, override val description: String) : SlashItem {
        override val id get() = "slash:$command"
        override val label get() = "/$command"
    }

    /** A provider command, offered only at the start of the message, where the provider expands it. */
    data class Provider(val command: SlashCommand) : SlashItem {
        override val id get() = "provider-slash-command:${command.name}"
        override val label get() = "/${command.name}"
        override val description get() = command.description ?: command.inputHint ?: "Run provider command"
    }

    /** A provider skill; picking it writes `$name`. */
    data class Skill(val skill: ProviderSkill) : SlashItem {
        override val id get() = "skill:${skill.name}"
        override val label get() = "/skill:${skill.name}"
        override val description get() = skill.shortDescription ?: skill.description ?: skill.scope?.let { "$it skill" }.orEmpty()
    }
}

/** What a pick does besides rewriting the prompt. */
enum class SlashAction { NONE, OPEN_MODEL_PICKER, PLAN_MODE, DEFAULT_MODE }

data class SlashPick(val text: String, val cursor: Int, val action: SlashAction)

/**
 * T3's slash command menu: trigger detection (web `composer-logic.ts` `detectComposerTrigger`), the
 * items (`useComposerMenuItems.ts`, client-runtime `providerSkills.ts`), their search and order
 * (`composerSlashCommandSearch.ts`, `providerSkillSearch.ts`, shared `searchRanking.ts`) and what a
 * pick does (`ChatComposer.tsx` `onSelectComposerItem`).
 */
object SlashMenu {
    private fun isSpace(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r'

    /**
     * The slash word ending at the caret, or null. A word with a second slash, such as `/tmp/x`, is a
     * path. A selection that is not a caret opens nothing.
     */
    fun detect(text: String, selectionStart: Int, selectionEnd: Int = selectionStart): SlashTrigger? {
        if (selectionStart != selectionEnd) return null
        val cursor = selectionEnd.coerceIn(0, text.length)
        var start = cursor
        while (start > 0 && !isSpace(text[start - 1])) start--
        if (start == cursor || text[start] != '/') return null
        var wordEnd = cursor
        while (wordEnd < text.length && !isSpace(text[wordEnd])) wordEnd++
        if ('/' in text.substring(start + 1, wordEnd)) return null
        return SlashTrigger(text.substring(start + 1, cursor), start, cursor)
    }

    /**
     * The menu for [trigger] in [text]: `/model`, then `/plan` and `/default` with plan mode on, then
     * the provider's commands (at the start of the message only), then its skills; filtered and ranked
     * by the query. [canCompact] is the thread-level half of T3's `compactSlashCommandAvailable`
     * (a thread with a conversation, idle, no request open, nothing attached); `/compact` also needs
     * the command to be the whole message.
     */
    fun items(
        text: String,
        trigger: SlashTrigger,
        provider: ProviderInfo?,
        cwd: String?,
        planMode: Boolean,
        canCompact: Boolean,
        showSkills: Boolean = true,
    ): List<SlashItem> {
        val builtIns = buildList {
            add(SlashItem.BuiltIn("model", "Switch response model for this thread"))
            if (planMode) {
                add(SlashItem.BuiltIn("plan", "Switch this thread into plan mode"))
                add(SlashItem.BuiltIn("default", "Switch this thread back to normal build mode"))
            }
        }
        val skills = if (showSkills && provider != null) userSkills(provider.skillsFor(cwd)) else emptyList()
        val skillNames = skills.mapTo(HashSet()) { it.name.trim().lowercase() }
        val compact = canCompact && text.substring(0, trigger.start).isBlank() && text.substring(trigger.end).isBlank()
        val atStart = trigger.start == 0
        val commands = if (!atStart || provider == null) emptyList() else provider.slashCommandsFor(cwd)
            .filter { it.name.trim().lowercase() !in skillNames && (it.name != "compact" || compact) }
            .map(SlashItem::Provider)
        return search(builtIns + commands + skills.map(SlashItem::Skill), trigger.query)
    }

    /** ChatView's `activeThreadHasCompactableConversation`: a user message other than a bare `/compact`. */
    fun hasCompactableConversation(rows: Sequence<TurnRow>): Boolean =
        rows.any { it.type == "user_message" && (it.text.orEmpty().trim().lowercase() != "/compact" || it.attachments.isNotEmpty()) }

    /** `isProviderSkillUserInvocable` and `dedupeProviderSkillsByName`. */
    fun userSkills(skills: List<ProviderSkill>): List<ProviderSkill> {
        val seen = HashSet<String>()
        return skills.filter { it.enabled && it.userInvocable != false && seen.add(it.name.trim().lowercase()) }
    }

    /** `searchSlashCommandItems`: every item for an empty query, else the matches by score, then built-ins, commands, skills by name. */
    fun search(items: List<SlashItem>, query: String): List<SlashItem> {
        val q = query.trim().trimStart('/').lowercase()
        if (q.isEmpty()) return items
        return items.mapNotNull { item -> score(item, q)?.let { Triple(item, it, tieBreaker(item)) } }
            // ponytail: plain string order where T3 uses localeCompare; they differ only on equal scores with punctuation, switch to a Collator if that shows
            .sortedWith(compareBy<Triple<SlashItem, Int, String>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    private fun tieBreaker(item: SlashItem) = when (item) {
        is SlashItem.BuiltIn -> "0\u0000${item.command}"
        is SlashItem.Provider -> "1\u0000${item.command.name}"
        is SlashItem.Skill -> "2\u0000${item.skill.name}"
    }

    private fun score(item: SlashItem, query: String): Int? {
        if (item is SlashItem.Skill) {
            if (query == "skill") return 0
            val skillQuery = query.removePrefix("skill:")
            val skillScore = if (skillQuery.isEmpty()) 0 else scoreSkill(item.skill, skillQuery)
            if (skillScore != null) return skillScore
            return if ("skill".startsWith(query)) Int.MAX_VALUE else null
        }
        val primary = when (item) {
            is SlashItem.BuiltIn -> item.command
            is SlashItem.Provider -> item.command.name
            else -> error("unreachable")
        }.lowercase()
        return listOfNotNull(
            Ranking.score(primary, query, exact = 0, prefix = 2, boundary = 4, includes = 6, fuzzy = 100, markers = listOf("-", "_", "/")),
            Ranking.score(item.description.lowercase(), query, exact = 20, prefix = 22, boundary = 24, includes = 26),
        ).minOrNull()
    }

    /** web `scoreProviderSkill`. */
    fun scoreSkill(skill: ProviderSkill, query: String): Int? = listOfNotNull(
        Ranking.score(skill.name.lowercase(), query, exact = 0, prefix = 2, boundary = 4, includes = 6, fuzzy = 100, markers = listOf("-", "_", "/")),
        Ranking.score(displayName(skill).lowercase(), query, exact = 1, prefix = 3, boundary = 5, includes = 7, fuzzy = 110),
        Ranking.score(skill.shortDescription?.lowercase().orEmpty(), query, exact = 20, prefix = 22, boundary = 24, includes = 26),
        Ranking.score(skill.description?.lowercase().orEmpty(), query, exact = 30, prefix = 32, boundary = 34, includes = 36),
        Ranking.score(skill.scope?.lowercase().orEmpty(), query, exact = 40, prefix = 42, includes = 44),
    ).minOrNull()

    /** shared `formatProviderSkillDisplayName`: the display name, else the name in title case. */
    fun displayName(skill: ProviderSkill): String =
        skill.displayName?.trim()?.ifEmpty { null }
            ?: skill.name.split(Regex("[\\s:_-]+")).filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** client-runtime `resolveProviderSkillSourceKind`, as the label on a skill's badge. */
    fun skillSource(skill: ProviderSkill): String {
        val path = skill.path.replace('\\', '/')
        if ("/.codex/plugins/" in path || "/.agents/plugins/" in path) return "App"
        return when (skill.scope?.trim()?.lowercase()) {
            "repo", "repository" -> "Repo"
            "project", "workspace", "local" -> "Project"
            "user", "personal" -> "Personal"
            "system" -> "System"
            else -> "Provider"
        }
    }

    /**
     * What picking [item] does to [text]: a provider command becomes `/name `, a skill `$name `, each
     * taking a space that already follows the word; `/model`, `/plan` and `/default` clear the word
     * and run their action.
     */
    fun pick(text: String, trigger: SlashTrigger, item: SlashItem): SlashPick {
        fun replace(with: String, action: SlashAction = SlashAction.NONE): SlashPick {
            val start = trigger.start.coerceIn(0, text.length)
            var end = trigger.end.coerceIn(start, text.length)
            // `extendReplacementRangeForTrailingSpace`.
            if (with.endsWith(' ') && text.getOrNull(end) == ' ') end++
            return SlashPick(text.substring(0, start) + with + text.substring(end), start + with.length, action)
        }
        return when (item) {
            is SlashItem.BuiltIn -> replace("", when (item.command) {
                "model" -> SlashAction.OPEN_MODEL_PICKER
                "plan" -> SlashAction.PLAN_MODE
                else -> SlashAction.DEFAULT_MODE
            })
            is SlashItem.Provider -> replace("/${item.command.name} ")
            is SlashItem.Skill -> replace("$${item.skill.name} ")
        }
    }
}

/** shared `searchRanking.ts`. Values and queries arrive trimmed and lowercased. */
internal object Ranking {
    fun score(
        value: String,
        query: String,
        exact: Int,
        prefix: Int? = null,
        boundary: Int? = null,
        includes: Int? = null,
        fuzzy: Int? = null,
        markers: List<String> = listOf(" ", "-", "_", "/"),
    ): Int? {
        if (value.isEmpty() || query.isEmpty()) return null
        if (value == query) return exact
        val penalty = (value.length - query.length).coerceIn(0, 64)
        if (prefix != null && value.startsWith(query)) return prefix + penalty
        if (boundary != null) {
            markers.mapNotNull { m -> value.indexOf(m + query).takeIf { it >= 0 }?.let { it + m.length } }.minOrNull()
                ?.let { return boundary + it * 2 + penalty }
        }
        if (includes != null) value.indexOf(query).takeIf { it >= 0 }?.let { return includes + it * 2 + penalty }
        if (fuzzy != null) subsequence(value, query)?.let { return fuzzy + it }
        return null
    }

    /** `scoreSubsequenceMatch`: the query's letters in order, cheaper the earlier and tighter they sit. */
    fun subsequence(value: String, query: String): Int? {
        if (query.isEmpty()) return 0
        var q = 0
        var first = -1
        var previous = -1
        var gaps = 0
        for (i in value.indices) {
            if (value[i] != query[q]) continue
            if (first == -1) first = i
            if (previous != -1) gaps += i - previous - 1
            previous = i
            q++
            if (q == query.length) {
                val span = i - first + 1 - query.length
                return first * 2 + gaps * 3 + span + minOf(64, value.length - query.length)
            }
        }
        return null
    }
}
