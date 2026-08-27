package app.respiral.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.respiral.core.model.VaultSettings
import app.respiral.data.settings.SettingsRepository
import app.respiral.notifications.ReminderMode
import app.respiral.notifications.ReminderScheduler
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Presentation state for the deliberately local, low-impact settings surface. */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val scheduler: ReminderScheduler,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(VaultSettings())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var collectJob: Job = scope.launch {
        repository.settings.collectLatest { settings = it }
    }

    fun setLockEnabled(enabled: Boolean) = update { copy(lockEnabled = enabled) }

    fun setRevealNotificationText(enabled: Boolean) = update {
        copy(revealNotificationText = enabled)
    }

    fun setReminderMode(mode: ReminderMode, enabled: Boolean) = update {
        copy(reminderModes = if (enabled) reminderModes + mode else reminderModes - mode)
    }

    fun setReminderTime(value: String): Boolean {
        val parsed = runCatching { LocalTime.parse(value.trim()) }.getOrNull()
        if (parsed == null) {
            message = "Use a time such as 08:00."
            return false
        }
        update { copy(reminderTime = parsed) }
        return true
    }

    fun clearMessage() {
        message = null
    }

    /** Cancels the repository collector when this model is used outside a Compose lifecycle. */
    fun close() {
        collectJob.cancel()
    }

    private fun update(transform: VaultSettings.() -> VaultSettings) {
        val updated = settings.transform()
        settings = updated
        message = null
        scope.launch {
            repository.update(updated)
            // Scheduling is intentionally immediate. The scheduler cancels the pending alarm
            // when a mode or time is cleared, so stale reminders cannot survive an edit.
            scheduler.schedule(updated)
        }
    }
}
