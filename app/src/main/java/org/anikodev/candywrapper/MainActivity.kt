package org.anikodev.candywrapper

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.anikodev.candywrapper.core.timing.Timeline
import org.anikodev.candywrapper.ui.App
import org.anikodev.candywrapper.ui.CandyTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as CandyApp).graph

    /** Text shared to the app (a pairing link from another app), handed to the pairing screen. */
    private var sharedText by mutableStateOf<String?>(null)

    /** The update check waits for the first frame, then runs on every return to the app. */
    private var drawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Timeline.mark("activity.onCreate")
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedText = intent.sharedText()
        // The cache usually loaded while the activity was being created. Waiting here means the
        // first frame shows the cached list; past the limit the UI starts empty and fills in.
        val readyInTime = graph.awaitReady(READY_WAIT_MS)
        Timeline.mark("activity.cache.wait", if (readyInTime) "ready" else "timed out")
        setContent {
            val ready by graph.readyFlow.collectAsStateWithLifecycle()
            CandyTheme {
                if (ready) App(graph, sharedText) { sharedText = null }
            }
        }
        reportFirstFrame(window.decorView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedText()?.let { sharedText = it }
    }

    override fun onStart() {
        super.onStart()
        if (graph.readyFlow.value) graph.environments.setForeground(true)
        if (drawn) graph.updater.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (graph.readyFlow.value) graph.environments.setForeground(false)
    }

    private fun Intent.sharedText(): String? = if (action == Intent.ACTION_SEND) getStringExtra(Intent.EXTRA_TEXT) else null

    private fun reportFirstFrame(view: View) {
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                // Removing a draw listener inside onDraw is not allowed; post it.
                view.post {
                    view.viewTreeObserver.removeOnDrawListener(this)
                    Timeline.mark("first.frame")
                    reportFullyDrawn()
                    drawn = true
                    graph.updater.onForeground()
                }
            }
        })
    }

    private companion object {
        const val READY_WAIT_MS = 400L
    }
}
