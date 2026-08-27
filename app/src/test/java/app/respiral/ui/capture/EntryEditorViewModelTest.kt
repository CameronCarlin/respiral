package app.respiral.ui.capture

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EntryEditorViewModelTest {
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

private class FailingVaultRepository : VaultRepository {
    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry {
        throw IOException("Vault unavailable")
    }

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = emptyFlow()

    override suspend fun get(id: UUID): VaultEntry = throw UnsupportedOperationException()

    override suspend fun delete(id: UUID) = Unit

    override suspend fun rebuildIndex() = Unit
}
