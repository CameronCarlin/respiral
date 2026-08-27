package app.respiral.core.model

import app.respiral.notifications.ReminderMode
import java.time.LocalTime

/** Settings that are local to this device and never part of vault content. */
data class VaultSettings(
    val onboardingSeen: Boolean = false,
    val lockEnabled: Boolean = false,
    val reminderModes: Set<ReminderMode> = emptySet(),
    val reminderTime: LocalTime? = null,
    val revealNotificationText: Boolean = false,
)
