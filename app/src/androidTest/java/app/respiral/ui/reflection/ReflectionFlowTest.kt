package app.respiral.ui.reflection

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.index.EntryIndexEntity
import app.respiral.data.index.RespiralDatabase
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.LegacyVaultRecovery
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultDiagnosticCode
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultHealth
import app.respiral.data.vault.VaultRepository
import app.respiral.ui.arrival.ArrivalScreen
import app.respiral.ui.library.LibraryScreen
import app.respiral.ui.theme.RespiralTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ReflectionFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var recoveryFixtureRoot: File
    private lateinit var fileStore: VaultFileStore

    @Test
    fun arrival_uses_the_approved_two_primary_actions() {
        composeTestRule.setContent {
            ArrivalScreen(onRemindMe = {}, onAddEntry = {})
        }

        composeTestRule.onNodeWithText("Remind me who I am").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add something good").assertIsDisplayed()
    }

    @Test
    fun arrival_offers_a_route_to_browse_the_vault() {
        var browsed = false
        composeTestRule.setContent {
            ArrivalScreen(onRemindMe = {}, onAddEntry = {}, onBrowse = { browsed = true })
        }

        composeTestRule.onNodeWithTag("browse-vault").performClick()

        assertThat(browsed).isTrue()
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
    fun reflection_hides_the_gentle_card_when_no_note_remains_and_attention_is_needed() {
        val repository = FakeVaultRepository().apply {
            health.value = VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 1)
        }
        composeTestRule.setContent {
            ReflectionScreen(repository = repository, tags = emptySet(), onBack = {})
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Some notes need attention. Your original files have not been removed. Diagnostic: RSP-R02.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("Take this gently").assertCountEquals(0)
        composeTestRule.onNodeWithText("Do not uninstall Respiral or clear its app data.").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun legacy_index_only_note_is_recovered_and_reflection_displays_its_body() {
        val database = createOnDeviceLegacyDatabase()
        try {
            val repository = runBlocking {
                val row = legacyRow(title = "Still me", body = "I stayed kind under pressure.")
                database.entryIndexDao().upsert(row)
                assertThat(recoveryFixtureRoot.resolve("vault/entries/${row.id}.md").exists()).isFalse()
                val report = LegacyVaultRecovery(fileStore).recover(database.entryIndexDao().snapshot())
                assertThat(report.recoveredCount).isEqualTo(1)
                DefaultVaultRepository(fileStore).apply { refresh(report) }
            }

            composeTestRule.setContent {
                RespiralTheme { ReflectionScreen(repository, emptySet(), onBack = {}) }
            }

            composeTestRule.waitUntilAtLeastOneExists(hasText("I stayed kind under pressure."), 5_000)
            composeTestRule.onNodeWithText("I stayed kind under pressure.").assertIsDisplayed()
        } finally {
            database.close()
            recoveryFixtureRoot.deleteRecursively()
        }
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

    private fun createOnDeviceLegacyDatabase(): RespiralDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        recoveryFixtureRoot = context.cacheDir
            .resolve("reflection-legacy-recovery-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        fileStore = VaultFileStore(
            vaultRoot = recoveryFixtureRoot.resolve("vault"),
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = context.contentResolver::openInputStream,
        )
        return Room.databaseBuilder(
            context,
            RespiralDatabase::class.java,
            recoveryFixtureRoot.resolve("reflection-legacy.db").absolutePath,
        ).build()
    }
}

private fun legacyRow(title: String, body: String): EntryIndexEntity {
    val timestamp = Instant.parse("2026-08-28T07:00:00Z").toEpochMilli()
    return EntryIndexEntity(
        id = UUID.nameUUIDFromBytes("reflection-legacy-recovery".toByteArray()).toString(),
        title = title,
        bodyForSearch = body,
        createdAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
        tagNames = "|AFFIRMATION|",
    )
}

private class FakeVaultRepository : VaultRepository {
    private val entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    override val health = MutableStateFlow<VaultHealth>(VaultHealth.Healthy)

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
