package org.anikodev.candywrapper.core.orchestrator

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.media.ChatAttachment
import org.anikodev.candywrapper.core.model.HtmlPage
import org.anikodev.candywrapper.core.model.TurnRow

/** One thing the Orchestrator chat shows (CandyWrapper OrchestratorChat.swift, "The chat looks like iMessage"). */
sealed interface ChatItem {
    val key: String

    /** A centred time label where more than 15 minutes pass between messages. */
    data class Time(override val key: String, val label: String) : ChatItem
    /** The brief, collapsed into a card. */
    data class Brief(override val key: String, val text: String, val updated: Boolean) : ChatItem
    /** A small centred line: an event, a result, the tools a turn used, a scheduled check. Tapping opens [threadId]. */
    data class Line(override val key: String, val text: String, val threadId: String? = null) : ChatItem
    /** An image you sent, drawn on its own like a photo in Messages. */
    data class Image(override val key: String, val attachment: ChatAttachment) : ChatItem
    /** A page the Orchestrator published, above its reply. */
    data class Html(override val key: String, val page: HtmlPage) : ChatItem
    /** A bubble; [first] and [last] place it in its sender's stack, and only the last gets a tail. */
    data class Bubble(override val key: String, val fromUser: Boolean, val text: String, val markdown: List<MdBlock>?, val first: Boolean, val last: Boolean) : ChatItem
    data class Plan(override val key: String, val steps: List<OrchestratorReply.PlanStep>) : ChatItem
    /** The notes a reply saved, with Undo. [messageId] is the reply's row id. */
    data class Memory(override val key: String, val messageId: String, val notes: List<String>) : ChatItem
    /** "Read" under your last message once the Orchestrator's turn started. */
    data object Read : ChatItem { override val key = "read" }
    /** Three dots while its turn runs. */
    data object Typing : ChatItem { override val key = "typing" }
}

object OrchestratorChat {
    const val TIME_GAP_MS = 15 * 60_000L
    private const val SCHEDULED_PREFIX = "Scheduled check:"
    /** T3's stand-in text for a message of images only. */
    const val ATTACHMENT_ONLY_PROMPT = "[User attached one or more files without additional text. Respond using the conversation context and the attached files.]"
    private val QUIET = setOf("user_message", "assistant_message", "reasoning", "checkpoint", "thread_created", "system_notice", "notification")
    private val EVENT_LINK = Regex(""" \(t3-thread://v1/([A-Za-z0-9_:.-]+)\)\.?""")

    /**
     * The chat for the Orchestrator thread's rows, oldest first. [running] is whether its turn runs now. [previous]
     * lets unchanged bubbles keep their parsed Markdown.
     */
    fun items(rows: List<TurnRow>, running: Boolean, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), previous: Map<String, ChatItem> = emptyMap()): List<ChatItem> {
        val out = ArrayList<ChatItem>()
        var lastMs: Long? = null
        val tools = toolSummaries(rows)
        val pages = rows.filter { it.htmlPage != null && it.runId != null }.groupBy({ it.runId!! }, { it.htmlPage!! })
        val toolsShown = HashSet<String>()
        val pagesShown = HashSet<String>()
        var lastUser: Pair<Int, Long>? = null
        var lastAssistantMs = 0L
        for (r in rows) {
            if (r.type != "user_message" && r.type != "assistant_message") continue
            val ms = r.atMs
            if (lastMs.let { it == null || ms - it > TIME_GAP_MS }) out += ChatItem.Time("time-${r.id}", timeLabel(ms, nowMs, zone))
            lastMs = ms
            val text0 = r.text.orEmpty()
            if (r.type == "user_message") {
                val parts = OrchestratorBrief.split(text0)
                parts.brief?.let { out += ChatItem.Brief("brief-${r.id}", it, parts.updated) }
                var text = parts.message.trim()
                val images = r.attachments.filter { it.isImage }
                images.forEachIndexed { i, a -> out += ChatItem.Image("image-${r.id}-$i", a) }
                if (images.isNotEmpty() && text == ATTACHMENT_ONLY_PROMPT) text = ""
                if (text.isEmpty() && images.isNotEmpty()) lastUser = out.lastIndex to ms
                if (text.isEmpty()) continue
                when {
                    OrchestratorEvent.isEventMessage(text) -> text.split('\n').filter { it.isNotEmpty() }.forEachIndexed { i, line ->
                        val (t, thread) = eventLine(line)
                        out += ChatItem.Line("event-${r.id}-$i", t, thread)
                    }
                    OrchestratorRelay.isResultMessage(text) -> OrchestratorRelay.summaries(text).forEachIndexed { i, s ->
                        out += ChatItem.Line("result-${r.id}-$i", s.replaceFirstChar(Char::uppercaseChar))
                    }
                    text.startsWith(SCHEDULED_PREFIX) -> out += ChatItem.Line("scheduled-${r.id}", "Scheduled check while CandyWrapper was closed")
                    else -> {
                        out += bubble(r.id, true, text, previous)
                        lastUser = out.lastIndex to ms
                    }
                }
            } else {
                lastAssistantMs = ms
                val run = r.runId
                if (run != null && tools[run] != null && toolsShown.add(run)) out += ChatItem.Line("tools-$run", tools.getValue(run))
                if (run != null && pagesShown.add(run)) pages[run]?.forEachIndexed { i, p -> out += ChatItem.Html("html-$run-$i", p) }
                val reply = OrchestratorReply.parse(text0)
                if (reply.text.isNotEmpty()) out += bubble(r.id, false, reply.text, previous)
                reply.plan?.takeIf { it.isNotEmpty() }?.let { out += ChatItem.Plan("plan-${r.id}", it) }
                if (reply.memories.isNotEmpty()) out += ChatItem.Memory("memory-${r.id}", r.id, reply.memories.map { it.note })
                if (reply.digest != null) out += ChatItem.Line("digest-${r.id}", "Digest updated")
            }
        }
        // A running turn that used tools or published a page before it wrote anything.
        if (running) for ((run, summary) in tools) if (run !in toolsShown) out += ChatItem.Line("tools-$run", summary)
        for ((run, list) in pages) if (run !in pagesShown) list.forEachIndexed { i, p -> out += ChatItem.Html("html-$run-$i", p) }
        lastUser?.let { (index, ms) -> if (running || lastAssistantMs >= ms) out.add(index + 1, ChatItem.Read) }
        if (running) out += ChatItem.Typing
        return stacked(out)
    }

    private fun bubble(id: String, fromUser: Boolean, text: String, previous: Map<String, ChatItem>): ChatItem.Bubble {
        val prev = previous[id] as? ChatItem.Bubble
        val md = if (prev != null && prev.text == text) prev.markdown else if (fromUser) null else Markdown.parse(text)
        return ChatItem.Bubble(id, fromUser, text, md, first = true, last = true)
    }

    /** Sets each bubble's place in its stack: bubbles in a row from one sender form a stack. */
    fun stacked(items: List<ChatItem>): List<ChatItem> {
        fun sender(i: Int): Boolean? = (items.getOrNull(i) as? ChatItem.Bubble)?.fromUser
        return items.mapIndexed { i, item ->
            if (item !is ChatItem.Bubble) return@mapIndexed item
            val first = sender(i - 1) != item.fromUser
            val last = sender(i + 1) != item.fromUser
            if (item.first == first && item.last == last) item else item.copy(first = first, last = last)
        }
    }

    /** "“Release notes” finished a turn" and the thread it names, from an event line. Ids may hold a colon (`mcp:<uuid>`). */
    fun eventLine(line: String): Pair<String, String?> {
        val text = line.removePrefix("Event: ")
        val m = EVENT_LINK.find(text) ?: return text to null
        return text.substring(0, m.range.first) to m.groupValues[1]
    }

    /** Per run, "Used 3 tools: t3_thread_launch, t3_thread_send" for the work rows between its messages. */
    fun toolSummaries(rows: List<TurnRow>): Map<String, String> {
        val byRun = LinkedHashMap<String, MutableList<String>>()
        for (r in rows) {
            if (r.type in QUIET) continue
            val run = r.runId ?: continue
            byRun.getOrPut(run) { ArrayList() } += r.title
        }
        return byRun.mapValues { (_, names) ->
            val unique = names.distinct()
            val shown = unique.take(2).joinToString(", ") + if (unique.size > 2) "…" else ""
            if (names.size == 1) "Used 1 tool: $shown" else "Used ${names.size} tools: $shown"
        }
    }

    fun timeLabel(ms: Long, nowMs: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        return when (date.toLocalDate()) {
            today -> "Today " + date.format(clock)
            today.minusDays(1) -> "Yesterday " + date.format(clock)
            else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.US))
        }
    }
}
