package org.anikodev.candywrapper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone drawer: a swipe from the left edge opens it, and taps near that edge still reach the thread. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ShellTest {
    @get:Rule val compose = createComposeRule()

    private var tapped = false
    private var drawer = DrawerState(open = false)

    private lateinit var scope: CoroutineScope

    private fun show() = compose.setContent {
        scope = rememberCoroutineScope()
        CandyTheme(ThemeChoice.LIGHT) {
            AppShell(drawer, sidebar = { Txt("Sidebar") }) {
                Box(Modifier.fillMaxSize().testTag("content").clickable { tapped = true })
            }
        }
    }

    @Test
    fun edgeSwipeOpensTheSidebar() {
        show()
        compose.onNodeWithTag("content").performTouchInput { swipeRight(startX = 4f, endX = width * 0.8f, durationMillis = 200) }
        compose.waitForIdle()
        assertTrue(drawer.isOpen)
    }

    @Test
    fun swipeFromTheMiddleDoesNotOpenIt() {
        show()
        compose.onNodeWithTag("content").performTouchInput { swipeRight(startX = width / 2f, endX = width * 0.9f, durationMillis = 200) }
        compose.waitForIdle()
        assertFalse(drawer.isOpen)
    }

    @Test
    fun tapNearTheEdgeReachesTheContent() {
        show()
        compose.onNodeWithTag("content").performTouchInput { click(Offset(6f, height / 2f)) }
        compose.waitForIdle()
        assertTrue(tapped)
        assertFalse(drawer.isOpen)
    }

    // Every release must end fully open or fully closed: a move queued behind the release used to
    // cancel the settle animation and leave the panel stuck part way.
    @Test
    fun swipeOnTheOpenSidebarClosesItAllTheWay() {
        drawer = DrawerState(open = true)
        show()
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.6f, endX = width * 0.25f, durationMillis = 300) }
        compose.waitForIdle()
        assertEquals(0f, drawer.fraction.value)
    }

    @Test
    fun slowPartialSwipeSettlesAtAnEnd() {
        show()
        compose.onNodeWithTag("content").performTouchInput { swipeRight(startX = 4f, endX = width * 0.3f, durationMillis = 1500) }
        compose.waitForIdle()
        val f = drawer.fraction.value
        assertTrue("stuck at $f", f == 0f || f == 1f)
    }

    @Test
    fun aMoveQueuedBehindTheReleaseDoesNotStopTheSettle() {
        show()
        compose.runOnIdle {
            drawer.beginDrag()
            scope.launch { drawer.dragBy(30f, 100f) }
        }
        compose.runOnIdle {
            scope.launch { drawer.settle(0f) }
            scope.launch { drawer.dragBy(5f, 100f) }
        }
        compose.waitForIdle()
        assertEquals(0f, drawer.fraction.value)
    }
}
