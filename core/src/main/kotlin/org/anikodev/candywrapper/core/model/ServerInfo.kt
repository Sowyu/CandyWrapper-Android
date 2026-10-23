package org.anikodev.candywrapper.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.json.arr
import org.anikodev.candywrapper.core.json.bool
import org.anikodev.candywrapper.core.json.obj
import org.anikodev.candywrapper.core.json.str

/** One provider option picked for a model, `{id, value}` with a string or boolean value (contracts `model.ts:46-53`). */
@Serializable
data class OptionValue(val id: String, val text: String? = null, val flag: Boolean? = null)

/** `ModelSelection` (protocol.md 7.1). Always sent with `instanceId`, never the legacy `provider` key. */
@Serializable
data class ModelSelection(val instanceId: String, val model: String, val options: List<OptionValue> = emptyList()) {
    fun option(id: String): OptionValue? = options.firstOrNull { it.id == id }

    fun withOption(value: OptionValue) = copy(options = options.filterNot { it.id == value.id } + value)

    fun toJson(): JsonObject = buildJsonObject {
        put("instanceId", instanceId)
        put("model", model)
        if (options.isNotEmpty()) put("options", buildJsonArray {
            for (o in options) add(buildJsonObject {
                put("id", o.id)
                if (o.flag != null) put("value", o.flag) else put("value", o.text)
            })
        })
    }

    companion object {
        /** Decodes the array form and the legacy `{effort: "max"}` object form. */
        fun from(o: JsonObject?): ModelSelection? {
            o ?: return null
            val instance = o.str("instanceId") ?: o.str("provider") ?: return null
            val model = o.str("model") ?: return null
            val options = when (val raw = o["options"]) {
                is JsonArray -> raw.mapNotNull { (it as? JsonObject)?.let(::optionFrom) }
                is JsonObject -> raw.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { primitive(k, it) } }
                else -> emptyList()
            }
            return ModelSelection(instance, model, options)
        }

        private fun optionFrom(o: JsonObject): OptionValue? {
            val id = o.str("id") ?: return null
            return primitive(id, o["value"] as? JsonPrimitive ?: return null)
        }

        private fun primitive(id: String, p: JsonPrimitive): OptionValue =
            if (p.isString) OptionValue(id, text = p.content) else OptionValue(id, flag = p.booleanOrNull)
    }
}

data class OptionChoice(val id: String, val label: String, val isDefault: Boolean)

/** A model's option: `select` has [choices], `boolean` has none. */
data class OptionDescriptor(val id: String, val label: String, val type: String, val choices: List<OptionChoice>) {
    val defaultChoice: OptionChoice? get() = choices.firstOrNull { it.isDefault } ?: choices.firstOrNull()
}

data class ModelInfo(val slug: String, val name: String, val options: List<OptionDescriptor>)

/** One `ServerProvider` (protocol.md 9.6), trimmed to what the composer offers. */
data class ProviderInfo(
    val instanceId: String,
    val name: String,
    val driver: String,
    val ready: Boolean,
    val models: List<ModelInfo>,
    val showInteractionModeToggle: Boolean,
    /** Empty means every mode. */
    val runtimeModes: List<String>,
) {
    fun model(slug: String) = models.firstOrNull { it.slug == slug }
}

/**
 * What the composer needs from `server.getConfig`: providers with their models, the defaults for
 * new threads, and whether the server picks queue or steer for a message sent during a run.
 */
data class ServerInfo(
    val providers: List<ProviderInfo> = emptyList(),
    val defaultModel: ModelSelection? = null,
    val defaultRuntimeMode: String = "full-access",
    val serverResolvesDelivery: Boolean = false,
    /** `capabilities.threadSettlement`: Settle and Un-settle. */
    val threadSettlement: Boolean = false,
    /** `capabilities.threadSnooze`: Snooze and Wake. */
    val threadSnooze: Boolean = false,
    /** `capabilities.threadPinning`: Pin and Unpin. */
    val threadPinning: Boolean = false,
) {
    fun provider(instanceId: String) = providers.firstOrNull { it.instanceId == instanceId }

    /** The model a new thread starts on: the project's, then the server's, then the first ready model. */
    fun modelForNewThread(projectDefault: ModelSelection?): ModelSelection? =
        projectDefault ?: defaultModel ?: providers.firstOrNull { it.ready && it.models.isNotEmpty() }?.let { p ->
            ModelSelection(p.instanceId, p.models.first().slug)
        }

    companion object {
        val RUNTIME_MODES = listOf("approval-required", "auto-accept-edits", "auto", "full-access")

        fun parse(config: JsonObject): ServerInfo {
            val providers = config.arr("providers").orEmpty().mapNotNull { (it as? JsonObject)?.let(::provider) }
            val settings = config.obj("settings")
            val caps = config.obj("environment")?.obj("capabilities")
            return ServerInfo(
                providers = providers,
                defaultModel = ModelSelection.from(settings?.obj("defaultModelSelection")),
                defaultRuntimeMode = settings?.str("defaultRuntimeMode")?.takeIf { it in RUNTIME_MODES } ?: "full-access",
                serverResolvesDelivery = caps?.bool("serverResolvedCommandContext") == true,
                threadSettlement = caps?.bool("threadSettlement") == true,
                threadSnooze = caps?.bool("threadSnooze") == true,
                threadPinning = caps?.bool("threadPinning") == true,
            )
        }

        private fun provider(o: JsonObject): ProviderInfo? {
            val id = o.str("instanceId") ?: return null
            if (o.bool("enabled") == false) return null
            val models = o.arr("models").orEmpty().mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val slug = mo.str("slug") ?: return@mapNotNull null
                val options = mo.obj("capabilities")?.arr("optionDescriptors").orEmpty().mapNotNull { d ->
                    val dobj = d as? JsonObject ?: return@mapNotNull null
                    OptionDescriptor(
                        id = dobj.str("id") ?: return@mapNotNull null,
                        label = dobj.str("label") ?: dobj.str("id")!!,
                        type = dobj.str("type") ?: "select",
                        choices = dobj.arr("options").orEmpty().mapNotNull { c ->
                            val co = c as? JsonObject ?: return@mapNotNull null
                            OptionChoice(co.str("id") ?: return@mapNotNull null, co.str("label") ?: co.str("id")!!, co.bool("isDefault") == true)
                        },
                    )
                }
                ModelInfo(slug, mo.str("name") ?: slug, options)
            }
            return ProviderInfo(
                instanceId = id,
                name = o.str("displayName") ?: o.str("driver") ?: id,
                driver = o.str("driver") ?: id,
                ready = o.bool("installed") != false && (o.str("status") ?: "ready") == "ready",
                models = models,
                showInteractionModeToggle = o.bool("showInteractionModeToggle") != false,
                runtimeModes = o.arr("supportedRuntimeModes").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }
    }
}
