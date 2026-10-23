package org.anikodev.candywrapper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
    private val drawer = DrawerState(open = false)

    private fun show() = compose.setContent {
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
}
