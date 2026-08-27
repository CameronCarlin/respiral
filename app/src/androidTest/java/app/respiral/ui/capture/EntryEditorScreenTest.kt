package app.respiral.ui.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.respiral.MainActivity
import org.junit.Rule
import org.junit.Test

class EntryEditorScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcome_offers_the_three_personal_starting_points() {
        composeTestRule.onNodeWithText("Something I handled well").assertIsDisplayed()
        composeTestRule.onNodeWithText("What people appreciate about me").assertIsDisplayed()
        composeTestRule.onNodeWithText("A reminder for a difficult day").assertIsDisplayed()
    }

    @Test
    fun saving_a_freeform_note_returns_to_the_library() {
        composeTestRule.onNodeWithText("Add something good").performClick()
        composeTestRule.onNodeWithTag("entry-title").performTextInput("I showed up")
        composeTestRule.onNodeWithTag("entry-body").performTextInput("I called a friend when it mattered.")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("I showed up").assertIsDisplayed()
    }
}
