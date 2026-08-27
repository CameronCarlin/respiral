package app.respiral.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Keeps the library query and tag selection close to the timeline it drives. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    repository: VaultRepository,
    scope: CoroutineScope,
) {
    private val queryFlow = MutableStateFlow("")
    private val tagsFlow = MutableStateFlow<Set<VaultTag>>(emptySet())
    private var queryValue by mutableStateOf("")

    var query: String
        get() = queryValue
        set(value) {
            queryValue = value
            queryFlow.value = value
        }

    var selectedTags by mutableStateOf<Set<VaultTag>>(emptySet())
        private set

    var loadError by mutableStateOf(false)
        private set

    val entries: StateFlow<List<VaultEntrySummary>> = combine(queryFlow, tagsFlow) { query, tags -> query to tags }
        .flatMapLatest { (query, tags) ->
            repository.observeTimeline(query, tags)
                .catch {
                    loadError = true
                    emit(emptyList())
                }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun toggleTag(tag: VaultTag) {
        selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
        tagsFlow.value = selectedTags
    }
}
