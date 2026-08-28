package app.respiral.ui.reflection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Selects a locally indexed vault entry without recording or ranking the selection. */
class ReflectionViewModel(
    private val repository: VaultRepository,
    private val activeTags: Set<VaultTag>,
    private val select: (List<VaultEntrySummary>) -> VaultEntrySummary = { entries -> entries.random() },
) {
    var entry by mutableStateOf<VaultEntry?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    suspend fun nextEntry(): Boolean {
        isLoading = true
        return try {
            val summaries = loadSummariesWithOneRebuild()
            val matches = summaries.filter { summary ->
                activeTags.isEmpty() || summary.tags.any(activeTags::contains)
            }
            val chosen = matches.takeIf { it.isNotEmpty() }?.let(select)
            if (chosen == null) {
                entry = null
                message = "There isn't a matching note yet. Your words will be here when you are ready."
                false
            } else {
                entry = repository.get(chosen.id)
                message = null
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            entry = null
            message = "This note couldn't be opened right now. Your vault stays private on this device."
            false
        } finally {
            isLoading = false
        }
    }

    private suspend fun loadSummariesWithOneRebuild(): List<VaultEntrySummary> {
        val firstRead = try {
            repository.observeTimeline(query = "", tags = activeTags).first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (!firstRead.isNullOrEmpty()) return firstRead

        // Room is only a rebuildable projection. Recover once from the canonical Markdown vault
        // when the projection is empty or unreadable, then let the caller report any real vault
        // failure from the second attempt.
        repository.rebuildIndex()
        return repository.observeTimeline(query = "", tags = activeTags).first()
    }
}
