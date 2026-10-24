package org.anikodev.candywrapper.orchestrator

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.core.model.ModelSelection
import org.anikodev.candywrapper.core.model.ProjectSummary
import org.anikodev.candywrapper.core.model.TurnRow
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.CheckTask
import org.anikodev.candywrapper.core.orchestrator.CrewCard
import org.anikodev.candywrapper.core.orchestrator.MachineRef
import org.anikodev.candywrapper.core.orchestrator.OrchThread
import org.anikodev.candywrapper.core.orchestrator.OrchestratorBrief
import org.anikodev.candywrapper.core.orchestrator.OrchestratorChat
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorEvent
import org.anikodev.candywrapper.core.orchestrator.OrchestratorEventBatch
import org.anikodev.candywrapper.core.orchestrator.OrchestratorMachine
import org.anikodev.candywrapper.core.orchestrator.OrchestratorModelRow
import org.anikodev.candywrapper.core.orchestrator.OrchestratorNeed
import org.anikodev.candywrapper.core.orchestrator.OrchestratorOverview
import org.anikodev.candywrapper.core.orchestrator.OrchestratorRelay
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync
import org.anikodev.candywrapper.core.orchestrator.OrchestratorReply
import org.anikodev.candywrapper.core.orchestrator.ProjectionMessage
import org.anikodev.candywrapper.core.orchestrator.RelayHost
import org.anikodev.candywrapper.core.orchestrator.RelayOwnership
import org.anikodev.candywrapper.core.orchestrator.ScheduleClaim
import org.anikodev.candywrapper.core.orchestrator.TurnWatch
import org.anikodev.candywrapper.core.rpc.RpcConnection
import org.anikodev.candywrapper.core.sync.Commands
import org.anikodev.candywrapper.core.sync.ConnectionState
import org.anikodev.candywrapper.core.sync.Delivery
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.sync.NotConnected
import org.anikodev.candywrapper.core.sync.OrchestratorShell
import org.anikodev.candywrapper.core.sync.UploadedAttachment
import org.anikodev.candywrapper.core.timing.Timeline

/** What the sidebar row, the pane and Settings draw. */
data class OrchestratorUi(
    val active: Boolean = false,
    val name: String = OrchestratorConfig.DEFAULT_NAME,
    val bot: BotIdentity = BotIdentity.ORCHESTRATOR,
    val envId: String? = null,
    val machine: String = "",
    val connected: Boolean = false,
    /** Its thread on [envId], null until found or started. */
    val threadId: String? = null,
    /** Still looking for a thread the Mac started. */
    val discovering: Boolean = false,
    val working: Boolean = false,
    val needs: List<OrchestratorNeed> = emptyList(),
    val crew: List<CrewCard> = emptyList(),
    /** This phone relays events and calls right now. */
    val relaying: Boolean = false,
    /** Why this phone only watches, when it does. */
    val relayNote: String? = null,
)

/**
 * The Orchestrator on this phone (docs/orchestrator.md): finds the thread CandyWrapper on the Mac started, or starts
 * one, sends your messages with an updated brief when the brief changed, and, only while it holds the "while
 * closed" task and the app is in front, relays other threads' turn ends, failures and requests and runs the
 * Orchestrator's `orchestrator-call` blocks on every machine.
 *
 * All state lives on one serial dispatcher. With the toggle off or Redesign off, nothing runs and the shells keep no
 * relay fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorController(private val graph: AppGraph) {
    private val store get() = graph.orchestratorStore
    private val config: OrchestratorConfig get() = store.config.value
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    /** From the activity: true while the app is in front. */
    val foreground = MutableStateFlow(true)

    private val _ui = MutableStateFlow(OrchestratorUi())
    val ui: StateFlow<OrchestratorUi> = _ui

    // Serial state
    @Volatile private var shells: Map<String, OrchestratorShell> = emptyMap()
    /** The home machine's task list on the current connection; absent until it arrives. */
    private val tasks = HashMap<String, List<CheckTask>>()
    private var tasksError: String? = null
    private var taskSubscription: RpcConnection.Subscription? = null
    private var taskConnection: RpcConnection? = null
    private val baselines = HashMap<String, Pair<Long, Map<String, OrchThread>>>()
    private val batch = OrchestratorEventBatch()
    private var flushJob: Job? = null
    private val turnWatch = TurnWatch()
    private val handled = HashSet<String>()
    private var relaying = false
    private var reason: RelayOwnership.Reason? = RelayOwnership.Reason.WAITING
    private var writing = false
    private var retryJob: Job? = null
    private val discovered = HashSet<String>()
    private var discovering = false
    private var running = false
    /** Hands off marks and workers the thread itself carries, whichever device wrote them. */
    private var briefHandsOff: Set<String> = emptySet()
    private var resultWorkers: Set<String> = emptySet()
    /** Notes a reply saved this session, by row id, with what they replaced, for Undo. */
    private val memoryUndo = HashMap<String, List<Pair<String, String>>>()
    private val scanned = HashSet<String>()
    private var lastUserRow: String? = null
    private val _undoable = MutableStateFlow<Set<String>>(emptySet())
    val undoable: StateFlow<Set<String>> = _undoable
    /** The settings documents last seen on its machine, by prompt; null until that machine's task list loads. */
    private var seenSettings: List<String>? = null
    /** The machine the settings sync with, to tell when it moved. */
    private var settingsMachine: String? = null
    /** The stamps last merged, to tell your edits from the other devices'. */
    private var syncedStamps: Map<String, OrchestratorSettingsSync.Stamp> = emptyMap()
    private var settingsChain: Job? = null
    private var settingsPush: Job? = null

    fun start() {
        val active = combine(store.config.map { it.enabled }, graph.redesign.value) { on, redesign -> on && redesign }.distinctUntilChanged()
        scope.launch { active.collectLatest { on -> if (on) runActive() else stopped() } }
    }

    private suspend fun runActive() = coroutineScope {
        running = true
        Timeline.mark("orchestrator.on")
        launch { graph.environments.sessions.collect { list -> list.forEach { it.trackOrchestrator(true) } } }
        launch {
            graph.environments.sessions.flatMapLatest { list ->
                if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { s -> s.orchestratorShell.map { s.id to it } }) { it.toList() }
            }.collect { pairs ->
                shells = pairs.mapNotNull { (id, shell) -> shell?.let { id to it } }.toMap()
                evaluate()
                if (relaying) {
                    relayEvents()
                    watchTurn()
                }
            }
        }
        launch { foreground.collect { evaluate() } }
        launch { store.config.collect { settingsEdited(); evaluate() } }
        launch {
            graph.environments.sessions.flatMapLatest { list -> if (list.isEmpty()) flowOf(Unit) else combine(list.map { it.state }) { } }.collect { evaluate() }
        }
    }

    /** The toggle went off or the theme changed: hand the task back if held, then drop every piece of relay state. */
    private suspend fun stopped() {
        if (!running) return
        running = false
        val home = home()
        val machine = home?.let { config.machines[it] }
        val task = machine?.let { m -> CheckTask.forThread(tasks[home].orEmpty(), m.threadId) }
        if (home != null && machine != null) {
            val d = RelayOwnership.decide(false, foreground.value, home in tasks, task, machine.claim, machine.startedHere, config.closedCheckMinutes > 0)
            if (relaying) takeBatch(true, null)?.let { send(it) }
            if (d.action is RelayOwnership.Action.Release) runCatching { release(home, (d.action as RelayOwnership.Action.Release).taskId) }
        }
        // ponytail: a hold left by a killed process while the toggle was on stays until the next time it's on; release it at boot if that bites
        graph.environments.sessions.value.forEach { it.trackOrchestrator(false) }
        taskSubscription?.cancel()
        taskSubscription = null
        taskConnection = null
        tasks.clear()
        shells = emptyMap()
        baselines.clear()
        batch.reset()
        flushJob?.cancel()
        turnWatch.reset()
        relaying = false
        settingsPush?.cancel()
        seenSettings = null
        settingsMachine = null
        _ui.value = OrchestratorUi()
    }

    // Machines

    private fun sessions(): List<EnvironmentSession> = graph.environments.sessions.value

    private fun session(id: String?) = id?.let(graph.environments::session)

    private fun live(id: String) = session(id)?.state?.value is ConnectionState.Live

    /** The machine it runs on: the one picked in Settings, else where a thread was found, else the first. */
    fun home(): String? {
        val ids = sessions().map { it.id }
        return config.environmentId?.takeIf { it in ids } ?: config.machines.keys.firstOrNull { it in ids } ?: ids.firstOrNull()
    }

    private fun label(id: String) = session(id)?.env?.value?.label ?: id

    private fun machines(): List<MachineRef> {
        val home = home()
        return sessions().map { s -> MachineRef(s.id, s.env.value.label, s.id == home, live(s.id) && shells[s.id] != null) }.sortedBy { !it.isHome }
    }

    /** The Models rows whose model the machine's catalog offers; the rest stay out of the brief. */
    fun availableRows(env: String): List<OrchestratorModelRow> {
        val providers = session(env)?.server?.value?.providers.orEmpty()
        if (providers.isEmpty()) return config.models
        return config.models.filter { r -> providers.firstOrNull { it.instanceId == r.instanceId }?.model(r.model) != null }
    }

    /** What the Orchestrator's own turns run on: the default row, else the machine's default model. */
    private fun ownSelection(env: String): ModelSelection? =
        availableRows(env).let { rows -> rows.firstOrNull { it.isDefault } ?: rows.firstOrNull() }?.selection ?: session(env)?.server?.value?.defaultModel

    private fun handsOff(key: String) = config.isHandsOff(key) || key in briefHandsOff

    // Who relays

    private fun evaluate() {
        if (!running) return
        val home = home()
        val machine = home?.let { config.machines[it] }
        val shell = home?.let { shells[it] }
        if (home != null && machine != null && shell?.live == true && shell.threads.none { it.id == machine.threadId }) {
            // Archived or deleted: Start, or the next discovery, finds another.
            store.update { it.copy(machines = it.machines - home) }
            discovered -= home
            return evaluate()
        }
        watchSettings(home)
        manageTasks(home)
        if (home != null && machine == null && shell?.live == true && home in tasks && home !in discovered && !discovering) {
            discovered += home
            discovering = true
            scope.launch { try { discover(home) } finally { discovering = false; evaluate() } }
        }
        if (home == null || machine == null) {
            setRelaying(false, null, null)
            return publish()
        }
        val task = CheckTask.forThread(tasks[home].orEmpty(), machine.threadId)
        val d = RelayOwnership.decide(true, foreground.value, home in tasks, task, machine.claim, machine.startedHere, config.closedCheckMinutes > 0)
        if (d.forgetClaim) setClaim(home, null)
        if (!writing && retryJob?.isActive != true) when (val a = d.action) {
            is RelayOwnership.Action.Claim -> write(home) { claim(home, a.taskId) }
            is RelayOwnership.Action.Release -> { flushNow(null); write(home) { release(home, a.taskId) } }
            RelayOwnership.Action.Create -> write(home) { create(home, machine) }
            RelayOwnership.Action.None -> Unit
        }
        setRelaying(d.relaying, d.reason, task)
        publish()
    }

    /** Subscribes to the home machine's scheduled tasks while the app is in front, once per connection. */
    private fun manageTasks(home: String?) {
        val conn = if (home != null && foreground.value && live(home)) session(home)?.liveConnection() else null
        if (conn === taskConnection) return
        taskSubscription?.cancel()
        taskSubscription = null
        taskConnection = conn
        // A list from an older connection stays, so leaving the front can still hand the task back.
        if (conn == null || home == null) return
        // A new connection starts unknown: a list from before could say "on" after the Mac turned it off.
        tasks.remove(home)
        tasksError = null
        // The first list on a connection syncs, so an edit that couldn't go up while offline goes up now.
        seenSettings = null
        taskSubscription = conn.subscribe("scheduledTasks.subscribe", JsonObject(emptyMap()), onEnd = { error ->
            if (error != null) scope.launch {
                if (taskConnection === conn) { tasksError = error.message; publish() }
            }
        }) { values ->
            val json = values.lastOrNull() as? JsonObject
            val list = CheckTask.list(json)
            val settings = OrchestratorSettingsSync.tasks(json)
            scope.launch {
                if (taskConnection !== conn) return@launch
                tasks[home] = list
                settingsListChanged(home, settings)
                evaluate()
            }
        }
    }

    private fun write(home: String, block: suspend () -> Unit) {
        writing = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Timeline.mark("orchestrator.schedule.failed", e.message?.take(120))
                // One try a minute while it keeps failing; any push re-decides sooner.
                retryJob = scope.launch { delay(60_000); evaluate() }
            } finally {
                writing = false
                evaluate()
            }
        }
    }

    private suspend fun setEnabled(home: String, taskId: String, enabled: Boolean): CheckTask? {
        val result = session(home)?.request("scheduledTasks.setEnabled", buildJsonObject { put("id", taskId); put("enabled", enabled) }) as? JsonObject
        val task = CheckTask.fromResult(result) ?: return null
        tasks[home] = tasks[home].orEmpty().filter { it.id != task.id } + task
        return task
    }

    private suspend fun claim(home: String, taskId: String) {
        val before = tasks[home]?.firstOrNull { it.id == taskId }
        val task = setEnabled(home, taskId, false) ?: return
        // The server writes nothing for a task already off, and then the off is someone else's.
        if (before != null && task.updatedAt == before.updatedAt) return
        setClaim(home, ScheduleClaim(task.id, task.updatedAt))
        Timeline.mark("orchestrator.relay.claimed")
    }

    private suspend fun release(home: String, taskId: String) {
        setEnabled(home, taskId, true)
        setClaim(home, null)
        Timeline.mark("orchestrator.relay.released")
    }

    /** A thread this phone started gets its own "while closed" task, created off and held. */
    private suspend fun create(home: String, machine: OrchestratorMachine) {
        val session = session(home) ?: throw NotConnected()
        val thread = shells[home]?.threads?.firstOrNull { it.id == machine.threadId } ?: return
        val selection = ownSelection(home) ?: thread.model ?: return
        val minutes = config.closedCheckMinutes.takeIf { it > 0 } ?: 30
        val result = session.request("scheduledTasks.upsert", buildJsonObject {
            put("title", "${config.displayName} check")
            put("prompt", OrchestratorBrief.scheduledCheckPrompt)
            put("enabled", false)
            put("schedule", buildJsonObject { put("type", "interval"); put("everyMs", minutes * 60_000L) })
            put("projectId", thread.projectId)
            put("threadId", thread.id)
            put("workspaceStrategy", buildJsonObject { put("type", "root") })
            put("modelSelection", selection.toJson())
            put("runtimeMode", session.server.value.defaultRuntimeMode)
            put("interactionMode", "default")
            put("creationSource", Commands.SOURCE)
        }) as? JsonObject
        val task = CheckTask.fromResult(result) ?: return
        tasks[home] = tasks[home].orEmpty() + task
        setClaim(home, ScheduleClaim(task.id, task.updatedAt))
    }

    private fun setClaim(home: String, claim: ScheduleClaim?) {
        store.update { c -> c.machines[home]?.let { m -> c.copy(machines = c.machines + (home to m.copy(claim = claim))) } ?: c }
    }

    private fun setRelaying(next: Boolean, why: RelayOwnership.Reason?, task: CheckTask?) {
        reason = why
        if (next == relaying) return
        relaying = next
        if (next) {
            // A fresh start: what happened before this phone took over is not news to send.
            baselines.clear()
            turnWatch.reset()
            batch.reset()
        } else {
            val takeover = task?.takeIf { why == RelayOwnership.Reason.OTHER_APP }?.updatedAtMs
            flushNow(takeover)
            batch.reset()
            flushJob?.cancel()
        }
    }

    // Finding the thread

    /**
     * The Mac remembers its thread only in its own defaults, so the phone finds it on the server: the thread its
     * "while closed" task names, else a top-level thread with its name whose first message is a brief.
     */
    private suspend fun discover(home: String) {
        val shell = shells[home] ?: return
        val top = shell.threads.filter { it.parentThreadId == null && !it.subagent }
        val fromTask = tasks[home].orEmpty().filter { it.isCheck }.sortedByDescending { it.updatedAtMs }
            .firstNotNullOfOrNull { t -> top.firstOrNull { it.id == t.threadId } }
        if (fromTask != null) return adopt(home, fromTask)
        val names = setOf(config.displayName.lowercase(), OrchestratorConfig.DEFAULT_NAME.lowercase())
        for (candidate in top.filter { it.title.lowercase() in names }.sortedByDescending { it.updatedAt }.take(3)) {
            val messages = runCatching { projection(home, candidate.id) }.getOrNull() ?: continue
            if (messages.firstOrNull { it.role == "user" }?.text?.startsWith(OrchestratorBrief.FIRST_HEADING) == true) return adopt(home, candidate)
        }
    }

    /**
     * Takes over a thread another CandyWrapper started. The phone's own brief counts as sent, so only a change made
     * here goes out as an updated brief: switching devices never replaces the Mac's brief on its own.
     */
    private fun adopt(home: String, thread: OrchThread) {
        if (config.machines[home] != null) return
        // Only a name no device ever set: the shared settings' name wins over the thread's title.
        if (config.name == OrchestratorConfig.DEFAULT_NAME && thread.title != config.name && "name" !in store.stamps) store.update { it.copy(name = thread.title) }
        val brief = renderBrief(home)
        store.update { it.copy(machines = it.machines + (home to OrchestratorMachine(thread.id, brief.second))) }
        Timeline.mark("orchestrator.adopted", label(home))
    }

    private suspend fun projection(env: String, threadId: String): List<ProjectionMessage> {
        val json = session(env)?.request("orchestration.getThreadProjection", buildJsonObject { put("threadId", threadId) }, 30_000) as? JsonObject
        return ProjectionMessage.list(json)
    }

    // The brief

    /** The brief for [env] and its fingerprint. */
    fun renderBrief(env: String): Pair<String, String> {
        val projects = shells[env]?.projects ?: sessions().firstOrNull { it.id == env }?.threads?.value?.projects.orEmpty()
        val handsOff = config.handsOff.mapNotNull { key ->
            val machine = key.substringBefore(':')
            val local = key.substringAfter(':')
            if (session(machine) == null) return@mapNotNull null
            val title = shells[machine]?.threads?.firstOrNull { it.id == local }?.title ?: "Untitled"
            "“$title” (t3-thread://v1/$local)" + if (machine == env) "" else " on ${label(machine)} (machine $machine)"
        }.sorted()
        val inputs = OrchestratorBrief.Inputs(
            config.displayName, label(env), projects.map { OrchestratorBrief.Project(it.title, it.id, it.workspaceRoot) }, config.crewLimit,
            availableRows(env), config.notebook, handsOff, config.askFirstMinutes, machines(),
        )
        return OrchestratorBrief.render(config.prompt, inputs) to OrchestratorBrief.fingerprint(config.prompt, inputs)
    }

    // Talking to it

    private fun orchestratorThread(): OrchThread? {
        val home = home() ?: return null
        val id = config.machines[home]?.threadId ?: return null
        return shells[home]?.threads?.firstOrNull { it.id == id }
    }

    /** One message to the Orchestrator; behind a running turn it queues instead of interrupting. */
    private suspend fun dispatch(text: String, attachments: List<UploadedAttachment> = emptyList()) {
        val home = home() ?: throw NotConnected()
        val id = config.machines[home]?.threadId ?: throw NotConnected()
        val session = session(home) ?: throw NotConnected()
        val thread = orchestratorThread()
        val busy = thread?.running == true
        session.dispatch(Commands.sendMessage(
            id, text, attachments,
            activeRunId = if (busy) thread?.latestRunId else null,
            delivery = if (busy) Delivery.QUEUE else Delivery.AUTO,
            serverResolvesDelivery = session.server.value.serverResolvesDelivery,
            model = ownSelection(home) ?: thread?.model,
        ))
    }

    /** Your message from the pane, with the updated brief in front when the brief changed since the thread got it. */
    suspend fun send(text: String, attachments: List<UploadedAttachment>) = withContext(serial) {
        val home = home() ?: throw NotConnected()
        val machine = config.machines[home] ?: throw NotConnected()
        val message = if (text.isEmpty() && attachments.isNotEmpty()) OrchestratorChat.ATTACHMENT_ONLY_PROMPT else text
        val (brief, fingerprint) = renderBrief(home)
        val changed = fingerprint != machine.briefDigest
        dispatch(if (changed) OrchestratorBrief.updated(brief, message) else message, attachments)
        if (changed) store.update { c -> c.machines[home]?.let { c.copy(machines = c.machines + (home to it.copy(briefDigest = fingerprint))) } ?: c }
    }

    /**
     * Starts an Orchestrator on its machine when none was found there. The Mac doesn't adopt a thread it didn't
     * start, so CandyWrapper on the Mac would start its own; the Settings row says so.
     */
    suspend fun startHere() = withContext(serial) {
        val home = home() ?: throw NotConnected()
        if (config.machines[home] != null) return@withContext
        val session = session(home) ?: throw NotConnected()
        val projects = shells[home]?.projects.orEmpty()
        val project = projects.firstOrNull { it.id == config.homeProjectId } ?: projects.firstOrNull()
            ?: throw IllegalStateException("Add a project on ${label(home)} first. Its thread lives in one.")
        val selection = ownSelection(home) ?: throw IllegalStateException("No model is available on ${label(home)}. Add one in Settings > Orchestrator.")
        val id = UUID.randomUUID().toString()
        val (brief, fingerprint) = renderBrief(home)
        session.dispatch(Commands.createThread(id, project.id, config.displayName, selection, session.server.value.defaultRuntimeMode, "default"))
        store.update { it.copy(machines = it.machines + (home to OrchestratorMachine(id, fingerprint, startedHere = true))) }
        session.dispatch(Commands.sendMessage(id, OrchestratorBrief.first(brief), model = selection))
    }

    /** Allow or Deny through T3's own `runtime-request.respond`. The Orchestrator never approves. */
    suspend fun respond(need: OrchestratorNeed, allow: Boolean) {
        val session = session(need.envId) ?: throw NotConnected()
        session.dispatch(Commands.approve(need.threadId, need.requestId, if (allow) "accept" else "decline"))
    }

    /** A published page's signed inline URL (`assets.createUrl`, disposition inline), to open in the browser. */
    suspend fun pageUrl(attachmentId: String, fileName: String): String {
        val session = session(home()) ?: throw NotConnected()
        val result = session.request("assets.createUrl", buildJsonObject {
            put("resource", buildJsonObject {
                put("_tag", "attachment"); put("attachmentId", attachmentId); put("fileName", fileName)
                put("mimeType", "text/html"); put("disposition", "inline")
            })
        }) as? JsonObject
        val relative = (result?.get("relativeUrl") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw java.io.IOException("The computer did not return the page's address.")
        return session.resolve(relative).toString()
    }

    /** Where a thread a link names lives: its scoped machine when it has it there, else any machine that has it. */
    fun locate(threadId: String): String? {
        val home = home()
        if (home != null && shells[home]?.threads?.any { it.id == threadId } == true) return home
        return shells.entries.firstOrNull { (_, s) -> s.threads.any { it.id == threadId } }?.key ?: home
    }

    fun toggleHandsOff(key: String) = store.update { c -> c.copy(handsOff = if (key in c.handsOff) c.handsOff - key else c.handsOff + key) }

    // The event relay

    /** Diffs every machine's threads against the last live state and batches what changed. Runs on pushes only. */
    private fun relayEvents() {
        val home = home() ?: return
        val orchestrator = config.machines[home]?.threadId?.let { OrchestratorConfig.key(home, it) } ?: return
        val now = System.currentTimeMillis()
        val events = ArrayList<OrchestratorEvent>()
        for ((env, shell) in shells) {
            if (!shell.live) continue
            val current = shell.threads.associateBy { it.key }
            val old = baselines[env]
            baselines[env] = shell.generation to current
            // The first state of a connection is the baseline; nothing in it is news.
            if (old == null || old.first != shell.generation) continue
            events += OrchestratorEvent.diff(old.second, current.values, orchestrator, ::handsOff, config.askFirstMinutes, now,
                { config.relayedAt[it] }, if (env == home) null else label(env))
        }
        val deadline = batch.add(events, now) ?: return
        flushJob = scope.launch {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            takeBatch(force = false, takeover = null)?.let { send(it) }
        }
    }

    /** Sends what the batch holds now, as when this phone stops relaying. The text is taken before anything resets. */
    private fun flushNow(takeover: Long?) {
        flushJob?.cancel()
        val text = takeBatch(force = true, takeover = takeover) ?: return
        scope.launch { send(text) }
    }

    private fun takeBatch(force: Boolean, takeover: Long?): String? {
        val live = shells.values.flatMap { s -> s.threads.map { it.key } }.toSet()
        return batch.flush(System.currentTimeMillis() + 1, force) { e ->
            e.key in live && (takeover == null || RelayOwnership.keepAtHandoff(e, takeover))
        }
    }

    private suspend fun send(text: String) {
        runCatching { dispatch(text) }.onFailure { Timeline.mark("orchestrator.events.failed", it.message?.take(120)) }
    }

    // The call relay

    /** The Orchestrator's turn ended while this phone relays: run what it asked for on every machine. */
    private fun watchTurn() {
        val home = home() ?: return
        val shell = shells[home] ?: return
        val thread = orchestratorThread() ?: return
        if (!turnWatch.ended(shell.generation, thread.running)) return
        val run = thread.latestRunId
        scope.launch { runCatching { runCalls(home, thread.id, run) }.onFailure { Timeline.mark("orchestrator.calls.failed", it.message?.take(120)) } }
    }

    private suspend fun runCalls(home: String, threadId: String, run: String?) {
        // Another app that just took over gets the turn: its write lands on the task within a round trip.
        delay(CALL_GRACE_MS)
        if (!relaying) return
        val messages = projection(home, threadId)
        learn(messages)
        val replies = RelayOwnership.replies(messages, run, handled)
        handled += replies.map { it.id }
        val calls = replies.flatMap { OrchestratorReply.parse(it.text).calls }
        if (calls.isEmpty() || RelayOwnership.alreadyAnswered(messages, replies)) return
        Timeline.mark("orchestrator.calls", "${calls.size}")
        val text = OrchestratorRelay(host).run(calls) ?: return
        dispatch(text)
    }

    /** The Hands off list of the thread's latest brief, and every worker its results launched. */
    private fun learn(messages: List<ProjectionMessage>) {
        val home = home() ?: return
        val users = messages.filter { it.role == "user" }
        users.lastOrNull { OrchestratorBrief.split(it.text).brief != null }?.let { m ->
            briefHandsOff = OrchestratorBrief.handsOffIn(OrchestratorBrief.split(m.text).brief!!).map { (env, id) -> OrchestratorConfig.key(env ?: home, id) }.toSet()
        }
        // Only running workers count, so the set only grows; a page of rows adds what an earlier read missed.
        resultWorkers = resultWorkers + users.filter { OrchestratorRelay.isResultMessage(it.text) }.flatMap { OrchestratorRelay.launchedIn(it.text) }
    }

    private val host = object : RelayHost {
        override fun machines() = this@OrchestratorController.machines()
        override fun projects(machine: String): List<ProjectSummary>? = shells[machine]?.takeIf { it.live }?.projects
        override fun threads(machine: String): List<OrchThread>? = shells[machine]?.takeIf { it.live }?.threads
        override suspend fun request(machine: String, method: String, payload: JsonObject): JsonElement? =
            (session(machine) ?: throw NotConnected()).request(method, payload, 30_000)
        override fun config() = this@OrchestratorController.config
        override fun update(change: (OrchestratorConfig) -> OrchestratorConfig) = store.update(change)
        override fun rows(machine: String) = availableRows(machine)
        override fun runtimeMode(machine: String) = session(machine)?.server?.value?.defaultRuntimeMode ?: "full-access"
        override fun serverResolvesDelivery(machine: String) = session(machine)?.server?.value?.serverResolvesDelivery == true
        override fun orchestratorKey() = home()?.let { h -> config.machines[h]?.threadId?.let { OrchestratorConfig.key(h, it) } }
        override fun briefHandsOff() = this@OrchestratorController.briefHandsOff
        override fun resultWorkers() = this@OrchestratorController.resultWorkers
    }

    // Memory

    /** The pane's rows changed: save each finished reply's memory blocks to the notebook once, keeping Undo. */
    fun paneRows(rows: List<TurnRow>) = scope.launch {
        val saved = config.savedMemoryMessages.toHashSet()
        // Each finished reply is read once; a streaming one waits until it finishes.
        val fresh = rows.filter { it.type == "assistant_message" && !it.streaming && it.id !in saved && scanned.add(it.id) }
        val changes = fresh.mapNotNull { r -> OrchestratorReply.parse(r.text.orEmpty()).memories.takeIf { it.isNotEmpty() }?.let { r.id to it } }
        val users = rows.filter { it.type == "user_message" }
        if (users.lastOrNull()?.id != lastUserRow) {
            lastUserRow = users.lastOrNull()?.id
            learn(users.map { ProjectionMessage(it.id, "user", it.text.orEmpty(), false, it.atMs, it.runId) })
        }
        if (changes.isEmpty()) return@launch
        store.update { c ->
            var next = c
            for ((id, memories) in changes) {
                memoryUndo[id] = memories.map { it.note to next.note(it.note) }
                for (m in memories) next = next.withNote(m.note, m.text)
            }
            next.copy(savedMemoryMessages = (next.savedMemoryMessages + changes.map { it.first }).takeLast(200))
        }
        _undoable.value = memoryUndo.keys.toSet()
    }

    fun undoMemory(rowId: String) = scope.launch {
        val previous = memoryUndo.remove(rowId) ?: return@launch
        store.update { c -> previous.reversed().fold(c) { acc, (note, text) -> acc.withNote(note, text) } }
        _undoable.value = memoryUndo.keys.toSet()
    }

    // Settings on every device

    /** Follows the machine the settings live on; after you move it, the old machine's document names the new one. */
    private fun watchSettings(home: String?) {
        if (home == null || home == settingsMachine) return
        val old = settingsMachine
        // Only a machine whose list was read, so sessions arriving in another order at boot leave no stray task.
        val read = seenSettings != null
        settingsMachine = home
        seenSettings = null
        if (old != null && read && store.stamps["environmentId"]?.by == store.device) syncSettings(old)
    }

    /**
     * The list's first load, or a write from another device. Their values apply at once, before discovery or the
     * brief reads them; a write waits for a fresh list. Echoes of this phone's own write merge to nothing.
     */
    private fun settingsListChanged(home: String, found: List<OrchestratorSettingsSync.SettingsTask>) {
        val prompts = found.map { it.prompt }.sorted()
        if (prompts == seenSettings) return
        seenSettings = prompts
        val m = store.merge { c, s -> OrchestratorSettingsSync.merge(c, s, found.mapNotNull { it.document }, home, home()) }
        syncedStamps = m.stamps
        if (m.write != null || found.size > 1) syncSettings(home)
    }

    /** Your edit goes up a second after the last change, so typing in the prompt writes once. */
    private fun settingsEdited() {
        val device = store.device
        if (store.stamps.none { (key, stamp) -> stamp.by == device && syncedStamps[key] != stamp }) return
        settingsPush?.cancel()
        settingsPush = scope.launch {
            delay(SETTINGS_DEBOUNCE_MS)
            home()?.let(::syncSettings)
        }
    }

    /** One sync at a time, so writes land in order. */
    private fun syncSettings(env: String) {
        val previous = settingsChain
        settingsChain = scope.launch {
            previous?.join()
            runCatching { pushSettings(env) }.onFailure { Timeline.mark("orchestrator.settings.failed", it.message?.take(120)) }
        }
    }

    /** Merges [env]'s settings documents with this phone's, then writes the result back when it differs. */
    private suspend fun pushSettings(env: String) {
        if (!running) return
        val session = session(env) ?: return
        val json = session.request("scheduledTasks.list") as? JsonObject ?: return
        if (!running) return
        val found = OrchestratorSettingsSync.tasks(json)
        val m = store.merge { c, s -> OrchestratorSettingsSync.merge(c, s, found.mapNotNull { it.document }, env, home()) }
        syncedStamps = m.stamps
        val plan = OrchestratorSettingsSync.plan(m, found, if (found.isEmpty()) newSettingsTask(env) else null)
        session.request("scheduledTasks.upsert", plan.upsert ?: return)
        // Two devices may each have made one; every side keeps the smallest id and deletes the rest.
        for (id in plan.delete) runCatching { session.request("scheduledTasks.delete", Commands.deleteScheduledTask(id)) }
        Timeline.mark("orchestrator.settings.written", label(env))
    }

    /** A machine with no settings task gets one in the home project, else its first, on the default model row. */
    private fun newSettingsTask(env: String): OrchestratorSettingsSync.NewTask? {
        val projects = shells[env]?.projects ?: session(env)?.threads?.value?.projects.orEmpty()
        val project = projects.firstOrNull { it.id == config.homeProjectId } ?: projects.firstOrNull() ?: return null
        return OrchestratorSettingsSync.NewTask(project.id, ownSelection(env) ?: return null)
    }

    // What the views draw

    private fun publish() {
        if (!running) return
        val c = config
        val home = home()
        val shell = home?.let { shells[it] }
        val machine = home?.let { c.machines[it] }
        val orchestratorKey = machine?.let { OrchestratorConfig.key(home, it.threadId) }
        val threads = shell?.threads.orEmpty()
        val thread = machine?.let { m -> threads.firstOrNull { it.id == m.threadId } }
        val next = OrchestratorUi(
            active = true,
            name = c.displayName,
            bot = BotIdentity.fromRaw(c.botShape, c.botColor),
            envId = home,
            machine = home?.let(::label) ?: "No machine",
            connected = home != null && live(home),
            threadId = machine?.threadId,
            discovering = discovering || (machine == null && home != null && home !in discovered),
            working = thread?.running == true,
            needs = OrchestratorOverview.needs(threads),
            crew = OrchestratorOverview.crew(threads, shell?.projects.orEmpty(), c, orchestratorKey, System.currentTimeMillis(), ::handsOff),
            relaying = relaying,
            relayNote = if (relaying || machine == null) null else note(reason),
        )
        if (next != _ui.value) _ui.value = next
    }

    private fun note(reason: RelayOwnership.Reason?): String? = when (reason) {
        RelayOwnership.Reason.WAITING -> tasksError?.let { "This computer didn't share its scheduled tasks, so this phone only watches." }
        RelayOwnership.Reason.NO_TASK -> "Its \"while closed\" check is off, so this phone can't tell whether CandyWrapper is open on your Mac. It only watches; events and calls go through the Mac."
        RelayOwnership.Reason.OTHER_APP -> "CandyWrapper on your Mac relays while it's open. Quit it there and this phone takes over."
        else -> null
    }

    private companion object {
        const val CALL_GRACE_MS = 2_000L
        const val SETTINGS_DEBOUNCE_MS = 1_000L
    }
}
