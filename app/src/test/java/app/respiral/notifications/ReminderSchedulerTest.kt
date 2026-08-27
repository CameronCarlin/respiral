package app.respiral.notifications

import app.respiral.core.model.VaultSettings
import app.respiral.core.time.Clock
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalTime
import org.junit.Test

class ReminderSchedulerTest {
    private val fixedNow = Instant.parse("2026-08-27T08:00:00Z")
    private val fakeAlarmGateway = FakeAlarmGateway()
    private val scheduler = DefaultReminderScheduler(
        alarmGateway = fakeAlarmGateway,
        clock = FixedClock(fixedNow),
        zoneId = java.time.ZoneOffset.UTC,
    )

    @Test
    fun disabled_reminders_cancel_all_pending_reminder_alarms() {
        scheduler.schedule(VaultSettings(reminderModes = emptySet(), reminderTime = null))

        assertThat(fakeAlarmGateway.cancelledRequestCodes).contains(REMINDER_REQUEST_CODE)
    }

    @Test
    fun enabled_daily_reminder_schedules_the_next_local_occurrence() {
        scheduler.schedule(
            VaultSettings(
                reminderModes = setOf(ReminderMode.PRIVATE_NUDGE),
                reminderTime = LocalTime.of(8, 30),
            ),
        )

        assertThat(fakeAlarmGateway.nextTriggerAt).isEqualTo(Instant.parse("2026-08-27T08:30:00Z"))
        assertThat(fakeAlarmGateway.scheduledRequestCodes).contains(REMINDER_REQUEST_CODE)
    }

    @Test
    fun a_reminder_at_or_before_the_current_local_time_is_scheduled_for_tomorrow() {
        scheduler.schedule(
            VaultSettings(
                reminderModes = setOf(ReminderMode.CAPTURE_PROMPT),
                reminderTime = LocalTime.of(8, 0),
            ),
        )

        assertThat(fakeAlarmGateway.nextTriggerAt).isEqualTo(Instant.parse("2026-08-28T08:00:00Z"))
    }

    @Test
    fun private_nudge_never_contains_vault_text() {
        val notificationFactory = DefaultReminderNotificationFactory(
            entryProvider = { sampleEntry() },
        )

        val notification = notificationFactory.build(ReminderMode.PRIVATE_NUDGE, revealNotificationText = false)

        assertThat(notification.body).isEqualTo("A few words are waiting for you.")
        assertThat(notification.body).doesNotContain(sampleEntry().body)
    }

    @Test
    fun saved_entry_uses_private_nudge_when_lock_screen_text_is_disabled() {
        val notificationFactory = DefaultReminderNotificationFactory(
            entryProvider = { sampleEntry() },
        )

        val notification = notificationFactory.build(ReminderMode.SAVED_ENTRY, revealNotificationText = false)

        assertThat(notification.body).isEqualTo("A few words are waiting for you.")
        assertThat(notification.body).doesNotContain(sampleEntry().body)
    }
}

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private class FakeAlarmGateway : AlarmGateway {
    val scheduledRequestCodes = mutableListOf<Int>()
    val cancelledRequestCodes = mutableListOf<Int>()
    var nextTriggerAt: Instant? = null

    override fun schedule(requestCode: Int, triggerAt: Instant) {
        scheduledRequestCodes += requestCode
        nextTriggerAt = triggerAt
    }

    override fun cancel(requestCode: Int) {
        cancelledRequestCodes += requestCode
    }
}
