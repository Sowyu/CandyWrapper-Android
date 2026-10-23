package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.OptionValue
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.ServerInfo
import org.anikodev.candywrapper.core.model.ThreadListModel
import org.anikodev.candywrapper.core.orchestrator.BotColor
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.BotMood
import org.anikodev.candywrapper.core.orchestrator.BotShape
import org.anikodev.candywrapper.core.orchestrator.Notebook
import org.anikodev.candywrapper.core.orchestrator.OrchestratorBrief
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorModelRow
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.orchestrator.OrchestratorUi

/** A machine the Orchestrator can run on, as the Machine menu lists it. */
data class OrchestratorMachineChoice(val id: String, val label: String, val connected: Boolean)

private val NoThreads = MutableStateFlow(ThreadListModel())
private val NoServer = MutableStateFlow(ServerInfo())

/** Settings > Orchestrator (CandyWrapper OrchestratorSettingsView), reached from Appearance while Redesign is on. */
@Composable
fun OrchestratorSettingsScreen(graph: AppGraph, nav: Navigator) {
    val config by graph.orchestratorStore.config.collectAsStateWithLifecycle()
    val ui by graph.orchestrator.ui.collectAsStateWithLifecycle()
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    val machineId = config.environmentId?.takeIf { id -> sessions.any { it.id == id } } ?: ui.envId ?: sessions.firstOrNull()?.id
    val session = sessions.firstOrNull { it.id == machineId }
    val threads by (session?.threads ?: NoThreads).collectAsStateWithLifecycle()
    val server by (session?.server ?: NoServer).collectAsStateWithLifecycle()
    val offered = remember(config.models, server, machineId) {
        machineId?.let { id -> graph.orchestrator.availableRows(id).map { it.id }.toSet() } ?: config.models.map { it.id }.toSet()
    }
    ArtEmphasisEffect(ArtEmphasis.SETTINGS)
    OrchestratorSettingsPanel(
        config = config,
        ui = ui,
        machines = sessions.map { OrchestratorMachineChoice(it.id, it.env.value.label, it.state.value is ConnectionState.Live) },
        machineId = machineId,
        projects = threads.projects,
        server = server,
        offered = offered,
        onUpdate = graph.orchestratorStore::update,
        onStart = { graph.orchestrator.startHere() },
        onBack = { nav.pop() },
    )
}

private sealed interface OrchestratorSheet {
    data object Look : OrchestratorSheet
    data class Model(val row: OrchestratorModelRow, val isNew: Boolean) : OrchestratorSheet
}

/**
 * The page itself, from plain values: the status, the Orchestrator's own settings, the Models list, the prompt and the
 * notebook. Every change goes through [onUpdate]; [onStart] is the only call that reaches a server.
 */
@Composable
fun OrchestratorSettingsPanel(
    config: OrchestratorConfig,
    ui: OrchestratorUi,
    machines: List<OrchestratorMachineChoice>,
    machineId: String?,
    projects: List<ProjectSummary>,
    server: ServerInfo,
    /** Ids of the Models rows the machine's catalog offers. */
    offered: Set<String>,
    onUpdate: ((OrchestratorConfig) -> OrchestratorConfig) -> Unit,
    onStart: suspend () -> Unit,
    onBack: () -> Unit,
    openLook: Boolean = false,
) {
    val machineLabel = machines.firstOrNull { it.id == machineId }?.label ?: ui.machine.ifEmpty { "this machine" }
    val identity = BotIdentity.fromRaw(config.botShape, config.botColor)
    var sheet by remember { mutableStateOf<OrchestratorSheet?>(if (openLook) OrchestratorSheet.Look else null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Orchestrator", onBack = onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Spacer(Modifier.padding(top = 8.dp))
                StatusCard(identity, config.displayName, ui, machineLabel, onStart)

                GroupLabel("Orchestrator")
                Card {
                    NameField(config.name) { name -> onUpdate { it.copy(name = name) } }
                    Divider()
                    ListRow(
                        "Look", detail = "Its bot's shape and colour",
                        trailing = { Bot(identity, BotMood.IDLE, 24.dp) },
                    ) { sheet = OrchestratorSheet.Look }
                    Divider()
                    ChoiceRow(
                        "Machine", "Where its thread runs",
                        machines.firstOrNull { it.id == machineId }?.let(::machineName) ?: "None",
                        machines.map { machineName(it) to (it.id == machineId) },
                    ) { i -> onUpdate { it.copy(environmentId = machines[i].id, homeProjectId = null) } }
                    Divider()
                    ChoiceRow(
                        "Home project", "Where its own thread sits",
                        (projects.firstOrNull { it.id == config.homeProjectId } ?: projects.firstOrNull())?.title ?: "No projects",
                        projects.mapIndexed { i, p -> p.title to (p.id == config.homeProjectId || (config.homeProjectId == null && i == 0)) },
                    ) { i -> onUpdate { it.copy(homeProjectId = projects[i].id) } }
                    Divider()
                    Stepper("Worker limit", "Workers it runs at once", config.crewLimit, 1..12) { n -> onUpdate { it.copy(crewLimit = n) } }
                    Divider()
                    MinutesRow(
                        "Ask first", "Asks before prompting a thread you just used",
                        config.askFirstMinutes, ASK_FIRST, { if (it == 0) "Off" else "$it minutes" },
                    ) { m -> onUpdate { it.copy(askFirstMinutes = m) } }
                    Divider()
                    MinutesRow(
                        "While closed", "Applies to an Orchestrator this phone started. The Mac's own check follows the Mac's setting.",
                        config.closedCheckMinutes, WHILE_CLOSED, ::everyLabel,
                    ) { m -> onUpdate { it.copy(closedCheckMinutes = m) } }
                }

                GroupLabel("Models")
                ModelsCard(config.models, server, offered, machineLabel, onEdit = { sheet = OrchestratorSheet.Model(it, isNew = false) }) {
                    val p = server.providers.firstOrNull()
                    val m = p?.models?.firstOrNull()
                    sheet = OrchestratorSheet.Model(
                        OrchestratorModelRow(label = "", instanceId = p?.instanceId.orEmpty(), model = m?.slug.orEmpty(), isDefault = config.models.isEmpty()),
                        isNew = true,
                    )
                }

                GroupLabel("Prompt")
                PromptCard(config.prompt) { text -> onUpdate { it.copy(prompt = text) } }

                GroupLabel("Notebook")
                Card {
                    for ((i, name) in Notebook.names.withIndex()) {
                        if (i > 0) Divider()
                        NoteField(name, config.note(name)) { text -> onUpdate { it.withNote(name, text) } }
                    }
                }
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp))
            }
        }

        when (val s = sheet) {
            OrchestratorSheet.Look -> LookSheet(identity, onDismiss = { sheet = null }) { picked ->
                onUpdate { it.copy(botShape = picked.shape.ordinal, botColor = picked.color.ordinal) }
            }
            is OrchestratorSheet.Model -> ModelSheet(
                s.row, s.isNew, server, config.models,
                onDismiss = { sheet = null },
                onModels = { rows -> onUpdate { it.copy(models = rows) } },
            )
            null -> {}
        }
    }
}

private val ASK_FIRST = listOf(0, 5, 10, 15, 30)
private val WHILE_CLOSED = listOf(0, 15, 30, 60, 120)

private fun everyLabel(m: Int) = when (m) {
    0 -> "Off"
    60 -> "Every hour"
    120 -> "Every 2 hours"
    else -> "Every $m minutes"
}

private fun machineName(m: OrchestratorMachineChoice) = if (m.connected) m.label else "${m.label} (offline)"

@Composable
private fun StatusCard(identity: BotIdentity, name: String, ui: OrchestratorUi, machine: String, onStart: suspend () -> Unit) {
    val c = Candy.colors
    val scope = rememberCoroutineScope()
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Bot(identity, if (ui.working) BotMood.WORKING else BotMood.IDLE, 40.dp)
            Column(Modifier.weight(1f)) {
                Txt(name, CandyType.baseMedium, c.foreground, maxLines = 1)
                Txt(
                    when {
                        ui.threadId != null -> "Running on $machine"
                        ui.discovering -> "Looking for it on $machine"
                        else -> "Not started on $machine"
                    },
                    CandyType.sm, c.mutedForeground, maxLines = 1,
                )
                ui.relayNote?.let { Txt(it, CandyType.sm, c.mutedForeground) }
            }
        }
        if (ui.threadId == null && !ui.discovering) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(
                    "CandyWrapper on your Mac starts its own unless you start it there first. Start it on the Mac if you use both.",
                    CandyType.sm, c.mutedForeground,
                )
                CButton(if (starting) "Starting" else "Start on $machine", enabled = !starting && ui.connected, size = ButtonSize.SM) {
                    starting = true
                    error = null
                    scope.launch {
                        runCatching { onStart() }.onFailure { error = it.message ?: "Couldn't start it on $machine." }
                        starting = false
                    }
                }
                error?.let { Txt(it, CandyType.sm, c.errorForeground) }
            }
        }
    }
}

/** A field the user types in: kept locally so typing stays cheap, written on each change, refreshed when the store moves. */
@Composable
private fun rememberField(stored: String): androidx.compose.runtime.MutableState<String> {
    val local = remember { mutableStateOf(stored) }
    LaunchedEffect(stored) { if (local.value != stored) local.value = stored }
    return local
}

@Composable
private fun NameField(stored: String, onChange: (String) -> Unit) {
    var text by rememberField(stored)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SettingTitle("Name", "In the sidebar and its chat") }
        TextInput(text, { text = it; onChange(it) }, OrchestratorConfig.DEFAULT_NAME, Modifier.width(168.dp))
    }
}

@Composable
private fun SettingTitle(title: String, detail: String?) {
    val c = Candy.colors
    Column {
        Txt(title, CandyType.base, c.foreground)
        if (detail != null) Txt(detail, CandyType.sm, c.mutedForeground)
    }
}

/** A setting whose value opens a menu of [options], each a label and whether it is the current one. Its detail never truncates. */
@Composable
private fun ChoiceRow(label: String, detail: String?, value: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = options.isNotEmpty(), role = Role.Button) { open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { SettingTitle(label, detail) }
            SelectValue(value, open)
        }
        Menu(open, { open = false }, alignEnd = true, minWidth = 200.dp) {
            for ((i, o) in options.withIndex()) MenuItem(o.first, selected = o.second) { open = false; onPick(i) }
        }
    }
}

@Composable
private fun MinutesRow(label: String, detail: String, current: Int, choices: List<Int>, name: (Int) -> String, onPick: (Int) -> Unit) =
    ChoiceRow(label, detail, name(current), choices.map { name(it) to (it == current) }) { onPick(choices[it]) }

@Composable
private fun Stepper(label: String, detail: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val c = Candy.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SettingTitle(label, detail) }
        IconButton(Icons.Minus, "Fewer workers", enabled = value > range.first, box = 32.dp, size = 16.dp) { onChange(value - 1) }
        Txt("$value", CandyType.baseMedium, c.foreground, align = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.size(24.dp, 20.dp).semantics { contentDescription = "$label $value" })
        IconButton(Icons.Plus, "More workers", enabled = value < range.last, box = 32.dp, size = 16.dp) { onChange(value + 1) }
    }
}

// Models

/** "Claude · Claude Opus 5.5 · High": the provider, the model and each option the row sets, by the catalog's names. */
private fun modelLine(row: OrchestratorModelRow, server: ServerInfo): String {
    val provider = server.provider(row.instanceId)
    val model = provider?.model(row.model)
    val options = row.options.map { o ->
        val d = model?.options?.firstOrNull { it.id == o.id }
        when {
            o.flag != null -> "${d?.label ?: o.id} ${if (o.flag == true) "on" else "off"}"
            else -> d?.choices?.firstOrNull { it.id == o.text }?.label ?: o.text.orEmpty()
        }
    }
    return (listOf(provider?.name ?: row.instanceId, model?.name ?: row.model) + options).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun ModelsCard(
    rows: List<OrchestratorModelRow>,
    server: ServerInfo,
    offered: Set<String>,
    machine: String,
    onEdit: (OrchestratorModelRow) -> Unit,
    onAdd: () -> Unit,
) {
    val c = Candy.colors
    Card {
        if (rows.isEmpty()) {
            Txt("No models yet. The Orchestrator uses the thread's default model.", CandyType.sm, c.mutedForeground, modifier = Modifier.padding(16.dp))
        }
        for (row in rows) {
            val (glyph, tint) = providerIcon(server.provider(row.instanceId)?.driver)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit ${row.label}") { onEdit(row) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(glyph, tint, 18.dp, Modifier.padding(top = 1.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(row.label, CandyType.base, c.foreground, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (row.isDefault) DefaultTag()
                    }
                    Txt(modelLine(row, server), CandyType.sm, c.mutedForeground, maxLines = 1)
                    if (row.id !in offered) Txt("Not offered on $machine", CandyType.sm, c.warningForeground, maxLines = 1)
                    if (row.useWhen.isNotEmpty()) Txt(row.useWhen, CandyType.xs, c.mutedForeground.copy(alpha = 0.8f), maxLines = 2)
                }
            }
            Divider()
        }
        ListRow("Add model", icon = Icons.Plus, onClick = onAdd)
    }
}

/** Adds or edits one Models row. Save writes it; Move and Delete write at once. Exactly one row stays the default. */
@Composable
private fun BoxScope.ModelSheet(
    initial: OrchestratorModelRow,
    isNew: Boolean,
    server: ServerInfo,
    rows: List<OrchestratorModelRow>,
    onDismiss: () -> Unit,
    onModels: (List<OrchestratorModelRow>) -> Unit,
) {
    val c = Candy.colors
    var draft by remember { mutableStateOf(initial) }
    val provider = server.provider(draft.instanceId)
    val model = provider?.model(draft.model)
    val index = rows.indexOfFirst { it.id == draft.id }

    fun save() {
        val label = draft.label.trim().ifEmpty { model?.name ?: draft.model }
        if (draft.model.isEmpty()) return
        val saved = draft.copy(label = label)
        var next = if (index >= 0) rows.map { if (it.id == saved.id) saved else it } else rows + saved
        if (saved.isDefault) next = next.map { it.copy(isDefault = it.id == saved.id) }
        if (next.none { it.isDefault } && next.isNotEmpty()) next = next.mapIndexed { i, r -> r.copy(isDefault = i == 0) }
        onModels(next)
        onDismiss()
    }

    Sheet(
        onDismiss, if (isNew) "Add model" else "Edit model",
        description = "Pick a model from this machine's catalog and say when the Orchestrator should use it.",
        footer = {
            CButton("Cancel", kind = ButtonKind.GHOST, size = ButtonSize.SM, onClick = onDismiss)
            CButton(if (isNew) "Add model" else "Save", kind = ButtonKind.PRIMARY, size = ButtonSize.SM, enabled = draft.model.isNotEmpty()) { save() }
        },
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextInput(draft.label, { draft = draft.copy(label = it) }, "Heavy work", label = "Label")
            if (server.providers.isEmpty()) {
                Txt("This machine hasn't listed its models yet. Connect to it to pick one.", CandyType.sm, c.mutedForeground)
            } else {
                SheetChoice("Provider", provider?.name ?: draft.instanceId.ifEmpty { "Choose" }, server.providers.map { it.name to (it.instanceId == draft.instanceId) }) { i ->
                    val p = server.providers[i]
                    draft = draft.copy(instanceId = p.instanceId, model = p.models.firstOrNull()?.slug.orEmpty(), options = emptyList())
                }
                SheetChoice("Model", model?.name ?: draft.model.ifEmpty { "Choose" }, provider?.models.orEmpty().map { it.name to (it.slug == draft.model) }) { i ->
                    draft = draft.copy(model = provider!!.models[i].slug, options = emptyList())
                }
                for (d in model?.options.orEmpty()) {
                    val selection = ModelSelection(draft.instanceId, draft.model, draft.options)
                    if (d.type == "boolean") {
                        val on = selection.option(d.id)?.flag == true
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .clickable(role = Role.Switch, onClickLabel = d.label) { draft = draft.copy(options = selection.withOption(OptionValue(d.id, flag = !on)).options) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(d.label, CandyType.smMedium, c.foreground, modifier = Modifier.weight(1f))
                            Switch(on)
                        }
                    } else {
                        val current = selection.option(d.id)?.text ?: d.defaultChoice?.id
                        SheetChoice(d.label, d.choices.firstOrNull { it.id == current }?.label ?: d.label, d.choices.map { it.label to (it.id == current) }) { i ->
                            draft = draft.copy(options = selection.withOption(OptionValue(d.id, text = d.choices[i].id)).options)
                        }
                    }
                }
            }
            TextInput(draft.useWhen, { draft = draft.copy(useWhen = it) }, "Hard tasks: tricky bugs, concurrency, security", label = "Use when", singleLine = false, minLines = 3)
            TextInput(draft.notes, { draft = draft.copy(notes = it) }, "Optional, such as slower but catches subtle bugs", label = "Notes", singleLine = false, minLines = 2)
        }
        SwitchRow("Default", draft.isDefault, detail = "Used when nothing else fits, and by the Orchestrator's own thread.") { draft = draft.copy(isDefault = it) }
        if (index >= 0) {
            Divider()
            if (index > 0) ListRow("Move up", icon = Icons.ArrowUp) { onModels(rows.toMutableList().apply { add(index - 1, removeAt(index)) }) }
            if (index < rows.lastIndex) ListRow("Move down", icon = Icons.ArrowDown) { onModels(rows.toMutableList().apply { add(index + 1, removeAt(index)) }) }
            ListRow("Delete", icon = Icons.Trash2, labelColor = c.errorForeground) {
                var next = rows.filterNot { it.id == draft.id }
                if (next.none { it.isDefault } && next.isNotEmpty()) next = next.mapIndexed { i, r -> r.copy(isDefault = i == 0) }
                onModels(next)
                onDismiss()
            }
        }
    }
}

/** A labelled select inside a sheet: the label over the value, which opens a menu. */
@Composable
private fun SheetChoice(label: String, value: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(label, CandyType.smMedium, Candy.colors.foreground)
        Box {
            Box(Modifier.clip(RoundedCornerShape(Radius.md)).clickable(role = Role.Button, onClickLabel = label) { open = true }) { SelectValue(value, open) }
            Menu(open, { open = false }, minWidth = 220.dp) {
                for ((i, o) in options.withIndex()) MenuItem(o.first, selected = o.second) { open = false; onPick(i) }
            }
        }
    }
}

// Prompt and notebook

@Composable
private fun PromptCard(stored: String, onChange: (String) -> Unit) {
    var text by rememberField(stored)
    var confirm by remember { mutableStateOf(false) }
    val c = Candy.colors
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingTitle(
                "System prompt",
                "Sent as the first message of its thread. It can use {{name}}, {{machine}}, {{projects}}, {{bots}}, {{models}} and {{crewLimit}}.",
            )
            TextInput(
                text, { text = it; onChange(it) }, "The Orchestrator's prompt", Modifier.heightIn(max = 360.dp),
                singleLine = false, minLines = 6, style = CandyType.monoSmall, label = null,
            )
            Txt("An edit goes out as an updated brief with your next message.", CandyType.xs, c.mutedForeground)
            if (confirm) {
                Txt("Reset the prompt to the default? Your edits are lost.", CandyType.sm, c.mutedForeground)
                Row(horizontalArrangement = ButtonRow) {
                    CButton("Reset", kind = ButtonKind.DESTRUCTIVE, size = ButtonSize.SM) {
                        confirm = false
                        text = OrchestratorBrief.defaultPrompt
                        onChange(OrchestratorBrief.defaultPrompt)
                    }
                    CButton("Keep", kind = ButtonKind.GHOST, size = ButtonSize.SM) { confirm = false }
                }
            } else {
                CButton("Reset to default", size = ButtonSize.SM, enabled = stored != OrchestratorBrief.defaultPrompt) { confirm = true }
            }
        }
    }
}

private val NOTE_TITLES = mapOf("rules" to "Rules", "crew-rules" to "Worker rules", "plan" to "Plan", "digest" to "Digest")
private val NOTE_DETAILS = mapOf(
    "rules" to "How you want it to work. It adds your corrections here.",
    "crew-rules" to "Rules every worker gets in its first prompt.",
    "plan" to "Each goal, its steps and which thread owns each.",
    "digest" to "Its last report.",
)

@Composable
private fun NoteField(name: String, stored: String, onChange: (String) -> Unit) {
    var text by rememberField(stored)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingTitle(NOTE_TITLES[name] ?: name, NOTE_DETAILS[name])
        TextInput(text, { text = it; onChange(it) }, "Empty", Modifier.heightIn(max = 240.dp), singleLine = false, minLines = 2)
    }
}

// Look

/** After the Mac's bot picker: a big preview, every shape in the current colour, then the colour dots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.LookSheet(identity: BotIdentity, onDismiss: () -> Unit, onPick: (BotIdentity) -> Unit) {
    val c = Candy.colors
    val pumpkin = identity.shape == BotShape.PUMPKIN
    Sheet(onDismiss, "Look", description = "Its bot's shape and colour, in the sidebar and its chat.") {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Bot(identity, BotMood.IDLE, 96.dp, description = "${identity.shape.label}, ${colorName(identity.color)}")
        }
        Txt("Shape", CandyType.smMedium, c.foreground, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 7,
        ) {
            for (shape in BotShape.entries) {
                val chosen = shape == identity.shape
                val shape10 = RoundedCornerShape(10.dp)
                Box(
                    Modifier.size(44.dp).clip(shape10)
                        .background(if (chosen) c.secondary else Color.Transparent)
                        .let { if (chosen) it.border(2.dp, c.primary, shape10) else it }
                        .clickable(role = Role.RadioButton, onClickLabel = shape.label) { onPick(BotIdentity.of(shape, identity.color)) }
                        .semantics { contentDescription = shape.label; selected = chosen },
                    contentAlignment = Alignment.Center,
                ) { Bot(BotIdentity.of(shape, identity.color), BotMood.IDLE, 28.dp) }
            }
        }
        Txt(
            if (pumpkin) "Colour: the pumpkin is always orange" else "Colour", CandyType.smMedium, if (pumpkin) c.mutedForeground else c.foreground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp).graphicsLayer { alpha = if (pumpkin) 0.35f else 1f }) {
            for (color in BotColor.entries) {
                val chosen = color == identity.color
                val fill = Color(0xFF000000 or color.rgb.toLong())
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .clickable(enabled = !pumpkin, role = Role.RadioButton, onClickLabel = colorName(color)) { onPick(BotIdentity.of(identity.shape, color)) }
                        .semantics { contentDescription = colorName(color); selected = chosen },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(30.dp).let { if (chosen) it.border(2.dp, c.foreground, CircleShape) else it }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(fill).border(1.dp, c.foreground.copy(alpha = 0.1f), CircleShape))
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

private fun colorName(color: BotColor) = color.name.lowercase().replaceFirstChar { it.uppercase() }
