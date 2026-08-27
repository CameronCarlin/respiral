package app.respiral.ui.capture

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditorViewModelTest {
    @Test
    fun saving_existing_photo_entry_without_new_selection_retains_existing_media() = runTest {
        val id = UUID.randomUUID()
        val existing = VaultEntry(
            id = id,
            title = "A photo note",
            body = "A note with a photo.",
            createdAt = Instant.parse("2026-08-26T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-26T10:00:00Z"),
            tags = emptySet(),
            media = listOf(app.respiral.core.model.VaultMedia("media/$id/0.jpg", "image/jpeg")),
        )
        val repository = RecordingVaultRepository(existing)
        val viewModel = EntryEditorViewModel(repository, entryId = existing.id)

        assertThat(viewModel.load()).isTrue()
        assertThat(viewModel.save()).isTrue()
        assertThat(repository.savedEntry?.media).isEqualTo(existing.media)
        assertThat(repository.savedPendingMedia).isEmpty()
    }

    @Test
    fun existing_photo_entry_cannot_save_before_load_completes() = runTest {
        val id = UUID.randomUUID()
        val existing = VaultEntry(
            id = id,
            title = "A photo note",
            body = "A note with a photo.",
            createdAt = Instant.parse("2026-08-26T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-26T10:00:00Z"),
            tags = emptySet(),
            media = listOf(app.respiral.core.model.VaultMedia("media/$id/0.jpg", "image/jpeg")),
        )
        val repository = DeferredRecordingVaultRepository(existing)
        val viewModel = EntryEditorViewModel(repository, entryId = existing.id)
        val loading = async { viewModel.load() }
        runCurrent()

        assertThat(viewModel.save()).isFalse()
        assertThat(repository.savedEntry).isNull()

        repository.allowGet.complete(Unit)
        assertThat(loading.await()).isTrue()
        assertThat(viewModel.save()).isTrue()
        assertThat(repository.savedEntry?.media).isEqualTo(existing.media)
    }

    @Test
    fun save_failure_keeps_the_draft_intact() = runTest {
        val viewModel = EntryEditorViewModel(FailingVaultRepository())
        viewModel.title = "I showed up"
        viewModel.body = "I called a friend when it mattered."
        viewModel.tags = setOf(VaultTag.AFFIRMATION)

        assertThat(viewModel.save()).isFalse()
        assertThat(viewModel.title).isEqualTo("I showed up")
        assertThat(viewModel.body).isEqualTo("I called a friend when it mattered.")
        assertThat(viewModel.tags).containsExactly(VaultTag.AFFIRMATION)
    }
}

private class RecordingVaultRepository(private val existing: VaultEntry) : VaultRepository {
    var savedEntry: VaultEntry? = null
    var savedPendingMedia: List<PendingMedia>? = null

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry {
        savedEntry = entry
        savedPendingMedia = pendingMedia
        return entry
    }

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = emptyFlow()

    override suspend fun get(id: UUID): VaultEntry = existing

    override suspend fun delete(id: UUID) = Unit

    override suspend fun rebuildIndex() = Unit
}

private class DeferredRecordingVaultRepository(private val existing: VaultEntry) : VaultRepository {
    val allowGet = CompletableDeferred<Unit>()
    var savedEntry: VaultEntry? = null

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry {
        savedEntry = entry
        return entry
    }

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = emptyFlow()

    override suspend fun get(id: UUID): VaultEntry {
        allowGet.await()
        return existing
    }

    override suspend fun delete(id: UUID) = Unit

    override suspend fun rebuildIndex() = Unit
}

private class FailingVaultRepository : VaultRepository {
    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry {
        throw IOException("Vault unavailable")
    }

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = emptyFlow()

    override suspend fun get(id: UUID): VaultEntry = throw UnsupportedOperationException()

    override suspend fun delete(id: UUID) = Unit

    override suspend fun rebuildIndex() = Unit
}
