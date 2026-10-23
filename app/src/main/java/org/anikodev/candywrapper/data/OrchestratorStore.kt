package org.anikodev.candywrapper.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.orchestrator.OrchestratorConfig

/**
 * The Orchestrator's settings and state on this phone, one JSON file in the app's files, replaced atomically. T3's
 * settings never see any of it. Reads happen on the boot thread; writes are folded and go out on IO.
 */
class OrchestratorStore(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "orchestrator/config.json")
    private val _config = MutableStateFlow(OrchestratorConfig())
    val config: StateFlow<OrchestratorConfig> = _config
    private val writes = Channel<Unit>(Channel.CONFLATED)

    fun load() {
        _config.value = runCatching { WireJson.decodeFromString(OrchestratorConfig.serializer(), file.readText()) }.getOrNull() ?: OrchestratorConfig()
        scope.launch(Dispatchers.IO) { for (w in writes) write() }
    }

    fun update(change: (OrchestratorConfig) -> OrchestratorConfig) {
        while (true) {
            val before = _config.value
            val after = change(before)
            if (after == before) return
            if (_config.compareAndSet(before, after)) break
        }
        writes.trySend(Unit)
    }

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(WireJson.encodeToString(OrchestratorConfig.serializer(), _config.value))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
