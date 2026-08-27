package app.respiral

import android.app.Application
import android.content.Context
import app.respiral.data.settings.DataStoreSettingsRepository
import app.respiral.core.time.SystemClock
import app.respiral.security.DefaultVaultSession
import app.respiral.security.VaultSession

/** The app-local dependency construction root. */
class RespiralApplication : Application() {
    /**
     * The process-wide settings repository. DataStore must not be constructed more than once for
     * a given file, so every Android entry point obtains this instance from the application.
     */
    val settingsRepository: DataStoreSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DataStoreSettingsRepository.create(this)
    }

    /** A process-local session; the vault lock never persists an app credential. */
    val vaultSession: VaultSession by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultVaultSession(SystemClock)
    }

    companion object {
        fun from(context: Context): RespiralApplication =
            context.applicationContext as? RespiralApplication
                ?: error("Respiral components must run in RespiralApplication")
    }
}
