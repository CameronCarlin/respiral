package app.respiral.ui.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun first_save_opens_library_and_the_note_opens_again() {
        composeTestRule.onNodeWithText("Add something good").performClick()
        composeTestRule.onNodeWithTag("entry-title").performTextInput("I showed up")
        composeTestRule.onNodeWithTag("entry-body").performTextInput("I called a friend when it mattered.")
        composeTestRule.onNodeWithText("Save").performClick()

        composeTestRule.waitUntilAtLeastOneExists(hasText("Your library"), 5_000)
        composeTestRule.onNodeWithText("I showed up").performClick()
        composeTestRule.onNodeWithText("I called a friend when it mattered.").assertIsDisplayed()
    }
}
