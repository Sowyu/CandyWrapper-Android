package org.anikodev.candywrapper.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Typing "/" in the composer opens T3's command menu; typing filters it and a tap runs the pick. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ComposerSlashTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var text by mutableStateOf("")
    private var mode by mutableStateOf("default")

    private fun show(planMode: Boolean = false) = compose.setContent {
        CandyTheme(ThemeChoice.LIGHT) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Composer(
                    text = text, onText = { text = it }, settings = ComposerSettings(ThreadFixtures.model, "full-access", mode), server = ThreadFixtures.server,
                    running = false, canStop = false, files = emptyList(), busy = null, error = null, connected = true,
                    onModel = {}, onRuntimeMode = {}, onTogglePlan = if (planMode) ({ mode = if (mode == "plan") "default" else "plan" }) else null,
                    onAttach = {}, onRemoveFile = {}, onSend = {}, onQueue = null, onSteer = null, onStop = {}, onDismissError = {},
                )
            }
        }
    }

    private val field get() = compose.onNodeWithContentDescription("Message")
    private val menu get() = compose.onNodeWithTag("slash-menu")

    @Test
    fun slashOpensTheMenuTypingFiltersAndATapInsertsTheCommand() {
        show()
        menu.assertDoesNotExist()
        field.performTextInput("/")
        menu.assertExists()
        compose.onNodeWithText("/model").assertExists()
        compose.onNodeWithText("/review").assertExists()
        compose.onNodeWithText("/init").assertExists()
        compose.onNodeWithText("/skill:Frontend Design").assertExists()

        field.performTextInput("rev")
        compose.onNodeWithText("/review").assertExists()
        compose.onNodeWithText("/init").assertDoesNotExist()
        compose.onNodeWithText("/model").assertDoesNotExist()

        compose.onNodeWithText("/review").performClick()
        compose.waitForIdle()
        assertEquals("/review ", text)
        menu.assertDoesNotExist()
    }

    @Test
    fun aSkillInsertsItsToken() {
        show()
        field.performTextInput("Make it nice /front")
        compose.onNodeWithText("/skill:Frontend Design").performClick()
        compose.waitForIdle()
        assertEquals("Make it nice \$frontend-design ", text)
    }

    @Test
    fun deletingTheSlashClosesTheMenu() {
        show()
        field.performTextInput("/")
        menu.assertExists()
        field.performTextClearance()
        compose.waitForIdle()
        menu.assertDoesNotExist()
    }

    @Test
    fun noMatchSaysSo() {
        show()
        field.performTextInput("/zzz")
        compose.onNodeWithText("No matching command.").assertExists()
    }

    @Test
    fun modelClearsTheWordAndOpensTheModelPicker() {
        show()
        field.performTextInput("/mod")
        compose.onNodeWithText("/model").performClick()
        compose.waitForIdle()
        assertEquals("", text)
        compose.onNodeWithText("Claude Sonnet 5.5").assertExists()
    }

    @Test
    fun planSwitchesTheThreadIntoPlanMode() {
        show(planMode = true)
        field.performTextInput("/pl")
        compose.onNodeWithText("/plan").performClick()
        compose.waitForIdle()
        assertEquals("plan", mode)
        assertEquals("", text)
    }

    @Test
    fun backShutsItUntilTheCaretLeavesTheWord() {
        show()
        field.performTextInput("/re")
        menu.assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        menu.assertDoesNotExist()
        field.performTextInput("v")
        menu.assertDoesNotExist()
        // A new word opens it again.
        field.performTextInput(" /")
        menu.assertExists()
    }
}
