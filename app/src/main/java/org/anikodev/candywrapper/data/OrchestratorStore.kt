package org.anikodev.candywrapper.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig
import org.anikodev.candywrapper.core.orchestrator.OrchestratorSettingsSync

/**
 * The Orchestrator's settings and state on this phone, one JSON file in the app's files, replaced atomically. T3's
 * settings never see any of it. The shared settings also carry a stamp each in `sync.json`, with this install's
 * device id, so the controller can sync them with the other devices (`OrchestratorSettingsSync`). Reads happen on
 * the boot thread; writes are folded and go out on IO.
 */
class OrchestratorStore(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "orchestrator/config.json")
    private val syncFile = File(dir, "orchestrator/sync.json")
    private val _config = MutableStateFlow(OrchestratorConfig())
    val config: StateFlow<OrchestratorConfig> = _config
    private var sync = OrchestratorSettingsSync.State(UUID.randomUUID().toString())
    private val writes = Channel<Unit>(Channel.CONFLATED)

    /** When each shared setting was last set and by which device. */
    val stamps: Map<String, OrchestratorSettingsSync.Stamp> get() = synchronized(this) { sync.stamps }

    /** This install's id in the stamps, a lowercase UUID made once. */
    val device: String get() = synchronized(this) { sync.device }

    fun load() {
        _config.value = read(file, OrchestratorConfig.serializer()) ?: OrchestratorConfig()
        // First launch with sync: what you set before goes up as set now.
        sync = read(syncFile, OrchestratorSettingsSync.State.serializer())
            ?: sync.copy(stamps = OrchestratorSettingsSync.migrationStamps(_config.value, System.currentTimeMillis(), sync.device)).also { writes.trySend(Unit) }
        scope.launch(Dispatchers.IO) { for (w in writes) write() }
    }

    /** A local edit: the shared settings it changes are stamped with this device and now. */
    fun update(change: (OrchestratorConfig) -> OrchestratorConfig) {
        synchronized(this) {
            val before = _config.value
            val after = change(before)
            if (after == before) return
            sync = sync.copy(stamps = OrchestratorSettingsSync.stamps(before, after, sync.stamps, System.currentTimeMillis(), sync.device))
            _config.value = after
        }
        writes.trySend(Unit)
    }

    /** Settings merged from the other devices, against the current config and stamps; they keep the stamps they came with. */
    fun merge(block: (OrchestratorConfig, Map<String, OrchestratorSettingsSync.Stamp>) -> OrchestratorSettingsSync.Merge): OrchestratorSettingsSync.Merge {
        val m = synchronized(this) {
            val m = block(_config.value, sync.stamps)
            if (m.config == _config.value && m.stamps == sync.stamps) return m
            sync = sync.copy(stamps = m.stamps)
            _config.value = m.config
            m
        }
        writes.trySend(Unit)
        return m
    }

    private fun <T> read(f: File, serializer: KSerializer<T>): T? = runCatching { WireJson.decodeFromString(serializer, f.readText()) }.getOrNull()

    private fun write() {
        val (config, state) = synchronized(this) { _config.value to sync }
        replace(file, WireJson.encodeToString(OrchestratorConfig.serializer(), config))
        replace(syncFile, WireJson.encodeToString(OrchestratorSettingsSync.State.serializer(), state))
    }

    private fun replace(f: File, text: String) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
