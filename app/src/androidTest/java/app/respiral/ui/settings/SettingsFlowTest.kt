package app.respiral.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.respiral.core.model.VaultSettings
import app.respiral.data.settings.SettingsRepository
import app.respiral.notifications.ReminderScheduler
import app.respiral.ui.security.LockedVaultScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class SettingsFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun notification_modes_are_off_until_explicitly_enabled() {
        val repository = TestSettingsRepository()
        composeTestRule.setContent {
            val scope = rememberCoroutineScope()
            val viewModel = remember { SettingsViewModel(repository, NoOpScheduler, scope) }
            SettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onExport = {},
                onImport = {},
                onRequestNotificationPermission = {},
            )
        }

        composeTestRule.onNodeWithTag("reminder-saved-entry").assertIsOff()
        composeTestRule.onNodeWithTag("reminder-private-nudge").assertIsOff()
        composeTestRule.onNodeWithTag("reminder-capture-prompt").assertIsOff()
    }

    @Test
    fun locked_vault_offers_only_the_native_authenticate_action() {
        composeTestRule.setContent { LockedVaultScreen(onAuthenticate = {}) }

        composeTestRule.onNodeWithText("Unlock Respiral").assertIsDisplayed()
        composeTestRule.onNodeWithTag("authenticate-vault").assertIsDisplayed()
    }
}

private class TestSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(VaultSettings())
    override val settings: Flow<VaultSettings> = state
    override suspend fun update(settings: VaultSettings) { state.value = settings }
}

private object NoOpScheduler : ReminderScheduler {
    override fun schedule(settings: VaultSettings) = Unit
    override fun cancel() = Unit
}
