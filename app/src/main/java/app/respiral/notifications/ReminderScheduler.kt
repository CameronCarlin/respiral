package app.respiral.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultSettings
import app.respiral.core.time.Clock
import app.respiral.core.time.SystemClock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

const val REMINDER_REQUEST_CODE: Int = 7_201
const val REMINDER_CHANNEL_ID: String = "respiral_daily_reminders"

interface ReminderScheduler {
    fun schedule(settings: VaultSettings)

    fun cancel()
}

data class ReminderNotification(val title: String, val body: String)

interface AlarmGateway {
    fun schedule(requestCode: Int, triggerAt: Instant)

    fun cancel(requestCode: Int)
}

interface ReminderNotificationFactory {
    fun build(mode: ReminderMode, revealNotificationText: Boolean): ReminderNotification
}

/** Calculates one future local occurrence and delegates device scheduling to [AlarmGateway]. */
class DefaultReminderScheduler(
    private val alarmGateway: AlarmGateway,
    private val clock: Clock = SystemClock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ReminderScheduler {
    override fun schedule(settings: VaultSettings) {
        val time = settings.reminderTime
        if (settings.reminderModes.isEmpty() || time == null) {
            cancel()
            return
        }

        val now = clock.now().atZone(zoneId)
        val date = if (time <= now.toLocalTime()) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val next = date.atTime(time).atZone(zoneId).toInstant()
        alarmGateway.schedule(REMINDER_REQUEST_CODE, next)
    }

    override fun cancel() {
        alarmGateway.cancel(REMINDER_REQUEST_CODE)
    }
}

/**
 * Renders the three deliberately low-impact reminder modes. The provider is invoked only when
 * the owner has explicitly allowed vault text on the lock screen.
 */
class DefaultReminderNotificationFactory(
    private val entryProvider: () -> VaultEntry? = { null },
) : ReminderNotificationFactory {
    override fun build(mode: ReminderMode, revealNotificationText: Boolean): ReminderNotification {
        if (mode == ReminderMode.SAVED_ENTRY && revealNotificationText) {
            val entry = entryProvider()
            if (entry != null) {
                return ReminderNotification(
                    title = entry.title.ifBlank { "A saved reminder" },
                    body = entry.body.ifBlank { "A few words you left for yourself." },
                )
            }
        }

        return when (mode) {
            ReminderMode.CAPTURE_PROMPT -> ReminderNotification(
                title = "Respiral",
                body = "What is something good you want to remember?",
            )
            ReminderMode.PRIVATE_NUDGE,
            ReminderMode.SAVED_ENTRY,
            -> ReminderNotification(
                title = "Respiral",
                body = "A few words are waiting for you.",
            )
        }
    }
}

/** Android AlarmManager adapter. Inexact idle alarms remain available without exact-alarm access. */
class AndroidAlarmGateway(private val context: Context) : AlarmGateway {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(requestCode: Int, triggerAt: Instant) {
        val operation = pendingIntent(requestCode)
        val triggerMillis = triggerAt.toEpochMilli()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, operation)
                return
            } catch (_: SecurityException) {
                // Permission may be revoked between the capability check and the call. Fall back
                // to the inexact idle API so reminders remain best-effort and local.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, operation)
    }

    override fun cancel(requestCode: Int) {
        alarmManager.cancel(pendingIntent(requestCode))
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, RespiralAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal fun reminderModes(settings: VaultSettings): List<ReminderMode> =
    settings.reminderModes.sortedBy(ReminderMode::ordinal)

internal fun randomEntryProvider(repository: app.respiral.data.vault.VaultRepository): () -> VaultEntry? = {
    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val summary = repository.observeTimeline(query = "", tags = emptySet()).first().randomOrNull()
        summary?.let { repository.get(it.id) }
    }
}
