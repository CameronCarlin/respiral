package app.respiral.ui.reflection

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import app.respiral.ui.arrival.ArrivalScreen
import app.respiral.ui.library.LibraryScreen
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test

class ReflectionFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun arrival_uses_the_approved_two_primary_actions() {
        composeTestRule.setContent {
            ArrivalScreen(onRemindMe = {}, onAddEntry = {})
        }

        composeTestRule.onNodeWithText("Remind me who I am").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add something good").assertIsDisplayed()
    }

    @Test
    fun reflection_with_affirmation_filter_never_shows_achievement_only_entry() {
        val repository = FakeVaultRepository().apply {
            seed(sampleEntry(title = "Achievement", tags = setOf(VaultTag.ACHIEVEMENT)))
            seed(sampleEntry(title = "Affirmation", tags = setOf(VaultTag.AFFIRMATION)))
        }
        composeTestRule.setContent {
            ReflectionScreen(repository = repository, tags = setOf(VaultTag.AFFIRMATION), onBack = {})
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("reflection-entry").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Affirmation").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Achievement").assertCountEquals(0)
    }

    @Test
    fun library_search_filters_a_reverse_chronological_timeline() {
        val repository = FakeVaultRepository().apply {
            seed(sampleEntry(title = "Older kindness", createdAt = Instant.parse("2026-08-26T09:00:00Z")))
            seed(sampleEntry(title = "Newer kindness", createdAt = Instant.parse("2026-08-26T10:00:00Z")))
        }
        composeTestRule.setContent {
            LibraryScreen(repository = repository, onEntrySelected = {}, onReflect = {}, onBack = {})
        }

        composeTestRule.onNodeWithTag("library-search").performTextInput("kindness")
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("timeline-entry").fetchSemanticsNodes().size == 2
        }
        composeTestRule.onAllNodesWithTag("timeline-entry")[0].assertTextContains("Newer kindness")
    }
}

private class FakeVaultRepository : VaultRepository {
    private val entries = MutableStateFlow<List<VaultEntry>>(emptyList())

    fun seed(entry: VaultEntry) {
        entries.value = (entries.value.filterNot { it.id == entry.id } + entry)
    }

    override suspend fun save(entry: VaultEntry, pendingMedia: List<PendingMedia>): VaultEntry {
        seed(entry)
        return entry
    }

    override fun observeTimeline(query: String, tags: Set<VaultTag>): Flow<List<VaultEntrySummary>> = entries.map { all ->
        all.asSequence()
            .filter { entry ->
                query.isBlank() || entry.title.contains(query, ignoreCase = true) || entry.body.contains(query, ignoreCase = true)
            }
            .filter { entry -> tags.isEmpty() || entry.tags.any(tags::contains) }
            .sortedByDescending(VaultEntry::createdAt)
            .map { entry -> VaultEntrySummary(entry.id, entry.title, entry.createdAt, entry.tags) }
            .toList()
    }

    override suspend fun get(id: UUID): VaultEntry = entries.value.first { it.id == id }

    override suspend fun delete(id: UUID) {
        entries.value = entries.value.filterNot { it.id == id }
    }

    override suspend fun rebuildIndex() = Unit
}

private fun sampleEntry(
    title: String,
    createdAt: Instant = Instant.parse("2026-08-26T09:00:00Z"),
    tags: Set<VaultTag> = emptySet(),
): VaultEntry = VaultEntry(
    id = UUID.nameUUIDFromBytes(title.toByteArray()),
    title = title,
    body = "A private note.",
    createdAt = createdAt,
    updatedAt = createdAt,
    tags = tags,
    media = emptyList(),
)
