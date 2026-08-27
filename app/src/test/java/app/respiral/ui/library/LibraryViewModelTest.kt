package app.respiral.ui.library

import app.respiral.core.model.VaultTag
import app.respiral.core.model.VaultEntry
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun changing_the_search_query_reloads_the_reverse_chronological_timeline() = runTest {
        val newer = VaultEntrySummary(UUID.randomUUID(), "Newer kindness", Instant.parse("2026-08-26T10:00:00Z"), emptySet())
        val older = VaultEntrySummary(UUID.randomUUID(), "Older kindness", Instant.parse("2026-08-26T09:00:00Z"), emptySet())
        val repository = LibraryRepository(newer, older)
        val viewModel = LibraryViewModel(repository, backgroundScope)

        viewModel.query = "kindness"
        runCurrent()

        assertThat(viewModel.entries.value).containsExactly(newer, older).inOrder()
        assertThat(repository.queries.last()).isEqualTo("kindness")
    }

    @Test
    fun timeline_failure_is_presented_as_an_empty_library_instead_of_escaping() = runTest {
        val repository = object : VaultRepository {
            override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry
            override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = flow {
                error("corrupt index")
            }
            override suspend fun get(id: UUID): VaultEntry = error("unused")
            override suspend fun delete(id: UUID) = Unit
            override suspend fun rebuildIndex() = Unit
        }

        val viewModel = LibraryViewModel(repository, backgroundScope)
        runCurrent()

        assertThat(viewModel.entries.value).isEmpty()
        assertThat(viewModel.loadError).isTrue()
    }
}

private class LibraryRepository(
    private vararg val summaries: VaultEntrySummary,
) : VaultRepository {
    val queries = mutableListOf<String>()

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> {
        queries += query
        return flowOf(summaries.toList())
    }

    override suspend fun get(id: UUID): VaultEntry = throw UnsupportedOperationException()

    override suspend fun delete(id: UUID) = Unit

    override suspend fun rebuildIndex() = Unit
}
