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
import org.anikodev.candywrapper.data.CacheDb
import org.anikodev.candywrapper.data.KeystoreDPoPKey
import org.anikodev.candywrapper.data.LazyDPoPKey
import org.anikodev.candywrapper.data.SecretBox
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

    /** The environment whose threads were open last, to reopen on a cold start. */
    @Volatile var lastEnvironmentId: String? = null
        private set

    private val _account = MutableStateFlow<ClerkAccount?>(null)
    val account: StateFlow<ClerkAccount?> = _account

    fun boot() = thread(name = "cw-boot") {
        val start = Timeline.nowMs()
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
        environments.restore(envs, shells)
        Timeline.span("cache.loaded", start, "${envs.size} environments, ${shells.values.sumOf { it?.threads?.size ?: 0 }} threads")
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

    private companion object {
        const val LAST_ENV = "last_env"
    }
}
