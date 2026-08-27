package app.respiral.data.vault

import android.net.Uri
import androidx.room.withTransaction
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.index.EntryIndexEntity
import app.respiral.data.index.RespiralDatabase
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A newly selected media item; it is appended to an entry's existing canonical media. */
data class PendingMedia(val source: Uri, val mimeType: String)

data class VaultEntrySummary(
    val id: UUID,
    val title: String,
    val createdAt: Instant,
    val tags: Set<VaultTag>,
)

interface VaultRepository {
    /** Saves text and retains [entry.media], appending any newly selected [pendingMedia]. */
    suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry

    fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>>

    suspend fun get(id: UUID): VaultEntry

    suspend fun delete(id: UUID)

    suspend fun rebuildIndex()
}

class DefaultVaultRepository private constructor(
    private val fileStore: VaultFileStore,
    private val database: RespiralDatabase,
    private val afterSaveIndexUpsert: suspend () -> Unit,
) : VaultRepository {
    constructor(fileStore: VaultFileStore, database: RespiralDatabase) : this(fileStore, database, {})

    internal companion object {
        fun withAfterSaveIndexUpsert(
            fileStore: VaultFileStore,
            database: RespiralDatabase,
            afterSaveIndexUpsert: suspend () -> Unit,
        ): DefaultVaultRepository = DefaultVaultRepository(fileStore, database, afterSaveIndexUpsert)
    }

    private val indexDao = database.entryIndexDao()

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry {
        val staged = fileStore.stage(entry, pendingMedia)
        val promoted = fileStore.promote(staged)
        try {
            database.withTransaction {
                indexDao.upsert(staged.entry.toIndexEntity())
                afterSaveIndexUpsert()
            }
            promoted.complete()
            return staged.entry
        } catch (error: Throwable) {
            promoted.restore()
            throw error
        }
    }

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> =
        indexDao.observeTimeline(
            query = query.escapeLikePattern(),
            hasSelectedTags = tags.isNotEmpty(),
            includeAchievement = VaultTag.ACHIEVEMENT in tags,
            includeAffirmation = VaultTag.AFFIRMATION in tags,
            includeWhoIAm = VaultTag.WHO_I_AM in tags,
        ).map { entries -> entries.map(EntryIndexEntity::toSummary) }

    override suspend fun get(id: UUID): VaultEntry = fileStore.read(id)

    override suspend fun delete(id: UUID) {
        val stagedDeletion = fileStore.stageDelete(id)
        try {
            database.withTransaction {
                indexDao.delete(id.toString())
            }
            stagedDeletion.complete()
        } catch (error: Throwable) {
            stagedDeletion.restore()
            throw error
        }
    }

    override suspend fun rebuildIndex() {
        val entries = fileStore.readAll()
        database.withTransaction {
            indexDao.clear()
            entries.forEach { indexDao.upsert(it.toIndexEntity()) }
        }
    }
}

private fun VaultEntry.toIndexEntity(): EntryIndexEntity = EntryIndexEntity(
    id = id.toString(),
    title = title,
    bodyForSearch = body,
    createdAtEpochMs = createdAt.toEpochMilli(),
    updatedAtEpochMs = updatedAt.toEpochMilli(),
    tagNames = tags.sortedBy(VaultTag::ordinal).joinToString(separator = "", prefix = "|", postfix = "|") { it.name },
)

private fun EntryIndexEntity.toSummary(): VaultEntrySummary = VaultEntrySummary(
    id = UUID.fromString(id),
    title = title,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    tags = tagNames
        .trim('|')
        .split('|')
        .filter(String::isNotEmpty)
        .map(VaultTag::valueOf)
        .toSet(),
)

private fun String.escapeLikePattern(): String = buildString(length) {
    for (character in this@escapeLikePattern) {
        if (character == '%' || character == '_' || character == '\\') append('\\')
        append(character)
    }
}
