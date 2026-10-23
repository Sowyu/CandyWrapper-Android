package org.anikodev.candywrapper.core.sync

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.anikodev.candywrapper.core.model.ModelSelection

/** An uploaded attachment for `message.dispatch` (`ChatAttachment`, protocol.md 7.3). */
data class UploadedAttachment(val id: String, val name: String, val mimeType: String, val sizeBytes: Long) {
    val type: String get() = if (mimeType.startsWith("image/")) "image" else "file"
}

/** How a message sent during a run should land. [AUTO] lets the server or the provider's capabilities decide. */
enum class Delivery { AUTO, QUEUE, STEER }

/**
 * `orchestration.dispatchCommand` payloads (protocol.md 7.2, 7.3; contracts `orchestrationV2.ts:2703-3080`).
 * Each call mints a fresh `commandId`; retry an action only with the payload it returned.
 *
 * Content commands carry `createdBy: "user"` and `creationSource: "mobile"`, as T3's own phone app does.
 */
object Commands {
    const val SOURCE = "mobile"

    fun newId(): String = UUID.randomUUID().toString()

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun command(type: String, threadId: String, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        put("commandId", newId())
        put("threadId", threadId)
        body()
    }

    fun createThread(threadId: String, projectId: String, title: String, model: ModelSelection, runtimeMode: String, interactionMode: String) =
        command("thread.create", threadId) {
            put("createdBy", "user")
            put("creationSource", SOURCE)
            put("projectId", projectId)
            put("title", title)
            put("modelSelection", model.toJson())
            put("runtimeMode", runtimeMode)
            put("interactionMode", interactionMode)
            put("branch", JsonPrimitive(null as String?))
            put("worktreePath", JsonPrimitive(null as String?))
        }

    /**
     * `message.dispatch`. With no active run it starts at once. During a run: a server with
     * `serverResolvedCommandContext` gets `deliveryIntent` and decides; an older one gets an explicit
     * mode, queueing unless the user asked to steer (client-runtime `commands.ts:694-780`).
     */
    fun sendMessage(
        threadId: String,
        text: String,
        attachments: List<UploadedAttachment> = emptyList(),
        activeRunId: String? = null,
        delivery: Delivery = Delivery.AUTO,
        serverResolvesDelivery: Boolean = false,
        titleSeed: String? = null,
        model: ModelSelection? = null,
        messageId: String = newId(),
    ) = command("message.dispatch", threadId) {
        put("createdBy", "user")
        put("creationSource", SOURCE)
        put("messageId", messageId)
        put("text", text)
        put("attachments", buildJsonArray {
            for (a in attachments) add(buildJsonObject {
                put("type", a.type)
                put("id", a.id)
                put("name", a.name)
                put("mimeType", a.mimeType)
                put("sizeBytes", a.sizeBytes)
            })
        })
        titleSeed?.trim()?.takeIf { it.isNotEmpty() }?.let { put("titleSeed", it) }
        model?.let { put("modelSelection", it.toJson()) }
        val mode: JsonObject = when {
            serverResolvesDelivery -> {
                if (delivery != Delivery.QUEUE) put("deliveryIntent", if (delivery == Delivery.STEER) "steer" else "auto")
                mode(if (delivery == Delivery.QUEUE) "queue_after_active" else "start_immediately")
            }
            activeRunId == null -> mode("start_immediately")
            delivery == Delivery.STEER -> mode("steer_active", activeRunId)
            // ponytail: older servers queue by default; read providerSessions[].capabilities.turns if steering by default matters there.
            else -> mode("queue_after_active")
        }
        put("dispatchMode", mode)
    }

    private fun mode(type: String, targetRunId: String? = null) = buildJsonObject {
        put("type", type)
        targetRunId?.let { put("targetRunId", it) }
    }

    /** The Stop button: interrupts the run and holds the queue (protocol.md 8.5). */
    fun interrupt(threadId: String, runId: String) = command("run.interrupt", threadId) {
        put("runId", runId)
        put("holdQueue", true)
    }

    fun cancelQueued(threadId: String, runId: String) = command("queued-run.cancel", threadId) { put("runId", runId) }

    fun resumeQueue(threadId: String) = command("queue.resume", threadId)

    fun promoteToSteer(threadId: String, queuedRunId: String, targetRunId: String) = command("queued-message.promote-to-steer", threadId) {
        put("queuedRunId", queuedRunId)
        put("targetRunId", targetRunId)
    }

    /** An approval: `accept`, `acceptForSession`, `acceptAlways`, `decline` or `cancel`. */
    fun approve(threadId: String, requestId: String, decision: String) = command("runtime-request.respond", threadId) {
        put("requestId", requestId)
        put("decision", decision)
    }

    /** Answers to a question: per question id a string, or a list of strings for multi-select. */
    fun answer(threadId: String, requestId: String, answers: Map<String, Any>) = command("runtime-request.respond", threadId) {
        put("requestId", requestId)
        put("answers", buildJsonObject {
            for ((id, value) in answers) put(id, toJson(value))
        })
    }

    fun dismissQuestion(threadId: String, requestId: String) = command("thread.user-input.dismiss", threadId) { put("requestId", requestId) }

    fun rename(threadId: String, title: String) = command("thread.metadata.update", threadId) { put("title", title.trim()) }

    fun archive(threadId: String) = command("thread.archive", threadId)

    fun unarchive(threadId: String) = command("thread.unarchive", threadId)

    /** The server keeps the newest watermark it has seen, so a late or repeated visit cannot move it back. */
    fun visit(threadId: String, visitedAtMs: Long) = command("thread.visit", threadId) { put("visitedAt", iso(visitedAtMs)) }

    fun markUnread(threadId: String) = command("thread.mark-unread", threadId)

    fun setRuntimeMode(threadId: String, mode: String) = command("thread.runtime-mode.set", threadId) { put("runtimeMode", mode) }

    fun setInteractionMode(threadId: String, mode: String) = command("thread.interaction-mode.set", threadId) { put("interactionMode", mode) }

    /**
     * Changing the model within a provider is `thread.model-selection.set`; changing the provider is
     * `provider.switch`, unless the server resolves it (`packages/shared/src/model.ts:18-25`).
     */
    fun setModel(threadId: String, currentInstanceId: String?, model: ModelSelection, serverResolves: Boolean) =
        command(if (serverResolves || currentInstanceId == null || currentInstanceId == model.instanceId) "thread.model-selection.set" else "provider.switch", threadId) {
            put("modelSelection", model.toJson())
        }

    private fun toJson(value: Any): JsonElement = when (value) {
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJson(it ?: "") })
        else -> JsonPrimitive(value.toString())
    }
}
