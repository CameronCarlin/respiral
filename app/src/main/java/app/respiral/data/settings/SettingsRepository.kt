package app.respiral.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import android.content.Context
import java.io.File
import app.respiral.core.model.VaultSettings
import app.respiral.notifications.ReminderMode
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<VaultSettings>

    suspend fun update(settings: VaultSettings)
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<VaultSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toVaultSettings)

    override suspend fun update(settings: VaultSettings) {
        dataStore.edit { preferences ->
            preferences[ONBOARDING_SEEN] = settings.onboardingSeen
            preferences[LOCK_ENABLED] = settings.lockEnabled
            preferences[REMINDER_MODES] = settings.reminderModes.map(ReminderMode::name).toSet()
            settings.reminderTime?.let { preferences[REMINDER_TIME] = it.toString() }
                ?: preferences.remove(REMINDER_TIME)
            preferences[REVEAL_NOTIFICATION_TEXT] = settings.revealNotificationText
        }
    }

    private fun toVaultSettings(preferences: Preferences): VaultSettings = VaultSettings(
        onboardingSeen = preferences[ONBOARDING_SEEN] ?: false,
        lockEnabled = preferences[LOCK_ENABLED] ?: false,
        reminderModes = preferences[REMINDER_MODES]
            .orEmpty()
            .mapNotNull { name -> runCatching { ReminderMode.valueOf(name) }.getOrNull() }
            .toSet(),
        reminderTime = preferences[REMINDER_TIME]
            ?.let { value -> runCatching { LocalTime.parse(value) }.getOrNull() },
        revealNotificationText = preferences[REVEAL_NOTIFICATION_TEXT] ?: false,
    )

    companion object {
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val REMINDER_MODES = stringSetPreferencesKey("reminder_modes")
        val REMINDER_TIME = stringPreferencesKey("reminder_time")
        val REVEAL_NOTIFICATION_TEXT = booleanPreferencesKey("reveal_notification_text")
        fun create(context: Context): DataStoreSettingsRepository = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                produceFile = {
                    File(context.filesDir, "respiral-settings.preferences_pb")
                },
            ),
        )
    }
}
