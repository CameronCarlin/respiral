package app.respiral.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.respiral.core.model.VaultSettings
import app.respiral.notifications.ReminderMode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun update_persists_every_setting_and_defaults_to_private_options() = runTest {
        val file = Files.createTempFile("respiral-settings-", ".preferences_pb").toFile()
        file.delete()
        val repository = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { file },
            ),
        )
        val expected = VaultSettings(
            lockEnabled = true,
            reminderModes = setOf(ReminderMode.SAVED_ENTRY, ReminderMode.PRIVATE_NUDGE),
            reminderTime = LocalTime.of(8, 30),
            revealNotificationText = true,
        )

        assertThat(repository.settings.first()).isEqualTo(VaultSettings())

        repository.update(expected)

        assertThat(repository.settings.first()).isEqualTo(expected)
    }
}
