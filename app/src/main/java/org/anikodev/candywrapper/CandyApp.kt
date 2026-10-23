package org.anikodev.candywrapper

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.anikodev.candywrapper.core.auth.ClientIdentity
import org.anikodev.candywrapper.core.timing.Timeline

class CandyApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        // Timeline zero is the moment the process forked, so every mark reads as "ms since process start".
        val sinceStartMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        Timeline.originNanos = System.nanoTime() - sinceStartMs * 1_000_000
        Timeline.sink = { Log.i("CWTiming", it.format()) }
        Timeline.mark("app.onCreate")
        super.onCreate()
        ClientIdentity.appVersion = BuildConfig.VERSION_NAME
        ClientIdentity.deviceModel = Build.MODEL.orEmpty()
        ClientIdentity.osMajorVersion = Build.VERSION.RELEASE.substringBefore('.')
        graph = AppGraph(this)
        graph.boot()
    }
}
