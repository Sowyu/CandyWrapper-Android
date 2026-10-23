package org.anikodev.candywrapper.core.sync

import org.anikodev.candywrapper.core.timing.Timeline

/**
 * Names the steps of one connect attempt. Each step becomes a Timeline span
 * (`connect.<label>.<step>`) and is reported to [onStep], which the session shows as its state,
 * so the UI says what is really happening.
 */
class Tracer(private val prefix: String, private val onStep: (String) -> Unit = {}) {
    suspend fun <T> step(name: String, block: suspend () -> T): T {
        onStep(name)
        val start = Timeline.nowMs()
        try {
            return block().also { Timeline.span("$prefix.$name", start) }
        } catch (e: Throwable) {
            Timeline.span("$prefix.$name", start, "failed: ${e::class.simpleName}")
            throw e
        }
    }

    fun mark(name: String, detail: String? = null) = Timeline.mark("$prefix.$name", detail)
}
