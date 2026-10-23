package org.anikodev.candywrapper.ui

import androidx.compose.runtime.mutableStateMapOf
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.diff.UnifiedDiff
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.model.ApprovalChoice
import org.anikodev.candywrapper.core.model.ModelInfo
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionChoice
import org.anikodev.candywrapper.core.model.OptionDescriptor
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.model.ProviderInfo
import org.anikodev.candywrapper.core.model.Question
import org.anikodev.candywrapper.core.model.QuestionOption
import org.anikodev.candywrapper.core.model.RowKind
import org.anikodev.candywrapper.core.model.ServerInfo
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.store.ThreadMeta
import org.anikodev.candywrapper.core.sync.PendingRequest
import org.anikodev.candywrapper.core.sync.QueuedMessage
import org.anikodev.candywrapper.core.sync.ThreadSession
import org.anikodev.candywrapper.core.sync.TimelineItem
import org.anikodev.candywrapper.core.sync.TimelineRow
import org.anikodev.candywrapper.core.sync.ToolGroup

/** A thread with every kind of row the view draws: messages, Markdown, tool calls, a diff and a checkpoint. */
object ThreadFixtures {
    private var ordinal = 0L
    private fun row(id: String, type: String, title: String, text: String? = null, detail: String? = null, body: String? = null, status: String = "completed", failed: Boolean = false, checkpointId: String? = null) =
        TurnRow(id, type, ordinal++, status, "run1", title, text, detail, body, failed = failed, checkpointId = checkpointId, atMs = Fixtures.NOW - 3 * 60_000)

    private fun item(r: TurnRow) = TimelineRow(r, if (r.kind == RowKind.ASSISTANT || r.kind == RowKind.PLAN) r.text?.let(Markdown::parse) else null)

    private val diffText = """
        diff --git a/app/src/main/java/ui/Sidebar.kt b/app/src/main/java/ui/Sidebar.kt
        --- a/app/src/main/java/ui/Sidebar.kt
        +++ b/app/src/main/java/ui/Sidebar.kt
        @@ -12,6 +12,8 @@ fun SidebarPanel(
             val c = Candy.colors
        -    var query by remember { mutableStateOf("") }
        +    var query by rememberSaveable { mutableStateOf(initialQuery) }
        +    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
             val groups = remember(model, query) { filterGroups(model.groups, query) }
    """.trimIndent()

    val diff = UnifiedDiff.parse(diffText).first().lines

    val user = row("u1", "user_message", "You", text = "The sidebar should look like T3's desktop app. Show me what changed and run the tests.")
    val reasoning = row("r1", "reasoning", "Thinking", text = "Reading the sidebar component and T3's LegacySidebar before touching anything.")
    val read = row("c1", "command_execution", "Ran command", detail = "rg -n \"SidebarMenuSub\" apps/web/src", body = "apps/web/src/components/ui/sidebar.tsx:773")
    val edit = row("e1", "file_change", "Edited file", detail = "Sidebar.kt  +2 -1", body = "app/src/main/java/ui/Sidebar.kt")
    val test = row("c2", "command_execution", "Ran command", detail = "./gradlew :app:testDebugUnitTest", body = "BUILD SUCCESSFUL in 41s")
    val failedTool = row("c3", "command_execution", "Ran command", detail = "./gradlew lint", failed = true, body = "Lint found 2 errors")
    val answer = row(
        "a1", "assistant_message", "Assistant",
        text = """
            ## What changed

            The sidebar now follows `LegacySidebar.tsx`: projects with their threads nested under a guide line.

            - Thread rows show the **status**, the title and the time.
            - [x] Copy the row metrics
            - [ ] Check the tablet layout

            ```kotlin
            fun relativeTime(atMs: Long, now: Long): String {
                val s = (now - atMs) / 1000
                return if (s < 60) "now" else "${'$'}{s / 60}m"
            }
            ```

            > [!NOTE]
            > Rows keep their instance when nothing changed, so Compose skips them.

            | Step | Status |
            | --- | --- |
            | Sidebar | Done |
            | Composer | Pending |
        """.trimIndent(),
    )
    val checkpoint = row("k1", "checkpoint", "Checkpoint", text = "2 files changed", body = "app/src/main/java/ui/Sidebar.kt  +42 -7\ndocs/design.md  +12 -0", checkpointId = "cp1")

    val items: List<TimelineItem> = listOf(
        item(user), item(reasoning),
        ToolGroup("g1", listOf(item(read), item(edit))),
        item(test), item(failedTool),
        item(answer), item(checkpoint),
    )

    val model = ModelSelection("claude", "claude-opus-5-5", listOf(OptionValue("effort", text = "medium")))

    val server = ServerInfo(
        providers = listOf(
            ProviderInfo(
                "claude", "Claude", "claudeAgent", true,
                listOf(
                    ModelInfo("claude-opus-5-5", "Claude Opus 5.5", listOf(OptionDescriptor("effort", "Effort", "select", listOf(OptionChoice("low", "Low", false), OptionChoice("medium", "Medium", true), OptionChoice("high", "High", false))))),
                    ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", emptyList()),
                ),
                showInteractionModeToggle = true, runtimeModes = emptyList(),
            ),
            ProviderInfo("codex", "Codex", "codex", true, listOf(ModelInfo("gpt-6-astra", "GPT-6 Astra", emptyList())), true, emptyList()),
        ),
    )

    val state = ThreadSession.State(
        items = items, title = "Copy T3's sidebar into the phone app", phase = ThreadSession.Phase.LIVE,
        meta = ThreadMeta(model = model, runtimeMode = "full-access"),
    )

    val approval = PendingRequest(
        TurnRow("ap1", "approval_request", 99, "pending", "run1", "Approval", text = "app/src/main/java/ui/Sidebar.kt", detail = "file-change: app/src/main/java/ui/Sidebar.kt",
            requestId = "req1", approvalOptions = listOf(ApprovalChoice("accept", "Approve"), ApprovalChoice("acceptForSession", "Approve for session"), ApprovalChoice("decline", "Decline"))),
    )

    val question = PendingRequest(
        TurnRow("q1", "user_input_request", 100, "pending", "run1", "Question", requestId = "req2", questions = listOf(
            Question("purpose", "Purpose", "What should the small CLI tool do?", listOf(
                QuestionOption("Transform files", "Read input files and write converted or cleaned output."),
                QuestionOption("Query data", "Look up or summarise information from a file, database, or API."),
                QuestionOption("Something else", "Describe the purpose in your own words."),
            )),
            Question("lang", "Language", "Which language?", listOf(QuestionOption("Kotlin"), QuestionOption("Python"))),
        )),
    )

    val queued = listOf(QueuedMessage("q-run-1", 1, "Then make the tablet layout match"), QueuedMessage("q-run-2", 2, "and push when the tests pass"))

    /** A host with the edit row open on its diff and the group open. */
    fun host(open: Boolean = true) = TimelineHost(
        { ItemDetail(null, diff) },
        mutableStateMapOf<String, Boolean>().apply { if (open) { put("g1", true); put("e1", true) } },
        mutableStateMapOf<String, ItemDetail>().apply { put("e1:completed", ItemDetail(null, diff)) },
    ) { _, _ -> }
}
