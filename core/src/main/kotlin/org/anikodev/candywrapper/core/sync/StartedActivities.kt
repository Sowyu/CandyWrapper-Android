package org.anikodev.candywrapper.core.sync

/**
 * Tells [onChange] when the app comes to the front or goes to the back, counted over started
 * activities. A pairing link opened from another app starts a second activity, and the first one
 * stops after the new one started, so forwarding each stop would leave the sessions in the
 * background with the app in front. Call from the main thread.
 */
class StartedActivities(private val onChange: (foreground: Boolean) -> Unit) {
    private var started = 0

    fun start() {
        if (started++ == 0) onChange(true)
    }

    fun stop() {
        if (started > 0 && --started == 0) onChange(false)
    }
}
