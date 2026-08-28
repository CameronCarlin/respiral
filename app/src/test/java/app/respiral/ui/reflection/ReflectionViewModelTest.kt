package app.respiral.ui.reflection

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultDiagnosticCode
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultHealth
import app.respiral.data.vault.VaultRepository
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    @Test
    fun next_entry_rethrows_cancellation_instead_of_turning_it_into_an_error_message() = runTest {
        val cancellation = CancellationException("test cancellation")
        val repository = ReflectionRepository(emptyList(), cancellation)
        val viewModel = ReflectionViewModel(repository, emptySet())

        try {
            viewModel.nextEntry()
            throw AssertionError("Expected cancellation to be rethrown")
        } catch (actual: CancellationException) {
            assertThat(actual).isSameInstanceAs(cancellation)
        }

        assertThat(viewModel.isLoading).isFalse()
        assertThat(viewModel.message).isNull()
    }

    @Test
    fun empty_index_is_rebuilt_before_reporting_that_the_vault_is_empty() = runTest {
        val saved = entry("Saved note", setOf(VaultTag.AFFIRMATION))
        val repository = RebuildingReflectionRepository(saved)
        val viewModel = ReflectionViewModel(repository, emptySet()) { candidates -> candidates.first() }

        assertThat(viewModel.nextEntry()).isTrue()
        assertThat(viewModel.entry?.title).isEqualTo("Saved note")
        assertThat(repository.rebuildCalls).isEqualTo(1)
    }

    @Test
    fun unreadable_index_is_rebuilt_before_reporting_that_the_note_cannot_open() = runTest {
        val saved = entry("Recovered note", setOf(VaultTag.WHO_I_AM))
        val repository = ThrowingThenRebuildingRepository(saved)
        val viewModel = ReflectionViewModel(repository, emptySet()) { candidates -> candidates.first() }

        assertThat(viewModel.nextEntry()).isTrue()
        assertThat(viewModel.entry?.title).isEqualTo("Recovered note")
        assertThat(repository.rebuildCalls).isEqualTo(1)
        assertThat(viewModel.message).isNull()
    }

    @Test
    fun missing_selected_entry_refreshes_once_and_selects_from_remaining_markdown() = runTest {
        val first = entry("Stale note", emptySet())
        val remaining = entry("Remaining note", emptySet())
        val repository = EntryDisappearsRepository(first, remaining)
        val viewModel = ReflectionViewModel(repository, emptySet()) { candidates -> candidates.first() }

        assertThat(viewModel.nextEntry()).isTrue()
        assertThat(viewModel.entry).isEqualTo(remaining)
        assertThat(repository.rebuildCalls).isEqualTo(1)
    }

    @Test
    fun empty_attention_vault_keeps_the_original_files_warning_visible() = runTest {
        val repository = EmptyAttentionRepository()
        val viewModel = ReflectionViewModel(repository, emptySet())

        assertThat(viewModel.nextEntry()).isFalse()
        assertThat(viewModel.message)
            .isEqualTo("Some notes need attention. Your original files have not been removed. Diagnostic: RSP-R02.")
        assertThat(viewModel.shouldKeepAppData).isTrue()
    }

    @Test
    fun stale_replacement_after_rebuild_keeps_the_attention_guidance_visible() = runTest {
        // Catches a replacement get() that escapes to nextEntry's generic error handler.
        val repository = ReplacementAlsoDisappearsRepository(
            stale = entry("First stale note", emptySet()),
            replacement = entry("Replacement stale note", emptySet()),
        )
        val viewModel = ReflectionViewModel(repository, emptySet()) { candidates -> candidates.first() }

        assertThat(viewModel.nextEntry()).isFalse()
        assertThat(viewModel.message)
            .isEqualTo("Some notes need attention. Your original files have not been removed. Diagnostic: RSP-R02.")
        assertThat(viewModel.shouldKeepAppData).isTrue()
        assertThat(repository.rebuildCalls).isEqualTo(1)
    }
}

private class EntryDisappearsRepository(
    private val stale: VaultEntry,
    private val remaining: VaultEntry,
) : VaultRepository {
    var rebuilt = false
    var rebuildCalls = 0

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry
    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = flowOf(
        listOf(if (rebuilt) remaining else stale).map { it.toSummary() },
    )
    override suspend fun get(id: UUID): VaultEntry =
        if (id == remaining.id) remaining else error("The selected entry disappeared")
    override suspend fun delete(id: UUID) = Unit
    override suspend fun rebuildIndex() { rebuilt = true; rebuildCalls += 1 }
}

private class EmptyAttentionRepository : VaultRepository {
    override val health = flowOf<VaultHealth>(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 1))

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry
    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = flowOf(emptyList())
    override suspend fun get(id: UUID): VaultEntry = error("unused")
    override suspend fun delete(id: UUID) = Unit
    override suspend fun rebuildIndex() = Unit
}

private class ReplacementAlsoDisappearsRepository(
    private val stale: VaultEntry,
    private val replacement: VaultEntry,
) : VaultRepository {
    var rebuilt = false
    var rebuildCalls = 0

    override val health = flowOf<VaultHealth>(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 1))

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry
    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = flowOf(
        listOf(if (rebuilt) replacement else stale).map { it.toSummary() },
    )
    override suspend fun get(id: UUID): VaultEntry = error("The selected entry disappeared")
    override suspend fun delete(id: UUID) = Unit
    override suspend fun rebuildIndex() { rebuilt = true; rebuildCalls += 1 }
}

private class RebuildingReflectionRepository(private val saved: VaultEntry) : VaultRepository {
    var rebuilt = false
    var rebuildCalls = 0

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry
    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = flowOf(
        if (rebuilt) listOf(VaultEntrySummary(saved.id, saved.title, saved.createdAt, saved.tags)) else emptyList(),
    )
    override suspend fun get(id: UUID): VaultEntry = saved
    override suspend fun delete(id: UUID) = Unit
    override suspend fun rebuildIndex() { rebuilt = true; rebuildCalls += 1 }
}

private class ThrowingThenRebuildingRepository(private val saved: VaultEntry) : VaultRepository {
    var rebuilt = false
    var rebuildCalls = 0

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry
    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> =
        if (rebuilt) {
            flowOf(listOf(VaultEntrySummary(saved.id, saved.title, saved.createdAt, saved.tags)))
        } else {
            flow { error("unreadable index") }
        }
    override suspend fun get(id: UUID): VaultEntry = saved
    override suspend fun delete(id: UUID) = Unit
    override suspend fun rebuildIndex() { rebuilt = true; rebuildCalls += 1 }
}

private class ReflectionRepository(
    private val entries: List<VaultEntry>,
    private val observeFailure: CancellationException? = null,
) : VaultRepository {
    var requestedTags: Set<VaultTag>? = null

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry = entry

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> {
        requestedTags = tags
        observeFailure?.let { failure -> return flow { throw failure } }
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

private fun VaultEntry.toSummary() = VaultEntrySummary(id, title, createdAt, tags)
