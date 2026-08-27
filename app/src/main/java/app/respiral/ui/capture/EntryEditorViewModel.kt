package app.respiral.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultRepository
import java.time.Instant
import java.util.UUID

class EntryEditorViewModel(
    private val repository: VaultRepository,
    private val entryId: UUID? = null,
    initialPrompt: String = "",
    initialTags: Set<VaultTag> = emptySet(),
    private val now: () -> Instant = Instant::now,
) {
    var title by mutableStateOf("")
    var body by mutableStateOf(initialPrompt)
    var tags by mutableStateOf(initialTags)
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val pendingMedia = mutableStateListOf<PendingMedia>()
    val media: List<PendingMedia>
        get() = pendingMedia.toList()

    private var existingEntry: VaultEntry? = null

    suspend fun load(): Boolean = try {
        entryId?.let { repository.get(it) }?.also { entry ->
            existingEntry = entry
            title = entry.title
            body = entry.body
            tags = entry.tags
        }
        true
    } catch (_: Throwable) {
        errorMessage = "Couldn't open this entry. Your draft is still here."
        false
    }

    fun addPhoto(media: PendingMedia) {
        pendingMedia += media
        errorMessage = null
    }

    fun removePhoto(media: PendingMedia) {
        pendingMedia.remove(media)
    }

    suspend fun save(): Boolean = try {
        val timestamp = now()
        val previous = existingEntry
        repository.save(
            VaultEntry(
                id = previous?.id ?: entryId ?: UUID.randomUUID(),
                title = title,
                body = body,
                createdAt = previous?.createdAt ?: timestamp,
                updatedAt = timestamp,
                tags = tags,
                media = previous?.media.orEmpty(),
            ),
            pendingMedia.toList(),
        )
        errorMessage = null
        true
    } catch (_: Throwable) {
        errorMessage = "Couldn't save this entry. Your draft is still here."
        false
    }

    suspend fun delete(): Boolean = try {
        val id = entryId ?: return false
        repository.delete(id)
        true
    } catch (_: Throwable) {
        errorMessage = "Couldn't delete this entry."
        false
    }
}
