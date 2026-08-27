package app.respiral.ui.settings

import app.respiral.core.model.VaultSettings
import app.respiral.data.settings.SettingsRepository
import app.respiral.notifications.ReminderMode
import app.respiral.notifications.ReminderScheduler
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun enabling_a_reminder_persists_it_and_schedules_immediately() = runTest {
        val repository = FakeSettingsRepository()
        val scheduler = RecordingScheduler()
        val viewModel = SettingsViewModel(repository, scheduler, this)

        viewModel.setReminderMode(ReminderMode.PRIVATE_NUDGE, enabled = true)
        advanceUntilIdle()

        assertThat(repository.settings.first().reminderModes)
            .containsExactly(ReminderMode.PRIVATE_NUDGE)
        assertThat(scheduler.scheduled).containsExactly(repository.settings.first())
        viewModel.close()
    }

    @Test
    fun invalid_reminder_time_is_rejected_without_changing_settings() = runTest {
        val repository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(repository, RecordingScheduler(), this)

        val accepted = viewModel.setReminderTime("not a time")
        advanceUntilIdle()

        assertThat(accepted).isFalse()
        assertThat(repository.settings.first().reminderTime).isNull()
        assertThat(viewModel.message).isEqualTo("Use a time such as 08:00.")
        viewModel.close()
    }
}

private class FakeSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(VaultSettings())
    override val settings: Flow<VaultSettings> = state
    override suspend fun update(settings: VaultSettings) {
        state.value = settings
    }
}

private class RecordingScheduler : ReminderScheduler {
    val scheduled = mutableListOf<VaultSettings>()
    override fun schedule(settings: VaultSettings) {
        scheduled += settings
    }
    override fun cancel() = Unit
}
