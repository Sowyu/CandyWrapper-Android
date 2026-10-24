package org.anikodev.candywrapper.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bookmark beside attach: a tap stashes the draft, the badge counts the stash, and it opens the list. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class StashButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var text by mutableStateOf("")
    private var stash by mutableStateOf(listOf<String>())
    private var opened by mutableIntStateOf(0)

    private fun show() = compose.setContent {
        CandyTheme(ThemeChoice.LIGHT) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Composer(
                    text = text, onText = { text = it }, settings = ComposerSettings(ThreadFixtures.model, "full-access", "default"), server = ThreadFixtures.server,
                    running = false, canStop = false, files = emptyList(), busy = null, error = null, connected = true,
                    onModel = {}, onRuntimeMode = {}, onTogglePlan = null,
                    onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = null, onSteer = null, onStop = {}, onDismissError = {},
                    onStash = { stash = stash + text; text = "" }, stashCount = stash.size, onOpenStash = { opened++ },
                )
            }
        }
    }

    private val button get() = compose.onNodeWithContentDescription("Stash prompt", substring = true)

    @Test
    fun aTapStashesTheDraftAndTheBadgeCountsIt() {
        show()
        compose.onNodeWithText("1").assertDoesNotExist()
        text = "Bump the Compose BOM"
        compose.waitForIdle()
        button.performClick()
        compose.waitForIdle()
        assertEquals(listOf("Bump the Compose BOM"), stash)
        assertEquals("", text)
        assertEquals(0, opened)
        compose.onNodeWithContentDescription("Stash prompt, 1 stashed").assertExists()
        compose.onNodeWithText("1").assertExists()

        text = "Then run the shots"
        compose.waitForIdle()
        button.performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2").assertExists()
    }

    @Test
    fun withNothingToStashATapOpensTheList() {
        stash = listOf("Earlier prompt")
        show()
        button.performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        assertEquals(listOf("Earlier prompt"), stash)
    }

    @Test
    fun aLongPressOpensTheListEvenWithADraft() {
        stash = listOf("Earlier prompt")
        text = "Keep typing"
        show()
        button.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, opened)
        assertEquals(1, stash.size)
        assertEquals("Keep typing", text)
    }

    @Test
    fun emptyDraftAndEmptyStashLeaveItDisabled() {
        show()
        button.performClick()
        compose.waitForIdle()
        assertEquals(0, opened)
        assertEquals(emptyList<String>(), stash)
    }
}
