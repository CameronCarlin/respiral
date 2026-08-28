package app.respiral.data.vault

import app.respiral.data.index.EntryIndexEntity
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CURRENT_RECOVERY_VERSION = 1

data class PersistedRecoveryState(
    val version: Int = 0,
    val diagnosticCode: VaultDiagnosticCode? = null,
    val failureCount: Int = 0,
)

interface VaultRecoveryStateStore {
    suspend fun readRecoveryState(): PersistedRecoveryState
    suspend fun writeRecoveryState(state: PersistedRecoveryState)
}

/** A deliberately narrow view of the legacy Room database used only during one-time recovery. */
interface LegacyVaultSource : Closeable {
    suspend fun snapshot(): List<EntryIndexEntity>
}

/**
 * Owns the asynchronous migration/readiness boundary for the process-wide file-backed vault.
 * Readiness is completed even if Room cannot be opened so valid Markdown remains usable.
 */
class VaultRuntime(
    val repository: DefaultVaultRepository,
    private val recovery: LegacyVaultRecovery,
    private val recoveryStateStore: VaultRecoveryStateStore,
    private val legacyDatabaseExists: () -> Boolean,
    private val openLegacyDatabase: () -> LegacyVaultSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()

    @Volatile private var readinessFailure: Throwable? = null

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val scopeJob = scope.coroutineContext[Job]
        if (scopeJob?.isActive == false) {
            failReadiness(CancellationException("Vault runtime scope is not active"))
            return
        }
        scopeJob?.invokeOnCompletion { cause ->
            if (cause != null && !ready.isCompleted) failReadiness(cause)
        }
        val startup = scope.launch(ioDispatcher) {
            try {
                bootstrap()
                ready.complete(Unit)
            } catch (error: CancellationException) {
                failReadiness(error)
                throw error
            } catch (_: Throwable) {
                // Settings/storage failures must not strand valid Markdown behind readiness or
                // expose exception text. Persist when possible and always publish a safe code.
                val failed = PersistedRecoveryState(
                    diagnosticCode = VaultDiagnosticCode.RSP_R03,
                    failureCount = 1,
                )
                try {
                    recoveryStateStore.writeRecoveryState(failed)
                } catch (error: CancellationException) {
                    failReadiness(error)
                    throw error
                } catch (_: Throwable) {
                    // The safe state is best-effort when settings storage itself is unavailable.
                }
                try {
                    repository.refresh(
                        LegacyRecoveryReport(
                            recoveredCount = 0,
                            failureCount = failed.failureCount,
                            diagnosticCode = failed.diagnosticCode,
                        ),
                    )
                } catch (error: CancellationException) {
                    failReadiness(error)
                    throw error
                } catch (_: Throwable) {
                    // Readiness still completes if even the best-effort scan cannot be published.
                }
                ready.complete(Unit)
            }
        }
        startup.invokeOnCompletion { cause ->
            if (!ready.isCompleted) {
                failReadiness(
                    cause ?: IllegalStateException("Vault runtime completed before readiness"),
                )
            }
        }
    }

    suspend fun awaitReady(): DefaultVaultRepository {
        ready.await()
        readinessFailure?.let { throw it }
        return repository
    }

    private fun failReadiness(error: Throwable) {
        readinessFailure = error
        ready.complete(Unit)
    }

    private suspend fun bootstrap() = withContext(ioDispatcher) {
        val persisted = recoveryStateStore.readRecoveryState()
        val report = when {
            persisted.version >= CURRENT_RECOVERY_VERSION -> persisted.toRecoveryReport()
            !legacyDatabaseExists() -> {
                val completed = PersistedRecoveryState(version = CURRENT_RECOVERY_VERSION)
                recoveryStateStore.writeRecoveryState(completed)
                null
            }
            else -> recoverLegacyDatabase(persisted)
        }
        repository.refresh(report)
    }

    private suspend fun recoverLegacyDatabase(
        previous: PersistedRecoveryState,
    ): LegacyRecoveryReport? {
        var source: LegacyVaultSource? = null
        return try {
            source = openLegacyDatabase()
            val report = recovery.recover(source.snapshot())
            recoveryStateStore.writeRecoveryState(
                PersistedRecoveryState(
                    version = CURRENT_RECOVERY_VERSION,
                    diagnosticCode = report.diagnosticCode,
                    failureCount = report.failureCount,
                ),
            )
            report
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failed = PersistedRecoveryState(
                version = previous.version,
                diagnosticCode = VaultDiagnosticCode.RSP_R03,
                failureCount = 1,
            )
            recoveryStateStore.writeRecoveryState(failed)
            LegacyRecoveryReport(
                recoveredCount = 0,
                failureCount = failed.failureCount,
                diagnosticCode = failed.diagnosticCode,
            )
        } finally {
            try {
                source?.close()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Closing a retained read-only legacy source must not hide recovered Markdown.
            }
        }
    }

    private fun PersistedRecoveryState.toRecoveryReport(): LegacyRecoveryReport? =
        diagnosticCode?.let { code ->
            LegacyRecoveryReport(
                recoveredCount = 0,
                failureCount = failureCount,
                diagnosticCode = code,
            )
        }
}
