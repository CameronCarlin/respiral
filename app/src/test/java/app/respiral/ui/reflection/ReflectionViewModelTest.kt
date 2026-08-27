package app.respiral.ui.reflection

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReflectionViewModelTest {
    @Test
    fun next_entry_selects_only_indexed_entries_matching_the_active_tags() = runTest {
        val achievement = entry("Achievement", setOf(VaultTag.ACHIEVEMENT))
        val affirmation = entry("Affirmation", setOf(VaultTag.AFFIRMATION))
        val repository = ReflectionRepository(listOf(achievement, affirmation))
        val viewModel = ReflectionViewModel(repository, setOf(VaultTag.AFFIRMATION)) { candidates -> candidates.first() }

        assertThat(viewModel.nextEntry()).isTrue()
        assertThat(viewModel.entry?.title).isEqualTo("Affirmation")
        assertThat(repository.requestedTags).isEqualTo(setOf(VaultTag.AFFIRMATION))
    }
}

private class ReflectionRepository(private val entries: List<VaultEntry>) : VaultRepository {
    var requestedTags: Set<VaultTag>? = null

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> {
        requestedTags = tags
        return flowOf(entries.map { entry -> VaultEntrySummary(entry.id, entry.title, entry.createdAt, entry.tags) })
    }

    override suspend fun get(id: UUID): VaultEntry = entries.first { it.id == id }

    override suspend fun delete(id: UUID) = Unit

    override suspend fun rebuildIndex() = Unit
}

private fun entry(title: String, tags: Set<VaultTag>): VaultEntry = VaultEntry(
    id = UUID.nameUUIDFromBytes(title.toByteArray()),
    title = title,
    body = "A private note.",
    createdAt = Instant.parse("2026-08-26T09:00:00Z"),
    updatedAt = Instant.parse("2026-08-26T09:00:00Z"),
    tags = tags,
    media = emptyList(),
)
