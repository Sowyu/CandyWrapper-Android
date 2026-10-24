package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.composer.SlashAction
import org.anikodev.candywrapper.core.composer.SlashItem
import org.anikodev.candywrapper.core.composer.SlashMenu
import org.anikodev.candywrapper.core.composer.SlashPick
import org.anikodev.candywrapper.core.composer.SlashTrigger
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.json.str
import org.anikodev.candywrapper.core.model.ProviderInfo
import org.anikodev.candywrapper.core.model.ProviderSkill
import org.anikodev.candywrapper.core.model.ServerInfo
import org.anikodev.candywrapper.core.model.SlashCommand
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.sync.Commands

/** T3's slash command menu: when it opens, what it lists, how a query ranks it, and what a pick does. */
class SlashMenuTest {
    private fun provider(
        commands: List<SlashCommand> = listOf(
            SlashCommand("review", "Review a pull request"),
            SlashCommand("release-notes", "Write release notes"),
            SlashCommand("compact", "Summarize the conversation"),
        ),
        skills: List<ProviderSkill> = emptyList(),
    ) = ProviderInfo("claude", "Claude", "claudeAgent", true, emptyList(), true, emptyList(), slashCommands = commands, skills = skills)

    private fun labels(text: String, provider: ProviderInfo? = provider(), planMode: Boolean = false, canCompact: Boolean = false): List<String> {
        val trigger = SlashMenu.detect(text, text.length)!!
        return SlashMenu.items(text, trigger, provider, null, planMode, canCompact).map { it.label }
    }

    @Test
    fun aSlashWordUnderTheCaretOpensTheMenu() {
        assertEquals(SlashTrigger("", 0, 1), SlashMenu.detect("/", 1))
        assertEquals(SlashTrigger("rev", 0, 4), SlashMenu.detect("/rev", 4))
        // Anywhere a word starts, not only at the start of the message.
        assertEquals(SlashTrigger("pl", 6, 9), SlashMenu.detect("hello /pl", 9))
        assertEquals(SlashTrigger("", 3, 4), SlashMenu.detect("a\n\n/", 4))
        // The caret in the middle of the word: the query is what comes before it.
        assertEquals(SlashTrigger("re", 0, 3), SlashMenu.detect("/review", 3))
    }

    @Test
    fun otherTextOpensNothing() {
        assertNull(SlashMenu.detect("", 0))
        assertNull(SlashMenu.detect("hello", 5))
        assertNull(SlashMenu.detect("a/b", 3))
        // A path, even with the caret before its second slash.
        assertNull(SlashMenu.detect("/tmp/build.sh", 13))
        assertNull(SlashMenu.detect("/tmp/build.sh", 3))
        // A finished command and the caret after its space.
        assertNull(SlashMenu.detect("/review ", 8))
        // Caret before the slash; a selection rather than a caret.
        assertNull(SlashMenu.detect("a /x", 2))
        assertNull(SlashMenu.detect("/rev", 1, 4))
        // Backspacing the slash closes it.
        assertNull(SlashMenu.detect("", 0))
    }

    @Test
    fun theMenuListsBuiltInsThenProviderCommandsThenSkills() {
        val skills = listOf(ProviderSkill("frontend-design", "/Users/a/.claude/skills/frontend-design/SKILL.md", scope = "user", description = "Build UI"))
        assertEquals(listOf("/model", "/review", "/release-notes", "/skill:frontend-design"), labels("/", provider(skills = skills)))
        assertEquals(listOf("/model", "/plan", "/default", "/review", "/release-notes"), labels("/", planMode = true))
        // No provider yet: T3's own commands still show.
        assertEquals(listOf("/model"), labels("/", provider = null))
    }

    @Test
    fun providerCommandsOnlyOpenTheMessage() {
        // Elsewhere the provider would get the text literally, so T3 offers only its own commands and skills.
        assertEquals(listOf("/model"), labels("fix this /"))
        assertEquals(listOf("/model"), labels(" /"))
    }

    @Test
    fun compactNeedsAnIdleThreadAndAnOtherwiseEmptyMessage() {
        assertEquals(listOf("/model", "/review", "/release-notes", "/compact"), labels("/", canCompact = true))
        val text = "/ and more"
        val items = SlashMenu.items(text, SlashMenu.detect(text, 1)!!, provider(), null, planMode = false, canCompact = true)
        assertFalse(items.any { it.label == "/compact" })
    }

    @Test
    fun aThreadWithOnlyACompactHasNothingToCompact() {
        fun user(text: String) = TurnRow("u$text", "user_message", 0, "completed", title = "You", text = text)
        assertFalse(SlashMenu.hasCompactableConversation(emptySequence()))
        assertFalse(SlashMenu.hasCompactableConversation(sequenceOf(user(" /Compact "))))
        assertTrue(SlashMenu.hasCompactableConversation(sequenceOf(user("/compact"), user("Fix the build"))))
    }

    @Test
    fun skillsTheUserCannotStartAreLeftOutAndShadowCommands() {
        val skills = listOf(
            ProviderSkill("review", "/p/review", description = "Careful review"),
            ProviderSkill("Review", "/p/review2"),
            ProviderSkill("off", "/p/off", enabled = false),
            ProviderSkill("agent-only", "/p/agent", userInvocable = false),
        )
        // The skill named review hides the command of the same name; the duplicate, disabled and agent-only skills are dropped.
        assertEquals(listOf("/model", "/release-notes", "/compact", "/skill:review"), labels("/", provider(skills = skills), canCompact = true))
    }

    @Test
    fun typingFiltersWithT3sRanking() {
        // Name prefixes first, shorter names ahead; a match in a description ranks after any name.
        assertEquals(listOf("/review", "/release-notes", "/model"), labels("/re"))
        // Subsequence matches still count.
        assertEquals(listOf("/review"), labels("/rvw"))
        assertEquals(listOf("/model"), labels("/MOD"))
        assertEquals(emptyList(), labels("/zzz"))
    }

    @Test
    fun skillQueries() {
        val skills = listOf(
            ProviderSkill("frontend-design", "/p/a", shortDescription = "Design pages"),
            ProviderSkill("pdf", "/p/b", displayName = "PDF tools"),
        )
        val p = provider(commands = emptyList(), skills = skills)
        assertEquals(listOf("/skill:frontend-design", "/skill:pdf"), labels("/skill", p))
        assertEquals(listOf("/skill:pdf"), labels("/skill:pd", p))
        // "sk" matches every skill through the word skill, after real matches.
        assertEquals(listOf("/skill:frontend-design", "/skill:pdf"), labels("/sk", p))
        // A display name and a short description are searched too.
        assertEquals(listOf("/skill:pdf"), labels("/tools", p))
        assertEquals(listOf("/skill:frontend-design"), labels("/pages", p))
    }

    @Test
    fun pickingAProviderCommandOrSkillWritesItsToken() {
        val review = SlashItem.Provider(SlashCommand("review"))
        assertEquals(SlashPick("/review ", 8, SlashAction.NONE), SlashMenu.pick("/re", SlashTrigger("re", 0, 3), review))
        // A space already after the word is reused, not doubled.
        assertEquals(SlashPick("/review now", 8, SlashAction.NONE), SlashMenu.pick("/re now", SlashTrigger("re", 0, 3), review))
        val skill = SlashItem.Skill(ProviderSkill("pdf", "/p"))
        assertEquals(SlashPick("use \$pdf ", 9, SlashAction.NONE), SlashMenu.pick("use /sk", SlashTrigger("sk", 4, 7), skill))
    }

    @Test
    fun pickingABuiltInClearsTheWordAndRunsIt() {
        val t = SlashTrigger("mo", 0, 3)
        assertEquals(SlashPick("", 0, SlashAction.OPEN_MODEL_PICKER), SlashMenu.pick("/mo", t, SlashItem.BuiltIn("model", "")))
        assertEquals(SlashPick(" rest", 0, SlashAction.PLAN_MODE), SlashMenu.pick("/pl rest", SlashTrigger("pl", 0, 3), SlashItem.BuiltIn("plan", "")))
        assertEquals(SlashAction.DEFAULT_MODE, SlashMenu.pick("/d", SlashTrigger("d", 0, 2), SlashItem.BuiltIn("default", "")).action)
    }

    @Test
    fun skillLabelsAndSources() {
        assertEquals("Frontend Design", SlashMenu.displayName(ProviderSkill("frontend-design", "/p")))
        assertEquals("PDF", SlashMenu.displayName(ProviderSkill("pdf", "/p", displayName = " PDF ")))
        assertEquals("Personal", SlashMenu.skillSource(ProviderSkill("a", "/p", scope = "user")))
        assertEquals("Repo", SlashMenu.skillSource(ProviderSkill("a", "/p", scope = "repository")))
        assertEquals("App", SlashMenu.skillSource(ProviderSkill("a", "/Users/a/.codex/plugins/x/SKILL.md", scope = "user")))
        assertEquals("Provider", SlashMenu.skillSource(ProviderSkill("a", "/p")))
        assertEquals("user skill", SlashItem.Skill(ProviderSkill("a", "/p", scope = "user")).description)
        assertEquals("Run provider command", SlashItem.Provider(SlashCommand("x")).description)
        assertEquals("<pr>", SlashItem.Provider(SlashCommand("x", inputHint = "<pr>")).description)
    }

    private val config = WireJson.parseToJsonElement(
        """
        {"providers":[{"instanceId":"claude","driver":"claudeAgent","enabled":true,"installed":true,"status":"ready","models":[],
          "slashCommands":[{"name":"review","description":"Review a PR"},{"name":"init","input":{"hint":"<dir>"}},{"description":"no name"}],
          "skills":[{"name":"pdf","path":"/s/pdf/SKILL.md","enabled":true,"scope":"user","userInvocable":false}],
          "workspaceSnapshots":[{"cwd":"/repo","checkedAt":"2026-10-24T10:00:00.000Z","slashCommands":[{"name":"deploy"}],"skills":[]},
                                {"cwd":"/busy","checkedAt":"2026-10-24T10:00:00.000Z","slashCommands":[],"slashCommandsPending":true,"skills":[]}]}]}
        """,
    ) as JsonObject

    @Test
    fun providersCarryTheirCommandsSkillsAndCheckouts() {
        val p = ServerInfo.parse(config).provider("claude")!!
        assertEquals(listOf(SlashCommand("review", "Review a PR"), SlashCommand("init", inputHint = "<dir>")), p.slashCommands)
        assertEquals(false, p.skills.single().userInvocable)
        assertEquals("user", p.skills.single().scope)
        // A checkout's scan replaces the provider's lists; an unknown checkout keeps them.
        assertEquals(listOf("deploy"), p.slashCommandsFor("/repo").map { it.name })
        assertEquals(emptyList(), p.skillsFor("/repo"))
        assertEquals(listOf("review", "init"), p.slashCommandsFor("/other").map { it.name })
        assertEquals(listOf("review", "init"), p.slashCommandsFor(null).map { it.name })
        // Current for five minutes after the scan, and never while it is still running.
        val checked = java.time.Instant.parse("2026-10-24T10:00:00Z").toEpochMilli()
        assertTrue(p.hasCurrentWorkspace("/repo", checked + 60_000))
        assertFalse(p.hasCurrentWorkspace("/repo", checked + 5 * 60_000))
        assertFalse(p.hasCurrentWorkspace("/busy", checked + 1))
        assertFalse(p.hasCurrentWorkspace("/other", checked + 1))
    }

    @Test
    fun refreshedProvidersReplaceOnlyTheirOwn() {
        val server = ServerInfo(providers = listOf(provider(), ProviderInfo("codex", "Codex", "codex", true, emptyList(), true, emptyList())))
        val fresh = ServerInfo.providers(config)
        val merged = server.withProviders(fresh)
        assertEquals(listOf("claude", "codex"), merged.providers.map { it.instanceId })
        assertEquals(listOf("review", "init"), merged.provider("claude")!!.slashCommands.map { it.name })
    }

    @Test
    fun refreshProvidersMatchesTheContract() {
        val payload = Commands.refreshProviders("claude", "/repo")
        assertEquals(setOf("instanceId", "cwd"), payload.keys)
        assertEquals("claude", payload.str("instanceId"))
        assertEquals("/repo", payload.str("cwd"))
    }
}
