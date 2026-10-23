package org.anikodev.candywrapper.core.timing

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Named timing marks, in milliseconds since process start. The app sets [originNanos] from the
 * process start time and a [sink] that logs each mark to logcat (tag CWTiming); the Diagnostics
 * screen reads [snapshot]. Marks are cheap: one nanoTime read and a synchronized append.
 *
 * Use [mark] for a point in time and [span] or [timed] for a step with a duration.
 */
object Timeline {
    data class Mark(val atMs: Double, val name: String, val durationMs: Double? = null, val detail: String? = null) {
        fun format(): String = buildString {
            append(String.format(Locale.ROOT, "%8.1f ms  %s", atMs, name))
            if (durationMs != null) append(String.format(Locale.ROOT, "  (%.1f ms)", durationMs))
            if (detail != null) append("  ").append(detail)
        }
    }

    private const val CAPACITY = 500

    @Volatile var originNanos: Long = System.nanoTime()

    @Volatile var sink: ((Mark) -> Unit)? = null

    private val marks = ArrayDeque<Mark>()
    private val _version = MutableStateFlow(0)

    /** Bumps on every mark, so a screen can redraw the list. */
    val version: StateFlow<Int> = _version

    fun nowMs(): Double = (System.nanoTime() - originNanos) / 1_000_000.0

    fun mark(name: String, detail: String? = null): Mark = record(Mark(nowMs(), name, null, detail))

    /** Records a step that started at [startMs] (from [nowMs]) and ends now. */
    fun span(name: String, startMs: Double, detail: String? = null): Mark {
        val now = nowMs()
        return record(Mark(now, name, now - startMs, detail))
    }

    inline fun <T> timed(name: String, detail: String? = null, block: () -> T): T {
        val start = nowMs()
        val result = block()
        span(name, start, detail)
        return result
    }

    fun snapshot(): List<Mark> = synchronized(marks) { marks.toList() }

    fun clear() {
        synchronized(marks) { marks.clear() }
        _version.update { it + 1 }
    }

    private fun record(mark: Mark): Mark {
        synchronized(marks) {
            if (marks.size == CAPACITY) marks.removeFirst()
            marks.addLast(mark)
        }
        _version.update { it + 1 }
        sink?.invoke(mark)
        return mark
    }
}
