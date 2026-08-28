package app.respiral.ui.reflection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultHealth
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
    var shouldKeepAppData by mutableStateOf(false)
        private set

    suspend fun nextEntry(): Boolean {
        isLoading = true
        return try {
            val summaries = loadSummariesWithOneRebuild()
            val chosen = selectMatching(summaries)
            if (chosen == null) {
                showNoMatchingEntryMessage()
                false
            } else {
                selectEntry(chosen)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            entry = null
            message = "This note couldn't be opened right now. Your vault stays private on this device."
            shouldKeepAppData = false
            false
        } finally {
            isLoading = false
        }
    }

    private suspend fun selectEntry(chosen: VaultEntrySummary): Boolean {
        try {
            entry = repository.get(chosen.id)
            message = null
            shouldKeepAppData = false
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            repository.rebuildIndex()
            val refreshed = repository.observeTimeline(query = "", tags = activeTags).first()
            val replacement = selectMatching(refreshed)
            if (replacement == null) {
                showNoMatchingEntryMessage()
                return false
            }
            try {
                entry = repository.get(replacement.id)
                message = null
                shouldKeepAppData = false
                return true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                showNoMatchingEntryMessage()
                return false
            }
        }
    }

    private fun selectMatching(summaries: List<VaultEntrySummary>): VaultEntrySummary? = summaries
        .filter { summary -> activeTags.isEmpty() || summary.tags.any(activeTags::contains) }
        .takeIf { it.isNotEmpty() }
        ?.let(select)

    private suspend fun showNoMatchingEntryMessage() {
        entry = null
        when (val health = repository.health.first()) {
            is VaultHealth.NeedsAttention -> {
                message = health.messageOrNull()
                shouldKeepAppData = true
            }

            else -> {
                message = "There isn't a matching note yet. Your words will be here when you are ready."
                shouldKeepAppData = false
            }
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

internal fun VaultHealth.messageOrNull(): String? = when (this) {
    VaultHealth.Loading,
    VaultHealth.Healthy,
    -> null

    VaultHealth.Recovered(1) -> "Respiral gently repaired 1 local note."
    is VaultHealth.Recovered -> "Respiral gently repaired $count local notes."
    is VaultHealth.NeedsAttention ->
        "Some notes need attention. Your original files have not been removed. Diagnostic: ${code.displayValue}."
}
