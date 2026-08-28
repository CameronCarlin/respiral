package app.respiral.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.respiral.core.model.VaultSettings
import app.respiral.notifications.ReminderMode
import app.respiral.data.vault.PersistedRecoveryState
import app.respiral.data.vault.VaultDiagnosticCode
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
            onboardingSeen = true,
            lockEnabled = true,
            reminderModes = setOf(ReminderMode.SAVED_ENTRY, ReminderMode.PRIVATE_NUDGE),
            reminderTime = LocalTime.of(8, 30),
            revealNotificationText = true,
        )

        assertThat(repository.settings.first()).isEqualTo(VaultSettings())

        repository.update(expected)

        assertThat(repository.settings.first()).isEqualTo(expected)
    }

    @Test
    fun recovery_state_persists_version_and_safe_failure_summary() = runTest {
        val file = Files.createTempFile("respiral-recovery-", ".preferences_pb").toFile()
        file.delete()
        val repository = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { file },
            ),
        )
        val expected = PersistedRecoveryState(
            version = 1,
            diagnosticCode = VaultDiagnosticCode.RSP_R02,
            failureCount = 2,
        )

        repository.writeRecoveryState(expected)

        assertThat(repository.readRecoveryState()).isEqualTo(expected)
    }
}
