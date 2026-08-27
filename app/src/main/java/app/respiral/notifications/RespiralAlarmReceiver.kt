package app.respiral.notifications

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.respiral.RespiralApplication
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.time.SystemClock
import app.respiral.data.index.RespiralDatabase
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Posts one locally-rendered reminder and schedules the following day's occurrence. */
class RespiralAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliver(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun deliver(context: Context) {
        val settings = RespiralApplication.from(context).settingsRepository.settings.first()
        if (settings.reminderModes.isEmpty() || settings.reminderTime == null) return

        val repository = defaultVaultRepository(context)
        val factory = DefaultReminderNotificationFactory(randomEntryProvider(repository))
        val mode = reminderModes(settings).random()
        val rendered = factory.build(mode, settings.revealNotificationText)
        if (notificationsAllowed(context)) {
            post(context, rendered)
        }

        // The alarm is one-shot. Scheduling after delivery also keeps the next occurrence local
        // to the device's current time zone if it changed since the previous schedule.
        DefaultReminderScheduler(AndroidAlarmGateway(context), SystemClock).schedule(settings)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, notification: ReminderNotification) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        NotificationManagerCompat.from(context).notify(REMINDER_REQUEST_CODE, builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Daily reminders",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Quiet, private reminders from your Respiral vault"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun defaultVaultRepository(context: Context): VaultRepository = DefaultVaultRepository(
        VaultFileStore(context, CanonicalMarkdownEntryCodec()),
        RespiralDatabase.create(context),
    )
}
