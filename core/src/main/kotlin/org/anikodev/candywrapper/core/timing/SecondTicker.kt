package org.anikodev.candywrapper.core.timing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One tick on each wall-clock second, shared by every "Working for" label on screen (T3's
 * `useNowSecond`, CandyWrapper's `LabelClock`). A label calls [acquire] while it is visible and
 * [release] when it leaves the screen or the app stops; the ticker runs only while one is held, so
 * nothing ticks when no thread is working or the screen is off. [onTick] gets the second, in epoch
 * milliseconds rounded down. Not thread-safe; call it from the main thread.
 */
class SecondTicker(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTick: (Long) -> Unit,
) {
    private var holders = 0
    private var job: Job? = null

    val running: Boolean get() = job != null

    fun acquire() {
        holders++
        if (job != null) return
        // The first value lands now, so a label that just appeared is right before the next second.
        onTick(clock().let { it - it % 1000 })
        job = scope.launch {
            while (true) {
                delay(1000 - clock() % 1000)
                onTick(clock().let { it - it % 1000 })
            }
        }
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) {
            job?.cancel()
            job = null
        }
    }
}
