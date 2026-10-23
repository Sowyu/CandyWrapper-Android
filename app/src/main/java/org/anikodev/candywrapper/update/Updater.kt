package org.anikodev.candywrapper.update

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.anikodev.candywrapper.BuildConfig
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.timing.Timeline
import org.anikodev.candywrapper.core.update.UpdateClient
import org.anikodev.candywrapper.core.update.UpdateManifest
import org.anikodev.candywrapper.core.update.hasSha256

enum class UpdateStep { IDLE, NEEDS_PERMISSION, DOWNLOADING, INSTALLING }

@Immutable
data class UpdateState(
    /** The newest nightly, only when it is newer than this build. */
    val available: UpdateManifest? = null,
    val step: UpdateStep = UpdateStep.IDLE,
    val percent: Int = 0,
    val checking: Boolean = false,
    val checkedAtMs: Long = 0,
    val checkError: String? = null,
    val installError: String? = null,
    /** The version the user said "Later" to. The banner hides until a newer one appears. */
    val dismissedCode: Int = 0,
)

/**
 * Checks the nightly release and installs a newer build over this one.
 *
 * [onForeground] runs after the first frame and on each return to the app; it hits the network at
 * most every 10 minutes. Nothing runs while the app is in the background, except a download or
 * install the user started. Everything blocking runs on [Dispatchers.IO].
 */
class Updater(private val app: Application, private val scope: CoroutineScope) {
    private val client = UpdateClient()
    private val prefs by lazy { app.getSharedPreferences("updater", Context.MODE_PRIVATE) }
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val checkLock = Mutex()
    private val installLock = Mutex()
    @Volatile private var restored = false

    fun onForeground() {
        scope.launch(Dispatchers.IO) {
            restore()
            val s = _state.value
            val m = s.available
            if (s.step == UpdateStep.NEEDS_PERMISSION && m != null && app.packageManager.canRequestPackageInstalls()) {
                install(m) // The user came back from granting "Install unknown apps".
            } else if (System.currentTimeMillis() - s.checkedAtMs >= THROTTLE_MS) {
                check()
            }
        }
    }

    fun checkNow() {
        scope.launch(Dispatchers.IO) { restore(); check() }
    }

    fun install() {
        val m = _state.value.available ?: return
        scope.launch(Dispatchers.IO) { install(m) }
    }

    fun dismiss() = _state.update { it.copy(dismissedCode = it.available?.versionCode ?: 0, installError = null) }

    /** From [InstallReceiver]: the system refused or the user backed out of the install dialog. */
    internal fun installFailed(message: String) {
        Timeline.mark("update.install.failed", message)
        _state.update { it.copy(step = UpdateStep.IDLE, installError = message) }
    }

    /** The last manifest and check time from disk, so a cold start inside the throttle still shows the banner. */
    private fun restore() {
        if (restored) return
        synchronized(this) {
            if (restored) return
            val checkedAt = prefs.getLong(KEY_CHECKED_AT, 0)
            val manifest = prefs.getString(KEY_MANIFEST, null)?.let { runCatching { UpdateManifest.parse(it) }.getOrNull() }
            val newer = manifest?.takeIf { it.isNewerThan(INSTALLED) }
            // The APK from a finished update is still in the cache; it is this build now.
            if (newer == null) updateDir().deleteRecursively()
            _state.update { it.copy(available = it.available ?: newer, checkedAtMs = maxOf(it.checkedAtMs, checkedAt)) }
            restored = true
        }
    }

    private fun check() {
        if (!checkLock.tryLock()) return
        try {
            _state.update { it.copy(checking = true, checkError = null) }
            val result = runCatching { client.fetchManifest() }
            val now = System.currentTimeMillis()
            result.onSuccess { m ->
                prefs.edit().putLong(KEY_CHECKED_AT, now).putString(KEY_MANIFEST, WireJson.encodeToString(UpdateManifest.serializer(), m)).apply()
                Timeline.mark("update.checked", "${m.versionName}, installed ${BuildConfig.VERSION_NAME}")
                _state.update {
                    // Never swap the manifest under a download or install in progress.
                    val available = if (it.step == UpdateStep.IDLE) m.takeIf { m.isNewerThan(INSTALLED) } else it.available
                    it.copy(available = available, checking = false, checkedAtMs = now)
                }
            }.onFailure { e ->
                Timeline.mark("update.check.failed", e.message.orEmpty())
                _state.update { it.copy(checking = false, checkError = e.message ?: e::class.simpleName) }
            }
        } finally {
            checkLock.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure goes back to the banner as text.
    private fun install(m: UpdateManifest) {
        if (!installLock.tryLock()) return
        try {
            _state.update { it.copy(installError = null) }
            if (!app.packageManager.canRequestPackageInstalls()) {
                _state.update { it.copy(step = UpdateStep.NEEDS_PERMISSION) }
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            val apk = File(updateDir(), m.apkName)
            if (!apk.hasSha256(m.sha256)) {
                updateDir().deleteRecursively()
                _state.update { it.copy(step = UpdateStep.DOWNLOADING, percent = 0) }
                client.download(m, apk) { p -> _state.update { it.copy(percent = p) } }
                Timeline.mark("update.downloaded", m.versionName)
            }
            checkSigner(apk)
            _state.update { it.copy(step = UpdateStep.INSTALLING) }
            commit(apk)
            // InstallReceiver reports the outcome. On success this process is replaced.
        } catch (e: Exception) {
            installFailed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            installLock.unlock()
        }
    }

    /** Android would refuse a different signer with a vague "conflicts with an existing package". */
    private fun checkSigner(apk: File) {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("The download is not an Android package.")
        check(archive.packageName == app.packageName) { "The download is ${archive.packageName}, not this app." }
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = { info: android.content.pm.PackageInfo -> info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() }
        check(signers(archive) == signers(installed)) {
            "This build is signed with a different key. Uninstall CandyWrapper once, then install the nightly APK from GitHub."
        }
    }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Skips the confirmation once this app installed the current build; otherwise Android asks.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                // MUTABLE: the installer fills in the status and the confirmation intent.
                val intent = Intent(app, InstallReceiver::class.java)
                val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    private fun updateDir() = File(app.cacheDir, "update")

    private companion object {
        const val THROTTLE_MS = 10 * 60_000L
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_MANIFEST = "manifest"
        val INSTALLED = BuildConfig.VERSION_CODE.toLong()
    }
}
