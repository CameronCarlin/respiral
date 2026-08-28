package app.respiral

import android.app.Application
import android.content.Context
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.time.SystemClock
import app.respiral.data.index.RespiralDatabase
import app.respiral.data.settings.DataStoreSettingsRepository
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.LegacyVaultRecovery
import app.respiral.data.vault.LegacyVaultSource
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultRuntime
import app.respiral.security.DefaultVaultSession
import app.respiral.security.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app-local dependency construction root. */
class RespiralApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The process-wide settings repository. DataStore must not be constructed more than once for
     * a given file, so every Android entry point obtains this instance from the application.
     */
    val settingsRepository: DataStoreSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DataStoreSettingsRepository.create(this)
    }

    val vaultFileStore: VaultFileStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultFileStore(this, CanonicalMarkdownEntryCodec())
    }

    private val vaultRuntime: VaultRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val fileStore = vaultFileStore
        VaultRuntime(
            repository = DefaultVaultRepository(fileStore),
            recovery = LegacyVaultRecovery(fileStore),
            recoveryStateStore = settingsRepository,
            legacyDatabaseExists = { getDatabasePath(LEGACY_DATABASE_NAME).isFile },
            openLegacyDatabase = {
                val database = RespiralDatabase.create(this)
                object : LegacyVaultSource {
                    override suspend fun snapshot() = database.entryIndexDao().snapshot()
                    override fun close() = database.close()
                }
            },
        )
    }

    val vaultRepository: DefaultVaultRepository
        get() = vaultRuntime.repository

    suspend fun awaitVaultRepository(): DefaultVaultRepository = vaultRuntime.awaitReady()

    /** A process-local session; the vault lock never persists an app credential. */
    val vaultSession: VaultSession by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultVaultSession(SystemClock)
    }

    override fun onCreate() {
        super.onCreate()
        vaultRuntime.start(applicationScope)
    }

    companion object {
        private const val LEGACY_DATABASE_NAME = "respiral-index.db"

        fun from(context: Context): RespiralApplication =
            context.applicationContext as? RespiralApplication
                ?: error("Respiral components must run in RespiralApplication")
    }
}
