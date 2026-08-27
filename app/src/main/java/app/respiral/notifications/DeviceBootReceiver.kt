package app.respiral.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.respiral.data.settings.DataStoreSettingsRepository
import app.respiral.core.time.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restores an opted-in reminder after reboot or a device-local time-zone change. */
class DeviceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = DataStoreSettingsRepository.create(appContext).settings.first()
                if (settings.reminderModes.isNotEmpty() && settings.reminderTime != null) {
                    DefaultReminderScheduler(AndroidAlarmGateway(appContext), SystemClock).schedule(settings)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
