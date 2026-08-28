package app.respiral.data.vault

import android.net.Uri
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A newly selected media item; it is appended to an entry's existing canonical media. */
data class PendingMedia(val source: Uri, val mimeType: String)

data class VaultEntrySummary(
    val id: UUID,
    val title: String,
    val createdAt: Instant,
    val tags: Set<VaultTag>,
)

interface VaultRepository {
    val health: Flow<VaultHealth>
        get() = flowOf(VaultHealth.Healthy)

    /** Saves text and retains [entry.media], appending any newly selected [pendingMedia]. */
    suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry

    fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>>

    suspend fun get(id: UUID): VaultEntry

    suspend fun delete(id: UUID)

    suspend fun rebuildIndex()

    /**
     * Applies a fully validated, private vault directory. Transfer code is the only caller; the
     * default keeps lightweight repository fakes useful for presentation tests.
     */
    suspend fun applyImportedVault(
        stagedVault: File,
        entries: List<VaultEntry>,
        mode: ImportMode,
    ): Set<UUID> = throw UnsupportedOperationException("Vault import is unavailable")
}

class DefaultVaultRepository private constructor(
    private val fileStore: VaultFileStore,
    private val afterSaveProjectionUpdate: suspend () -> Unit,
    private val afterDeleteProjectionUpdate: suspend () -> Unit,
    private val afterRefreshProjectionUpdate: suspend () -> Unit,
) : VaultRepository {
    constructor(fileStore: VaultFileStore) : this(fileStore, {}, {}, {})

    /** Kept only until Task 4 moves the remaining application construction roots off Room. */
    constructor(
        fileStore: VaultFileStore,
        @Suppress("UNUSED_PARAMETER") legacyDatabase: Any,
    ) : this(fileStore)

    internal companion object {
        fun withAfterSaveProjectionUpdate(
            fileStore: VaultFileStore,
            afterSaveProjectionUpdate: suspend () -> Unit,
        ): DefaultVaultRepository = withMutationHooks(
            fileStore = fileStore,
            afterSaveProjectionUpdate = afterSaveProjectionUpdate,
        )

        fun withMutationHooks(
            fileStore: VaultFileStore,
            afterSaveProjectionUpdate: suspend () -> Unit = {},
            afterDeleteProjectionUpdate: suspend () -> Unit = {},
            afterRefreshProjectionUpdate: suspend () -> Unit = {},
        ): DefaultVaultRepository = DefaultVaultRepository(
            fileStore,
            afterSaveProjectionUpdate,
            afterDeleteProjectionUpdate,
            afterRefreshProjectionUpdate,
        )
    }

    private val mutationMutex = Mutex()
    private val projection = MutableStateFlow<List<VaultEntry>>(emptyList())
    private val mutableHealth = MutableStateFlow<VaultHealth>(VaultHealth.Loading)

    override val health: StateFlow<VaultHealth> = mutableHealth.asStateFlow()

    override suspend fun save(
        entry: VaultEntry,
        pendingMedia: List<PendingMedia>,
    ): VaultEntry = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val previousProjection = projection.value
            val staged = fileStore.stage(entry, pendingMedia)
            val promoted = fileStore.promote(staged)
            try {
                val decoded = fileStore.read(staged.entry.id)
                projection.value = previousProjection
                    .filterNot { it.id == decoded.id }
                    .plus(decoded)
                afterSaveProjectionUpdate()
                promoted.complete()
                decoded
            } catch (error: Throwable) {
                try {
                    promoted.restore()
                } catch (restoreError: Throwable) {
                    error.addSuppressed(restoreError)
                }
                projection.value = previousProjection
                throw error
            }
        }
    }

    override fun observeTimeline(
        query: String,
        tags: Set<VaultTag>,
    ): Flow<List<VaultEntrySummary>> = projection.map { entries ->
        entries
            .asSequence()
            .filter { entry ->
                entry.title.contains(query, ignoreCase = true) ||
                    entry.body.contains(query, ignoreCase = true)
            }
            .filter { entry -> tags.isEmpty() || entry.tags.any(tags::contains) }
            .sortedByDescending(VaultEntry::createdAt)
            .map(VaultEntry::toSummary)
            .toList()
    }

    override suspend fun get(id: UUID): VaultEntry = projection.value
        .firstOrNull { it.id == id }
        ?: throw FileNotFoundException("Vault entry is not in the current projection: $id")

    override suspend fun delete(id: UUID) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val previousProjection = projection.value
            val stagedDeletion = fileStore.stageDelete(id)
            try {
                projection.value = previousProjection.filterNot { it.id == id }
                afterDeleteProjectionUpdate()
                stagedDeletion.complete()
            } catch (error: Throwable) {
                try {
                    stagedDeletion.restore()
                } catch (restoreError: Throwable) {
                    error.addSuppressed(restoreError)
                }
                projection.value = previousProjection
                throw error
            }
        }
    }

    suspend fun refresh(recoveryReport: LegacyRecoveryReport? = null) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            refreshLocked(recoveryReport)
        }
    }

    override suspend fun rebuildIndex() {
        refresh()
    }

    override suspend fun applyImportedVault(
        stagedVault: File,
        entries: List<VaultEntry>,
        mode: ImportMode,
    ): Set<UUID> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            when (mode) {
                ImportMode.MERGE -> mergeImportedVault(stagedVault, entries)
                ImportMode.REPLACE -> replaceImportedVault(stagedVault, entries)
            }
        }
    }

    private suspend fun refreshLocked(recoveryReport: LegacyRecoveryReport? = null) {
        val scan = fileStore.scan()
        projection.value = scan.entries
        mutableHealth.value = when {
            scan.unreadableCount > 0 -> VaultHealth.NeedsAttention(
                VaultDiagnosticCode.RSP_R02,
                scan.unreadableCount,
            )

            recoveryReport?.failureCount?.let { it > 0 } == true -> VaultHealth.NeedsAttention(
                recoveryReport.diagnosticCode!!,
                recoveryReport.failureCount,
            )

            recoveryReport?.recoveredCount?.let { it > 0 } == true ->
                VaultHealth.Recovered(recoveryReport.recoveredCount)

            else -> VaultHealth.Healthy
        }
        afterRefreshProjectionUpdate()
    }

    private suspend fun mergeImportedVault(
        stagedVault: File,
        importedEntries: List<VaultEntry>,
    ): Set<UUID> {
        val previousProjection = projection.value
        val previousHealth = mutableHealth.value
        val localIds = fileStore.scan().entries.mapTo(mutableSetOf(), VaultEntry::id)
        val toImport = importedEntries.filterNot { it.id in localIds }
        val merged = fileStore.merge(stagedVault, toImport.mapTo(mutableSetOf(), VaultEntry::id))
        return try {
            refreshLocked()
            merged.complete()
            toImport.mapTo(mutableSetOf(), VaultEntry::id)
        } catch (error: Throwable) {
            try {
                merged.restore()
            } catch (restoreError: Throwable) {
                error.addSuppressed(restoreError)
            }
            projection.value = previousProjection
            mutableHealth.value = previousHealth
            throw error
        }
    }

    private suspend fun replaceImportedVault(
        stagedVault: File,
        importedEntries: List<VaultEntry>,
    ): Set<UUID> {
        val previousProjection = projection.value
        val previousHealth = mutableHealth.value
        val replacement = fileStore.replaceRoot(stagedVault)
        return try {
            refreshLocked()
            replacement.complete()
            importedEntries.mapTo(mutableSetOf(), VaultEntry::id)
        } catch (error: Throwable) {
            try {
                replacement.restore()
            } catch (restoreError: Throwable) {
                error.addSuppressed(restoreError)
            }
            projection.value = previousProjection
            mutableHealth.value = previousHealth
            throw error
        }
    }
}

private fun VaultEntry.toSummary(): VaultEntrySummary = VaultEntrySummary(
    id = id,
    title = title,
    createdAt = createdAt,
    tags = tags,
)
