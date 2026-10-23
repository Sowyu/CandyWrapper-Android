package org.anikodev.candywrapper

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.anikodev.candywrapper.core.sidebar.InboxReturns
import org.anikodev.candywrapper.core.stash.PromptStash
import org.anikodev.candywrapper.core.connect.ClerkAccount
import org.anikodev.candywrapper.core.connect.ClerkClient
import org.anikodev.candywrapper.core.connect.ConnectAuthorizer
import org.anikodev.candywrapper.core.connect.RelayClient
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.sync.BearerCredentials
import org.anikodev.candywrapper.core.sync.ConnectCredentials
import org.anikodev.candywrapper.core.sync.Environments
import org.anikodev.candywrapper.core.timing.Timeline
import org.anikodev.candywrapper.data.ImageStore
import org.anikodev.candywrapper.data.ProjectIconStore
import org.anikodev.candywrapper.data.CacheDb
import org.anikodev.candywrapper.data.KeystoreDPoPKey
import org.anikodev.candywrapper.data.LazyDPoPKey
import org.anikodev.candywrapper.data.SecretBox
import org.anikodev.candywrapper.ui.DitherArtStore
import org.anikodev.candywrapper.ui.RedesignStyle
import org.anikodev.candywrapper.ui.ThemeChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.anikodev.candywrapper.update.Updater

/**
 * Everything the app holds for the life of the process, built by hand (no DI framework).
 *
 * [boot] runs on its own thread from `Application.onCreate`: it opens the cache, restores every
 * environment's thread list, releases [awaitReady], and only then starts connecting. The activity
 * waits on [awaitReady] so its first frame shows cached data.
 */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = CountDownLatch(1)
    private val _readyFlow = MutableStateFlow(false)
    val readyFlow: StateFlow<Boolean> = _readyFlow

    lateinit var db: CacheDb
        private set
    lateinit var secrets: SecretBox
        private set
    lateinit var clerk: ClerkClient
        private set
    lateinit var environments: Environments
        private set

    val updater by lazy { Updater(app, scope) }

    /** The prompt stash, on this phone only. Its file is read on the boot thread. */
    val stash by lazy { PromptStash(java.io.File(app.filesDir, "prompt-stash")) }

    /** The thread's images on disk and in memory (data/ImageStore). */
    val images by lazy { ImageStore(app.cacheDir) { app.contentResolver.openInputStream(android.net.Uri.parse(it)) } }

    /** Project icons, read back on the boot thread so the first frame has them (data/ProjectIconStore). */
    val projectIcons = ProjectIconStore(app.filesDir, scope) { environments.session(it) }

    /** The environment whose threads were open last, to reopen on a cold start. */
    @Volatile var lastEnvironmentId: String? = null
        private set

    /** The thread open last in [lastEnvironmentId], reopened with the sidebar closed on a cold start. */
    @Volatile var lastThreadId: String? = null
        private set

    private val _theme = MutableStateFlow(ThemeChoice.SYSTEM)
    /** Light, dark or follow the system; stored with the UI state. */
    val theme: StateFlow<ThemeChoice> = _theme

    /** Settings > Appearance > Redesign (Experimental) and its options, all stored on this phone. Off by default. */
    val redesign = UiSetting(REDESIGN, false)
    val redesignAccent = UiValue(REDESIGN_ACCENT)
    val redesignPicture = UiValue(REDESIGN_PICTURE)
    val redesignDim = UiValue(REDESIGN_DIM)
    /** Redesign's sidebar sections the user folded, project group keys joined by newlines. */
    val redesignFolded = UiValue(REDESIGN_FOLDED)

    /** Redesign's dithered art, rendered once per picture, size and appearance and cached on disk. */
    val ditherArt by lazy { DitherArtStore(app.filesDir, app.cacheDir) }

    /** Settings > Legacy sidebar: T3's per-project tree instead of the new flat sidebar. Off by default, as `legacySidebarEnabled`. */
    val legacySidebar = UiSetting(LEGACY_SIDEBAR, false)

    /** Settings > Plan mode: the Plan toggle in the composer. Off by default, as `planModeEnabled`; off means build mode. */
    val planMode = UiSetting(PLAN_MODE, false)

    /**
     * The sidebar's machine: an environment id or [ALL_MACHINES]. Null until the user picks one;
     * the sidebar then shows all machines when more than one is configured.
     */
    val machineScope = UiValue(MACHINE_SCOPE)

    /** The new sidebar's project filter: a project group key, null for All projects (`sidebarProjectScopeKey`). */
    val projectScope = UiValue(PROJECT_SCOPE)

    /** Whether the Settled and Snoozed shelves are open (T3 keeps both in local storage, closed by default). */
    val settledShelf = UiSetting(SETTLED_SHELF, false)
    val snoozedShelf = UiSetting(SNOOZED_SHELF, false)

    /** Settings > Working section: T3's beta `sidebarWorkingShelfEnabled`, off by default as in T3 and CandyWrapper. */
    val workingSection = UiSetting(WORKING_SECTION, false)
    /** Whether the Working shelf is open; closed by default, as CandyWrapper's. */
    val workingShelf = UiSetting(WORKING_SHELF, false)

    /** When threads left the Working shelf, for the inbox order; kept for the app's life, as T3 keeps it at module scope. */
    val inboxReturns = InboxReturns()

    private val _account = MutableStateFlow<ClerkAccount?>(null)
    val account: StateFlow<ClerkAccount?> = _account

    fun boot() = thread(name = "cw-boot") {
        val start = Timeline.nowMs()
        // Icon thumbnails decode beside the SQLite read and are in memory before the first frame.
        val icons = thread(name = "cw-icons") { Timeline.timed("cache.icons.loaded") { projectIcons.hydrate() } }
        db = CacheDb(app)
        secrets = SecretBox(db)
        clerk = ClerkClient(secrets, ClerkClient.Device(Build.VERSION.RELEASE, BuildConfig.VERSION_NAME, app.packageName, Build.MODEL))
        val key = LazyDPoPKey { Timeline.timed("keystore.dpop.loaded") { KeystoreDPoPKey.loadOrCreate() } }
        val relay = RelayClient(key, clerk, secrets)
        val authorizer = ConnectAuthorizer(relay, clerk, secrets)
        environments = Environments(db, secrets, BearerCredentials(secrets), ConnectCredentials(authorizer), authorizer, relay, scope)
        val envs = db.environments()
        val shells = envs.associate { it.id to db.shell(it.id) }
        lastEnvironmentId = db.uiValue(LAST_ENV)
        lastThreadId = db.uiValue(LAST_THREAD)
        _theme.value = db.uiValue(THEME)?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.SYSTEM
        for (s in listOf(legacySidebar, planMode, settledShelf, snoozedShelf, workingSection, workingShelf, redesign)) s.load()
        for (v in listOf(machineScope, projectScope, redesignAccent, redesignPicture, redesignDim, redesignFolded)) v.load()
        stash.entries
        environments.restore(envs, shells)
        Timeline.span("cache.loaded", start, "${envs.size} environments, ${shells.values.sumOf { it?.threads?.size ?: 0 }} threads")
        // The first frame shows the last thread, so its rows are read here, while the activity is created.
        val reopen = lastThreadId?.let { t -> lastEnvironmentId?.let(environments::session)?.thread(t) }
        if (reopen != null) Timeline.timed("cache.thread.preloaded") { reopen.preload() }
        icons.join(ICONS_WAIT_MS)
        _readyFlow.value = true
        ready.countDown()

        Http.prefetchDns(scope, envs.flatMap { e -> listOf(e.httpBase) + e.directRoutes.map { it.httpBaseUrl } }.mapNotNull { it.toHttpUrlOrNull()?.host })
        if (envs.any { it.kind == EnvironmentKind.CONNECT }) scope.launch(Dispatchers.IO) { key.thumbprint }
        environments.startAll()
        secrets.warm()
        _account.value = clerk.account()
        if (_account.value != null) scope.launch(Dispatchers.IO) { refreshConnect() }
        watchNetwork()
    }

    /** True once the cache is loaded. Waits at most [timeoutMs]. */
    fun awaitReady(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun rememberEnvironment(id: String?) {
        lastEnvironmentId = id
        scope.launch(Dispatchers.IO) { db.setUiValue(LAST_ENV, id) }
    }

    fun rememberThread(envId: String, threadId: String?) {
        if (lastEnvironmentId == envId && lastThreadId == threadId) return
        lastEnvironmentId = envId
        lastThreadId = threadId
        scope.launch(Dispatchers.IO) {
            db.setUiValue(LAST_ENV, envId)
            db.setUiValue(LAST_THREAD, threadId)
        }
    }

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        scope.launch(Dispatchers.IO) { db.setUiValue(THEME, choice.name) }
    }

    /** A local switch stored with the UI state. Never sent to the computer. */
    inner class UiSetting(private val key: String, default: Boolean) {
        private val flow = MutableStateFlow(default)
        val value: StateFlow<Boolean> = flow
        internal fun load() { db.uiValue(key)?.let { flow.value = it == "1" } }
        fun set(on: Boolean) {
            flow.value = on
            scope.launch(Dispatchers.IO) { db.setUiValue(key, if (on) "1" else "0") }
        }
    }

    /** A local string stored with the UI state, null when unset. */
    inner class UiValue(private val key: String) {
        private val flow = MutableStateFlow<String?>(null)
        val value: StateFlow<String?> = flow
        internal fun load() { flow.value = db.uiValue(key) }
        fun set(v: String?) {
            if (flow.value == v) return
            flow.value = v
            scope.launch(Dispatchers.IO) { db.setUiValue(key, v) }
        }
    }

    /** Redesign's options as one value, read before the first frame so the theme never flashes. */
    val redesignStyle: StateFlow<RedesignStyle> by lazy {
        combine(redesign.value, redesignAccent.value, redesignPicture.value, redesignDim.value) { on, accent, picture, dim -> styleOf(on, accent, picture, dim) }
            .stateIn(scope, SharingStarted.Eagerly, styleOf(redesign.value.value, redesignAccent.value.value, redesignPicture.value.value, redesignDim.value.value))
    }

    private fun styleOf(on: Boolean, accent: String?, picture: String?, dim: String?) = RedesignStyle().let { d ->
        RedesignStyle(on, accent ?: d.accent, picture ?: d.picture, dim?.toIntOrNull() ?: d.dim)
    }

    /** After a sign-in or sign-out. */
    fun accountChanged() {
        _account.value = clerk.account()
    }

    /** Lists the account's linked computers. Returns an error message or null. */
    suspend fun refreshConnect(): String? = runCatching {
        environments.refreshConnect()
        environments.wakeConnect()
    }.exceptionOrNull()?.let { it.message ?: it::class.simpleName }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // The first callback reports the network the app started on; only later ones are changes.
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                if (previous != null && previous != network) {
                    Timeline.mark("network.changed")
                    environments.networkChanged()
                }
            }
        })
    }

    companion object {
        /** [machineScope] for every machine at once. */
        const val ALL_MACHINES = "all"
        /** How long the first frame waits for icon thumbnails; past it the monograms show and the icons land after. */
        private const val ICONS_WAIT_MS = 150L
        private const val LEGACY_SIDEBAR = "legacy_sidebar"
        private const val PLAN_MODE = "plan_mode"
        private const val MACHINE_SCOPE = "machine_scope"
        private const val PROJECT_SCOPE = "project_scope"
        private const val SETTLED_SHELF = "shelf_settled"
        private const val SNOOZED_SHELF = "shelf_snoozed"
        private const val WORKING_SECTION = "working_section"
        private const val WORKING_SHELF = "shelf_working"
        private const val LAST_ENV = "last_env"
        private const val THEME = "theme"
        private const val LAST_THREAD = "last_thread"
        private const val REDESIGN = "redesign"
        private const val REDESIGN_ACCENT = "redesign_accent"
        private const val REDESIGN_PICTURE = "redesign_picture"
        private const val REDESIGN_DIM = "redesign_dim"
        private const val REDESIGN_FOLDED = "redesign_folded"
    }
}
